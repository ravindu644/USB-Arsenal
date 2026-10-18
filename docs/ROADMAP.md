# Roadmap: USB Arsenal

**Goal.** One static binary that turns a phone (or any Linux board with a USB device
controller) into a composite USB gadget: a drive that boots an ISO, USB tethering, and a
keyboard and mouse at `/dev/hidgN`, all at once, and that always hands the controller
back to its owner afterwards.

**Background.** [research/01-kernel-survey.md](research/01-kernel-survey.md) has the
per-kernel facts every decision below rests on.
[research/02-android-usb-handoff.md](research/02-android-usb-handoff.md) covers taking
the controller from Android and giving it back.

**Decisions.**
- Runs on any Linux, not only Android. Android-only steps (`setprop`, `g1`, `android0`)
  sit behind `is_android()`.
- Capabilities are probed at runtime by doing the real thing (create a function, remove
  it). `check` reports them, and every command fails closed on what it needs.
- Configfs is the floor. Kernels without it (stock 3.18) are refused with a reason; the
  legacy `android_usb` gadget is out of scope.
- Two backends behind one interface: **own gadget** (primary), and **piggyback** inside
  Android's `g1` for stock Samsung kernels that cannot create a second gadget.
- One UDC is used automatically. With more than one, `--udc` is required.
- git-style CLI: `usb-arsenal <command> <action> [options]`, each command with a full
  `--help`. See [cli.md](cli.md).
- Networking comes from Droidspaces (`src/net/`), same author, GPL-3.0-or-later.

Tick a box only when the item is merged and verified as described in its phase.

## Phase 0. Gadget core, `check`, CLI
- [x] Makefile modelled on Droidspaces: `native`, `aarch64`, `armhf`, `x86_64`, `x86`,
      `format`, `clean`; bare `make` prints help
- [x] `src/include/usbarsenal.h` as the single header; logging via `ua_log`/`ua_warn`/
      `ua_error`/`ua_debug`
- [x] configfs discovery (`/sys/kernel/config` or `/config`, mounts configfs and loads
      `libcomposite` when needed)
- [x] UDC selection from `/sys/class/udc`, `--udc` required when ambiguous
- [x] Handoff state file written (and fsynced) before the controller changes hands;
      ignored after a reboot (boot_id)
- [x] Own-gadget backend: release Android (`sys.usb.config none`), build, bind,
      `soft_connect`, wait for `configured`
- [x] Piggyback backend: functions in `g1`, selected via `android0/functions`, `enable`
- [x] Teardown restores the previous owner; every failure after `begin` routes through it
- [x] Stop signals held during the handoff; SIGHUP/SIGPIPE ignored so a dying adb shell
      cannot interrupt it
- [x] `check`, `status`, `stop` (idempotent, doubles as crash recovery)
- [x] `tests/dummy_hcd.sh`: end-to-end on a Linux PC through dummy_hcd. Written, not run
      yet (needs root on the dev machine)
- [x] Android detected from the kernel (`g1` + `/sys/class/android_usb`), not `/system`,
      so it works from inside a container; `g1` rebound by hand where `setprop` is absent
- [ ] Gate on hardware: `tether start --no-net`, then `stop`, gives ADB back on all three
      test devices; `kill -9` mid-bind, then `stop`, recovers. a166p (5.15, from a
      Droidspaces container): `g1` unbound and rebound correctly, `kill -9` recovered;
      ADB itself not observed (no host on the cable)
- [ ] Piggyback: confirm whether unlinking our functions after `enable 0` hits the
      `linked_func` WARN on stock Samsung

## Phase 1. `storage`
- [x] `start IMAGE` with `--mode auto|cdrom|disk|rw` and `--lun N`
- [x] auto mode: MBR (`0x55AA` at 510) or GPT (`EFI PART` at 512) boots as a read-only
      disk, anything else as a CD-ROM
- [x] Attribute order: `removable`, `cdrom`, `ro`, then `file`
- [x] Hot-swap: `start` on a live LUN changes the medium without a rebind
- [x] Extra LUNs on a live function (unlinked while `lun.N` is created; EBUSY otherwise);
      a failed `start` only undoes what it made
- [x] `eject` via `forced_eject` (6.6+) or an empty `file`
- [x] Warning above the 2.36 GB CD-ROM clamp, refusal below the 300-frame minimum
- [x] SELinux: relabel images the kernel domain may not read (tmpfs, unlabeled) to
      `media_rw_data_file`
- [x] Mode change or host medium lock falls back to a rebind (clears the lock)
- [x] Hardware (a166p to a Fedora host): FAT image read as a disk (label seen), ISO read
      as `sr0` iso9660, swaps work
- [ ] Hardware: a hybrid Linux ISO actually boots a PC as a disk; a non-hybrid ISO boots
      from `sr0`
- [ ] Hardware: swap while live, host sees a medium change, no re-enumeration
- [ ] Stretch: Ventoy-style on-PC menu

## Phase 2. `hid`
- [x] Boot keyboard (8-byte report) and mouse (5 buttons, X, Y, wheel; 4 bytes)
- [x] Node found by the function's `dev` major:minor, not by name
- [x] `--perm` (default 0660) and `--group` (default root), saved and re-applied after
      every rebind (the kernel recreates the nodes as 0600 root)
- [x] Hardware (a166p to Fedora): the host registers a keyboard and a mouse; reports
      written to `/dev/hidg1`/`hidg2` are collected by the host
- [ ] Hardware: a keypress report types on the host (only empty reports were sent)

## Phase 3. `tether`
- [x] NCM first, RNDIS fallback (`--rndis`, or automatic when NCM is missing)
- [x] RNDIS Microsoft OS descriptors (own backend)
- [x] Stable MACs derived from `ro.serialno` / `/etc/machine-id`
- [x] Daemon: address, DHCP (Droidspaces `dhcp.c`), NAT (`iptables.c`), uplink detection
      (`uplink.c` from `network.c`), Android policy rules
- [x] Re-asserts `ip_forward` and follows the uplink between Wi-Fi and mobile data
- [x] `--no-net` for a bare network function
- [x] Refuses clearly when the interface is in another network namespace (container),
      and a failed start removes the function it added
- [ ] Hardware: host gets a lease, `ping 1.1.1.1` works; after `stop`, Android's own USB
      tethering still works
- [ ] Re-apply the address and restart DHCP when a composition change re-registers the
      netdev (today: `tether stop` + `start`)
- [ ] Container support, see [research/05-netns.md](research/05-netns.md): Droidspaces
      `usb_net_passthrough` (host-side move), plus setns-move-setns in USB Arsenal when a
      host netns handle exists (chroot `/proc/1/ns/net`, or a bind mount)
- [ ] IPv6 (RA is already in `ra.c`, NAT66 probe is `ds_ipt6_available()`)

## Phase 5. Daemon, containers, gateway (see [daemon.md](daemon.md))
- [x] Workspace `/data/local/USB-Arsenal` / `/var/lib/USB-Arsenal`, laid out like
      Droidspaces' (`Logs`, `Pids`, `Net`, `Gadget`, `Run`, `usb-arsenald.pid`)
- [x] `usb-arsenal daemon [--foreground] [--gid N]`, `daemon stop`: Droidspaces' daemon
      ported to a filesystem socket (`Run/usb-arsenal.sock`, `0660 root:3460`)
- [x] Access: root or the `usb-arsenal` group (`SO_PEERCRED`, `SO_PEERGROUPS`, `/proc`
      Groups), fail closed
- [x] Client relay for every command; `storage start` passes the image as an fd
      (`/proc/self/fd/N` to the kernel, no path translation)
- [x] `tether start --gateway NAME|--gateway-pid|--gateway-netns [--gateway-iface]`:
      `usb0` on bridge `ua-usb`, veth born inside the container, re-plugged after a
      container restart or a gadget rebind
- [x] KernelSU/Magisk module (`make module`): picks the ABI binary, starts the daemon at
      boot
- [x] Hardware (a166p): daemon, relayed storage with fd passing, relayed NAT tether
      (host online via `wlan0`), gateway into OpenWrt (host leased and online via
      OpenWrt), gateway surviving a gadget rebind
- [x] Hardware: client inside a Droidspaces container through the bind-mounted `Run/`:
      root (storage from a container path, HID, NAT tether: host online through the
      phone), group member (status, storage of their own image), non-member refused
- [x] Hardware: gateway re-plug after an OpenWrt container restart (new pid and netns;
      cable re-plugged automatically; host's DNS through OpenWrt back after ~13 s, then
      one ~7 s blip while OpenWrt's services start)
- [ ] Hardware: module flashed, daemon up after boot

## Phase 4. UVC (TODO)
- [ ] `f_uvc` plus a userspace frame feeder, after the kernel's `uvc-gadget` sample

## Stretch
- [ ] `check --json` for a future app frontend
- [ ] Verify on a Raspberry Pi (`dtoverlay=dwc2`)
- [ ] Verify on a Pixel (gadget HAL that rebinds `g1` on its own)

## Verification on hardware
- [ ] Exynos9820 on M62-backport (own gadget, pullup HACK)
- [ ] Stock Samsung 4.14/4.19 (piggyback)
- [ ] Samsung 5.10/5.15 (own gadget)

## Status log
- 2026-10-18: Research done (kernel survey, Droidspaces/Dragon conventions, prior art).
  Phases 0 to 3 implemented and building clean with `-Werror` for x86_64, aarch64 and
  armhf. Nothing has run on hardware or under dummy_hcd yet.
- 2026-10-18: First hardware run, a166p (MT6835, 5.15.178) from inside the Droidspaces
  Ubuntu container over SSH, no USB host attached. `check` all green (own gadget, every
  function, NAT). Verified: handoff from `g1` and back, `kill -9` recovery, storage
  auto disk/CD-ROM and hot-swap, HID nodes (`/dev/hidg1`/`hidg2`, Android's `hid.gs0`
  holds `hidg0`), NCM, composition changes keeping state, `stop` idempotent. Found and
  fixed: Android detection failing in a container, EBUSY on adding a LUN to a linked
  function, failure paths deleting live LUNs, extra LUN dirs blocking teardown, failed
  tether start leaving its function. Not verifiable from the container: enumeration on
  a host, tether network side (`usb0` lives in Android's netns).
- 2026-10-18: Phone on the Fedora PC's USB. Storage: the host saw the disk but every
  read failed; root cause was SELinux (the kernel's file-storage thread may not read
  tmpfs or unlabeled files). Fixed with an automatic relabel, after which the host read
  the FAT16 image and the ISO as `sr0`. Found that a mode switch or a host medium lock
  makes a live swap fail with EBUSY on 5.15, so those cases now rebind. HID: the host
  registered a keyboard and a mouse, and reports were delivered. `stop` gave back MTP
  and ADB (`adb devices` lists the phone). Netns research done
  (research/05-netns.md).
- 2026-10-18: Code review by three parallel read-only agents (core, gadget commands,
  lifted net code and build). Fixed:
  - The handoff file was dropped even when giving the controller back failed. It is
    now kept until Android's gadget is bound again.
  - Hand-binding `g1` could race init. It now waits up to 10 s for Android's mode, and
    only binds `g1` once its functions are linked.
  - Added a state lock over every handoff.
  - `commit` reported success after link failures.
  - Saved and restored `android0/enable` on piggyback.
  - Probe names no longer look like ours.
  - readdir-while-rmdir.
  - The state directory is refused if unsafe (symlink or foreign owner).
  - A boot marker fallback when boot_id is hidden.
  - `stop` is a true no-op when nothing is held.
  - A missing tether daemon pid after a binary update.
  - `hid start` destroying an existing keyboard or mouse.
  - HID permissions lost on rebind.
  - No pid file for foreground tether.
  - Teardown now stops the tether daemon.
  - iptables `-i ""` on an empty interface.
  - Option error messages.

  Regression on the a166p with the Fedora host attached: CD-ROM plus writable disk plus
  HID together; the host wrote a file through the writable LUN and it landed in the
  phone's image; `stop` gave ADB back.
- 2026-10-18: Daemon and client (filesystem socket, root or group 3460), workspace moved
  to `/data/local/USB-Arsenal`, KernelSU module, gateway tether mode. Verified from the
  Android side against the Fedora host: relayed storage via fd passing, relayed NAT
  tether (host online through `wlan0`), gateway into the OpenWrt container through its
  existing VirtualAP `vaplan` interface (host leased and online through OpenWrt), and
  the gateway surviving a gadget rebind.
- 2026-10-18: Container client verified on the a166p. `Run/` is bind-mounted into the
  Ubuntu container. As root: `check`, `storage start /var/tmp/fat.img` (fd passing from
  the container's rootfs; host read FAT16), `hid start`, and `tether start`, which now
  works from inside the container because the network side runs in the daemon (host
  default route through the phone, `ping google.com` fine). As a member of group 3460:
  `status` and `storage start --lun 1 ~/user.img` (opened with the user's own rights).
  A non-member gets "Permission denied" and exit 1, and `version` now says
  "daemon, but no access". `stop` as the group member gave back ADB.
  Host-side note: with storage or HID in the gadget, the host names the NCM interface
  `...i3`, and NetworkManager may need `nmcli device connect` the first time.
- 2026-10-18: OpenWrt restart under gateway mode. `droidspaces --name=OpenWrt restart`
  changed the pid (27088 → 20373) and the netns. The tether loop logged "not running",
  then re-plugged `vaplan0` into the new namespace, where netifd brought it back as
  192.168.42.1. Host side: DNS through OpenWrt down about 13 s (Droidspaces' graceful
  stop plus boot), then one ~7 s blip during OpenWrt service start-up, then stable. The
  host kept its lease; nothing was needed on the PC. Lesson: the test host must not
  route its default traffic over the USB link, or a gateway restart stalls the test
  machine itself (a NetworkManager profile with `never-default` did it).
