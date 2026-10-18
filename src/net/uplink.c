/*
 * USB Arsenal - lifted from Droidspaces (src/net/network.c) by the same author
 *
 * Finds the interface and routing table that currently reach the internet,
 * so tethered traffic can be pointed at it. Android keeps one routing table
 * per network and a netd rule naming the default one; plain Linux has a
 * default route in the main table.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "net.h"
#include "usbarsenal.h"
#include <fnmatch.h>
#include <linux/rtnetlink.h>

/* The only interface families that terminate internet access, highest
 * precedence first: Wi-Fi STA, ethernet, CLAT (464xlat), Qualcomm and MTK
 * mobile data. */
static const char *const k_uplink_patterns[] = {
    "wlan*", "eth*", "end*", "enp*", "v4-*", "rmnet*", "*ccmni*",
};

/* Never an uplink: downstream devices that share connectivity out (ours
 * included, usb0/ncm0/rndis0 are exactly what we create), VPN tunnels,
 * loopback and placeholders. */
static const char *const k_uplink_excludes[] = {
    "ds-*",   "lo",   "dummy*", "swlan*", "ap*",  "usb*",
    "rndis*", "ncm*", "p2p*",   "tun*",   "ppp*", "bt-pan",
};

static int iface_is_running(const char *ifname) {
  int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
  if (fd < 0)
    return 0;
  struct ifreq ifr;
  memset(&ifr, 0, sizeof(ifr));
  safe_strncpy(ifr.ifr_name, ifname, IFNAMSIZ);
  int ret = 0;
  if (ioctl(fd, SIOCGIFFLAGS, &ifr) == 0)
    ret = (ifr.ifr_flags & (IFF_UP | IFF_RUNNING)) == (IFF_UP | IFF_RUNNING);
  close(fd);
  return ret;
}

static int uplink_candidate_ok(const char *ifname) {
  if (!ifname[0])
    return 0;
  for (size_t i = 0;
       i < sizeof(k_uplink_excludes) / sizeof(k_uplink_excludes[0]); i++)
    if (fnmatch(k_uplink_excludes[i], ifname, 0) == 0)
      return 0;
  return iface_is_running(ifname);
}

static int scan_uplink_whitelist(ds_nl_ctx_t *ctx, char *iface_out,
                                 int *table_out) {
  char all[64][IFNAMSIZ];
  int count = ds_nl_list_ifaces(ctx, all, 64);
  for (size_t p = 0;
       p < sizeof(k_uplink_patterns) / sizeof(k_uplink_patterns[0]); p++)
    for (int j = 0; j < count; j++) {
      int tbl = 0;
      if (fnmatch(k_uplink_patterns[p], all[j], 0) != 0 ||
          !uplink_candidate_ok(all[j]) ||
          ds_nl_get_iface_table(ctx, AF_INET, all[j], &tbl) != 0)
        continue;
      safe_strncpy(iface_out, all[j], IFNAMSIZ);
      *table_out = tbl;
      return 0;
    }
  return -ENOENT;
}

/* A pinned --upstream is taken as given: the user picked it deliberately, so
 * none of the exclude heuristics apply, only "is it up and routed". */
static int resolve_pinned(ds_nl_ctx_t *ctx, const char *pin, char *iface_out,
                          int *table_out) {
  int tbl = RT_TABLE_MAIN;
  if (!iface_is_running(pin))
    return -ENOENT;
  if (is_android() && ds_nl_get_iface_table(ctx, AF_INET, pin, &tbl) != 0)
    return -ENOENT;
  safe_strncpy(iface_out, pin, IFNAMSIZ);
  *table_out = tbl;
  return 0;
}

/* First hit wins: a pinned interface, Android's netd default-network rule,
 * the main table's default route, then a name scan. Returns how the uplink
 * was found, or NULL when there is none. */
const char *ua_find_uplink(const char *pin, char *iface_out, int *table_out) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return NULL;
  const char *how = NULL;
  iface_out[0] = '\0';
  *table_out = 0;

  if (pin && pin[0]) {
    if (resolve_pinned(ctx, pin, iface_out, table_out) == 0)
      how = "pinned";
  } else if (is_android() &&
             ds_nl_get_android_default(ctx, AF_INET, iface_out, table_out) ==
                 0 &&
             uplink_candidate_ok(iface_out)) {
    how = "netd rule";
  } else {
    *table_out = RT_TABLE_MAIN;
    if (ds_nl_get_table_default_oif(ctx, AF_INET, RT_TABLE_MAIN, iface_out) ==
            0 &&
        uplink_candidate_ok(iface_out))
      how = "main table";
    else if (scan_uplink_whitelist(ctx, iface_out, table_out) == 0)
      how = "interface scan";
  }
  ds_nl_close(ctx);
  return how;
}
