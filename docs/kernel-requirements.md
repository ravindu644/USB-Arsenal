# Kernel requirements

`usb-arsenal check` asks the running kernel directly. This page lists what to enable when
building one.

## Required

| Option | Why |
|---|---|
| `CONFIG_USB_CONFIGFS` | The gadget interface. Stock 3.18 Samsung kernels pick `CONFIG_USB_G_ANDROID` instead (same Kconfig `choice`), so they need a rebuild. |
| A peripheral-capable UDC driver | dwc3 on most phones, dwc2 on a Raspberry Pi (`dtoverlay=dwc2` and `modules-load=dwc2`) |

## Per function

| Option | Command |
|---|---|
| `CONFIG_USB_CONFIGFS_MASS_STORAGE` | `storage` (missing from stock exynos9820/990 defconfigs) |
| `CONFIG_USB_CONFIGFS_F_HID` | `hid` (missing from stock exynos9820/990 defconfigs) |
| `CONFIG_USB_CONFIGFS_NCM` | `tether` (present everywhere surveyed) |
| `CONFIG_USB_CONFIGFS_RNDIS` | `tether --rndis` (removed from GKI after 5.10) |
| `CONFIG_IP_NF_NAT`, `CONFIG_IP_NF_TARGET_MASQUERADE` | `tether` NAT |
| `CONFIG_USB_CONFIGFS_F_UVC` | planned |

Modules work: the kernel loads `usb_f_<name>` when the function directory is created.

## Samsung configfs patches

Stock Samsung 4.14 and 4.19 kernels (`CONFIG_USB_CONFIGFS_UEVENT`) cannot create a second
gadget, and cannot bind one built by hand. USB Arsenal still works there through the
piggyback backend, but these two commits from `M62-backport` unlock the own-gadget
backend, which Android's USB HAL cannot disturb:

1. `usb: gadget: configfs: allow creating more than one gadget` (`aa8867995`): one uevent
   device per gadget (`android0`, `android1`, ...) instead of a hardcoded `android0`.
2. `usb: gadget: configfs: support mainline gadget bring-up alongside Android`
   (`4088bea61`): splice `linked_func` into `func_list` at bind, and search it on unlink.

Background: [references/samsung-gadget-duct-tapes.md](references/samsung-gadget-duct-tapes.md).

## Recommended patch for Samsung 4.x kernels (`f_ncm.c`)

Samsung's NCM extras (`CONFIG_USB_ANDROID_SAMSUNG_COMPOSITE`) are one per system but
were created and destroyed per NCM instance:
1. `create_terminal_attribute()` calls `device_destroy()` on the shared `android0` when
   a second instance cannot add `terminal_version`.
2. `ncm_function_init()` overwrites the global `_ncm_dev` and registers the
   `usb_ncm` misc device again at every function alloc.
3. `ncm_function_cleanup()` deregisters that misc device and frees `_ncm_dev` when
   *any* instance is freed, under the instance that still uses them.

The patch (applied to `M62-backport`):
- counts NCM instances under a mutex;
- creates the attributes once and never destroys `android0`;
- initialises `_ncm_dev` and the misc device only once;
- cleans them up only after the last instance;
- exposes the count as `/sys/class/android_usb/android0/ncm_instances`.

How USB Arsenal decides:

| `terminal_version` | `ncm_instances` | Meaning | `tether` uses |
|---|---|---|---|
| absent | absent | no Samsung extras, or no NCM instance yet; safe | NCM |
| present | present | patched kernel; safe | NCM |
| present | absent | unpatched Samsung kernel; a second NCM instance panics | RNDIS |

It is decided by looking, never by trying. See
[research/01-kernel-survey.md](research/01-kernel-survey.md).

## Raspberry Pi and other boards

```
# /boot/firmware/config.txt
dtoverlay=dwc2
# then: modprobe libcomposite (usb-arsenal tries this itself)
```

Only the USB-C / OTG port of a Pi 4/5 (or the data micro-USB port of a Zero) can act
as a device.
