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

#define FUNC "mass_storage.ua"

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

#ifndef LOOP_SET_BLOCK_SIZE
#define LOOP_SET_BLOCK_SIZE 0x4C09
#endif

/* The loop device behind a dvd LUN, closed once the kernel holds it */
static int loop_fd = -1;

void ua_help_storage(void) {
  printf(
      "Usage: " UA_NAME " storage <action> [options] [IMAGE]\n\n"
      "Present an image file or block device to the host as a USB drive.\n"
      "A bootable ISO boots on the host like a real USB stick or DVD.\n\n"
      "Actions:\n"
      "  start IMAGE   Insert IMAGE. If the LUN already holds an image, swap\n"
      "                it in place without the host re-enumerating the "
      "device.\n"
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
      "  -h, --help       Show this help\n\n"
      "Examples:\n"
      "  " UA_NAME " storage start ubuntu-24.04.iso\n"
      "  " UA_NAME " storage start --mode dvd Win11.iso\n"
      "  " UA_NAME " storage start --mode rw /data/local/tmp/pendrive.img\n"
      "  " UA_NAME " storage start --lun 1 --mode disk rescue.img\n"
      "  " UA_NAME " storage start fedora.iso     # swaps the live image\n"
      "  " UA_NAME " storage eject\n"
      "  " UA_NAME " storage stop\n\n"
      "Make a blank 4 GB pen drive image:\n"
      "  truncate -s 4G pendrive.img\n");
}

/* lseek works for block devices too, no BLKGETSIZE64 needed */
static long long image_size(int fd) {
  return (long long)lseek(fd, 0, SEEK_END);
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

static int lun_dir(const char *fdir, int lun, char *out, size_t size) {
  int n = snprintf(out, size, "%s/lun.%d", fdir, lun);
  return (n < 0 || (size_t)n >= size) ? -1 : 0;
}

/* A dvd LUN holds a loop device rather than the image itself */
static int is_loop(const char *file) {
  const char *base = strrchr(file, '/');
  return base && !strncmp(base + 1, "loop", 4);
}

static int lun_write(const char *ldir, const char *attr, const char *val) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/%s", ldir, attr);
  if (write_file(p, val) == 0)
    return 0;
  ua_debug("%s <- %s: %s", p, val, strerror(errno));
  return -1;
}

/* Eject. "\n" rather than "": a zero-length write never reaches the store
 * function. forced_eject (6.6+) also clears a host's medium lock. */
static int lun_eject(const char *ldir) {
  char p[PATH_MAX];
  snprintf(p, sizeof(p), "%s/forced_eject", ldir);
  if (access(p, F_OK) == 0)
    return write_file(p, "1");
  return lun_write(ldir, "file", "\n");
}

/* Write order matters: cdrom and ro return EBUSY while a file is open, so
 * the medium goes out first and the new one goes in last. */
/* Returns -EBUSY, quietly, when the host holds a medium lock: the caller
 * decides whether a rebind (which clears it) is the way out. With wait set
 * the eject is retried while the kernel processes that disconnect. */
static int lun_load(const char *ldir, const char *path, enum mode mode,
                    int wait) {
  char cur[PATH_MAX] = "", file[PATH_MAX + 2];
  snprintf(file, sizeof(file), "%s/file", ldir);
  if (read_file(file, cur, sizeof(cur)) > 0) {
    int tries = wait ? 30 : 1;
    while (lun_eject(ldir) < 0)
      if (errno != EBUSY || --tries == 0)
        return errno == EBUSY ? -EBUSY : -1;
      else
        usleep(100000);
  }
  lun_write(ldir, "removable", "1");
  if (inquiry[0])
    lun_write(ldir, "inquiry_string", inquiry);
  if (lun_write(ldir, "cdrom", mode == MODE_CDROM ? "1" : "0") < 0 &&
      mode == MODE_CDROM) {
    ua_error("This kernel cannot emulate a CD-ROM (no lun.N/cdrom)");
    return -1;
  }
  lun_write(ldir, "ro", mode == MODE_RW ? "0" : "1");

  snprintf(file, sizeof(file), "%s\n", path);
  if (lun_write(ldir, "file", file) < 0) {
    ua_error("The kernel refused %s: %s", path, strerror(errno));
    return -1;
  }
  return 0;
}

/* f_mass_storage reads the image from a kernel thread, so SELinux checks the
 * kernel's domain against the image's label, not ours. Android's policy lets
 * it at user storage (/sdcard, /data) but not at tmpfs or an unlabeled
 * filesystem such as a container's rootfs: the host then gets "unrecovered
 * read error" on every sector, and the only log is a dontaudit nobody sees.
 * So ask the policy itself, on any distro, instead of trusting a label list. */
#define UA_STORAGE_LABEL "u:object_r:media_rw_data_file:s0"

/* Labels can outlive SELinux (a kernel booted with it off still reads them
 * from disk), so ask whether it is running, not whether a file has one */
static int selinux_on(void) {
  return access("/sys/fs/selinux/enforce", F_OK) == 0;
}

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
   * domain (the reply's flags) */
  if (!selinux_on() ||
      (read_file("/sys/fs/selinux/enforce", v, sizeof(v)) > 0 && v[0] == '0'))
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

/* The image arrives open, either opened here or by a client in another
 * mount namespace and passed over the daemon socket. The kernel gets
 * /proc/self/fd/N: it follows the magic link to the real file wherever that
 * lives, so no path ever has to mean the same thing on both sides. name is
 * only for messages. */
static int do_start(int fd, const char *name, enum mode mode, int lun) {
  char path[64];
  snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
  long long size = image_size(fd);
  if (mode == MODE_AUTO) {
    mode = detect_mode(fd);
    if (mode == MODE_CDROM && size > CDROM_MAX_BYTES)
      mode = MODE_DVD;
  }

  if (mode == MODE_CDROM && size >= 0 && size < CDROM_MIN_BYTES) {
    ua_error("%s is too small for a CD-ROM (minimum 600 KB)", name);
    return UA_EXIT_FAIL;
  }
  if (mode == MODE_CDROM && size > CDROM_MAX_BYTES)
    ua_warn("%s is %.2f GB, but the kernel's CD-ROM emulation stops at 2.36 "
            "GB unless it carries the DVD patch (kernel-patches/). "
            "Otherwise the host reads a truncated disc; --mode dvd has no "
            "limit.",
            name, (double)size / 1e9);

  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_STORAGE) < 0)
    return UA_EXIT_FAIL;

  struct stat st;
  int have_st = fstat(fd, &st) == 0;
  if (have_st && S_ISREG(st.st_mode))
    selinux_fix(fd, name, mode == MODE_RW, g.android_gadget);

  /* A block device served as is: the kernel thread reads it under the node's
   * label, and no label is readable on every policy, so it cannot be fixed by
   * relabelling the way a file can. Say why before the host sees a dead disk.
   * Whether to serve it at all, mounted or not, is the user's call. */
  char blabel[256];
  if (have_st && S_ISBLK(st.st_mode) &&
      !kernel_may(fd, "blk_file", mode == MODE_RW, g.android_gadget, blabel,
                  sizeof(blabel))) {
    ua_error("SELinux does not let the kernel %s %s (%s), so the host would "
             "see only read errors",
             mode == MODE_RW ? "read and write" : "read", name, blabel);
    ua_log("To serve it anyway, set SELinux permissive by hand first: "
           "setenforce 0");
    return UA_EXIT_FAIL;
  }

  if (mode == MODE_DVD) {
    char dev[32];
    if ((loop_fd = loop_attach(fd, name, dev, sizeof(dev))) < 0)
      return UA_EXIT_FAIL;
    /* Fail closed: an unreadable LUN looks like a dead disk on the host */
    char label[256];
    if (!kernel_may(loop_fd, "blk_file", 0, g.android_gadget, label,
                    sizeof(label))) {
      ua_error("SELinux does not let the kernel read loop devices (%s), so "
               "the host would see only read errors",
               label);
      if (g.android_gadget)
        ua_log("The USB Arsenal module loads the rule at boot; without it, "
               "run: ksud sepolicy patch \"allow kernel loop_device blk_file "
               "{ open read getattr ioctl }\" (Magisk: magiskpolicy --live)");
      return UA_EXIT_FAIL;
    }
    ua_debug("%s is %s with 2048-byte blocks", name, dev);
    snprintf(path, sizeof(path), "/proc/self/fd/%d", loop_fd);
  }

  char fdir[PATH_MAX], ldir[PATH_MAX];
  if (ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir)) < 0 ||
      lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0)
    return UA_EXIT_FAIL;

  /* Already on the bus with this LUN, in the same mode: a medium change is
   * all the host needs to see, like swapping a disc in a real drive. Disk vs
   * CD-ROM is a different SCSI device type, which a host only reads when it
   * enumerates, and a host lock blocks the eject; both take the rebind
   * below, whose disconnect also clears the lock. */
  /* dvd and disk differ in sector size, which the host also only reads
   * when it sees a new medium, so a loaded one is swapped the same way. */
  char cdrom[PATH_MAX + 8], cur[PATH_MAX + 8], v[8] = "";
  snprintf(cdrom, sizeof(cdrom), "%s/cdrom", ldir);
  snprintf(cur, sizeof(cur), "%s/file", ldir);
  if (ua_gadget_live(&g) && ua_gadget_has(&g, FUNC) &&
      read_file(cdrom, v, sizeof(v)) > 0 &&
      (v[0] == '1') == (mode == MODE_CDROM) &&
      (read_file(cur, cur, sizeof(cur)) <= 0 ||
       is_loop(cur) == (mode == MODE_DVD))) {
    int r = lun_load(ldir, path, mode, 0);
    if (r == 0) {
      ua_log("LUN %d now holds %s (%s)", lun, name, mode_names[mode]);
      return UA_EXIT_OK;
    }
    if (r != -EBUSY)
      return UA_EXIT_FAIL;
    ua_debug("host holds the medium lock, rebinding to release it");
  }

  int existed = access(fdir, F_OK) == 0, new_lun = 0;
  if (ua_gadget_begin(&g) < 0)
    return UA_EXIT_FAIL;
  if (ua_gadget_add(&g, FUNC, fdir, sizeof(fdir)) < 0)
    goto fail;
  /* lun.0 comes with the function; the rest are made on demand, and only
   * while the function is out of every config (EBUSY otherwise) */
  if (lun > 0 && access(ldir, F_OK) != 0) {
    ua_gadget_unlink(&g, FUNC);
    if (mkdir(ldir, 0755) < 0) {
      ua_error("Cannot create LUN %d: %s", lun, strerror(errno));
      goto fail;
    }
    new_lun = 1;
  }
  int r = lun_load(ldir, path, mode, 1);
  if (r < 0) {
    if (r == -EBUSY)
      ua_error("The host still holds the drive locked (EBUSY)");
    goto fail;
  }
  if (ua_gadget_commit(&g) < 0)
    return UA_EXIT_FAIL;
  ua_log("Serving %s as a %s on LUN %d", name,
         mode == MODE_CDROM ? "CD-ROM"
         : mode == MODE_DVD ? "read-only disk with 2048-byte sectors"
         : mode == MODE_RW  ? "writable disk"
                            : "read-only disk",
         lun);
  return UA_EXIT_OK;

fail:
  /* Undo only what this call made: the other LUNs and functions stay live */
  if (!existed)
    ua_gadget_remove(&g, FUNC);
  else if (new_lun)
    rmdir(ldir);
  ua_gadget_commit(&g);
  return UA_EXIT_FAIL;
}

static int do_eject(int lun) {
  struct ua_gadget g;
  char fdir[PATH_MAX], ldir[PATH_MAX];
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, 0) < 0)
    return UA_EXIT_FAIL;
  if (!ua_gadget_has(&g, FUNC)) {
    ua_error("Mass storage is not running");
    return UA_EXIT_FAIL;
  }
  ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir));
  if (lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0 || lun_eject(ldir) < 0) {
    ua_error("Eject failed: %s%s", strerror(errno),
             errno == EBUSY ? " (the host has locked the drive)" : "");
    return UA_EXIT_FAIL;
  }
  ua_log("LUN %d ejected", lun);
  return UA_EXIT_OK;
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
  return UA_EXIT_OK;
}

void ua_status_storage(const struct ua_gadget *g) {
  char fdir[PATH_MAX];
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

struct sargs {
  enum mode mode;
  int mode_set, lun, fd;
  const char *action;
  int image; /* index of IMAGE in argv, 0 if none */
};

/* Shared by the command and by a client about to relay it: the client has
 * to find IMAGE to open it on its side. report=0 parses silently and leaves
 * the complaining to the daemon. Returns -1 to go on, else an exit code. */
static int parse(int argc, char **argv, struct sargs *a, int report) {
  static const struct option opts[] = {
      {"mode", required_argument, NULL, 'm'},
      {"lun", required_argument, NULL, 'l'},
      {"fd", required_argument, NULL, 'F'}, /* set by the client relay */
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  int c;
  char *end;
  memset(a, 0, sizeof(*a));
  a->fd = -1;
  a->action = "";
  optind = 0;
  while ((c = getopt_long(argc, argv, ":m:l:h", opts, NULL)) != -1) {
    switch (c) {
    case 'm': {
      int found = 0;
      for (int i = 0; i < NMODES; i++)
        if (!strcmp(optarg, mode_names[i]))
          a->mode = (enum mode)i, found = 1, a->mode_set = 1;
      if (!found) {
        if (report)
          ua_usage_error("storage", "Unknown mode '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      break;
    }
    case 'l': {
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 0 || v > 7) {
        if (report)
          ua_usage_error("storage", "--lun takes a number from 0 to 7");
        return UA_EXIT_USAGE;
      }
      a->lun = (int)v;
      break;
    }
    case 'F': {
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 3) {
        if (report)
          ua_usage_error("storage", "--fd takes an open descriptor number");
        return UA_EXIT_USAGE;
      }
      a->fd = (int)v;
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
  int nargs = argc - optind;
  if (nargs > 0)
    a->action = argv[optind];
  if (!strcmp(a->action, "start")) {
    int want = a->fd >= 0 ? 1 : 2;
    if (nargs != want) {
      if (report)
        ua_usage_error("storage", "start takes exactly one IMAGE");
      return UA_EXIT_USAGE;
    }
    a->image = a->fd >= 0 ? 0 : optind + 1;
    return -1;
  }
  if (nargs != 1) {
    if (report)
      ua_usage_error("storage",
                     nargs ? "Too many arguments" : "Missing action");
    return UA_EXIT_USAGE;
  }
  return -1;
}

static int open_image(const char *image, enum mode mode) {
  return open(image, (mode == MODE_RW ? O_RDWR : O_RDONLY) | O_CLOEXEC);
}

/* For the client relay: open IMAGE here, in the caller's namespaces and with
 * the caller's own permissions, and say where it sits in argv so the relay
 * can swap it for --fd. Returns that index, 0 when there is nothing to open,
 * -1 when the image cannot be opened (already reported). */
int ua_storage_client_fd(int argc, char **argv, int *fd) {
  struct sargs a;
  if (parse(argc, argv, &a, 0) != -1 || !a.image)
    return 0;
  if ((*fd = open_image(argv[a.image], a.mode)) < 0) {
    ua_error("%s: %s", argv[a.image], strerror(errno));
    return -1;
  }
  return a.image;
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
  for (int i = 0; !a.mode_set && i < NMODES; i++)
    if (!strcmp(cfg.storage_mode, mode_names[i]))
      a.mode = (enum mode)i;
  if (!strcmp(a.action, "start")) {
    char name[PATH_MAX] = "";
    int fd = a.fd;
    if (fd >= 0) {
      char link[64];
      snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
      ssize_t n = readlink(link, name, sizeof(name) - 1);
      name[n > 0 ? n : 0] = '\0';
    } else {
      const char *image = argv[a.image];
      if ((fd = open_image(image, a.mode)) < 0) {
        ua_error("%s: %s", image, strerror(errno));
        return UA_EXIT_FAIL;
      }
      if (!realpath(image, name))
        safe_strncpy(name, image, sizeof(name));
    }
    r = do_start(fd, name[0] ? name : "image", a.mode, a.lun);
    close(fd); /* the kernel holds its own reference by now */
    if (loop_fd >= 0)
      close(loop_fd); /* autoclear: the loop now lives as long as the LUN */
    return r;
  }
  if (!strcmp(a.action, "eject"))
    return do_eject(a.lun);
  if (!strcmp(a.action, "stop"))
    return do_stop();
  ua_usage_error("storage", "Unknown action '%s'", a.action);
  return UA_EXIT_USAGE;
}
