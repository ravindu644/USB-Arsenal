/*
 * USB Arsenal - lifted from Droidspaces (src/net/iptables.c) by the same author
 *
 * Surgical iptables and ip6tables rule management via the raw IP_TABLES and
 * IP6_TABLES socket APIs. One engine serves both families.
 *
 * Android Safety Contract (NEVER violate these)
 *   • Never flush any chain (would kill Android tethering/hotspot)
 *   • Never change any chain policy
 *   • Never touch rules we did not create
 *   • Only INSERT rules scoped to our bridge/veth and our own subnets
 *   • Always check existence before inserting (fully idempotent)
 *
 * Kernel / API compatibility
 *   • Kernel 3.10+ (Android/Linux)
 *   • Uses getsockopt/setsockopt on an AF_INET or AF_INET6 SOCK_RAW socket
 *   • The raw socket is always tried first. The iptables(8) / ip6tables(8)
 *     binary is the fallback for whatever the kernel rejects, and the only
 *     path for port forwards.
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "net.h"
#include "usbarsenal.h"
#include <arpa/inet.h>
#include <linux/netfilter.h>
#include <linux/netfilter/nf_nat.h>
#include <linux/netfilter/x_tables.h>
#include <linux/netfilter/xt_TCPMSS.h>
#include <linux/netfilter/xt_tcpudp.h>
#include <linux/netfilter_ipv4/ip_tables.h>
#include <linux/netfilter_ipv6/ip6_tables.h>

/* Address family descriptor
 *
 * ip_tables and ip6_tables are the same machine with a different rule header.
 * The table-level structs (getinfo, get_entries, replace) and the sockopt
 * numbers are identical, so the engine below uses the ipt_ names for both.
 * A rule entry is [family IP header][common tail], which moves target_offset
 * and next_offset and changes the address width. Everything family specific
 * is in this struct and in entry_view(). */

struct xt_family {
  int af;            /* AF_INET or AF_INET6: the raw socket's family */
  int level;         /* IPPROTO_IP or IPPROTO_IPV6: the sockopt level */
  size_t entry_sz;   /* sizeof the family's rule entry */
  size_t off_target; /* offset of target_offset inside an entry */
  size_t off_next;   /* offset of next_offset inside an entry */
  size_t addr_len;   /* 4 or 16 */
  const char *bin;   /* fallback binary */
};

static const struct xt_family xt_v4 = {
    AF_INET,
    IPPROTO_IP,
    sizeof(struct ipt_entry),
    offsetof(struct ipt_entry, target_offset),
    offsetof(struct ipt_entry, next_offset),
    4,
    "iptables",
};

static const struct xt_family xt_v6 = {
    AF_INET6,
    IPPROTO_IPV6,
    sizeof(struct ip6t_entry),
    offsetof(struct ip6t_entry, target_offset),
    offsetof(struct ip6t_entry, next_offset),
    16,
    "ip6tables",
};

static const struct xt_family *xt_family_of(int family) {
  return family == AF_INET6 ? &xt_v6 : &xt_v4;
}

/* The engine relies on the two APIs sharing these layouts and numbers. If a
 * kernel header ever breaks one of them the build stops here, rather than a
 * table replace corrupting someone's firewall. */
#define DS_XT_SAME(name, cond) typedef char ds_xt_same_##name[(cond) ? 1 : -1]
DS_XT_SAME(getinfo, sizeof(struct ipt_getinfo) == sizeof(struct ip6t_getinfo));
DS_XT_SAME(getinfo_size, offsetof(struct ipt_getinfo, size) ==
                             offsetof(struct ip6t_getinfo, size));
DS_XT_SAME(replace, sizeof(struct ipt_replace) == sizeof(struct ip6t_replace));
DS_XT_SAME(replace_counters, offsetof(struct ipt_replace, counters) ==
                                 offsetof(struct ip6t_replace, counters));
DS_XT_SAME(entrytable, offsetof(struct ipt_get_entries, entrytable) ==
                           offsetof(struct ip6t_get_entries, entrytable));
DS_XT_SAME(sockopts, IPT_SO_GET_INFO == IP6T_SO_GET_INFO &&
                         IPT_SO_GET_ENTRIES == IP6T_SO_GET_ENTRIES &&
                         IPT_SO_SET_REPLACE == IP6T_SO_SET_REPLACE);
DS_XT_SAME(inv_dstip, IPT_INV_DSTIP == IP6T_INV_DSTIP);

static unsigned int ent_u16(const void *e, size_t off) {
  uint16_t v;
  memcpy(&v, (const uint8_t *)e + off, sizeof(v));
  return v;
}

static unsigned int ent_next(const struct xt_family *f, const void *e) {
  return ent_u16(e, f->off_next);
}

static unsigned int ent_target_off(const struct xt_family *f, const void *e) {
  return ent_u16(e, f->off_target);
}

/* The match fields of one entry, whichever family it belongs to. This is the
 * only place that knows the two IP header layouts. */
struct xt_ent_view {
  char *iniface, *outiface;
  unsigned char *in_mask, *out_mask;
  void *src, *smsk, *dst, *dmsk;
  uint16_t *proto;
  uint8_t *flags, *invflags;
};

static void entry_view(const struct xt_family *f, void *e,
                       struct xt_ent_view *v) {
  if (f->af == AF_INET6) {
    struct ip6t_ip6 *ip = &((struct ip6t_entry *)e)->ipv6;
    v->iniface = ip->iniface;
    v->outiface = ip->outiface;
    v->in_mask = ip->iniface_mask;
    v->out_mask = ip->outiface_mask;
    v->src = &ip->src;
    v->smsk = &ip->smsk;
    v->dst = &ip->dst;
    v->dmsk = &ip->dmsk;
    v->proto = &ip->proto;
    v->flags = &ip->flags;
    v->invflags = &ip->invflags;
  } else {
    struct ipt_ip *ip = &((struct ipt_entry *)e)->ip;
    v->iniface = ip->iniface;
    v->outiface = ip->outiface;
    v->in_mask = ip->iniface_mask;
    v->out_mask = ip->outiface_mask;
    v->src = &ip->src;
    v->smsk = &ip->smsk;
    v->dst = &ip->dst;
    v->dmsk = &ip->dmsk;
    v->proto = &ip->proto;
    v->flags = &ip->flags;
    v->invflags = &ip->invflags;
  }
}

/* Read-only walkers hold const blobs; the view is shared with the rule
 * builders, which write through it. */
static void entry_view_ro(const struct xt_family *f, const void *e,
                          struct xt_ent_view *v) {
  entry_view(f, (void *)(uintptr_t)e, v);
}

/* Either family, into 16-byte buffers of which the first addr_len bytes are
 * used. Returns 0, or -1 for a string that is not an address of this family.
 */
static int fam_parse_cidr(const struct xt_family *f, const char *cidr,
                          uint8_t net[16], uint8_t mask[16]) {
  char buf[64];
  safe_strncpy(buf, cidr, sizeof(buf));

  int prefix = (int)f->addr_len * 8;
  char *slash = strchr(buf, '/');
  if (slash) {
    *slash = '\0';
    prefix = atoi(slash + 1);
  }
  if (prefix < 0 || prefix > (int)f->addr_len * 8)
    return -1;

  memset(net, 0, 16);
  memset(mask, 0, 16);
  if (inet_pton(f->af, buf, net) != 1)
    return -1;
  for (size_t i = 0; i < f->addr_len && prefix > 0; i++, prefix -= 8)
    mask[i] = (prefix >= 8) ? 0xff : (uint8_t)(0xff << (8 - prefix));
  return 0;
}

/* Module loader - best-effort, harmless on built-in or absent modprobe.
 *
 * The kernel loads a table's module by itself the first time the table is
 * asked for, but only once ip_tables / ip6_tables has registered the sockopt,
 * and nothing autoloads those. Loading the table modules pulls them in.
 * Called from get_table() when a table turns out to be missing, so a kernel
 * that has everything built in never pays for the ten forks. */

static int modules_probed = 0;

static void probe_iptables_modules(void) {
  if (modules_probed)
    return;
  modules_probed = 1;

  char *mods[] = {"iptable_nat",
                  "iptable_filter",
                  "iptable_mangle",
                  "ip6table_nat",
                  "ip6table_filter",
                  "ip6table_mangle",
                  "ip_conntrack",
                  "xt_conntrack",
                  "nf_nat",
                  "xt_addrtype", /* required for --dst-type LOCAL DNAT */
                  NULL};
  for (int i = 0; mods[i]; i++) {
    char *a[] = {"modprobe", "-q", mods[i], NULL};
    run_command_quiet(a);
  }
}

/* Binary fallback
 *
 * One rule through iptables(8) or ip6tables(8). `spec` is everything after
 * the chain name. The binary knows nothing about what we inserted earlier, so
 * bin_ensure() checks before it inserts: without that, every container start
 * on a host that has no raw API would stack another copy. */

static int bin_rule(const struct xt_family *f, const char *op,
                    const char *table, const char *chain,
                    const char *const spec[]) {
  const char *head[] = {f->bin, "-t", table, op, chain};
  char *argv[24];
  size_t n = 0;

  for (size_t i = 0; i < sizeof(head) / sizeof(head[0]); i++)
    argv[n++] = (char *)(uintptr_t)head[i];
  for (size_t i = 0; spec[i] && n < sizeof(argv) / sizeof(argv[0]) - 1; i++)
    argv[n++] = (char *)(uintptr_t)spec[i];
  argv[n] = NULL;
  return run_command_quiet(argv);
}

static int bin_ensure(const struct xt_family *f, const char *table,
                      const char *chain, const char *const spec[]) {
  if (bin_rule(f, "-C", table, chain, spec) == 0)
    return 0;
  /* An exit status is positive and every caller tests for < 0, so a refused
   * rule used to read as success and NAT66 was announced with no MASQUERADE. */
  return bin_rule(f, "-I", table, chain, spec) == 0 ? 0 : -1;
}

static const char *const k_mss_spec[] = {
    "-p", "tcp",    "--tcp-flags",         "SYN,RST", "SYN",
    "-j", "TCPMSS", "--clamp-mss-to-pmtu", NULL};

/* Internal: get table info + entries blob via getsockopt
 *
 * Returns 0 on success.  *entries_out points to the allocated
 * ipt_get_entries struct; caller frees it.
 *
 * Convenience macro: ENTRIES_BLOB(base) → pointer to the raw rule bytes
 * inside the ipt_get_entries allocation. */

static int get_table(const struct xt_family *f, int fd, const char *table_name,
                     struct ipt_getinfo *info, unsigned char **entries_out) {
  memset(info, 0, sizeof(*info));
  safe_strncpy(info->name, table_name, sizeof(info->name));
  socklen_t info_len = sizeof(*info);

  while (getsockopt(fd, f->level, IPT_SO_GET_INFO, info, &info_len) < 0) {
    int err = errno;
    /* ENOPROTOOPT: ip_tables itself is not in. ENOENT: this table is not.
     * Load the modules once and ask again. */
    if ((err == ENOENT || err == ENOPROTOOPT) && !modules_probed) {
      probe_iptables_modules();
      continue;
    }
    ua_log("[IPT] %s get_table('%s') GET_INFO failed: %s", f->bin, table_name,
           strerror(err));
    return -err;
  }

  size_t esz = sizeof(struct ipt_get_entries) + info->size;
  struct ipt_get_entries *entries = calloc(1, esz);
  if (!entries)
    return -ENOMEM;

  safe_strncpy(entries->name, table_name, sizeof(entries->name));
  entries->size = info->size;
  socklen_t elen = (socklen_t)esz;

  if (getsockopt(fd, f->level, IPT_SO_GET_ENTRIES, entries, &elen) < 0) {
    int err = errno;
    ua_log("[IPT] %s get_table('%s') GET_ENTRIES failed: %s", f->bin,
           table_name, strerror(err));
    free(entries);
    return -err;
  }

  *entries_out = (unsigned char *)entries;
  return 0;
}

#define ENTRIES_BLOB(base)                                                     \
  ((unsigned char *)((struct ipt_get_entries *)(base))->entrytable)

#define DS_IPT_MATCH_IN 0x01u
#define DS_IPT_MATCH_OUT 0x02u
#define DS_IPT_MATCH_CLAMP 0x04u /* our MSS clamp, see clamp_rule() */
static int is_our_clamp(const struct xt_family *f, const unsigned char *e);

static int hook_contains_offset(const struct ipt_getinfo *info,
                                unsigned int hook_id, unsigned int offset) {
  if (!info || hook_id >= NF_INET_NUMHOOKS ||
      !(info->valid_hooks & (1u << hook_id)))
    return 0;
  return offset >= info->hook_entry[hook_id] &&
         offset < info->underflow[hook_id];
}

static int iface_empty(const char *iface, const unsigned char *mask) {
  for (int i = 0; i < IFNAMSIZ; i++) {
    if (iface[i] != '\0' || mask[i] != 0)
      return 0;
  }
  return 1;
}

static int iface_exact_match(const char *rule_iface,
                             const unsigned char *rule_mask,
                             const char *iface) {
  size_t len;

  if (!iface || !iface[0])
    return 0;

  len = strnlen(iface, IFNAMSIZ);
  if (len == 0 || len >= IFNAMSIZ)
    return 0;
  if (strncmp(rule_iface, iface, IFNAMSIZ) != 0)
    return 0;

  for (size_t i = 0; i <= len; i++) {
    if (rule_mask[i] != 0xff)
      return 0;
  }
  for (size_t i = len + 1; i < IFNAMSIZ; i++) {
    if (rule_mask[i] != 0)
      return 0;
  }
  return 1;
}

static int target_is_accept(const struct xt_entry_target *t) {
  if (strcmp(t->u.user.name, "ACCEPT") == 0)
    return 1;
  if (t->u.user.name[0] == '\0' &&
      t->u.target_size >= XT_ALIGN(sizeof(struct xt_standard_target))) {
    const struct xt_standard_target *st = (const struct xt_standard_target *)t;
    return st->verdict == -NF_ACCEPT - 1;
  }
  return 0;
}

/* Human-readable target name for logging.  Standard targets (ACCEPT and the
 * other built-in verdicts) carry an empty user.name - the verdict is encoded
 * numerically - so decode the common ACCEPT case instead of printing ''. */
static const char *target_label(const struct xt_entry_target *t) {
  if (t->u.user.name[0])
    return t->u.user.name;
  if (target_is_accept(t))
    return "ACCEPT";
  return "standard";
}

/* Internal: walk the blob to find an existing rule matching our fingerprint.
 *
 * All non-NULL criteria must match simultaneously.  src and src_mask point at
 * addr_len bytes, or are NULL for "any source". */

static int rule_exists_in_hook(const struct xt_family *f,
                               const struct ipt_getinfo *info,
                               const unsigned char *blob, unsigned int hook_id,
                               const char *iface_in, const char *iface_out,
                               const void *src, const void *src_mask,
                               const char *target_name) {
  if (!info || !blob || hook_id >= NF_INET_NUMHOOKS ||
      !(info->valid_hooks & (1u << hook_id)))
    return 0;

  unsigned int off = info->hook_entry[hook_id];
  unsigned int end = info->underflow[hook_id];
  if (off > end || end > info->size)
    return 0;

  while (off + f->entry_sz <= end) {
    const unsigned char *e = blob + off;
    unsigned int next = ent_next(f, e);
    unsigned int toff = ent_target_off(f, e);
    if (next < f->entry_sz || next > end - off ||
        toff + sizeof(struct xt_entry_target) > next)
      break;

    const struct xt_entry_target *t =
        (const struct xt_entry_target *)(e + toff);
    struct xt_ent_view v;
    entry_view_ro(f, e, &v);

    int match = 1;

    if (target_name && target_name[0]) {
      if (strcmp(target_name, "ACCEPT") == 0) {
        if (!target_is_accept(t))
          match = 0;
      } else if (strcmp(t->u.user.name, target_name) != 0) {
        match = 0;
      }
    }
    /* NULL: any interface. "": the rule must not be bound to one. */
    if (match && iface_in && strncmp(v.iniface, iface_in, IFNAMSIZ) != 0)
      match = 0;
    if (match && iface_out && strncmp(v.outiface, iface_out, IFNAMSIZ) != 0)
      match = 0;
    if (match && src &&
        (memcmp(v.src, src, f->addr_len) != 0 ||
         memcmp(v.smsk, src_mask, f->addr_len) != 0))
      match = 0;

    if (match)
      return 1;
    off += next;
  }
  return 0;
}

/* The standard target of an entry, or NULL when the entry is malformed or its
 * target is an extension. Jump fixups only ever touch standard targets. */
static struct xt_standard_target *
entry_standard_target(const struct xt_family *f, unsigned char *e,
                      unsigned int next) {
  unsigned int toff = ent_target_off(f, e);
  if (toff + sizeof(struct xt_standard_target) > next)
    return NULL;

  struct xt_entry_target *t = (struct xt_entry_target *)(e + toff);
  if (t->u.user.name[0] != '\0' ||
      t->u.target_size != (uint16_t)XT_ALIGN(sizeof(struct xt_standard_target)))
    return NULL;
  return (struct xt_standard_target *)t;
}

/* Internal: fixup_jump_targets
 *
 * After inserting new_rule_sz bytes at insert_off, any xt_standard_target
 * with a positive verdict (= absolute byte offset = chain jump) that pointed
 * to an entry AT OR AFTER insert_off must be incremented by new_rule_sz. */

static void fixup_jump_targets(const struct xt_family *f, unsigned char *blob,
                               unsigned int blob_sz, unsigned int insert_off,
                               unsigned int delta) {
  unsigned int off = 0;

  while (off < blob_sz) {
    unsigned char *e = blob + off;
    unsigned int next = ent_next(f, e);

    if (next < f->entry_sz || off + next > blob_sz)
      break;

    struct xt_standard_target *st = entry_standard_target(f, e, next);
    if (st && st->verdict >= (int)insert_off)
      st->verdict += (int)delta;

    off += next;
  }
}

/* Internal: fixup_jump_targets_removed
 *
 * Removal twin of fixup_jump_targets.  After rules are deleted the surviving
 * entries shift to lower offsets, but every xt_standard_target with a
 * non-negative verdict (= absolute byte offset = chain jump) still holds its
 * OLD offset.  Re-base each by the number of bytes removed before that offset:
 * old_offsets[k] gives an entry's old start, removed_before[k] the bytes
 * removed before it.  Without this the kernel's mark_source_chains() validator
 * can no longer resolve the shifted jump (xt_find_jump_offset fails) and
 * rejects the whole table replace with ELOOP. */

static void fixup_jump_targets_removed(const struct xt_family *f,
                                       unsigned char *blob,
                                       unsigned int blob_sz,
                                       const unsigned int *old_offsets,
                                       const unsigned int *removed_before,
                                       unsigned int nents) {
  unsigned int off = 0;

  while (off < blob_sz) {
    unsigned char *e = blob + off;
    unsigned int next = ent_next(f, e);

    if (next < f->entry_sz || off + next > blob_sz)
      break;

    struct xt_standard_target *st = entry_standard_target(f, e, next);
    if (st && st->verdict >= 0) {
      /* Jump target: find the tracked entry that started at this old
       * offset and subtract the bytes removed before it. */
      for (unsigned int k = 0; k < nents; k++) {
        if (old_offsets[k] == (unsigned int)st->verdict) {
          st->verdict -= (int)removed_before[k];
          break;
        }
      }
    }

    off += next;
  }
}

/* Internal: insert_rule_at_hook
 *
 * Inserts new_rule at the very beginning of the given hook's chain. */

static int insert_rule_at_hook(const struct xt_family *f, int fd,
                               const char *table_name,
                               struct ipt_getinfo *info_in,
                               unsigned char *blob_in, unsigned int hook_id,
                               const void *new_rule, unsigned int new_rule_sz) {
  /*
   * cur_info / cur_blob track the table state we are working from.
   * They start as the caller-supplied values.  On EAGAIN we refetch the table
   * and update these so the next attempt uses a fresh snapshot.
   *
   * cur_base: the allocation returned by get_table() on a refetch.
   *           NULL means we are still using the caller-owned blob_in.
   *           Non-NULL means we own it and must free it on exit.
   */
  struct ipt_getinfo cur_info = *info_in;
  unsigned char *cur_base = NULL; /* NULL → caller owns initial blob */
  unsigned char *cur_blob = blob_in;

  int max_retries = 5; /* generous: handles bursts of netd activity */
  int ret = -1, err = EAGAIN;

  ua_log("[IPT] insert_rule_at_hook: %s table='%s' hook=%u rule_sz=%u", f->bin,
         table_name, hook_id, new_rule_sz);

  while (max_retries-- > 0) {
    unsigned int insert_off = cur_info.hook_entry[hook_id];
    unsigned int old_sz = cur_info.size;
    unsigned int new_sz = old_sz + new_rule_sz;

    ua_log("[IPT] insert_rule_at_hook: table='%s' hook=%u insert_off=%u "
           "old_sz=%u new_sz=%u",
           table_name, hook_id, insert_off, old_sz, new_sz);

    size_t replace_sz = sizeof(struct ipt_replace) + new_sz;
    struct ipt_replace *repl = calloc(1, replace_sz);
    if (!repl) {
      err = ENOMEM;
      break;
    }

    safe_strncpy(repl->name, table_name, sizeof(repl->name));
    repl->valid_hooks = cur_info.valid_hooks;
    repl->num_entries = cur_info.num_entries + 1;
    repl->size = new_sz;

    /* num_counters = OLD count */
    repl->num_counters = cur_info.num_entries;

    /* Counters buffer: kernel writes OLD entry counters back into this array.
     * Must hold cur_info.num_entries (the live count) values, not
     * num_entries+1. */
    repl->counters = calloc(cur_info.num_entries ? cur_info.num_entries : 1,
                            sizeof(struct xt_counters));
    if (!repl->counters) {
      free(repl);
      err = ENOMEM;
      break;
    }

    /* hook_entry/underflow offset adjustment */
    for (int h = 0; h < NF_INET_NUMHOOKS; h++) {
      if (!(cur_info.valid_hooks & (1u << h)))
        continue;

      repl->hook_entry[h] = cur_info.hook_entry[h];
      repl->underflow[h] = cur_info.underflow[h];

      /* hook_entry: strictly greater - the chain we insert INTO keeps its start
       */
      if (cur_info.hook_entry[h] > insert_off)
        repl->hook_entry[h] += new_rule_sz;

      /* underflow: at-or-after - terminal entries always shift */
      if (cur_info.underflow[h] >= insert_off)
        repl->underflow[h] += new_rule_sz;

      ua_log("[IPT]   hook[%d]: entry %u→%u  underflow %u→%u", h,
             cur_info.hook_entry[h], repl->hook_entry[h], cur_info.underflow[h],
             repl->underflow[h]);
    }

    /* Build new blob: prefix | new_rule | suffix */
    unsigned char *nb = (unsigned char *)(repl + 1);
    if (insert_off > 0)
      memcpy(nb, cur_blob, insert_off);
    memcpy(nb + insert_off, new_rule, new_rule_sz);
    if (old_sz > insert_off)
      memcpy(nb + insert_off + new_rule_sz, cur_blob + insert_off,
             old_sz - insert_off);

    /* Patch stale jump verdicts in the shifted suffix */
    fixup_jump_targets(f, nb, new_sz, insert_off, new_rule_sz);

    ret = setsockopt(fd, f->level, IPT_SO_SET_REPLACE, repl,
                     (socklen_t)replace_sz);
    err = errno;

    free(repl->counters);
    free(repl);

    if (ret == 0)
      break;

    if (err == EAGAIN && max_retries > 0) {
      ua_log("[IPT]   EAGAIN (attempt remaining=%d), refetching '%s' table",
             max_retries, table_name);
      usleep(5000 + 10000 * (4 - max_retries)); /* 5 / 15 / 25 / 35 ms */

      /* Free any previously refetched base and get a fresh snapshot. */
      if (cur_base) {
        free(cur_base);
        cur_base = NULL;
      }

      struct ipt_getinfo new_info;
      unsigned char *new_base = NULL;
      if (get_table(f, fd, table_name, &new_info, &new_base) < 0) {
        ua_log("[IPT]   refetch of '%s' failed, giving up", table_name);
        ret = -1;
        err = EAGAIN;
        break;
      }

      cur_base = new_base;
      cur_blob = ENTRIES_BLOB(new_base);
      cur_info = new_info;
      continue;
    }

    break; /* non-EAGAIN error, or last attempt exhausted */
  }

  if (cur_base)
    free(cur_base);

  if (ret < 0) {
    if (err != ENOENT && err != EAGAIN)
      ua_log("[IPT] insert_rule_at_hook: kernel rejected blob (errno=%d %s)",
             err, strerror(err));
    return -err;
  }
  return 0;
}

/* Internal: remove_matching_rules
 *
 * Builds a new blob that omits every entry matching any of our fingerprints,
 * then submits it atomically via IPT_SO_SET_REPLACE.
 *
 * Two-pass algorithm avoids in-place mutation of the blob being walked.
 *
 * match_src / match_mask point at addr_len bytes, or are NULL when only the
 * interface rules are wanted.
 *
 * Returns the number of rules removed (0 when nothing matched), or a negative
 * errno when the table replace failed. */

static int remove_matching_rules(const struct xt_family *f, int fd,
                                 const char *table_name,
                                 struct ipt_getinfo *info_in,
                                 unsigned char *blob_in, unsigned int hook_id,
                                 const void *match_src, const void *match_mask,
                                 const char *match_iface,
                                 unsigned int iface_flags) {
  if (!info_in || !blob_in || hook_id >= NF_INET_NUMHOOKS ||
      !(info_in->valid_hooks & (1u << hook_id)))
    return 0;

  /*
   * cur_info / cur_blob track the table state we are working from.
   * They start as the caller-supplied values.  On EAGAIN we refetch the table
   * and update these so the next attempt uses a fresh snapshot of the rules.
   *
   * cur_base: the allocation returned by get_table() on a refetch.
   *           NULL means we are still using the caller-owned blob_in.
   *           Non-NULL means we own it and must free it on exit.
   */
  struct ipt_getinfo cur_info = *info_in;
  unsigned char *cur_base = NULL; /* NULL → caller owns initial blob */
  unsigned char *cur_blob = blob_in;

  int max_retries = 5;
  int ret = 0, err = 0;
  int removed = 0;

  while (max_retries-- > 0) {
    unsigned char *new_blob = malloc(cur_info.size);
    if (!new_blob) {
      /* On an EAGAIN refetch iteration cur_base owns a get_table() blob; free
       * it before bailing so the early return does not leak it. */
      if (cur_base)
        free(cur_base);
      return -ENOMEM;
    }

    /* Allocate per-entry tracking arrays */
    unsigned int *old_offsets =
        calloc(cur_info.num_entries + 1, sizeof(unsigned int));
    unsigned int *removed_before =
        calloc(cur_info.num_entries + 1, sizeof(unsigned int));

    if (!old_offsets || !removed_before) {
      free(new_blob);
      free(old_offsets);
      free(removed_before);
      ret = -1;
      err = ENOMEM;
      break;
    }

    unsigned int new_sz = 0;
    unsigned int removed_count = 0;
    unsigned int cumulative_gone = 0;
    unsigned int offset = 0;
    unsigned int ei = 0;

    /* Pass 1: walk, classify, build new_blob */
    while (offset + f->entry_sz <= cur_info.size && ei < cur_info.num_entries) {
      const unsigned char *e = cur_blob + offset;
      unsigned int next = ent_next(f, e);
      unsigned int toff = ent_target_off(f, e);
      if (next < f->entry_sz || next > cur_info.size - offset ||
          toff + sizeof(struct xt_entry_target) > next)
        break;

      old_offsets[ei] = offset;
      removed_before[ei] = cumulative_gone;

      const struct xt_entry_target *t =
          (const struct xt_entry_target *)(e + toff);
      const char *tname = t->u.user.name;
      struct xt_ent_view v;
      entry_view_ro(f, e, &v);

      int is_ours = 0;

      if (!hook_contains_offset(&cur_info, hook_id, offset))
        goto keep_rule;

      /* MASQUERADE for our subnet */
      if (match_src && strcmp(tname, "MASQUERADE") == 0 &&
          memcmp(v.src, match_src, f->addr_len) == 0 &&
          memcmp(v.smsk, match_mask, f->addr_len) == 0 &&
          memcmp(v.dst, match_src, f->addr_len) == 0 &&
          memcmp(v.dmsk, match_mask, f->addr_len) == 0 &&
          (*v.invflags & IPT_INV_DSTIP))
        is_ours = 1;

      /* Our MSS clamp, and only ours: a foreign TCPMSS rule stays. */
      if (!is_ours && (iface_flags & DS_IPT_MATCH_CLAMP) && is_our_clamp(f, e))
        is_ours = 1;

      /* ACCEPT on the exact interface/direction we inserted. */
      if (!is_ours && match_iface && match_iface[0] && target_is_accept(t)) {
        if ((iface_flags & DS_IPT_MATCH_IN) &&
            iface_exact_match(v.iniface, v.in_mask, match_iface) &&
            iface_empty(v.outiface, v.out_mask))
          is_ours = 1;
        if ((iface_flags & DS_IPT_MATCH_OUT) &&
            iface_exact_match(v.outiface, v.out_mask, match_iface) &&
            iface_empty(v.iniface, v.in_mask))
          is_ours = 1;
      }

    keep_rule:
      if (is_ours) {
        /* Safety: never remove an underflow (chain policy) entry. */
        int is_underflow = 0;
        for (int h = 0; h < NF_INET_NUMHOOKS; h++) {
          if ((cur_info.valid_hooks & (1u << h)) &&
              offset == cur_info.underflow[h]) {
            is_underflow = 1;
            break;
          }
        }
        if (is_underflow) {
          ua_warn("[IPT] remove: would remove underflow entry at offset %u - "
                  "skipping",
                  offset);
          memcpy(new_blob + new_sz, e, next);
          new_sz += next;
          offset += next;
          ei++;
          continue;
        }
        ua_log("[IPT] remove: matched %s '%s' rule at offset %u", f->bin,
               target_label(t), offset);
        cumulative_gone += next;
        removed_count++;
      } else {
        memcpy(new_blob + new_sz, e, next);
        new_sz += next;
      }

      offset += next;
      ei++;
    }
    old_offsets[ei] = offset;
    removed_before[ei] = cumulative_gone;

    if (removed_count == 0) {
      free(new_blob);
      free(old_offsets);
      free(removed_before);
      ret = 0; /* nothing to do */
      break;
    }

    /* Re-base surviving jump verdicts for the bytes just removed; without this
     * the kernel rejects the table replace with ELOOP (see
     * fixup_jump_targets_removed). */
    fixup_jump_targets_removed(f, new_blob, new_sz, old_offsets, removed_before,
                               ei + 1);

    /* Build ipt_replace */
    size_t replace_sz = sizeof(struct ipt_replace) + new_sz;
    struct ipt_replace *repl = calloc(1, replace_sz);
    if (!repl) {
      free(new_blob);
      free(old_offsets);
      free(removed_before);
      ret = -1;
      err = ENOMEM;
      break;
    }

    safe_strncpy(repl->name, table_name, sizeof(repl->name));
    repl->valid_hooks = cur_info.valid_hooks;
    repl->num_entries = cur_info.num_entries - removed_count;
    repl->size = new_sz;

    /* num_counters: must be the OLD count */
    repl->num_counters = cur_info.num_entries;
    repl->counters = calloc(cur_info.num_entries ? cur_info.num_entries : 1,
                            sizeof(struct xt_counters));

    if (!repl->counters) {
      free(repl);
      free(new_blob);
      free(old_offsets);
      free(removed_before);
      ret = -1;
      err = ENOMEM;
      break;
    }

    /* Pass 2: fix up hook_entry / underflow offsets */
    for (int h = 0; h < NF_INET_NUMHOOKS; h++) {
      if (!(cur_info.valid_hooks & (1u << h)))
        continue;

      unsigned int adj = 0;
      for (unsigned int k = 0; k <= ei; k++) {
        if (old_offsets[k] >= cur_info.hook_entry[h]) {
          adj = removed_before[k];
          break;
        }
      }
      repl->hook_entry[h] = cur_info.hook_entry[h] - adj;

      adj = 0;
      for (unsigned int k = 0; k <= ei; k++) {
        if (old_offsets[k] >= cur_info.underflow[h]) {
          adj = removed_before[k];
          break;
        }
      }
      repl->underflow[h] = cur_info.underflow[h] - adj;
    }

    memcpy(repl + 1, new_blob, new_sz);

    ret = setsockopt(fd, f->level, IPT_SO_SET_REPLACE, repl,
                     (socklen_t)replace_sz);
    err = errno;

    free(repl->counters);
    free(repl);
    free(new_blob);
    free(old_offsets);
    free(removed_before);

    if (ret == 0) {
      removed = (int)removed_count;
      break;
    }

    if (err == EAGAIN && max_retries > 0) {
      ua_log("[IPT] remove: EAGAIN (attempt remaining=%d), refetching '%s'",
             max_retries, table_name);
      usleep(5000 + 10000 * (4 - max_retries));

      if (cur_base) {
        free(cur_base);
        cur_base = NULL;
      }

      struct ipt_getinfo new_info;
      unsigned char *new_base = NULL;
      if (get_table(f, fd, table_name, &new_info, &new_base) < 0) {
        ua_log("[IPT] remove: refetch of '%s' failed, giving up", table_name);
        ret = -1;
        err = EAGAIN;
        break;
      }

      cur_base = new_base;
      cur_blob = ENTRIES_BLOB(new_base);
      cur_info = new_info;
      continue;
    }

    ua_warn("[IPT] remove: table replace on '%s' failed (%s) - falling back "
            "to the %s binary",
            table_name, strerror(err), f->bin);
    break;
  }

  if (cur_base)
    free(cur_base);

  return (ret < 0) ? -err : removed;
}

/* Internal: open a raw socket (shared across public APIs) */

static int open_raw_socket(const struct xt_family *f) {
  int fd = socket(f->af, SOCK_RAW | SOCK_CLOEXEC, IPPROTO_RAW);
  if (fd < 0)
    ua_log("[IPT] Failed to open %s raw socket: %s", f->bin, strerror(errno));
  return fd;
}

/* Start a rule in buf: zeroed, offsets set for a target of target_sz bytes.
 * Returns the target slot. buf must hold XT_ALIGN(entry_sz) + target_sz. */
static struct xt_entry_target *
rule_begin(const struct xt_family *f, unsigned char *buf, size_t target_sz) {
  size_t hdr = XT_ALIGN(f->entry_sz);
  uint16_t toff = (uint16_t)hdr, next = (uint16_t)(hdr + target_sz);

  memset(buf, 0, hdr + target_sz);
  memcpy(buf + f->off_target, &toff, sizeof(toff));
  memcpy(buf + f->off_next, &next, sizeof(next));
  return (struct xt_entry_target *)(buf + hdr);
}

/* The MSS clamp is [entry][tcp match][TCPMSS target]: the one rule we build
 * with a match extension. */
#define DS_XT_CLAMP_MATCH_SZ                                                   \
  XT_ALIGN(sizeof(struct xt_entry_match) + sizeof(struct xt_tcp))
#define DS_XT_CLAMP_TARGET_SZ                                                  \
  XT_ALIGN(sizeof(struct xt_entry_target) + sizeof(struct xt_tcpmss_info))
#define DS_XT_NAT_TARGET_SZ                                                    \
  XT_ALIGN(sizeof(struct xt_entry_target) + sizeof(struct nf_nat_range))

/* Big enough for any rule we build, in either family. */
#define DS_XT_RULE_MAX                                                         \
  (XT_ALIGN(sizeof(struct ip6t_entry)) +                                       \
   (DS_XT_CLAMP_MATCH_SZ + DS_XT_CLAMP_TARGET_SZ > DS_XT_NAT_TARGET_SZ         \
        ? DS_XT_CLAMP_MATCH_SZ + DS_XT_CLAMP_TARGET_SZ                         \
        : DS_XT_NAT_TARGET_SZ))

/* Build "-p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu" in
 * buf, which must hold DS_XT_RULE_MAX bytes. Returns the rule's size. The
 * kernel accepts a clamp only on a rule that matches TCP SYNs, which is what
 * the tcp match says. */
static unsigned int clamp_rule(const struct xt_family *f, unsigned char *buf) {
  size_t hdr = XT_ALIGN(f->entry_sz);
  uint16_t toff = (uint16_t)(hdr + DS_XT_CLAMP_MATCH_SZ);
  uint16_t next = (uint16_t)(toff + DS_XT_CLAMP_TARGET_SZ);

  memset(buf, 0, next);
  memcpy(buf + f->off_target, &toff, sizeof(toff));
  memcpy(buf + f->off_next, &next, sizeof(next));

  struct xt_ent_view v;
  entry_view(f, buf, &v);
  *v.proto = IPPROTO_TCP;
  if (f->af == AF_INET6)
    *v.flags |= IP6T_F_PROTO; /* ip6t only looks at proto when told to */

  struct xt_entry_match *m = (struct xt_entry_match *)(buf + hdr);
  m->u.match_size = DS_XT_CLAMP_MATCH_SZ;
  safe_strncpy(m->u.user.name, "tcp", sizeof(m->u.user.name));
  struct xt_tcp *tcp = (struct xt_tcp *)m->data;
  tcp->spts[1] = tcp->dpts[1] = 0xffff; /* any port */
  tcp->flg_mask = 0x02 | 0x04;          /* SYN,RST */
  tcp->flg_cmp = 0x02;                  /* SYN */

  struct xt_entry_target *t = (struct xt_entry_target *)(buf + toff);
  t->u.target_size = DS_XT_CLAMP_TARGET_SZ;
  safe_strncpy(t->u.user.name, "TCPMSS", sizeof(t->u.user.name));
  ((struct xt_tcpmss_info *)t->data)->mss = XT_TCPMSS_CLAMP_PMTU;
  return next;
}

/* Is this entry the clamp clamp_rule() builds, byte for byte in the parts
 * that matter? Used by the remover so that only our rule goes, never a VPN's
 * TCPMSS rule that happens to share the chain. */
static int is_our_clamp(const struct xt_family *f, const unsigned char *e) {
  unsigned int toff = ent_target_off(f, e);
  if (toff != XT_ALIGN(f->entry_sz) + DS_XT_CLAMP_MATCH_SZ)
    return 0;
  struct xt_ent_view v;
  entry_view_ro(f, e, &v);
  if (*v.proto != IPPROTO_TCP || !iface_empty(v.iniface, v.in_mask) ||
      !iface_empty(v.outiface, v.out_mask))
    return 0;

  const struct xt_entry_match *m =
      (const struct xt_entry_match *)(e + XT_ALIGN(f->entry_sz));
  const struct xt_tcp *tcp = (const struct xt_tcp *)m->data;
  if (m->u.match_size != DS_XT_CLAMP_MATCH_SZ ||
      strcmp(m->u.user.name, "tcp") != 0 || tcp->flg_mask != (0x02 | 0x04) ||
      tcp->flg_cmp != 0x02 || tcp->invflags != 0)
    return 0;

  const struct xt_entry_target *t = (const struct xt_entry_target *)(e + toff);
  return t->u.target_size == DS_XT_CLAMP_TARGET_SZ &&
         strcmp(t->u.user.name, "TCPMSS") == 0 &&
         ((const struct xt_tcpmss_info *)t->data)->mss == XT_TCPMSS_CLAMP_PMTU;
}

/* Public API: ds_ipt_host_rules_present
 *
 * Fork-free probe for the whole host-side rule set of one family:
 *   filter INPUT       -i <iface> ACCEPT
 *   filter FORWARD     -i <iface> ACCEPT
 *   filter FORWARD     -o <iface> ACCEPT
 *   nat    POSTROUTING -s <cidr> ! -d <cidr> MASQUERADE
 *   nat    PREROUTING  DNAT                     (only if expect_dnat)
 *   mangle POSTROUTING TCPMSS
 *
 * The route monitor polls this as its "have our rules been removed?" signal,
 * so it must stay cheap: one getsockopt pair per table, no iptables binary.
 * Checking every rule rather than one canary matters because a netd restart is
 * not the only thing that removes them - firewall apps rewrite the filter
 * chains while leaving nat's MASQUERADE untouched, which a MASQUERADE-only
 * probe would never notice.
 *
 * Two deliberate limits, both bounded by what the entry blob exposes without
 * parsing xt match payloads:
 *   - The DNAT and TCPMSS checks match on target name, so they prove that
 *     port forwarding / MSS clamping is still installed, not that every
 *     individual --port mapping is.  A single mapping deleted on its own is
 *     not detected; anything that removes them as a group is.
 *   - An unreadable mangle table is not treated as failure.  MSS clamping is
 *     an MTU guard rather than connectivity, and failing the whole probe there
 *     would stop us reconciling the rules that do matter.
 *
 * Returns 1 when all of them are present, 0 when any is missing, and -1 when
 * the filter or nat table could not be read.  Callers must treat -1 as
 * "unknown" and do nothing: on a kernel with no raw API the rules live
 * wherever the binary put them, and this probe cannot see them. */

int ds_ipt_host_rules_present(int family, const char *iface,
                              const char *src_cidr, int expect_dnat) {
  const struct xt_family *f = xt_family_of(family);
  uint8_t net[16], mask[16];
  if (fam_parse_cidr(f, src_cidr, net, mask) < 0)
    return -1;

  int fd = open_raw_socket(f);
  if (fd < 0)
    return -1;

  struct ipt_getinfo info;
  unsigned char *base = NULL;

  /* filter: the three iface ACCEPT rules, in one table read. */
  if (get_table(f, fd, "filter", &info, &base) < 0) {
    close(fd);
    return -1;
  }
  int present =
      rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_LOCAL_IN, iface,
                          NULL, NULL, NULL, "ACCEPT") &&
      rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_FORWARD, iface,
                          NULL, NULL, NULL, "ACCEPT") &&
      rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_FORWARD, NULL,
                          iface, NULL, NULL, "ACCEPT");
  free(base);

  /* nat: MASQUERADE, plus a port-forward DNAT when --port is configured.
   * Skipped once something is already known missing - the caller reinstalls
   * the full set either way. */
  if (present) {
    base = NULL;
    if (get_table(f, fd, "nat", &info, &base) < 0) {
      close(fd);
      return -1;
    }
    present =
        rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_POST_ROUTING,
                            NULL, NULL, net, mask, "MASQUERADE");
    if (present && expect_dnat)
      present =
          rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_PRE_ROUTING,
                              NULL, NULL, NULL, NULL, "DNAT");
    free(base);
  }

  /* mangle: the MSS clamp, by the installer's own test: a TCPMSS target not
   * bound to an interface. An unreadable mangle table leaves the verdict
   * untouched rather than failing the probe - see the header comment. */
  if (present) {
    base = NULL;
    if (get_table(f, fd, "mangle", &info, &base) == 0) {
      present = rule_exists_in_hook(f, &info, ENTRIES_BLOB(base),
                                    NF_INET_POST_ROUTING, "", "", NULL, NULL,
                                    "TCPMSS");
      free(base);
    }
  }

  close(fd);
  return present ? 1 : 0;
}

/* Public API: ds_ipt6_available
 *
 * Can this host do NAT66 at all? That takes two things: the ip6 nat table
 * (CONFIG_IP6_NF_NAT, which stock GKI kernels leave out) and an IPv6
 * MASQUERADE target, which is a separate option on older kernels. The kernel
 * is asked for both, the same way ip6tables resolves a target, so the answer
 * holds whatever the config option is called on this version. Both the runtime
 * and `droidspaces check` come through here. */

int ds_ipt6_available(void) {
  int fd = open_raw_socket(&xt_v6);
  if (fd >= 0) {
    struct ipt_getinfo info;
    unsigned char *base = NULL;
    if (get_table(&xt_v6, fd, "nat", &info, &base) == 0) {
      free(base);
      /* Revision 0 is the one raw_ensure_masquerade() inserts. */
      struct xt_get_revision rev = {.name = "MASQUERADE"};
      socklen_t len = sizeof(rev);
      int ret =
          getsockopt(fd, xt_v6.level, IP6T_SO_GET_REVISION_TARGET, &rev, &len);
      close(fd);
      return ret >= 0;
    }
    close(fd);
  }

  /* No raw API (nftables only kernel), so only the binary can ask. Listing
   * the table would not load the target, so append the real thing to a chain
   * nothing jumps to and take it away again. No packet ever sees it. */
  const char *const none[] = {NULL};
  const char *const masq[] = {"-j", "MASQUERADE", NULL};
  bin_rule(&xt_v6, "-N", "nat", "ds-cap-masq", none);
  int ok = bin_rule(&xt_v6, "-A", "nat", "ds-cap-masq", masq) == 0;
  bin_rule(&xt_v6, "-F", "nat", "ds-cap-masq", none);
  bin_rule(&xt_v6, "-X", "nat", "ds-cap-masq", none);
  return ok;
}

/* Internal: raw_ensure_masquerade
 *
 * Returns 0 when the rule is in the nat table (already or newly), or a
 * negative errno when the raw path could not put it there. */

static int raw_ensure_masquerade(const struct xt_family *f, const uint8_t *net,
                                 const uint8_t *mask) {
  int fd = open_raw_socket(f);
  if (fd < 0)
    return -ENOPROTOOPT;

  struct ipt_getinfo info;
  unsigned char *base = NULL;
  int ret = get_table(f, fd, "nat", &info, &base);
  if (ret < 0) {
    close(fd);
    return ret;
  }

  /* Idempotency check */
  if (rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), NF_INET_POST_ROUTING,
                          NULL, NULL, net, mask, "MASQUERADE")) {
    ua_log("[IPT] %s MASQUERADE already present - skipping", f->bin);
    free(base);
    close(fd);
    return 0;
  }

  /* The kernel validates the target payload size per family: IPv4 wants the
   * old multi-range struct with rangesize=1, IPv6 a plain nf_nat_range. An
   * all-zero range means "pick the outgoing address", which is all we want. */
  size_t payload = (f->af == AF_INET6)
                       ? sizeof(struct nf_nat_range)
                       : sizeof(struct nf_nat_ipv4_multi_range_compat);
  size_t target_sz = XT_ALIGN(sizeof(struct xt_entry_target) + payload);

  unsigned char rule_buf[DS_XT_RULE_MAX];
  struct xt_entry_target *rt = rule_begin(f, rule_buf, target_sz);
  struct xt_ent_view v;
  entry_view(f, rule_buf, &v);

  memcpy(v.src, net, f->addr_len);
  memcpy(v.smsk, mask, f->addr_len);
  memcpy(v.dst, net, f->addr_len);
  memcpy(v.dmsk, mask, f->addr_len);
  *v.invflags = IPT_INV_DSTIP; /* ! -d */

  rt->u.target_size = (__u16)target_sz;
  safe_strncpy(rt->u.user.name, "MASQUERADE", sizeof(rt->u.user.name));
  if (f->af == AF_INET)
    ((struct nf_nat_ipv4_multi_range_compat *)rt->data)->rangesize = 1;

  ret = insert_rule_at_hook(f, fd, "nat", &info, ENTRIES_BLOB(base),
                            NF_INET_POST_ROUTING, rule_buf,
                            (unsigned int)(XT_ALIGN(f->entry_sz) + target_sz));
  free(base);
  close(fd);
  return ret;
}

/* Public API: ds_ipt_ensure_masquerade
 *
 * Inserts: -t nat -I POSTROUTING -s <cidr> ! -d <cidr> -j MASQUERADE */

int ds_ipt_ensure_masquerade(int family, const char *src_cidr) {
  const struct xt_family *f = xt_family_of(family);
  ua_log("[IPT] ensure_masquerade: %s cidr=%s", f->bin, src_cidr);

  uint8_t net[16], mask[16];
  if (fam_parse_cidr(f, src_cidr, net, mask) < 0)
    return -EINVAL;

  int ret = raw_ensure_masquerade(f, net, mask);
  if (ret == 0) {
    ua_log("[IPT] %s MASQUERADE in place via raw socket API", f->bin);
    return 0;
  }

  ua_log("[IPT] %s MASQUERADE raw path failed (ret=%d), using the binary",
         f->bin, ret);
  const char *const spec[] = {"-s",     src_cidr, "!",          "-d",
                              src_cidr, "-j",     "MASQUERADE", NULL};
  return bin_ensure(f, "nat", "POSTROUTING", spec);
}

/* Internal: raw_ensure_iface_accept
 *
 * "-I <chain> [-i|-o] <iface> -j ACCEPT" in the filter table through the raw
 * socket. Returns 0 when the rule is there, a negative errno otherwise. */

static int raw_ensure_iface_accept(const struct xt_family *f, int fd,
                                   unsigned int hook, const char *chain,
                                   const char *iface, int is_out) {
  if (fd < 0)
    return -ENOPROTOOPT;

  struct ipt_getinfo info;
  unsigned char *base = NULL;
  int ret = get_table(f, fd, "filter", &info, &base);
  if (ret < 0)
    return ret;

  const char *want_in = is_out ? NULL : iface;
  const char *want_out = is_out ? iface : NULL;

  if (rule_exists_in_hook(f, &info, ENTRIES_BLOB(base), hook, want_in, want_out,
                          NULL, NULL, "ACCEPT")) {
    ua_log("[IPT] %s %s -%c %s ACCEPT already present", f->bin, chain,
           is_out ? 'o' : 'i', iface);
    free(base);
    return 0;
  }

  size_t target_sz = XT_ALIGN(sizeof(struct xt_standard_target));
  unsigned char rule_buf[DS_XT_RULE_MAX];
  struct xt_standard_target *st =
      (struct xt_standard_target *)rule_begin(f, rule_buf, target_sz);
  struct xt_ent_view v;
  entry_view(f, rule_buf, &v);

  safe_strncpy(is_out ? v.outiface : v.iniface, iface, IFNAMSIZ);
  memset(is_out ? v.out_mask : v.in_mask, 0xff,
         strnlen(iface, IFNAMSIZ - 1) + 1);

  st->target.u.target_size = (__u16)target_sz;
  st->verdict = -NF_ACCEPT - 1;

  ret = insert_rule_at_hook(f, fd, "filter", &info, ENTRIES_BLOB(base), hook,
                            rule_buf,
                            (unsigned int)(XT_ALIGN(f->entry_sz) + target_sz));
  free(base);
  if (ret == 0)
    ua_log("[IPT] %s %s -%c %s ACCEPT inserted via raw socket API", f->bin,
           chain, is_out ? 'o' : 'i', iface);
  return ret;
}

/* Raw first, then the binary for whatever the kernel would not take. */
static int ensure_iface_accept(const struct xt_family *f, int fd,
                               unsigned int hook, const char *chain,
                               const char *iface, int is_out) {
  int ret = raw_ensure_iface_accept(f, fd, hook, chain, iface, is_out);
  if (ret == 0)
    return 0;

  ua_log("[IPT] %s %s -%c %s raw path failed (ret=%d), using the binary",
         f->bin, chain, is_out ? 'o' : 'i', iface, ret);
  const char *const spec[] = {is_out ? "-o" : "-i", iface, "-j", "ACCEPT",
                              NULL};
  return bin_ensure(f, "filter", chain, spec);
}

/* Public API: ds_ipt_ensure_forward_accept
 *
 * Inserts:
 *   -t filter -I FORWARD -i <iface> -j ACCEPT
 *   -t filter -I FORWARD -o <iface> -j ACCEPT
 *
 * Called with the gadget's network interface (usb0, ncm0, rndis0). */

int ds_ipt_ensure_forward_accept(int family, const char *iface) {
  const struct xt_family *f = xt_family_of(family);
  ua_log("[IPT] ensure_forward_accept: %s iface=%s", f->bin, iface);

  int fd = open_raw_socket(f);
  int in = ensure_iface_accept(f, fd, NF_INET_FORWARD, "FORWARD", iface, 0);
  int out = ensure_iface_accept(f, fd, NF_INET_FORWARD, "FORWARD", iface, 1);
  if (fd >= 0)
    close(fd);
  return (in == 0 && out == 0) ? 0 : -1;
}

/* Public API: ds_ipt_ensure_input_accept
 *
 * Inserts: -t filter -I INPUT -i <iface> -j ACCEPT */

int ds_ipt_ensure_input_accept(int family, const char *iface) {
  const struct xt_family *f = xt_family_of(family);
  ua_log("[IPT] ensure_input_accept: %s iface=%s", f->bin, iface);

  int fd = open_raw_socket(f);
  int ret = ensure_iface_accept(f, fd, NF_INET_LOCAL_IN, "INPUT", iface, 0);
  if (fd >= 0)
    close(fd);
  return ret;
}

/* Public API: ds_ipt_ensure_mss_clamp
 *
 * MSS clamping rule for TCP SYN packets - prevents MTU blackhole through
 * bridge + veth path. It matters most on IPv6, where routers never fragment
 * and a mobile uplink is often well under the veth's 1500.
 *
 * Raw socket first, like every other rule: a TCPMSS target in mangle
 * POSTROUTING that is not bound to an interface counts as present, a VPN's
 * fixed --set-mss included, since it clamps our SYNs just as well; one scoped
 * to its own tunnel does not. Otherwise clamp_rule() goes in. The binary is
 * the fallback for kernels without the raw API. */

int ds_ipt_ensure_mss_clamp(int family) {
  const struct xt_family *f = xt_family_of(family);
  ua_log("[IPT] ensure_mss_clamp: %s", f->bin);

  int ret = -ENOTSUP;
  int fd = open_raw_socket(f);
  if (fd >= 0) {
    struct ipt_getinfo info;
    unsigned char *base = NULL;
    ret = get_table(f, fd, "mangle", &info, &base);
    if (ret == 0) {
      if (rule_exists_in_hook(f, &info, ENTRIES_BLOB(base),
                              NF_INET_POST_ROUTING, "", "", NULL, NULL,
                              "TCPMSS")) {
        ua_log("[IPT] %s TCPMSS clamp already present - skipping", f->bin);
      } else {
        unsigned char rule_buf[DS_XT_RULE_MAX];
        unsigned int sz = clamp_rule(f, rule_buf);
        ret = insert_rule_at_hook(f, fd, "mangle", &info, ENTRIES_BLOB(base),
                                  NF_INET_POST_ROUTING, rule_buf, sz);
      }
      free(base);
    }
    close(fd);
  }
  if (ret == 0) {
    ua_log("[IPT] %s TCPMSS clamp in place via raw socket API", f->bin);
    return 0;
  }

  ua_log("[IPT] %s TCPMSS raw path failed (ret=%d), using the binary", f->bin,
         ret);
  return bin_ensure(f, "mangle", "POSTROUTING", k_mss_spec);
}

/* Remove one of our rules: raw first, then the binary.
 *
 * The binary also runs when the raw path found nothing. A rule that went in
 * through the binary fallback may live somewhere the raw socket cannot see
 * (iptables-nft keeps its rules in nftables), and skipping the delete there
 * would leak it. Deleting a rule that does not exist fails quietly. */
static void remove_rule(const struct xt_family *f, int fd, const char *table,
                        unsigned int hook, const char *chain, const void *src,
                        const void *mask, const char *iface,
                        unsigned int iface_flags, const char *const spec[]) {
  int removed = -1;

  if (fd >= 0) {
    struct ipt_getinfo info;
    unsigned char *base = NULL;
    if (get_table(f, fd, table, &info, &base) == 0) {
      removed = remove_matching_rules(f, fd, table, &info, ENTRIES_BLOB(base),
                                      hook, src, mask, iface, iface_flags);
      free(base);
    }
  }
  /* The clamp is the exception: when the raw path could read the table and
   * found none of ours, there is nothing the binary could find either, and
   * its spec would match a foreign clamp-to-pmtu rule that was never ours. */
  if (removed < 0 || (removed == 0 && !(iface_flags & DS_IPT_MATCH_CLAMP)))
    bin_rule(f, "-D", table, chain, spec);
}

/* The three ACCEPT rules ds_ipt_ensure_{forward,input}_accept put on iface. */
static void remove_iface_accepts(const struct xt_family *f, int fd,
                                 const char *iface) {
  /* iptables reads -i "" as any interface: the binary fallback would delete
   * someone else's unconditional ACCEPT */
  if (!iface || !iface[0])
    return;
  const char *const in_spec[] = {"-i", iface, "-j", "ACCEPT", NULL};
  const char *const out_spec[] = {"-o", iface, "-j", "ACCEPT", NULL};

  remove_rule(f, fd, "filter", NF_INET_FORWARD, "FORWARD", NULL, NULL, iface,
              DS_IPT_MATCH_IN, in_spec);
  remove_rule(f, fd, "filter", NF_INET_FORWARD, "FORWARD", NULL, NULL, iface,
              DS_IPT_MATCH_OUT, out_spec);
  remove_rule(f, fd, "filter", NF_INET_LOCAL_IN, "INPUT", NULL, NULL, iface,
              DS_IPT_MATCH_IN, in_spec);
}

/* Public API: ds_ipt_remove_nat_rules
 *
 * Removes the MASQUERADE for subnet and the ACCEPT rules on iface that the
 * tethering setup inserted, for one family. Safe after a crash, and when
 * that family was never set up. The MSS clamp stays: it is not scoped to us,
 * Droidspaces inserts the identical rule, and pulling it would break a
 * container running next to us. A clamp with nothing to clamp costs nothing. */

int ds_ipt_remove_nat_rules(int family, const char *subnet, const char *iface) {
  const struct xt_family *f = xt_family_of(family);
  ua_log("[IPT] remove_nat_rules: %s subnet=%s iface=%s", f->bin, subnet,
         iface);

  uint8_t net[16], mask[16];
  if (fam_parse_cidr(f, subnet, net, mask) < 0)
    return -EINVAL;

  int fd = open_raw_socket(f);

  const char *const masq_spec[] = {"-s",   subnet, "!",          "-d",
                                   subnet, "-j",   "MASQUERADE", NULL};
  remove_rule(f, fd, "nat", NF_INET_POST_ROUTING, "POSTROUTING", net, mask,
              NULL, 0, masq_spec);
  remove_iface_accepts(f, fd, iface);

  if (fd >= 0)
    close(fd);
  return 0;
}

/* Can this host masquerade IPv4 at all: the nat table and the MASQUERADE
 * target, asked the same way ds_ipt6_available() asks for IPv6. */
int ds_ipt4_nat_available(void) {
  int fd = open_raw_socket(&xt_v4);
  if (fd >= 0) {
    struct ipt_getinfo info;
    unsigned char *base = NULL;
    if (get_table(&xt_v4, fd, "nat", &info, &base) == 0) {
      free(base);
      struct xt_get_revision rev = {.name = "MASQUERADE"};
      socklen_t len = sizeof(rev);
      int ret =
          getsockopt(fd, xt_v4.level, IPT_SO_GET_REVISION_TARGET, &rev, &len);
      close(fd);
      return ret >= 0;
    }
    close(fd);
  }
  const char *const none[] = {NULL};
  const char *const masq[] = {"-j", "MASQUERADE", NULL};
  bin_rule(&xt_v4, "-N", "nat", "ua-cap-masq", none);
  int ok = bin_rule(&xt_v4, "-A", "nat", "ua-cap-masq", masq) == 0;
  bin_rule(&xt_v4, "-F", "nat", "ua-cap-masq", none);
  bin_rule(&xt_v4, "-X", "nat", "ua-cap-masq", none);
  return ok;
}
