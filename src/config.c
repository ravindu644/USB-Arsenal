/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Profiles: everything a user may want to change, from the IDs the host sees
 * to each command's defaults, in an editable key=value file. The format and
 * the loader follow Droidspaces' config.c: '#' comments, trimmed keys and
 * values, a bad value warned about and left at its default, atomic writes.
 *
 * One profile is active at a time: <workspace>/Gadget/identity.conf, a
 * symlink to the user's file.
 */

#include "usbarsenal.h"

#define PROFILE_LEAF "identity.conf"

#define F(field) offsetof(struct ua_config, field)
#define S(field) sizeof(((struct ua_config *)0)->field)

/* One table drives loading, validation, `generate` and `show`. setup.c has a
 * second table for the same machinery. */
static const struct ua_key keys[] = {
    {"id_vendor", K_HEX16, F(id_vendor), 0, 0, NULL, NULL},
    {"id_product", K_HEX16, F(id_product), 0, 0, NULL, NULL},
    {"bcd_device", K_HEX16, F(bcd_device), 0, 0, NULL, NULL},
    {"bcd_usb", K_HEX16, F(bcd_usb), 0, 0, NULL, NULL},
    {"manufacturer", K_STR, F(manufacturer), S(manufacturer), 126, NULL, NULL},
    {"product", K_STR, F(product), S(product), 126, NULL, NULL},
    {"serial", K_STR, F(serial), S(serial), 126, NULL,
     "empty: ro.serialno, or /etc/machine-id"},
    {"configuration", K_STR, F(configuration), S(configuration), 126, NULL,
     NULL},
    {"max_power", K_UINT, F(max_power), 0, 500, NULL, "mA, 0-500"},
    {"storage_inquiry", K_STR, F(storage_inquiry), S(storage_inquiry), 28, NULL,
     "vendor(8) product(16) revision(4); empty: kernel default"},
    {"tether_dev_mac", K_MAC, F(tether_dev_mac), S(tether_dev_mac), 0, NULL,
     "empty: derived, stable per device"},
    {"tether_host_mac", K_MAC, F(tether_host_mac), S(tether_host_mac), 0, NULL,
     "the MAC the host sees"},
    {"storage_mode", K_CHOICE, F(storage_mode), S(storage_mode), 0,
     "auto cdrom dvd disk rw", "auto | cdrom | dvd | disk | rw"},
    {"tether_function", K_CHOICE, F(tether_function), S(tether_function), 0,
     "ncm rndis", "ncm | rndis"},
    {"tether_address", K_CIDR, F(tether_address), S(tether_address), 0, NULL,
     "this side's address; the host gets the next one"},
    {"tether_dns", K_DNS, F(tether_dns), S(tether_dns), 0, NULL,
     "empty: 1.1.1.1,8.8.8.8"},
    {"tether_upstream", K_STR, F(tether_upstream), S(tether_upstream),
     IFNAMSIZ - 1, NULL, "empty: detect automatically"},
    {"hid_perm", K_OCTAL, F(hid_perm), 0, 0777, NULL, "octal"},
    {"hid_group", K_UINT, F(hid_group), 0, 0x7fffffff, NULL, "numeric gid"},
};

#define NKEYS (sizeof(keys) / sizeof(keys[0]))
#define FIRST_DEFAULT 12 /* keys from storage_mode on are command defaults */

static void ua_config_defaults(struct ua_config *c) {
  memset(c, 0, sizeof(*c));
  c->id_vendor = 0x1d6b; /* Linux Foundation multifunction composite */
  c->id_product = 0x0104;
  c->bcd_device = 0x0100;
  c->bcd_usb = 0x0200;
  safe_strncpy(c->manufacturer, "USB Arsenal", sizeof(c->manufacturer));
  safe_strncpy(c->product, "USB Arsenal", sizeof(c->product));
  safe_strncpy(c->configuration, "USB Arsenal", sizeof(c->configuration));
  c->max_power = 250;
  safe_strncpy(c->storage_mode, "auto", sizeof(c->storage_mode));
  safe_strncpy(c->tether_function, "ncm", sizeof(c->tether_function));
  safe_strncpy(c->tether_address, "192.168.42.129/24",
               sizeof(c->tether_address));
  c->hid_perm = 0660;
}

static char *trim(char *s) {
  while (isspace((unsigned char)*s))
    s++;
  char *end = s + strlen(s);
  while (end > s && isspace((unsigned char)end[-1]))
    *--end = '\0';
  return s;
}

static int valid_mac(const char *v) {
  unsigned b[6];
  char tail;
  return sscanf(v, "%2x:%2x:%2x:%2x:%2x:%2x%c", &b[0], &b[1], &b[2], &b[3],
                &b[4], &b[5], &tail) == 6 &&
         strlen(v) == 17 && !(b[0] & 1);
}

static int valid_cidr(const char *v) {
  char ip[32];
  const char *slash = strchr(v, '/');
  struct in_addr a;
  if (!slash || (size_t)(slash - v) >= sizeof(ip))
    return 0;
  memcpy(ip, v, (size_t)(slash - v));
  ip[slash - v] = '\0';
  char *end;
  long p = strtol(slash + 1, &end, 10);
  return inet_pton(AF_INET, ip, &a) == 1 && !*end && end != slash + 1 &&
         p >= 8 && p <= 30;
}

int ua_valid_dns(const char *v) {
  char tmp[128], *save = NULL;
  struct in_addr a;
  int n = 0;
  /* As long as the key's own field holds, or a list cut short here would be
   * checked and then stored shorter than it was given */
  if (strlen(v) >= sizeof(tmp))
    return 0;
  safe_strncpy(tmp, v, sizeof(tmp));
  for (char *t = strtok_r(tmp, ",", &save); t; t = strtok_r(NULL, ",", &save))
    if (inet_pton(AF_INET, trim(t), &a) != 1)
      return 0;
    else
      n++;
  return n > 0;
}

static int ua_in_choices(const char *v, const char *choices) {
  size_t n = strlen(v);
  for (const char *c = choices; n && (c = strstr(c, v)); c += n)
    if ((c == choices || c[-1] == ' ') && (c[n] == ' ' || !c[n]))
      return 1;
  return 0;
}

/* A setup LUN: MODE:PATH with an absolute path, or empty for no drive */
static int valid_lun(const char *v, size_t size) {
  const char *colon = strchr(v, ':');
  char mode[8];
  if (!colon || (size_t)(colon - v) >= sizeof(mode) || colon[1] != '/' ||
      strlen(v) >= size)
    return 0;
  memcpy(mode, v, (size_t)(colon - v));
  mode[colon - v] = '\0';
  return ua_in_choices(mode, "auto cdrom dvd disk rw");
}

/* Store val into the key's field. Empty clears a string (back to "derive
 * it"); a numeric key cannot be empty. 0 ok, -1 rejected. */
int ua_key_set(void *base, const struct ua_key *k, const char *val) {
  char *field = (char *)base + k->off;
  char *end;
  unsigned long v;
  switch (k->kind) {
  case K_HEX16:
    v = strtoul(val, &end, 0);
    if (!*val || *end || v > 0xffff)
      return -1;
    *(unsigned *)(void *)field = (unsigned)v;
    return 0;
  case K_UINT:
  case K_OCTAL:
    v = strtoul(val, &end, k->kind == K_OCTAL ? 8 : 10);
    if (!*val || *end || v > k->max)
      return -1;
    *(unsigned *)(void *)field = (unsigned)v;
    return 0;
  case K_STR:
    if (strlen(val) > k->max)
      return -1;
    for (const char *p = val; *p; p++)
      if (!isprint((unsigned char)*p))
        return -1;
    break;
  case K_MAC:
    if (*val && !valid_mac(val))
      return -1;
    break;
  case K_CHOICE:
    /* Empty means the default (the setup leaves it to the profile) */
    if (*val && !ua_in_choices(val, k->choices))
      return -1;
    break;
  case K_LUN:
    if (*val && !valid_lun(val, k->size))
      return -1;
    break;
  case K_CIDR:
    /* Empty: the setup leaves it to the profile, and a profile without one
     * gets the built-in address */
    if (*val && !valid_cidr(val))
      return -1;
    break;
  case K_DNS:
    if (*val && !ua_valid_dns(val))
      return -1;
    break;
  }
  safe_strncpy(field, val, k->size);
  return 0;
}

void ua_key_get(const void *base, const struct ua_key *k, char *out,
                size_t size) {
  const char *field = (const char *)base + k->off;
  unsigned v = (k->kind == K_HEX16 || k->kind == K_UINT || k->kind == K_OCTAL)
                   ? *(const unsigned *)(const void *)field
                   : 0;
  if (k->kind == K_HEX16)
    snprintf(out, size, "0x%04x", v);
  else if (k->kind == K_UINT)
    snprintf(out, size, "%u", v);
  else if (k->kind == K_OCTAL)
    snprintf(out, size, "0%o", v);
  else
    safe_strncpy(out, field, size);
}

/* Returns the number of lines that were not taken (bad value, unknown key,
 * no '='), or -1 when the file cannot be read. Everything else keeps what
 * base held before, normally the defaults. */
int ua_table_load(const char *path, const struct ua_key *table, size_t nkeys,
                  void *base) {
  FILE *f = fopen(path, "re");
  if (!f)
    return -1;
  char line[512];
  int n = 0, bad = 0;
  while (fgets(line, sizeof(line), f)) {
    n++;
    char *t = trim(line);
    if (!*t || *t == '#')
      continue;
    char *eq = strchr(t, '=');
    if (!eq) {
      ua_warn("%s:%d: not key=value, ignored", path, n);
      bad++;
      continue;
    }
    *eq = '\0';
    char *key = trim(t), *val = trim(eq + 1);
    /* An inline comment starts the value or follows whitespace, so a '#'
     * inside a string (a serial like "AB#12") stays part of it. A value
     * that is only a comment is empty: that is how `generate` writes them. */
    for (char *h = val; (h = strchr(h, '#')); h++)
      if (h == val || isspace((unsigned char)h[-1])) {
        *h = '\0';
        val = trim(val);
        break;
      }
    size_t i;
    for (i = 0; i < nkeys; i++)
      if (!strcmp(key, table[i].name))
        break;
    if (i == nkeys) {
      ua_warn("%s:%d: unknown key '%s', ignored", path, n, key);
      bad++;
    } else if (ua_key_set(base, &table[i], val) < 0) {
      ua_warn("%s:%d: bad value '%s' for %s, keeping the default", path, n, val,
              key);
      bad++;
    }
  }
  fclose(f);
  return bad;
}

static void active_path(char *out, size_t size) {
  ua_ws_path("Gadget", PROFILE_LEAF, out, size);
}

/* The profile in force: the user's file through the symlink.
 * No profile is fine and silent; one that has gone missing is reported. */
void ua_config_active(struct ua_config *c) {
  char p[PATH_MAX], target[PATH_MAX];
  struct stat st;
  ua_config_defaults(c);
  active_path(p, sizeof(p));
  if (lstat(p, &st) < 0)
    return;
  if (S_ISLNK(st.st_mode)) {
    ssize_t n = readlink(p, target, sizeof(target) - 1);
    target[n > 0 ? n : 0] = '\0';
    if (ua_table_load(p, keys, NKEYS, c) < 0) {
      ua_warn("Profile %s is gone; using the built-in defaults", target);
      ua_config_defaults(c);
      return;
    }
    safe_strncpy(c->source, target, sizeof(c->source));
    return;
  }
  /* A plain file: an older version's copy of a profile */
  if (ua_table_load(p, keys, NKEYS, c) == 0)
    safe_strncpy(c->source, p, sizeof(c->source));
}

/* mkstemp next to path, write, fsync, rename, fsync the directory: the
 * Droidspaces ds_config_save sequence. body(f, arg) writes the content. */
int ua_write_atomic(const char *path, mode_t mode,
                    void (*body)(FILE *, const void *), const void *arg) {
  char tmp[PATH_MAX + 8];
  snprintf(tmp, sizeof(tmp), "%s.XXXXXX", path);
  int fd = mkstemp(tmp);
  if (fd < 0)
    return -1;
  fchmod(fd, mode);
  FILE *f = fdopen(fd, "w");
  if (!f) {
    close(fd);
    unlink(tmp);
    return -1;
  }
  body(f, arg);
  int ok = fflush(f) == 0 && !ferror(f) &&
           (fsync(fileno(f)) == 0 || errno == EINVAL);
  if (fclose(f) != 0 || !ok || rename(tmp, path) < 0) {
    unlink(tmp);
    return -1;
  }
  char *slash = strrchr(tmp, '/');
  if (slash) {
    *slash = '\0';
    int dfd =
        open(slash == tmp ? "/" : tmp, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd >= 0) {
      fsync(dfd);
      close(dfd);
    }
  }
  return 0;
}

static void template_body(FILE *f, const void *arg) {
  const struct ua_config *c = arg;
  fprintf(f, "# USB Arsenal profile. Make it the default with:\n"
             "#   " UA_NAME " identity set <this file>\n"
             "# A command-line option overrides the value here. An empty value "
             "means\n# \"derive it\" where the comment says so.\n\n"
             "# What the host sees\n");
  for (size_t i = 0; i < NKEYS; i++) {
    char v[160], line[200];
    if (i == FIRST_DEFAULT)
      fprintf(f, "\n# Defaults for commands\n");
    ua_key_get(c, &keys[i], v, sizeof(v));
    snprintf(line, sizeof(line), "%s=%s", keys[i].name, v);
    if (keys[i].comment)
      fprintf(f, "%-32s # %s\n", line, keys[i].comment);
    else
      fprintf(f, "%s\n", line);
  }
}

void ua_help_identity(void) {
  printf(
      "Usage: " UA_NAME " identity <action> [options] [FILE]\n\n"
      "Profiles: the IDs, strings and serial the host sees, plus the defaults\n"
      "of every command, in an editable key=value file. Keep as many as you\n"
      "like; one is active at a time.\n\n"
      "Actions:\n"
      "  generate FILE  Write a profile with every key and its default.\n"
      "                 Refuses an existing file unless --force.\n"
      "  set FILE       Make FILE the active profile. A live gadget re-plugs\n"
      "                 once so the host sees the change now.\n"
      "  show [FILE...] Print the values in force and where they come from;\n"
      "                 with FILEs, what each one gives, read as set reads\n"
      "                 it (a bad line keeps that key's default)\n"
      "  unset          Back to the built-in defaults\n\n"
      "Options:\n"
      "      --force    Let generate overwrite FILE\n"
      "      --json     show as JSON: {profile, missing, keys}, or with\n"
      "                 FILEs an array of {path, errors, keys}. A derived\n"
      "                 value is \"\"; errors is -1 for a FILE that cannot\n"
      "                 be read.\n"
      "  -h, --help     Show this help\n\n"
      "The active profile is a symlink at <workspace>/Gadget/identity.conf,\n"
      "so an edit of FILE counts from the next start. A profile that has\n"
      "gone missing is reported, and the built-in\n"
      "defaults are used.\n\n"
      "Examples:\n"
      "  " UA_NAME " identity generate ~/pixel.conf\n"
      "  " UA_NAME " identity set ~/pixel.conf\n"
      "  " UA_NAME " identity show\n"
      "  " UA_NAME " identity show --json ~/pixel.conf ~/other.conf\n"
      "  " UA_NAME " identity unset\n");
}

static int replug_if_live(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0)
    return 0;
  if (!ua_gadget_live(&g))
    return 0;
  ua_log("Re-plugging the gadget so the host sees the new identity");
  if (ua_gadget_begin(&g) < 0)
    return -1;
  return ua_gadget_commit(&g);
}

/* set: link to the user's file, by its canonical path */
static int do_set(const char *file) {
  char real[PATH_MAX], active[PATH_MAX], tmp[PATH_MAX + 8];
  struct ua_config c;

  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  if (!realpath(file, real)) {
    ua_error("%s: %s", file, strerror(errno));
    return UA_EXIT_FAIL;
  }
  ua_config_defaults(&c);
  int bad = ua_table_load(real, keys, NKEYS, &c);
  if (bad != 0) {
    ua_error(bad < 0 ? "Cannot read %s" : "%s has errors (see above); not set",
             real);
    return UA_EXIT_FAIL;
  }

  active_path(active, sizeof(active));
  snprintf(tmp, sizeof(tmp), "%s.new", active);
  unlink(tmp);
  if (symlink(real, tmp) < 0 || rename(tmp, active) < 0) {
    ua_error("Cannot link %s: %s", active, strerror(errno));
    unlink(tmp);
    return UA_EXIT_FAIL;
  }
  ua_log("Active profile: %s", real);
  return replug_if_live() < 0 ? UA_EXIT_FAIL : UA_EXIT_OK;
}

/* c's values as ua_key_get prints them: a "keys" JSON object, or a line each
 */
static void print_keys(const struct ua_config *c, int json) {
  int first = 1;
  if (json)
    printf("\"keys\":{");
  for (size_t i = 0; i < NKEYS; i++) {
    char v[160];
    ua_key_get(c, &keys[i], v, sizeof(v));
    if (json)
      ua_json_str(keys[i].name, v, &first);
    else
      printf("  %-16s %s\n", keys[i].name, v[0] ? v : "(derived)");
  }
  if (json)
    printf("}");
}

/* The profile in force, or with FILEs each of them read through the same
 * loader `set` uses, so a client never has to parse a profile itself */
static int do_show(char **files, int nfiles, int json) {
  struct ua_config c;
  int ret = UA_EXIT_OK, first;
  if (!nfiles) {
    char p[PATH_MAX], missing[PATH_MAX] = "";
    struct stat st;
    ua_config_active(&c);
    /* A link to a deleted file reads as the built-in defaults; say which file
     * went, or the user only sees their profile silently ignored */
    active_path(p, sizeof(p));
    if (lstat(p, &st) == 0 && S_ISLNK(st.st_mode) && stat(p, &st) < 0 &&
        errno == ENOENT) {
      ssize_t n = readlink(p, missing, sizeof(missing) - 1);
      missing[n > 0 ? n : 0] = '\0';
    }
    if (json) {
      first = 1;
      printf("{");
      ua_json_str("profile", c.source, &first);
      ua_json_str("missing", missing, &first);
      printf(",");
      print_keys(&c, 1);
      printf("}\n");
    } else {
      if (missing[0])
        printf("Profile: %s (missing)\n", missing);
      else
        printf("Profile: %s\n", c.source[0] ? c.source : "built-in defaults");
      print_keys(&c, 0);
    }
    return UA_EXIT_OK;
  }
  if (json)
    printf("[");
  for (int i = 0; i < nfiles; i++) {
    char real[PATH_MAX];
    ua_config_defaults(&c);
    int bad = ua_table_load(files[i], keys, NKEYS, &c);
    if (bad < 0) {
      ua_error("Cannot read %s: %s", files[i], strerror(errno));
      ret = UA_EXIT_FAIL;
    }
    if (!realpath(files[i], real))
      safe_strncpy(real, files[i], sizeof(real));
    if (json) {
      first = 1;
      printf("%s{", i ? "," : "");
      ua_json_str("path", real, &first);
      ua_json_int("errors", bad, &first);
      printf(",");
      print_keys(&c, 1);
      printf("}");
    } else {
      printf("Profile: %s", real);
      if (bad)
        printf(bad < 0 ? " (cannot read)" : " (%d bad lines, defaults kept)",
               bad);
      printf("\n");
      print_keys(&c, 0);
    }
  }
  if (json)
    printf("]\n");
  return ret;
}

static int do_unset(void) {
  char p[PATH_MAX];
  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  active_path(p, sizeof(p));
  if (unlink(p) < 0 && errno != ENOENT) {
    ua_error("Cannot remove %s: %s", p, strerror(errno));
    return UA_EXIT_FAIL;
  }
  ua_log("Back to the built-in defaults");
  return replug_if_live() < 0 ? UA_EXIT_FAIL : UA_EXIT_OK;
}

int ua_cmd_identity(int argc, char **argv) {
  enum { OPT_FORCE = 256, OPT_JSON };
  static const struct option opts[] = {
      {"force", no_argument, NULL, OPT_FORCE},
      {"json", no_argument, NULL, OPT_JSON},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  int force = 0, json = 0, c;
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_identity();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":h", opts, NULL)) != -1) {
    switch (c) {
    case OPT_FORCE:
      force = 1;
      break;
    case OPT_JSON:
      json = 1;
      break;
    case 'h':
      ua_help_identity();
      return UA_EXIT_OK;
    case ':':
      ua_usage_error("identity", "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error("identity", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  int nargs = argc - optind;
  const char *action = nargs ? argv[optind] : "";
  const char *file = nargs > 1 ? argv[optind + 1] : NULL;
  if (json && strcmp(action, "show")) {
    ua_usage_error("identity", "--json goes with show");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(action, "show"))
    return do_show(argv + optind + 1, nargs - 1, json);
  int want = !strcmp(action, "generate") || !strcmp(action, "set") ? 2 : 1;
  if (nargs != want) {
    ua_usage_error("identity", nargs < want ? "Missing FILE"
                                            : "Too many "
                                              "arguments");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(action, "generate")) {
    struct ua_config d;
    ua_config_defaults(&d);
    if (!force && access(file, F_OK) == 0) {
      ua_error("%s exists; use --force to overwrite it", file);
      return UA_EXIT_FAIL;
    }
    if (ua_write_atomic(file, 0644, template_body, &d) < 0) {
      ua_error("Cannot write %s: %s", file, strerror(errno));
      return UA_EXIT_FAIL;
    }
    ua_log("Wrote %s; edit it, then: " UA_NAME " identity set %s", file, file);
    return UA_EXIT_OK;
  }
  if (!strcmp(action, "set"))
    return do_set(file);
  if (!strcmp(action, "unset"))
    return do_unset();
  ua_usage_error("identity", "Unknown action '%s'", action);
  return UA_EXIT_USAGE;
}
