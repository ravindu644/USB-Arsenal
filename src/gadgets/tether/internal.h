/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * What the tether files share.
 */

#ifndef UA_TETHER_INTERNAL_H
#define UA_TETHER_INTERNAL_H

#include "net/net.h"
#include "usbarsenal.h"

/* NAT66 hands the host a ULA /64. Our own, not Droidspaces' fd64:7370::/48,
 * so a container's network and the USB link can run side by side. */
#define NAT6_PREFIX "fd75:7361::"
#define NAT6_CIDR "fd75:7361::/64"
#define NAT6_GW_LL "fe80::1" /* the source of every RA ds_ra_build() sends */

struct tether {
  const char *upstream;           /* --upstream, or NULL for automatic */
  const char *dns;                /* --dns, or NULL */
  const char *dev_mac, *host_mac; /* the profile's, "" for stable_macs() */
  char iface[IFNAMSIZ];
  uint32_t ip_be, net_be, mask_be, offer_be;
  uint8_t prefix;
  char cidr[32]; /* our subnet, for the NAT rules */
  int table;     /* uplink routing table the FROM rule points at, 0 = none */
  char uplink[IFNAMSIZ];
  int nat6;   /* IPv6 NAT is up, and the DHCP thread sends RAs */
  int table6; /* uplink IPv6 table the IPv6 FROM rule points at, 0 = none */
  struct in6_addr prefix6;
  unsigned ifindex; /* of the netdev we configured; a new one means rebind */
  /* gateway mode: a container (OpenWrt) serves the host instead of us */
  int gateway;
  const char *gw_name, *gw_path, *gw_iface;
  pid_t gw_pid;
  ino_t gw_ino; /* the netns the cable is plugged into, 0 = none */
};

/* tether.c */

void gw_mac(const char *key, uint8_t mac[6]);
void write_info(const struct tether *t);

/* gateway.c */

void drop_host_end(void);
int gw_up(struct tether *t);
void gw_tick(struct tether *t);
void gw_down(void);

/* routed.c */

void sync_uplink(struct tether *t, int quiet);
void install_policy_rules(struct tether *t);
int install_family_rules(int family, const char *cidr, const char *iface);
int net_up(struct tether *t);
void follow_netdev(struct tether *t);
void net_down(struct tether *t);

/* monitor.c */

int serve(struct tether *t, int ready_fd);

#endif /* UA_TETHER_INTERNAL_H */
