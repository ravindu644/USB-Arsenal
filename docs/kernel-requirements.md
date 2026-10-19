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

## Patches for Samsung 4.x kernels

`kernel-patches/4.14/` holds `git format-patch` exports, written against the
M62-backport kernel (Exynos 9820, 4.14). Apply them in order:

```sh
cd /path/to/kernel
git am /path/to/USB-Arsenal/kernel-patches/4.14/*.patch
```

Other Samsung 4.x trees (Exynos 990 on 4.19; A01 and Tab A on 4.9) carry the same code
and may need small rebases.

| Patch | Fixes | Without it, USB Arsenal... |
|---|---|---|
| `0001` configfs: allow creating more than one gadget | Every gadget registered its uevent device under a hardcoded `android0`, so a second gadget failed with a misleading "Out of memory" | does not run: the `mkdir` of its own gadget fails as ENOMEM (nothing is changed), and it names the patches. |
| `0002` configfs: support mainline gadget bring-up alongside Android | A symlinked function only reached the config through `android0/functions`, so a hand-built gadget failed to bind with "needs at least one function" | creates its gadget but cannot bind it, fails with EINVAL and names this patch. Both are needed. |
| `0003` f_ncm: fix Samsung's changes for a second instance and rx | Samsung's f_ncm only survives the one NCM instance its HAL creates. A second instance destroyed the shared `android0` device; the netdev existed only after bind, so setting `dev_addr` dereferenced NULL; and the last datagram of each NTB was queued as an skb that was then freed. Each one panicked the kernel. | avoids NCM on these kernels and tethers over RNDIS, and reads `dev_addr` before setting MACs. |
| `0004`, `0005` f_mass_storage: make CD-ROM emulation work with Mac OS-X / Windows | Upstream `89ada0fe669a` and `3b91edd624ab` (5.10+ already have them): READ TOC formats 1 and 2. Windows asks for format 1 (the last session) before it mounts a CD's UDF volume | a big ISO in `--mode cdrom` boots WinPE but Windows never mounts the disc. `--mode dvd` is unaffected. |
| `0006` f_mass_storage: emulate a DVD-ROM for big CD-ROM images | Every kernel (mainline too) truncates a CD-ROM image to 2.36 GB, because the lead-out must fit an MSF address. PiKVM's DVD support, applied as is on `0004` and `0005`: bigger images become a DVD-ROM. Verified on the S10: a 4.9 GB Windows PE ISO boots and mounts. Not Samsung specific; for newer kernels start from `6.12/0001` | warns, and the host reads a truncated disc. |

### How USB Arsenal tells a patched kernel apart

It looks, and never tries, because trying is what panics an unpatched kernel:

| `android0/terminal_version` | `android0/ncm_instances` | Meaning | `tether` uses |
|---|---|---|---|
| absent | absent | no Samsung NCM extras, or no NCM instance yet | NCM |
| present | present | patched with `0003` | NCM |
| present | absent | unpatched Samsung kernel | RNDIS |

### Any kernel: DVD-ROM emulation

`kernel-patches/6.12/0001-pikvm-msd-dvd-support.patch` is PiKVM's original patch, kept
verbatim (Maxim Devaev, pikvm/packages `linux-rpi-pikvm` at `336ceef6`, written for
6.12) as the base for ports. It lifts the 2.36 GB CD-ROM clamp by presenting bigger
images as a DVD-ROM, the same change as `4.14/0006`. Checked with `git apply --check`:
it applies as is to the 6.6 GKI (Pixel 10) and to the Galaxy A16 trees (a166p 5.15,
a165f 5.10); sm8550 (5.15), Exynos 990 (4.19) and t295 (4.9) need small rebases in
`f_mass_storage.c`. `--mode dvd` does not need it.

`ncm_instances` is added by `0003`; it also shows how many NCM instances exist. For
`0001` and `0002`, `check` shows whether a gadget of our own can be created ("Own USB
gadget"); without them the `mkdir` fails as ENOMEM, which changes nothing, and `check`
names the patches.

Background: [developer/research/01-kernel-survey.md](developer/research/01-kernel-survey.md).
