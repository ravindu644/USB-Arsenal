/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Gateway mode: the USB link goes to a container (OpenWrt) that serves the
 * host itself. usb0 sits on our bridge, and a veth runs into the container.
 */

#include "net/net.h"
#include "usbarsenal.h"

#include "internal.h"

/* Gateway topology, after Droidspaces' gateway mode: usb0 never leaves our
 * namespace. It sits on a bridge with no address, and one veth runs from
 * that bridge into the container, its far end born there under its final
 * name. A container restart only takes the far end with it, and a gadget
 * rebind only gives usb0 a new identity; both are re-plugged each tick. */
#define GW_BRIDGE "ua-usb"
#define GW_VETH "ua-gw"

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

/* With no daemon alive, a host end of the cable is a leftover; dropping it
 * takes its far end with it, so what the container still holds is not ours */
void drop_host_end(void) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  if (ds_nl_link_exists(ctx, GW_VETH))
    ds_nl_del_link(ctx, GW_VETH);
  ds_nl_close(ctx);
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

int gw_up(struct tether *t) {
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

void gw_tick(struct tether *t) {
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

void gw_down(void) {
  ds_nl_ctx_t *ctx = ds_nl_open();
  if (!ctx)
    return;
  /* The host end goes first: it pins the container's namespace */
  ds_nl_del_link(ctx, GW_VETH);
  ds_nl_del_link(ctx, GW_BRIDGE);
  ds_nl_close(ctx);
}
