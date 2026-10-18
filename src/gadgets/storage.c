/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * `storage`: f_mass_storage LUNs. Booting an ISO is entirely the kernel's
 * job; we only pick between CD-ROM and disk mode and write the attributes in
 * the order the kernel accepts. See docs/research/03-mass-storage-cdrom.md.
 */

#include "usbarsenal.h"
#include <sys/xattr.h>

#define FUNC "mass_storage.ua"

/* storage_common.c clamps a CD-ROM to 256*60*75-1 frames of 2048 bytes on
 * every kernel we support, and only logs it. Past this the host reads a
 * silently truncated disc. */
#define CDROM_MAX_BYTES ((long long)(256 * 60 * 75 - 1) * 2048)
#define CDROM_MIN_BYTES (300LL * 2048)

enum mode { MODE_AUTO, MODE_CDROM, MODE_DISK, MODE_RW };
static const char *const mode_names[] = {"auto", "cdrom", "disk", "rw"};

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
      "                            CD-ROM otherwise (Windows ISOs)\n"
      "                     cdrom  read-only CD-ROM, boots plain ISO9660/UDF\n"
      "                            via El Torito. Limit: about 2.36 GB.\n"
      "                     disk   read-only disk\n"
      "                     rw     writable disk: a pen drive the host can\n"
      "                            write to. Never use on a mounted image.\n"
      "  -l, --lun N      LUN to use, 0-7 (default: 0). Each LUN is a\n"
      "                   separate drive on the host.\n"
      "  -h, --help       Show this help\n\n"
      "Examples:\n"
      "  " UA_NAME " storage start ubuntu-24.04.iso\n"
      "  " UA_NAME " storage start --mode cdrom Win11.iso\n"
      "  " UA_NAME " storage start --mode rw /data/local/tmp/pendrive.img\n"
      "  " UA_NAME " storage start --lun 1 --mode disk rescue.img\n"
      "  " UA_NAME " storage start fedora.iso     # swaps the live image\n"
      "  " UA_NAME " storage eject\n"
      "  " UA_NAME " storage stop\n\n"
      "Make a blank 4 GB pen drive image:\n"
      "  truncate -s 4G pendrive.img\n\n"
      "Windows ISOs are larger than the kernel's CD-ROM limit. Write them to\n"
      "a disk image with an NTFS partition (Rufus, or woeusb on Linux) and\n"
      "use --mode disk.\n");
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
 * kernel domain against the file's label, not us. Android's policy lets it
 * at user storage (/sdcard, /data) but not at tmpfs or an unlabeled
 * filesystem such as a container's rootfs: the host then gets "unrecovered
 * read error" on every sector, and the only log is a dontaudit nobody sees.
 * These are the labels the policy was checked to allow, read and write. */
static const char *const kernel_ok[] = {
    ":media_rw_data_file:", ":vold_data_file:",   ":fuse:",
    ":app_data_file:",      ":system_data_file:",
};
#define UA_STORAGE_LABEL "u:object_r:media_rw_data_file:s0"

static void selinux_fix(const char *path) {
  char label[256];
  ssize_t n = getxattr(path, "security.selinux", label, sizeof(label) - 1);
  if (n <= 0)
    return; /* no label, no SELinux to satisfy */
  label[n] = '\0';
  for (size_t i = 0; i < sizeof(kernel_ok) / sizeof(kernel_ok[0]); i++)
    if (strstr(label, kernel_ok[i]))
      return;
  if (setxattr(path, "security.selinux", UA_STORAGE_LABEL,
               sizeof(UA_STORAGE_LABEL), 0) == 0)
    ua_log("Relabelled %s (%s -> %s) so the kernel may read it", path, label,
           UA_STORAGE_LABEL);
  else
    ua_warn("%s is labelled %s, which Android's SELinux policy does not let "
            "the kernel read; the host will see read errors (%s). Move it to "
            "/sdcard or /data.",
            path, label, strerror(errno));
}

static int do_start(const char *image, enum mode mode, int lun) {
  char path[PATH_MAX];
  if (!realpath(image, path)) {
    ua_error("%s: %s", image, strerror(errno));
    return UA_EXIT_FAIL;
  }
  int fd = open(path, (mode == MODE_RW ? O_RDWR : O_RDONLY) | O_CLOEXEC);
  if (fd < 0) {
    ua_error("%s: %s", path, strerror(errno));
    return UA_EXIT_FAIL;
  }
  if (mode == MODE_AUTO)
    mode = detect_mode(fd);
  long long size = image_size(fd);
  close(fd);

  if (mode == MODE_CDROM && size >= 0 && size < CDROM_MIN_BYTES) {
    ua_error("%s is too small for a CD-ROM (minimum 600 KB)", path);
    return UA_EXIT_FAIL;
  }
  if (mode == MODE_CDROM && size > CDROM_MAX_BYTES)
    ua_warn("%s is %.2f GB, but the kernel's CD-ROM emulation stops at 2.36 "
            "GB. The host will read a truncated disc. Use --mode disk with a "
            "disk image instead (see '" UA_NAME " storage --help').",
            path, (double)size / 1e9);

  struct ua_gadget g;
  if (ua_gadget_open(&g) < 0 || ua_check_requirements(&g, UA_NEED_STORAGE) < 0)
    return UA_EXIT_FAIL;

  struct stat st;
  if (g.android_gadget && stat(path, &st) == 0 && S_ISREG(st.st_mode))
    selinux_fix(path);

  char fdir[PATH_MAX], ldir[PATH_MAX];
  if (ua_gadget_func_dir(&g, FUNC, fdir, sizeof(fdir)) < 0 ||
      lun_dir(fdir, lun, ldir, sizeof(ldir)) < 0)
    return UA_EXIT_FAIL;

  /* Already on the bus with this LUN, in the same mode: a medium change is
   * all the host needs to see, like swapping a disc in a real drive. Disk vs
   * CD-ROM is a different SCSI device type, which a host only reads when it
   * enumerates, and a host lock blocks the eject; both take the rebind
   * below, whose disconnect also clears the lock. */
  char cdrom[PATH_MAX + 8], v[8] = "";
  snprintf(cdrom, sizeof(cdrom), "%s/cdrom", ldir);
  if (ua_gadget_live(&g) && ua_gadget_has(&g, FUNC) &&
      read_file(cdrom, v, sizeof(v)) > 0 &&
      (v[0] == '1') == (mode == MODE_CDROM)) {
    int r = lun_load(ldir, path, mode, 0);
    if (r == 0) {
      ua_log("LUN %d now holds %s (%s)", lun, path, mode_names[mode]);
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
  ua_log("Serving %s as a %s on LUN %d", path,
         mode == MODE_CDROM ? "CD-ROM"
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
    printf("Storage:    LUN %d: %s%s\n", lun, file[0] ? file : "(empty)",
           !file[0] ? ""
           : cdrom  ? " [cdrom]"
           : ro     ? " [disk, read-only]"
                    : " [disk, writable]");
  }
}

int ua_cmd_storage(int argc, char **argv) {
  static const struct option opts[] = {
      {"mode", required_argument, NULL, 'm'},
      {"lun", required_argument, NULL, 'l'},
      {"help", no_argument, NULL, 'h'},
      {NULL, 0, NULL, 0},
  };
  enum mode mode = MODE_AUTO;
  int lun = 0, c;

  if (argc < 2 || ua_want_help(argc, argv)) {
    ua_help_storage();
    return UA_EXIT_OK;
  }
  while ((c = getopt_long(argc, argv, ":m:l:h", opts, NULL)) != -1) {
    switch (c) {
    case 'm': {
      int found = 0;
      for (int i = 0; i < 4; i++)
        if (!strcmp(optarg, mode_names[i]))
          mode = (enum mode)i, found = 1;
      if (!found) {
        ua_usage_error("storage", "Unknown mode '%s'", optarg);
        return UA_EXIT_USAGE;
      }
      break;
    }
    case 'l': {
      char *end;
      long v = strtol(optarg, &end, 10);
      if (*optarg == '\0' || *end || v < 0 || v > 7) {
        ua_usage_error("storage", "--lun takes a number from 0 to 7");
        return UA_EXIT_USAGE;
      }
      lun = (int)v;
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

  int nargs = argc - optind;
  const char *action = nargs > 0 ? argv[optind] : "";
  if (!strcmp(action, "start")) {
    if (nargs != 2) {
      ua_usage_error("storage", "start takes exactly one IMAGE");
      return UA_EXIT_USAGE;
    }
    return do_start(argv[optind + 1], mode, lun);
  }
  if (nargs != 1) {
    ua_usage_error("storage", nargs ? "Too many arguments" : "Missing action");
    return UA_EXIT_USAGE;
  }
  if (!strcmp(action, "eject"))
    return do_eject(lun);
  if (!strcmp(action, "stop"))
    return do_stop();
  ua_usage_error("storage", "Unknown action '%s'", action);
  return UA_EXIT_USAGE;
}
