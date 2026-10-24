# AGENTS.md

Instructions for AI coding agents working in this repository. Read
[docs/developer/architecture.md](docs/developer/architecture.md) for how the code fits
together and [docs/developer/ROADMAP.md](docs/developer/ROADMAP.md) for what is done and
what is open. This file holds the house rules and the facts you must not relearn the hard
way.

## What this is

USB Arsenal is a root tool, written in C, that builds a **composite USB gadget** through
configfs on Android and Linux. It provides mass storage (ISOs as a disk or CD-ROM,
writable drives), tethering (NCM/RNDIS with DHCP and NAT, or an OpenWrt gateway), and HID
nodes, all at once. It only drives the kernel's gadget subsystem: create functions, set
attributes, link, bind, raise the pullup, and always hand the controller back so the
phone's own USB (ADB/MTP) returns.

A root daemon on the Android side owns the port. Every other copy of the binary,
including one in a container, relays its command line to it over a filesystem socket.

## Build

- `make native`, `make aarch64`, `make armhf`, `make x86_64`, `make x86`: static musl
  builds into `output/usb-arsenal`. Toolchains are found as `<triplet>-gcc` in
  `$MUSL_CROSS`, `~/toolchains/<triplet>-cross/bin`, `PATH` or `/opt/cross/bin`.
- `make module`: every architecture in a KernelSU/Magisk zip (`module/`).
- `make format`: clang-format over `src/`. Bare `make` prints help.
- **Version:** `UA_VERSION` in `usbarsenal.h` is the only source. `make bump NEW=x.y.z`
  rewrites it and `module/module.prop`; commit that alone as `usb-arsenal: bump vX.Y.Z`.
  The built versionCode, in the module zip and the APK alike, is
  (major*1000 + minor*100 + patch*10) * 10000 + the commit count, as Droidspaces does, so
  every commit's build updates the last; CI checks out the full history for it.
- **CI** (`.github/workflows/ci.yml`): every push
  and pull request builds the module zip with Droidspaces' published musl toolchains,
  cached between runs, and the app's release APK.
  Pushing a `vX.Y.Z` tag (or a manual run with `tag_name`) also publishes a release with
  the zip, the APK, `SHA256SUMS` and generated notes. The tag must equal `UA_VERSION`, so bump
  first: `make bump NEW=X.Y.Z`, commit, then `git tag vX.Y.Z && git push --tags`.
- **The app's signing key** is Droidspaces': `droidspaces.keystore` at the repo root and its
  passwords in `Android/local.properties` (see `local.properties.example`), both gitignored;
  CI takes them from the `RELEASE_KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD` secrets. Without them a release is signed with the debug key. Releases are
  shrunk with R8.
- **No wildcard SRCS**: add each new `.c` to the list in the Makefile.
- Header dependencies are tracked (`-MMD`), so a header edit rebuilds what includes it.
- clangd reads `compile_flags.txt`.

## Layout

| Path | What lives there |
|---|---|
| `src/include/usbarsenal.h` | the only header a `.c` includes (plus `src/net/net.h` for networking) |
| `src/gadget.c` | the core every command goes through: open, begin, add, commit, teardown |
| `src/gadgets/` | the commands: storage, hid, tether |
| `src/daemon.c` | the daemon and the client relay |
| `src/config.c` | identity profiles |
| `src/check.c` | capability probing |
| `src/net/` | lifted from Droidspaces; keep its `ds_` names so it can be re-synced |
| `kernel-patches/<kernel-version>/` | `git format-patch` exports of kernel fixes; list each in `docs/kernel-requirements.md` |
| `DESIGN.md` | the Android app's design language: tokens, components, copy rules |
| `docs/developer/design/` | the reference screens and `lcd.css`, the shared stylesheet |

Two more facts:
- **The workspace** is `/data/local/USB-Arsenal` on Android and `/var/lib/USB-Arsenal` on
  Linux and in containers. Use `ua_ws_path()`; never invent another state path.
- **Never assume Android from `/system`.** Whether Android owns the controller is decided
  from the kernel (`g1` plus `/sys/class/android_usb`), so the tool also works inside a
  container.

## Kernel traps that panicked a phone: never regress these

Both are in kernel code that a plain configfs or sysfs write reaches. Details are in
[docs/developer/research/01-kernel-survey.md](docs/developer/research/01-kernel-survey.md).

- **Never write `connect` to `soft_connect` unless the UDC `state` is still "not
  attached", and then only as `disconnect` followed by `connect`.** On 4.14 cores
  "connect" starts the controller unconditionally. A started dwc3 then frees its own IRQ
  while in use, and the next unbind panics.
- **Never create an NCM function, not even to probe, when
  `/sys/class/android_usb/android0/terminal_version` exists without `ncm_instances`.**
  Samsung's f_ncm then destroys the shared `android0` and the kernel BUGs. Use RNDIS.
  `ua_ncm_unsafe()` is the one place that decides.
- **Never remove a gadget directory (ours or a probe) while `/sys/class/android_usb`
  exists.** Every `androidN` device has devt 0 and `android_device_destroy()` finds its
  device by devt, so any `rmdir` of a gadget destroys `android0`, which is `g1`'s. Android
  then hears no USB state until a reboot: no USB notification, tethering greyed out.
  `keep_gadget()` in `src/gadget.c` keeps ours, unbound and empty. `kernel-patches/`
  fixes the kernel for 4.9 to 6.6 (on 6.6 in `android_configfs_uevent.c`). Seen on the
  a166p (5.15).
- **Never write `dev_addr`, `host_addr` or `qmult` to a network function without
  reading `dev_addr` first.** Samsung's f_ncm has no netdev before bind, and its stores
  dereference that NULL. The read is safe everywhere, and failing means skip
  (the f_ncm patch in `kernel-patches/` for 4.9, 4.14 and 4.19 fixes the kernel).

## Testing on a device

- **Run `adb devices` first, every time.** If no line ends in `device`, stop and say so.
- **ADB drops while the gadget holds the port, wireless ADB too:** taking the port sets
  `sys.usb.config none`, which stops `adbd` (seen on the a166p), and handing it back restarts
  it. Run each test as one background `nohup` script that ends in `usb-arsenal stop` and logs
  to a file (`screencap` and `input tap` work from it), and read the file afterwards over USB
  ADB, which is back once Android holds the port again.
- **Check `dmesg` after every step**, not only at the end. The warnings come before the
  panic.
- **After a panic, read `/proc/last_kmsg` and `/sys/fs/pstore/*` before anything else,**
  then read the kernel source at the call trace. Never guess.
- **`usb-arsenal stop` is the recovery path.** A test that cannot end in a working
  `stop` is not finished.
- **Keep the test PC's own connection off the USB link** (a `never-default` profile), or
  a gateway test stalls the machine running it.
- Without a phone: `sudo tests/dummy_hcd.sh`. More in
  [docs/developer/testing.md](docs/developer/testing.md).

## Commits

- Run `make format` before committing any change to a `.c` or `.h` file.
- Sign off every commit: `git commit -s`.
- **Never add a `Co-Authored-By:` trailer for an AI agent** in this repository. Human
  co-authors are fine.
- Prefixes, matching the style Droidspaces uses: `fix:`, `refactor:`, `feat:`, `docs:`,
  or a subsystem name (`gadget:`, `storage:`, `net:`, `hid:`, `daemon:`, `check:`). Use
  `fix(security):` for anything security related.

## Style

Four rules. They apply to code, comments, commit messages, and documentation.

**No em-dashes.** Use a comma, a full stop, or rewrite the sentence.

**No ASCII banner comments.** No rows of `-----`, no `=====`, no boxed section headers.
An SPDX licence block at the top of each file is fine.

**Comments sound like a person.** Say why the code does something, or what breaks if it
does not. Skip comments that restate the line below them.

```c
/* Bad: write 1 to the UDC */
echo_udc(udc);

/* Good: Android's bind HACK leaves D+ down, so assert the pullup ourselves after bind */
udc_soft_connect(udc);
```

**Ten lines that work beat a hundred that do the same thing.** Delete before you add.
A smaller diff in the right place is the goal, not a smaller diff anywhere.

## Before you write it at all

Work down this list and stop at the first answer that holds.

1. Does this need to exist? A flag nobody asked for, a knob for a value that never
   changes, an abstraction with one implementation: skip it and say so in one line.
2. Does it already exist here? Grep first.
3. **Does the kernel already do it?** This is the big one for USB Arsenal. The gadget
   subsystem, configfs, `f_mass_storage`, `f_hid` and `f_ncm`/`f_rndis` already
   implement the hard parts. Use the kernel's interface; do not reimplement a protocol in
   userspace. Read the kernel sources (the trees in
   [research/01](docs/developer/research/01-kernel-survey.md) are a start) before writing a
   line.
4. Does an installed dependency or libc cover it? Never add a dependency for a few lines.
5. Can it be one line? Then it is one line.
6. Only then write the smallest thing that works.

Two answers work? Take the higher one and move on.

The list shortens the solution, never the reading. Trace the flow the change touches
before you pick a rung. A small diff in the wrong place is a second bug, not a lazy fix.

Never simplify away input validation at a trust boundary, a fail-closed check, error
handling that would leave the phone's USB broken, or anything the requester asked for by
name.

## Hard constraints

- **The kernel floor is 3.18 built with `CONFIG_USB_CONFIGFS`.** Stock 3.18 ships the
  legacy android gadget instead, and `check` refuses it with the reason. The top end is
  the latest GKI. Detect capabilities at runtime (which functions the kernel built, the
  UDC, patched or stock configfs); never hardcode a kernel-specific path.
- **Never hardcode the UDC name.** Read it from `/sys/class/udc`; require `--udc` when
  there is more than one.
- **Teardown must always restore the previous owner.** A crash or error path that leaves
  the UDC unbound kills ADB and MTP. The hand-back record stays until Android's gadget
  is really bound again. This is the one thing that must never break.
- **Never unbind someone else's gadget off Android.** Name it and refuse.
- **Root is required, and the daemon accepts only root or the `usb-arsenal` group** (gid
  3460). Fail closed and loud when a configfs path, a permission or the workspace is
  wrong.

## Documentation

- **User pages** live in `docs/` (installation, usage, tethering, containers, identity,
  kernel requirements, troubleshooting).
- **Developer material** lives in `docs/developer/`: architecture, testing, research
  notes, references and the roadmap.
- **Record findings and decisions there as you make them,** not in your head. Tick
  `docs/developer/ROADMAP.md` and add a dated line to its status log.
- **The README stays under 150 lines and claims nothing that was not verified on
  hardware.**

## Designing the app

- **[DESIGN.md](DESIGN.md) is the law** for anything the app shows. Read it before any UI
  work and again before you call it finished. Closed world: two colours (`lcd`, `ink`),
  three faces (Jersey 10, Silkscreen, VT323), four sizes (104, 72, 22, 16), square corners,
  stepped motion. If a value is not in the file, the file is telling you not to want it.
- **Start from what exists.** Take the rules from `docs/developer/design/lcd.css` and the
  closest screen in `docs/developer/design/`. Never restyle a component that already has a
  class there.
- **Show only what the backend does.** Before a control, a status line or a number goes on
  a screen, grep `src/` for the command or flag behind it. If the app does it on its own
  (the image folder, Create, HID input), it is app-side; say so in DESIGN.md's Backend truth
  table. No UVC, pending queue, host port, lease countdown or uptime until the C side has it.
- **Say it once.** A fact appears once per screen. No status line that repeats what an
  element shows (`host sees dvd`, `serving`, `configured`), no note beside a title that
  echoes the box below it, no `by arsenal`, no subtitle explaining a control, nothing in the
  top bar beside `< BACK`. Helper text only prevents a mistake; warnings only state a
  consequence.
- **Research before using a platform API.** Read the Android developer docs for any
  animation, haptics, focus or input API before writing it, and record its API level and
  fallback in DESIGN.md. Never guess a constant's API level.
- **Motion and haptics come from DESIGN.md's tables.** Stepped easing on the 40ms frame
  clock, the event map for haptics, the device tiers for rich effects. Never use
  `createOneShot` or a hand-made waveform for touch feedback. With reduced motion, every loop
  shows its resting frame.
- **Fast work is paced so it can be read.** Nothing appears in a single frame: titles type,
  shells play their session (DESIGN.md's Shell session row: Root, Check, Backend).
  The pacing comes from the beat between lines, not from slow typing, and the keys never run
  ahead of what the screen says. Check a change frame by frame in a `screenrecord`, not by eye.
- **A screen never re-lays itself out when work finishes.** It shows its final layout from
  the start (a disabled key where a key will be, an idle prompt where output will go), and a
  line already drawn never moves.
- **Never ask root for what the app knows itself** (its own version, the phone's codename),
  and never ask twice: every read is cached across launches with the boot id it was read in
  (`backend/Cache.kt`), the first frame draws from the cache, one live read per launch corrects
  it, and Back never re-runs a probe; only Recheck, Reinstall, a controller change or an identity
  command does. The activity handles configuration changes itself, so a rotation or a gamepad
  connecting never cancels a su request.
- **The setup is the backend's.** What each function starts with lives in
  `Gadget/setup.conf`, what runs in `Gadget/running.conf`, both written by the C side
  (`src/setup.c`); the app edits the setup through `setup set`, starts with a bare `start`, applies
  with `<function> apply`, and reads everything back in one `status --json`. Never keep a setup
  or a "what runs" record in the app: `docs/developer/research/06-app-state.md` is why.
- **One status loop,** in `App()`, with ordered reads: a read begun while a command runs is
  skipped, and a result is dropped when a newer one was applied or a command finished since it
  began. A failed read keeps the last status; after three the bar says `no answer from usb-arsenal`.
- **Every screen works without touch.** Before a screen is done, walk it with
  `adb shell input keyevent` (`KEYCODE_DPAD_*`, `KEYCODE_ENTER`, `KEYCODE_BUTTON_A`,
  `KEYCODE_BUTTON_B`, `KEYCODE_TAB`) and confirm every control is reachable and the focus
  cursor is visible.
- **Done means:** `npx @google/design.md lint DESIGN.md` reports no errors, every screen
  renders at 390x844 with nothing clipped and the bottom 48px empty, and a grep for the em
  dash finds nothing. A change to the language goes into DESIGN.md and `lcd.css` in the
  same commit.

## Android app

- **Where and what:** `Android/`, a Kotlin and Jetpack Compose app, package
  `com.usbarsenal.app`, minSdk 26. Compose foundation only: no Material component is
  visible, every widget is ours and follows DESIGN.md. The version comes from `UA_VERSION`.
- **Never run root commands through one shared shell.** One stuck command would freeze every
  other one behind it. Each operation runs in its own short-lived root shell with a timeout,
  and the shell is killed (not just abandoned) on timeout or cancel. Commands that change the
  gadget go through one ordered lane, so two never race; a timeout kills that command's shell
  and frees the lane, so the lane cannot stall. Reads (`status --json`, `check --json`, log
  tails) use their own shells and never wait on the lane.
- **The installer is ported from Droidspaces** (`BinaryInstaller`, `ModuleInstaller`,
  `DeviceArch`, the backend checker), restyled to DESIGN.md. The boot module is `module/`.
  The binary lives in the workspace (`/data/local/USB-Arsenal/bin/usb-arsenal`); the module
  only starts it. `/data/adb/modules/usb-arsenal/system/bin/usb-arsenal` is a link to it, there
  only while the user wants `usb-arsenal` on PATH (Droidspaces' `SymlinkInstaller`; the zip
  makes it, the app's Settings switch makes or removes it). The install is the binary,
  then the module, and every step's real output goes to the screen. It never starts or stops
  the daemon: daemon mode is a Settings switch that writes `1` to
  `/data/local/USB-Arsenal/.daemon_mode`, and the boot module starts the daemon on it
  (Droidspaces' marker).
- **Never patch the live SELinux policy** (`ksud sepolicy patch`, `magiskpolicy --live`): root
  detectors watch for it. `sepolicy.rule` loads at boot; when the module had none before an
  install, the install says to reboot.
- **Test over wireless ADB** while nothing is bound; a test that binds the gadget runs as the
  background script above, since `adbd` stops for as long as the gadget holds the port.
- **Test haptics on more than one phone:** one with rich haptics, one midrange, and once with
  touch feedback switched off.
