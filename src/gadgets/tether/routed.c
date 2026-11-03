/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Serving the host ourselves: the address, NAT, Android's policy rules, the
 * uplink they point at, and NAT66 on top.
 */

#include "net/net.h"
#include "usbarsenal.h"
#include <linux/rtnetlink.h>
#include <sys/signalfd.h>

#include "internal.h"

/* Below Android's VPN rules (10000+) so tethered traffic never gets pulled
 * into a VPN, above the OEM reserved range (< 1000). Same values Droidspaces
 * uses; the selectors are our own subnet, so the two never collide. */
#define RULE_PRIO_TO 6090
#define RULE_PRIO_FROM 6100

/* Point "from our subnet" at the uplink's routing table. Android only: there
 * every network has its own table and nothing routes our subnet by default.
 * On plain Linux the main table already does. */
/* The IPv6 half of sync_uplink(). On an IPv6-only carrier the IPv4 uplink
 * is the CLAT device v4-rmnetX, whose IPv6 routes live in the table of the
 * rmnetX underneath. An uplink with no IPv6 table clears the rule, and the
 * host falls back to IPv4 at once instead of waiting on a dead route. */
static void sync_uplink6(struct tether *t, const char *iface) {
  if (!t->nat6 || !is_android())
    return;
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  int table = 0;
  if (!strncmp(iface, "v4-", 3))
    iface += 3;
  ds_nl_get_iface_table(ctx, AF_INET6, iface, &table);
  if (table != t->table6) {
    if (t->table6 > 0)
      ds_nl_rule6(ctx, 0, &t->prefix6, 64, 1, t->table6, RULE_PRIO_FROM);
    if (table > 0 &&
        ds_nl_rule6(ctx, 1, &t->prefix6, 64, 1, table, RULE_PRIO_FROM) < 0)
      ua_warn("[NET] Cannot add the IPv6 route rule for table %d", table);
    t->table6 = table;
  }
  ds_nl_close(ctx);
}

void sync_uplink(struct tether *t, int quiet) {
  char iface[IFNAMSIZ];
  int table = 0;
  const char *how = ua_find_uplink(t->upstream, iface, &table, t->nat6);
  if (!how) {
    /* Once per loss, not every tick: this goes to the log */
    if (!quiet || t->uplink[0])
      ua_warn("[NET] No internet connection to share yet; the host gets a "
              "link but no route out until one comes up");
    t->uplink[0] = '\0';
    iface[0] = '\0';
    /* Automatic mode keeps the rule through a transient loss. A pinned
     * uplink that is gone loses it, so the host's traffic never leaks out
     * of a table the kernel may recycle for another network. */
    table = t->upstream ? 0 : t->table;
  }
  if (strcmp(iface, t->uplink) != 0 && how) {
    ua_log("Sharing %s (%s)", iface, how);
    safe_strncpy(t->uplink, iface, sizeof(t->uplink));
  }
  if (how || t->upstream)
    sync_uplink6(t, iface);
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

/* Android routes nothing to or from our subnets by default: replies to the
 * host must find our connected route in main, and the host's traffic must
 * find the uplink's table. Re-run by the monitor, because netd flushes every
 * rule when it restarts; adding a rule that exists is a no-op. */
void install_policy_rules(struct tether *t) {
  if (!is_android())
    return;
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  int ret = ds_nl_add_rule4(ctx, 0, 0, t->net_be, t->prefix, RT_TABLE_MAIN,
                            RULE_PRIO_TO);
  if (t->table > 0 && ds_nl_add_rule4(ctx, t->net_be, t->prefix, 0, 0, t->table,
                                      RULE_PRIO_FROM) < 0)
    ret = -1;
  if (t->nat6 &&
      ds_nl_rule6(ctx, 1, &t->prefix6, 64, 0, RT_TABLE_MAIN, RULE_PRIO_TO) < 0)
    ret = -1;
  if (t->table6 > 0 &&
      ds_nl_rule6(ctx, 1, &t->prefix6, 64, 1, t->table6, RULE_PRIO_FROM) < 0)
    ret = -1;
  if (ret < 0)
    ua_warn("[NET] One or more route rules failed to install");
  ds_nl_close(ctx);
}

/* One family's firewall rules. Fails on MASQUERADE or FORWARD, the two
 * without which that family has no connectivity at all. A mobile uplink's
 * MTU is often below the 1500 the host assumes, hence the clamp. */
int install_family_rules(int family, const char *cidr, const char *iface) {
  ds_ipt_ensure_input_accept(family, iface);
  int ret = ds_ipt_ensure_masquerade(family, cidr) < 0 ||
                    ds_ipt_ensure_forward_accept(family, iface) < 0
                ? -1
                : 0;
  ds_ipt_ensure_mss_clamp(family);
  return ret;
}

/* The per-interface half of NAT66, redone whenever the netdev is re-created.
 * Our end is a router: IPv6 on even where the default is off, and never
 * autoconfigured from what the host sends. accept_ra_rt_table exists only on
 * Android, whose default files connected routes in a private per-interface
 * table; 0 keeps them in main, where the TO rule sends replies. The prefix
 * route goes in by hand too, for a netdev that existed before the knob. */
static int nat6_iface(ds_nl_ctx_t *ctx, struct tether *t) {
  static const char *const knobs[][2] = {{"disable_ipv6", "0"},
                                         {"accept_ra", "0"},
                                         {"autoconf", "0"},
                                         {"accept_ra_rt_table", "0"}};
  for (size_t i = 0; i < sizeof(knobs) / sizeof(knobs[0]); i++) {
    char p[128];
    snprintf(p, sizeof(p), "/proc/sys/net/ipv6/conf/%s/%s", t->iface,
             knobs[i][0]);
    write_file(p, knobs[i][1]);
  }
  struct in6_addr ll, gw = t->prefix6;
  inet_pton(AF_INET6, NAT6_GW_LL, &ll);
  gw.s6_addr[15] = 1;
  int err = ds_nl_add_addr6(ctx, t->iface, &ll, 64);
  if (err == 0)
    err = ds_nl_add_addr6(ctx, t->iface, &gw, 64);
  if (err == 0)
    err = ds_nl_add_route6(ctx, &t->prefix6, 64,
                           ds_nl_get_ifindex(ctx, t->iface));
  return err;
}

/* Turning on IPv6 forwarding makes the kernel ignore RAs on every interface
 * at accept_ra=1, so a machine that got its own address by SLAAC would lose
 * its default route when the current one expires. 2 means "accept even while
 * forwarding". Android already runs its uplinks at 2, and if forwarding is
 * already on, whoever turned it on has dealt with this. */
static void keep_host_slaac(void) {
  char val[4] = {0};
  if (read_file("/proc/sys/net/ipv6/conf/all/forwarding", val, sizeof(val)) >
          0 &&
      val[0] == '1')
    return;
  DIR *d = opendir("/proc/sys/net/ipv6/conf");
  if (!d)
    return;
  struct dirent *e;
  while ((e = readdir(d))) {
    if (e->d_name[0] == '.' || !strcmp(e->d_name, "all") ||
        !strcmp(e->d_name, "lo"))
      continue;
    char p[PATH_MAX];
    snprintf(p, sizeof(p), "/proc/sys/net/ipv6/conf/%s/accept_ra", e->d_name);
    if (read_file(p, val, sizeof(val)) > 0 && val[0] == '1')
      write_file(p, "2");
  }
  closedir(d);
}

/* NAT66, after Droidspaces' setup_nat6_host (itself after LXC's lxc-net): a
 * ULA /64 on the link, one MASQUERADE rule, forwarding, and RAs from the DHCP
 * thread. Anything missing leaves the host on IPv4 only, as it was before. */
static void nat6_up(struct tether *t) {
  if (access("/proc/sys/net/ipv6/conf/all/forwarding", F_OK) != 0)
    return;
  /* Not a warning: stock GKI has no IPv6 nat table; `check` reports it */
  if (!ds_ipt6_available()) {
    ua_log("[NET] No IPv6 NAT in this kernel; the host gets IPv4 only");
    return;
  }
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  inet_pton(AF_INET6, NAT6_PREFIX, &t->prefix6);
  int err = nat6_iface(ctx, t);
  ds_nl_close(ctx);
  /* Fail closed: an address and a default route with no MASQUERADE behind
   * them make every IPv6 connection hang instead of falling back to IPv4 */
  if (err < 0 || install_family_rules(AF_INET6, NAT6_CIDR, t->iface) < 0) {
    ua_warn("[NET] IPv6 setup on %s failed; the host gets IPv4 only", t->iface);
    ds_ipt_remove_nat_rules(AF_INET6, NAT6_CIDR, t->iface);
    return;
  }
  keep_host_slaac();
  write_file("/proc/sys/net/ipv6/conf/all/forwarding", "1");
  t->nat6 = 1;
  ua_log("[NET] IPv6: advertising " NAT6_CIDR " to the host");
}

int net_up(struct tether *t) {
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
  ds_nl_close(ctx);

  write_file("/proc/sys/net/ipv4/ip_forward", "1");
  if (install_family_rules(AF_INET, t->cidr, t->iface) < 0) {
    ua_error("Cannot install the NAT rules for %s", t->cidr);
    return -1;
  }

  nat6_up(t);
  install_policy_rules(t);
  sync_uplink(t, 0);
  ds_dhcp_server_start(t->dns, t->iface, t->offer_be, t->ip_be, t->mask_be,
                       t->nat6 ? &t->prefix6 : NULL);
  t->ifindex = if_nametoindex(t->iface);
  return 0;
}

/* Kernels with Samsung's UEVENT configfs (M62 4.14) free the netdev when the
 * gadget unbinds and make a new one at bind, so any other function starting
 * or stopping hands us a fresh rndis0/usb0: no address, down, and a DHCP
 * socket still bound to the dead ifindex. Spot the new index and redo the
 * per-interface half. The NAT rules match by name and subnet, so they hold. */
void follow_netdev(struct tether *t) {
  unsigned idx = if_nametoindex(t->iface);
  if (!idx || idx == t->ifindex)
    return;
  ua_log("[NET] %s was re-created by a rebind, configuring it again", t->iface);
  ds_dhcp_server_stop();
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (ctx) {
    int r = ds_nl_link_up(ctx, t->iface);
    int a = ds_nl_add_addr4(ctx, t->iface, t->ip_be, t->prefix);
    if (r < 0 || (a < 0 && a != -EEXIST))
      ua_warn("[NET] Cannot bring up the new %s", t->iface);
    if (t->nat6 && nat6_iface(ctx, t) < 0)
      ua_warn("[NET] Cannot give the new %s its IPv6 addresses", t->iface);
    ds_nl_close(ctx);
  }
  ds_dhcp_server_start(t->dns, t->iface, t->offer_be, t->ip_be, t->mask_be,
                       t->nat6 ? &t->prefix6 : NULL);
  t->ifindex = idx;
}

void net_down(struct tether *t) {
  ds_dhcp_server_stop();
  ds_ipt_remove_nat_rules(AF_INET, t->cidr, t->iface);
  if (t->nat6)
    ds_ipt_remove_nat_rules(AF_INET6, NAT6_CIDR, t->iface);
  if (is_android()) {
    ds_nl_ctx_t *ctx = ds_nl_open();
    if (ctx) {
      ds_nl_del_rule4(ctx, 0, 0, t->net_be, t->prefix, RT_TABLE_MAIN,
                      RULE_PRIO_TO);
      if (t->table > 0)
        ds_nl_del_rule4(ctx, t->net_be, t->prefix, 0, 0, t->table,
                        RULE_PRIO_FROM);
      if (t->nat6)
        ds_nl_rule6(ctx, 0, &t->prefix6, 64, 0, RT_TABLE_MAIN, RULE_PRIO_TO);
      if (t->table6 > 0)
        ds_nl_rule6(ctx, 0, &t->prefix6, 64, 1, t->table6, RULE_PRIO_FROM);
      ds_nl_close(ctx);
    }
  }
  /* Forwarding stays on: a Droidspaces container or Android's own tethering
   * may be routing through it by now, and turning it off would cut them */
}
