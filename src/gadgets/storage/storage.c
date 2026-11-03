/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `storage`: f_mass_storage LUNs. Booting an ISO is entirely the kernel's
 * job; we only pick between CD-ROM and disk mode and write the attributes in
 * the order the kernel accepts. See
 * docs/developer/research/03-mass-storage-cdrom.md.
 */

#include "internal.h"
#include "usbarsenal.h"

#define FUNC UA_F_STORAGE

/* The profile's SCSI inquiry string, empty for the kernel's own */
static char inquiry[32];

static const char *const mode_names[] = {"auto", "cdrom", "dvd", "disk", "rw"};
#define NMODES (int)(sizeof(mode_names) / sizeof(mode_names[0]))

static int mode_of(const char *name) {
  for (int i = 0; i < NMODES; i++)
    if (!strcmp(name, mode_names[i]))
      return i;
  return -1;
}

void ua_help_storage(void) {
  printf(
      "Usage: " UA_NAME " storage <action> [options] [IMAGE]\n"
      "       " UA_NAME " storage start [--lun N] [--mode MODE] IMAGE "
      "[[--lun N] [--mode MODE] IMAGE ...]\n\n"
      "Present an image file or block device to the host as a USB drive.\n"
      "A bootable ISO boots on the host like a real USB stick or DVD.\n\n"
      "Actions:\n"
      "  start [IMAGE] Insert IMAGE. If the LUN already holds an image, swap\n"
      "                it in place without the host re-enumerating the "
      "device.\n"
      "                The IMAGEs given are saved as the setup (see `setup`);\n"
      "                with none, the saved setup starts.\n"
      "  apply         Bring the drives to the setup: eject what it dropped,\n"
      "                load what it changed or added, leave the rest alone\n"
      "  eject         Take the medium out; the host sees an empty drive\n"
      "  stop          Remove the USB drive from the gadget entirely\n\n"
      "Options:\n"
      "  -m, --mode MODE  How the host sees the image (default: auto)\n"
      "                     auto   disk if the image has an MBR or GPT\n"
      "                            (isohybrid Linux ISOs, USB images),\n"
      "                            CD-ROM otherwise, dvd past the CD-ROM\n"
      "                            limit (Windows ISOs)\n"
      "                     cdrom  read-only CD-ROM, boots plain ISO9660/UDF\n"
      "                            via El Torito. Limit: about 2.36 GB.\n"
      "                     dvd    read-only disk with 2048-byte sectors, for\n"
      "                            plain ISOs of any size. UEFI boots it via\n"
      "                            El Torito and Windows reads its UDF.\n"
      "                            Needs kernel 4.14+ (loop block size).\n"
      "                     disk   read-only disk\n"
      "                     rw     writable disk: a pen drive the host can\n"
      "                            write to. Never use on a mounted image.\n"
      "  -l, --lun N      LUN to use, 0-7 (default: 0). Each LUN is a\n"
      "                   separate drive on the host.\n"
      "                   start takes up to eight IMAGEs at once, each\n"
      "                   after its own --lun and --mode, and the host\n"
      "                   sees all of them in one replug. An IMAGE with\n"
      "                   no --lun takes the LUN after the one before.\n"
      "  -h, --help       Show this help\n\n"
      "Examples:\n"
      "  " UA_NAME " storage start ubuntu-24.04.iso\n"
      "  " UA_NAME " storage start --mode dvd Win11.iso\n"
      "  " UA_NAME " storage start --mode rw /data/local/tmp/pendrive.img\n"
      "  " UA_NAME " storage start --lun 1 --mode disk rescue.img\n"
      "  " UA_NAME " storage start Win11.iso --mode rw pendrive.img\n"
      "  " UA_NAME " storage start fedora.iso     # swaps the live image\n"
      "  " UA_NAME " storage start                # what `setup show` lists\n"
      "  " UA_NAME " storage eject\n"
      "  " UA_NAME " storage stop\n\n"
      "Make a blank 4 GB pen drive image:\n"
      "  truncate -s 4G pendrive.img\n");
}

static int open_image(const char *image, enum mode mode);

static int lun_dir(const char *fdir, int lun, char *out, size_t size) {
  int n = snprintf(out, size, "%s/lun.%d", fdir, lun);
  return (n < 0 || (size_t)n >= size) ? -1 : 0;
}

/* A dvd LUN holds a loop device rather than the image itself */
static int is_loop(const char *file) {
  const char *base = strrchr(file, '/');
  return base && !strncmp(base + 1, "loop", 4);
}

/* Eject. "\n" rather than "": a zero-length write never reaches the store
 * function. forced_eject (6.6+) also clears a host's medium lock. */
static int lun_eject(const char *ldir) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/forced_eject", ldir);
  if (access(p, F_OK) == 0)
    return write_file(p, "1");
  return write_attr(ldir, "file", "\n");
}

/* Tenths of a second that every eject wait of one command shares. A host's
 * medium lock lets go once the kernel has processed the disconnect, which
 * every LUN sees at once; eight locked LUNs waiting in turn would add up past
 * the app's 30 s timeout, whose kill lands mid-commit. */
static int eject_budget = 50;

/* Eject, retrying while EBUSY as long as the budget lasts */
static int lun_eject_wait(const char *ldir) {
  int r;
  while ((r = lun_eject(ldir)) < 0 && errno == EBUSY && eject_budget > 0) {
    eject_budget--;
    usleep(100000);
  }
  return r;
}

/* Write order matters: cdrom and ro return EBUSY while a file is open, so
 * the medium goes out first and the new one goes in last.
 * Returns -EBUSY, quietly, when the host holds a medium lock: the caller
 * decides whether a rebind (which clears it) is the way out. With wait set
 * the eject is retried while the kernel processes that disconnect. A medium
 * the kernel refuses puts the old one back. */
static int lun_load(const char *ldir, const char *path, enum mode mode,
                    int wait) {
  char cur[PATH_MAX] = "", file[PATH_MAX + 2], ro[4] = "", cdrom[4] = "";
  int old = -1, r = -1;
  snprintf(file, sizeof(file), "%s/file", ldir);
  if (read_file(file, cur, sizeof(cur)) > 0) {
    /* Held across the swap so it can go back in: a dvd LUN's loop device
     * would detach on eject (autoclear) with nobody else holding it */
    snprintf(file, sizeof(file), "%s/ro", ldir);
    read_file(file, ro, sizeof(ro));
    snprintf(file, sizeof(file), "%s/cdrom", ldir);
    read_file(file, cdrom, sizeof(cdrom));
    old = open(cur, (ro[0] == '0' ? O_RDWR : O_RDONLY) | O_CLOEXEC);
    if ((wait ? lun_eject_wait(ldir) : lun_eject(ldir)) < 0) {
      r = errno == EBUSY ? -EBUSY : -1;
      goto out;
    }
  }
  write_attr(ldir, "removable", "1");
  if (inquiry[0])
    write_attr(ldir, "inquiry_string", inquiry);
  if (write_attr(ldir, "cdrom", mode == MODE_CDROM ? "1" : "0") < 0 &&
      mode == MODE_CDROM) {
    ua_error("This kernel cannot emulate a CD-ROM (no lun.N/cdrom)");
    goto restore;
  }
  write_attr(ldir, "ro", mode == MODE_RW ? "0" : "1");

  snprintf(file, sizeof(file), "%s\n", path);
  if (write_attr(ldir, "file", file) < 0) {
    ua_error("The kernel refused %s: %s", path, strerror(errno));
    goto restore;
  }
  r = 0;
  goto out;

restore:
  if (old >= 0) {
    if (cdrom[0])
      write_attr(ldir, "cdrom", cdrom);
    if (ro[0])
      write_attr(ldir, "ro", ro);
    snprintf(file, sizeof(file), "/proc/self/fd/%d\n", old);
    if (write_attr(ldir, "file", file) == 0)
      ua_log("Put %s back", cur);
    else
      ua_warn("Cannot put %s back: %s", cur, strerror(errno));
  }
out:
  if (old >= 0)
    close(old);
  return r;
}

/* One file behind two LUNs, either of them writable: the host caches each
 * drive on its own, so a write through one corrupts what the other shows,
 * and two writers corrupt the file. Live LUNs this call replaces do not
 * count. A dvd LUN's loop device is skipped; it is read-only anyway. */
static int shared_rw(const struct load *ls, int n, const char *fdir) {
  struct stat a, b;
  for (int i = 0; i < n; i++) {
    if (fstat(ls[i].fd, &a) < 0)
      continue;
    for (int j = 0; j < i; j++)
      if (fstat(ls[j].fd, &b) == 0 && a.st_dev == b.st_dev &&
          a.st_ino == b.st_ino &&
          (ls[i].mode == MODE_RW || ls[j].mode == MODE_RW)) {
        ua_error("%s is given for LUN %d and LUN %d; a writable LUN cannot "
                 "share its file",
                 ls[i].name, ls[j].lun, ls[i].lun);
        return -1;
      }
    for (int lun = 0; lun < 8; lun++) {
      char ldir[PATH_MAX], p[PATH_MAX + 8], file[PATH_MAX] = "", ro[4] = "";
      int ours = 0;
      for (int j = 0; j < n; j++)
        ours |= ls[j].lun == lun;
      if (ours || lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0)
        continue;
      snprintf(p, sizeof(p), "%s/file", ldir);
      if (read_file(p, file, sizeof(file)) <= 0 || is_loop(file) ||
          stat(file, &b) < 0 || a.st_dev != b.st_dev || a.st_ino != b.st_ino)
        continue;
      snprintf(p, sizeof(p), "%s/ro", ldir);
      read_file(p, ro, sizeof(ro));
      if (ls[i].mode == MODE_RW || ro[0] == '0') {
        ua_error("%s is already on LUN %d; a writable LUN cannot share its "
                 "file",
                 ls[i].name, lun);
        return -1;
      }
    }
  }
  return 0;
}

/* Whether the LUN already serves this very file in this mode: then there is
 * nothing to write, and the host keeps its medium. Ejecting and reloading
 * the same disc would be a medium change, and a locked drive a replug. */
static int serving(const char *ldir, const struct load *l) {
  char p[PATH_MAX + 16], file[PATH_MAX] = "", backing[PATH_MAX] = "", ro[4];
  snprintf(p, sizeof(p), "%s/file", ldir);
  if (read_file(p, file, sizeof(file)) <= 0)
    return 0;
  if (is_loop(file)) {
    snprintf(p, sizeof(p), "/sys/block/%s/loop/backing_file",
             strrchr(file, '/') + 1);
    read_file(p, backing, sizeof(backing));
  }
  snprintf(p, sizeof(p), "%s/ro", ldir);
  return strcmp(backing[0] ? backing : file, l->name) == 0 &&
         read_file(p, ro, sizeof(ro)) > 0 &&
         (ro[0] == '0') == (l->mode == MODE_RW);
}

/* Every image is prepared first, so a bad one fails before the host sees
 * anything; then all of them load in one begin/commit, one replug however
 * many LUNs. Other LUNs already on the bus stay as they are. */
static int do_start(struct load *ls, int n) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_STORAGE) < 0)
    return UA_EXIT_FAIL;
  for (int i = 0; i < n; i++)
    if (prepare(&ls[i], g.android_gadget) < 0)
      return UA_EXIT_FAIL;

  char fdir[PATH_MAX], ldir[UA_LUNS][PATH_MAX];
  if (ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir)) < 0)
    return UA_EXIT_FAIL;
  for (int i = 0; i < n; i++)
    if (lun_dir(fdir, ls[i].lun, ldir[i], sizeof(ldir[i])) < 0)
      return UA_EXIT_FAIL;
  if (shared_rw(ls, n, fdir) < 0)
    return UA_EXIT_FAIL;

  /* Images for LUNs already on the bus, each in the same mode as its LUN: a
   * medium change is all the host needs to see, like swapping a disc in a
   * real drive. Disk vs CD-ROM is a different SCSI device type, which a host
   * only reads when it enumerates, and a host lock blocks the eject; both
   * take the rebind below, whose disconnect also clears the lock. dvd and
   * disk differ in sector size, which the host also only reads when it sees
   * a new medium, so a loaded one is swapped the same way. */
  int inplace = ua_gadget_live(&g) && ua_gadget_has(&g, FUNC);
  for (int i = 0; inplace && i < n; i++) {
    char cdrom[PATH_MAX + 8], cur[PATH_MAX + 8], v[8] = "";
    snprintf(cdrom, sizeof(cdrom), "%s/cdrom", ldir[i]);
    snprintf(cur, sizeof(cur), "%s/file", ldir[i]);
    inplace = read_file(cdrom, v, sizeof(v)) > 0 &&
              (v[0] == '1') == (ls[i].mode == MODE_CDROM) &&
              (read_file(cur, cur, sizeof(cur)) <= 0 ||
               is_loop(cur) == (ls[i].mode == MODE_DVD));
  }
  if (inplace) {
    /* The lock, so a command run meanwhile queues up behind the swap */
    int r = 0;
    ua_crit_enter();
    for (int i = 0; i < n && r == 0; i++) {
      if (serving(ldir[i], &ls[i])) {
        ua_log("LUN %d already holds %s (%s)", ls[i].lun, ls[i].name,
               mode_names[ls[i].mode]);
        continue;
      }
      r = lun_load(ldir[i], ls[i].path, ls[i].mode, 0);
      if (r == 0)
        ua_log("LUN %d now holds %s (%s)", ls[i].lun, ls[i].name,
               mode_names[ls[i].mode]);
    }
    ua_crit_leave();
    if (r == 0)
      return UA_EXIT_OK;
    if (r != -EBUSY)
      return UA_EXIT_FAIL;
    ua_debug("host holds the medium lock, rebinding to release it");
  }

  int existed = access(fdir, F_OK) == 0, made[UA_LUNS] = {0}, unlinked = 0,
      reached = 0, failed = -1;
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  if (ua_gadget_add(&g, FUNC, fdir, sizeof(fdir)) < 0)
    goto fail;
  for (int i = 0; i < n; i++) {
    /* lun.0 comes with the function; the rest are made on demand, and only
     * while the function is out of every config (EBUSY otherwise) */
    if (ls[i].lun > 0 && access(ldir[i], F_OK) != 0) {
      if (!unlinked++)
        ua_gadget_unlink(&g, FUNC);
      if (mkdir(ldir[i], 0755) < 0) {
        ua_error("Cannot create LUN %d: %s", ls[i].lun, strerror(errno));
        goto fail;
      }
      made[i] = 1;
    }
    reached = i + 1;
    int r = lun_load(ldir[i], ls[i].path, ls[i].mode, 1);
    if (r < 0) {
      if (r == -EBUSY)
        ua_error("The host still holds the drive locked (EBUSY)");
      failed = i;
      goto fail;
    }
  }
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  for (int i = 0; i < n; i++)
    ua_log("Serving %s as a %s on LUN %d", ls[i].name,
           ls[i].mode == MODE_CDROM ? "CD-ROM"
           : ls[i].mode == MODE_DVD ? "read-only disk with 2048-byte sectors"
           : ls[i].mode == MODE_RW  ? "writable disk"
                                    : "read-only disk",
           ls[i].lun);
  return UA_EXIT_OK;

fail:
  /* Leave nothing of this call behind: LUNs it made go, LUNs it loaded
   * that were already there come back empty, their old medium having gone
   * out first. The one that failed kept or got back its old medium
   * (lun_load). Other LUNs and functions stay live. */
  if (!existed)
    ua_gadget_remove(&g, FUNC);
  else
    for (int i = reached - 1; i >= 0; i--)
      if (made[i])
        rmdir(ldir[i]);
      else if (i != failed)
        lun_eject(ldir[i]);
  ua_gadget_commit(&g);
  return UA_EXIT_FAIL;
}

/* Eject every LUN in mask. Those a host has locked all go out in one
 * rebind, not one each. */
static int do_eject(unsigned mask) {
  struct ua_gadget g;
  char fdir[PATH_MAX], ldir[PATH_MAX];
  unsigned locked = 0;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, FUNC)) {
    ua_error("Mass storage is not running");
    return UA_EXIT_FAIL;
  }
  ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir));
  for (int lun = 0; lun < UA_LUNS; lun++) {
    if (!(mask & (1u << lun)))
      continue;
    if (lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0) {
      ua_error("Eject failed: %s", strerror(errno));
      return UA_EXIT_FAIL;
    }
    if (lun_eject(ldir) == 0) {
      ua_log("LUN %d ejected", lun);
      ua_running_clear_lun(lun);
      continue;
    }
    /* Before 6.6 there is no forced_eject, and a host's medium lock makes
     * the eject EBUSY. The disconnect of a rebind releases it, as start does
     * for a swap; the eject is retried while the kernel processes it. */
    if (errno != EBUSY || !ua_gadget_live(&g)) {
      ua_error("Eject failed: %s", strerror(errno));
      return UA_EXIT_FAIL;
    }
    locked |= 1u << lun;
  }
  if (!locked)
    return UA_EXIT_OK;
  ua_debug("host holds the medium lock, rebinding to release it");
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  int ret = UA_EXIT_OK;
  for (int lun = 0; lun < UA_LUNS; lun++) {
    if (!(locked & (1u << lun)) || lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0)
      continue;
    if (lun_eject_wait(ldir) < 0) {
      ua_error("Eject of LUN %d failed: %s%s", lun, strerror(errno),
               errno == EBUSY ? " (the host still holds the drive locked)"
                              : "");
      ret = UA_EXIT_FAIL;
      continue;
    }
    ua_log("LUN %d ejected (the host had it locked, so it was replugged)", lun);
    ua_running_clear_lun(lun);
  }
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  return ret;
}

static int do_stop(void) {
  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, FUNC)) {
    ua_log("Mass storage is not running");
    return UA_EXIT_OK;
  }
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  ua_gadget_remove(&g, FUNC);
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_log("Mass storage removed");
  ua_running_clear(UA_FN_STORAGE);
  return UA_EXIT_OK;
}

/* A LUN of the setup, opened and named as an IMAGE operand would be */
static int load_from_spec(struct load *l, int lun, const char *spec) {
  char mode[8], path[PATH_MAX];
  int m;
  if (sscanf(spec, "%7[^:]:%4095[^\n]", mode, path) != 2 ||
      (m = mode_of(mode)) < 0) {
    ua_error("Bad setup for LUN %d: '%s'", lun, spec);
    return -1;
  }
  l->lun = lun;
  l->mode = (enum mode)m;
  l->loop_fd = -1;
  if ((l->fd = open_image(path, l->mode)) < 0) {
    ua_error("%s: %s", path, strerror(errno));
    return -1;
  }
  if (!realpath(path, l->name))
    safe_strncpy(l->name, path, sizeof(l->name));
  return 0;
}

static void close_loads(struct load *ls, int n) {
  /* The kernel holds its own references by now; a loop is autoclear, so it
   * lives as long as its LUN */
  for (int i = 0; i < n; i++) {
    close(ls[i].fd);
    if (ls[i].loop_fd >= 0)
      close(ls[i].loop_fd);
  }
}

/* Start what was opened, and record the LUNs that now run */
static int start_loads(struct load *ls, int n) {
  int r = do_start(ls, n);
  close_loads(ls, n);
  if (r == UA_EXIT_OK) {
    unsigned mask = 0;
    for (int i = 0; i < n; i++)
      mask |= 1u << ls[i].lun;
    ua_running_take(UA_FN_STORAGE, mask);
  }
  return r;
}

/* Bring the drives to the setup, deciding against the kernel: a LUN the
 * setup dropped is ejected in place, one whose setup changed since it started
 * (or that the host emptied) is loaded, the rest are left alone. With nothing
 * set up, storage stops. */
static int do_apply(void) {
  struct ua_setup s, r;
  struct ua_gadget g;
  struct load ls[UA_LUNS];
  char fdir[PATH_MAX];
  int any = 0, n = 0;
  unsigned eject = 0;
  ua_setup_load(&s);
  ua_running_load(&r);
  for (int lun = 0; lun < UA_LUNS; lun++)
    any |= s.lun[lun][0] != '\0';
  if (ua_gadget_open(&g) < 0)
    return UA_EXIT_FAIL;
  int linked = ua_gadget_has(&g, FUNC) && ua_gadget_live(&g);
  if (!any) {
    if (linked)
      return do_stop();
    ua_log("Nothing set up for storage");
    return UA_EXIT_OK;
  }
  if (linked)
    ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir));
  for (int lun = 0; lun < UA_LUNS; lun++) {
    char ldir[PATH_MAX], p[PATH_MAX + 8], file[PATH_MAX] = "";
    int loaded = linked && lun_dir(fdir, lun, ldir, sizeof(ldir)) == 0 &&
                 (snprintf(p, sizeof(p), "%s/file", ldir),
                  read_file(p, file, sizeof(file)) > 0);
    if (!s.lun[lun][0]) {
      if (loaded)
        eject |= 1u << lun;
      else if (r.lun[lun][0])
        ua_running_clear_lun(lun);
      continue;
    }
    if (loaded && !strcmp(s.lun[lun], r.lun[lun]))
      continue;
    if (n == UA_LUNS)
      break;
    if (load_from_spec(&ls[n], lun, s.lun[lun]) < 0) {
      close_loads(ls, n);
      return UA_EXIT_FAIL;
    }
    n++;
  }
  /* Every image opened first, so a missing one fails before any eject */
  if (eject && do_eject(eject) != UA_EXIT_OK) {
    close_loads(ls, n);
    return UA_EXIT_FAIL;
  }
  if (!n) {
    ua_log(eject ? "Storage now runs its setup"
                 : "Storage already runs its setup");
    return UA_EXIT_OK;
  }
  return start_loads(ls, n);
}

/* JSON: the elements of an array, one object per LUN; an ejected LUN has an
 * empty file, mode and access */
void ua_status_storage(const struct ua_gadget *g, int json) {
  char fdir[PATH_MAX];
  int sep = 0;
  if (!ua_gadget_has(g, FUNC) ||
      ua_gadget_func_dir(g, FUNC, fdir, sizeof(fdir)) < 0)
    return;
  for (int lun = 0; lun < 16; lun++) {
    char ldir[PATH_MAX], p[PATH_MAX + 8], file[PATH_MAX] = "", v[8] = "";
    if (lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0 || access(ldir, F_OK) != 0)
      continue;
    snprintf(p, sizeof(p), "%s/file", ldir);
    read_file(p, file, sizeof(file));
    snprintf(p, sizeof(p), "%s/cdrom", ldir);
    int cdrom = read_file(p, v, sizeof(v)) > 0 && v[0] == '1';
    snprintf(p, sizeof(p), "%s/ro", ldir);
    int ro = read_file(p, v, sizeof(v)) > 0 && v[0] == '1';
    /* Name the image behind a dvd LUN's loop device */
    char backing[PATH_MAX] = "";
    if (is_loop(file)) {
      snprintf(p, sizeof(p), "/sys/block/%s/loop/backing_file",
               strrchr(file, '/') + 1);
      read_file(p, backing, sizeof(backing));
    }
    const char *mode = !file[0]     ? ""
                       : cdrom      ? "cdrom"
                       : backing[0] ? "dvd"
                       : ro         ? "disk"
                                    : "rw";
    if (json) {
      int first = 1;
      printf("%s{", sep++ ? "," : "");
      ua_json_int("lun", lun, &first);
      ua_json_str("file", backing[0] ? backing : file, &first);
      ua_json_str("mode", mode, &first);
      ua_json_str("access",
                  !file[0]             ? ""
                  : strcmp(mode, "rw") ? "ro"
                                       : "rw",
                  &first);
      printf("}");
      continue;
    }
    printf("Storage:    LUN %d: %s%s\n", lun,
           backing[0] ? backing
           : file[0]  ? file
                      : "(empty)",
           !file[0]     ? ""
           : cdrom      ? " [cdrom]"
           : backing[0] ? " [dvd]"
           : ro         ? " [disk, read-only]"
                        : " [disk, writable]");
  }
}

/* One IMAGE of a start: its argv index */
struct simg {
  int lun, image, mode_set;
  enum mode mode;
};

struct sargs {
  int lun, nimg; /* lun: the last --lun, for eject */
  const char *action;
  struct simg img[UA_LUNS];
};

/* An IMAGE takes the --lun and --mode given since the one before it; with
 * no --lun, the LUN after that one's */
static int add_image(struct sargs *a, int image, int *lun, int *mode) {
  if (a->nimg == UA_LUNS) {
    ua_usage_error("storage", "start takes at most %d IMAGEs", UA_LUNS);
    return -1;
  }
  struct simg *m = &a->img[a->nimg];
  m->lun = *lun >= 0 ? *lun : a->nimg ? a->img[a->nimg - 1].lun + 1 : 0;
  m->mode_set = *mode >= 0;
  m->mode = m->mode_set ? (enum mode) * mode : MODE_AUTO;
  m->image = image;
  for (int i = 0; i < a->nimg; i++)
    if (a->img[i].lun == m->lun) {
      ua_usage_error("storage", "LUN %d is given twice", m->lun);
      return -1;
    }
  if (m->lun > 7) {
    /* only reachable without --lun: it was checked when it was read */
    ua_usage_error("storage",
                   "An IMAGE would take LUN 8, past the last; give it a "
                   "--lun");
    return -1;
  }
  a->nimg++;
  *lun = *mode = -1;
  return 0;
}

/* A word that is not an option: the action first, then IMAGEs, which only
 * start takes */
static int operand(struct sargs *a, int i, char **argv, int *lun, int *mode) {
  if (!a->action[0]) {
    a->action = argv[i];
    return 0;
  }
  if (strcmp(a->action, "start")) {
    ua_usage_error("storage", "Too many arguments");
    return -1;
  }
  return add_image(a, i, lun, mode);
}

/* Returns -1 to go on, else an exit code. Options are read in order ("-" in
 * the option string), so each one applies to the IMAGE after it. */
static int parse(int argc, char **argv, struct sargs *a) {
  static const struct option opts[] = {
      {"mode", required_argument, NULL, 'm'},
      {"lun", required_argument, NULL, 'l'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  int c, lun = -1, mode = -1;
  char *end;
  memset(a, 0, sizeof(*a));
  a->action = "";
  optind = 0;
  while ((c = getopt_long(argc, argv, "-:m:l:h", opts, NULL)) != -1) {
    switch (c) {
    case 1: /* not an option: the action, then IMAGEs */
      if (operand(a, optind - 1, argv, &lun, &mode) < 0)
        return UA_EXIT_USAGE;
      break;
    case 'm':
      if ((mode = mode_of(optarg)) < 0) {
        ua_usage_error("storage", "Unknown mode '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      break;
    case 'l': {
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 0 || v > 7) {
        ua_usage_error("storage", "--lun takes a number from 0 to 7");
        return UA_EXIT_USAGE;
      }
      lun = a->lun = (int)v;
      break;
    }
    case 'h':
      ua_help_storage();
      return UA_EXIT_OK;
    case ':':
      ua_usage_error("storage", "Option '%s' needs a value", argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      ua_usage_error("storage", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  /* getopt stops at "--"; what follows it is operands, so an IMAGE whose
   * name starts with '-' can still be given */
  for (; optind < argc; optind++)
    if (operand(a, optind, argv, &lun, &mode) < 0)
      return UA_EXIT_USAGE;
  if (!a->action[0]) {
    ua_usage_error("storage", "Missing action");
    return UA_EXIT_USAGE;
  }
  if (strcmp(a->action, "start"))
    return -1;
  /* Options after the last IMAGE: with one IMAGE they are its own, as they
   * always were; with several there is no telling which they meant */
  if (a->nimg && (lun >= 0 || mode >= 0)) {
    if (a->nimg > 1) {
      ua_usage_error("storage",
                     "--lun and --mode go before the IMAGE they are for");
      return UA_EXIT_USAGE;
    }
    if (lun >= 0)
      a->img[0].lun = lun;
    if (mode >= 0)
      a->img[0].mode = (enum mode)mode, a->img[0].mode_set = 1;
  }
  return -1;
}

static int open_image(const char *image, enum mode mode) {
  return open(image, (mode == MODE_RW ? O_RDWR : O_RDONLY) | O_CLOEXEC);
}

int ua_cmd_storage(int argc, char **argv) {
  struct sargs a;
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_storage();
    return UA_EXIT_OK;
  }
  int r = parse(argc, argv, &a);
  if (r != -1)
    return r;
  struct ua_config cfg;
  ua_config_active(&cfg);
  safe_strncpy(inquiry, cfg.storage_inquiry, sizeof(inquiry));
  if (!strcmp(a.action, "start")) {
    struct load ls[UA_LUNS];
    struct ua_setup s;
    int n = 0;
    ua_setup_load(&s);
    if (!a.nimg) {
      /* A bare start runs the setup */
      for (int lun = 0; lun < UA_LUNS; lun++) {
        if (!s.lun[lun][0])
          continue;
        if (load_from_spec(&ls[n], lun, s.lun[lun]) < 0) {
          close_loads(ls, n);
          return UA_EXIT_FAIL;
        }
        n++;
      }
      if (!n) {
        ua_error("Nothing set up for storage");
        ua_log("Give an IMAGE, or: " UA_NAME
               " setup set storage_lun0=auto:/path/to/image.iso");
        return UA_EXIT_FAIL;
      }
      return start_loads(ls, n);
    }
    for (; n < a.nimg; n++) {
      struct simg *m = &a.img[n];
      struct load *l = &ls[n];
      const char *image = argv[m->image];
      l->lun = m->lun;
      l->loop_fd = -1;
      l->mode = m->mode;
      if (!m->mode_set && mode_of(cfg.storage_mode) >= 0)
        l->mode = (enum mode)mode_of(cfg.storage_mode);
      if ((l->fd = open_image(image, l->mode)) < 0) {
        ua_error("%s: %s", image, strerror(errno));
        close_loads(ls, n);
        return UA_EXIT_FAIL;
      }
      if (!realpath(image, l->name))
        safe_strncpy(l->name, image, sizeof(l->name));
    }
    /* The IMAGEs given become the setup, as Droidspaces saves a start's
     * flags, so a later bare start needs nothing. A name that means nothing
     * where a later start runs (a container's private path, started again
     * from Android) fails that start, naming it. */
    for (int i = 0; i < n; i++)
      snprintf(s.lun[ls[i].lun], UA_LUN_SPEC, "%s:%s", mode_names[ls[i].mode],
               ls[i].name);
    if (ua_setup_save(&s) < 0) {
      close_loads(ls, n);
      return UA_EXIT_FAIL;
    }
    return start_loads(ls, n);
  }
  if (!strcmp(a.action, "apply"))
    return do_apply();
  if (!strcmp(a.action, "eject")) {
    r = do_eject(1u << a.lun);
    if (r == UA_EXIT_OK) {
      /* An eject from the command line is an edit of the setup too */
      struct ua_setup s;
      ua_setup_load(&s);
      s.lun[a.lun][0] = '\0';
      ua_setup_save(&s);
    }
    return r;
  }
  if (!strcmp(a.action, "stop"))
    return do_stop();
  ua_usage_error("storage", "Unknown action '%s'", a.action);
  return UA_EXIT_USAGE;
}
