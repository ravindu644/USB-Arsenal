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
- [x] Piggyback backend: functions in `g1`, selected via `android0/functions`, `enable`
- [x] Teardown restores the previous owner; every failure after `begin` routes through it
- [x] Stop signals held during the handoff; SIGHUP/SIGPIPE ignored so a dying adb shell
      cannot interrupt it
- [x] `check`, `status`, `stop` (idempotent, doubles as crash recovery)
- [x] `tests/dummy_hcd.sh`: end-to-end on a Linux PC through dummy_hcd. Written, not run
      yet (needs root on the dev machine)
- [x] Android detected from the kernel (`g1` + `/sys/class/android_usb`), not `/system`,
      so it works from inside a container; `g1` rebound by hand where `setprop` is absent
- [x] Gate on hardware: `stop` gives ADB back; `kill -9` mid-bind, then `stop`,
      recovers. a166p (5.15): `g1` rebound, `kill -9` recovered, `adb devices` saw the
      phone again. S10 (M62 4.14): `stop` after an NCM tether gave back
      `mtp,conn_gadget,adb` and ADB returned
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
- [ ] Hardware: the fail-closed SELinux path on a device without the rule
- [ ] Stretch: Ventoy-style on-PC menu

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
- [ ] Hardware: the Wi-Fi to mobile data handoff (the S10 has mobile data off; with
      Wi-Fi off the monitor rightly refused the IPv6-only IMS bearer `rmnet1`)
- [x] `--no-net` for a bare network function
- [x] Refuses clearly when the interface is in another network namespace (container),
      and a failed start removes the function it added
- [x] Hardware: host gets a lease and is online (a166p, S10)
- [ ] Hardware: after `stop`, Android's own USB tethering still works
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
- [ ] IPv6 in gateway mode: the Android side keeps IPv6 off on `usb0`, the bridge and
      the veth (done); the OpenWrt side uses VirtualAP's odhcpd settings, documented in
      tethering.md, not yet tried on hardware

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
- [ ] `f_uvc` plus a userspace frame feeder, after the kernel's `uvc-gadget` sample

## Stretch
- [ ] `check --json` for a future app frontend
- [ ] Verify on a Raspberry Pi (`dtoverlay=dwc2`)
- [ ] Verify on a Pixel (gadget HAL that rebinds `g1` on its own)

## Verification on hardware
- [x] Exynos9820 on M62-backport (S10, own gadget, pullup HACK): storage, HID, RNDIS
      and NCM tether (with `kernel-patches/4.14/`), IPv6, re-plug follow, ADB back after
      `stop`; see the status log for the panics fixed on the way
- [ ] Stock Samsung 4.14/4.19 (piggyback)
- [x] Samsung 5.15 (a166p, own gadget): every command, containers, gateway mode

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
- 2026-10-18: All functions at once from the Ubuntu container (writable disk, HID
  keyboard and mouse, NCM tether through the daemon), checked by the user. A cable
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
- 2026-10-20: A 2012 Samsung NP300E. The 4.9 GB WinPE ISO in `--mode cdrom` (S10 with
  `0004` to `0006`) booted in UEFI mode to Windows Boot Manager and in legacy mode
  ("USB CD:: Linux File-CD Gadget") to Strelec's BIOS menu. `--mode dvd` did not appear
  in its boot menu in either mode, so `dvd` is for newer UEFI firmware and the patched
  `cdrom` is the one for older PCs.
- 2026-10-20: HID types. From the a166p's Droidspaces Ubuntu container (over SSH,
  `hid start` relayed to the daemon), boot keyboard reports written to `/dev/hidg1`
  typed "hello from usb arsenal" into Sublime Text on Fedora, and mouse reports on
  `/dev/hidg2` drew a square. The container saw the nodes because it mounts the kernel's
  devtmpfs, where the kernel creates them itself. `stop` from the container brought ADB
  back.
- 2026-10-20: Hot swap. From the a166p's container, `storage start --mode disk` swapped
  SWAP_A for SWAP_B on a live LUN (Fedora had unmounted it): "LUN 0 now holds", the same
  USB device number before and after, and the host kernel logged only a partition
  re-read, no disconnect. Images in the container's rootfs were relabelled from
  `unlabeled` and read fine.
