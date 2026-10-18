/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `hid`: a boot-protocol keyboard and mouse through f_hid. Our job ends at
 * /dev/hidgN existing with sane permissions; what gets written there is up
 * to whoever opens it. Descriptors:
 * docs/developer/references/hid-report-descriptors.txt
 */

#include "usbarsenal.h"

#define KBD "hid.ua_kbd"
#define MOUSE "hid.ua_mouse"

/* Standard boot keyboard: modifiers, reserved, 6 keycodes, 5 LED outputs.
 * 8-byte input report. */
static const unsigned char kbd_desc[] = {
    0x05, 0x01, 0x09, 0x06, 0xa1, 0x01, 0x05, 0x07, 0x19, 0xe0, 0x29,
    0xe7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x08, 0x81, 0x03, 0x95, 0x05, 0x75, 0x01, 0x05,
    0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02, 0x95, 0x01, 0x75, 0x03,
    0x91, 0x03, 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05,
    0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00, 0xc0,
};

/* Mouse: 5 buttons, relative X, Y and wheel. 4-byte input report. */
static const unsigned char mouse_desc[] = {
    0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x09, 0x01, 0xa1, 0x00, 0x05,
    0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05,
    0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x03, 0x81, 0x01, 0x05,
    0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f,
    0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0xc0, 0xc0,
};

struct hid_fn {
  const char *func, *label, *protocol, *report_length;
  const unsigned char *desc;
  size_t desc_len;
};

static const struct hid_fn fns[] = {
    {KBD, "keyboard", "1", "8", kbd_desc, sizeof(kbd_desc)},
    {MOUSE, "mouse", "2", "4", mouse_desc, sizeof(mouse_desc)},
};

void ua_help_hid(void) {
  printf(
      "Usage: " UA_NAME " hid <action> [options]\n\n"
      "Add a USB keyboard and mouse to the gadget. The host sees real HID\n"
      "devices; this side gets two character devices to write reports to.\n"
      "What you write there is up to you.\n\n"
      "Actions:\n"
      "  start   Create the keyboard and mouse and print which /dev/hidgN\n"
      "          is which\n"
      "  stop    Remove them\n\n"
      "Options:\n"
      "  -p, --perm MODE    Octal permissions for the nodes (default: 0660)\n"
      "  -g, --group GROUP  Group owning the nodes, name or GID (default:\n"
      "                     root). Anyone who can write a node can type on\n"
      "                     the host, so widen this deliberately.\n"
      "  -h, --help         Show this help\n\n"
      "Reports:\n"
      "  keyboard  8 bytes: modifiers, 0, then up to 6 HID keycodes\n"
      "  mouse     4 bytes: buttons, X, Y, wheel (signed)\n\n"
      "Examples:\n"
      "  " UA_NAME " hid start\n"
      "  " UA_NAME " hid start --group input\n"
      "  # type 'a' on the host (press, then release):\n"
      "  printf '\\0\\0\\x04\\0\\0\\0\\0\\0' > /dev/hidg0\n"
      "  printf '\\0\\0\\0\\0\\0\\0\\0\\0' > /dev/hidg0\n"
      "  # move the mouse 10 px right:\n"
      "  printf '\\0\\x0a\\0\\0' > /dev/hidg1\n"
      "  " UA_NAME " hid stop\n");
}

static int write_bin(const char *dir, const char *attr, const void *buf,
                     size_t len) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/%s", dir, attr);
  int fd = open(p, O_WRONLY | O_TRUNC | O_CLOEXEC);
  if (fd < 0)
    return -1;
  ssize_t w = write_all(fd, buf, len);
  close(fd);
  return w == (ssize_t)len ? 0 : -1;
}

/* f_hid locks its attributes once the function is in a config, so this must
 * run before commit links it */
static int setup_fn(struct ua_gadget *g, const struct hid_fn *f, int *created) {
  char dir[PATH_MAX];
  ua_gadget_func_dir(g, f->func, dir, sizeof(dir));
  *created = access(dir, F_OK) != 0;
  if (ua_gadget_add(g, f->func, dir, sizeof(dir)) < 0)
    return -1;
  /* Already there and linked from an earlier start: out of the config for
   * the writes, commit puts it back */
  ua_gadget_unlink(g, f->func);
  if (write_bin(dir, "protocol", f->protocol, 1) < 0 ||
      write_bin(dir, "subclass", "1", 1) < 0 ||
      write_bin(dir, "report_length", f->report_length, 1) < 0 ||
      write_bin(dir, "report_desc", f->desc, f->desc_len) < 0) {
    ua_error("Cannot configure the %s: %s", f->label, strerror(errno));
    return -1;
  }
  return 0;
}

/* The function's "dev" attribute is major:minor; the node name is up to
 * ueventd/udev/devtmpfs, so find it by device number, not by guessing. */
static int find_node(const struct ua_gadget *g, const char *func, char *out,
                     size_t size) {
  char dir[PATH_MAX], p[PATH_MAX + 8], dev[32];
  unsigned int maj, min;
  ua_gadget_func_dir(g, func, dir, sizeof(dir));
  snprintf(p, sizeof(p), "%s/dev", dir);
  if (read_file(p, dev, sizeof(dev)) <= 0 ||
      sscanf(dev, "%u:%u", &maj, &min) != 2)
    return -1;
  /* The node shows up a moment after bind, when the device manager has run */
  for (int tries = 0; tries < 20; tries++) {
    DIR *d = opendir("/dev");
    struct dirent *e;
    while (d && (e = readdir(d))) {
      struct stat st;
      if (strncmp(e->d_name, "hidg", 4) != 0)
        continue;
      snprintf(out, size, "/dev/%s", e->d_name);
      if (stat(out, &st) == 0 && S_ISCHR(st.st_mode) &&
          major(st.st_rdev) == maj && minor(st.st_rdev) == min) {
        closedir(d);
        return 0;
      }
    }
    if (d)
      closedir(d);
    usleep(100000);
  }
  return -1;
}

static void perm_path(char *out, size_t size) {
  ua_ws_path("Gadget", "hid.perm", out, size);
}

static void save_perm(mode_t perm, gid_t gid) {
  char p[PATH_MAX];
  perm_path(p, sizeof(p));
  int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0)
    return;
  dprintf(fd, "%o %u\n", (unsigned)perm, (unsigned)gid);
  close(fd);
}

/* Every unbind destroys /dev/hidgN and every bind makes it again, as
 * 0600 root, so the requested access is re-applied after each commit, not
 * only by `hid start` */
static int apply_perm(const struct ua_gadget *g, int report) {
  char p[PATH_MAX], buf[64];
  unsigned perm = 0660, gid = 0;
  int ret = 0;
  perm_path(p, sizeof(p));
  if (read_file(p, buf, sizeof(buf)) > 0)
    sscanf(buf, "%o %u", &perm, &gid);
  for (size_t i = 0; i < 2; i++) {
    char node[PATH_MAX];
    if (!ua_gadget_has(g, fns[i].func))
      continue;
    if (find_node(g, fns[i].func, node, sizeof(node)) < 0) {
      ua_warn("The %s is bound but its /dev/hidgN node did not appear",
              fns[i].label);
      ret = -1;
      continue;
    }
    if (chown(node, 0, (gid_t)gid) < 0 || chmod(node, (mode_t)perm) < 0)
      ua_warn("Cannot set permissions on %s: %s", node, strerror(errno));
    if (report)
      ua_log("%-8s %s", fns[i].label, node);
  }
  return ret;
}

void ua_hid_reapply(const struct ua_gadget *g) {
  if (ua_gadget_has(g, KBD) || ua_gadget_has(g, MOUSE))
    apply_perm(g, 0);
}

static gid_t parse_group(const char *s, int *ok) {
  char *end;
  long v = strtol(s, &end, 10);
  *ok = 1;
  if (*s && !*end && v >= 0)
    return (gid_t)v;
  /* By name through /etc/group; Android has none, so numbers only there */
  FILE *f = fopen("/etc/group", "re");
  char line[512];
  size_t n = strlen(s);
  while (f && fgets(line, sizeof(line), f)) {
    if (!strncmp(line, s, n) && line[n] == ':') {
      char *gid = strchr(line + n + 1, ':');
      fclose(f);
      return gid ? (gid_t)strtol(gid + 1, NULL, 10) : 0;
    }
  }
  if (f)
    fclose(f);
  *ok = 0;
  return 0;
}

static int do_start(mode_t perm, gid_t gid) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_HID) < 0)
    return UA_EXIT_FAIL;

  if (!(ua_gadget_has(&g, KBD) && ua_gadget_has(&g, MOUSE) &&
        ua_gadget_live(&g))) {
    /* Saved before commit, which re-applies it after the bind */
    save_perm(perm, gid);
    int created[2] = {0, 0};
    if (ua_gadget_begin(&g) < 0)
      return UA_EXIT_FAIL;
    for (size_t i = 0; i < 2; i++)
      if (setup_fn(&g, &fns[i], &created[i]) < 0) {
        /* Undo only what this call made */
        for (size_t j = 0; j <= i; j++)
          if (created[j])
            ua_gadget_remove(&g, fns[j].func);
        ua_gadget_commit(&g);
        return UA_EXIT_FAIL;
      }
    if (ua_gadget_commit(&g) < 0)
      return UA_EXIT_FAIL;
  } else {
    save_perm(perm, gid);
  }
  return apply_perm(&g, 1) == 0 ? UA_EXIT_OK : UA_EXIT_FAIL;
}

static int do_stop(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, KBD) && !ua_gadget_has(&g, MOUSE)) {
    ua_log("HID is not running");
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, KBD);
  ua_gadget_remove(&g, MOUSE);
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_log("HID removed");
  return UA_EXIT_OK;
}

void ua_status_hid(const struct ua_gadget *g) {
  for (size_t i = 0; i < 2; i++) {
    char node[PATH_MAX];
    if (ua_gadget_has(g, fns[i].func))
      printf("HID:        %-8s %s\n", fns[i].label,
             find_node(g, fns[i].func, node, sizeof(node)) == 0 ? node
                                                                : "(no node)");
  }
}

int ua_cmd_hid(int argc, char **argv) {
  static const struct option opts[] = {
      {"perm", required_argument, NULL, 'p'},
      {"group", required_argument, NULL, 'g'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  struct ua_config cfg;
  int c, ok;
  char *end;
  ua_config_active(&cfg);
  mode_t perm = (mode_t)cfg.hid_perm;
  gid_t gid = (gid_t)cfg.hid_group;

  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_hid();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":p:g:h", opts, NULL)) != -1) {
    switch (c) {
    case 'p':
      perm = (mode_t)strtoul(optarg, &end, 8);
      if (!*optarg || *end || perm > 0777) {
        ua_usage_error("hid", "--perm takes an octal mode like 0660");
        return UA_EXIT_USAGE;
      }
      break;
    case 'g':
      gid = parse_group(optarg, &ok);
      if (!ok) {
        ua_usage_error("hid", "Unknown group '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      break;
    case 'h':
      ua_help_hid();
      return UA_EXIT_OK;
    case ':':
      ua_usage_error("hid", "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error("hid", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  if (argc - optind != 1) {
    ua_usage_error("hid",
                   argc - optind ? "Too many arguments" : "Missing action");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(argv[optind], "start"))
    return do_start(perm, gid);
  if (!strcmp(argv[optind], "stop"))
    return do_stop();
  ua_usage_error("hid", "Unknown action '%s'", argv[optind]);
  return UA_EXIT_USAGE;
}
