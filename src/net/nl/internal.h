/*
 * USB Arsenal - lifted from Droidspaces (src/net/nl/internal.h)
 *
 * What the netlink files share: the context, the message helpers and the
 * rule attributes. Included last, after <linux/rtnetlink.h>.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef UA_NET_NL_INTERNAL_H
#define UA_NET_NL_INTERNAL_H

/* Netlink rule attributes, not always present in the Android sysroot */
#ifndef FRA_DST
#define FRA_DST 1        /* destination address */
#define FRA_SRC 2        /* source address */
#define FRA_IIFNAME 3    /* input interface name */
#define FRA_PRIORITY 6   /* rule priority / preference */
#define FRA_FWMARK 10    /* fwmark value */
#define FRA_TABLE 15     /* extended table id */
#define FRA_FWMASK 16    /* fwmark mask */
#define FRA_UID_RANGE 20 /* uid range (kernel 4.10+; Android per-app rules) */
#endif

#ifndef FR_ACT_TO_TBL
#define FR_ACT_TO_TBL 1 /* rule action: look up the referenced table */
#endif

/* Internal constants */

#define NL_BUFSIZE 8192

/* Pointer to byte just past the last written byte in a netlink message */
#define NLMSG_TAIL(n)                                                          \
  ((struct rtattr *)(((uint8_t *)(n)) + NLMSG_ALIGN((n)->nlmsg_len)))

struct ds_nl_ctx {
  int fd;       /* AF_NETLINK / NETLINK_ROUTE socket */
  uint32_t seq; /* monotonically increasing sequence number */
  pid_t pid;    /* our PID used as nl_portid */
};

/* core.c */

struct rtattr *nl_addattr(struct nlmsghdr *n, int maxlen, int type,
                          const void *data, int dlen);
struct rtattr *nl_nest_begin(struct nlmsghdr *n, int maxlen, int type);
void nl_nest_end(struct nlmsghdr *n, struct rtattr *nest);
uint32_t nl_rta_u32(struct rtattr *rta);
int ds_nl_talk(ds_nl_ctx_t *ctx, struct nlmsghdr *req);

#endif /* UA_NET_NL_INTERNAL_H */
