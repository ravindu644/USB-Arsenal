# Kernel requirements

`usb-arsenal check` asks the running kernel directly: it creates each gadget function
and removes it again, without reading a config dump. This page lists what to enable when
building a kernel, and the patches USB Arsenal ships for Samsung kernels.

## Required

| Option | Why |
|---|---|
| `CONFIG_USB_CONFIGFS` | The gadget interface. Stock 3.18 Samsung kernels pick `CONFIG_USB_G_ANDROID` instead (the same Kconfig `choice`), so they need a rebuild. |
| A peripheral-capable UDC driver | dwc3 on most phones, dwc2 on a Raspberry Pi |

## Per function

| Option | Command |
|---|---|
| `CONFIG_USB_CONFIGFS_MASS_STORAGE` | `storage` (missing from stock Exynos 9820/990 defconfigs) |
| `CONFIG_USB_CONFIGFS_F_HID` | `hid` (missing from stock Exynos 9820/990 defconfigs) |
| `CONFIG_USB_CONFIGFS_NCM` | `tether` (present in every kernel surveyed) |
| `CONFIG_USB_CONFIGFS_RNDIS` | `tether --rndis` (removed from GKI after 5.10) |
| `CONFIG_IP_NF_NAT`, `CONFIG_IP_NF_TARGET_MASQUERADE` | `tether` NAT |

Modules work too: the kernel loads `usb_f_<name>` when the function directory is
created.

## Kernel patches

`kernel-patches/<version>/` holds `git format-patch` exports, one folder per kernel
version, each made on a real tree. Apply a folder in order:

```sh
cd /path/to/kernel
git am /path/to/USB-Arsenal/kernel-patches/<version>/*.patch
```

| Folder | Made on | Holds | Tested |
|---|---|---|---|
| `4.9/` | Galaxy A01: [a015f](https://github.com/Kernels-by-ravindu644/samsung_kernel_a015f), also [a015g](https://github.com/ravindu644/kernel_samsung_a01) (Qualcomm sdm439) | android0 fix, f_ncm, READ TOC 1/2, DVD | compiled |
| `4.9/t295/` | Galaxy Tab A 8.0 2019 ([t295](https://github.com/Kernels-by-ravindu644/samsung_kernel_t295)) | the same, with the stock READ TOC base | compiled |
| `4.14/` | Exynos 9820: [LineageOS tree](https://github.com/Kernels-by-ravindu644/samsung_kernel_exynos9820_lineageos), also [M62-backport](https://github.com/ravindu644/M62-backport) | multi-gadget, bring-up, f_ncm, READ TOC 1/2, DVD | booted on the S10 (M62): storage, CD-ROM and DVD-ROM boots, NCM |
| `4.19/` | [Exynos 990](https://github.com/ravindu644/samsung_kernel_exynos990) | the 4.14 set, ported | compiled |
| `5.10/` | Galaxy A16 4G ([a165f](https://github.com/Kernels-by-ravindu644/samsung_kernel_a165f)) | android0 fix, DVD | compiled |
| `5.15/` | Galaxy A16 5G ([a166p](https://github.com/Kernels-by-ravindu644/samsung_kernel_a166p)) | android0 fix, DVD, f_hid interval | compiled |
| `5.15/sm8550/` | [Snapdragon 8 Gen 2 Samsung tree](https://github.com/samsung-sm8550-tab/android_kernel_samsung_sm8550) | android0 fix, DVD rebased on Samsung's mode pages | compiled |
| `6.6/` | [Pixel 10 GKI](https://github.com/Kernels-by-ravindu644/google_kernel_GKI-6.6.118-android15.8) (`android_configfs_uevent.c`) | android0 fix, DVD | compiled |
| `6.12/` | PiKVM's original DVD patch, kept verbatim as the base for ports | DVD | (PiKVM) |

"Compiled" means every touched object built with the tree's own defconfig and the
Samsung or GKI options that select the patched code; nothing else of that kernel was
built or booted. Each folder keeps the original authors (Roger Quadros, Neal Liu, Maxim
Devaev) and says in the commit what a port changed.

What each fix does, and what USB Arsenal does without it:

| Fix | Fixes | Without it, USB Arsenal... |
|---|---|---|
| configfs: allow creating more than one gadget (4.14, 4.19) | Every gadget registered its uevent device under a hardcoded `android0`, so a second gadget failed with a misleading "Out of memory" | does not run: the `mkdir` of its own gadget fails as ENOMEM (nothing is changed), and it names the patches. |
| configfs: support mainline gadget bring-up alongside Android (4.14, 4.19) | With `CONFIG_USB_OLD_CONFIGFS`, a symlinked function only reached the config through `android0/functions`, so a hand-built gadget failed to bind with "needs at least one function" | creates its gadget but cannot bind it, fails with EINVAL and names this patch. |
| configfs: unregister the gadget's own android device (4.9, 5.10, 5.15, 6.6; part of the multi-gadget fix on 4.14/4.19) | Every `androidN` device has devt 0 and removal looked the device up by devt, so dropping any second gadget destroyed `android0`, and Android heard no USB state until a reboot | keeps its own gadget, unbound and empty, instead of removing it, so the bug is never triggered by USB Arsenal. |
| f_ncm: fix Samsung's changes for a second instance (4.9, 4.14, 4.19) | Samsung's f_ncm only survives the one NCM instance its HAL creates: its once-per-system extras were created and destroyed per instance (destroying `android0`), the netdev existed only after bind so setting `dev_addr` dereferenced NULL, and on 4.14/4.19 the last datagram of each NTB was queued as an skb that was then freed. Each one panicked the kernel. | avoids NCM on these kernels and tethers over RNDIS, and reads `dev_addr` before setting MACs. |
| f_mass_storage: CD-ROM emulation for Mac OS-X / Windows (4.9, 4.14, 4.19) | Upstream `89ada0fe669a` and `3b91edd624ab` (5.10+ already have them): READ TOC formats 1 and 2. Windows asks for format 1 (the last session) before it mounts a CD's UDF volume. On the A01 they replace Samsung's own `_SUPPORT_MAC_` raw TOC. | a big ISO in `--mode cdrom` boots WinPE but Windows never mounts the disc. `--mode dvd` is unaffected. |
| f_mass_storage: emulate a DVD-ROM for big CD-ROM images (every folder) | Every kernel (mainline too) truncates a CD-ROM image to 2.36 GB, because the lead-out must fit an MSF address. PiKVM's DVD support: bigger images become a DVD-ROM. On 4.x it needs the READ TOC fixes first and a local `put_be24()`. Verified on the S10: a 4.9 GB Windows PE ISO boots and mounts. | warns, and the host reads a truncated disc; `--mode dvd` does not need it. |
| f_hid: writable bInterval configfs attribute (5.15) | f_hid hardcodes the HID interrupt endpoint's bInterval in its descriptors (1 ms at high speed, the `/* FIXME: Add this field in the configfs */` it has always carried), so a keyboard is polled no faster than about 1000 times a second however fast reports are written. Adds an `interval` attribute, the raw bInterval, that overrides fs/hs/ss at bind; 0 keeps the compiled default. At high speed the period is `2^(interval-1)` microframes, so 1 is 125 us. | `hid --interval` finds no such attribute, warns, and leaves the kernel's default poll rate. |

### How USB Arsenal tells a patched kernel apart

It looks, and never tries, because trying is what panics an unpatched kernel:

| `android0/terminal_version` | `android0/ncm_instances` | Meaning | `tether` uses |
|---|---|---|---|
| absent | absent | no Samsung NCM extras, or no NCM instance yet | NCM |
| present | present | patched with the f_ncm fix | NCM |
| present | absent | unpatched Samsung kernel | RNDIS |

`ncm_instances` is added by the f_ncm fix; it also shows how many NCM instances exist. For
the two configfs fixes, `check` shows whether a gadget of our own can be created ("Own USB
gadget"); without them the `mkdir` fails as ENOMEM, which changes nothing, and `check`
names the patches.

Background: [developer/research/01-kernel-survey.md](developer/research/01-kernel-survey.md).
