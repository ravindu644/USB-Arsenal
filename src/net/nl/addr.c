/*
 * USB Arsenal - lifted from Droidspaces (src/net/nl/addr.c)
 *
 * Addresses and the one route we add by hand: IPv4 and IPv6 addresses, an
 * IPv6 prefix route.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "net/net.h"
#include "usbarsenal.h"
#include <arpa/inet.h>
#include <linux/if_link.h>
#include <linux/rtnetlink.h>
#include <linux/veth.h>
#include <net/if.h>
#include <sys/socket.h>

#include "internal.h"

/* Add an IPv4 address to an interface
 * ip_be and bcast_be are in network byte order. */

int ds_nl_add_addr4(ds_nl_ctx_t *ctx, const char *ifname, uint32_t ip_be,
                    uint8_t prefix) {
  if (prefix > 32)
    return -EINVAL;

  int idx = ds_nl_get_ifindex(ctx, ifname);
  if (idx <= 0)
    return -ENODEV;

  struct {
    struct nlmsghdr n;
    struct ifaddrmsg ifa;
    char buf[256];
  } req;
  memset(&req, 0, sizeof(req));
  req.n.nlmsg_len = NLMSG_LENGTH(sizeof(struct ifaddrmsg));
  req.n.nlmsg_type = RTM_NEWADDR;
  req.n.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE | NLM_F_REPLACE | NLM_F_ACK;
  req.ifa.ifa_family = AF_INET;
  req.ifa.ifa_prefixlen = prefix;
  req.ifa.ifa_index = (unsigned int)idx;
  req.ifa.ifa_scope = RT_SCOPE_UNIVERSE;

  nl_addattr(&req.n, (int)sizeof(req), IFA_LOCAL, &ip_be, 4);
  nl_addattr(&req.n, (int)sizeof(req), IFA_ADDRESS, &ip_be, 4);

  if (prefix < 32) {
    uint32_t bcast = ip_be | htonl(0xffffffffu >> prefix);
    nl_addattr(&req.n, (int)sizeof(req), IFA_BROADCAST, &bcast, 4);
  }

  return ds_nl_talk(ctx, &req.n);
}

/* Whether ifname still carries this exact IPv4 address. A gadget rebind from
 * another function starting or stopping can flush the address while the kernel
 * keeps the ifindex, so follow_netdev() cannot tell from the index alone that
 * the address is gone; it asks here. Dumps RTM_GETADDR (unprivileged) and
 * scans for our ifindex, prefix and IFA_LOCAL. On any send/parse trouble it
 * answers 1 (assume present) so a half-read dump never provokes a needless
 * re-add. */
int ds_nl_has_addr4(ds_nl_ctx_t *ctx, const char *ifname, uint32_t ip_be,
                    uint8_t prefix) {
  unsigned int idx = if_nametoindex(ifname);
  if (idx == 0)
    return 0;

  struct {
    struct nlmsghdr n;
    struct ifaddrmsg a;
  } req;
  memset(&req, 0, sizeof(req));
  req.n.nlmsg_len = NLMSG_LENGTH(sizeof(struct ifaddrmsg));
  req.n.nlmsg_type = RTM_GETADDR;
  req.n.nlmsg_flags = NLM_F_REQUEST | NLM_F_DUMP;
  req.a.ifa_family = AF_INET;
  req.n.nlmsg_seq = ++ctx->seq;
  req.n.nlmsg_pid = (uint32_t)ctx->pid;

  if (send(ctx->fd, &req, req.n.nlmsg_len, 0) < 0)
    return 1;

  uint8_t buf[NL_BUFSIZE];
  int found = 0;
  /* Read the whole dump: returning early would leave unread messages on the
   * socket for the next request to trip over. */
  for (;;) {
    ssize_t n = recv(ctx->fd, buf, sizeof(buf), 0);
    if (n <= 0)
      break;
    struct nlmsghdr *h = (struct nlmsghdr *)buf;
    for (; NLMSG_OK(h, (uint32_t)n); h = NLMSG_NEXT(h, n)) {
      if (h->nlmsg_type == NLMSG_DONE)
        return found;
      if (h->nlmsg_type != RTM_NEWADDR)
        continue;
      struct ifaddrmsg *ifa = NLMSG_DATA(h);
      if (ifa->ifa_index != idx || ifa->ifa_prefixlen != prefix)
        continue;
      struct rtattr *rta = IFA_RTA(ifa);
      int rlen = (int)IFA_PAYLOAD(h);
      for (; RTA_OK(rta, rlen); rta = RTA_NEXT(rta, rlen))
        if (rta->rta_type == IFA_LOCAL && RTA_PAYLOAD(rta) == 4 &&
            *(uint32_t *)RTA_DATA(rta) == ip_be)
          found = 1;
    }
  }
  return found;
}

/* IPv6 siblings of ds_nl_add_addr4, for NAT66.
 *
 * IFA_F_NODAD: these are our own router addresses on a link we created, so
 * there is nobody to collide with, and waiting out duplicate address detection
 * would leave the address unusable for the first second of every boot. It is
 * the old 8-bit flag, so it works on the 3.10 floor.
 *
 * The route is always link scope (no gateway): the only caller points a
 * container's /64 at its point-to-point veth. */

int ds_nl_add_addr6(ds_nl_ctx_t *ctx, const char *ifname,
                    const struct in6_addr *ip, uint8_t prefix) {
  int idx = ds_nl_get_ifindex(ctx, ifname);
  if (idx <= 0)
    return -ENODEV;

  struct {
    struct nlmsghdr n;
    struct ifaddrmsg ifa;
    char buf[256];
  } req;
  memset(&req, 0, sizeof(req));
  req.n.nlmsg_len = NLMSG_LENGTH(sizeof(struct ifaddrmsg));
  req.n.nlmsg_type = RTM_NEWADDR;
  req.n.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE | NLM_F_REPLACE | NLM_F_ACK;
  req.ifa.ifa_family = AF_INET6;
  req.ifa.ifa_prefixlen = prefix;
  req.ifa.ifa_index = (unsigned int)idx;
  req.ifa.ifa_flags = IFA_F_NODAD;

  nl_addattr(&req.n, (int)sizeof(req), IFA_LOCAL, ip, sizeof(*ip));
  nl_addattr(&req.n, (int)sizeof(req), IFA_ADDRESS, ip, sizeof(*ip));
  return ds_nl_talk(ctx, &req.n);
}

int ds_nl_add_route6(ds_nl_ctx_t *ctx, const struct in6_addr *dst,
                     uint8_t dst_len, int oif_idx) {
  struct {
    struct nlmsghdr n;
    struct rtmsg r;
    char buf[256];
  } req;
  memset(&req, 0, sizeof(req));
  req.n.nlmsg_len = NLMSG_LENGTH(sizeof(struct rtmsg));
  req.n.nlmsg_type = RTM_NEWROUTE;
  req.n.nlmsg_flags = NLM_F_REQUEST | NLM_F_CREATE | NLM_F_REPLACE | NLM_F_ACK;
  req.r.rtm_family = AF_INET6;
  req.r.rtm_dst_len = dst_len;
  req.r.rtm_table = RT_TABLE_MAIN;
  req.r.rtm_protocol = RTPROT_BOOT;
  req.r.rtm_scope = RT_SCOPE_LINK;
  req.r.rtm_type = RTN_UNICAST;

  nl_addattr(&req.n, (int)sizeof(req), RTA_DST, dst, sizeof(*dst));
  nl_addattr(&req.n, (int)sizeof(req), RTA_OIF, &oif_idx, (int)sizeof(int));
  return ds_nl_talk(ctx, &req.n);
}
