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
 * symlink to the user's file, or a copy of it when the daemon could not see
 * the user's file (a container's private path).
 */

#include "usbarsenal.h"

#define PROFILE_LEAF "identity.conf"
#define COPY_TAG "# copied from "

enum kind { K_HEX16, K_UINT, K_STR, K_MAC, K_CHOICE, K_CIDR, K_DNS, K_OCTAL };

struct key {
  const char *name;
  enum kind kind;
  size_t off, size; /* field in struct ua_config; size for strings */
  unsigned max;     /* K_UINT, K_OCTAL upper bound; K_STR max length */
  const char *choices, *comment;
};

#define F(field) offsetof(struct ua_config, field)
#define S(field) sizeof(((struct ua_config *)0)->field)

/* One table drives loading, validation, `generate` and `show` */
static const struct key keys[] = {
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
     "auto cdrom disk rw", "auto | cdrom | disk | rw"},
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

void ua_config_defaults(struct ua_config *c) {
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

static int valid_dns(const char *v) {
  char tmp[128], *save = NULL;
  struct in_addr a;
  int n = 0;
  safe_strncpy(tmp, v, sizeof(tmp));
  for (char *t = strtok_r(tmp, ",", &save); t; t = strtok_r(NULL, ",", &save))
    if (inet_pton(AF_INET, trim(t), &a) != 1)
      return 0;
    else
      n++;
  return n > 0;
}

static int in_choices(const char *v, const char *choices) {
  size_t n = strlen(v);
  for (const char *c = choices; n && (c = strstr(c, v)); c += n)
    if ((c == choices || c[-1] == ' ') && (c[n] == ' ' || !c[n]))
      return 1;
  return 0;
}

/* Store val into the key's field. Empty clears a string (back to "derive
 * it"); a numeric key cannot be empty. 0 ok, -1 rejected. */
static int set_key(struct ua_config *c, const struct key *k, const char *val) {
  char *field = (char *)c + k->off;
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
    if (!in_choices(val, k->choices))
      return -1;
    break;
  case K_CIDR:
    if (!valid_cidr(val))
      return -1;
    break;
  case K_DNS:
    if (*val && !valid_dns(val))
      return -1;
    break;
  }
  safe_strncpy(field, val, k->size);
  return 0;
}

static void get_key(const struct ua_config *c, const struct key *k, char *out,
                    size_t size) {
  const char *field = (const char *)c + k->off;
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
 * cfg held before, normally the defaults. */
int ua_config_load(const char *path, struct ua_config *c) {
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
    for (i = 0; i < NKEYS; i++)
      if (!strcmp(key, keys[i].name))
        break;
    if (i == NKEYS) {
      ua_warn("%s:%d: unknown key '%s', ignored", path, n, key);
      bad++;
    } else if (set_key(c, &keys[i], val) < 0) {
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

/* The profile in force: the user's file through the symlink, or the copy.
 * No profile is fine and silent; one that has gone missing is reported. */
void ua_config_active(struct ua_config *c) {
  char p[PATH_MAX], target[PATH_MAX], first[PATH_MAX + 32];
  struct stat st;
  ua_config_defaults(c);
  active_path(p, sizeof(p));
  if (lstat(p, &st) < 0)
    return;
  if (S_ISLNK(st.st_mode)) {
    ssize_t n = readlink(p, target, sizeof(target) - 1);
    target[n > 0 ? n : 0] = '\0';
    if (ua_config_load(p, c) < 0) {
      ua_warn("Profile %s is gone; using the built-in defaults", target);
      ua_config_defaults(c);
      return;
    }
    safe_strncpy(c->source, target, sizeof(c->source));
    return;
  }
  if (ua_config_load(p, c) < 0)
    return;
  c->copied = 1;
  safe_strncpy(c->source, p, sizeof(c->source));
  FILE *f = fopen(p, "re");
  if (f && fgets(first, sizeof(first), f) &&
      !strncmp(first, COPY_TAG, strlen(COPY_TAG))) {
    first[strcspn(first, "\n")] = '\0';
    safe_strncpy(c->source, first + strlen(COPY_TAG), sizeof(c->source));
  }
  if (f)
    fclose(f);
}

/* mkstemp next to path, write, fsync, rename, fsync the directory: the
 * Droidspaces ds_config_save sequence. body(f, arg) writes the content. */
static int write_atomic(const char *path, mode_t mode,
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
    get_key(c, &keys[i], v, sizeof(v));
    snprintf(line, sizeof(line), "%s=%s", keys[i].name, v);
    if (keys[i].comment)
      fprintf(f, "%-32s # %s\n", line, keys[i].comment);
    else
      fprintf(f, "%s\n", line);
  }
}

struct copy_arg {
  int fd;
  const char *from;
};

static void copy_body(FILE *f, const void *arg) {
  const struct copy_arg *a = arg;
  char buf[4096];
  ssize_t n;
  fprintf(f, COPY_TAG "%s\n", a->from);
  lseek(a->fd, 0, SEEK_SET);
  while ((n = read(a->fd, buf, sizeof(buf))) > 0)
    fwrite(buf, 1, (size_t)n, f);
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
      "  show           Print the values in force and where they come from\n"
      "  unset          Back to the built-in defaults\n\n"
      "Options:\n"
      "      --force    Let generate overwrite FILE\n"
      "  -h, --help     Show this help\n\n"
      "The active profile is a symlink at <workspace>/Gadget/identity.conf.\n"
      "When the daemon cannot see FILE (a path private to a container), it\n"
      "keeps a copy instead; run `identity set` again after editing it.\n"
      "A profile that has gone missing is reported, and the built-in\n"
      "defaults are used.\n\n"
      "Examples:\n"
      "  " UA_NAME " identity generate ~/pixel.conf\n"
      "  " UA_NAME " identity set ~/pixel.conf\n"
      "  " UA_NAME " identity show\n"
      "  " UA_NAME " identity unset\n");
}

static int replug_if_live(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0)
    return 0;
  if (g.piggyback) {
    ua_log("Piggyback backend: the IDs and strings stay Android's; the "
           "command defaults apply");
    return 0;
  }
  if (!ua_gadget_live(&g))
    return 0;
  ua_log("Re-plugging the gadget so the host sees the new identity");
  if (ua_gadget_begin(&g) < 0)
    return -1;
  return ua_gadget_commit(&g);
}

/* set: link to the user's file when we can see that very file, else keep a
 * copy. fd/from come from a client relay (its open file and the path it has
 * on its side); without them FILE is ours to resolve. */
static int do_set(const char *file, int fd, const char *from) {
  char real[PATH_MAX], active[PATH_MAX], tmp[PATH_MAX + 8], src[64];
  struct ua_config c;
  struct stat a, b;
  int link_ok;

  if (geteuid() != 0) {
    ua_error("Must be run as root");
    return UA_EXIT_FAIL;
  }
  if (fd >= 0) {
    snprintf(src, sizeof(src), "/proc/self/fd/%d", fd);
    safe_strncpy(real, from ? from : "", sizeof(real));
    link_ok = from && fstat(fd, &a) == 0 && stat(from, &b) == 0 &&
              a.st_dev == b.st_dev && a.st_ino == b.st_ino;
  } else {
    if (!realpath(file, real)) {
      ua_error("%s: %s", file, strerror(errno));
      return UA_EXIT_FAIL;
    }
    link_ok = 1;
  }

  ua_config_defaults(&c);
  int bad = ua_config_load(fd >= 0 ? src : real, &c);
  if (bad != 0) {
    ua_error(bad < 0 ? "Cannot read %s" : "%s has errors (see above); not set",
             real[0] ? real : file);
    return UA_EXIT_FAIL;
  }

  active_path(active, sizeof(active));
  snprintf(tmp, sizeof(tmp), "%s.new", active);
  unlink(tmp);
  if (link_ok) {
    if (symlink(real, tmp) < 0 || rename(tmp, active) < 0) {
      ua_error("Cannot link %s: %s", active, strerror(errno));
      unlink(tmp);
      return UA_EXIT_FAIL;
    }
    ua_log("Active profile: %s", real);
  } else {
    struct copy_arg ca = {fd, real};
    if (write_atomic(active, 0644, copy_body, &ca) < 0) {
      ua_error("Cannot write %s: %s", active, strerror(errno));
      return UA_EXIT_FAIL;
    }
    ua_log("Active profile: a copy of %s (the daemon cannot see that path; "
           "run `identity set` again after editing it)",
           real);
  }
  return replug_if_live() < 0 ? UA_EXIT_FAIL : UA_EXIT_OK;
}

static int do_show(void) {
  struct ua_config c;
  ua_config_active(&c);
  printf("Profile: %s%s\n", c.source[0] ? c.source : "built-in defaults",
         c.copied ? " (copy)" : "");
  for (size_t i = 0; i < NKEYS; i++) {
    char v[160];
    get_key(&c, &keys[i], v, sizeof(v));
    printf("  %-16s %s\n", keys[i].name, v[0] ? v : "(derived)");
  }
  return UA_EXIT_OK;
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
  enum { OPT_FORCE = 256, OPT_FD, OPT_PATH };
  static const struct option opts[] = {
      {"force", no_argument, NULL, OPT_FORCE},
      {"fd", required_argument, NULL, OPT_FD},     /* set by the relay */
      {"path", required_argument, NULL, OPT_PATH}, /* set by the relay */
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  int force = 0, fd = -1, c;
  const char *from = NULL;
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_identity();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":h", opts, NULL)) != -1) {
    switch (c) {
    case OPT_FORCE:
      force = 1;
      break;
    case OPT_FD:
      fd = atoi(optarg);
      break;
    case OPT_PATH:
      from = optarg;
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
  int want =
      !strcmp(action, "generate") || (!strcmp(action, "set") && fd < 0) ? 2 : 1;
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
    if (write_atomic(file, 0644, template_body, &d) < 0) {
      ua_error("Cannot write %s: %s", file, strerror(errno));
      return UA_EXIT_FAIL;
    }
    ua_log("Wrote %s; edit it, then: " UA_NAME " identity set %s", file, file);
    return UA_EXIT_OK;
  }
  if (!strcmp(action, "set"))
    return do_set(file, fd, from);
  if (!strcmp(action, "show"))
    return do_show();
  if (!strcmp(action, "unset"))
    return do_unset();
  ua_usage_error("identity", "Unknown action '%s'", action);
  return UA_EXIT_USAGE;
}
