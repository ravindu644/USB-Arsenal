# 01. Kernel survey: the gadget subsystem from 3.18 to GKI 6.6

Read-only survey of every tree in `~/Documents/Kernels` (2026-10-18). Paths below are
relative to each tree. Line numbers are from that date's checkouts.

**Short answer.** Every kernel from 4.9 up can build a configfs gadget, but three
variants exist, and the 3.18 floor ships without configfs gadget support at all.

## Summary

| Tree | Version | configfs.c | uevent device | linked_func staging | Second gadget | Pullup HACK |
|---|---|---|---|---|---|---|
| samsung_kernel_t385 | 3.18.120 | Samsung UEVENT, minimal (`state` only) | hardcoded `android0` (configfs.c:1732) | no | no | yes (udc-core.c:411-418) |
| t295, a015f, a015g | 4.9.227 | AOSP UEVENT (Qualcomm) | `android%d` (t295 configfs.c:1948) | no | yes | no (core.c:1370) |
| exynos9820_lineageos | 4.14.356 | Samsung UEVENT + GSI | hardcoded `android0` (configfs.c:2309) | yes (2094, 2168) | no | no in effect, see note |
| M62-backport | 4.14.356 | Samsung + the two fixes | `android0`, then `android1..` (configfs.c:2222) | yes, rescued at bind | yes | yes (core.c:1340-1346) |
| exynos990 | 4.19.325 | Samsung UEVENT + GSI | hardcoded `android0` (configfs.c:2268) | yes (2056, 2130) | no | yes (core.c:1403-1409) |
| a165f | 5.10.226 | AOSP UEVENT | `android%d` (configfs.c:1791) | no | yes | no |
| a166p | 5.15.178 | AOSP UEVENT | `android%d` (1785) | no | yes | no |
| sm8550 | 5.15.202 | AOSP UEVENT | `android%d` (1789) | no | yes | no |
| pixel10pro / p10-pro-krnl | 6.6.118 | mainline + `android_configfs_uevent.c` | IDA-allocated `android%d` (:172-179) | no | yes | no (connect from a workqueue) |

Note on exynos9820: the connect call is wrapped in `#ifndef CONFIG_USB_OLD_CONFIG`
(core.c:1355), but Kconfig defines `USB_OLD_CONFIGFS` (Kconfig:137). The typo means the
pullup does go up on bind there.

## What it means for USB Arsenal

- **3.18 (t385).** `USB_CONFIGFS` and `USB_G_ANDROID` share one Kconfig `choice`
  (gadget/Kconfig:206-514). Both shipped defconfigs pick `G_ANDROID`. There is no
  configfs `f_hid` (f_hid.c is legacy-only, not in function/Makefile). Needs a custom
  kernel; `check` says so.
- **Stock Samsung 4.14 / 4.19.** `mkdir` of a second gadget collides on `android0` and
  fails as ENOMEM. A link puts the function on `gi->linked_func`, not `cfg->func_list`
  (exynos9820 configfs.c:729), so a hand-built gadget fails bind with "needs at least
  one function". Only `android0/functions` moves it across, so USB Arsenal needs patches
  `0001` and `0002` here and refuses without them. There is one exception: a config string other than `"Conf 1"` set before
  linking turns on "symlink change mode" (exynos9820 :466, exynos990 :434). We set
  `"USB Arsenal"`, which is harmless everywhere else.
- **M62-backport.** Commit `aa8867995` (`kernel-patches/4.14/0001`) gives every gadget its own uevent
  device. Commit `3090dac36` (`0002`) splices `linked_func` at bind (configfs.c:1599-1610)
  and searches it on unlink (:765-779). The own-gadget backend works there.
- **M62 `enable_store(0)`** calls `unregister_gadget` + `clear_current_usb_link`
  (configfs.c:~2125). If Android's HAL writes `android0` while we hold the controller, it
  can unbind us.

## UDC core

- `soft_connect` (write-only, `connect`/`disconnect`, EOPNOTSUPP with no driver bound)
  and `state` (read-only) exist on every version, 3.18 included (udc-core.c:522-555).
  USB Arsenal always writes `connect` after bind: required on HACK kernels, a no-op
  elsewhere.
- The `function` attribute exists only on 4.14+.

## f_mass_storage

| Version | LUN attributes |
|---|---|
| 3.18 | file, ro, removable, cdrom, nofua (f_mass_storage.c:3991) |
| 4.9 to 6.1 era | + inquiry_string |
| 6.6 | + forced_eject (f_mass_storage.c:3266) |

Function level: `stall`. FSG_MAX_LUNS is 8 on 3.18, 16 later. CD-ROM details are in
[03-mass-storage-cdrom.md](03-mass-storage-cdrom.md).

## f_hid

- 3.18: no configfs HID.
- 4.9 / 4.14: subclass, protocol, report_length, report_desc, dev.
- 4.19+, 5.10, 5.15, 6.6: + no_out_endpoint.
- Node `/dev/hidg<minor>`. HIDG_MINORS = 4, so at most four HID functions.
- Attributes return EBUSY once the function is in a config (`opts->refcnt`), so they
  are written before linking.

## NCM / RNDIS

- NCM attributes everywhere: dev_addr, host_addr, qmult, ifname; 5.10 adds
  max_segment_size; NCM os_desc only on 4.19+.
- RNDIS: 3.18 has dev_addr, host_addr, qmult, ifname and os_desc `interface.rndis`;
  4.9 (Qualcomm) adds `wceis`; 4.14+ adds class/subclass/protocol.

| Defconfig | NCM | RNDIS | MASS_STORAGE | F_HID | UVC |
|---|---|---|---|---|---|
| 4.9 Samsung | y | y | y | y | n |
| exynos9820, M62, exynos990 stock | y | y | n | n | n |
| M62 `custom.config` | y | y | y | y | n |
| 5.10 GKI (a165f) | y | y | y | y | y |
| 5.15 / 6.6 GKI | y | **n** | y | y | y |

So: NCM is the only network function present everywhere, which is why `tether`
prefers it, and why stock exynos kernels can tether but not boot an ISO.

## Other cross-version traps

- **Unbind.** Every version takes `""` or `"none"`, but Samsung 4.14/4.19 reject a
  zero-length write or one containing a NUL (`if(!len || strlen(name)!=len)`,
  exynos9820 configfs.c:422). We always write `none\n`. Those kernels also `mdelay(50)`
  on every UDC write.
- Writing UDC while it is already bound returns EBUSY everywhere.
- `max_speed` exists only on 5.10+; `webusb/` only on 6.6. We use neither.
- Function directory names must be `type.instance`, under 40 characters, everywhere.
- Samsung GSI code special-cases a function named `adb` when relinking. We never
  relink Android's functions.

## os_desc

Present on 3.18 already: `use`, `b_vendor_code`, `qw_sign` (configfs.c:815-924), the
config link (:934), and `interface.*` compatible_id / sub_compatible_id (:1229-1314).

## Two kernel traps that panicked the S10 (M62, 4.14)

Found on hardware on 2026-10-18. Both are in kernel code that USB Arsenal drives
through plain configfs and sysfs writes.

### `soft_connect` "connect" double-starts the controller (4.14 and older cores)

```c
/* M62 drivers/usb/gadget/udc/core.c:1454 usb_udc_softconn_store */
if (sysfs_streq(buf, "connect")) {
        usb_gadget_udc_start(udc);      /* unconditional */
        usb_gadget_connect(udc->gadget);
```

The 5.15 core tracks `udc->started` (a166p `core.c:1123`); 4.14 does not.
`dwc3_gadget_start()` requests its IRQ before checking for a bound driver (M62
`dwc3/gadget.c:2476-2491`). On an already started controller it frees the IRQ while
it is still in use, which logs "Trying to free already-free IRQ" and corrupts the
controller. The next unbind panics.

The fix is in `pullup()` (`src/gadget.c`):
- if the UDC `state` leaves "not attached" within 1 s, the host is already
  enumerating, so nothing is written;
- otherwise it writes the balanced pair `disconnect` (`udc_stop`) then `connect`
  (`udc_start`).

On M62 our configuration string also enables Samsung's symlink change mode, so
`UDC_store` raises the pullup itself and `soft_connect` is never needed.

### Samsung `f_ncm` destroys `android0` on a second NCM instance

```c
/* f_ncm.c create_terminal_attribute(), CONFIG_USB_ANDROID_SAMSUNG_COMPOSITE */
android_dev = create_function_device("terminal_version");  /* == android0 */
err = device_create_file(android_dev, &dev_attr_terminal_version);
if (err) {
        device_destroy(android_dev->class, android_dev->devt); /* android0! */
```

- `ncm_alloc_inst()` runs this on every NCM `mkdir`.
- Android's `g1` already has `ncm.0`, so a second instance fails with a duplicate
  `terminal_version`, and the error path destroys `android0`.
- The next NCM instance hits `kernel BUG at fs/sysfs/file.c:333`. The same freed device
  under Android's gadget is a panic waiting to happen.
- The code is in M62, exynos9820, exynos990, a015f/g and t295.

USB Arsenal's guard: when `android0/terminal_version` exists, no NCM directory is ever
created, not even for the capability probe. `check` flags NCM as unsafe and `tether`
uses RNDIS. Samsung's `rndis_alloc_inst` allocates everything per instance and is fine.

The real fix is a kernel one: drop the `device_destroy()` in that error path, since the
device belongs to Android's gadget, not to this NCM instance.

