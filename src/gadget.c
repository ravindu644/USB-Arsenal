/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The gadget core. Every subsystem goes through begin -> add -> commit, and
 * every failure after begin goes through teardown, which is the one path that
 * hands the controller back. See
 * docs/developer/research/02-android-usb-handoff.md.
 */

#include "usbarsenal.h"

const char *ua_udc_opt;

/* What we took from the previous owner, written before we touch anything so
 * `stop` can put it back after a crash or a kill -9. */
struct handoff {
  char udc[64];         /* the controller we took */
  char usb_config[128]; /* Android: sys.usb.config before we started */
  char boot[40];        /* boot_id: a handoff from an earlier boot is void */
  char g1_udc[64];      /* Android's g1 was bound to this before we took it */
};

static int handoff_load(struct handoff *h);

/* What tells one boot from the next. boot_id where the kernel offers it,
 * the boot time from /proc/stat where a restricted container hides it; an
 * empty marker would make every old handoff look current. */
static void boot_marker(char *out, size_t size) {
  char buf[4096], *bt;
  if (read_file("/proc/sys/kernel/random/boot_id", out, size) > 0)
    return;
  if (read_file("/proc/stat", buf, sizeof(buf)) > 0 &&
      (bt = strstr(buf, "btime ")))
    snprintf(out, size, "btime-%ld", strtol(bt + 6, NULL, 10));
  else
    safe_strncpy(out, "unknown", size);
}

static void handoff_path(char *out, size_t size) {
  ua_ws_path("Gadget", "handoff", out, size);
}

int ua_gadget_taken(void) {
  struct handoff h;
  return handoff_load(&h) == 0;
}

static int handoff_save(const struct handoff *h) {
  char p[PATH_MAX];
  handoff_path(p, sizeof(p));
  int fd = open(p, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, 0600);
  FILE *f = fd >= 0 ? fdopen(fd, "w") : NULL;
  if (!f) {
    if (fd >= 0)
      close(fd);
    return -1;
  }
  fprintf(f, "udc=%s\nusb_config=%s\nboot=%s\ng1_udc=%s\n", h->udc,
          h->usb_config, h->boot, h->g1_udc);
  /* The whole point of this file is surviving a crash, so it has to be on
   * disk before the controller changes hands */
  int ok = fflush(f) == 0 && fsync(fileno(f)) == 0;
  return (fclose(f) == 0 && ok) ? 0 : -1;
}

static int handoff_load(struct handoff *h) {
  char p[PATH_MAX], line[512];
  memset(h, 0, sizeof(*h));
  handoff_path(p, sizeof(p));
  FILE *f = fopen(p, "re");
  if (!f)
    return -1;
  while (fgets(line, sizeof(line), f)) {
    line[strcspn(line, "\r\n")] = '\0';
    char *v = strchr(line, '=');
    if (!v)
      continue;
    *v++ = '\0';
    if (!strcmp(line, "udc"))
      safe_strncpy(h->udc, v, sizeof(h->udc));
    else if (!strcmp(line, "usb_config"))
      safe_strncpy(h->usb_config, v, sizeof(h->usb_config));
    else if (!strcmp(line, "boot"))
      safe_strncpy(h->boot, v, sizeof(h->boot));
    else if (!strcmp(line, "g1_udc"))
      safe_strncpy(h->g1_udc, v, sizeof(h->g1_udc));
  }
  fclose(f);

  /* A reboot already gave the controller back. Android keeps this file in
   * /data across it, and acting on it would skip releasing Android's gadget. */
  char boot[40] = "";
  boot_marker(boot, sizeof(boot));
  if (strcmp(boot, h->boot) != 0) {
    unlink(p);
    return -1;
  }
  return 0;
}

static void handoff_drop(void) {
  char p[PATH_MAX];
  handoff_path(p, sizeof(p));
  unlink(p);
}

/* Ctrl-C between unbinding Android and binding ours would leave the phone
 * with no USB at all, so the handoff runs with the usual stop signals held.
 * kill -9 still gets through; `stop` and the handoff file cover that. */
static int crit_depth, crit_lock = -1;
static sigset_t crit_old;

/* The critical section is also the only time this process holds the state
 * lock, so two commands run at once queue up instead of both deciding they
 * are first and overwriting each other's handoff. Released before a tether
 * daemon forks, so it never inherits the lock. */
void ua_crit_enter(void) {
  if (crit_depth++ == 0) {
    char p[PATH_MAX];
    ua_ws_path("Gadget", "lock", p, sizeof(p));
    crit_lock = open(p, O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (crit_lock >= 0)
      while (flock(crit_lock, LOCK_EX) < 0 && errno == EINTR)
        ;
    sigset_t s;
    sigemptyset(&s);
    sigaddset(&s, SIGINT);
    sigaddset(&s, SIGTERM);
    sigaddset(&s, SIGHUP);
    sigaddset(&s, SIGQUIT);
    sigprocmask(SIG_BLOCK, &s, &crit_old);
  }
}

void ua_crit_leave(void) {
  if (crit_depth > 0 && --crit_depth == 0) {
    if (crit_lock >= 0)
      close(crit_lock);
    crit_lock = -1;
    sigprocmask(SIG_SETMASK, &crit_old, NULL);
  }
}

static int pathf(char *out, size_t size, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));
static int pathf(char *out, size_t size, const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  int n = vsnprintf(out, size, fmt, ap);
  va_end(ap);
  return (n < 0 || (size_t)n >= size) ? -1 : 0;
}

static int write_attr(const char *dir, const char *attr, const char *val) {
  char p[PATH_MAX];
  if (pathf(p, sizeof(p), "%s/%s", dir, attr) < 0)
    return -1;
  if (write_file(p, val) < 0) {
    ua_debug("write %s <- %s: %s", p, val, strerror(errno));
    return -1;
  }
  return 0;
}

int ua_find_configfs(char *out, size_t size) {
  static const char *const roots[] = {"/sys/kernel/config", "/config"};
  for (int attempt = 0; attempt < 3; attempt++) {
    for (size_t i = 0; i < sizeof(roots) / sizeof(roots[0]); i++) {
      char p[PATH_MAX];
      snprintf(p, sizeof(p), "%s/usb_gadget", roots[i]);
      if (access(p, F_OK) == 0) {
        safe_strncpy(out, p, size);
        return 0;
      }
    }
    /* A plain Linux board often has configfs built but not mounted, and
     * libcomposite as a module. Android always has both ready. */
    if (attempt == 0 && grep_file("/proc/filesystems", "configfs") == 1)
      mount("none", "/sys/kernel/config", "configfs", 0, NULL);
    if (attempt == 1) {
      char *argv[] = {"modprobe", "libcomposite", NULL};
      run_command_quiet(argv);
    }
  }
  return -1;
}

int ua_list_udcs(char names[][64], int max) {
  DIR *d = opendir("/sys/class/udc");
  if (!d)
    return 0;
  int n = 0;
  struct dirent *e;
  while ((e = readdir(d)) && n < max)
    if (e->d_name[0] != '.')
      safe_strncpy(names[n++], e->d_name, 64);
  closedir(d);
  return n;
}

int ua_pick_udc(char *out, size_t size) {
  char names[16][64];
  int n = ua_list_udcs(names, 16);
  if (n == 0) {
    ua_error("No USB device controller in /sys/class/udc");
    ua_log("The kernel needs a peripheral-capable controller driver "
           "(on a Raspberry Pi: dtoverlay=dwc2).");
    return -1;
  }
  if (ua_udc_opt) {
    for (int i = 0; i < n; i++)
      if (!strcmp(names[i], ua_udc_opt)) {
        safe_strncpy(out, names[i], size);
        return 0;
      }
    ua_error("No such UDC: %s", ua_udc_opt);
  } else if (n == 1) {
    safe_strncpy(out, names[0], size);
    return 0;
  } else {
    ua_error("More than one USB device controller; pick one with --udc");
  }
  for (int i = 0; i < n; i++)
    ua_log("  %s", names[i]);
  return -1;
}

/* The first config of g1. Android names it b.1, but nothing guarantees it. */
/* Android's configfs gives every gadget an androidN device, all with devt 0,
 * and android_device_destroy() looks its device up by devt: dropping any
 * gadget destroys the oldest, android0, which belongs to g1. Android then
 * stops hearing USB state (no USB notification, tethering greyed out) until a
 * reboot. kernel-patches/ has the kernel fix for 4.9 to 6.6; meanwhile a gadget
 * we made stays, unbound and empty, instead of being removed. */
/* An empty write unbinds a gadget. "none" only does on Android's configfs,
 * which added it; mainline (a Pi, a PC under dummy_hcd) reads it as a
 * controller name and answers EBUSY, since the gadget is still bound. */
#define UNBIND "\n"

static int keep_gadget(void) {
  return access("/sys/class/android_usb", F_OK) == 0;
}

static int first_config(const char *gadget, char *out, size_t size) {
  char p[PATH_MAX];
  if (pathf(p, sizeof(p), "%s/configs", gadget) < 0)
    return -1;
  DIR *d = opendir(p);
  if (!d)
    return -1;
  struct dirent *e;
  int ret = -1;
  while ((e = readdir(d)))
    if (e->d_name[0] != '.') {
      ret = pathf(out, size, "%s/%s", p, e->d_name);
      break;
    }
  closedir(d);
  return ret;
}

int ua_gadget_open(struct ua_gadget *g) {
  memset(g, 0, sizeof(*g));
  if (ua_find_configfs(g->cfs, sizeof(g->cfs)) < 0) {
    ua_error("configfs USB gadget support not found");
    ua_log("The kernel needs CONFIG_USB_CONFIGFS. Run '" UA_NAME
           " check' for the full report.");
    return -1;
  }

  struct handoff h;
  int taken = handoff_load(&h) == 0;
  if (taken && h.udc[0]) {
    /* Stick to the controller we already hold, whatever --udc says now */
    safe_strncpy(g->udc, h.udc, sizeof(g->udc));
  } else if (ua_pick_udc(g->udc, sizeof(g->udc)) < 0) {
    return -1;
  }

  char own[PATH_MAX];
  if (pathf(own, sizeof(own), "%s/" UA_GADGET, g->cfs) < 0)
    return -1;

  /* Asked of the kernel, not of /system: inside a container on a phone (a
   * Droidspaces rootfs, a chroot) is_android() is false, but g1 and the
   * android_usb class are still there and Android still owns the UDC. */
  char p[PATH_MAX];
  g->android_gadget =
      is_android() ||
      (pathf(p, sizeof(p), "%s/g1", g->cfs) == 0 && access(p, F_OK) == 0 &&
       access("/sys/class/android_usb", F_OK) == 0);

  if (access(own, F_OK) != 0) {
    /* Stock Samsung configfs names every gadget's uevent device android0, so a
     * second one fails in device_create() before anything is set up and the
     * mkdir returns ENOMEM. Nothing changes; it is the cheapest detector. */
    if (mkdir(own, 0755) < 0) {
      int err = errno;
      if (err == ENOMEM && g->android_gadget &&
          access("/sys/class/android_usb/android0/functions", F_OK) == 0) {
        ua_error("This is stock Samsung configfs, which cannot create a "
                 "second gadget next to Android's");
        ua_log("It needs the configfs patches in kernel-patches/4.14 or 4.19 "
               "(docs/kernel-requirements.md). Nothing was "
               "changed.");
      } else {
        ua_error("Cannot create a gadget in %s: %s", g->cfs, strerror(err));
      }
      return -1;
    }
    if (!keep_gadget())
      rmdir(own);
  }

  safe_strncpy(g->path, own, sizeof(g->path));
  if (pathf(g->config, sizeof(g->config), "%s/configs/" UA_CONFIG, own) < 0)
    return -1;
  return 0;
}

/* Our functions carry the "ua" instance */
static int is_ours(const char *func) {
  const char *dot = strchr(func, '.');
  return dot && !strncmp(dot + 1, "ua", 2);
}

int ua_gadget_func_dir(const struct ua_gadget *g, const char *func, char *dir,
                       size_t size) {
  return pathf(dir, size, "%s/functions/%s", g->path, func);
}

int ua_gadget_has(const struct ua_gadget *g, const char *func) {
  char p[PATH_MAX];
  struct stat st;
  return pathf(p, sizeof(p), "%s/%s", g->config, func) == 0 &&
         lstat(p, &st) == 0;
}

/* Bound to the controller right now, as far as this tool is concerned */
int ua_gadget_live(const struct ua_gadget *g) {
  char p[PATH_MAX], buf[64];
  if (!ua_gadget_taken())
    return 0;
  return pathf(p, sizeof(p), "%s/UDC", g->path) == 0 &&
         read_file(p, buf, sizeof(buf)) > 0;
}

static int build_skeleton(const struct ua_gadget *g) {
  char p[PATH_MAX], v[160];
  struct ua_config c;
  if (mkdir(g->path, 0755) < 0 && errno != EEXIST)
    return -1;
  /* The active profile, or the built-in identity. Written on every take and
   * every rebind, so `identity set` reaches the host at the next enumeration */
  ua_config_active(&c);
  snprintf(v, sizeof(v), "0x%04x", c.id_vendor);
  write_attr(g->path, "idVendor", v);
  snprintf(v, sizeof(v), "0x%04x", c.id_product);
  write_attr(g->path, "idProduct", v);
  snprintf(v, sizeof(v), "0x%04x", c.bcd_device);
  write_attr(g->path, "bcdDevice", v);
  snprintf(v, sizeof(v), "0x%04x", c.bcd_usb);
  write_attr(g->path, "bcdUSB", v);
  /* Misc/IAD: Windows only enumerates a composite with an RNDIS or NCM
   * function properly when the device says it uses interface associations */
  write_attr(g->path, "bDeviceClass", "0xEF");
  write_attr(g->path, "bDeviceSubClass", "0x02");
  write_attr(g->path, "bDeviceProtocol", "0x01");

  if (pathf(p, sizeof(p), "%s/strings/0x409", g->path) < 0 ||
      mkdir_p(p, 0755) < 0)
    return -1;
  write_attr(p, "manufacturer", c.manufacturer);
  write_attr(p, "product", c.product);
  if (!c.serial[0])
    ua_device_seed(c.serial, sizeof(c.serial));
  write_attr(p, "serialnumber", c.serial);

  if (pathf(p, sizeof(p), "%s/strings/0x409", g->config) < 0 ||
      mkdir_p(p, 0755) < 0)
    return -1;
  /* Samsung's configfs treats any configuration string other than "Conf 1" as
   * "symlink change mode" and binds links straight away. Harmless elsewhere. */
  write_attr(p, "configuration", c.configuration);
  snprintf(v, sizeof(v), "%u", c.max_power);
  write_attr(g->config, "MaxPower", v);
  return 0;
}

/* Off Android nobody hands the controller over politely. If another gadget
 * holds it, say who and stop: we do not unbind someone else's gadget. */
static int udc_owner(const struct ua_gadget *g, char *owner, size_t size) {
  DIR *d = opendir(g->cfs);
  if (!d)
    return 0;
  struct dirent *e;
  int found = 0;
  while (!found && (e = readdir(d))) {
    char p[PATH_MAX], buf[64];
    if (e->d_name[0] == '.' || !strcmp(e->d_name, UA_GADGET))
      continue;
    if (pathf(p, sizeof(p), "%s/%s/UDC", g->cfs, e->d_name) == 0 &&
        read_file(p, buf, sizeof(buf)) > 0 && !strcmp(buf, g->udc)) {
      safe_strncpy(owner, e->d_name, size);
      found = 1;
    }
  }
  closedir(d);
  return found;
}

static void android_release(const struct ua_gadget *g) {
  char p[PATH_MAX], buf[64];
  /* Ask init first, so adbd and the HAL stand down instead of rebinding.
   * Without the property service (a container) there is nobody to ask. */
  if (is_android()) {
    ua_debug("setprop sys.usb.config none");
    ua_setprop("sys.usb.config", "none");
    if (ua_wait_prop("sys.usb.state", "none", UA_HANDOFF_TIMEOUT_MS) < 0)
      ua_warn("Android did not report sys.usb.state=none in time");
  }
  /* A gadget HAL may not follow the property at all. Take it by hand. */
  if (pathf(p, sizeof(p), "%s/g1/UDC", g->cfs) == 0 &&
      read_file(p, buf, sizeof(buf)) > 0) {
    ua_debug("g1 still bound to %s, unbinding it", buf);
    write_file(p, UNBIND);
  }
}

int ua_gadget_begin(struct ua_gadget *g) {
  ua_crit_enter();

  if (ua_gadget_taken()) {
    /* Already ours: unbind so functions can change. Attributes like the HID
     * report descriptor are locked while the function is in use. */
    if (ua_gadget_live(g))
      write_attr(g->path, "UDC", UNBIND);
    /* Idempotent, and covers a crash between saving the handoff and
     * building the gadget the first time round */
    if (build_skeleton(g) < 0) {
      ua_error("Cannot build the gadget in %s: %s", g->path, strerror(errno));
      ua_crit_leave();
      return -1;
    }
    return 0;
  }

  struct handoff h;
  memset(&h, 0, sizeof(h));
  safe_strncpy(h.udc, g->udc, sizeof(h.udc));
  boot_marker(h.boot, sizeof(h.boot));

  /* Android's gadget but no Android userspace: a container or chroot running
   * us directly. Its workspace is not Android's, so a copy run from Android
   * would keep a second hand-back record for the same controller. */
  if (g->android_gadget && !is_android())
    ua_warn("Running directly inside a container: the hand-back record is "
            "in %s. Do not run USB Arsenal from Android at the same time; "
            "bind-mount the daemon's Run/ to go through it instead (see "
            "docs/containers.md).",
            ua_workspace());

  char owner[256] = "";
  int owned = udc_owner(g, owner, sizeof(owner));
  if (owned && g->android_gadget && !strcmp(owner, "g1"))
    safe_strncpy(h.g1_udc, g->udc, sizeof(h.g1_udc));
  else if (owned) {
    /* Not Android's: we do not unbind someone else's gadget */
    ua_error("%s is in use by the gadget '%s'", g->udc, owner);
    ua_log("Unbind it first: echo none > %s/%s/UDC", g->cfs, owner);
    ua_crit_leave();
    return -1;
  }

  if (is_android()) {
    ua_getprop("sys.usb.config", h.usb_config, sizeof(h.usb_config));
    if (!h.usb_config[0] || !strcmp(h.usb_config, "none"))
      ua_getprop("persist.sys.usb.config", h.usb_config, sizeof(h.usb_config));
    if (!h.usb_config[0] || !strcmp(h.usb_config, "none"))
      safe_strncpy(h.usb_config, "mtp", sizeof(h.usb_config));
  }
  if (handoff_save(&h) < 0) {
    ua_error("Cannot write the handoff state in %s: %s", ua_workspace(),
             strerror(errno));
    ua_crit_leave();
    return -1;
  }

  if (g->android_gadget)
    android_release(g);
  if (build_skeleton(g) < 0) {
    ua_error("Cannot build the gadget in %s: %s", g->path, strerror(errno));
    ua_gadget_teardown(g);
    ua_crit_leave();
    return -1;
  }
  return 0;
}

int ua_gadget_add(struct ua_gadget *g, const char *func, char *dir,
                  size_t size) {
  if (ua_gadget_func_dir(g, func, dir, size) < 0)
    return -1;
  if (mkdir(dir, 0755) < 0 && errno != EEXIST) {
    int err = errno;
    ua_error("Cannot create function %s: %s", func, strerror(err));
    if (err == ENOENT || err == EINVAL)
      ua_log("The kernel was built without this function. Run '" UA_NAME
             " check'.");
    return -1;
  }
  return 0;
}

/* Out of the config but kept: some attributes (a new LUN, HID descriptors,
 * NCM addresses) can only change while nothing uses the function. Commit
 * links it back. */
void ua_gadget_unlink(struct ua_gadget *g, const char *func) {
  char p[PATH_MAX];
  if (pathf(p, sizeof(p), "%s/%s", g->config, func) == 0 && unlink(p) < 0 &&
      errno != ENOENT)
    ua_warn("Cannot unlink %s: %s", p, strerror(errno));
}

/* Names of the entries of dir (no dot entries) that pass keep(), read in
 * full before the caller removes any: rmdir during readdir of the same
 * configfs directory may skip entries. */
#define MAX_ENTRIES 64
static int list_dir(const char *dir, char names[][NAME_MAX + 1],
                    int (*keep)(const char *dir, const struct dirent *e)) {
  DIR *d = opendir(dir);
  struct dirent *e;
  int n = 0;
  while (d && n < MAX_ENTRIES && (e = readdir(d)))
    if (e->d_name[0] != '.' && keep(dir, e))
      safe_strncpy(names[n++], e->d_name, NAME_MAX + 1);
  if (d)
    closedir(d);
  return d ? n : -1;
}

static int keep_dir(const char *dir, const struct dirent *e) {
  (void)dir;
  return e->d_type == DT_DIR;
}

static int keep_link(const char *dir, const struct dirent *e) {
  char p[PATH_MAX];
  struct stat st;
  return pathf(p, sizeof(p), "%s/%s", dir, e->d_name) == 0 &&
         lstat(p, &st) == 0 && S_ISLNK(st.st_mode);
}

static int keep_ours(const char *dir, const struct dirent *e) {
  (void)dir;
  return is_ours(e->d_name);
}

int ua_gadget_remove(struct ua_gadget *g, const char *func) {
  char p[PATH_MAX];
  if (pathf(p, sizeof(p), "%s/%s", g->config, func) == 0 && unlink(p) < 0 &&
      errno != ENOENT)
    ua_warn("Cannot unlink %s: %s", p, strerror(errno));
  if (ua_gadget_func_dir(g, func, p, sizeof(p)) < 0)
    return -1;
  /* Groups made under the function (lun.1 and up) must go first. Its own
   * default groups (lun.0, os_desc) refuse rmdir, which is fine. */
  char names[MAX_ENTRIES][NAME_MAX + 1];
  int n = list_dir(p, names, keep_dir);
  for (int i = 0; i < n; i++) {
    char sub[PATH_MAX];
    if (pathf(sub, sizeof(sub), "%s/%s", p, names[i]) == 0)
      rmdir(sub);
  }
  if (rmdir(p) < 0 && errno != ENOENT) {
    ua_warn("Cannot remove %s: %s", p, strerror(errno));
    return -1;
  }
  return 0;
}

/* Link every function of ours that is not linked yet; returns how many are */
static int link_functions(struct ua_gadget *g) {
  char fdir[PATH_MAX];
  if (pathf(fdir, sizeof(fdir), "%s/functions", g->path) < 0)
    return -1;
  DIR *d = opendir(fdir);
  if (!d)
    return -1;
  int n = 0, failed = 0;
  struct dirent *e;
  while ((e = readdir(d))) {
    char src[PATH_MAX], dst[PATH_MAX];
    if (e->d_name[0] == '.' || !is_ours(e->d_name))
      continue;
    if (pathf(src, sizeof(src), "%s/%s", fdir, e->d_name) < 0 ||
        pathf(dst, sizeof(dst), "%s/%s", g->config, e->d_name) < 0)
      continue;
    if (symlink(src, dst) < 0 && errno != EEXIST) {
      ua_error("Cannot link %s: %s", e->d_name, strerror(errno));
      failed = 1;
      continue;
    }
    n++;
  }
  closedir(d);
  return failed ? -1 : n;
}

/* Android's udc core comments out the pullup after bind on some kernels
 * (the HACK), so the host sees nothing. soft_connect raises it, but its
 * "connect" calls usb_gadget_udc_start() unconditionally before the core
 * learned to track a started UDC (no "started" guard in 4.14). On a dwc3
 * that bind already started, dwc3_gadget_start() requests its IRQ, finds a
 * driver bound, and frees that IRQ on the way out while it is still in use;
 * the next unbind then panics the phone (seen on the S10 with the M62
 * kernel). So: leave it alone when the host is already enumerating us, and
 * otherwise use the balanced pair, disconnect (udc_stop) then connect
 * (udc_start), which never starts a started controller. */
static void pullup(const struct ua_gadget *g) {
  char p[PATH_MAX], st[64];
  if (pathf(p, sizeof(p), "/sys/class/udc/%s/state", g->udc) < 0)
    return;
  for (int waited = 0; waited < UA_PULLUP_WAIT_MS; waited += 100) {
    if (read_file(p, st, sizeof(st)) > 0 && strcmp(st, "not attached") != 0)
      return; /* the host is talking to us: the pullup is up */
    usleep(100000);
  }
  if (pathf(p, sizeof(p), "/sys/class/udc/%s/soft_connect", g->udc) < 0)
    return;
  ua_debug("no enumeration after bind, cycling soft_connect");
  if (write_file(p, "disconnect") < 0 || write_file(p, "connect") < 0)
    ua_debug("soft_connect: %s", strerror(errno));
}

static void wait_configured(const struct ua_gadget *g) {
  char p[PATH_MAX];
  if (pathf(p, sizeof(p), "/sys/class/udc/%s/state", g->udc) == 0 &&
      ua_wait_file(p, "configured", UA_ENUM_TIMEOUT_MS) < 0)
    ua_warn("The host has not configured the gadget yet (is the cable "
            "plugged into a computer?)");
}

int ua_gadget_commit(struct ua_gadget *g) {
  char udc[80];
  int n = link_functions(g);
  if (n < 0)
    goto fail;
  if (n == 0) {
    ua_gadget_teardown(g);
    ua_crit_leave();
    return 0;
  }
  snprintf(udc, sizeof(udc), "%s\n", g->udc);

  if (write_attr(g->path, "UDC", udc) < 0) {
    int err = errno;
    ua_error("Binding to %s failed: %s", g->udc, strerror(err));
    if (err == EINVAL)
      ua_log("Samsung configfs without the bring-up fix (kernel-patches/4.14 "
             "or 4.19) cannot bind a hand-built gadget.");
    else if (err == EBUSY)
      ua_log("Another gadget or a legacy g_* driver holds the controller.");
    goto fail;
  }
  pullup(g);
  wait_configured(g);
  ua_crit_leave();
  ua_hid_reapply(g);
  return 0;

fail:
  ua_gadget_teardown(g);
  ua_crit_leave();
  return -1;
}

static int unlink_all(const char *dir) {
  char names[MAX_ENTRIES][NAME_MAX + 1], p[PATH_MAX];
  int n = list_dir(dir, names, keep_link);
  for (int i = 0; i < n; i++)
    if (pathf(p, sizeof(p), "%s/%s", dir, names[i]) == 0)
      unlink(p);
  return n;
}

/* Our own gadget holds something: bound, or functions linked. A kept, empty
 * gadget (keep_gadget) does not count. */
int ua_gadget_in_use(const char *cfs) {
  char p[PATH_MAX], buf[64], names[MAX_ENTRIES][NAME_MAX + 1];
  if (pathf(p, sizeof(p), "%s/" UA_GADGET "/UDC", cfs) == 0 &&
      read_file(p, buf, sizeof(buf)) > 0)
    return 1;
  return pathf(p, sizeof(p), "%s/" UA_GADGET "/configs/" UA_CONFIG, cfs) == 0 &&
         list_dir(p, names, keep_link) > 0;
}

static void remove_ours(struct ua_gadget *g) {
  char names[MAX_ENTRIES][NAME_MAX + 1], fdir[PATH_MAX];
  if (pathf(fdir, sizeof(fdir), "%s/functions", g->path) < 0)
    return;
  int n = list_dir(fdir, names, keep_ours);
  for (int i = 0; i < n; i++)
    ua_gadget_remove(g, names[i]);
}

/* g1's config has functions linked: Android has set up a mode, and binding
 * it now gives the user that mode instead of an empty device */
static int g1_ready(const struct ua_gadget *g) {
  char g1[PATH_MAX], cfg[PATH_MAX], names[MAX_ENTRIES][NAME_MAX + 1];
  return pathf(g1, sizeof(g1), "%s/g1", g->cfs) == 0 &&
         first_config(g1, cfg, sizeof(cfg)) == 0 &&
         list_dir(cfg, names, keep_link) > 0;
}

static int g1_bound(const struct ua_gadget *g) {
  char p[PATH_MAX], buf[64];
  return pathf(p, sizeof(p), "%s/g1/UDC", g->cfs) == 0 &&
         read_file(p, buf, sizeof(buf)) > 0;
}

static void bind_g1(const struct ua_gadget *g) {
  char p[PATH_MAX], udc[80];
  snprintf(udc, sizeof(udc), "%s\n", g->udc);
  if (pathf(p, sizeof(p), "%s/g1/UDC", g->cfs) == 0 && write_file(p, udc) == 0)
    pullup(g);
  else
    ua_error("Cannot rebind Android's g1 to %s: %s", g->udc, strerror(errno));
}

static void android_restore(const struct ua_gadget *g,
                            const struct handoff *h) {
  if (is_android() && h->usb_config[0]) {
    ua_debug("setprop sys.usb.config %s", h->usb_config);
    ua_setprop("sys.usb.config", h->usb_config);
    /* adb modes also wait for adbd to come back, which takes a while */
    if (ua_wait_prop("sys.usb.state", h->usb_config, UA_RESTORE_TIMEOUT_MS) < 0)
      ua_debug("sys.usb.state did not reach %s in time", h->usb_config);
  }
  /* No property service (container), or a HAL that ignored it: bind g1
   * ourselves. Only once its functions are linked, or we would race init
   * while it is still relinking and leave g1 bound with nothing in it. */
  if ((h->g1_udc[0] || is_android()) && !g1_bound(g) && g1_ready(g)) {
    if (is_android())
      ua_warn("Android did not rebind g1, binding it by hand");
    bind_g1(g);
  }
}

void ua_gadget_teardown(struct ua_gadget *g) {
  struct handoff h;
  int have = handoff_load(&h) == 0, was_here = 0;
  char p[PATH_MAX];

  ua_crit_enter();
  /* Everything of ours goes, NCM included: its network side must not keep
   * serving an interface that is about to vanish */
  ua_tether_kill();
  if (access(g->path, F_OK) == 0) {
    char buf[64];
    if (pathf(p, sizeof(p), "%s/UDC", g->path) == 0 &&
        read_file(p, buf, sizeof(buf)) > 0) {
      write_file(p, UNBIND);
      was_here = 1;
    }
    if (unlink_all(g->config) > 0)
      was_here = 1;
    if (pathf(p, sizeof(p), "%s/os_desc", g->path) == 0)
      unlink_all(p);
    remove_ours(g);
    if (!keep_gadget()) {
      if (pathf(p, sizeof(p), "%s/strings/0x409", g->config) == 0)
        rmdir(p);
      rmdir(g->config);
      if (pathf(p, sizeof(p), "%s/strings/0x409", g->path) == 0)
        rmdir(p);
      if (rmdir(g->path) < 0 && errno != ENOENT)
        ua_warn("Cannot remove %s: %s", g->path, strerror(errno));
    }
  }

  if (g->android_gadget) {
    if (have)
      android_restore(g, &h);
    /* Our gadget was there but the record of what we took is gone (a crash
     * between deleting it and finishing, an older version): Android's g1
     * with functions linked and nothing bound can only mean we left it so */
    else if (was_here && !g1_bound(g) && g1_ready(g))
      bind_g1(g);
  }

  /* The record goes only once the controller is really back. If g1 did not
   * come back, keeping it means the next `stop` tries again. */
  if (have && h.g1_udc[0] && !g1_bound(g))
    ua_error("Android's gadget is not bound yet; run '" UA_NAME " stop' again");
  else
    handoff_drop();
  ua_crit_leave();
}
