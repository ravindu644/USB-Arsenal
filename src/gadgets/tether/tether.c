/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `tether`: an f_ncm (or f_rndis) network function, plus a small daemon
 * that gives the host an address, NAT and a route out. The networking is
 * Droidspaces' (src/net/); tether/ only wires it to the gadget's
 * interface. This file is the command and the function; routed.c serves
 * the host, gateway.c hands the link to a container, and monitor.c keeps
 * either going. See docs/developer/research/04-tethering.md.
 */

#include "net/net.h"
#include "usbarsenal.h"
#include <linux/rtnetlink.h>
#include <sys/signalfd.h>

#include "internal.h"

#define NCM UA_F_NCM
#define RNDIS UA_F_RNDIS

void ua_help_tether(void) {
  printf(
      "Usage: " UA_NAME " tether <action> [options]\n\n"
      "Share this device's internet connection with the host over USB.\n"
      "The host gets a network adapter, an address by DHCP, and a route out\n"
      "through whatever connection this device is using.\n\n"
      "Actions:\n"
      "  start   Add the network function and start sharing. The options\n"
      "          given are saved as the setup (see `setup`); with none, the\n"
      "          saved setup starts.\n"
      "  apply   Restart with the setup if it changed since the start, in\n"
      "          one replug; start if not running\n"
      "  stop    Stop sharing and remove the network function\n\n"
      "Options:\n"
      "      --rndis            Use RNDIS instead of NCM. NCM works out of\n"
      "                         the box on Windows 11, macOS and Linux;\n"
      "                         RNDIS is for Windows 7/8 and older hosts.\n"
      "                         Picked automatically if NCM is missing.\n"
      "      --ncm              Use NCM even if the profile says RNDIS\n"
      "  -u, --upstream IFACE   Share this interface instead of the one\n"
      "                         detected automatically (wlan0, rmnet_data0)\n"
      "  -a, --address CIDR     This side's address (default:\n"
      "                         192.168.42.129/24). The host is offered the\n"
      "                         next address.\n"
      "  -d, --dns IP[,IP]      DNS servers handed to the host (default:\n"
      "                         1.1.1.1,8.8.8.8)\n"
      "      --no-net           Only add the network function: no address,\n"
      "                         DHCP or NAT. For bringing your own setup.\n"
      "                         Forgets a saved gateway.\n"
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

/* A locally administered 02:55 MAC, FNV-1a hashed from key */
void gw_mac(const char *key, uint8_t mac[6]) {
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

/* Fixed MACs per device: random ones make Windows add "Ethernet 7, 8, 9..."
 * every time tethering starts. Locally administered, derived from something
 * stable on this machine. */
static void stable_macs(char *dev, char *host, size_t size) {
  char seed[128];
  uint8_t m[6];
  ua_device_seed(seed, sizeof(seed));
  gw_mac(seed, m);
  snprintf(dev, size, "02:55:%02x:%02x:%02x:%02x", m[2], m[3], m[4], m[5]);
  snprintf(host, size, "02:48:%02x:%02x:%02x:%02x", m[2], m[3], m[4], m[5]);
}

/* Windows only loads its RNDIS driver unprompted when the device answers the
 * Microsoft OS descriptor request with compatible ID "RNDIS" */
static void rndis_os_desc(const struct ua_gadget *g, const char *fdir) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/os_desc", g->path);
  write_attr(p, "use", "1");
  write_attr(p, "b_vendor_code", "0xcd");
  write_attr(p, "qw_sign", "MSFT100");
  snprintf(p, sizeof(p), "%s/os_desc/interface.rndis", fdir);
  write_attr(p, "compatible_id", "RNDIS");
  write_attr(p, "sub_compatible_id", "5162001");
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

/* What the running daemon is doing, as key=value lines: status prints them as
 * text or JSON. A key with no value is left out. */
void write_info(const struct tether *t) {
  char p[PATH_MAX];
  struct in_addr a = {t->ip_be};
  info_path(p, sizeof(p));
  FILE *f = fopen(p, "we");
  if (!f)
    return;
  fprintf(f, "iface=%s\n", t->iface);
  if (t->gateway) {
    fprintf(f, "gateway=%s\ngateway_iface=%s\ngateway_waiting=%d\n",
            t->gw_name ? t->gw_name : "", t->gw_iface, !t->gw_ino);
  } else {
    fprintf(f, "address=%s/%u\n", inet_ntoa(a), t->prefix);
    if (t->nat6)
      fprintf(f, "ipv6=" NAT6_CIDR "\n");
    if (t->uplink[0])
      fprintf(f, "upstream=%s\n", t->uplink);
  }
  fclose(f);
}

/* One value from tether.info, "" when the key is not there */
static void info_get(const char *info, const char *key, char *out,
                     size_t size) {
  size_t len = strlen(key);
  out[0] = '\0';
  for (const char *l = info; *l;) {
    const char *end = strchrnul(l, '\n');
    if ((size_t)(end - l) > len && !strncmp(l, key, len) && l[len] == '=') {
      snprintf(out, size, "%.*s", (int)(end - l - len - 1), l + len + 1);
      return;
    }
    l = *end ? end + 1 : end;
  }
}

/* serve() under a pid file, so `stop` from another shell reaches it too */
static int serve_owned(struct tether *t, int ready_fd) {
  char p[PATH_MAX];
  FILE *f;
  pid_path(p, sizeof(p));
  if ((f = fopen(p, "we"))) {
    fprintf(f, "%d\n", (int)getpid());
    fclose(f);
  }
  int ret = serve(t, ready_fd);
  unlink(p);
  info_path(p, sizeof(p));
  unlink(p);
  return ret;
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
    /* Every line goes to the one log through ua_log; the terminal is gone */
    int null = open("/dev/null", O_RDWR);
    if (null >= 0) {
      dup2(null, STDIN_FILENO);
      dup2(null, STDOUT_FILENO);
      dup2(null, STDERR_FILENO);
    }
    _exit(serve_owned(t, pfd[1]) == 0 ? 0 : 1);
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
  /* The far end is born under its name; one already there (VirtualAP's
   * managed hotspot makes vaplan0) fails every plug, so refuse before the
   * gadget is touched. A stopped container is fine: the cable goes in when
   * it starts */
  char ns[64];
  if (t->gateway)
    drop_host_end();
  if (t->gateway &&
      ua_container_netns(t->gw_name, t->gw_pid, t->gw_path, ns, sizeof(ns)) ==
          0 &&
      ds_netns_has_link(ns, t->gw_iface)) {
    ua_error("%s already exists inside the container; give the cable another "
             "name (--gateway-iface)",
             t->gw_iface);
    return UA_EXIT_FAIL;
  }

  const char *func = live_func(&g);
  int added = 0;
  if (!func || !ua_gadget_live(&g)) {
    if (!rndis && ua_ncm_unsafe())
      ua_log("NCM would crash this Samsung kernel (see 'check'); using RNDIS");
    func = (rndis || !ua_probe_function(&g, "ncm", NULL)) ? RNDIS : NCM;
    char dir[PATH_MAX], dev[32], host[32], other[PATH_MAX];
    ua_gadget_func_dir(&g, func, dir, sizeof(dir));
    if (ua_gadget_begin(&g) < 0)
      return UA_EXIT_FAIL;
    /* With the gadget not live, a tether function already here is what a
     * crash left. The other kind would be linked beside ours at commit, so it
     * goes; ours is reused, and either way a failed start removes it. */
    const char *alt = strcmp(func, NCM) ? NCM : RNDIS;
    ua_gadget_func_dir(&g, alt, other, sizeof(other));
    if (access(other, F_OK) == 0)
      ua_gadget_remove(&g, alt);
    /* dev_addr and host_addr are EBUSY while the function is linked */
    ua_gadget_unlink(&g, func);
    if (ua_gadget_add(&g, func, dir, sizeof(dir)) < 0) {
      ua_gadget_commit(&g);
      return UA_EXIT_FAIL;
    }
    stable_macs(dev, host, sizeof(dev));
    if (t->dev_mac[0])
      safe_strncpy(dev, t->dev_mac, sizeof(dev));
    if (t->host_mac[0])
      safe_strncpy(host, t->host_mac, sizeof(host));
    /* Samsung's 4.x f_ncm creates its netdev only at bind, and its
     * u_ether_configfs stores dereference that NULL netdev: writing dev_addr
     * to an unbound instance panics the kernel (the f_ncm patch in
     * kernel-patches/ fixes it). The show side has always checked, so a read
     * that fails means there is nothing to set the address on yet. Mainline
     * creates the netdev with the instance, and the read just succeeds. */
    char cur[PATH_MAX + 16], mac[32];
    snprintf(cur, sizeof(cur), "%s/dev_addr", dir);
    if (read_file(cur, mac, sizeof(mac)) > 0) {
      write_attr(dir, "dev_addr", dev);
      write_attr(dir, "host_addr", host);
    } else {
      ua_debug("%s has no netdev before bind; the kernel picks the MACs", func);
    }
    if (!strcmp(func, RNDIS))
      rndis_os_desc(&g, dir);
    if (ua_gadget_commit(&g) < 0)
      return UA_EXIT_FAIL;
    added = 1;
  }

  /* The netdev is registered at bind; give a slow kernel a moment */
  for (int i = 0; i < 20 && read_ifname(&g, func, t->iface) < 0; i++)
    usleep(100000);
  if (!t->iface[0]) {
    ua_error("The %s function has no network interface", func);
    return undo(&g, func, added);
  }
  ua_log("%s is up on %s", !strcmp(func, NCM) ? "NCM" : "RNDIS", t->iface);
  if (no_net) {
    ua_running_take(UA_FN_TETHER, 0);
    return UA_EXIT_OK;
  }
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
    ua_log("Sharing on %s; Ctrl-C to stop", t->iface);
    ua_running_take(UA_FN_TETHER, 0);
    return serve_owned(t, -1) == 0 ? UA_EXIT_OK : undo(&g, func, added);
  }
  if (spawn_daemon(t) < 0) {
    ua_error("The network side did not come up; see the log");
    return undo(&g, func, added);
  }
  ua_running_take(UA_FN_TETHER, 0);
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
  ua_running_clear(UA_FN_TETHER);
  return UA_EXIT_OK;
}

/* Bring tethering to its setup. Running and unchanged: nothing. Changed: the
 * function is swapped inside one critical section: one replug of the whole
 * gadget (configfs swaps a function only unbound, so every function
 * reconnects), but the port never goes back to Android between the stop and
 * the start. Not running: a start. */
static int do_apply(struct tether *t, int rndis, int no_net) {
  struct ua_setup s, r;
  struct ua_gadget g;
  ua_setup_load(&s);
  ua_running_load(&r);
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  if (!(live_func(&g) && ua_gadget_live(&g)))
    return do_start(t, rndis, no_net, 0);
  if (!ua_setup_pending(UA_FN_TETHER, &s, &r)) {
    ua_log("Tethering already runs its setup");
    return UA_EXIT_OK;
  }
  ua_tether_kill();
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, NCM);
  ua_gadget_remove(&g, RNDIS);
  int ret = do_start(t, rndis, no_net, 0);
  /* do_start committed (and left the lock once) when it got that far; a
   * failure before that leaves the gadget unbound, so bind what is left */
  if (ua_gadget_live(&g))
    ua_crit_leave();
  else
    ua_gadget_commit(&g);
  return ret;
}

/* What the link has carried, counted by the kernel at our end; -1 unread */
static long long link_bytes(const char *iface, const char *which) {
  char p[PATH_MAX], v[32];
  snprintf(p, sizeof(p), "/sys/class/net/%s/statistics/%s", iface, which);
  return iface[0] && read_file(p, v, sizeof(v)) > 0 ? atoll(v) : -1;
}

/* JSON: one object, or null with no tether function in the gadget */
void ua_status_tether(const struct ua_gadget *g, int json) {
  const char *func = live_func(g);
  char p[PATH_MAX], info[512] = "", iface[IFNAMSIZ] = "", addr[64], v6[64],
                    up[IFNAMSIZ], gw[256], gw_if[IFNAMSIZ], wait[4];
  if (!func) {
    if (json)
      printf("null");
    return;
  }
  const char *name = !strcmp(func, NCM) ? "ncm" : "rndis";
  info_path(p, sizeof(p));
  int running = daemon_pid() > 0 && read_file(p, info, sizeof(info)) > 0;
  info_get(info, "iface", iface, sizeof(iface));
  if (!iface[0])
    read_ifname(g, func, iface);
  info_get(info, "address", addr, sizeof(addr));
  info_get(info, "ipv6", v6, sizeof(v6));
  info_get(info, "upstream", up, sizeof(up));
  info_get(info, "gateway", gw, sizeof(gw));
  info_get(info, "gateway_iface", gw_if, sizeof(gw_if));
  info_get(info, "gateway_waiting", wait, sizeof(wait));
  int gateway = gw_if[0] != '\0';

  if (json) {
    int first = 1;
    printf("{");
    ua_json_str("function", name, &first);
    ua_json_str("iface", iface, &first);
    ua_json_int("running", running, &first);
    ua_json_str("address", addr, &first);
    ua_json_str("ipv6", v6, &first);
    ua_json_str("upstream", up, &first);
    ua_json_int("gateway", gateway, &first);
    ua_json_str("gateway_container", gw, &first);
    ua_json_str("gateway_iface", gw_if, &first);
    ua_json_int("gateway_waiting", wait[0] == '1', &first);
    ua_json_int("tx_bytes", link_bytes(iface, "tx_bytes"), &first);
    ua_json_int("rx_bytes", link_bytes(iface, "rx_bytes"), &first);
    printf("}");
  } else if (!running) {
    printf("Tether:     %s on %s (no network side running)\n", name,
           iface[0] ? iface : "?");
  } else if (gateway) {
    printf("Tether:     %s: %s bridged to %s as %s%s\n", name, iface,
           gw[0] ? gw : "the container", gw_if,
           wait[0] == '1' ? " (waiting for it to run)" : "");
  } else {
    printf("Tether:     %s: %s %s%s%s, sharing %s\n", name, iface, addr,
           v6[0] ? " + " : "", v6, up[0] ? up : "(no uplink yet)");
  }
}

int ua_cmd_tether(int argc, char **argv) {
  enum {
    OPT_RNDIS = 256,
    OPT_NCM,
    OPT_NO_NET,
    OPT_GW_PID,
    OPT_GW_NETNS,
    OPT_GW_IFACE
  };
  static const struct option opts[] = {
      {"rndis", no_argument, NULL, OPT_RNDIS},
      {"ncm", no_argument, NULL, OPT_NCM},
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
  /* static: t points into both for the daemon's lifetime */
  static struct ua_config cfg;
  static struct ua_setup s;
  struct tether t, probe;
  int rndis, no_net, fg = 0, c, edited = 0;
  pid_t gw_pid = 0;
  const char *gw_path = NULL;
  memset(&t, 0, sizeof(t));
  ua_config_active(&cfg);
  ua_setup_load(&s);

  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_tether();
    return UA_EXIT_OK;
  }
  /* Each option is checked as before, then goes into the setup: a start
   * with options saves them (Droidspaces' rule), a bare one runs the setup */
  char kv[UA_LUN_SPEC];
#define SET(fmt, ...)                                                          \
  (snprintf(kv, sizeof(kv), fmt, __VA_ARGS__), edited = 1, ua_setup_set(&s, kv))
  while ((c = getopt_long(argc, argv, ":u:a:d:fG:h", opts, NULL)) != -1) {
    switch (c) {
    case OPT_RNDIS:
      SET("tether_function=%s", "rndis");
      break;
    case OPT_NCM: /* still RNDIS where NCM is missing or unsafe: the probe
                     decides */
      SET("tether_function=%s", "ncm");
      break;
    case OPT_NO_NET: /* a saved gateway would win over it */
      SET("tether_net=%d", 0);
      SET("tether_gateway=%s", "");
      break;
    case 'G':
      if (SET("tether_gateway=%s", optarg) < 0)
        return UA_EXIT_USAGE;
      break;
    case OPT_GW_PID: {
      char *end;
      long v = strtol(optarg, &end, 10);
      if (!*optarg || *end || v <= 0) {
        ua_usage_error("tether", "--gateway-pid takes a process id");
        return UA_EXIT_USAGE;
      }
      gw_pid = (pid_t)v;
      break;
    }
    case OPT_GW_NETNS:
      gw_path = optarg;
      break;
    case OPT_GW_IFACE:
      if (!*optarg || strlen(optarg) >= IFNAMSIZ) {
        ua_usage_error("tether", "Bad interface name '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      SET("tether_gateway_iface=%s", optarg);
      break;
    case 'u':
      if (strlen(optarg) >= IFNAMSIZ) {
        ua_usage_error("tether", "Interface name too long: %s", optarg);
        return UA_EXIT_USAGE;
      }
      SET("tether_upstream=%s", optarg);
      break;
    case 'a': {
      /* Saved in its full form, so the file's reader need not know that a
       * bare address is a /24 */
      if (parse_address(optarg, &probe) < 0) {
        ua_usage_error("tether",
                       "--address takes a host address with a /8../30 "
                       "prefix, like 192.168.42.129/24");
        return UA_EXIT_USAGE;
      }
      struct in_addr a = {probe.ip_be};
      SET("tether_address=%s/%u", inet_ntoa(a), probe.prefix);
      break;
    }
    case 'd':
      /* Handed to the DHCP server as is, so it is checked here like the
       * profile's tether_dns */
      if (!ua_valid_dns(optarg)) {
        ua_usage_error("tether", "--dns takes IPv4 addresses, comma-separated, "
                                 "127 characters at most");
        return UA_EXIT_USAGE;
      }
      SET("tether_dns=%s", optarg);
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
#undef SET
  if (argc - optind != 1) {
    ua_usage_error("tether",
                   argc - optind ? "Too many arguments" : "Missing action");
    return UA_EXIT_USAGE;
  }
  if (edited && ua_setup_save(&s) < 0)
    return UA_EXIT_FAIL;
  /* The setup, then the profile where the setup leaves a value empty */
  rndis = !strcmp(
      s.tether_function[0] ? s.tether_function : cfg.tether_function, "rndis");
  if (parse_address(s.tether_address[0] ? s.tether_address : cfg.tether_address,
                    &t) < 0)
    parse_address("192.168.42.129/24", &t);
  t.dns = s.tether_dns[0]     ? s.tether_dns
          : cfg.tether_dns[0] ? cfg.tether_dns
                              : NULL;
  t.upstream = s.tether_upstream[0]     ? s.tether_upstream
               : cfg.tether_upstream[0] ? cfg.tether_upstream
                                        : NULL;
  t.gw_iface = s.tether_gateway_iface[0] ? s.tether_gateway_iface : "usb0";
  t.dev_mac = cfg.tether_dev_mac;
  t.host_mac = cfg.tether_host_mac;
  if (s.tether_gateway[0]) {
    t.gateway = 1;
    t.gw_name = s.tether_gateway;
  }
  /* A pid or a netns names a container the setup cannot: this start only */
  if (gw_pid || gw_path) {
    t.gateway = 1;
    t.gw_pid = gw_pid;
    t.gw_path = gw_path;
  }
  /* A gateway is the network side: tether_net only says whether we serve one */
  no_net = !s.tether_net && !t.gateway;
  if (!strcmp(argv[optind], "start"))
    return do_start(&t, rndis, no_net, fg);
  if (!strcmp(argv[optind], "apply"))
    return do_apply(&t, rndis, no_net);
  if (!strcmp(argv[optind], "stop"))
    return do_stop();
  ua_usage_error("tether", "Unknown action '%s'", argv[optind]);
  return UA_EXIT_USAGE;
}
