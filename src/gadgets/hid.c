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

/* Mouse: 5 buttons, relative X, Y, wheel and a horizontal wheel (the
 * Consumer page's AC Pan, usage 0x0238, which every OS reads as sideways
 * scroll). 5-byte input report; the logical range and size carry over from
 * the wheel, only the count is set again. */
static const unsigned char mouse_desc[] = {
    0x05, 0x01, 0x09, 0x02, 0xa1, 0x01, 0x09, 0x01, 0xa1, 0x00, 0x05,
    0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05,
    0x75, 0x01, 0x81, 0x02, 0x95, 0x01, 0x75, 0x03, 0x81, 0x01, 0x05,
    0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7f,
    0x75, 0x08, 0x95, 0x03, 0x81, 0x06, 0x05, 0x0c, 0x0a, 0x38, 0x02,
    0x95, 0x01, 0x81, 0x06, 0xc0, 0xc0,
};

struct hid_fn {
  const char *func, *label, *protocol, *report_length;
  const unsigned char *desc;
  size_t desc_len;
};

static const struct hid_fn fns[] = {
    {KBD, "keyboard", "1", "8", kbd_desc, sizeof(kbd_desc)},
    {MOUSE, "mouse", "2", "5", mouse_desc, sizeof(mouse_desc)},
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
      "  stop    Remove them\n"
      "  write NODE SIZE\n"
      "          Copy reports of SIZE bytes (1 to 64) from stdin to NODE, a\n"
      "          /dev/hidgN, without ever blocking: while the host is not\n"
      "          reading, only the newest unsent report is kept, and it goes\n"
      "          out as soon as the host reads again. Exits 0 at the end of\n"
      "          stdin once the last report went out (a second at most),\n"
      "          non-zero when it did not or NODE fails (the host went "
      "away).\n\n"
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
      "  mouse     5 bytes: buttons, X, Y, wheel, pan (signed)\n\n"
      "Examples:\n"
      "  " UA_NAME " hid start\n"
      "  " UA_NAME " hid start --group input\n"
      "  # type 'a' on the host (press, then release):\n"
      "  printf '\\0\\0\\x04\\0\\0\\0\\0\\0' > /dev/hidg0\n"
      "  printf '\\0\\0\\0\\0\\0\\0\\0\\0' > /dev/hidg0\n"
      "  # move the mouse 10 px right:\n"
      "  printf '\\0\\x0a\\0\\0\\0' > /dev/hidg1\n"
      "  # or keep one writer open for a stream of reports:\n"
      "  " UA_NAME " hid write /dev/hidg1 5 < reports\n"
      "  " UA_NAME " hid stop\n");
}

/* f_hid locks its attributes once the function is in a config, so this must
 * run before commit links it */
static int setup_fn(struct ua_gadget *g, const struct hid_fn *f, int *created,
                    unsigned interval) {
  char dir[PATH_MAX], ip[PATH_MAX];
  ua_gadget_func_dir(g, f->func, dir, sizeof(dir));
  snprintf(ip, sizeof(ip), "%s/interval", dir);
  /* No interval asked is the kernel's default, and only a fresh function has
   * it: one a crash left behind holds the last start's value, and no value
   * written means "default" everywhere (0 is our patch's default, but
   * mainline's own attribute takes it as bInterval 0, which hosts drop) */
  if (!interval && access(ip, F_OK) == 0)
    ua_gadget_remove(g, f->func);
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
   * and mainline (6.x) add a writable "interval" attribute, so write it when
   * one was asked and it is there, and fall back with a note when it is not */
  if (!interval)
    return 0;
  if (access(ip, F_OK) != 0) {
    ua_warn("This kernel has no f_hid 'interval' control; --interval needs the "
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
  return 0;
}

/* The function's "dev" attribute, major:minor. It is there unbound too. */
static int func_dev(const struct ua_gadget *g, const char *func, dev_t *out) {
  char dir[PATH_MAX], p[PATH_MAX + 8], dev[32];
  unsigned int maj, min;
  ua_gadget_func_dir(g, func, dir, sizeof(dir));
  snprintf(p, sizeof(p), "%s/dev", dir);
  if (read_file(p, dev, sizeof(dev)) <= 0 ||
      sscanf(dev, "%u:%u", &maj, &min) != 2)
    return -1;
  *out = makedev(maj, min);
  return 0;
}

/* The node name is up to ueventd/udev/devtmpfs, so find it by device number,
 * not by guessing */
static int find_node(const struct ua_gadget *g, const char *func, char *out,
                     size_t size, int tries) {
  dev_t dev;
  if (func_dev(g, func, &dev) < 0)
    return -1;
  /* The node shows up a moment after bind, when the device manager has run;
   * a bind waits up to [tries] tenths of a second for it, a read looks once */
  while (tries-- > 0) {
    DIR *d = opendir("/dev");
    struct dirent *e;
    while (d && (e = readdir(d))) {
      struct stat st;
      if (strncmp(e->d_name, "hidg", 4) != 0)
        continue;
      snprintf(out, size, "/dev/%s", e->d_name);
      if (stat(out, &st) == 0 && S_ISCHR(st.st_mode) && st.st_rdev == dev) {
        closedir(d);
        return 0;
      }
    }
    if (d)
      closedir(d);
    if (tries)
      usleep(100000);
  }
  return -1;
}

static void perm_path(char *out, size_t size) {
  ua_ws_path("Gadget", "hid.perm", out, size);
}

/* Only a --perm or --group given to a start is saved: a bare start (the
 * app's, an apply) must not put the profile's back over it. Without a saved
 * one, the profile's applies. The file goes with the HID function
 * (ua_running_clear). */
static void save_perm(mode_t perm, gid_t gid) {
  char p[PATH_MAX];
  perm_path(p, sizeof(p));
  int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
  if (fd < 0)
    return;
  dprintf(fd, "%o %u\n", (unsigned)perm, (unsigned)gid);
  close(fd);
}

/* The access in force: what a start was given, else the profile's */
static void load_perm(unsigned *perm, unsigned *gid) {
  char p[PATH_MAX], buf[64];
  struct ua_config c;
  ua_config_active(&c);
  *perm = c.hid_perm;
  *gid = c.hid_group;
  perm_path(p, sizeof(p));
  if (read_file(p, buf, sizeof(buf)) > 0)
    sscanf(buf, "%o %u", perm, gid);
}

/* Every unbind destroys /dev/hidgN and every bind makes it again, as
 * 0600 root, so the requested access is re-applied after each commit, not
 * only by `hid start` */
static int apply_perm(const struct ua_gadget *g, int report) {
  unsigned perm, gid;
  int ret = 0;
  /* Every commit comes here; only one with HID needs the profile read */
  if (!ua_gadget_has(g, KBD) && !ua_gadget_has(g, MOUSE))
    return 0;
  load_perm(&perm, &gid);
  for (size_t i = 0; i < 2; i++) {
    char node[PATH_MAX];
    if (!ua_gadget_has(g, fns[i].func))
      continue;
    if (find_node(g, fns[i].func, node, sizeof(node), 20) < 0) {
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

/* Whether process [pid] (its /proc name) has one of devs[] open, or with
 * [close_them] (our own fds, inherited without O_CLOEXEC) closes them. Only
 * an fd whose link names /dev/ or a hidg node is stat'ed: fstatat on a FUSE
 * file asks its daemon, which can hang, and we are in the critical section.
 * fstatat then follows the magic link to the inode, so a node the unbind
 * already deleted still matches. */
static int holds(int proc, const char *pid, const dev_t *devs, int n,
                 int close_them, int *fds) {
  char p[32];
  snprintf(p, sizeof(p), "%s/fd", pid);
  int dfd = openat(proc, p, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (dfd < 0)
    return 0;
  DIR *d = fdopendir(dfd);
  if (!d) {
    close(dfd);
    return 0;
  }
  struct dirent *e;
  int found = 0;
  while (!found && (e = readdir(d))) {
    char link[64];
    struct stat st;
    if (e->d_name[0] == '.')
      continue;
    (*fds)++;
    ssize_t r = readlinkat(dfd, e->d_name, link, sizeof(link) - 1);
    if (r <= 0)
      continue;
    link[r] = '\0';
    if ((strncmp(link, "/dev/", 5) && !strstr(link, "hidg")) ||
        fstatat(dfd, e->d_name, &st, 0) < 0 || !S_ISCHR(st.st_mode))
      continue;
    for (int i = 0; i < n; i++)
      if (st.st_rdev == devs[i]) {
        if (close_them)
          close(atoi(e->d_name));
        else
          found = 1;
      }
  }
  closedir(d);
  return found;
}

static long now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000L + ts.tv_nsec / 1000000;
}

/* Nobody may hold our /dev/hidgN across an unbind. f_hid without the 2022
 * lifetime fix (every 4.9 and 4.14, 4.19 before 4.19.270) frees its state on
 * unlink while the node is open, and f_hid without the 2026 fixes resets its
 * wait queues on every bind. A process asleep on the node then walks a freed
 * or reset list when it wakes, and the kernel BUGs (seen on 4.14 and 4.19).
 * So right after each unbind, which deletes the node, the holders are killed
 * (SIGKILL: a caught signal restarts the read or write right back onto the
 * queue) and waited for. One pass over /proc as Droidspaces walks it, 60 to
 * 80 ms on a phone with 16k fds; the wait looks at the killed pids only.
 * ponytail: sees only our pid namespace, and only the thread group leader's
 * fds (a holder whose main thread exited is missed); a copy in a container
 * cannot close a holder outside it. -1 when a holder may still be there. */
int ua_hid_evict(const struct ua_gadget *g) {
  dev_t devs[2];
  int n = 0;
  for (size_t i = 0; i < 2; i++)
    if (func_dev(g, fns[i].func, &devs[n]) == 0)
      n++;
  if (!n)
    return 0;
  DIR *d = opendir("/proc");
  if (!d) {
    ua_warn("Cannot read /proc to find who holds /dev/hidgN: %s",
            strerror(errno));
    return -1;
  }
  int proc = dirfd(d), fds = 0, nk = 0, lost = 0;
  pid_t killed[64];
  char self[16], name[32], comm[32];
  long t0 = now_ms();
  struct dirent *e;
  snprintf(self, sizeof(self), "%d", (int)getpid());
  holds(proc, "self", devs, n, 1, &fds);
  while ((e = readdir(d))) {
    if (e->d_name[0] < '1' || e->d_name[0] > '9' || !strcmp(e->d_name, self) ||
        !holds(proc, e->d_name, devs, n, 0, &fds))
      continue;
    pid_t pid = (pid_t)atoi(e->d_name);
    snprintf(name, sizeof(name), "/proc/%d/comm", (int)pid);
    if (read_file(name, comm, sizeof(comm)) <= 0)
      strcpy(comm, "?");
    kill(pid, SIGKILL);
    ua_debug("pid %d (%s) holds a HID node, killed it", (int)pid, comm);
    if (nk < (int)(sizeof(killed) / sizeof(killed[0])))
      killed[nk++] = pid;
    else
      lost = 1; /* killed but not waited for */
  }
  ua_debug("HID holders: %d fds looked at in %ld ms", fds, now_ms() - t0);
  int left = nk;
  for (int t = 0; left && t < 100; t++) {
    usleep(10000);
    left = 0;
    for (int i = 0; i < nk; i++) {
      snprintf(name, sizeof(name), "%d", (int)killed[i]);
      if (killed[i] && holds(proc, name, devs, n, 0, &fds))
        left++;
      else
        killed[i] = 0;
    }
  }
  closedir(d);
  for (int i = 0; i < nk; i++)
    if (killed[i])
      ua_warn("pid %d still holds a HID node after SIGKILL", (int)killed[i]);
  if (lost)
    ua_warn("Over %d processes held a HID node", nk);
  return left || lost ? -1 : 0;
}

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

static int do_start(mode_t perm, gid_t gid, int perm_given, unsigned interval) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_HID) < 0)
    return UA_EXIT_FAIL;

  /* Saved before commit, which re-applies it after the bind */
  if (perm_given)
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
 * critical section when the interval changed (one replug of the whole
 * gadget, as configfs swaps a function only unbound, but the port never goes
 * back to Android), a start when it is not running */
static int do_apply(mode_t perm, gid_t gid, int perm_given, unsigned interval) {
  struct ua_setup s, r;
  struct ua_gadget g;
  ua_setup_load(&s);
  ua_running_load(&r);
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  if (!(ua_gadget_has(&g, KBD) && ua_gadget_live(&g)))
    return do_start(perm, gid, perm_given, interval);
  if (!ua_setup_pending(UA_FN_HID, &s, &r)) {
    ua_log("HID already runs its setup");
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, KBD);
  ua_gadget_remove(&g, MOUSE);
  int ret = do_start(perm, gid, perm_given, interval);
  if (ua_gadget_live(&g))
    ua_crit_leave();
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
    if (find_node(g, fns[i].func, node, sizeof(node), 1) < 0)
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

/* 1 sent, 0 the host has not taken the last report yet, -1 failed */
static int send_report(int fd, const unsigned char *r, size_t size) {
  ssize_t n;
  while ((n = write(fd, r, size)) < 0 && errno == EINTR)
    ;
  return n >= 0 ? 1 : errno == EAGAIN ? 0 : -1;
}

/* hid write NODE SIZE: the reports on stdin, SIZE bytes each, onto NODE.
 * f_hid sends one report per write and, opened non-blocking, answers EAGAIN
 * while the host has not taken the one before. Nothing queues here: an unsent
 * report is replaced by the next, so while the writer runs the host ends in
 * the latest state (a release is not lost behind a press), and a host waking
 * from sleep gets no backlog replayed. No lock: it only writes a node the
 * caller can open. */
static int do_write(const char *node, const char *size_arg) {
  unsigned char rep[64], pend[64];
  struct stat st;
  char *end;
  unsigned long size = strtoul(size_arg, &end, 10);
  int fd, pending = 0, eof = 0, r;
  if (!*size_arg || *end || size < 1 || size > sizeof(rep)) {
    ua_usage_error("hid", "write takes a report SIZE of 1 to 64 bytes");
    return UA_EXIT_USAGE;
  }
  /* Asked before the open, which has side effects on some devices */
  if (stat(node, &st) == 0 && !S_ISCHR(st.st_mode)) {
    ua_error("%s is not a character device", node);
    return UA_EXIT_FAIL;
  }
  if ((fd = open(node, O_WRONLY | O_NONBLOCK | O_CLOEXEC)) < 0) {
    ua_error("%s: %s", node, strerror(errno));
    return UA_EXIT_FAIL;
  }
  while (!eof) {
    struct pollfd p[2] = {{STDIN_FILENO, POLLIN, 0}, {fd, POLLOUT, 0}};
    if (poll(p, pending ? 2 : 1, -1) < 0) {
      if (errno == EINTR)
        continue;
      goto fail;
    }
    if (p[0].revents) {
      size_t got = 0;
      ssize_t n;
      while (got < size) {
        if ((n = read(STDIN_FILENO, rep + got, size - got)) < 0 &&
            errno == EINTR)
          continue;
        if (n <= 0) {
          eof = 1; /* a report cut short by the end is dropped */
          break;
        }
        got += (size_t)n;
      }
      if (got == size) {
        memcpy(pend, rep, size);
        pending = 1;
      }
    }
    if (pending && (r = send_report(fd, pend, size)) < 0)
      goto fail;
    if (pending)
      pending = r == 0;
  }
  /* stdin is done: the newest report still goes, a second at most. A host
   * that takes nothing that long never gets it (a release among them), which
   * is a failure, not a clean end */
  if (pending) {
    struct pollfd p = {fd, POLLOUT, 0};
    poll(&p, 1, 1000);
    if ((r = send_report(fd, pend, size)) < 0)
      goto fail;
    if (r == 0) {
      ua_error("%s: the host did not take the last report", node);
      close(fd);
      return UA_EXIT_FAIL;
    }
  }
  close(fd);
  return UA_EXIT_OK;

fail:
  ua_error("%s: %s", node, strerror(errno));
  close(fd);
  return UA_EXIT_FAIL;
}

int ua_cmd_hid(int argc, char **argv) {
  static const struct option opts[] = {
      {"perm", required_argument, NULL, 'p'},
      {"group", required_argument, NULL, 'g'},
      {"interval", required_argument, NULL, 'i'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  struct ua_setup s;
  int c, ok, edited = 0, perm_given = 0;
  unsigned cur_perm, cur_gid;
  char *end;
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_hid();
    return UA_EXIT_OK;
  }
  if (!strcmp(argv[1], "write")) {
    if (argc != 4) {
      ua_usage_error("hid", "write takes NODE and SIZE");
      return UA_EXIT_USAGE;
    }
    return do_write(argv[2], argv[3]);
  }
  ua_setup_load(&s);
  /* A --perm alone keeps the group in force, and the other way round */
  load_perm(&cur_perm, &cur_gid);
  mode_t perm = (mode_t)cur_perm;
  gid_t gid = (gid_t)cur_gid;
  while ((c = getopt_long(argc, argv, ":p:g:i:h", opts, NULL)) != -1) {
    switch (c) {
    case 'p':
      perm = (mode_t)strtoul(optarg, &end, 8);
      if (!*optarg || *end || perm > 0777) {
        ua_usage_error("hid", "--perm takes an octal mode like 0660");
        return UA_EXIT_USAGE;
      }
      perm_given = 1;
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
      perm_given = 1;
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
    return do_start(perm, gid, perm_given, interval);
  if (!strcmp(argv[optind], "apply"))
    return do_apply(perm, gid, perm_given, interval);
  if (!strcmp(argv[optind], "stop"))
    return do_stop();
  ua_usage_error("hid", "Unknown action '%s'", argv[optind]);
  return UA_EXIT_USAGE;
}
