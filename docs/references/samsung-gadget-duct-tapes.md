# Samsung USB Gadget "Duct-tapes"

# Samsung Exynos9820 USB configfs gadget — two bugs, fixes, and no‑patch PoC

Kernel: `4.14.356-openela` (Samsung BEYONDX / Exynos9820, One UI configfs gadget).  
Single USB device controller: `10c00000.dwc3`.  
Android's own gadget is `g1`, config `configs/b.1`, uevent device `android0`  
(driven by `/vendor/etc/init/init.exynos9820.usb.rc`).

Both bugs live in `drivers/usb/gadget/configfs.c`, inside the Samsung  
`CONFIG_USB_CONFIGFS_UEVENT` additions. Mainline Linux does not have either.

---

## Bug 1 — second gadget fails with "Out of memory"

### What was the issue

Creating any second gadget failed:

```bash
# mkdir /config/usb_gadget/keyboard
mkdir: 'keyboard': Out of memory
```

dmesg showed the real error (mkdir maps every failure in `gadgets_make()` to  
`-ENOMEM`, hence the misleading "Out of memory"):

```plaintext
sysfs: cannot create duplicate filename '/devices/virtual/android_usb/android0'
WARNING: ... sysfs_create_dir_ns+0xb0/0x100
kobject_add_internal failed for android0 with -EEXIST
gadgets_make+0x3a0/0x4c0
```

### Root cause

Every gadget registered its uevent device under the **hardcoded** name  
`android0` (`android_device_create()`). The first gadget `g1` already owns  
`android0`, so the second `device_create(... "android0")` collides with  
`-EEXIST`, and `gadgets_make()` returns `-ENOMEM`.

### How we fixed it

Give each gadget its **own** device, stored in `gi->dev`. The first gadget keeps  
the name `android0` (what Android's HAL expects); additional gadgets become  
`android1`, `android2`, … Uevents are sent from the gadget's own device, and the  
device is unregistered on removal instead of being looked up by the shared  
`devt` 0.

Commit: `usb: gadget: configfs: allow creating more than one gadget`  
(`aa8867995`).

---

## Bug 2 — binding fails with "needs at least one function" (`-EINVAL`)

### What was the issue

The standard, documented (mainline / Kali NetHunter) way to bring up a gadget  
failed even though the function symlink was clearly present:

```bash
# ln -s functions/hid.keyboard configs/c.1/
# echo 10c00000.dwc3 > keyboard/UDC
sh: echo: write error: Invalid argument
```

dmesg:

```plaintext
gadget_dev_desc_UDC_store: +++
Config c/1 of keyboard needs at least one function.
udc 10c00000.dwc3: failed to start keyboard: -22
gadget_dev_desc_UDC_store: err return ---
```

### Root cause

Under `CONFIG_USB_CONFIGFS_UEVENT`, creating the symlink does **not** add the  
function to the config's bind list (`cfg->func_list`) like mainline does.  
Instead `config_usb_cfg_link()` stashes it in a per‑gadget staging pool  
`gi->linked_func`. The **only** thing that moves functions from `linked_func`  
into `cfg->func_list` is `functions_store()` — the sysfs attribute  
`/sys/class/android_usb/android<N>/functions`, which **Android's USB HAL**  
writes.

`configfs_composite_bind()` (reached by `echo <udc> > UDC`) walks  
`cfg->func_list`; for a hand‑built gadget it is still empty, so bind returns  
`-EINVAL`.

This is deliberate Samsung design: the HAL symlinks **all** functions once at  
boot (filling `linked_func` as an "available" pool), then per USB mode writes a  
_selected, ordered subset_ to `android0/functions`, then `UDC`, then  
`android0/enable 1`. A plain configfs user never performs that `functions`  
write, so nothing is ever moved into the bind list.

A second, matching bug: `config_usb_cfg_unlink()` searched only  
`cfg->func_list`, so `rm`‑ing the symlink of a never‑selected function hit  
`WARN("Unable to locate function to unbind")` and leaked the function.

### How we fixed it

Two additive, `CONFIG_USB_CONFIGFS_UEVENT`‑guarded changes, both no‑ops for the  
Android HAL flow (Android fills `func_list` before writing `UDC`):

1. In `configfs_composite_bind()`, if a config's `func_list` is empty, splice  
  the staged `gi->linked_func` into it **before** the empty check — rescuing  
  the direct `echo <udc> > UDC` path.
2. In `config_usb_cfg_unlink()`, also search `gi->linked_func` so a staged  
  function can be dropped and freed.

Commit: `usb: gadget: configfs: support mainline gadget bring-up alongside Android` (`4088bea61`).

---

## PoC — working HID keyboard with **no kernel patch** (shell only)

On a stock kernel you cannot create a second gadget (Bug 1), so this PoC  
**injects HID into the existing** `g1` **gadget** and drives it through Samsung's own  
`android0` HAL attributes (which is exactly how it works around Bug 2 — the  
`functions` write is the step that moves the function into the bind list).

Run as root. `$UDC`/paths assume the device above.

```bash
set -e
G=/config/usb_gadget/g1          # existing Android gadget
A=/sys/class/android_usb/android0 # its uevent device
UDC=10c00000.dwc3

# 1) Create an HID keyboard function inside g1
mkdir -p $G/functions/hid.keyboard
echo 1 > $G/functions/hid.keyboard/protocol      # 1 = keyboard
echo 1 > $G/functions/hid.keyboard/subclass      # 1 = boot interface
echo 8 > $G/functions/hid.keyboard/report_length # 8-byte boot keyboard report

# Standard 63-byte boot-keyboard report descriptor (bash printf; \x = raw byte)
printf '\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x03\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x03\x95\x06\x75\x08\x15\x00\x25\x65\x05\x07\x19\x00\x29\x65\x81\x00\xc0' \
  > $G/functions/hid.keyboard/report_desc

# 2) Link it into g1's config (stages it into the gadget's linked_func pool)
ln -s $G/functions/hid.keyboard $G/configs/b.1/ 2>/dev/null || true

# 3) Samsung 3-step bring-up via android0 (this is the Bug 2 workaround):
echo 0 > $A/enable                 # tear down current composition
echo "adb,hid" > $A/functions      # <-- moves linked_func -> func_list; keep adb so USB adb survives
echo $UDC > $G/UDC                 # bind (func_list now non-empty)
echo 1 > $A/enable                 # pull up / connect

# 4) Verify
cat $A/state                       # expect: CONFIGURED
ls -l /dev/hidg0                   # keyboard character device
```

Type the letter **a** on the host (modifier=0, keycode 0x04, then release):

```bash
printf '\x00\x00\x04\x00\x00\x00\x00\x00' > /dev/hidg0   # press 'a'
printf '\x00\x00\x00\x00\x00\x00\x00\x00' > /dev/hidg0   # release
```

**Why** `functions` **is the key line:** writing `hid` (and `adb`) to  
`android0/functions` runs `functions_store()`, which moves the staged function  
out of `gi->linked_func` into `cfg->func_list`. That is precisely what the plain  
`echo <udc> > UDC` path fails to do on an unpatched kernel.

> With the kernel patch (`4088bea61`) applied you would **not** need the  
> `android0/functions` / `enable` writes at all — `ln -s …` + `echo $UDC > UDC`  
> is enough.

---

## PoC — HID keyboard **+** mouse **+** mass storage **+** RNDIS at once

Same injection into `g1`; add all four functions, link them, then select them  
together in one `functions` write. The `hid` token moves **both** HID functions  
(keyboard and mouse) because both are named `hid`.

```bash
set -e
G=/config/usb_gadget/g1
A=/sys/class/android_usb/android0
UDC=10c00000.dwc3
MSIMG=/data/local/tmp/ms.img       # backing store for mass storage (any path on a rw fs)

# --- HID keyboard ---
mkdir -p $G/functions/hid.keyboard
echo 1 > $G/functions/hid.keyboard/protocol
echo 1 > $G/functions/hid.keyboard/subclass
echo 8 > $G/functions/hid.keyboard/report_length
printf '\x05\x01\x09\x06\xa1\x01\x05\x07\x19\xe0\x29\xe7\x15\x00\x25\x01\x75\x01\x95\x08\x81\x02\x95\x01\x75\x08\x81\x03\x95\x05\x75\x01\x05\x08\x19\x01\x29\x05\x91\x02\x95\x01\x75\x03\x91\x03\x95\x06\x75\x08\x15\x00\x25\x65\x05\x07\x19\x00\x29\x65\x81\x00\xc0' \
  > $G/functions/hid.keyboard/report_desc

# --- HID mouse (3-byte report: buttons + X + Y) ---
mkdir -p $G/functions/hid.mouse
echo 2 > $G/functions/hid.mouse/protocol         # 2 = mouse
echo 1 > $G/functions/hid.mouse/subclass
echo 3 > $G/functions/hid.mouse/report_length
printf '\x05\x01\x09\x02\xa1\x01\x09\x01\xa1\x00\x05\x09\x19\x01\x29\x03\x15\x00\x25\x01\x95\x03\x75\x01\x81\x02\x95\x01\x75\x05\x81\x03\x05\x01\x09\x30\x09\x31\x15\x81\x25\x7f\x75\x08\x95\x02\x81\x06\xc0\xc0' \
  > $G/functions/hid.mouse/report_desc

# --- Mass storage (needs a backing file or block device) ---
[ -f "$MSIMG" ] || dd if=/dev/zero of="$MSIMG" bs=1M count=64
mkdir -p $G/functions/mass_storage.0
echo "$MSIMG" > $G/functions/mass_storage.0/lun.0/file
echo 0 > $G/functions/mass_storage.0/lun.0/ro          # 1 = read-only (CD-ROM-style)

# --- RNDIS (g1 usually already has rndis.0 from the init rc; mkdir is a no-op then) ---
mkdir -p $G/functions/rndis.0 2>/dev/null || true

# --- Link everything into g1's config ---
for f in hid.keyboard hid.mouse mass_storage.0 rndis.0; do
  ln -s $G/functions/$f $G/configs/b.1/ 2>/dev/null || true
done

# --- RNDIS + composite usually needs the Microsoft/IAD device class on the host ---
echo 0xEF > $G/bDeviceClass        # Misc
echo 0x02 > $G/bDeviceSubClass
echo 0x01 > $G/bDeviceProtocol     # Interface Association Descriptor

# --- Single-shot bring-up: select all four (hid = both keyboard+mouse) ---
echo 0 > $A/enable
echo "rndis,mass_storage,hid,adb" > $A/functions   # keep adb; order = USB interface order
echo $UDC > $G/UDC
echo 1 > $A/enable

# --- Verify ---
cat $A/state                       # expect: CONFIGURED
ls -l /dev/hidg0 /dev/hidg1        # hidg0 = keyboard, hidg1 = mouse
```

Tear down / restore normal Android USB:

```bash
echo 0 > /sys/class/android_usb/android0/enable
echo "mtp,adb" > /sys/class/android_usb/android0/functions   # Android's usual set
echo 0 > /config/usb_gadget/g1/bDeviceClass
echo 0 > /config/usb_gadget/g1/bDeviceSubClass
echo 0 > /config/usb_gadget/g1/bDeviceProtocol
echo 10c00000.dwc3 > /config/usb_gadget/g1/UDC
echo 1 > /sys/class/android_usb/android0/enable
```

---

## Gotcha — host doesn't see the gadget until you unplug/replug (D+ pullup)

Symptom: after swapping from Android's `g1` to a hand-built gadget, the old  
device disappears from the host but the new one never shows up in `lsusb` —  
until you physically unplug and replug the cable.

Cause: `udc_bind_to_driver()` in `drivers/usb/gadget/udc/core.c` has an Android  
HACK that **comments out the pullup** after bind:

```c
/* HACK: The Android gadget driver disconnects the gadget on bind and
 * expects the gadget to stay disconnected until it calls
 * usb_gadget_connect when userspace is ready. ...
 * usb_udc_connect_control(udc);   <-- commented out
 */
```

So `echo <udc> > UDC` **binds but never raises the D+ pullup**. The pullup is  
asserted only by `usb_gadget_connect()`, which Samsung calls from `enable_store`  
(`echo 1 > android%d/enable`) or from `UDC_store` only when  
`gi->symboliclink_change_mode` is set. The plain path hits neither, so:

- `echo none > g1/UDC` → unregister → `usb_gadget_disconnect` → pullup down → old device vanishes.
- `echo <udc> > kbd/UDC` → bind, pullup stays down → host sees nothing.
- unplug/replug → VBUS cycles → the udc vbus notifier runs `usb_udc_connect_control()` → pullup up → device appears.

### Fix — assert the pullup yourself (no kernel change)

After writing `UDC`, poke the generic UDC `soft_connect` attribute (it calls  
`usb_gadget_connect()` directly; only needs a gadget bound, controller-agnostic):

```bash
echo 10c00000.dwc3 > /config/usb_gadget/<gadget>/UDC
echo connect > /sys/class/udc/10c00000.dwc3/soft_connect   # host enumerates now, no replug
```

> **Correction (2026-10-18, found by USB Arsenal on this same kernel):** on 4.14,
> `soft_connect` "connect" calls `usb_gadget_udc_start()` unconditionally. If the
> controller is already started (it is after a bind; Samsung's symlink change mode
> even raises the pullup itself), `dwc3_gadget_start()` frees the controller's IRQ
> while it is in use, and the next unbind panics the phone. Only poke `soft_connect`
> when the UDC `state` is still "not attached", and then as the balanced pair
> `disconnect` + `connect`. Also, never create a second NCM instance on these kernels:
> Samsung's f_ncm destroys `android0` when it fails to add `terminal_version`. See
> docs/research/01-kernel-survey.md.

Both PoCs above instead finish with `echo 1 > android0/enable`, which already  
calls `usb_gadget_connect()` — so they do **not** need `soft_connect`. You only  
need it for the pure mainline flow (`ln -s …` + `echo <udc> > UDC`) on the  
patched kernel. To hand the controller back to Android: `echo none > <gadget>/UDC`  
then rebind `g1` (Android re-asserts its own pullup via `enable`).

> Do **not** "fix" this by auto-connecting in `UDC_store` on the mainline path:  
> it would also fire during Android's own `UDC` write (before its `enable`),  
> raising the pullup before the HAL is ready and causing early/double  
> enumeration. The one-line `soft_connect` avoids that entirely.

---

## Caveats

- **Single UDC.** The phone has exactly one controller (`10c00000.dwc3`). Only  
  one gadget binds at a time — this is why the PoC reuses `g1` instead of a  
  second gadget, and why `g1` must be rebound after every change.
- **Android's HAL will fight you.** `init.exynos9820.usb.rc` has  
  `on property:sys.usb.config=…` triggers that rewrite `android0/functions`  
  whenever the USB config changes (plug/unplug, Developer-options toggle, MTP  
  switch). Any of those will wipe the injected functions. For a stable setup,  
  apply the kernel patches and use a **separate** gadget bound over a dedicated  
  controller/OTG event instead of piggybacking on `g1`.
- **Host won't see the gadget until pullup.** `echo <udc> > UDC` binds but does  
  not raise D+ (Android HACK) — see the pullup gotcha above; use `soft_connect`  
  on the pure mainline flow.
- `printf '\xHH'` **needs bash** (or `busybox printf`). Android's stock `toybox`  
  shell may differ — run these from bash, or use `xxd -r -p` to build the  
  descriptor files.
- **Mass storage backing file** must live on a writable filesystem with enough  
  space; point `lun.0/file` at a real image or block device.
- **RNDIS on Windows** may still need matching VID/PID and an inf/driver; the  
  IAD device-class lines above cover the common composite-enumeration case.