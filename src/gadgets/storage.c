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

#include "usbarsenal.h"
#include <linux/loop.h>
#include <sys/xattr.h>

#define FUNC UA_F_STORAGE

/* The profile's SCSI inquiry string, empty for the kernel's own */
static char inquiry[32];

/* storage_common.c clamps a CD-ROM to 256*60*75-1 frames of 2048 bytes on
 * every kernel we support, and only logs it. Past this the host reads a
 * silently truncated disc. */
#define CDROM_MAX_BYTES ((long long)(256 * 60 * 75 - 1) * 2048)
#define CDROM_MIN_BYTES (300LL * 2048)

enum mode { MODE_AUTO, MODE_CDROM, MODE_DVD, MODE_DISK, MODE_RW };
static const char *const mode_names[] = {"auto", "cdrom", "dvd", "disk", "rw"};
#define NMODES (int)(sizeof(mode_names) / sizeof(mode_names[0]))

static int mode_of(const char *name) {
  for (int i = 0; i < NMODES; i++)
    if (!strcmp(name, mode_names[i]))
      return i;
  return -1;
}

#ifndef LOOP_SET_BLOCK_SIZE
#define LOOP_SET_BLOCK_SIZE 0x4C09
#endif

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

/* An MBR or GPT means the image boots as a disk: isohybrid ISOs carry one
 * for exactly that reason. A plain ISO9660 image starts with 32 KB of zeros,
 * so it gets CD-ROM mode, the only way it can boot. */
static enum mode detect_mode(int fd) {
  unsigned char buf[1024];
  if (pread(fd, buf, sizeof(buf), 0) != (ssize_t)sizeof(buf))
    return MODE_CDROM;
  if (buf[510] == 0x55 && buf[511] == 0xAA)
    return MODE_DISK;
  if (!memcmp(buf + 512, "EFI PART", 8))
    return MODE_DISK;
  return MODE_CDROM;
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
 * the medium goes out first and the new one goes in last. */
/* Returns -EBUSY, quietly, when the host holds a medium lock: the caller
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

/* f_mass_storage reads the image from a kernel thread, so SELinux checks the
 * kernel's domain against the image's label, not ours. Android's policy lets
 * it at user storage (/sdcard, /data) but not at tmpfs or an unlabeled
 * filesystem such as a container's rootfs: the host then gets "unrecovered
 * read error" on every sector, and the only log is a dontaudit nobody sees.
 * So ask the policy itself, on any distro, instead of trusting a label list. */
#define UA_STORAGE_LABEL "u:object_r:media_rw_data_file:s0"

/* The kernel's own context, from kthreadd (kernel_t on Fedora, kernel on
 * Android). In a PID namespace /proc/2 is someone else; Android's name is
 * then the only safe guess, and elsewhere we do not ask. */
static int kernel_context(char *out, size_t size, int android) {
  char comm[32] = "";
  if (read_file("/proc/2/comm", comm, sizeof(comm)) > 0 &&
      !strncmp(comm, "kthreadd", 8) &&
      read_file("/proc/2/attr/current", out, size) > 0) {
    out[strcspn(out, "\n")] = '\0';
    return 0;
  }
  if (!android)
    return -1;
  safe_strncpy(out, "u:r:kernel:s0", size);
  return 0;
}

static int perm_bit(const char *cls, const char *perm) {
  char p[96], v[16];
  snprintf(p, sizeof(p), "/sys/fs/selinux/class/%s/perms/%s", cls, perm);
  return read_file(p, v, sizeof(v)) > 0 ? atoi(v) : 0;
}

/* May the kernel read (and with rw, write) what fd is, as class cls ("file",
 * "blk_file")? 1 when it may or when there is nothing to ask. label gets the
 * object's label for the caller's message. */
static int kernel_may(int fd, const char *cls, int rw, int android, char *label,
                      size_t lsize) {
  char kctx[256], q[600], ans[96], v[16], p[96];
  label[0] = '\0';
  /* The policy answers the same in permissive mode, so a denial only counts
   * when it is enforced: not with SELinux permissive, nor in a permissive
   * domain (the reply's flags). Labels can outlive SELinux (a kernel booted
   * with it off still reads them from disk), so ask whether it is running,
   * not whether a file has one. */
  if (read_file("/sys/fs/selinux/enforce", v, sizeof(v)) <= 0 || v[0] == '0')
    return 1;
  ssize_t n = fgetxattr(fd, "security.selinux", label, lsize - 1);
  if (n <= 0)
    return 1;
  label[n] = '\0';
  snprintf(p, sizeof(p), "/sys/fs/selinux/class/%s/index", cls);
  int idx = read_file(p, v, sizeof(v)) > 0 ? atoi(v) : 0;
  int rd = perm_bit(cls, "read"), wr = perm_bit(cls, "write");
  if (idx <= 0 || rd <= 0 || kernel_context(kctx, sizeof(kctx), android) < 0)
    return 1;
  int afd = open("/sys/fs/selinux/access", O_RDWR | O_CLOEXEC);
  if (afd < 0)
    return 1;
  int len = snprintf(q, sizeof(q), "%s %s %d", kctx, label, idx), ok = 1;
  if (write(afd, q, len) == len && (n = read(afd, ans, sizeof(ans) - 1)) > 0) {
    unsigned long allowed = 0, flags = 0;
    ans[n] = '\0';
    /* allowed decided auditallow auditdeny seqno flags */
    sscanf(ans, "%lx %*x %*x %*x %*u %lx", &allowed, &flags);
    ok = (allowed >> (rd - 1)) & 1;
    if (rw && wr > 0)
      ok = ok && ((allowed >> (wr - 1)) & 1);
    if (flags & 1) /* AVD_FLAGS_PERMISSIVE */
      ok = 1;
  }
  close(afd);
  return ok;
}

static void selinux_fix(int fd, const char *path, int rw, int android) {
  char label[256];
  if (kernel_may(fd, "file", rw, android, label, sizeof(label)))
    return;
  /* Android has one label every policy lets the kernel at; other distros do
   * not, so there it is the user's call */
  if (android && fsetxattr(fd, "security.selinux", UA_STORAGE_LABEL,
                           sizeof(UA_STORAGE_LABEL), 0) == 0)
    ua_log("Relabelled %s (%s -> %s) so the kernel may read it", path, label,
           UA_STORAGE_LABEL);
  else if (android)
    ua_warn("%s is labelled %s, which Android's SELinux policy does not let "
            "the kernel read; the host will see read errors (%s). Move it to "
            "/sdcard or /data.",
            path, label, strerror(errno));
  else
    ua_warn("SELinux does not let the kernel read %s (labelled %s); the host "
            "will see read errors. Relabel it (chcon) or move it.",
            path, label);
}

/* A loop device node: ueventd (or udev) makes it a moment after the kernel
 * creates the device, so wait for it before making one ourselves. The minor
 * comes from sysfs, since loop.max_part spaces them out. */
static int loop_open(int n, char *dev, size_t size) {
  for (int tries = 0; tries < 20; tries++) {
    snprintf(dev, size, "/dev/block/loop%d", n);
    if (access(dev, F_OK) != 0)
      snprintf(dev, size, "/dev/loop%d", n);
    int fd = open(dev, O_RDONLY | O_CLOEXEC);
    if (fd >= 0 || errno != ENOENT)
      return fd;
    usleep(50000);
  }
  char p[64], v[32];
  unsigned maj, min;
  snprintf(p, sizeof(p), "/sys/block/loop%d/dev", n);
  if (read_file(p, v, sizeof(v)) <= 0 || sscanf(v, "%u:%u", &maj, &min) != 2)
    return -1;
  snprintf(
      dev, size,
      access("/dev/block", F_OK) == 0 ? "/dev/block/loop%d" : "/dev/loop%d", n);
  mknod(dev, S_IFBLK | 0600, makedev(maj, min));
  return open(dev, O_RDONLY | O_CLOEXEC);
}

/* dvd mode: the image behind a read-only loop device with 2048-byte blocks.
 * f_mass_storage then reports 2048-byte sectors, the geometry a plain ISO's
 * UDF is laid out for, and disk mode has no CD-ROM size clamp. Autoclear
 * detaches the loop when its last user lets go: us right after the kernel
 * opens it, then the kernel on eject, swap or stop. */
static int loop_attach(int fd, const char *name, char *dev, size_t size) {
  int ctl = open("/dev/loop-control", O_RDWR | O_CLOEXEC);
  if (ctl < 0) {
    ua_error("dvd mode needs loop devices: /dev/loop-control: %s",
             strerror(errno));
    return -1;
  }
  for (int tries = 0; tries < 3; tries++) {
    int n = ioctl(ctl, LOOP_CTL_GET_FREE);
    int lfd = n < 0 ? -1 : loop_open(n, dev, size);
    if (lfd < 0)
      break;
    if (ioctl(lfd, LOOP_SET_FD, fd) < 0) {
      close(lfd);
      if (errno == EBUSY)
        continue; /* someone took it between GET_FREE and here */
      break;
    }
    struct loop_info64 li;
    memset(&li, 0, sizeof(li));
    li.lo_flags = LO_FLAGS_AUTOCLEAR;
    safe_strncpy((char *)li.lo_file_name, name, sizeof(li.lo_file_name));
    if (ioctl(lfd, LOOP_SET_STATUS64, &li) < 0 ||
        ioctl(lfd, LOOP_SET_BLOCK_SIZE, 2048UL) < 0) {
      ua_error("Cannot give %s 2048-byte blocks: %s%s", dev, strerror(errno),
               errno == EINVAL ? " (needs kernel 4.14 or newer)" : "");
      ioctl(lfd, LOOP_CLR_FD, 0);
      close(lfd);
      close(ctl);
      return -1;
    }
    close(ctl);
    return lfd;
  }
  ua_error("Cannot set up a loop device for %s: %s", name, strerror(errno));
  close(ctl);
  return -1;
}

/* One LUN of a start: its image, open, and what the kernel will be given.
 * loop_fd is the loop device behind a dvd LUN, closed once the kernel holds
 * it. */
struct load {
  int fd, loop_fd, lun;
  enum mode mode;
  char name[PATH_MAX], path[64];
};

/* Everything about one image before the gadget changes: its mode, the size
 * checks, SELinux, the loop device behind dvd. The image arrives open, either
 * opened here or by a client in another mount namespace and passed over the
 * daemon socket. The kernel gets /proc/self/fd/N: it follows the magic link
 * to the real file wherever that lives, so no path ever has to mean the same
 * thing on both sides. name is only for messages. */
static int prepare(struct load *l, int android_gadget) {
  snprintf(l->path, sizeof(l->path), "/proc/self/fd/%d", l->fd);
  /* lseek works for block devices too, no BLKGETSIZE64 needed */
  long long size = (long long)lseek(l->fd, 0, SEEK_END);
  if (l->mode == MODE_AUTO) {
    l->mode = detect_mode(l->fd);
    if (l->mode == MODE_CDROM && size > CDROM_MAX_BYTES)
      l->mode = MODE_DVD;
  }

  if (l->mode == MODE_CDROM && size >= 0 && size < CDROM_MIN_BYTES) {
    ua_error("%s is too small for a CD-ROM (minimum 600 KB)", l->name);
    return -1;
  }
  if (l->mode == MODE_CDROM && size > CDROM_MAX_BYTES)
    ua_warn("%s is %.2f GB, but the kernel's CD-ROM emulation stops at 2.36 "
            "GB unless it carries the DVD patch (kernel-patches/). "
            "Otherwise the host reads a truncated disc; --mode dvd has no "
            "limit.",
            l->name, (double)size / 1e9);

  struct stat st;
  int have_st = fstat(l->fd, &st) == 0;
  if (have_st && S_ISREG(st.st_mode))
    selinux_fix(l->fd, l->name, l->mode == MODE_RW, android_gadget);

  /* A block device served as is: the kernel thread reads it under the node's
   * label, and no label is readable on every policy, so it cannot be fixed by
   * relabelling the way a file can. Say why before the host sees a dead disk.
   * Whether to serve it at all, mounted or not, is the user's call. */
  char blabel[256];
  if (have_st && S_ISBLK(st.st_mode) &&
      !kernel_may(l->fd, "blk_file", l->mode == MODE_RW, android_gadget, blabel,
                  sizeof(blabel))) {
    ua_error("SELinux does not let the kernel %s %s (%s), so the host would "
             "see only read errors",
             l->mode == MODE_RW ? "read and write" : "read", l->name, blabel);
    ua_log("To serve it anyway, set SELinux permissive by hand first: "
           "setenforce 0");
    return -1;
  }

  if (l->mode == MODE_DVD) {
    char dev[32];
    if ((l->loop_fd = loop_attach(l->fd, l->name, dev, sizeof(dev))) < 0)
      return -1;
    /* Fail closed: an unreadable LUN looks like a dead disk on the host */
    char label[256];
    if (!kernel_may(l->loop_fd, "blk_file", 0, android_gadget, label,
                    sizeof(label))) {
      ua_error("SELinux does not let the kernel read loop devices (%s), so "
               "the host would see only read errors",
               label);
      if (android_gadget)
        ua_log("The USB Arsenal module loads the rule at boot; without it, "
               "run: ksud sepolicy patch \"allow kernel loop_device blk_file "
               "{ open read getattr ioctl }\" (Magisk: magiskpolicy --live)");
      return -1;
    }
    ua_debug("%s is %s with 2048-byte blocks", l->name, dev);
    snprintf(l->path, sizeof(l->path), "/proc/self/fd/%d", l->loop_fd);
  }
  return 0;
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

  char fdir[PATH_MAX], ldir[UA_MAX_FDS][PATH_MAX];
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

  int existed = access(fdir, F_OK) == 0, made[UA_MAX_FDS] = {0}, unlinked = 0,
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
  struct load ls[UA_MAX_FDS];
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
    if (n == UA_MAX_FDS)
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

/* One IMAGE of a start: argv index, or fd when the client relay opened it */
struct simg {
  int lun, fd, image, mode_set;
  enum mode mode;
};

struct sargs {
  int lun, nimg; /* lun: the last --lun, for eject */
  const char *action;
  struct simg img[UA_MAX_FDS];
};

/* An IMAGE takes the --lun and --mode given since the one before it; with
 * no --lun, the LUN after that one's */
static int add_image(struct sargs *a, int fd, int image, int *lun, int *mode,
                     int report) {
  if (a->nimg == UA_MAX_FDS) {
    if (report)
      ua_usage_error("storage", "start takes at most %d IMAGEs", UA_MAX_FDS);
    return -1;
  }
  struct simg *m = &a->img[a->nimg];
  m->lun = *lun >= 0 ? *lun : a->nimg ? a->img[a->nimg - 1].lun + 1 : 0;
  m->mode_set = *mode >= 0;
  m->mode = m->mode_set ? (enum mode) * mode : MODE_AUTO;
  m->fd = fd;
  m->image = image;
  for (int i = 0; i < a->nimg; i++)
    if (a->img[i].lun == m->lun) {
      if (report)
        ua_usage_error("storage", "LUN %d is given twice", m->lun);
      return -1;
    }
  if (m->lun > 7) {
    /* only reachable without --lun: it was checked when it was read */
    if (report)
      ua_usage_error("storage",
                     "%s would take LUN 8, past the last; give it a --lun",
                     image ? "an IMAGE" : "a descriptor");
    return -1;
  }
  a->nimg++;
  *lun = *mode = -1;
  return 0;
}

/* A word that is not an option: the action first, then IMAGEs, which only
 * start takes */
static int operand(struct sargs *a, int i, char **argv, int *lun, int *mode,
                   int report) {
  if (!a->action[0]) {
    a->action = argv[i];
    return 0;
  }
  if (strcmp(a->action, "start")) {
    if (report)
      ua_usage_error("storage", "Too many arguments");
    return -1;
  }
  return add_image(a, -1, i, lun, mode, report);
}

/* Shared by the command and by a client about to relay it: the client has
 * to find each IMAGE to open it on its side. report=0 parses silently and
 * leaves the complaining to the daemon. Returns -1 to go on, else an exit
 * code. Options are read in order ("-" in the option string), so each one
 * applies to the IMAGE after it. */
static int parse(int argc, char **argv, struct sargs *a, int report) {
  static const struct option opts[] = {
      {"mode", required_argument, NULL, 'm'},
      {"lun", required_argument, NULL, 'l'},
      {"fd", required_argument, NULL, 'F'}, /* set by the client relay */
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
      if (operand(a, optind - 1, argv, &lun, &mode, report) < 0)
        return UA_EXIT_USAGE;
      break;
    case 'm':
      if ((mode = mode_of(optarg)) < 0) {
        if (report)
          ua_usage_error("storage", "Unknown mode '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      break;
    case 'l': {
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 0 || v > 7) {
        if (report)
          ua_usage_error("storage", "--lun takes a number from 0 to 7");
        return UA_EXIT_USAGE;
      }
      lun = a->lun = (int)v;
      break;
    }
    case 'F': {
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 3) {
        if (report)
          ua_usage_error("storage", "--fd takes an open descriptor number");
        return UA_EXIT_USAGE;
      }
      if (add_image(a, (int)v, 0, &lun, &mode, report) < 0)
        return UA_EXIT_USAGE;
      break;
    }
    case 'h':
      if (report)
        ua_help_storage();
      return UA_EXIT_OK;
    case ':':
      if (report)
        ua_usage_error("storage", "Option '%s' needs a value",
                       argv[optind - 1]);
      return UA_EXIT_USAGE;
    default:
      if (report)
        ua_usage_error("storage", "Bad option '%s'", ua_bad_option(argv));
      return UA_EXIT_USAGE;
    }
  }
  /* getopt stops at "--"; what follows it is operands, so an IMAGE whose
   * name starts with '-' can still be given */
  for (; optind < argc; optind++)
    if (operand(a, optind, argv, &lun, &mode, report) < 0)
      return UA_EXIT_USAGE;
  if (!a->action[0]) {
    if (report)
      ua_usage_error("storage", "Missing action");
    return UA_EXIT_USAGE;
  }
  if (strcmp(a->action, "start"))
    return -1;
  /* Options after the last IMAGE: with one IMAGE they are its own, as they
   * always were; with several there is no telling which they meant */
  if (a->nimg && (lun >= 0 || mode >= 0)) {
    if (a->nimg > 1) {
      if (report)
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

/* For the client relay: open every IMAGE here, in the caller's namespaces and
 * with the caller's own permissions, and say where each sits in argv so the
 * relay can swap it for --fd. Returns how many, 0 when there is nothing to
 * open, -1 when one cannot be opened (already reported). */
int ua_storage_client_fds(int argc, char **argv, int *fds, int *idx) {
  struct sargs a;
  if (parse(argc, argv, &a, 0) != -1 || strcmp(a.action, "start"))
    return 0;
  int n = 0;
  for (int i = 0; i < a.nimg; i++) {
    if (!a.img[i].image)
      continue;
    if ((fds[n] = open_image(argv[a.img[i].image], a.img[i].mode)) < 0) {
      ua_error("%s: %s", argv[a.img[i].image], strerror(errno));
      while (n--)
        close(fds[n]);
      return -1;
    }
    idx[n++] = a.img[i].image;
  }
  return n;
}

int ua_cmd_storage(int argc, char **argv) {
  struct sargs a;
  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_storage();
    return UA_EXIT_OK;
  }
  int r = parse(argc, argv, &a, 1);
  if (r != -1)
    return r;
  struct ua_config cfg;
  ua_config_active(&cfg);
  safe_strncpy(inquiry, cfg.storage_inquiry, sizeof(inquiry));
  if (!strcmp(a.action, "start")) {
    struct load ls[UA_MAX_FDS];
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
      l->lun = m->lun;
      l->loop_fd = -1;
      l->name[0] = '\0';
      l->mode = m->mode;
      if (!m->mode_set && mode_of(cfg.storage_mode) >= 0)
        l->mode = (enum mode)mode_of(cfg.storage_mode);
      if ((l->fd = m->fd) >= 0) {
        char link[64];
        snprintf(link, sizeof(link), "/proc/self/fd/%d", l->fd);
        ssize_t len = readlink(link, l->name, sizeof(l->name) - 1);
        l->name[len > 0 ? len : 0] = '\0';
      } else {
        const char *image = argv[m->image];
        if ((l->fd = open_image(image, l->mode)) < 0) {
          ua_error("%s: %s", image, strerror(errno));
          close_loads(ls, n);
          return UA_EXIT_FAIL;
        }
        if (!realpath(image, l->name))
          safe_strncpy(l->name, image, sizeof(l->name));
      }
      if (!l->name[0])
        safe_strncpy(l->name, "image", sizeof(l->name));
    }
    /* The IMAGEs given become the setup, as Droidspaces saves a start's
     * flags, so a later bare start needs nothing. A name the daemon cannot
     * open later (a container's private path) fails that start, naming it. */
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
