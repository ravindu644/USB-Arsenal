# 02. Taking the controller from Android, and giving it back

The one thing USB Arsenal must never break: after any command, crash or kill, the
phone's own USB (ADB, MTP) has to come back. This doc is why the core does what it does.
Code: `src/gadget.c`.

## How Android drives its gadget

From AOSP `system/core/rootdir/init.usb.configfs.rc`:

- `on property:sys.usb.config=none && property:sys.usb.configfs=1` writes `none` to
  `g1/UDC`, stops adbd, sets `sys.usb.ffs.ready 0`, removes links `f1`..`f3`, sets
  `sys.usb.state`.
- Each mode (`adb`, `mtp`, `mtp,adb`, `rndis`, ...) links its functions into
  `g1/configs/b.1/fN` and writes `${sys.usb.controller}` to `g1/UDC`. The `*,adb` modes
  wait for `sys.usb.ffs.ready=1`, which is set once adbd is up.
- The framework (`UsbDeviceManager`) drives `sys.usb.config` directly on legacy devices,
  or calls the gadget HAL's `setCurrentFunctions` when one exists.
- The Pixel HAL (`hardware/google/pixel/usb`) hardcodes `/config/usb_gadget/g1/`, and its
  `MonitorFfs` writes the controller to `g1/UDC` whenever the adb endpoints appear. In
  other words, it rebinds on its own when adbd comes back.

Samsung's stock configfs adds `/sys/class/android_usb/android0/{functions,enable,state}`
on top. Its HAL writes `functions`, then `UDC`, then `enable`.

## What we do (own-gadget backend)

1. **Save first.** `sys.usb.config` (falling back to `persist.sys.usb.config`, then
   `mtp`), the UDC and the boot_id go into the handoff file. It is fsynced before
   anything changes hands.
2. **Release.** `setprop sys.usb.config none`, then wait for `sys.usb.state=none`
   (`UA_HANDOFF_TIMEOUT_MS`, 5 s). If `g1/UDC` is still bound after that, because a HAL
   ignored the property, write `none\n` to it ourselves.
3. **Bind ours.** Write the UDC, write `connect` to `soft_connect`, then wait for
   `state=configured` (`UA_ENUM_TIMEOUT_MS`, 3 s; a timeout only warns, since the cable
   may simply be unplugged).
4. **Give it back.** Unbind, remove everything, `setprop sys.usb.config <saved>`, wait for
   `sys.usb.state`. If `g1/UDC` is still empty, bind it by hand and `soft_connect`.

While we hold the controller, any attempt by `g1` to bind gets EBUSY from the UDC core.
Stopping adbd through the property also stops `MonitorFfs` from trying.

## Piggyback backend (stock Samsung)

1. Save `android0/functions` and `g1`'s device class triple.
2. `enable 0`, then create our functions in `g1` under the `ua` instance prefix and link
   them into `g1`'s config.
3. Set the IAD class, write our function types (plus `adb` if it was on) to
   `android0/functions`, write the UDC, then `enable 1`. `enable` raises the pullup.
4. Teardown: `enable 0`, unlink and remove ours **first**, while they are still in
   `func_list` where stock unlink can find them, then restore the saved `functions` and
   class, write the UDC, `enable 1`.

Known limit: any `sys.usb.config` change while we are up (replug, MTP toggle, Developer
options) makes the vendor rc rewrite `android0/functions`, which wipes our functions.
The own-gadget backend has no such problem; the M62 patches make it available.

## Crash safety

- The handoff runs with SIGINT, SIGTERM, SIGHUP and SIGQUIT blocked (`ua_crit_enter`),
  so Ctrl-C cannot land between "Android released" and "ours bound".
- SIGHUP and SIGPIPE are ignored process-wide. Releasing Android stops adbd, which
  kills the `adb shell` the command may be running from. The handoff must finish anyway.
- `kill -9` still gets through. `usb-arsenal stop` reads the handoff file and finishes
  the job. It is idempotent and is a no-op when nothing is held.
- `/data/local/tmp` survives a reboot but the gadget does not. The handoff file carries
  the boot_id and is ignored (and deleted) on a different boot.

## Inside a container on Android

A Droidspaces (or chroot) rootfs has no `/system`, so `is_android()` is false, but the
kernel is Android's and `g1` still owns the controller. The core therefore asks the
kernel: `g1` plus `/sys/class/android_usb` means Android's gadget. Without the property
service there is nobody to ask, so the handoff unbinds `g1` by hand, records it as
`g1_udc` in the handoff file, and writes it back on teardown (plus `soft_connect`).
Verified on the a166p from its Ubuntu container.

## Off Android

There is no owner to negotiate with. If another configfs gadget holds the controller we
name it and refuse; we never unbind someone else's gadget. A legacy `g_*` module shows up
as EBUSY at bind, and the error says so.

## Open questions (verify on hardware)

- Samsung 5.x with its own USB HAL: does `setprop sys.usb.config` still drive it, or does
  the hand-unbind fallback carry the day?
- Pixel: does `MonitorFfs` try to rebind `g1` while we hold the controller (harmless
  EBUSY), and does it restore cleanly afterwards?
