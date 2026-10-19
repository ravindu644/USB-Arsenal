# 03. Mass storage: booting an ISO, and the CD-ROM limits

Code: `src/gadgets/storage.c`. Kernel: `f_mass_storage.c`, `storage_common.c`.

## The mechanism

A LUN has `file`, `ro`, `removable`, `cdrom`, `nofua` (plus `inquiry_string` from 4.9 and
`forced_eject` from 6.6). Booting is entirely the host firmware's job. We only choose
how the image is presented:

- **CD-ROM** (`cdrom=1`): the gadget is a SCSI MMC drive with 2048-byte sectors.
  BIOS/UEFI boots a plain ISO9660/UDF image through El Torito. This is the only way a
  non-hybrid ISO can boot.
- **Disk** (`cdrom=0`): a 512-byte-sector disk. Isohybrid ISOs (most Linux ISOs) and
  pen-drive images boot this way.

## Choosing the mode (auto)

An MBR signature (`0x55AA` at offset 510) or a GPT header (`EFI PART` at offset 512) means
the image was built to boot as a disk. Isohybrid ISOs carry an MBR for exactly that
reason, and so do FAT superfloppies. A plain ISO9660 image starts with 32 KB of zeros, so
it gets CD-ROM mode.

DriveDroid did not detect anything: the user picked writable USB, read-only USB or
CD-ROM per image, and CD-ROM needed a patched kernel. Our auto mode makes that choice
from the image, and `--mode` overrides it.

## Limits that bite (every kernel surveyed)

- **Maximum size.** `fsg_lun_open` clamps a CD-ROM to `256*60*75 - 1` frames of 2048
  bytes, about 2.36 GB, and only logs it ("file too big"). See "Getting past 2.36 GB"
  below. We warn above the limit.
- **Minimum size.** 300 frames (600 KB). We refuse below that.
- **Read-only.** `cdrom=1` forces read-only.
- **READ TOC.** 4.9 to 4.19 answer format 0 only, with start_track at most 1
  (exynos990 f_mass_storage.c:1200). 5.10+ answer formats 0, 1 and 2 (a165f :1201).
  t385 has Samsung's raw format-2 TOC (`_SUPPORT_MAC_`). Some picky UEFI or Mac
  firmware may need 5.10+.
- **Single data track,** no audio.

## Getting past 2.36 GB

**Where the limit comes from.** `store_cdrom_address()` writes READ TOC and READ HEADER
addresses in MSF form when the host asks for it, and the minutes go in one byte. 256
minutes of 75 frames a second is the last lead-out it can describe, so `fsg_lun_open`
truncates the whole medium to fit. Data reads use READ(10) with a 32-bit LBA and never
needed the clamp. The clamp is in every tree we have, from 3.18 to the 6.6 GKI
(`google_kernel_pixel10pro-2026-01/common/.../storage_common.c:247`), and in mainline.

**Upstream said no.** Dave Young sent a patch deleting the clamp in March 2015
(lkml 1503.0/06234). Alan Stern NAKed it: the gadget emulates a CD drive, not an image,
and a real drive with a 5 GB disc is a DVD drive with a different command set. He asked
for DVD emulation instead. Nobody upstreamed one.

**Downstream did it two ways.**
- Delete the clamp only (tjmnmk/gadget_cdrom `00-remove_iso_limit.patch`, an OpenBMC
  series from 2021). Reads work because they are LBA based; only an MSF TOC request
  gets a wrapped lead-out. The author calls it "not a good way".
- Real DVD-ROM emulation: PiKVM's `1102-pikvm-msd-dvd-support.patch` (Maxim Devaev,
  November 2025, in pikvm/packages `linux-rpi-pikvm`, KVMD 4.49+). Above the MSF limit
  the LUN sets `cd_as_dvd` and answers GET CONFIGURATION with the DVD-ROM profile, READ
  DISC INFORMATION, READ TRACK INFORMATION, READ DISC STRUCTURE (physical format), the
  0x2A capabilities mode page, and a fixed lead-out in the TOC. Images below the limit
  stay CD-ROMs. About 390 lines in `f_mass_storage.c` and `storage_common.[ch]`, GPL-2,
  no userspace interface change. Backported to 4.14 as `kernel-patches/4.14/0006`.

  First attempt on the S10: the clamp was gone (Fedora read all 4.9 GB of `sr0`, and its
  last sectors), WinPE booted, but Windows never mounted the disc. 4.14's READ TOC only
  answers format 0, and Windows asks for format 1 (multi-session info) to find the last
  session before mounting UDF. Upstream added formats 2 and 1 in 2022 (`89ada0fe669a`
  for Mac OS-X, then `3b91edd624ab` "Make CD-ROM emulation works with Windows OS"); 5.10+
  carry them, PiKVM's patch assumes them. They are now `4.14/0004` and `0005`, and
  PiKVM's patch applies as is on top as `0006`.

**Without a kernel patch: a 2048-byte disk (verified).** EDK2's `PartitionDxe/ElTorito.c`
parses El Torito on any block device whose block size divides 2048 ("If the ISO image has
been copied onto a different storage media"), and disk mode has no size clamp. Two steps,
both tested on the S10 (stock f_mass_storage) with a 4.9 GB Sergei Strelec WinPE 11 ISO
and an MSI laptop (UEFI):

1. The ISO file in plain disk mode (512-byte sectors): the firmware listed it, the Windows
   boot manager loaded `boot.wim` and WinPE reached the desktop. But WinPE never mounted
   the UDF volume, so everything outside `boot.wim` was missing. UDF counts its anchor
   at block 256 in units of the sector size, so on a 512-byte disk it looks at 128 KiB
   instead of 512 KiB. Linux retries with 2048 and mounts it; Windows does not.
2. The ISO behind a loop device with 2048-byte blocks (`LOOP_SET_BLOCK_SIZE`, 4.14+),
   that loop device in disk mode: `fsg_lun_open` takes the `S_ISBLK` path and reports
   2048-byte sectors (Fedora: `LOG-SEC 2048`, UDF found on the first scan). WinPE booted
   and mounted `USB_STRELEC` (4.56 GB, UDF) with all its programs.

SELinux: the kernel's storage thread must read the loop node. On the S10, `u:r:kernel:s0`
has no `read` on `loop_device` (only on Samsung's `vendor_block_device` and two others),
so the host saw "Unrecovered read error" on sector 0 until
`allow kernel loop_device blk_file { open read getattr ioctl }` was loaded
(`ksud sepolicy patch`; a module ships it as `sepolicy.rule`).

`storage --mode dvd` does this (`loop_attach()` in `src/gadgets/storage.c`), with
`LO_FLAGS_AUTOCLEAR` so the loop detaches when f_mass_storage closes it, and checks the
SELinux rule through selinuxfs `access` before serving.

Why Droidspaces' trick does not cover this: before loop-mounting a rootfs image it
relabels the **image file** to `vold_data_file` (`src/mount.c`), which satisfies the
kernel's read of the backing file, the same check `selinux_fix()` answers with
`media_rw_data_file`. Our denial is the kernel reading the **loop device node** (class
`blk_file`), which Droidspaces never meets because its `mount()` opens the node from its
own permissive domain. No stock label is readable that way on both test phones: as
`blk_file`, `loop_device` and `vold_device` are readable on the a166p but not on the S10,
and `vold_data_file`, `media_rw_data_file`, `app_data_file`, `system_data_file`,
`block_device` and `dm_device` on neither. So the node keeps its label and the module
ships the rule. Both SELinux steps are skipped when selinuxfs is not mounted, since
labels stay on disk after SELinux is turned off.

Also: toybox `losetup` has no block-size option and refuses paths over 64 bytes, and with
`loop.max_part=7` a loop's minor is `n * 8`, so a fallback `mknod` must read the
minor from sysfs.

The old fallback still works everywhere: an NTFS disk image (Rufus, woeusb) plus
UEFI:NTFS. A FAT32 copy fails because `install.wim` is over 4 GB.

## Write order

`cdrom` and `ro` return EBUSY while a file is open (`_fsg_store_ro`,
storage_common.c:379-383), so the order is: `removable=1`, `cdrom`, `ro`, then `file`
last. To change a live LUN, eject first.

## SELinux: the kernel reads the image, not us

f_mass_storage reads the backing file from a kernel thread, so SELinux checks
`u:r:kernel:s0` against the file's label. On the a166p (enforcing), the policy allows
`media_rw_data_file`, `vold_data_file`, `fuse`, `app_data_file` and `system_data_file`
(read and write, checked through selinuxfs `access`). It denies `tmpfs` and
`unlabeled`, which covers a container's `/tmp` and its ext4 loop rootfs. The denial is
dontaudit'ed, so the only symptom is the host logging "Unrecovered read error" on sector
0 of a disk of the right size. DriveDroid never hit this because its images lived on
`/sdcard`.

On an Android kernel, `storage start` relabels a regular file whose label is not in that
list to `u:object_r:media_rw_data_file:s0` (setxattr, logged). If that fails, it warns
and suggests moving the file to `/sdcard` or `/data`. Verified on the a166p: before the
relabel the host saw an unreadable disk; afterwards it read the FAT16 label.

## Ejecting and hot-swapping

- `file` takes `"\n"` to eject. A zero-length write never reaches the store function.
- If the host has locked the medium (PREVENT ALLOW MEDIUM REMOVAL), writing `file`
  returns EBUSY (storage_common.c:441). `forced_eject` (6.6) clears the lock first, so we
  use it when it exists.
- `storage start` on a live LUN, in the same mode, ejects and loads the new image without
  touching the UDC. The host sees a medium change, like swapping a disc.
- Two cases take a rebind instead (a short re-enumeration; the other functions come back
  with it):
  - **Disk to CD-ROM or back.** This is a different SCSI device type, which a host only
    reads at enumeration.
  - **A host lock.** Linux locks a CD-ROM or removable disk it has open, so before 6.6
    (no `forced_eject`) the eject returns EBUSY. Every reset or disconnect clears
    `prevent_medium_removal` (a166p `f_mass_storage.c:2421`), so after the unbind the
    eject is retried for up to 3 s.

  Verified on the a166p against a Fedora host: a disk-to-CD-ROM switch and a
  CD-ROM-to-CD-ROM swap both went through the rebind, with the CD-ROM and HID intact.

## Stretch: a Ventoy-style menu

Ventoy boots its own loader from a disk image and chain-loads ISOs from a data
partition. A USB Arsenal version would serve a small boot image and switch the LUN's
`file` when the user picks an ISO. That needs a channel back from the host, which the
gadget does not have, so it would have to run entirely on the host side. Roadmap only.
