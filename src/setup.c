/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The setup: what each function starts with, in the workspace, so the app
 * and the CLI share one record and a cleared app loses nothing. Two files
 * with the same keys, Droidspaces' container.config idea:
 *   Gadget/setup.conf    the draft; `setup set` and a start with arguments edit
 * it Gadget/running.conf  what the last successful start used; the backend
 *                        writes it, whoever started the function
 * A bare `start` runs the draft; `apply` brings what runs to the draft;
 * `status --json` reports both and whether they differ. The format and the
 * loader are config.c's.
 */

#include "usbarsenal.h"

#define SETUP_LEAF "setup.conf"
#define RUNNING_LEAF "running.conf"

#define F(field) offsetof(struct ua_setup, field)
#define S(field) sizeof(((struct ua_setup *)0)->field)
#define LUN(n)                                                                 \
  {"storage_lun" #n,                                                           \
   K_LUN,                                                                      \
   F(lun[n]),                                                                  \
   S(lun[n]),                                                                  \
   0,                                                                          \
   NULL,                                                                       \
   n == 0 ? "MODE:PATH; mode auto | cdrom | dvd | disk | rw" : NULL}

static const struct ua_key keys[] = {
    {"udc", K_STR, F(udc), S(udc), 63, NULL,
     "controller; empty: the only one in /sys/class/udc"},
    LUN(0),
    LUN(1),
    LUN(2),
    LUN(3),
    LUN(4),
    LUN(5),
    LUN(6),
    LUN(7),
    {"tether_function", K_CHOICE, F(tether_function), S(tether_function), 0,
     "ncm rndis", "ncm | rndis; empty: the profile's"},
    {"tether_gateway", K_STR, F(tether_gateway), S(tether_gateway), 255, NULL,
     "a container's name; empty: no gateway"},
    {"tether_gateway_iface", K_STR, F(tether_gateway_iface),
     S(tether_gateway_iface), IFNAMSIZ - 1, NULL, "empty: usb0"},
    {"tether_net", K_UINT, F(tether_net), 0, 1, NULL, "0: --no-net"},
    {"tether_address", K_CIDR, F(tether_address), S(tether_address), 0, NULL,
     "empty: the profile's"},
    {"tether_dns", K_DNS, F(tether_dns), S(tether_dns), 0, NULL,
     "empty: the profile's"},
    {"tether_upstream", K_STR, F(tether_upstream), S(tether_upstream),
     IFNAMSIZ - 1, NULL, "empty: the profile's"},
    {"hid_interval", K_UINT, F(hid_interval), 0, 255, NULL,
     "1-255; 0: the kernel's default"},
};

#define NKEYS (sizeof(keys) / sizeof(keys[0]))

/* Which function a key belongs to; the UDC goes with every start */
static enum ua_fn key_fn(size_t i) {
  const char *n = keys[i].name;
  if (!strncmp(n, "storage_", 8))
    return UA_FN_STORAGE;
  if (!strncmp(n, "tether_", 7))
    return UA_FN_TETHER;
  if (!strncmp(n, "hid_", 4))
    return UA_FN_HID;
  return UA_FN_ALL;
}

static void ua_setup_defaults(struct ua_setup *s) {
  memset(s, 0, sizeof(*s));
  s->tether_net = 1;
}

static void load(const char *leaf, struct ua_setup *s) {
  char p[PATH_MAX];
  ua_setup_defaults(s);
  ua_ws_path("Gadget", leaf, p, sizeof(p));
  ua_table_load(p, keys, NKEYS, s);
}

void ua_setup_load(struct ua_setup *s) { load(SETUP_LEAF, s); }
/* Running carries the boot it was written in, as the handoff does: after a
 * reboot nothing of ours runs, whatever the file says */
void ua_running_load(struct ua_setup *s) {
  char p[PATH_MAX], line[80] = "", boot[40];
  ua_setup_defaults(s);
  ua_ws_path("Gadget", RUNNING_LEAF, p, sizeof(p));
  FILE *f = fopen(p, "re");
  if (!f)
    return;
  if (fgets(line, sizeof(line), f))
    line[strcspn(line, "\n")] = '\0';
  fclose(f);
  ua_boot_marker(boot, sizeof(boot));
  if (strncmp(line, "# boot ", 7) || strcmp(line + 7, boot)) {
    unlink(p);
    return;
  }
  ua_table_load(p, keys, NKEYS, s);
}

struct body_arg {
  const struct ua_setup *s;
  int running;
};

static void body(FILE *f, const void *arg) {
  const struct body_arg *a = arg;
  if (a->running) {
    char boot[40];
    ua_boot_marker(boot, sizeof(boot));
    fprintf(f,
            "# boot %s\n# What the last successful start of each function "
            "used. Written by\n# " UA_NAME "; `" UA_NAME
            " setup show` reads it.\n\n",
            boot);
  } else
    fprintf(f,
            "# USB Arsenal setup: what each function starts with. Change "
            "it with\n#   " UA_NAME " setup set KEY=VALUE ...\n# An empty "
            "value means the active profile's default, or the kernel's.\n\n");
  for (size_t i = 0; i < NKEYS; i++) {
    char v[UA_LUN_SPEC], line[UA_LUN_SPEC + 40];
    ua_key_get(a->s, &keys[i], v, sizeof(v));
    snprintf(line, sizeof(line), "%s=%s", keys[i].name, v);
    if (keys[i].comment && !a->running)
      fprintf(f, "%-32s # %s\n", line, keys[i].comment);
    else
      fprintf(f, "%s\n", line);
  }
}

static int save(const char *leaf, const struct ua_setup *s, int running) {
  char p[PATH_MAX];
  struct body_arg a = {s, running};
  ua_ws_path("Gadget", leaf, p, sizeof(p));
  if (ua_write_atomic(p, 0644, body, &a) < 0) {
    ua_error("Cannot write %s: %s", p, strerror(errno));
    return -1;
  }
  return 0;
}

int ua_setup_save(const struct ua_setup *s) { return save(SETUP_LEAF, s, 0); }

/* "key=value" into the draft, validated as the file would be */
int ua_setup_set(struct ua_setup *s, const char *assignment) {
  char key[64];
  const char *eq = strchr(assignment, '=');
  if (!eq || eq == assignment || (size_t)(eq - assignment) >= sizeof(key)) {
    ua_error("'%s' is not KEY=VALUE", assignment);
    return -1;
  }
  memcpy(key, assignment, (size_t)(eq - assignment));
  key[eq - assignment] = '\0';
  for (size_t i = 0; i < NKEYS; i++)
    if (!strcmp(key, keys[i].name)) {
      if (ua_key_set(s, &keys[i], eq + 1) == 0)
        return 0;
      ua_error("Bad value '%s' for %s%s%s", eq + 1, key,
               keys[i].comment ? ": " : "",
               keys[i].comment ? keys[i].comment : "");
      return -1;
    }
  ua_error("No such setup key: %s", key);
  return -1;
}

/* Copy fn's keys (UA_FN_ALL: every key) from one setup to another; for
 * storage only the LUNs whose bit is in mask. The controller belongs to no
 * function, so only UA_FN_ALL takes it: `setup reset hid` must not drop it */
static void copy_fn(struct ua_setup *to, const struct ua_setup *from,
                    enum ua_fn fn, unsigned mask) {
  for (size_t i = 0; i < NKEYS; i++) {
    enum ua_fn kf = key_fn(i);
    if (fn != UA_FN_ALL && kf != fn)
      continue;
    if (kf == UA_FN_STORAGE && !(mask & (1u << (keys[i].name[11] - '0'))))
      continue;
    memcpy((char *)to + keys[i].off, (const char *)from + keys[i].off,
           keys[i].kind == K_UINT ? sizeof(unsigned) : keys[i].size);
  }
}

int ua_running_take(enum ua_fn fn, unsigned mask) {
  struct ua_setup s, r;
  ua_setup_load(&s);
  ua_running_load(&r);
  copy_fn(&r, &s, fn, mask);
  /* Every start runs on the setup's controller */
  safe_strncpy(r.udc, s.udc, sizeof(r.udc));
  return save(RUNNING_LEAF, &r, 1);
}

int ua_running_clear(enum ua_fn fn) {
  struct ua_setup r, d;
  char p[PATH_MAX];
  /* A --perm or --group given to `hid start` lasts as long as that HID
   * (hid.c save_perm); the next one starts from the profile's */
  if (fn == UA_FN_ALL || fn == UA_FN_HID) {
    ua_ws_path("Gadget", "hid.perm", p, sizeof(p));
    unlink(p);
  }
  if (fn == UA_FN_ALL) {
    ua_ws_path("Gadget", RUNNING_LEAF, p, sizeof(p));
    return unlink(p) < 0 && errno != ENOENT ? -1 : 0;
  }
  ua_running_load(&r);
  ua_setup_defaults(&d);
  copy_fn(&r, &d, fn, ~0u);
  return save(RUNNING_LEAF, &r, 1);
}

int ua_running_clear_lun(int lun) {
  struct ua_setup r;
  ua_running_load(&r);
  r.lun[lun][0] = '\0';
  return save(RUNNING_LEAF, &r, 1);
}

/* An empty value means the profile's (or usb0 for the cable's name), so two
 * setups are compared as a start would read them: picking the function the
 * profile already gives is no change, and a profile switch changes nothing
 * either, since both sides resolve against the same profile */
static void resolved(const struct ua_setup *s, const struct ua_config *cfg,
                     size_t i, char *out, size_t size) {
  ua_key_get(s, &keys[i], out, size);
  if (out[0])
    return;
  const char *n = keys[i].name;
  if (!strcmp(n, "tether_function"))
    safe_strncpy(out, cfg->tether_function, size);
  else if (!strcmp(n, "tether_address"))
    safe_strncpy(out, cfg->tether_address, size);
  else if (!strcmp(n, "tether_dns"))
    safe_strncpy(out, cfg->tether_dns, size);
  else if (!strcmp(n, "tether_upstream"))
    safe_strncpy(out, cfg->tether_upstream, size);
  else if (!strcmp(n, "tether_gateway_iface"))
    safe_strncpy(out, "usb0", size);
}

int ua_setup_pending(enum ua_fn fn, const struct ua_setup *setup,
                     const struct ua_setup *running) {
  struct ua_config cfg;
  ua_config_active(&cfg);
  for (size_t i = 0; i < NKEYS; i++) {
    if (key_fn(i) != fn)
      continue;
    char a[UA_LUN_SPEC], b[UA_LUN_SPEC];
    resolved(setup, &cfg, i, a, sizeof(a));
    resolved(running, &cfg, i, b, sizeof(b));
    if (strcmp(a, b))
      return 1;
  }
  return 0;
}

int ua_fn_linked(const struct ua_gadget *g, enum ua_fn fn) {
  switch (fn) {
  case UA_FN_STORAGE:
    return ua_gadget_has(g, UA_F_STORAGE);
  case UA_FN_TETHER:
    return ua_gadget_has(g, UA_F_NCM) || ua_gadget_has(g, UA_F_RNDIS);
  case UA_FN_HID:
    return ua_gadget_has(g, UA_F_KBD);
  default:
    return 0;
  }
}

static void json_table(const char *name, const struct ua_setup *s, int *first) {
  int f = 1;
  printf("%s\"%s\":{", *first ? "" : ",", name);
  *first = 0;
  for (size_t i = 0; i < NKEYS; i++) {
    char v[UA_LUN_SPEC];
    ua_key_get(s, &keys[i], v, sizeof(v));
    ua_json_str(keys[i].name, v, &f);
  }
  printf("}");
}

/* The status --json tail: boot_id, setup, running and pending. Pending is
 * "the function is linked and its draft differs from what started it". */
void ua_setup_json(const struct ua_gadget *g, int live, int *first) {
  struct ua_setup s, r;
  char boot[40];
  ua_setup_load(&s);
  ua_running_load(&r);
  ua_boot_marker(boot, sizeof(boot));
  ua_json_str("boot_id", boot, first);
  json_table("setup", &s, first);
  json_table("running", &r, first);
  printf(",\"pending\":{");
  static const char *const names[] = {"storage", "tether", "hid"};
  int f = 1;
  for (enum ua_fn fn = UA_FN_STORAGE; fn <= UA_FN_HID; fn++)
    ua_json_int(names[fn],
                live && ua_fn_linked(g, fn) && ua_setup_pending(fn, &s, &r),
                &f);
  printf("}");
}

void ua_help_setup(void) {
  printf(
      "Usage: " UA_NAME " setup <action> [KEY=VALUE ...]\n\n"
      "What each function starts with, kept in the workspace so the app and\n"
      "the command line share it. A `start` with arguments saves them here;\n"
      "a bare `start` runs what is saved; `<function> apply` brings what\n"
      "runs to it.\n\n"
      "Actions:\n"
      "  show [--json]  Every key: the setup, and what the last start used\n"
      "  set KEY=VALUE  Change the setup. A bad value changes nothing.\n"
      "  reset [FN]     Back to the defaults (storage, tether or hid; all\n"
      "                 without one)\n"
      "  revert [FN]    Back to what runs (what Discard does in the app)\n\n"
      "Keys:\n");
  for (size_t i = 0; i < NKEYS; i++)
    printf("  %-22s %s\n", keys[i].name,
           keys[i].comment ? keys[i].comment : "");
  printf("\nThe files are <workspace>/Gadget/setup.conf and running.conf.\n\n"
         "Examples:\n"
         "  " UA_NAME
         " setup set storage_lun0=auto:/sdcard/USB-Arsenal/win.iso\n"
         "  " UA_NAME " setup set tether_function=rndis tether_dns=9.9.9.9\n"
         "  " UA_NAME " storage start\n"
         "  " UA_NAME " setup show\n");
}

static int fn_of(const char *name, enum ua_fn *fn) {
  if (!name)
    *fn = UA_FN_ALL;
  else if (!strcmp(name, "storage"))
    *fn = UA_FN_STORAGE;
  else if (!strcmp(name, "tether"))
    *fn = UA_FN_TETHER;
  else if (!strcmp(name, "hid"))
    *fn = UA_FN_HID;
  else
    return -1;
  return 0;
}

static int do_show(int json) {
  struct ua_setup s, r;
  ua_setup_load(&s);
  ua_running_load(&r);
  if (json) {
    int first = 1;
    printf("{");
    json_table("setup", &s, &first);
    json_table("running", &r, &first);
    printf("}\n");
    return UA_EXIT_OK;
  }
  printf("%-22s %-40s %s\n", "key", "setup", "running");
  for (size_t i = 0; i < NKEYS; i++) {
    char a[UA_LUN_SPEC], b[UA_LUN_SPEC];
    ua_key_get(&s, &keys[i], a, sizeof(a));
    ua_key_get(&r, &keys[i], b, sizeof(b));
    printf("%-22s %-40s %s\n", keys[i].name, a[0] ? a : "-", b[0] ? b : "-");
  }
  return UA_EXIT_OK;
}

int ua_cmd_setup(int argc, char **argv) {
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_setup();
    return UA_EXIT_OK;
  }
  const char *action = argv[1];
  if (!strcmp(action, "show")) {
    int json = argc == 3 && !strcmp(argv[2], "--json");
    if (argc > 2 && !json) {
      ua_usage_error("setup", "show takes no arguments but --json");
      return UA_EXIT_USAGE;
    }
    return do_show(json);
  }
  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  if (!strcmp(action, "set")) {
    struct ua_setup s;
    if (argc < 3) {
      ua_usage_error("setup", "set takes KEY=VALUE");
      return UA_EXIT_USAGE;
    }
    ua_setup_load(&s);
    for (int i = 2; i < argc; i++)
      if (ua_setup_set(&s, argv[i]) < 0)
        return UA_EXIT_USAGE;
    return ua_setup_save(&s) < 0 ? UA_EXIT_FAIL : UA_EXIT_OK;
  }
  if (!strcmp(action, "reset") || !strcmp(action, "revert")) {
    struct ua_setup s, from;
    enum ua_fn fn;
    if (argc > 3 || fn_of(argc == 3 ? argv[2] : NULL, &fn) < 0) {
      ua_usage_error("setup", "%s takes storage, tether, hid or nothing",
                     action);
      return UA_EXIT_USAGE;
    }
    ua_setup_load(&s);
    if (action[2] == 's')
      ua_setup_defaults(&from);
    else
      ua_running_load(&from);
    copy_fn(&s, &from, fn, ~0u);
    if (ua_setup_save(&s) < 0)
      return UA_EXIT_FAIL;
    ua_log("Setup %s%s%s", action[2] == 's' ? "reset" : "reverted to what runs",
           argc == 3 ? " for " : "", argc == 3 ? argv[2] : "");
    return UA_EXIT_OK;
  }
  ua_usage_error("setup", "Unknown action '%s'", action);
  return UA_EXIT_USAGE;
}
