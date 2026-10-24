# Kernel requirements

`usb-arsenal check` asks the running kernel what it can do. This page says what a kernel
needs built in for each feature, and which patches in this folder a feature needs on which
kernel versions.

## Options

| Option | Needed for |
|---|---|
| `CONFIG_USB_CONFIGFS` | everything: the gadget interface (a 3.18 kernel built with the legacy android gadget instead needs a rebuild) |
| a peripheral-capable UDC driver | everything |
| `CONFIG_USB_CONFIGFS_MASS_STORAGE` | `storage` |
| `CONFIG_USB_CONFIGFS_F_HID` | `hid` |
| `CONFIG_USB_CONFIGFS_NCM` | `tether` |
| `CONFIG_USB_CONFIGFS_RNDIS` | `tether --rndis` (gone from GKI after 5.10) |
| `CONFIG_IP_NF_NAT`, `CONFIG_IP_NF_TARGET_MASQUERADE` | `tether` NAT |

Modules work too: the kernel loads `usb_f_<name>` when the function directory is created.

## Patches

Each `<version>/` folder holds `git format-patch` exports for that kernel version. Apply a
folder in order:

```sh
cd /path/to/kernel
git am /path/to/USB-Arsenal/kernel-patches/<version>/*.patch
```

A variant subfolder inside a version is the same set rebased on a tree whose mass storage
code differs; take the one that applies cleanly.

| Patch | Recommended for | Kernels |
|---|---|---|
| configfs: allow creating more than one gadget | running at all: without it a second gadget fails to be created, and `check` names the patch | [4.14](4.14/0001-usb-gadget-configfs-allow-creating-more-than-one-gad.patch), [4.19](4.19/0001-usb-gadget-configfs-allow-creating-more-than-one-gad.patch) |
| configfs: support mainline gadget bring-up alongside Android | running at all: without it a hand-built gadget never binds | [4.14](4.14/0002-usb-gadget-configfs-support-mainline-gadget-bring-up.patch), [4.19](4.19/0002-usb-gadget-configfs-support-mainline-gadget-bring-up.patch) |
| configfs: unregister the gadget's own android device | keeping Android's USB alive: without it removing any gadget kills Android's own until a reboot (USB Arsenal never removes its gadget, so it avoids the bug either way) | [4.9](4.9/0001-usb-gadget-configfs-unregister-the-gadget-s-own-andr.patch), [variant](4.9/t295/0001-usb-gadget-configfs-unregister-the-gadget-s-own-andr.patch), [5.10](5.10/0001-usb-gadget-configfs-unregister-the-gadget-s-own-andr.patch), [5.15](5.15/0001-usb-gadget-configfs-unregister-the-gadget-s-own-andr.patch), [variant](5.15/sm8550/0001-usb-gadget-configfs-unregister-the-gadget-s-own-andr.patch), [6.6](6.6/0001-usb-gadget-android_configfs_uevent-unregister-the-ga.patch); part of the first patch on 4.14 and 4.19 |
| f_ncm: fix Samsung's changes for a second instance | `tether` over NCM: without it `tether` uses RNDIS on a kernel with Samsung's NCM extras | [4.9](4.9/0002-usb-gadget-f_ncm-fix-Samsung-s-changes-for-a-second-.patch), [variant](4.9/t295/0002-usb-gadget-f_ncm-fix-Samsung-s-changes-for-a-second-.patch), [4.14](4.14/0003-usb-gadget-f_ncm-fix-Samsung-s-changes-for-a-second-.patch), [4.19](4.19/0003-usb-gadget-f_ncm-fix-Samsung-s-changes-for-a-second-.patch) |
| f_mass_storage: CD-ROM emulation for Mac OS X and Windows | `storage --mode cdrom` on Windows: without it Windows never mounts the disc | two patches each: [4.9](4.9/0003-usb-gadget-f_mass_storage-Make-CD-ROM-emulation-work.patch), [4.14](4.14/0004-usb-gadget-f_mass_storage-Make-CD-ROM-emulation-work.patch), [4.19](4.19/0004-usb-gadget-f_mass_storage-Make-CD-ROM-emulation-work.patch) and the [variant](4.9/t295/0003-usb-gadget-f_mass_storage-Make-CD-ROM-emulation-work.patch) (5.10 and up have them) |
| f_mass_storage: emulate a DVD-ROM for big CD-ROM images | `storage` with an image over 2.36G: without it the host reads a truncated disc (`--mode dvd` does not need it) | [4.9](4.9/0005-usb-gadget-f_mass_storage-emulate-a-DVD-ROM-for-big-.patch), [variant](4.9/t295/0005-usb-gadget-f_mass_storage-emulate-a-DVD-ROM-for-big-.patch), [4.14](4.14/0006-usb-gadget-f_mass_storage-emulate-a-DVD-ROM-for-big-.patch), [4.19](4.19/0006-usb-gadget-f_mass_storage-emulate-a-DVD-ROM-for-big-.patch), [5.10](5.10/0002-pikvm-msd-dvd-support.patch), [5.15](5.15/0002-pikvm-msd-dvd-support.patch), [variant](5.15/sm8550/0002-usb-gadget-f_mass_storage-emulate-a-DVD-ROM-for-big-.patch), [6.6](6.6/0002-pikvm-msd-dvd-support.patch), [6.12](6.12/0001-pikvm-msd-dvd-support.patch) |
| f_hid: writable bInterval configfs attribute | `hid --interval`: without it the keyboard is polled at the kernel's default 1 ms | [4.14](4.14/0007-usb-gadget-hid-add-a-writable-bInterval-configfs-att.patch), [5.10](5.10/0003-usb-gadget-hid-add-a-writable-bInterval-configfs-att.patch), [5.15](5.15/0004-usb-gadget-hid-add-a-writable-bInterval-configfs-att.patch) |

USB Arsenal reads whether a patch is there and never probes, since probing is what panics an
unpatched kernel: `check` shows whether a gadget of our own can be created, `tether` picks
RNDIS when Samsung's NCM extras are there without the f_ncm fix, and `hid --interval` warns
when the attribute is missing.
