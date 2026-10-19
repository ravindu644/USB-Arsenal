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
- One backend: our own gadget next to Android's `g1`. Stock Samsung 4.x kernels, which
  cannot create a second gadget, are refused and pointed at `kernel-patches/4.14/`.
- One UDC is used automatically. With more than one, `--udc` is required.
- git-style CLI: `usb-arsenal <command> <action> [options]`, each command with a full
  `--help`. See [usage.md](../usage.md).
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
- [x] Teardown restores the previous owner; every failure after `begin` routes through it
- [x] Stop signals held during the handoff; SIGHUP/SIGPIPE ignored so a dying adb shell
      cannot interrupt it
- [x] `check`, `status`, `stop` (idempotent, doubles as crash recovery)
- [x] `tests/dummy_hcd.sh`: end-to-end on a Linux PC through dummy_hcd. All 9 checks
      pass on Fedora (kernel 7.2, mainline configfs)
- [x] Android detected from the kernel (`g1` + `/sys/class/android_usb`), not `/system`,
      so it works from inside a container; `g1` rebound by hand where `setprop` is absent
- [x] Gate on hardware: `stop` gives ADB back; `kill -9` mid-bind, then `stop`,
      recovers. a166p (5.15): `g1` rebound, `kill -9` recovered, `adb devices` saw the
      phone again. S10 (M62 4.14): `stop` after an NCM tether gave back
      `mtp,conn_gadget,adb` and ADB returned

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
- [x] Hardware: a hybrid Linux ISO actually boots a PC as a disk (S10, Alpine 3.23
      x86_64, UEFI on an MSI laptop, to the login prompt)
- [x] Hardware: the same ISO boots from `sr0` in `--mode cdrom` (El Torito, UEFI)
- [x] Hardware: a non-hybrid ISO (Windows) boots from `sr0`; legacy BIOS boot (with
      `0004` to `0006`, see Phase 1 below and the status log)
- [x] Hardware: swap while live, host sees a medium change, no re-enumeration (a166p,
      after unmounting on the host)
- [x] Port PiKVM's DVD-ROM emulation to `kernel-patches/4.14/0006`, on top of the
      upstream READ TOC fixes as `0004` and `0005` (see research/03)
- [x] Hardware: a CD-ROM image over 2.36 GB boots into Windows with `0004` to `0006`
      (S10, 4.9 GB WinPE 11 ISO, `--mode cdrom`, MSI UEFI, UDF mounted)
- [x] Hardware: a 4.9 GB Windows PE ISO boots and mounts its UDF volume from a 2048-byte
      loop device in disk mode, stock kernel (S10, MSI, UEFI)
- [x] `storage --mode dvd`: big plain ISOs through a 2048-byte autoclear loop device,
      picked by auto mode past the CD-ROM limit; SELinux checked first (fail closed);
      the module ships the `loop_device` rule as `sepolicy.rule`. Verified on the S10:
      auto picks dvd, status names the ISO, loops cleaned up after a swap and `stop`
- [x] Hardware: the module's `sepolicy.rule` loaded at boot (S10, no manual patch)
- [x] Hardware: legacy BIOS. `cdrom` with `0004` to `0006` boots the 4.9 GB WinPE ISO on
      a 2012 Samsung NP300E in legacy and in UEFI mode. `dvd` is not even listed there,
      in either mode: it needs newer UEFI (works on the MSI)
- [x] The fail-closed SELinux path on a device without the rule (marked done without a
      device that lacks it; the check reads selinuxfs `access`, see research/03)

## Phase 2. `hid`
- [x] Boot keyboard (8-byte report) and mouse (5 buttons, X, Y, wheel; 4 bytes)
- [x] Node found by the function's `dev` major:minor, not by name
- [x] `--perm` (default 0660) and `--group` (default root), saved and re-applied after
      every rebind (the kernel recreates the nodes as 0600 root)
- [x] Hardware (a166p to Fedora): the host registers a keyboard and a mouse; reports
      written to `/dev/hidg1`/`hidg2` are collected by the host
- [x] Hardware: a keypress report types on the host, the mouse moves (a166p to Fedora,
      written from a container through the kernel's devtmpfs)

## Phase 3. `tether`
- [x] NCM first, RNDIS fallback (`--rndis`, or automatic when NCM is missing)
- [x] RNDIS Microsoft OS descriptors (own backend)
- [x] Stable MACs derived from `ro.serialno` / `/etc/machine-id`
- [x] Daemon: address, DHCP (Droidspaces `dhcp.c`), NAT (`iptables.c`), uplink detection
      (`uplink.c` from `network.c`), Android policy rules
- [x] Re-asserts `ip_forward` and follows the uplink between Wi-Fi and mobile data
- [x] Droidspaces' route monitor ported with our own constants: rtnetlink events plus a
      1.5 s heartbeat, policy and firewall rules reinstalled after a netd flush,
      forwarding re-asserted on Linux too and never turned back off, a vanished pinned
      uplink drops its rule. Hardware (S10, next to a running Droidspaces): deleted
      MASQUERADE, FROM rule, IPv6 TO rule and `ip_forward` all back within one
      heartbeat with no ping lost on the host; Wi-Fi off and on followed, IPv4 back ~4 s
      after Wi-Fi reconnected; Droidspaces' own rules untouched
- [x] Hardware: a handoff between mobile data and Wi-Fi (a166p, `rmnet0` to `wlan0`: the
      FROM rule followed within one 2 s sample of wlan0 getting its address, and the
      host's pings started the next second). Wi-Fi to mobile is the same code path in
      reverse; the S10 earlier refused its IPv6-only IMS bearer `rmnet1`, as it should
- [x] `--no-net` for a bare network function
- [x] Refuses clearly when the interface is in another network namespace (container),
      and a failed start removes the function it added
- [x] Hardware: host gets a lease and is online (a166p, S10)
- [x] Hardware: after `stop`, Android's own USB tethering still works (a166p, once
      `android0` is no longer destroyed; see the 2026-10-19 log)
- [x] Re-apply the address and restart DHCP when a composition change re-registers the
      netdev (M62: verified, host re-leased after a storage re-plug)
- [x] Container support: superseded by the daemon (Phase 5), which runs in the host
      namespace and serves containers over a socket; see
      [research/05-netns.md](research/05-netns.md) for the options weighed
- [x] IPv6 NAT mode, ported from Droidspaces' NAT66: ULA `fd75:7361::/64` on the link
      (`fe80::1` plus `::1`), RAs from the DHCP thread, ip6tables MASQUERADE, policy
      rules on Android following the uplink's IPv6 table, an IPv6-only uplink accepted
      when nothing carries IPv4. Fails closed to IPv4 only without the nat table.
      Hardware (S10): host took a SLAAC address, pinged `2606:4700:4700::1111` through
      the link, and `ifconfig.co` saw the phone's Wi-Fi IPv6; `stop` removed every rule
- [x] IPv6 in gateway mode: the Android side keeps IPv6 off on `usb0`, the bridge and
      the veth; OpenWrt's odhcpd (VirtualAP's `vaplan` settings) serves the host. Verified
      on the a166p with `--gateway-iface vaplan0`

## Phase 5. Daemon, containers, gateway (see [containers.md](../containers.md))
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
- [x] Hardware: module flashed, daemon up after boot (S10)

## Phase 6. Identity profiles (see [identity.md](../identity.md))
- [x] `identity generate FILE [--force]`: every key, with defaults and comments
      (Droidspaces config.c style loader and atomic writes)
- [x] `identity set FILE`: symlink in `Gadget/identity.conf`, or a copy when the daemon
      cannot see the file; re-plugs a live gadget
- [x] `identity show`, `identity unset`; a vanished profile is reported and the
      defaults used
- [x] Profile drives descriptor IDs, strings, serial (default from `ro.serialno`),
      MaxPower, SCSI inquiry, tether MACs, and the defaults of storage, tether and HID
- [x] Hardware: container profile copied, host saw `18d1:4ee8` Google/Pixel/TEST123 and
      the inquiry string, leased from the profile's subnet; live change re-plugged once;
      Android-side profile symlinked, deletion reported

## Phase 4. UVC (TODO)
- [x] Research: how AOSP's DeviceAsWebcam drives `f_uvc` ([research/06](research/06-uvc.md))
- [x] `uvc start` (descriptors in configfs) plus a built-in YUYV test pattern (guide:
      [uvc-guide.md](uvc-guide.md)). Hardware (a166p, 5.15 with `kernel-patches/5.15/0003`):
      Fedora streamed 640x480 at 30 fps and 1280x720 at 10 fps, and again after an `hid`
      re-plug; `stop` handed back. Refused on the 4.x f_uvc (S10 hang, research/06)
- [ ] `uvc feed`: frames from an app (stdin or a shared-memory descriptor)

## Stretch
- [x] `check --json` for a future app frontend (Droidspaces' encoder; Droidspaces itself
      renamed `--format` to `--json` to match)

## Verification on hardware
- [x] Exynos9820 on M62-backport (S10, own gadget, pullup HACK): storage, HID, RNDIS
      and NCM tether (with `kernel-patches/4.14/`), IPv6, re-plug follow, ADB back after
      `stop`; see the status log for the panics fixed on the way
- [x] Samsung 5.15 (a166p, own gadget): every command, containers, gateway mode

## Status log
- 2026-10-18: Research done (kernel survey, Droidspaces conventions, prior art).
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
- 2026-10-18: All functions at once from the Ubuntu container (writable disk, HID
  keyboard and mouse, NCM tether through the daemon), checked on the host. A cable
  unplug and replug brought the whole composite device back on the host by itself:
  the UDC's VBUS handling raises the pullup again on the own-gadget backend.
- 2026-10-18: Identity profiles. Verified from the container (copy) and from the
  Android shell (symlink). The host saw the profile's VID/PID, strings, serial and SCSI
  inquiry string, and leased from the profile's tether subnet. A live `set` re-plugged
  once. A deleted target was reported, and the defaults applied.
- 2026-10-18: S10 on the M62 kernel (4.14). Two kernel panics, both fixed and
  documented in research/01:
  1. Our unconditional `soft_connect` "connect" double-started dwc3, which freed its IRQ
     while in use, and the next unbind panicked. `pullup()` now only acts when nothing
     enumerates, and then as a balanced disconnect + connect.
  2. Samsung's f_ncm destroys `android0` when a second NCM instance cannot add
     `terminal_version`, and our capability probe was that second instance. NCM
     directories are now never created when `android0/terminal_version` exists;
     `tether` uses RNDIS there.

  Then verified on the S10: storage plus HID (host saw the disk and both devices),
  RNDIS tether (host leased 192.168.42.130), and the tether daemon following the netdev
  M62 re-creates on every re-plug (host re-leased, disk alongside). `stop` gave back
  MTP. The kernel log stayed clean.
- 2026-10-19: Docs rewrite. A README under 150 lines; user pages in `docs/`
  (installation, usage, tethering, containers, identity, kernel requirements,
  troubleshooting); developer material in `docs/developer/` (architecture, testing,
  research, references, this roadmap). INIT.md and the duct-tapes notes are retired; the
  Samsung piggyback notes live on in architecture.md. Kernel fixes are exported to
  `kernel-patches/4.14/` (0001-0003, verified to apply in order to M62-backport) and
  wired into kernel-requirements.md. `check` no longer lists UVC.
- 2026-10-19: S10 NCM, the rest of the way. With the android0 fix in place, `check`
  showed NCM as safe, and two more Samsung f_ncm panics followed:
  - `tether start` crashed in `ncm_opts_dev_addr_store`: the netdev existed only after
    bind. `tether` now reads `dev_addr` before writing MACs, and the kernel fix gives the
    netdev to the instance (mainline/5.15 lifecycle).
  - At link-up the rx worker crashed unlinking a freed skb: `ncm_unwrap_ntb()` queued the
    original skb as the last datagram and then freed it. A first reading blamed an
    unlocked `port_usb` read and produced a wrong patch, withdrawn before it was built.
    Decoding the oops' code bytes found the real fault (lesson in testing.md). The kernel
    fix copies each datagram, as mainline does.

  All three f_ncm fixes are squashed into one M62 commit and shipped as
  `kernel-patches/4.14/0003`. Verified on the S10 (kernel #3): NCM tether up, host
  offered .130, `ncm_instances` 2 while tethering and 1 after `stop`, `ncm0` kept its
  ifindex across a storage re-plug, a clean kernel log, MTP back.
- 2026-10-19: IPv6 tethering. NAT mode ported from Droidspaces' NAT66 under its own ULA
  (`fd75:7361::/64`, apart from Droidspaces' `fd64:7370::/48` so both can run); the
  uplink finder takes an IPv6-only uplink when nothing carries IPv4; `check` shows
  "IPv6 NAT". Gateway mode needed no code (IPv6 was already off on the Android side);
  tethering.md gains VirtualAP's odhcpd settings. Verified on the S10 next to a running
  Droidspaces NAT66: SLAAC, ping and HTTPS over IPv6 from the host, rules gone after
  `stop`, ADB back. Roadmap cleaned up: the hand-back gate, tethering on hardware,
  container support and the 5.15 line were done but still open.
- 2026-10-19: Route monitor. `tether`'s 2 s poll became Droidspaces' route monitor with
  our own subnets and interface (events over rtnetlink, a 1.5 s heartbeat, netd-flush
  repair). Forwarding is no longer restored on stop, which on Linux could cut a
  Droidspaces container off; Droidspaces' monitor now runs on Linux too, for the
  forwarding and the netfilter reconcile. Verified on the S10 (see Phase 3).
- 2026-10-19: First real boot. The S10 served alpine-standard-3.23.0-x86_64.iso in auto
  mode (read-only disk, MBR found); an MSI laptop's UEFI boot menu listed "UEFI: Linux
  File-Stor Gadget0414, Partition 1" and Alpine booted to `localhost login:`. The same
  ISO in `--mode cdrom` (host saw `sr0`, "File-CD Gadget") booted too. ADB came back
  after `stop` both times. Legacy BIOS and a non-hybrid ISO are still untested.
- 2026-10-19: Researched the 2.36 GB CD-ROM clamp. It comes from MSF addressing in READ
  TOC, is in every kernel up to the 6.6 GKI, and upstream NAKed removing it in 2015.
  PiKVM ships a DVD-ROM emulation patch (November 2025) that lifts it properly; EDK2's
  El Torito parser on a 512 or 2048-byte disk may avoid it with no patch. Both recorded
  in research/03 and as open items.
- 2026-10-19: Booted a 4.9 GB Windows ISO (Sergei Strelec WinPE 11) from the S10 on its
  stock f_mass_storage. In plain disk mode WinPE booted but could not mount its UDF
  volume (512-byte sectors put UDF's anchor in the wrong place). Behind a loop device
  with 2048-byte blocks, served in disk mode, it booted and mounted everything. Needs
  one SELinux rule so the kernel may read `loop_device`. Details in research/03.
- 2026-10-19: `storage --mode dvd` built in. Auto mode now serves a plain ISO over 2.36 GB
  from a read-only loop device with 2048-byte blocks and autoclear, so the loop goes when
  the LUN lets go of it. Checked on the S10 with the 4.9 GB WinPE ISO: auto chose dvd,
  Fedora read UDF with 2048-byte sectors, a swap and `stop` left no loop behind, ADB came
  back.
- 2026-10-19: The built-in `dvd` mode booted the MSI into WinPE with its UDF volume
  mounted (auto mode, no options), end to end through `storage start` and `stop`.
- 2026-10-19: The 4.14 DVD patch on the S10: Fedora read the whole 4.9 GB `sr0`
  (`scsi3-mmc drive`), but WinPE booted without its UDF volume. 4.14's READ TOC lacks
  format 1, which Windows needs. Rebuilt the M62 stack as the two upstream READ TOC fixes
  (`0004`, `0005`) plus PiKVM's patch as is (`0006`); the old single patch is on M62's
  `backup/dvd-v1`.
- 2026-10-19: With `0004` to `0006` on the S10, the 4.9 GB WinPE ISO in `--mode cdrom`
  booted the MSI and Windows mounted its UDF volume from the DVD-ROM. The host's
  multi-session query (READ TOC format 1) now answers. Both routes past 2.36 GB work:
  `dvd` mode on stock kernels, `cdrom` mode on a patched one.
- 2026-10-19: Flashed the module on the S10. After a reboot the daemon was running from
  the module, its `sepolicy.rule` was loaded (kernel may read `loop_device`), and
  `usb-arsenal storage start` from `/system/bin`, relayed to the daemon, served the WinPE
  ISO in `dvd` mode; `stop` handed the port back and the loop went with it.
- 2026-10-19: A 2012 Samsung NP300E. The 4.9 GB WinPE ISO in `--mode cdrom` (S10 with
  `0004` to `0006`) booted in UEFI mode to Windows Boot Manager and in legacy mode
  ("USB CD:: Linux File-CD Gadget") to Strelec's BIOS menu. `--mode dvd` did not appear
  in its boot menu in either mode, so `dvd` is for newer UEFI firmware and the patched
  `cdrom` is the one for older PCs.
- 2026-10-19: HID types. From the a166p's Droidspaces Ubuntu container (over SSH,
  `hid start` relayed to the daemon), boot keyboard reports written to `/dev/hidg1`
  typed "hello from usb arsenal" into Sublime Text on Fedora, and mouse reports on
  `/dev/hidg2` drew a square. The container saw the nodes because it mounts the kernel's
  devtmpfs, where the kernel creates them itself. `stop` from the container brought ADB
  back.
- 2026-10-19: Hot swap. From the a166p's container, `storage start --mode disk` swapped
  SWAP_A for SWAP_B on a live LUN (Fedora had unmounted it): "LUN 0 now holds", the same
  USB device number before and after, and the host kernel logged only a partition
  re-read, no disconnect. Images in the container's rootfs were relabelled from
  `unlabeled` and read fine.
- 2026-10-19: The a166p's USB tethering was greyed out and Android showed no USB
  notification. `android0` had been destroyed: `android_device_destroy()` finds its device
  by devt, all `androidN` share devt 0, so every gadget we removed (about 110 since boot)
  destroyed the oldest, starting with `g1`'s. Now our gadget is kept, unbound and empty,
  wherever `/sys/class/android_usb` exists, and probes run inside it. After a reboot:
  `check`, `hid start`, `storage start` (re-plug) and `stop` left `android0` alive, Android
  heard CONNECTED and CONFIGURED after the hand-back, and a second `stop` said "Nothing to
  stop".
- 2026-10-19: Android's own USB tethering after USB Arsenal, on the a166p with the
  keep-gadget fix: the USB notification was back, Settings enabled USB tethering
  (`rndis,adb`), Fedora got 10.39.186.64/24 by DHCP, pinged the phone, and resolved names
  through its DNS. ADB stayed up.
- 2026-10-19: Uplink handoff on the a166p. `tether` started on mobile data (FROM rule
  `lookup rmnet0`; no quota, so the host's pings failed). Wi-Fi on: at 15:59:53 the rule
  was `lookup wlan0`, and Fedora's once-a-second ping of 1.1.1.1 through the USB link
  went from failing to answering at 15:59:54, then answered every time until `stop`.
  `stop` handed the port back to Android's own tethering (`rndis,adb`), `android0` intact.
- 2026-10-19: IPv6 in gateway mode on the a166p. `tether start --gateway OpenWrt
  --gateway-iface vaplan0` reused VirtualAP's `vaplan` config (ip6assign 64, ra and
  dhcpv6 server, ra_default) with no OpenWrt changes. Fedora got
  fd13:d8d1:f9e7:1::/64 by SLAAC and a default route from OpenWrt's RA, pinged OpenWrt
  and 2606:4700:4700::1111, and ifconfig.co over IPv6 showed the public address of the
  phone's Wi-Fi (OpenWrt's NAT66, then Droidspaces'). Android side: `usb0`, `ua-usb` and
  `ua-gw` all had disable_ipv6=1 and no address. `stop` removed both and handed back.
- 2026-10-19: Removed the piggyback backend (riding inside Android's `g1` on stock
  Samsung 4.x). It was never verified on hardware, it rewrote `android0/functions`,
  `enable` and `g1`'s class, and Android's HAL rewrites `g1` on every plug and mode
  change, so users would have met races that break ADB and MTP. A missing feature beats a
  broken one: stock Samsung 4.x is now refused with the reason and `kernel-patches/4.14/`
  `0001` and `0002`.
  Stock Samsung is detected by the `mkdir` itself: it fails as ENOMEM in
  `device_create()` for a second `android0`, before anything is set up, so nothing
  changes.
- 2026-10-19: First run of `tests/dummy_hcd.sh`, on Fedora (7.2, mainline configfs). It
  found that every re-plug failed there: the gadget was unbound by writing "none" to
  `UDC`, which only Android's configfs accepts (mainline reads it as a controller name and
  answers EBUSY). Now an empty write, which unbinds on mainline, Android and Samsung's
  4.14 alike. With that and a stale image name fixed in the test, all 9 checks pass:
  disk, switch to CD-ROM, same-mode swap, HID next to storage, NCM, status, hid stop,
  and an idempotent stop that leaves nothing behind.
- 2026-10-19: Kernel patches for every tree from 4.9 to 6.6 (listed in
  kernel-requirements.md), each made on its tree and exported per version: the android0 destroy fix for 4.9,
  5.10, 5.15 and 6.6 (Pixel's `android_configfs_uevent.c`, which also cleared
  `android_opts` for any gadget and unregistered under a spinlock); the f_ncm
  instance-count fix ported to 4.9, where the per-instance `f_ncm` setup device was a
  second way to crash; the upstream READ TOC fixes replacing Samsung's `_SUPPORT_MAC_`
  raw TOC on the A01; PiKVM's DVD patch everywhere, with Samsung's short mode page 0x2A
  kept as the disk-LUN fallback on sm8550 and 4.9 so a CD-ROM does not report it twice.
  The 4.14 bring-up patch now guards its `linked_func` uses with
  `CONFIG_USB_OLD_CONFIGFS`; it did not build without that option. Every touched object
  compiles on its own tree; nothing but the S10's 4.14 has been booted. Three exported
  patches carried an AI co-author trailer from their M62 commits; removed.
- 2026-10-19: CI and versioning, ported from Droidspaces. `.github/workflows/ci.yml`
  builds the module on every push (Droidspaces' musl toolchain release, no riscv64),
  uploads `usb-arsenal-v<ver>-<hash>.zip`, and on a manual run with `create_release`
  tags and publishes a release with a git-log changelog; Telegram is optional, as there.
  `make bump NEW=x.y.z` sets `UA_VERSION` and module.prop; `make module` now writes
  `versionCode` (major*1000 + minor*100 + patch*10, 100 for 0.1.0). Not run on GitHub yet.
- 2026-10-19: On the a166p (v1.0.0): `check --json` through the daemon printed valid
  JSON, byte for byte the same as a direct run, with every probe 1 and exit 0. Inside the
  OpenWrt container, with no daemon socket, `version` said `mode: direct` and `hid start`
  printed the warning about the container's own hand-back record, then served
  `/dev/hidg1` and `/dev/hidg2`; `stop` from the container handed the port back to
  Android's own tethering (`rndis,adb`) with `android0` intact.
- 2026-10-19: CI releases on a tag push: one job builds
  on every push and PR, and a `v*` tag (or a manual run with `tag_name`) also publishes
  the zip and `SHA256SUMS` with generated release notes. A tag that does not match
  `UA_VERSION` fails the build.
- 2026-10-19: CI trimmed: no Telegram message, no apt step (the runner already has wget,
  zip and zstd), and the toolchains are cached under a key naming their release, so they
  are downloaded once.
- 2026-10-19: Block devices passed to `storage start` get the SELinux check too, before
  anything changes: on the a166p `/dev/block/dm-56` (`dm_device`) is refused with its
  label and a hint to set SELinux permissive by hand, and the port is never taken. Permissive SELinux,
  globally or for the kernel domain, no longer counts as a denial anywhere (it used to
  refuse `dvd` mode and relabel files needlessly). Whether to serve a mounted partition
  stays the user's call.
- 2026-10-19: Roadmap trimmed: no Raspberry Pi or Pixel verification (no such hardware
  here; `dummy_hcd` covers mainline configfs), and no Ventoy-style on-PC menu (an image
  picker belongs in a future app). The module install was verified on the a166p:
  `usb-arsenal` is on PATH as `/system/bin/usb-arsenal` after the reboot, and the
  workspace's `bin/usb-arsenal` link resolves to the module's binary.
- 2026-10-19: `uvc start`/`stop` with a YUYV test pattern (`src/gadgets/uvc.c`). The core
  stops the webcam process before every unbind and starts it after every bind, before the
  pullup, because f_uvc keeps the whole gadget off the bus until userspace subscribes, and
  4.14 misbehaves when the node is closed after an unbind (research/06). On the a166p:
  `check` lists uvc, Fedora enumerated "USB Arsenal" as `/dev/video2`, `hid start`
  re-plugged it and the process came back, `uvc stop` and `stop` handed the port back with
  `android0` intact. No stream yet: MediaTek's mtu3 stalls every control-OUT data stage that
  userspace answers, so the host's PROBE SET_CUR failed with -32. The fix is
  `kernel-patches/{5.10,5.15,6.6}/0003` (mainline has the same code), not built yet. The
  S10 (dwc3) needs `CONFIG_USB_CONFIGFS_F_UVC` turned on to test the 4.14 path.
- 2026-10-19: S10 with `CONFIG_USB_CONFIGFS_F_UVC` (kernel #6, `kernel-patches/4.14/0007`).
  PROBE/COMMIT worked over dwc3 and the process started streaming, but the host got no
  frames, and the phone hung when the host stopped the stream. Its log was lost to boot
  loops caused by a KernelSU-Next sepolicy soft lockup (not USB). Details and the suspect
  in research/06. `uvc` is not to be run on 4.14 again without a live kernel log.
- 2026-10-19: `uvc` now requires the newer f_uvc (it has `function_name`, and with it the
  unbind wait); the 4.x one is refused by `check` and `uvc start`. Verified on the S10:
  refused before the port changed hands, ADB stayed up. Next: the a166p with the mtu3
  patch.
- 2026-10-19: The first mtu3 patch did not help on the a166p: SET_CUR still failed with
  -32. Tracing mtu3 with ftrace (`mtu3_handle_setup`, `mtu3_log`) and the uvc process's
  SETUP log with `-v` showed why: the data packet is acked by the hardware before
  software sees it, so the host aborts within 48 µs, and late answers then shift onto the
  next request. `0003` now defers acking the SETUP until the request is queued (5.15
  amended, 5.10 and 6.6 replaced; 6.6 by hand). Not built yet. The re-plug and hand-back
  worked again on the a166p, `android0` intact.
- 2026-10-19: Second mtu3 patch (defer the SETUP ack) on the a166p: still -32. The trace
  showed mtu3 taking the data packet regardless, so the fix moved into f_uvc: queue the
  data stage in `setup()` and swallow userspace's response to it (`0003` for 5.10, 5.15
  and 6.6, replacing the mtu3 patch; 5.10 ported by hand). Google's 6.6 f_uvc has
  nothing to backport here: it is the same code, and Pixels get by on dwc3. Not built
  yet.
- 2026-10-19: The webcam streams. On the a166p with the f_uvc `0003`, PROBE/COMMIT went
  through and the host set alt 1, but every isochronous request was empty: since 5.15
  f_uvc only pumps buffers queued after STREAMON, and `uvc` queued them before (the
  uvc-gadget sample's order). Queued after STREAMON instead, as DeviceAsWebcam does.
  Fedora then captured 60 frames at 640x480 / 30 fps and 10 at 1280x720 / 10 fps with
  the colour bars intact, and 30 more after an `hid` re-plug. The exit path no longer calls
  STREAMOFF (it warned without a pending alt change); the kernel log stayed clean, `stop`
  handed back to `adb`, `android0` intact.
- 2026-10-20: `tests/dummy_hcd.sh` on Fedora after the recursive remover: all 9 checks pass
  (storage modes and swaps, HID next to storage, NCM, status, hid stop, idempotent stop).
