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
| `0001` configfs: allow creating more than one gadget | Every gadget registered its uevent device under a hardcoded `android0`, so a second gadget failed with a misleading "Out of memory" | uses the piggyback backend: its functions ride inside Android's own gadget `g1` and are selected through `android0/functions`. It works, but Android rewrites `g1` on a replug or a USB mode change. |
| `0002` configfs: support mainline gadget bring-up alongside Android | A symlinked function only reached the config through `android0/functions`, so a hand-built gadget failed to bind with "needs at least one function" | same as above; `0001` and `0002` together unlock the own-gadget backend. |
| `0003` f_ncm: fix Samsung's changes for a second instance and rx | Samsung's f_ncm only survives the one NCM instance its HAL creates. A second instance destroyed the shared `android0` device; the netdev existed only after bind, so setting `dev_addr` dereferenced NULL; and the last datagram of each NTB was queued as an skb that was then freed. Each one panicked the kernel. | avoids NCM on these kernels and tethers over RNDIS, and reads `dev_addr` before setting MACs. |
| `0004` f_mass_storage: emulate a DVD-ROM for big CD-ROM images | Every kernel (mainline too) truncates a CD-ROM image to 2.36 GB, because the lead-out must fit an MSF address. Backport of PiKVM's DVD support: bigger images become a DVD-ROM. Not Samsung specific; on 4.19+ use PiKVM's original | warns, and the host reads a truncated disc. |

### How USB Arsenal tells a patched kernel apart

It looks, and never tries, because trying is what panics an unpatched kernel:

| `android0/terminal_version` | `android0/ncm_instances` | Meaning | `tether` uses |
|---|---|---|---|
| absent | absent | no Samsung NCM extras, or no NCM instance yet | NCM |
| present | present | patched with `0003` | NCM |
| present | absent | unpatched Samsung kernel | RNDIS |

`ncm_instances` is added by `0003`; it also shows how many NCM instances exist. For
`0001` and `0002`, `check` shows which backend the kernel allows: "own gadget" with
them, "piggyback" without.

Background: [developer/research/01-kernel-survey.md](developer/research/01-kernel-survey.md).
