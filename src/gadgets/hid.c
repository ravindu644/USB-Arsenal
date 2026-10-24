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
#include <grp.h>

#define KBD UA_F_KBD
#define MOUSE UA_F_MOUSE

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
      "          is which. --interval is saved as the setup (see `setup`);\n"
      "          without it, the saved one applies.\n"
      "  apply   Restart with the setup's interval if it changed, in one\n"
      "          replug; start if not running\n"
      "  stop    Remove them\n\n"
      "Options:\n"
      "  -p, --perm MODE    Octal permissions for the nodes (default: 0660)\n"
      "  -g, --group GROUP  Group owning the nodes, name or GID (default:\n"
      "                     root). Anyone who can write a node can type on\n"
      "                     the host, so widen this deliberately.\n"
      "  -i, --interval N   Keyboard poll interval, 1 to 255 (needs the f_hid\n"
      "                     patch). Lower is faster: at high speed the period\n"
      "                     is 2^(N-1) x 125us, so 4 (the kernel default) is\n"
      "                     1 ms and 1 is 125us. Set at start; stop to "
      "change.\n"
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

/* f_hid locks its attributes once the function is in a config, so this must
 * run before commit links it */
static int setup_fn(struct ua_gadget *g, const struct hid_fn *f, int *created,
                    unsigned interval) {
  char dir[PATH_MAX];
  ua_gadget_func_dir(g, f->func, dir, sizeof(dir));
  *created = access(dir, F_OK) != 0;
  if (ua_gadget_add(g, f->func, dir, sizeof(dir)) < 0)
    return -1;
  /* Already there and linked from an earlier start: out of the config for
   * the writes, commit puts it back */
  ua_gadget_unlink(g, f->func);
  if (write_attr_buf(dir, "protocol", f->protocol, 1) < 0 ||
      write_attr_buf(dir, "subclass", "1", 1) < 0 ||
      write_attr_buf(dir, "report_length", f->report_length, 1) < 0 ||
      write_attr_buf(dir, "report_desc", f->desc, f->desc_len) < 0) {
    ua_error("Cannot configure the %s: %s", f->label, strerror(errno));
    return -1;
  }
  /* The interrupt endpoint's bInterval is set by the kernel's f_hid, not the
   * report descriptor. Stock f_hid hardcodes it (1 ms at high speed); our patch
   * adds a writable "interval" attribute, so write it when it is there and fall
   * back with a note when it is not. */
  if (interval) {
    char ip[PATH_MAX];
    snprintf(ip, sizeof(ip), "%s/interval", dir);
    if (access(ip, F_OK) != 0) {
      ua_warn(
          "This kernel has no f_hid 'interval' control; --interval needs the "
          "USB Arsenal f_hid patch. Using the kernel default.");
    } else {
      char v[8];
      int n = snprintf(v, sizeof(v), "%u", interval);
      if (write_attr_buf(dir, "interval", v, (size_t)n) < 0) {
        ua_error("Cannot set the %s poll interval: %s", f->label,
                 strerror(errno));
        return -1;
      }
    }
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

void ua_hid_reapply(const struct ua_gadget *g) { apply_perm(g, 0); }

static gid_t parse_group(const char *s, int *ok) {
  char *end;
  long v = strtol(s, &end, 10);
  *ok = 1;
  if (*s && !*end && v >= 0)
    return (gid_t)v;
  /* By name through /etc/group; Android has none, so numbers only there */
  struct group *gr = getgrnam(s);
  *ok = gr != NULL;
  return gr ? gr->gr_gid : 0;
}

static int do_start(mode_t perm, gid_t gid, unsigned interval) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_HID) < 0)
    return UA_EXIT_FAIL;

  /* Saved before commit, which re-applies it after the bind */
  save_perm(perm, gid);
  if (!(ua_gadget_has(&g, KBD) && ua_gadget_has(&g, MOUSE) &&
        ua_gadget_live(&g))) {
    int created[2] = {0, 0};
    if (ua_gadget_begin(&g) < 0)
      return UA_EXIT_FAIL;
    /* The interval is about typing speed, so it rides on the keyboard only. */
    for (size_t i = 0; i < 2; i++)
      if (setup_fn(&g, &fns[i], &created[i], i == 0 ? interval : 0) < 0) {
        /* Undo only what this call made */
        for (size_t j = 0; j <= i; j++)
          if (created[j])
            ua_gadget_remove(&g, fns[j].func);
        ua_gadget_commit(&g);
        return UA_EXIT_FAIL;
      }
    if (ua_gadget_commit(&g) < 0)
      return UA_EXIT_FAIL;
  } else if (interval) {
    /* Already running: the interval only changes with a restart, so a start
     * that asked for another one has not done what it was asked. The app
     * reads the exit code, not the warning. */
    char dir[PATH_MAX], p[PATH_MAX + 16], v[16] = "";
    ua_gadget_func_dir(&g, KBD, dir, sizeof(dir));
    snprintf(p, sizeof(p), "%s/interval", dir);
    if (read_file(p, v, sizeof(v)) > 0 && strtoul(v, NULL, 10) != interval) {
      ua_error("HID is already running with interval %s; '" UA_NAME
               " hid apply' restarts it with %u",
               v, interval);
      return UA_EXIT_FAIL;
    }
  }
  if (apply_perm(&g, 1) < 0)
    return UA_EXIT_FAIL;
  ua_running_take(UA_FN_HID, 0);
  return UA_EXIT_OK;
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
  ua_running_clear(UA_FN_HID);
  return UA_EXIT_OK;
}

/* Bring HID to its setup: nothing when it runs unchanged, a swap inside one
 * critical section when the interval changed (the gadget stays bound, the
 * other functions keep serving), a start when it is not running */
static int do_apply(mode_t perm, gid_t gid, unsigned interval) {
  struct ua_setup s, r;
  struct ua_gadget g;
  ua_setup_load(&s);
  ua_running_load(&r);
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  if (!(ua_gadget_has(&g, KBD) && ua_gadget_live(&g)))
    return do_start(perm, gid, interval);
  if (!ua_setup_pending(UA_FN_HID, &s, &r)) {
    ua_log("HID already runs its setup");
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, KBD);
  ua_gadget_remove(&g, MOUSE);
  int ret = do_start(perm, gid, interval);
  if (ua_gadget_live(&g))
    ua_gadget_leave();
  else
    ua_gadget_commit(&g);
  return ret;
}

/* JSON: the elements of an array, one object per live node */
void ua_status_hid(const struct ua_gadget *g, int json) {
  int sep = 0;
  for (size_t i = 0; i < 2; i++) {
    char node[PATH_MAX];
    if (!ua_gadget_has(g, fns[i].func))
      continue;
    if (find_node(g, fns[i].func, node, sizeof(node)) < 0)
      node[0] = '\0';
    if (json) {
      /* The poll interval the function runs with: what the patched f_hid's
       * attribute holds (0 for its default, 1 ms), or -1 where the kernel
       * has no control for it (stock f_hid), so a client shows what the host
       * gets */
      char dir[PATH_MAX], p[PATH_MAX + 16], v[16] = "";
      long interval = -1;
      if (ua_gadget_func_dir(g, fns[i].func, dir, sizeof(dir)) == 0) {
        snprintf(p, sizeof(p), "%s/interval", dir);
        if (read_file(p, v, sizeof(v)) > 0)
          interval = strtol(v, NULL, 10);
      }
      int first = 1;
      printf("%s{", sep++ ? "," : "");
      ua_json_str("function", fns[i].label, &first);
      ua_json_str("node", node, &first);
      ua_json_int("interval", interval, &first);
      printf("}");
    } else {
      printf("HID:        %-8s %s\n", fns[i].label,
             node[0] ? node : "(no node)");
    }
  }
}

int ua_cmd_hid(int argc, char **argv) {
  static const struct option opts[] = {
      {"perm", required_argument, NULL, 'p'},
      {"group", required_argument, NULL, 'g'},
      {"interval", required_argument, NULL, 'i'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  struct ua_config cfg;
  struct ua_setup s;
  int c, ok, edited = 0;
  char *end;
  ua_config_active(&cfg);
  ua_setup_load(&s);
  mode_t perm = (mode_t)cfg.hid_perm;
  gid_t gid = (gid_t)cfg.hid_group;

  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_hid();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":p:g:i:h", opts, NULL)) != -1) {
    switch (c) {
    case 'p':
      perm = (mode_t)strtoul(optarg, &end, 8);
      if (!*optarg || *end || perm > 0777) {
        ua_usage_error("hid", "--perm takes an octal mode like 0660");
        return UA_EXIT_USAGE;
      }
      break;
    case 'i': {
      unsigned long v = strtoul(optarg, &end, 10);
      if (!*optarg || *end || v < 1 || v > 255) {
        ua_usage_error(
            "hid", "--interval takes 1 to 255 (the HID poll interval; at high "
                   "speed the period is 2^(N-1) x 125us, so 1 is 125us)");
        return UA_EXIT_USAGE;
      }
      /* Saved as the setup, so a bare start keeps it */
      s.hid_interval = (unsigned)v;
      edited = 1;
      break;
    }
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
  if (edited && ua_setup_save(&s) < 0)
    return UA_EXIT_FAIL;
  unsigned interval = s.hid_interval; /* 0 = leave the kernel's default */
  if (!strcmp(argv[optind], "start"))
    return do_start(perm, gid, interval);
  if (!strcmp(argv[optind], "apply"))
    return do_apply(perm, gid, interval);
  if (!strcmp(argv[optind], "stop"))
    return do_stop();
  ua_usage_error("hid", "Unknown action '%s'", argv[optind]);
  return UA_EXIT_USAGE;
}
