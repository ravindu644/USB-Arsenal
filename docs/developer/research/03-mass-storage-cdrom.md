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
  bytes, about 2.36 GB, and only logs it ("file too big",
  `p10-pro-krnl/drivers/usb/gadget/function/storage_common.c:245-253`, same code on every
  version). A modern Windows ISO (5 to 7 GB) is silently truncated and fails part-way
  through setup. We warn above the limit. The fix is disk mode with an NTFS image
  (Rufus, woeusb) plus UEFI:NTFS. A FAT32 copy fails because `install.wim` is over 4 GB.
- **Minimum size.** 300 frames (600 KB). We refuse below that.
- **Read-only.** `cdrom=1` forces read-only.
- **READ TOC.** 4.9 to 4.19 answer format 0 only, with start_track at most 1
  (exynos990 f_mass_storage.c:1200). 5.10+ answer formats 0, 1 and 2 (a165f :1201).
  t385 has Samsung's raw format-2 TOC (`_SUPPORT_MAC_`). Some picky UEFI or Mac
  firmware may need 5.10+.
- **Single data track,** no audio.

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
