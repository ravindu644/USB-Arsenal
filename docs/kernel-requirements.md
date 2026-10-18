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

## Raspberry Pi and other boards

```
# /boot/firmware/config.txt
dtoverlay=dwc2
# then: modprobe libcomposite (usb-arsenal tries this itself)
```

Only the USB-C / OTG port of a Pi 4/5 (or the data micro-USB port of a Zero) can act
as a device.
