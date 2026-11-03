/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * What an image needs before the kernel may have it: CD-ROM or disk, the
 * size limits, SELinux letting the kernel read it, and the loop device that
 * gives dvd mode its 2048-byte sectors.
 */

#include "internal.h"
#include <linux/loop.h>
#include <sys/xattr.h>

/* storage_common.c clamps a CD-ROM to 256*60*75-1 frames of 2048 bytes on
 * every kernel we support, and only logs it. Past this the host reads a
 * silently truncated disc. */
#define CDROM_MAX_BYTES ((long long)(256 * 60 * 75 - 1) * 2048)
#define CDROM_MIN_BYTES (300LL * 2048)

#ifndef LOOP_SET_BLOCK_SIZE
#define LOOP_SET_BLOCK_SIZE 0x4C09
#endif

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

/* Everything about one image before the gadget changes: its mode, the size
 * checks, SELinux, the loop device behind dvd. The image arrives open. The
 * kernel gets /proc/self/fd/N: it follows the magic link to the file we
 * opened, wherever that lives in our mount namespace. name is only for
 * messages. */
int prepare(struct load *l, int android_gadget) {
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
