/*
 * USB Arsenal - composite USB gadget builder for Linux and Android
 *
 * Copyright (C) 2026 ravindu644 <droidcasts@protonmail.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * What storage.c and image.c share.
 */

#ifndef UA_STORAGE_INTERNAL_H
#define UA_STORAGE_INTERNAL_H

#include "usbarsenal.h"

enum mode { MODE_AUTO, MODE_CDROM, MODE_DVD, MODE_DISK, MODE_RW };

/* One LUN of a start: its image, open, and what the kernel will be given.
 * loop_fd is the loop device behind a dvd LUN, closed once the kernel holds
 * it. */
struct load {
  int fd, loop_fd, lun;
  enum mode mode;
  char name[PATH_MAX], path[64];
};

/* image.c */

int prepare(struct load *l, int android_gadget);

#endif /* UA_STORAGE_INTERNAL_H */
