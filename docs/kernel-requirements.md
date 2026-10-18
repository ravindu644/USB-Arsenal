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
| `0003` f_ncm: make Samsung's extras safe for more than one instance | A second NCM instance made `f_ncm` destroy the shared `android0` device and free state under Android's own NCM function, and the kernel panicked | avoids NCM on these kernels and tethers over RNDIS instead. |
| `0004` f_ncm: give the netdev to the instance, as mainline does | The netdev only existed after bind (and was freed at every unbind), so setting `dev_addr` on a new NCM function dereferenced NULL and panicked | reads `dev_addr` first, and skips the fixed MACs when there is no netdev yet, so the host sees kernel-chosen MACs. |
| `0005` f_ncm: copy each datagram in ncm_unwrap_ntb(), as mainline does | The last datagram of each NTB was queued as the original skb, which the function then freed, so the rx worker crashed on a freed skb at the first received frames | cannot work around it from userspace: NCM tethering to a host crashes an unpatched kernel at link-up. Use `tether start --rndis` on such kernels. |

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
