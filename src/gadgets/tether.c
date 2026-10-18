/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `tether`: an f_ncm (or f_rndis) network function, plus a small daemon
 * that gives the host an address, NAT and a route out. The networking is
 * Droidspaces' (src/net/); this file only wires it to the gadget's
 * interface. See docs/research/04-tethering.md.
 */

#include "../net/net.h"
#include "usbarsenal.h"
#include <linux/rtnetlink.h>

#define NCM "ncm.ua"
#define RNDIS "rndis.ua"

/* Below Android's VPN rules (10000+) so tethered traffic never gets pulled
 * into a VPN, above the OEM reserved range (< 1000). Same values Droidspaces
 * uses; the selectors are our own subnet, so the two never collide. */
#define RULE_PRIO_TO 6090
#define RULE_PRIO_FROM 6100

#define TICK_SEC 2

struct tether {
  const char *upstream; /* --upstream, or NULL for automatic */
  const char *dns;      /* --dns, or NULL */
  char iface[IFNAMSIZ];
  uint32_t ip_be, net_be, mask_be, offer_be;
  uint8_t prefix;
  char cidr[32]; /* our subnet, for the NAT rules */
  int table;     /* uplink routing table the FROM rule points at, 0 = none */
  char uplink[IFNAMSIZ];
  char saved_forward[8];
  /* gateway mode: a container (OpenWrt) serves the host instead of us */
  int gateway;
  const char *gw_name, *gw_path, *gw_iface;
  pid_t gw_pid;
  ino_t gw_ino; /* the netns the cable is plugged into, 0 = none */
};

/* Gateway topology, after Droidspaces' gateway mode: usb0 never leaves our
 * namespace. It sits on a bridge with no address, and one veth runs from
 * that bridge into the container, its far end born there under its final
 * name. A container restart only takes the far end with it, and a gadget
 * rebind only gives usb0 a new identity; both are re-plugged each tick. */
#define GW_BRIDGE "ua-usb"
#define GW_VETH "ua-gw"

void ua_help_tether(void) {
  printf(
      "Usage: " UA_NAME " tether <action> [options]\n\n"
      "Share this device's internet connection with the host over USB.\n"
      "The host gets a network adapter, an address by DHCP, and a route out\n"
      "through whatever connection this device is using.\n\n"
      "Actions:\n"
      "  start   Add the network function and start sharing\n"
      "  stop    Stop sharing and remove the network function\n\n"
      "Options:\n"
      "      --rndis            Use RNDIS instead of NCM. NCM works out of\n"
      "                         the box on Windows 11, macOS and Linux;\n"
      "                         RNDIS is for Windows 7/8 and older hosts.\n"
      "                         Picked automatically if NCM is missing.\n"
      "  -u, --upstream IFACE   Share this interface instead of the one\n"
      "                         detected automatically (wlan0, rmnet_data0)\n"
      "  -a, --address CIDR     This side's address (default:\n"
      "                         192.168.42.129/24). The host is offered the\n"
      "                         next address.\n"
      "  -d, --dns IP[,IP]      DNS servers handed to the host (default:\n"
      "                         1.1.1.1,8.8.8.8)\n"
      "      --no-net           Only add the network function: no address,\n"
      "                         DHCP or NAT. For bringing your own setup.\n"
      "  -f, --foreground       Stay in the foreground instead of running\n"
      "                         the network side as a daemon\n"
      "  -G, --gateway NAME     Gateway mode: hand the link to a Droidspaces\n"
      "                         container (OpenWrt) instead of serving it\n"
      "                         here. No DHCP or NAT from us; the container\n"
      "                         provides them on its end of the cable.\n"
      "      --gateway-pid PID  Same, for any container with this init pid\n"
      "      --gateway-netns PATH  Same, given a network namespace file\n"
      "      --gateway-iface IFACE  Name of the cable inside the container\n"
      "                         (default: usb0)\n"
      "  -h, --help             Show this help\n\n"
      "Examples:\n"
      "  " UA_NAME " tether start\n"
      "  " UA_NAME " tether start --upstream wlan0 --dns 9.9.9.9\n"
      "  " UA_NAME " tether start --rndis\n"
      "  " UA_NAME " tether start --no-net\n"
      "  " UA_NAME " tether start --gateway OpenWrt\n"
      "  " UA_NAME " tether stop\n");
}

static void pid_path(char *out, size_t size) {
  ua_ws_path("Pids", "tether.pid", out, size);
}

static void info_path(char *out, size_t size) {
  ua_ws_path("Net", "tether.info", out, size);
}

/* The pid file names a live daemon only if that pid is still ours: a pid
 * reused by something else after a reboot must never get our SIGTERM */
static pid_t daemon_pid(void) {
  char p[PATH_MAX];
  pid_path(p, sizeof(p));
  return ua_read_our_pid(p);
}

void ua_tether_kill(void) {
  char p[PATH_MAX];
  pid_t pid = daemon_pid();
  if (pid > 0 && kill(pid, SIGTERM) == 0) {
    /* It removes its NAT rules on the way out; give it time to */
    for (int i = 0; i < 50 && kill(pid, 0) == 0; i++)
      usleep(100000);
    if (kill(pid, 0) == 0) {
      ua_warn("Tether daemon %d did not exit, killing it", (int)pid);
      kill(pid, SIGKILL);
    }
  }
  pid_path(p, sizeof(p));
  unlink(p);
  info_path(p, sizeof(p));
  unlink(p);
}

static int parse_address(const char *s, struct tether *t) {
  char ip[32];
  const char *slash = strchr(s, '/');
  size_t n = slash ? (size_t)(slash - s) : strlen(s);
  char *end = NULL;
  long prefix = slash ? strtol(slash + 1, &end, 10) : 24;
  if (slash && (end == slash + 1 || *end))
    return -1;
  struct in_addr a;
  if (n >= sizeof(ip) || prefix < 8 || prefix > 30)
    return -1;
  memcpy(ip, s, n);
  ip[n] = '\0';
  if (inet_pton(AF_INET, ip, &a) != 1)
    return -1;

  uint32_t ip_h = ntohl(a.s_addr);
  uint32_t mask_h = 0xffffffffu << (32 - prefix);
  uint32_t net_h = ip_h & mask_h, bcast_h = net_h | ~mask_h;
  if (ip_h == net_h || ip_h == bcast_h)
    return -1;
  uint32_t offer_h = ip_h + 1 < bcast_h ? ip_h + 1 : ip_h - 1;
  if (offer_h == net_h)
    return -1;

  t->ip_be = a.s_addr;
  t->mask_be = htonl(mask_h);
  t->net_be = htonl(net_h);
  t->offer_be = htonl(offer_h);
  t->prefix = (uint8_t)prefix;
  struct in_addr na = {t->net_be};
  snprintf(t->cidr, sizeof(t->cidr), "%s/%ld", inet_ntoa(na), prefix);
  return 0;
}

/* Fixed MACs per device: random ones make Windows add "Ethernet 7, 8, 9..."
 * every time tethering starts. Locally administered, derived from something
 * stable on this machine. */
static void stable_macs(char *dev, char *host, size_t size) {
  char seed[128] = "";
  if (is_android())
    ua_getprop("ro.serialno", seed, sizeof(seed));
  if (!seed[0])
    read_file("/etc/machine-id", seed, sizeof(seed));
  if (!seed[0])
    safe_strncpy(seed, UA_NAME, sizeof(seed));
  uint32_t h = 2166136261u;
  for (const char *p = seed; *p; p++)
    h = (h ^ (uint8_t)*p) * 16777619u;
  snprintf(dev, size, "02:55:%02x:%02x:%02x:%02x", h >> 24, (h >> 16) & 0xff,
           (h >> 8) & 0xff, h & 0xff);
  snprintf(host, size, "02:48:%02x:%02x:%02x:%02x", h >> 24, (h >> 16) & 0xff,
           (h >> 8) & 0xff, h & 0xff);
}

static int write_in(const char *dir, const char *attr, const char *val) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/%s", dir, attr);
  return write_file(p, val);
}

/* Windows only loads its RNDIS driver unprompted when the device answers the
 * Microsoft OS descriptor request with compatible ID "RNDIS" */
static void rndis_os_desc(const struct ua_gadget *g, const char *fdir) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/os_desc", g->path);
  write_in(p, "use", "1");
  write_in(p, "b_vendor_code", "0xcd");
  write_in(p, "qw_sign", "MSFT100");
  snprintf(p, sizeof(p), "%s/os_desc/interface.rndis", fdir);
  write_in(p, "compatible_id", "RNDIS");
  write_in(p, "sub_compatible_id", "5162001");
  snprintf(p, sizeof(p), "%s/os_desc/" UA_CONFIG, g->path);
  if (symlink(g->config, p) < 0 && errno != EEXIST)
    ua_debug("os_desc link: %s", strerror(errno));
}

static const char *live_func(const struct ua_gadget *g) {
  return ua_gadget_has(g, NCM) ? NCM : ua_gadget_has(g, RNDIS) ? RNDIS : NULL;
}

static int read_ifname(const struct ua_gadget *g, const char *func, char *out) {
  char dir[PATH_MAX], p[PATH_MAX + 8], buf[64];
  ua_gadget_func_dir(g, func, dir, sizeof(dir));
  snprintf(p, sizeof(p), "%s/ifname", dir);
  /* Old kernels say "(unnamed net_device)" until the function is bound */
  if (read_file(p, buf, sizeof(buf)) <= 0 || buf[0] == '(' ||
      strlen(buf) >= IFNAMSIZ)
    return -1;
  safe_strncpy(out, buf, IFNAMSIZ);
  return 0;
}

static void write_info(const struct tether *t) {
  char p[PATH_MAX];
  struct in_addr a = {t->ip_be};
  info_path(p, sizeof(p));
  FILE *f = fopen(p, "we");
  if (!f)
    return;
  if (t->gateway)
    fprintf(f, "%s bridged to %s as %s%s", t->iface,
            t->gw_name ? t->gw_name : "the container", t->gw_iface,
            t->gw_ino ? "" : " (waiting for it to run)");
  else
    fprintf(f, "%s %s/%u, sharing %s", t->iface, inet_ntoa(a), t->prefix,
            t->uplink[0] ? t->uplink : "(no uplink yet)");
  fclose(f);
}

static void no_ipv6(const char *ifname) {
  char p[128];
  /* Android leaves accept_ra=2 everywhere: a port with no address would
   * pick up one, and a default route, from the container's RAs */
  snprintf(p, sizeof(p), "/proc/sys/net/ipv6/conf/%s/disable_ipv6", ifname);
  write_file(p, "1");
}

/* Android answers a fresh link's first master or up with a transient EPERM;
 * VirtualAP's six tries 300 ms apart cover it */
static int plug(ds_nl_ctx_t *ctx, const char *ifname, const char *master) {
  for (int i = 0; i < 6; i++) {
    if ((!master || ds_nl_set_master(ctx, ifname, master) == 0) &&
        ds_nl_link_up(ctx, ifname) == 0)
      return 0;
    usleep(300000);
  }
  return -1;
}

static int usb_on_bridge(const struct tether *t) {
  char p[128], target[PATH_MAX];
  snprintf(p, sizeof(p), "/sys/class/net/%s/master", t->iface);
  ssize_t n = readlink(p, target, sizeof(target) - 1);
  if (n <= 0)
    return 0;
  target[n] = '\0';
  const char *b = strrchr(target, '/');
  return !strcmp(b ? b + 1 : target, GW_BRIDGE);
}

static void gw_mac(const char *key, uint8_t mac[6]) {
  uint32_t h = 2166136261u;
  for (const char *k = key; *k; k++)
    h = (h ^ (uint8_t)*k) * 16777619u;
  mac[0] = 0x02;
  mac[1] = 0x55;
  mac[2] = (uint8_t)(h >> 24);
  mac[3] = (uint8_t)(h >> 16);
  mac[4] = (uint8_t)(h >> 8);
  mac[5] = (uint8_t)h;
}

/* Keep the cable plugged into the container's current namespace. A host end
 * whose far end lives in another (dead) namespace is deleted: it would pin
 * that namespace and lead nowhere. */
static void gw_wire(struct tether *t, int quiet) {
  char path[64];
  struct stat st;
  if (ua_container_netns(t->gw_name, t->gw_pid, t->gw_path, path,
                         sizeof(path)) < 0 ||
      stat(path, &st) < 0) {
    if (!quiet || t->gw_ino)
      ua_warn("[NET] The gateway container is not running; the cable goes in "
              "when it starts");
    t->gw_ino = 0;
    write_info(t);
    return;
  }
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  int have = ds_nl_link_exists(ctx, GW_VETH);
  if (have && st.st_ino == t->gw_ino && ds_netns_has_link(path, t->gw_iface)) {
    ds_nl_close(ctx);
    return;
  }
  if (have)
    ds_nl_del_link(ctx, GW_VETH);

  uint8_t mac[6];
  gw_mac(t->gw_iface, mac);
  int fd = ds_open_container_netns(path);
  int r = fd >= 0 ? ds_nl_create_veth_in(ctx, GW_VETH, t->gw_iface, fd, mac)
                  : -errno;
  if (fd >= 0)
    close(fd);
  if (r < 0) {
    ua_warn("[NET] Cannot plug %s into the container: %s%s", t->gw_iface,
            strerror(-r),
            r == -EEXIST ? " (an interface by that name already exists "
                           "there; pick another with --gateway-iface)"
                         : "");
    t->gw_ino = 0;
  } else {
    no_ipv6(GW_VETH);
    if (plug(ctx, GW_VETH, GW_BRIDGE) < 0 ||
        ds_netns_link_up(path, t->gw_iface) < 0)
      ua_warn("[NET] The cable is in but not up yet");
    t->gw_ino = st.st_ino;
    ua_log("Plugged %s into the container as %s", t->iface, t->gw_iface);
  }
  ds_nl_close(ctx);
  write_info(t);
}

static int gw_up(struct tether *t) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx) {
    ua_error("Cannot open a netlink socket: %s", strerror(errno));
    return -1;
  }
  uint8_t mac[6];
  int r = ds_nl_create_bridge(ctx, GW_BRIDGE);
  if (r < 0) {
    ua_error("Cannot create bridge " GW_BRIDGE ": %s", strerror(-r));
    ds_nl_close(ctx);
    return -1;
  }
  /* A bridge with no address borrows its lowest port MAC, so it would
   * change whenever the container restarts and the far side's ARP goes
   * stale. A MAC set by us is exempt. */
  gw_mac(GW_BRIDGE, mac);
  ds_nl_set_mac(ctx, GW_BRIDGE, mac);
  no_ipv6(GW_BRIDGE);
  write_file("/sys/class/net/" GW_BRIDGE "/bridge/nf_call_iptables", "0");
  write_file("/sys/class/net/" GW_BRIDGE "/bridge/nf_call_ip6tables", "0");
  no_ipv6(t->iface);
  if (plug(ctx, GW_BRIDGE, NULL) < 0 || plug(ctx, t->iface, GW_BRIDGE) < 0) {
    ua_error("Cannot put %s on bridge " GW_BRIDGE, t->iface);
    ds_nl_close(ctx);
    return -1;
  }
  ds_nl_close(ctx);
  gw_wire(t, 0);
  return 0;
}

static void gw_tick(struct tether *t) {
  /* M62-style kernels free usb0 on unbind and make a new one on bind */
  if (!usb_on_bridge(t)) {
    ds_nl_ctx_t *ctx = ds_nl_open();
    if (ctx) {
      no_ipv6(t->iface);
      if (plug(ctx, t->iface, GW_BRIDGE) == 0)
        ua_log("[NET] %s back on " GW_BRIDGE, t->iface);
      ds_nl_close(ctx);
    }
  }
  gw_wire(t, 1);
}

static void gw_down(void) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  /* The host end goes first: it pins the container's namespace */
  ds_nl_del_link(ctx, GW_VETH);
  ds_nl_del_link(ctx, GW_BRIDGE);
  ds_nl_close(ctx);
}

/* Point "from our subnet" at the uplink's routing table. Android only: there
 * every network has its own table and nothing routes our subnet by default.
 * On plain Linux the main table already does. */
static void sync_uplink(struct tether *t, int quiet) {
  char iface[IFNAMSIZ];
  int table = 0;
  const char *how = ua_find_uplink(t->upstream, iface, &table);
  if (!how) {
    /* Once per loss, not every tick: this goes to tether.log */
    if (!quiet || t->uplink[0])
      ua_warn("[NET] No internet connection to share yet; the host gets a "
              "link but no route out until one comes up");
    t->uplink[0] = '\0';
    iface[0] = '\0';
    table = t->table; /* leave the rule alone on a transient loss */
  }
  if (strcmp(iface, t->uplink) != 0 && how) {
    ua_log("Sharing %s (%s)", iface, how);
    safe_strncpy(t->uplink, iface, sizeof(t->uplink));
  }
  if (!is_android() || table == t->table) {
    write_info(t);
    return;
  }

  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  if (t->table > 0)
    ds_nl_del_rule4(ctx, t->net_be, t->prefix, 0, 0, t->table, RULE_PRIO_FROM);
  if (table > 0 && ds_nl_add_rule4(ctx, t->net_be, t->prefix, 0, 0, table,
                                   RULE_PRIO_FROM) < 0)
    ua_warn("[NET] Cannot add the route rule for table %d", table);
  t->table = table;
  ds_nl_close(ctx);
  write_info(t);
}

static int net_up(struct tether *t) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx) {
    ua_error("Cannot open a netlink socket: %s", strerror(errno));
    return -1;
  }
  int ret = ds_nl_link_up(ctx, t->iface);
  int addr = ds_nl_add_addr4(ctx, t->iface, t->ip_be, t->prefix);
  if (ret < 0 || (addr < 0 && addr != -EEXIST)) {
    ua_error("Cannot bring up %s: %s", t->iface,
             strerror(-(ret < 0 ? ret : addr)));
    ds_nl_close(ctx);
    return -1;
  }
  /* Replies to the host must find our connected route in main, whatever
   * table netd would otherwise send them to */
  if (is_android())
    ds_nl_add_rule4(ctx, 0, 0, t->net_be, t->prefix, RT_TABLE_MAIN,
                    RULE_PRIO_TO);
  ds_nl_close(ctx);

  read_file("/proc/sys/net/ipv4/ip_forward", t->saved_forward,
            sizeof(t->saved_forward));
  write_file("/proc/sys/net/ipv4/ip_forward", "1");

  if (ds_ipt_ensure_masquerade(AF_INET, t->cidr) < 0 ||
      ds_ipt_ensure_forward_accept(AF_INET, t->iface) < 0) {
    ua_error("Cannot install the NAT rules for %s", t->cidr);
    return -1;
  }
  ds_ipt_ensure_input_accept(AF_INET, t->iface);
  /* A mobile uplink's MTU is often below the 1500 the host assumes */
  ds_ipt_ensure_mss_clamp(AF_INET);

  sync_uplink(t, 0);
  ds_dhcp_server_start(t->dns, t->iface, t->offer_be, t->ip_be, t->mask_be,
                       NULL);
  return 0;
}

static void net_down(struct tether *t) {
  ds_dhcp_server_stop();
  ds_ipt_remove_nat_rules(AF_INET, t->cidr, t->iface);
  if (is_android()) {
    ds_nl_ctx_t *ctx = ds_nl_open();
    if (ctx) {
      ds_nl_del_rule4(ctx, 0, 0, t->net_be, t->prefix, RT_TABLE_MAIN,
                      RULE_PRIO_TO);
      if (t->table > 0)
        ds_nl_del_rule4(ctx, t->net_be, t->prefix, 0, 0, t->table,
                        RULE_PRIO_FROM);
      ds_nl_close(ctx);
    }
  } else if (t->saved_forward[0]) {
    /* On Android netd owns ip_forward and Android's own tethering may need
     * it by now, so only plain Linux gets its old value back */
    write_file("/proc/sys/net/ipv4/ip_forward", t->saved_forward);
  }
}

/* The network side lives as long as this loop. It also re-asserts what
 * Android likes to undo behind our back: ip_forward, and the uplink rule when
 * the default network moves between Wi-Fi and mobile data. */
static int serve(struct tether *t, int ready_fd) {
  sigset_t set;
  sigemptyset(&set);
  sigaddset(&set, SIGTERM);
  sigaddset(&set, SIGINT);
  sigaddset(&set, SIGHUP);
  sigprocmask(SIG_BLOCK, &set, NULL);

  int ok = (t->gateway ? gw_up(t) : net_up(t)) == 0;
  if (ready_fd >= 0) {
    char c = ok ? '1' : '0';
    if (write(ready_fd, &c, 1) < 0)
      ua_debug("ready pipe: %s", strerror(errno));
    close(ready_fd);
  }
  if (!ok) {
    if (t->gateway)
      gw_down();
    else
      net_down(t);
    return -1;
  }

  struct timespec tick = {TICK_SEC, 0};
  while (sigtimedwait(&set, NULL, &tick) < 0) {
    char fwd[8];
    if (t->gateway) {
      gw_tick(t);
      continue;
    }
    if (is_android() &&
        read_file("/proc/sys/net/ipv4/ip_forward", fwd, sizeof(fwd)) > 0 &&
        fwd[0] == '0')
      write_file("/proc/sys/net/ipv4/ip_forward", "1");
    sync_uplink(t, 1);
  }
  ua_log("[NET] Stopping tethering on %s", t->iface);
  if (t->gateway)
    gw_down();
  else
    net_down(t);
  return 0;
}

/* Fork the network side off and wait for it to say whether it came up, so
 * the user sees a failure here and not in a log they never read */
static int spawn_daemon(struct tether *t) {
  int pfd[2];
  if (pipe(pfd) < 0)
    return -1;
  pid_t pid = fork();
  if (pid < 0)
    return -1;
  if (pid == 0) {
    close(pfd[0]);
    setsid();
    if (chdir("/") < 0)
      _exit(1);
    char p[PATH_MAX];
    ua_ws_path("Logs", "tether.log", p, sizeof(p));
    int log = open(p, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    int null = open("/dev/null", O_RDONLY);
    if (null >= 0)
      dup2(null, STDIN_FILENO);
    if (log >= 0) {
      dup2(log, STDOUT_FILENO);
      dup2(log, STDERR_FILENO);
    }
    FILE *f;
    pid_path(p, sizeof(p));
    if ((f = fopen(p, "we"))) {
      fprintf(f, "%d\n", (int)getpid());
      fclose(f);
    }
    int ret = serve(t, pfd[1]);
    unlink(p);
    info_path(p, sizeof(p));
    unlink(p);
    _exit(ret == 0 ? 0 : 1);
  }
  close(pfd[1]);
  char c = '0';
  ssize_t r;
  while ((r = read(pfd[0], &c, 1)) < 0 && errno == EINTR)
    ;
  close(pfd[0]);
  return (r == 1 && c == '1') ? 0 : -1;
}

/* A failed start takes out the function it added, so the gadget is left as
 * it was found */
static int undo(struct ua_gadget *g, const char *func, int added) {
  if (added && ua_gadget_begin(g) == 0) {
    ua_gadget_remove(g, func);
    ua_gadget_commit(g);
  }
  return UA_EXIT_FAIL;
}

static int do_start(struct tether *t, int rndis, int no_net, int fg) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_NET) < 0)
    return UA_EXIT_FAIL;
  if (!no_net && !t->gateway && !ds_ipt4_nat_available()) {
    ua_error("This kernel cannot NAT (no iptables nat table or MASQUERADE)");
    ua_log("Use --no-net to only add the network function.");
    return UA_EXIT_FAIL;
  }
  if (daemon_pid() > 0) {
    ua_error("Tethering is already running; '" UA_NAME " tether stop' first");
    return UA_EXIT_FAIL;
  }

  const char *func = live_func(&g);
  int added = 0;
  if (!func || !ua_gadget_live(&g)) {
    /* A function already there (linked, gadget not live) is kept as is */
    if (!func)
      func = (rndis || !ua_probe_function(&g, "ncm", NULL)) ? RNDIS : NCM;
    char dir[PATH_MAX], dev[32], host[32];
    ua_gadget_func_dir(&g, func, dir, sizeof(dir));
    int existed = access(dir, F_OK) == 0;
    if (ua_gadget_begin(&g) < 0)
      return UA_EXIT_FAIL;
    /* dev_addr and host_addr are EBUSY while the function is linked */
    ua_gadget_unlink(&g, func);
    if (ua_gadget_add(&g, func, dir, sizeof(dir)) < 0) {
      ua_gadget_commit(&g);
      return UA_EXIT_FAIL;
    }
    stable_macs(dev, host, sizeof(dev));
    write_in(dir, "dev_addr", dev);
    write_in(dir, "host_addr", host);
    if (!strcmp(func, RNDIS) && !g.piggyback)
      rndis_os_desc(&g, dir);
    if (ua_gadget_commit(&g) < 0)
      return UA_EXIT_FAIL;
    added = !existed;
  }

  /* The netdev is registered at bind; give a slow kernel a moment */
  for (int i = 0; i < 20 && read_ifname(&g, func, t->iface) < 0; i++)
    usleep(100000);
  if (!t->iface[0]) {
    ua_error("The %s function has no network interface", func);
    return undo(&g, func, added);
  }
  ua_log("%s is up on %s", !strcmp(func, NCM) ? "NCM" : "RNDIS", t->iface);
  if (no_net)
    return UA_EXIT_OK;
  /* The kernel creates the interface in the initial network namespace. A
   * container with its own (Droidspaces NAT or gateway mode) cannot see it,
   * so nothing below could work from here. */
  if (if_nametoindex(t->iface) == 0) {
    ua_error("%s is not in this network namespace (running inside a "
             "container?)",
             t->iface);
    ua_log("Run 'tether start' from the host namespace, or use --no-net and "
           "configure %s there.",
           t->iface);
    return undo(&g, func, added);
  }

  if (fg) {
    /* A pid file, so `stop` from another shell reaches us too */
    char p[PATH_MAX];
    pid_path(p, sizeof(p));
    FILE *f = fopen(p, "we");
    if (f) {
      fprintf(f, "%d\n", (int)getpid());
      fclose(f);
    }
    ua_log("Sharing on %s; Ctrl-C to stop", t->iface);
    int r = serve(t, -1);
    unlink(p);
    info_path(p, sizeof(p));
    unlink(p);
    return r == 0 ? UA_EXIT_OK : undo(&g, func, added);
  }
  if (spawn_daemon(t) < 0) {
    char p[PATH_MAX];
    ua_ws_path("Logs", "tether.log", p, sizeof(p));
    ua_error("The network side did not come up; see %s", p);
    return undo(&g, func, added);
  }
  if (t->gateway) {
    ua_log("%s is bridged to the container as %s; it serves the host", t->iface,
           t->gw_iface);
    return UA_EXIT_OK;
  }
  struct in_addr a = {t->ip_be}, o = {t->offer_be};
  char ip[16];
  safe_strncpy(ip, inet_ntoa(a), sizeof(ip));
  ua_log("Host gets %s by DHCP, gateway %s", inet_ntoa(o), ip);
  return UA_EXIT_OK;
}

static int do_stop(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  ua_tether_kill();
  if (!live_func(&g)) {
    ua_log("Tethering is not running");
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, NCM);
  ua_gadget_remove(&g, RNDIS);
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_log("Tethering stopped");
  return UA_EXIT_OK;
}

void ua_status_tether(const struct ua_gadget *g) {
  const char *func = live_func(g);
  char iface[IFNAMSIZ] = "", p[PATH_MAX], info[256] = "";
  if (!func)
    return;
  read_ifname(g, func, iface);
  info_path(p, sizeof(p));
  if (daemon_pid() > 0 && read_file(p, info, sizeof(info)) > 0)
    printf("Tether:     %s: %s\n", !strcmp(func, NCM) ? "ncm" : "rndis", info);
  else
    printf("Tether:     %s on %s (no network side running)\n",
           !strcmp(func, NCM) ? "ncm" : "rndis", iface[0] ? iface : "?");
}

int ua_cmd_tether(int argc, char **argv) {
  enum { OPT_RNDIS = 256, OPT_NO_NET, OPT_GW_PID, OPT_GW_NETNS, OPT_GW_IFACE };
  static const struct option opts[] = {
      {"rndis", no_argument, NULL, OPT_RNDIS},
      {"no-net", no_argument, NULL, OPT_NO_NET},
      {"gateway", required_argument, NULL, 'G'},
      {"gateway-pid", required_argument, NULL, OPT_GW_PID},
      {"gateway-netns", required_argument, NULL, OPT_GW_NETNS},
      {"gateway-iface", required_argument, NULL, OPT_GW_IFACE},
      {"upstream", required_argument, NULL, 'u'},
      {"address", required_argument, NULL, 'a'},
      {"dns", required_argument, NULL, 'd'},
      {"foreground", no_argument, NULL, 'f'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  struct tether t;
  int rndis = 0, no_net = 0, fg = 0, c;
  memset(&t, 0, sizeof(t));
  parse_address("192.168.42.129/24", &t);
  t.gw_iface = "usb0";

  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_tether();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":u:a:d:fG:h", opts, NULL)) != -1) {
    switch (c) {
    case OPT_RNDIS:
      rndis = 1;
      break;
    case OPT_NO_NET:
      no_net = 1;
      break;
    case 'G':
      t.gateway = 1;
      t.gw_name = optarg;
      break;
    case OPT_GW_PID: {
      char *end;
      long v = strtol(optarg, &end, 10);
      if (!*optarg || *end || v <= 0) {
        ua_usage_error("tether", "--gateway-pid takes a process id");
        return UA_EXIT_USAGE;
      }
      t.gateway = 1;
      t.gw_pid = (pid_t)v;
      break;
    }
    case OPT_GW_NETNS:
      t.gateway = 1;
      t.gw_path = optarg;
      break;
    case OPT_GW_IFACE:
      if (!*optarg || strlen(optarg) >= IFNAMSIZ) {
        ua_usage_error("tether", "Bad interface name '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      t.gw_iface = optarg;
      break;
    case 'u':
      if (strlen(optarg) >= IFNAMSIZ) {
        ua_usage_error("tether", "Interface name too long: %s", optarg);
        return UA_EXIT_USAGE;
      }
      t.upstream = optarg;
      break;
    case 'a':
      if (parse_address(optarg, &t) < 0) {
        ua_usage_error("tether",
                       "--address takes a host address with a /8../30 "
                       "prefix, like 192.168.42.129/24");
        return UA_EXIT_USAGE;
      }
      break;
    case 'd':
      t.dns = optarg;
      break;
    case 'f':
      fg = 1;
      break;
    case 'h':
      ua_help_tether();
      return UA_EXIT_OK;
    case ':':
      ua_usage_error("tether", "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error("tether", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  if (argc - optind != 1) {
    ua_usage_error("tether",
                   argc - optind ? "Too many arguments" : "Missing action");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(argv[optind], "start"))
    return do_start(&t, rndis, no_net, fg);
  if (!strcmp(argv[optind], "stop"))
    return do_stop();
  ua_usage_error("tether", "Unknown action '%s'", argv[optind]);
  return UA_EXIT_USAGE;
}
