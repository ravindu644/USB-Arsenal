/*
 * USB Arsenal - networking pieces lifted from Droidspaces by the same author
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef UA_NET_H
#define UA_NET_H

#include <net/if.h>
#include <netinet/in.h>
#include <stddef.h>
#include <stdint.h>

#define DS_DNS_DEFAULT_1 "1.1.1.1"
#define DS_DNS_DEFAULT_2 "8.8.8.8"

/* Router Advertisement frame: ethernet + IPv6 + RA with a prefix option */
#define DS_RA_FRAME_LEN 102
#define DS_RA_ROUTER_LIFETIME 1800
#define DS_RA_INTERVAL_SEC 300

/* netlink.c */

typedef struct ds_nl_ctx ds_nl_ctx_t;

ds_nl_ctx_t *ds_nl_open(void);
void ds_nl_close(ds_nl_ctx_t *ctx);
int ds_nl_link_exists(ds_nl_ctx_t *ctx, const char *ifname);
int ds_nl_get_ifindex(ds_nl_ctx_t *ctx, const char *ifname);
int ds_nl_create_bridge(ds_nl_ctx_t *ctx, const char *name);
int ds_nl_create_veth_in(ds_nl_ctx_t *ctx, const char *host, const char *peer,
                         int peer_netns_fd, const uint8_t *peer_mac);
int ds_nl_set_master(ds_nl_ctx_t *ctx, const char *ifname, const char *master);
int ds_nl_link_up(ds_nl_ctx_t *ctx, const char *ifname);
int ds_nl_del_link(ds_nl_ctx_t *ctx, const char *ifname);
int ds_nl_set_mac(ds_nl_ctx_t *ctx, const char *ifname, const uint8_t mac[6]);
int ds_nl_add_addr4(ds_nl_ctx_t *ctx, const char *ifname, uint32_t ip_be,
                    uint8_t prefix);
int ds_nl_add_addr6(ds_nl_ctx_t *ctx, const char *ifname,
                    const struct in6_addr *ip, uint8_t prefix);
int ds_nl_add_route6(ds_nl_ctx_t *ctx, const struct in6_addr *dst,
                     uint8_t dst_len, int oif_idx);
int ds_nl_rule6(ds_nl_ctx_t *ctx, int add, const struct in6_addr *net,
                uint8_t len, int from_net, int table, int priority);
int ds_nl_get_iface_table(ds_nl_ctx_t *ctx, int family, const char *ifname,
                          int *table_out);
int ds_nl_get_table_default_oif(ds_nl_ctx_t *ctx, int family, int table,
                                char *ifname_out);
int ds_nl_get_android_default(ds_nl_ctx_t *ctx, int family, char *ifname_out,
                              int *table_out);
int ds_nl_add_rule4(ds_nl_ctx_t *ctx, uint32_t src_be, uint8_t src_len,
                    uint32_t dst_be, uint8_t dst_len, int table, int priority);
int ds_nl_del_rule4(ds_nl_ctx_t *ctx, uint32_t src_be, uint8_t src_len,
                    uint32_t dst_be, uint8_t dst_len, int table, int priority);
int ds_nl_list_ifaces(ds_nl_ctx_t *ctx, char names[][IFNAMSIZ], int max);

/* iptables.c: raw ip_tables socket first, the iptables binary as fallback */

int ds_ipt_ensure_masquerade(int family, const char *src_cidr);
int ds_ipt_host_rules_present(int family, const char *iface,
                              const char *src_cidr, int expect_dnat);
int ds_ipt_ensure_forward_accept(int family, const char *iface);
int ds_ipt_ensure_input_accept(int family, const char *iface);
int ds_ipt_ensure_mss_clamp(int family);
int ds_ipt_remove_nat_rules(int family, const char *subnet, const char *iface);
int ds_ipt6_available(void);
int ds_ipt4_nat_available(void);

/* dhcp.c: single-lease DHCP server thread bound to one interface */

void ds_dhcp_server_start(const char *dns, const char *iface,
                          uint32_t offer_ip_be, uint32_t gw_ip_be,
                          uint32_t netmask_be,
                          const struct in6_addr *ra_prefix);
void ds_dhcp_server_stop(void);

/* ra.c */

int ds_ra_is_solicit(const uint8_t *frame, size_t len);
void ds_ra_build(uint8_t *out, const uint8_t mac[6],
                 const struct in6_addr *prefix);

/* netns.c */

int ds_open_container_netns(const char *netns_path);
int ds_netns_link_up(const char *netns_path, const char *ifname);
int ds_netns_has_link(const char *netns_path, const char *ifname);
int ua_container_netns(const char *name, pid_t pid, const char *path, char *out,
                       size_t size);

/* uplink.c */

const char *ua_find_uplink(const char *pin, char *iface_out, int *table_out,
                           int v6);

#endif /* UA_NET_H */
