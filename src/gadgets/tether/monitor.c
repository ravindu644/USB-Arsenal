/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The network side's main loop: rtnetlink events and a heartbeat, each
 * putting back what someone removed and following the uplink.
 */

#include "net/net.h"
#include "usbarsenal.h"
#include <linux/rtnetlink.h>
#include <sys/signalfd.h>

#include "internal.h"

/* Droidspaces' route monitor heartbeat: covers kernels with broken netlink
 * notifications, a gateway container restarting in another namespace (no
 * event reaches us from there), and forwarding that someone switched off */
#define HEARTBEAT_MS 1500

/* Re-apply what someone else may have removed, then follow the uplink.
 * Droidspaces' do_uplink_reprobe(), with our subnets and interface. */
static void reprobe(struct tether *t) {
  if (t->gateway) {
    gw_tick(t);
    return;
  }
  follow_netdev(t);
  install_policy_rules(t);
  /* Only 0 means a rule is missing; -1 is "tables unreadable", where a
   * blind reinstall through the iptables binary would stack duplicates */
  if (ds_ipt_host_rules_present(AF_INET, t->iface, t->cidr, 0) == 0 ||
      (t->nat6 &&
       ds_ipt_host_rules_present(AF_INET6, t->iface, NAT6_CIDR, 0) == 0)) {
    ua_warn("[NET] The tether firewall rules are gone (netd restart or a "
            "firewall app?); reinstalling");
    install_family_rules(AF_INET, t->cidr, t->iface);
    if (t->nat6)
      install_family_rules(AF_INET6, NAT6_CIDR, t->iface);
  }
  sync_uplink(t, 1);
}

/* netd resets forwarding on Android, and on Linux any tool restoring its own
 * saved value on exit does the same. /proc/sys raises no event, so poll. */
static void keep_forwarding(const struct tether *t) {
  char val[4];
  if (!t->gateway && (t->table > 0 || !is_android()) &&
      read_file("/proc/sys/net/ipv4/ip_forward", val, sizeof(val)) > 0 &&
      val[0] == '0') {
    ua_log("[NET] ip_forward was switched off; switching it back on");
    write_file("/proc/sys/net/ipv4/ip_forward", "1");
  }
  if (t->nat6 &&
      read_file("/proc/sys/net/ipv6/conf/all/forwarding", val, sizeof(val)) >
          0 &&
      val[0] == '0')
    write_file("/proc/sys/net/ipv6/conf/all/forwarding", "1");
}

static int monitor_socket(const struct tether *t) {
  int sock = socket(AF_NETLINK, SOCK_RAW | SOCK_CLOEXEC, NETLINK_ROUTE);
  if (sock < 0)
    return -1;
  struct sockaddr_nl sa;
  memset(&sa, 0, sizeof(sa));
  sa.nl_family = AF_NETLINK;
  /* Rule changes are netd swapping the default network, the Wi-Fi <-> mobile
   * handoff itself. No RTMGRP_ macro exists for rule groups. */
  sa.nl_groups = RTMGRP_LINK | RTMGRP_IPV4_IFADDR | RTMGRP_IPV4_ROUTE |
                 (1u << (RTNLGRP_IPV4_RULE - 1));
  if (t->nat6)
    sa.nl_groups |= RTMGRP_IPV6_ROUTE | (1u << (RTNLGRP_IPV6_RULE - 1));
  if (bind(sock, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
    close(sock);
    return -1;
  }
  return sock;
}

/* An event on an interface that still exists and is not loopback or our own
 * bridge and veth (ua-*). The USB interface counts: a re-created one is what
 * follow_netdev() is waiting for. */
static int real_iface(int ifindex) {
  char name[IFNAMSIZ] = {0};
  if (ifindex <= 0 || !if_indextoname((unsigned)ifindex, name))
    return 0;
  return strncmp(name, "ua-", 3) != 0 && strcmp(name, "lo") != 0;
}

static int wants_reprobe(uint8_t *buf, ssize_t len) {
  for (struct nlmsghdr *h = (struct nlmsghdr *)buf; NLMSG_OK(h, (uint32_t)len);
       h = NLMSG_NEXT(h, len)) {
    if (h->nlmsg_type == NLMSG_DONE || h->nlmsg_type == NLMSG_ERROR)
      break;
    if (h->nlmsg_type == RTM_NEWRULE || h->nlmsg_type == RTM_DELRULE)
      return 1; /* our own rule echoes settle in one no-op pass */
    if (h->nlmsg_type == RTM_NEWLINK || h->nlmsg_type == RTM_DELLINK) {
      if (real_iface(((struct ifinfomsg *)NLMSG_DATA(h))->ifi_index))
        return 1;
    } else if (h->nlmsg_type == RTM_NEWADDR || h->nlmsg_type == RTM_DELADDR) {
      struct ifaddrmsg *ifa = NLMSG_DATA(h);
      if (ifa->ifa_family == AF_INET && real_iface((int)ifa->ifa_index))
        return 1;
    } else if (h->nlmsg_type == RTM_NEWROUTE || h->nlmsg_type == RTM_DELROUTE) {
      /* IPv6 also announces every /64, neighbour and cached clone; only a
       * route as short as uplink detection accepts can move the uplink */
      struct rtmsg *rtm = NLMSG_DATA(h);
      if (rtm->rtm_family != AF_INET &&
          !(rtm->rtm_family == AF_INET6 && rtm->rtm_dst_len < 8))
        continue;
      int oif = 0, rlen = (int)RTM_PAYLOAD(h);
      for (struct rtattr *rta = RTM_RTA(rtm); RTA_OK(rta, rlen);
           rta = RTA_NEXT(rta, rlen))
        if (rta->rta_type == RTA_OIF && RTA_PAYLOAD(rta) >= (int)sizeof(int))
          oif = *(int *)RTA_DATA(rta);
      /* No output interface (a blackhole, say) still counts */
      if (oif <= 0 || real_iface(oif))
        return 1;
    }
  }
  return 0;
}

/* The network side lives as long as this loop: Droidspaces' route monitor,
 * event driven over rtnetlink with a heartbeat behind it. It follows the
 * uplink between Wi-Fi and mobile data, puts back the rules and forwarding
 * that Android (or anyone) removes, and re-configures a re-created netdev. */
int serve(struct tether *t, int ready_fd) {
  sigset_t set;
  sigemptyset(&set);
  sigaddset(&set, SIGTERM);
  sigaddset(&set, SIGINT);
  sigaddset(&set, SIGHUP);
  sigprocmask(SIG_BLOCK, &set, NULL);
  int sfd = signalfd(-1, &set, SFD_CLOEXEC);

  int ok = sfd >= 0 && (t->gateway ? gw_up(t) : net_up(t)) == 0;
  if (ready_fd >= 0) {
    char c = ok ? '1' : '0';
    if (write(ready_fd, &c, 1) < 0)
      ua_debug("ready pipe: %s", strerror(errno));
    close(ready_fd);
  }
  if (!ok) {
    if (sfd < 0)
      ua_error("signalfd: %s", strerror(errno));
    else if (t->gateway)
      gw_down();
    else
      net_down(t);
    return -1;
  }

  int sock = monitor_socket(t);
  if (sock < 0)
    ua_warn("[NET] No rtnetlink socket (%s); following changes on the "
            "heartbeat only",
            strerror(errno));
  uint8_t buf[8192];
  for (;;) {
    keep_forwarding(t);
    struct pollfd pfd[2] = {{sfd, POLLIN, 0}, {sock, POLLIN, 0}};
    int pr = poll(pfd, sock >= 0 ? 2 : 1, HEARTBEAT_MS);
    if (pr < 0 && errno == EINTR)
      continue;
    if (pr < 0 || (pfd[0].revents & POLLIN))
      break;
    if (pr == 0) {
      reprobe(t);
      continue;
    }
    ssize_t len = recv(sock, buf, sizeof(buf), 0);
    if (len < 0 && errno == ENOBUFS) {
      /* An event burst overflowed the socket: resync and keep going */
      reprobe(t);
      continue;
    }
    if (len < 0 && errno != EINTR && errno != EAGAIN) {
      ua_warn("[NET] rtnetlink: %s; following changes on the heartbeat only",
              strerror(errno));
      close(sock);
      sock = -1;
    }
    if (len <= 0 || !wants_reprobe(buf, len))
      continue;
    /* A handoff is a burst of rule, route and address events: let it settle
     * into one reprobe instead of one per event */
    while (poll(&pfd[1], 1, 200) > 0 && recv(sock, buf, sizeof(buf), 0) > 0)
      ;
    reprobe(t);
  }
  if (sock >= 0)
    close(sock);
  close(sfd);
  ua_log("[NET] Stopping tethering on %s", t->iface);
  if (t->gateway)
    gw_down();
  else
    net_down(t);
  return 0;
}
