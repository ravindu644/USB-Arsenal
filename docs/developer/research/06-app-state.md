# 06. The app's state: map, audit, and the unified architecture

Written 2026-10-24 at commit `6ac7fba`, against the Kotlin under
`Android/app/src/main/java/com/usbarsenal/app/` and the C in `src/`. The reasons: the user saw
Home say "storage off" under a bottom bar that said "bound" while the PC had a drive on LUN0;
Settings and other screens draw their controls only once root has answered; and every setup
(the picks, the tether plan, the HID interval, the controller) lives only in the app's
`SharedPreferences`, so clearing the app's data loses it all.

Part 1 is the map. Part 2 is the audit (what the parallel scan found, verified, and what it
cleared). Part 3 is the architecture that fixes it.

## Part 1: the map

### 1.1 Navigation

- **The back stack** is `stack: List<Step>` in `App()` (`ui/Setup.kt`), `rememberSaveable`,
  seeded with `HOME` when prefs `setup/done` is true, else `WELCOME`. `push` and `pop` go
  through `show()`, which refuses a second change while one is in flight (`showing`), clears
  focus, waits two frames (so the pressed key is seen lifting), then sets `back` and `stack`.
  `Redraw` (`ui/Parts.kt`) paints the new screen in four bands and keeps the old one composed
  underneath until the last band; it only swallows touches on it.
- **Steps:** `WELCOME, ROOT, CONTROLLER, CHECK, INSTALL, HOME, LOG, SETTINGS, FONTS, STORAGE,
  TETHER, HID, KEYS, QUACKY, IDENTITY`. Setup is `stack.first() != HOME`. `home()` writes
  `setup/done` and resets the stack to `[HOME]`.
- **Focus return:** each opener carries `Modifier.opener(id)`; `ReturnFocus.focused` is the id
  holding focus now, `openers[i]` (also `rememberSaveable`) is the id that held focus on
  `stack[i]` when `stack[i+1]` was pushed, and a pop sets `ReturnFocus.returning` so that
  opener asks for focus once. `Sheet` does the same for the key that opened it.
- **What survives what:**

| Lifetime | State |
|---|---|
| Rotation (Activity recreated, process alive) | everything: `rememberSaveable` and every process object |
| Process death (stack restored, objects gone) | `stack`, `openers`, each screen's `rememberSaveable` (`lun`, `nextMode`, `creating`, `tab`, `full`, sheet `asking`, `picked` on Controller). Lost: `Setup.*` (root, check, report, install log and phase), `Gadget.*` (status, caps, profile, busy, fault, cli, `startedX`, `binds`), `Controller.udcs`, `DaemonLog`, `Picks`/`TetherSetup`/`HidSetup` until some screen calls `load` |
| A data wipe or reinstall | the prefs below; nothing on the backend side records a setup |

- **Who loads the prefs objects:** `Picks.load`, `TetherSetup.load`, `HidSetup.load` run in
  `HomeScreen`'s `LaunchedEffect(Unit)`; `StorageScreen` also calls `Picks.load`, `TetherScreen`
  `TetherSetup.load`, `HidScreen` `HidSetup.load`. Each is guarded by a `loaded` flag. The first
  frame of any of these screens composes before the effect runs. `Controller.load` runs in
  `MainActivity.onCreate`, so the pick is there before the first command.
- **Launch effects in `App()`:** on a `HOME` stack, `Controller.refresh()` (one root read) and,
  with several controllers and no pick, a push of `CONTROLLER`; the bind thud watches
  `Gadget.binds`.

### 1.2 State holders

| Holder | Lives | Written by | Read by |
|---|---|---|---|
| `Gadget.status: GadgetStatus?` | process | `Gadget.refresh()` only: every `PollStatus` loop (2 s, while STARTED) and `Gadget.run` after each command; set to `null` on any failed read | Home rows and bar, `pending`, `apply`, `isOn`, Storage `live`/`served`/`shownLive`, Tether chain and counters, Hid nodes, KbdMouse writers (through its own `hid` copy), Identity `known`/`bound`, Settings' controller note |
| `Gadget.caps: Capabilities?` | process | `CheckScreen` on success; `Gadget.loadOnce()` (`check --json` on the change lane) when null; Settings sets it to null after a controller pick | `rowLines` (unavailable rows), `has`, `tetherFunction`, Tether segment `enabled`, `HidSetup.fixed` |
| `Gadget.profile: Map` | process | `loadOnce()` when empty; `reloadProfile()` after identity commands; Settings on every visit | `tetherFunction`, Tether placeholders, Settings' Identity row, Identity `active`/`chosen` seed |
| `Gadget.busy: Busy?` | process | `Gadget.run` (set before the command, cleared after its post-read) | Home's bar and rows, `canApply`, every DIP's `enabled`, Identity `canApply` |
| `Gadget.fault: Fault?` | process | `Gadget.run` on failure, `Storage`'s picker refusal; cleared on the next `run` and when Log opens | the Fault line under the row or on the screen that owns `fn` |
| `Gadget.binds`, `fails`, `wasBound`, `finished` | process | `run`, `refresh`, `settle` | the thud in `App`, Pin in the bottom bar |
| `Gadget.backend: Backend?` | process | Home's `LaunchedEffect(Unit)` once per Home composition | `holds()` (DIPs, Apply), the attention key, the NoRoot shade; Settings reads its own copy |
| `Gadget.startedPicks / startedPlan / startedInterval`, `applying` | process | `flip` on a successful start (the setup taken when the start was sent), `apply` at its end, `forgetStart` on stop, stop all, a failed apply | `pending`, `apply` (`before`), `discard`, Storage's `shownLive` |
| `Gadget.cli` | process | `run` | Log's CLI tab |
| `Picks.all` | prefs `storage/picks` (tab-separated `lun mode path` lines) | Storage's file rows, Create, eject, mode segments; `discard` | Home's switch (`startable`), `setupArgs`, `pending`, Storage's strip and Drive |
| `TetherSetup.plan` | prefs `tether` (8 keys) | Tether's rows and sheets; the gateway auto-off on leave and on "Droidspaces not installed"; `discard` | `setupArgs`, `pending`, Tether's rows and chain fallbacks |
| `HidSetup.interval`, `speed`, `natural`; `keyboardFixed` (process) | prefs `hid`; `keyboardFixed` from every status read | Hid's steppers, KbdMouse's Speed and Natural scroll | `setupArgs`, `pending`, `fixed`, the report scaling |
| `IdentitySetup.chosen`, `edited`, `undo` | process, seeded from `Gadget.profile` | Identity picks, edits, apply, discard | Identity's panel, rows, sheet |
| `Controller.picked`, `udcs` | prefs `usb/udc`; `udcs` process | `Controller.refresh` (clears a pick no longer listed or when one controller), Controller step, Settings' choice | `udcFlag` on every command and read, Settings' USB section, setup's step count |
| `Setup.root`, `check`, `report`, `staged`, `log`, `phase`, `retries` | process | Root, Check, Install screens | the same screens on a revisit |
| `Switches.motion`, `haptics` | prefs `switches` | Settings | the theme, every loop, `Haptics.allowed()` |
| `DaemonLog.lines`, `offset` | process | Log's Daemon tab | Log |
| `SheetsOpen.count`, `ReturnFocus`, `Stick.sink` | process | `Sheet`, `opener`, KbdMouse | `Screen`, `opener`, `MainActivity` |
| Per-screen `remember`: Storage `dir`, `files`, `free`, `reload`; Tether `ds`, `choosing`; Settings `backend`, `daemon`, `linked`, `changed`, `fault`, `choosingUdc`; Identity `dir`, `profiles`, `sheet`; KbdMouse `hid`, `buttons`, `mods`, `stick` | screen | their screen | their screen |

### 1.3 Root calls

`Root` is one `RootExec`, `su --mount-master -c`. Three lanes: `read` (own shell, 15 s
default, never waits), `change` (one `Mutex`, 30 s default), `stream` (a shell kept open
until closed). Every call starts its own `sh`, prints its pid first, and a timeout or cancel
kills the whole tree. Reads of the backend carry `UA_NO_PROXY=1` so they never go through a
daemon and never show up in its log.

| Call | Lane, timeout | Caller | In flight | Failed |
|---|---|---|---|---|
| `status --json` | read, 15 s | `PollStatus` every 2 s on Home, Storage, Tether, Hid, Identity, Keys + mouse; `Gadget.run` after each command | nothing shown | `status = null`: Home idle, bar "Port with Android", rows off, Storage `live` empty, Identity `known` false |
| `check --json` | change, 30 s | `loadOnce()` on every `PollStatus` mount while `caps == null` | rows show as available (`caps == null` counts as yes) | `caps` stays null, retried at the next screen |
| `check` then `check --json` (staged binary) | read, 30 s | Check screen | the shell types; `Recheck`/`Next` dead until the report has typed | the report shows what printed and why it stopped |
| `identity show` | read, 15 s | `loadOnce()` while `profile` empty; `reloadProfile()`; Settings on each visit | Settings' Identity row says `Built-in` | `profile` stays as it was (`reloadProfile`), or empty (`loadOnce`) |
| `id -u; su -v` | read, 15 s | Root screen | the shell waits on `su` | the denial prints, NoRoot shade |
| `/sys/class/udc/*` list | read, 15 s | `Controller.refresh` from `App`, Root, Controller | setup's steps show 3 | nothing changes, nothing asked |
| `test -x …; module.prop; version; md5sum` | read, 15 s | `backendState` from Home once, Settings each visit, after install | Home: no attention key, DIPs held (`holds()` true while null); Settings: Reinstall dead | `NO_ROOT` on 127 or a timeout with no `bin` |
| `cat .daemon_mode`, `test -L link` | read | Settings each visit | both DIPs disabled | both DIPs stay disabled |
| `echo N > .daemon_mode`, `ln -sfn`/`rm -rf system` | change | Settings | nothing | Fault under the switches, shake |
| `d show --json` (Droidspaces) | read | Tether each visit | Gateway row shown (`ds == null` counts as installed), Container row says the saved name | `running = null`: Container says the saved name, the sheet says it cannot read |
| `mkdir -p; readlink -f`, `stat` per file, `stat -f` | read | Storage on open and after Create; Identity on open and after a save | `files == null`: no "No image loaded", no file rows | empty list |
| `stat -c '%d %i'` | read | `realPath` after the picker | nothing | the pick is refused with a Fault |
| `truncate -s`, `rm -f`, `printf > tmp && mv` | change | Create (sparse), Create cleanup, profile writes | Create key disabled while the job runs | Fault inside the sheet |
| `dd if=/dev/zero` | stream | Create (full) | Cells progress from `stat -c %s` every 500 ms | the short file is removed |
| `dd of=/dev/hidgN bs=8|4` | stream | KbdMouse, one per node, restarted after 1 s when it dies | nothing | reports dropped |
| `tail -n 200|-c +N -f` | stream | Log's Daemon tab | lines append | one `[-] Cannot follow` line |
| `: > log; rm -f .old`, `stat -c %s` | read | Log Clear and tab leave | | |
| `cat profile`, `identity generate tmp --force && cat` | read / change | Identity edit and create | Save key disabled while writing | Fault in the sheet |
| `usb-arsenal <args>` (every command below) | change, 30 s | `Gadget.run` only | `busy`: the bar inverted with the words from `says()`, the row says `starting`/`stopping` and the typed line, every DIP and Apply held | `fault` under the row or the bar, `fails++`, stderr's last line or `[-] Timed out (30s)` |

### 1.4 Command round trips

Every change goes `UI event → Gadget.run(fn, args) → Root.change("<UA> --udc X args") → the
binary (relayed to a daemon when one listens, else direct) → refresh() → status`. The
screens read the field named in the last column.

| UI event | Command | C entry | `status --json` field | Reader |
|---|---|---|---|---|
| Home Storage DIP on | `storage start [--lun N --mode M PATH]…` from `Picks.all` | `storage.c` `do_start`: prepare every image, one begin/commit; or an in-place `lun_load` swap when one image goes on a live LUN of the same type | `storage[]` (`lun`, `file` as the kernel shows it, `mode` resolved, `access`) | Home row lines, Storage `live`, `served`, `shownLive` |
| Home Storage DIP off | `storage stop` | `do_stop`: remove the function, commit (teardown when nothing is left) | `storage: []`, `bound` | the same |
| Storage Back, Apply | `storage eject --lun N` per dropped loaded LUN, then one `storage start` per changed live LUN, then one for new LUNs (`storageApply`), or `storage stop` with no picks | `do_eject` (in place; before 6.6 a host lock makes it rebind), `do_start` | `storage[]` | Storage, Home |
| Home Tether DIP on | `tether start --ncm|--rndis [--gateway C [--gateway-iface I]] | [--no-net] | [--address A] [--dns D] [--upstream U]` | `tether.c` `do_start`: function (NCM unless unsafe or missing), MACs, commit, fork the network side, wait for its verdict | `tether` (`function`, `iface`, `running`, `address`, `ipv6`, `upstream`, `gateway*`, `tx/rx_bytes`) | Home row, Tether chain, counters |
| Tether DIP off, Apply | `tether stop`; Apply is `stop` then `start` | `do_stop` | `tether: null` | the same |
| Home HID DIP on | `hid start [--interval N]` (omitted at 4 or when fixed) | `hid.c` `do_start`: both functions, descriptors, `interval` when the attribute exists, commit, `apply_perm` | `hid[]` (`function`, `node`, `interval`) | Home row, Hid card line, KbdMouse writers, `keyboardFixed` |
| HID DIP off, Apply | `hid stop`; Apply is `stop` then `start` | `do_stop` | `hid: []` | the same |
| Stop all | `stop` | `main.c` `cmd_stop`: kill the tether daemon, `ua_gadget_teardown`, hand back | `bound: 0`, everything empty | the bar, every row |
| Identity pick or Apply | `identity set PATH` / `identity unset` | `config.c` `do_set` (symlink or copy, replug if live) / `do_unset` | none: `identity show` is re-read | Identity, Settings |
| Identity Create | `identity generate tmp --force` then the app's own write | `config.c` generate | none | Identity's list |

What `status --json` cannot say: what was asked for (the picked path and `auto`, the plan,
the interval when the kernel has no control), who started it, and whether it is what the
screen is set up to start. Those live only in the process-only `startedX` and the prefs.

### 1.5 The C side of a status read

`cmd_status` (`src/main.c`) opens the gadget (`ua_gadget_open`: configfs, the UDC from the
handoff record when one is held, else `--udc` or the only one), reads the UDC state, and
`live = ua_gadget_live()` = the handoff record exists for this boot and `usb_arsenal/UDC` is
non-empty. With `live` it prints each function's part; without it the arrays are empty and
`tether` is `null`. `ua_status_storage` prints one object per existing `lun.N` with `file`,
`cdrom` and `ro` as the kernel holds them (a dvd LUN names its loop's `backing_file`), so an
ejected LUN is `{"file": ""}`. `ua_status_hid` lists each linked function with its node found
by major:minor (up to 2 s) and the `interval` attribute (`-1` without one). `ua_status_tether`
reads `Net/tether.info`, written by the network side, and the netdev counters.

Windows a reader can land in, all inside one command on the change lane:
- `begin` unbinds the UDC first, so until `commit` rebinds, `bound` is 0 and every array is
  empty. A replug (identity set, a LUN of a new type, an eject the host blocks before 6.6, HID
  or tether start on a live gadget) spends a few seconds here.
- `lun_load` ejects before it loads, so a swap in place has a moment of `bound: 1` with that
  LUN's `file` empty.
- `do_start`'s failure path on an existing function ejects every LUN it reached and commits,
  which leaves `bound: 1` with empty drives for good.

## Part 2: the audit

Seven agents read the code in parallel (sync layer, navigation, storage, tether, HID,
identity and settings, design). Every finding below was checked against the code by hand;
four are proved by `Android/app/src/test/java/com/usbarsenal/app/ui/AuditTest.kt`, which
fails on the audited code and passes once the fix is in. One agent claim was wrong and is
listed under "checked, not bugs".

### 2.1 The storage report

With a fresh status, the taps in the report (two LUNs up, one pick dropped in Storage, Back,
Apply) issue one command, `storage eject --lun 1`, and no snapshot of that command reads as
"bound with no images": `do_eject` leaves LUN0 alone, and during its rebind path the UDC is
unbound, so `bound` is 0. The screen the user saw therefore came from one of three paths,
all real:

1. **A late poll kept a mid-apply snapshot.** When `live` showed LUN0 empty (the host had
   ejected it, or its pick differed from `startedPicks` by path or mode), `storageApply`
   issues two steps, `eject --lun 1` then `start --lun 0`. Between them the kernel is bound
   with every LUN empty for hundreds of milliseconds. A poll that started in that window and
   returned after the second step's own post-read overwrites it, because `Gadget.refresh()`
   writes `status` with no ordering (`ui/Gadget.kt:143`). Home then shows `idle`, the storage
   row off, the bar `BOUND`, until the next poll. The PC keeps LUN0. This matches the report
   word for word and explains why it did not reproduce.
2. **Apply decided from a null status.** A failed read sets `status = null`; Apply then sees
   `live = []`, drops the eject and re-sends the kept pick as a `start`, which the kernel
   takes as an in-place reload of LUN0 (or a full replug when the host holds a lock). LUN1 keeps
   serving while the app has forgotten it. Proved by `applyWithUnknownStatusChangesNothing`.
3. **A bound gadget with empty drives reads as off.** `isOn(STORAGE)` is "a LUN holds a
   medium", so a linked, bound `mass_storage.ua` with every LUN ejected (a CLI eject, a host
   eject, `do_start`'s failure path) shows the row off and the bar bound, and `pending()` is
   false so Apply can never reach `storage stop`. Proved by `emptyDrivesAreStillOn`.

The architecture in Part 3 closes all three: status reads are ordered and a read that began
during a command is dropped; Apply is a backend command that reads the kernel itself; and
"on" means "the function is linked", as the backend already defines it.

### 2.2 Confirmed findings, ranked

The fix column says **arch** when Part 3 removes the code path, otherwise what to change.

| # | Severity | Finding | Trigger | Fix |
|---|---|---|---|---|
| 1 | high | `Gadget.status` writes are unordered; a poll begun during a command can land after the command's post-read and show a mid-command snapshot (`bound: 0` for the whole begin to commit span, or bound with empty LUNs between apply steps). Several `PollStatus` loops overlap during a `Redraw`. `ui/Gadget.kt:143`, `ui/Home.kt:39-51` | any command while a poll is in flight; su latency during enumeration | arch: one poller, a sequence number, reads begun while busy dropped |
| 2 | high | Apply and `pending()` decide from the UI's snapshot: null or stale `status` drops ejects, re-sends kept picks as starts, and lets Back leave silently with an unapplied edit. `ui/Gadget.kt:219, 270, 302` | a failed poll at the moment of Back or Apply | arch: `apply` moves to the backend; pending comes from the backend's two files |
| 3 | high | A failed read blanks the world: `getOrNull()` sets `status = null`, Home shows idle and "Port with Android", Identity ignores taps, Settings' Controller row becomes tappable while bound. `ui/Gadget.kt:143` | one su hiccup or 15 s timeout | arch: keep the last status, count misses |
| 4 | high | "On" means "a LUN holds a medium": a bound gadget with empty drives shows storage off, Apply cannot reach `storage stop`, only STOP ALL clears it. `ui/Gadget.kt:371-372` | CLI eject of the last LUN, host eject, `do_start` failure path | `isOn(STORAGE)` is `storage.isNotEmpty()`; `AuditTest.emptyDrivesAreStillOn` |
| 5 | high | Multi-controller launch with no pick: Home's `loadOnce` runs `check --json` without `--udc`; `check` then prints every function as 0, `caps` says MISSING for the process, every row ghosted and dead. The Controller step never resets `caps` (Settings does). `ui/Gadget.kt:162`, `ui/Setup.kt:548-553`, `src/check.c:171-207, 251-257` | two UDCs, pick absent or dropped | arch: the UDC lives in the setup file, `check` and every read carry it; `caps` re-read on a controller change |
| 6 | high | Tether's `leaving()` effect rewrites the saved plan (gateway off) on every dispose when the container is not running, bypassing the Not applied sheet, undoing Discard and Apply, and firing when Log opens. `ui/Tether.kt:59-62` | gateway tether running, container stopped, any Back | delete the effect; a stopped container is a fact the chain shows, not an edit |
| 7 | high | Identity's Discard writes the old profile text on the screen's coroutine scope; `back()` disposes the screen two frames later and the kill tree stops the write. The file keeps the edit, nothing offers it again, the next replug switches the host silently. `ui/Identity.kt:170` | edit the running profile while bound, Back, Discard | run it on `Gadget.scope` (as `run` does) |
| 8 | high | `chosen` is seeded from `active` once (the effect's key is a Boolean) and reset before `reloadProfile()`; Back then runs `identity set` with a stale pick and silently reverts a pick made from the CLI, a second tap while busy, or a set that linked and then failed to replug. `ui/Identity.kt:101-106, 123-125, 135-138` | any of those | seed from the cached profile on every change; reload before resetting; Back sets only what the user picked on this visit |
| 9 | high | A generated, unedited profile's `serial=   # comment` is read as the comment: the panel shows it, Edit cannot save. `backend/Profiles.kt:352-356` | `identity generate` then open Identity | match `(^|\s)#`; `AuditTest.generatedProfileSerialIsEmpty` |
| 10 | high | The Dip's only disabled look is the "kernel lacks it" knobless track, used for busy, no backend read yet, no picks, and Settings' unread switches; focus leaves a DIP the moment it is flipped; the haptic fires seconds before the knob moves. `ui/Controls.kt:261-266`, `ui/Home.kt:89, 145` | every flip, every cold launch | a held look that keeps the knob and takes no input; `enabled` only for a missing function |
| 11 | high | `hid start` on a running HID exits 0 with `--interval` ignored; the app records an interval that does not run and never offers Apply. `src/gadgets/hid.c:224-245`, `ui/Gadget.kt:229-231` | a null status showed the DIP off; the user flips it on with a new interval | arch: pending compares the setup with `status.hid[].interval`; `do_start` exits non-zero when it ignored `--interval` |
| 12 | medium | `backendState` maps an su denial (Magisk exits 13 with empty stdout) to NOT_INSTALLED, so Home offers Install and the installer fails; the NoRoot shade never shows. `backend/Installer.kt:49-50` | root revoked on a set-up app | treat a non-zero exit with no `bin` line and stderr from su as NO_ROOT, as `rootInfo` does |
| 13 | medium | Setup's root denial is sticky for the process: "Grant root, then reopen the app" cannot be satisfied while the process is cached. `ui/Setup.kt:88-93, 310` | deny, grant in the manager, relaunch | clear `Setup.root` on failure when the activity restarts |
| 14 | medium | `loadOnce` runs `check --json` on the change lane on every screen mount while `caps` is null; a persistently failing check (or an old backend missing a key, which makes `parseCheck` throw) queues a 30 s probe ahead of every command and probes configfs each time. `ui/Gadget.kt:161-163`, `backend/Backend.kt:228-239` | check fails once | arch: caps cached across launches with the boot id and re-read once per launch; optional keys |
| 15 | medium | The DHCP and NAT switch and Apply are enabled on a kernel without NAT (`caps.nat` is never read); the start is refused every time. `ui/Tether.kt:128`, `src/gadgets/tether.c:938-942` | kernel without the nat table | `net` held off and the row says why when `nat` is MISSING |
| 16 | medium | Screens re-lay themselves after root answers: Home's attention key appears or vanishes after `backendState`; Tether's Gateway row vanishes after the Droidspaces read; Storage swaps the Mode and FILES block for "No image loaded" after `listImages`; Identity's panel grows and Built-in is inverted until the profile is read; Settings' three switches are dead and its warning and fault lines push the sections down; the Interval row wraps when `fixed` flips. `ui/Home.kt:76, 152`, `ui/Tether.kt:120`, `ui/Storage.kt:163`, `ui/Identity.kt:108, 156`, `ui/Settings.kt:62-79, 123-124`, `ui/Hid.kt:45-53` | every visit | arch (cache-first draws the final layout from frame one) plus reserved lines for faults and warnings |
| 17 | medium | The Drive's LED and tray show the pick, not what runs: Eject drops the pick and the LED goes off while the kernel still serves; a CLI-started LUN shows as an empty drive; a changed pick blinks "serving". `ui/Panels.kt:69, 106`, `ui/Storage.kt:159-161` | eject, CLI start, re-pick | the face shows `running`, the body the setup |
| 18 | medium | `storage stop` with empty picks removes LUNs the app never showed (a CLI LUN), and the sheet's note never says storage stops. `ui/Gadget.kt:259`, `ui/Storage.kt:119` | one app pick, one CLI LUN, eject the pick, Apply | arch: the setup file holds every LUN whoever added it |
| 19 | medium | Install then Home: `Gadget.backend` keeps the pre-install state (the installer's final `backendState` is not stored), so the attention key draws and then vanishes. `ui/Home.kt:76`, `backend/Installer.kt:139-142` | every install | store the installer's final state; arch caches it |
| 20 | medium | `storage start` of the image a LUN already holds is not harmless: `lun_load` ejects and reloads (a medium change for the host); a host lock makes it a replug of every function; a failure after the eject leaves the LUN empty with no restore, and the swap path runs outside the critical section. `src/gadgets/storage.c:140-163, 480-492` | the app re-sends a pick (finding 2), a CLI start of the same file | `do_start` compares the backing file first and returns "already serving" with no kernel write; the swap takes `ua_crit_enter` |
| 21 | medium | Process-only `startedPicks`, `startedPlan`, `startedInterval`: a function started from the CLI or before process death never offers Apply, Discard does nothing, and a stale record after CLI activity makes Discard write the wrong setup back. `ui/Gadget.kt:109-126` | CLI start, process death, CLI stop and start | arch: `running.conf` written by the backend |
| 22 | medium | Deep restore after process death: `Gadget.backend` is null until Home composes, so Apply is dashed on every screen; `Controller.udcs` null hides Settings' USB section until the launch read returns; Keys + mouse never calls `HidSetup.load` so Speed and Natural scroll show defaults; a restore onto `[HOME, CONTROLLER]` pushes a second Controller screen; a restore onto INSTALL re-runs a finished install. `ui/Setup.kt:180-184, 481-485`, `ui/KbdMouse.kt:346` | process death on those screens | arch (cache) for the first three; guard the push on an existing CONTROLLER; persist `Setup.phase` DONE |
| 23 | medium | The controller pick is cleared when the picked UDC is missing at launch (host mode) or only one is listed, and nothing re-asks in the process: when it returns, every command fails "pick one with --udc" and Settings hides the row. `backend/Backend.kt:72` | dual-role port in host mode at launch | arch: the pick lives in `setup.conf`; the app never clears it, only the user does |
| 24 | medium | `HidWriter`: the 64-report channel guards nothing because the 64 KiB pipe buffers thousands of reports (a backlog replays when the host wakes); a blocked `write` is not cancellable, so a dead or stopped HID leaves `dd` and the IO thread alive and a second writer starts on the same node; the report in flight is lost when `dd` dies (a release, so a held key); a paste of hundreds of characters drops reports and the box diverges from the host. `ui/KbdMouse.kt:106-130, 537-542` | host asleep, HID stopped from the CLI, paste | a small non-blocking writer in C (`hid write`, `O_NONBLOCK`, drop on EAGAIN) in place of `dd`; the app writes one report per line to it |
| 25 | medium | Tether says `Upstream` twice (the node and the row, the duplicate DESIGN.md names); in gateway mode the container and its iface appear twice; `Host IPv4` is computed from the plan while its neighbour `Host IPv6` is live; the Container row says `none` while the plan names a container that Home will start. `ui/Tether.kt:106-109, 122-143` | default screen | one `Upstream` (the node shows live, the row is the setting, so rename the row `Via` or move it into the node), `Host IPv4` from status while running |
| 26 | medium | Plan equality counts an edit made back again as a change: `function` picked then picked back is `"ncm" != null`. `ui/Gadget.kt:221` | tap RNDIS, tap NCM | arch: compare resolved values; `AuditTest.functionPickedBackIsNoChange` |
| 27 | medium | Identity's Fault line takes every whole-gadget fault (a failed `stop` from Home shows on Identity) and sits above the panel; Home shows identity faults above the bar and shakes it. `ui/Identity.kt:110, 152`, `ui/Home.kt:104-105` | a failed stop, a failed set | give identity its own `Fn`-like owner; the line under the panel |
| 28 | medium | Profiles edited by hand round-trip wrongly: `withKeys` drops any of the six keys the file lacks; `listProfiles` greps `^key=` and misses ` key = v`; `hex4` reads a bare `0104` as decimal where C reads octal. `backend/Profiles.kt:346-347, 359, 392-405` | hand-written `.conf` | arch: `identity show --json FILE` or `setup show` style reads through the C loader, so the app never parses a profile itself |
| 29 | medium | A mid-replug poll (`bound 0, hid []`) tears Keys + mouse's writers down and leaves full screen; a 2 s later poll brings HID back but not full screen. `ui/KbdMouse.kt:175-182, 223-262` | a CLI replug while typing | arch: dropped mid-command reads; `on` from the kept status |
| 30 | low | No initial focus request on Storage, Tether, HID, Keys + mouse, Settings, Identity, Log, Fonts, Controller; the gamepad's A and X need focus to work and hijack `< Back` and the Keys segment; the wheel strip has no key path; L/M/R play no haptic. `ui/*.kt`, `ui/KbdMouse.kt:288-300, 435-512` | D-pad or gamepad use | `focusOn` on every screen's first row or primary key; A and X only over the pad |
| 31 | low | Keys tab keys are 40 dp (`KEY_H`), under the 44 dp minimum. `ui/KbdMouse.kt:141` | | 44 dp |
| 32 | low | Bottom bar: a failure while bound pops Pin out and later re-plugs him with a tick for no replug; `FAILED` returns after a command shorter than 2.4 s. `ui/Controls.kt:729-738, 844-863` | a failed start on a bound gadget | gate the pop-out on `port !is Bound`; cancel `bonking` on the next command |
| 33 | low | Settings does not poll status, so "stop all to change it" and the Controller row go stale after a CLI stop; `changed` is set when a switch returns to its boot value. `ui/Settings.kt:84, 107-109` | CLI stop while on Settings | arch: one poller feeds every screen |
| 34 | low | The NoRoot shade is driven only by Home's one read; root revoked while on Settings shows Reinstall live and no shade. `ui/Setup.kt:235`, `ui/Settings.kt:75` | root revoked mid-session | arch: `backend` is one cached, refreshed value |
| 35 | low | `tether apply` as stop then start hands the port to Android between the two commands when tether is the only function: two enumerations, adbd restarts. `ui/Gadget.kt:271` | Apply on tether alone | arch: `tether apply` in C keeps the gadget bound (begin, swap the function, commit) |
| 36 | low | `check`'s report through the app's pipe shows stderr lines above its header (stdout fully buffered, stderr not). `src/utils.c:30-32` | check fails in setup | `setvbuf(stdout, NULL, _IOLBF, 0)` when stdout is not a tty, or flush both in `ua_log_internal` |
| 37 | low | `save_perm` on every `hid start` resets a CLI `--perm`/`--group` to the profile's; `find_node` waits up to 2 s per node inside `status --json`; `ua_hid_reapply` runs after `ua_crit_leave`. `src/gadgets/hid.c:165-203, 223` | Apply after a CLI start with a group | `status` does not wait for a node; the rest noted |
| 38 | low | Period text and "1 ms, fixed by this kernel" assume high speed; the Keys + mouse shade says "Enable HID on Home" when the kernel has no HID; `Card` has no two-frame hold; the mode hint clips at font scale 1.3; Power segments read `100 MA`; the doze `z` is drawn as `Z`; Create failure plays `Reject` with no shake; the Terminal is always a Tab stop; `Segs` ghosts Silkscreen; `rootInfo` with no `su` on PATH shows an empty `su -v` line; `ruleIsNew` ignores a changed rule; `assetMd5` null on x86 means UPDATE forever; `encodePicks` breaks on a path with a newline; the Log tab duplicates a few lines after Clear and marks no rotation. | | each one line, after the architecture lands |

### 2.3 Checked, not bugs

- **`bound()`** strips the trailing newline configfs prints, so an unbound `UDC` reads as 0.
- **Stale `tether.info` after the network side dies** (an agent claim): `running = daemon_pid() > 0 && read_file(...)` short-circuits, so with no daemon the info buffer stays empty and status shows no stale upstream or gateway.
- **The change lane** is released on cancel after the `NonCancellable` kill; reads never take it; between apply steps `busy` is cleared and set again in one main-thread dispatch, so no switch is live between steps.
- **No window has the UDC bound with `mass_storage.ua` unlinked**: teardown unbinds before unlinking, `do_start` unlinks only after `begin`'s unbind, `commit` links before binding.
- **`storageApply` with a fresh status** gives the step lists `GadgetTest.storageAppliesPerLun` expects, re-derived by hand; `--lun` and `--mode` always precede their image as `parse()` wants; an eject of an already empty LUN is a kernel no-op.
- **`do_eject`**: `"\n"` is an eject to `fsg_store_file`; `forced_eject` on 6.6; the rebind path clears the host lock and retries 3 s.
- **`read_file(cur, cur, ...)`** aliasing is safe (the path is consumed by `open` first); `ua_json_str` escapes quotes, backslashes and controls.
- **The daemon relay and `UA_NO_PROXY` reads** agree on the workspace, the UDC (the handoff wins on both) and the handoff file; the fd shuffle in `session()` is correct; `storage eject` relays with no fds. One drift: after an in-place binary update without a reboot, a running daemon keeps executing its pinned old binary while reads run the new one.
- **Profile paths**: `profilesDir()` and `do_set`'s `realpath` both give `/storage/emulated/0/USB-Arsenal/Profiles/...`, so `chosen` and `active` are the same string in every app-driven path; `(copy)` resolves through `# copied from`.
- **`writeProfile(create = true)`** leaves no temp file behind; `withKeys` output is read by `ua_config_load`; `profileProblems` matches `set_key` for the six keys.
- **`rememberSaveable` of `List<Step>` and `List<String?>`** saves and restores; `show()`'s guard, `openers` padding and `ReturnFocus` reset are right; `readCheckReport` never yields partial JSON (fully buffered stdout is lost whole on a kill).
- **Validation parity**: `validAddress`/`hostOf` match `parse_address`; `validDns` matches `ua_valid_dns`; `tetherArgs` always names the function; `tetherFunction` matches `do_start` for every capability combination except the crash-left unbound function (finding 38's class).
- **Motion, haptics, colours, type**: every animation is stepped on the 40 ms clock with the table's frame counts, every loop rests under reduced motion, the tiers and `allowed()` gate are right, no third colour, no size outside the scale, no em dash in the app.
- **Reports and dd framing**: `keyFor` covers every key the table shows; each pipe write is one report and `bs` caps each read, so reports never merge.

## Part 3: the unified state architecture

Agreed and implemented on 2026-10-24: `src/setup.c`, the `apply` actions, the `status --json`
keys, `backend/Cache.kt` and the rewritten `ui/Gadget.kt`. The fixes it does not cover are
tracked in [../app-state-fixes.md](../app-state-fixes.md).

### 3.1 What it must do

- Truth lives in the workspace, through `ua_ws_path()`. A data wipe or reinstall loses nothing;
  the CLI alone rebuilds the last setup; the app is one client.
- One format, `config.c`'s `key=value` loader, validators and `write_atomic`, driven by a
  second key table. No second parser.
- Draft and running are two files. The backend writes running on every successful start,
  stop and eject, whoever ran it. Pending is their difference, computed by the backend.
- The app writes the draft through a backend command and reads everything in one
  `status --json`. It is cache-first: the first frame comes from the last read, a live read
  replaces only what changed, a failed read keeps the last value, and a sequence number
  orders the writes.
- Apply and pending are decided by the backend against the kernel, never from a UI snapshot.

### 3.2 The files

`Gadget/setup.conf` is the draft, what each function starts with. `Gadget/running.conf` has
the same keys and holds what the last successful start used, with a function's keys cleared
when it stops. Both are written with `write_atomic` and read with `ua_config_load`, which
takes the key table as a parameter (today it is a file-level constant; the one change in
the loader). Comments are `generate`'s style.

```
# USB Arsenal setup: what each function starts with. Change it with
#   usb-arsenal setup set KEY=VALUE ...
# An empty value means the active profile's default, or the kernel's.

udc=                              # controller; empty: the only one in /sys/class/udc
storage_lun0=auto:/storage/emulated/0/USB-Arsenal/Win11_24H2_x64.iso
storage_lun1=rw:/storage/emulated/0/USB-Arsenal/pendrive.img
storage_lun2=                     # MODE:PATH, mode auto | cdrom | dvd | disk | rw
storage_lun3=
storage_lun4=
storage_lun5=
storage_lun6=
storage_lun7=
tether_function=                  # ncm | rndis; empty: the profile's
tether_gateway=                   # a container's name; empty: no gateway
tether_gateway_iface=usb0
tether_net=1                      # 0: --no-net
tether_address=                   # empty: the profile's
tether_dns=
tether_upstream=
hid_interval=0                    # 1-255; 0: the kernel's default
```

`running.conf` after `storage start` from the file above, with tether and HID stopped:

```
udc=a600000.dwc3
storage_lun0=auto:/storage/emulated/0/USB-Arsenal/Win11_24H2_x64.iso
storage_lun1=rw:/storage/emulated/0/USB-Arsenal/pendrive.img
storage_lun2=
...
tether_function=
tether_gateway=
...
hid_interval=0
```

Running stores the setup's values as given, not the resolved ones (`auto`, an empty
address), so a profile switch edits nothing, as the current `startedPicks` comment requires.
The kernel's resolved truth stays where it is, in `status --json`'s `storage`, `hid` and
`tether`.

Validation: `storage_lunN` is a new kind, `K_LUN`, a mode from the list, a colon, and an
absolute path; `tether_gateway` and `tether_gateway_iface` are `K_STR` up to `IFNAMSIZ - 1`
for the iface; `tether_net` is `K_UINT` 0 or 1; `hid_interval` is `K_UINT` 0 to 255; the
rest reuse the profile table's kinds (`K_CHOICE`, `K_CIDR`, `K_DNS`, `K_STR`). `udc` is
`K_STR` of 63.

### 3.3 The CLI

New command, `setup`, in the `identity` style:

```
usb-arsenal setup show [--json]            # the draft and what runs, every key
usb-arsenal setup set KEY=VALUE ...        # edit the draft; a bad value is refused and nothing is written
usb-arsenal setup reset [storage|tether|hid]   # the draft back to defaults (all, or one function)
usb-arsenal setup revert [storage|tether|hid]  # the draft back to what runs (Discard)
```

Changed semantics, Droidspaces' rule: **a start with arguments saves them, a bare start
reuses them.**

- `storage start` with no IMAGE starts every `storage_lunN` of the draft, one replug for all.
  `storage start [--lun N] [--mode M] IMAGE ...` writes those LUNs into the draft first (the
  others keep theirs), then starts as today. `storage eject --lun N` clears LUN N in the draft
  and in running. `storage stop` clears running's storage keys.
- `tether start` with no option starts from the draft; options are merged into the draft
  first. `--gateway C` sets `tether_gateway=C`, `--no-net` sets `tether_net=0`. `tether stop`
  clears running's tether keys.
- `hid start` likewise with `--interval`. `--perm` and `--group` stay profile keys as today.
- `stop` clears running entirely.
- On every successful start the function's draft keys are copied into running (also the UDC
  used). The copy happens after `commit` returns 0, in the same process, so a crash before
  it leaves running as it was and the next `status` reports pending.

New action on each function, **`apply`**: make what runs match the draft, deciding against
the kernel, not a snapshot:

- `storage apply`: for each LUN, compare the draft with what `lun.N/file` and `cdrom`/`ro`
  hold and with running's entry. A LUN in running but not in the draft is ejected in place. A
  LUN whose draft differs from running, or whose kernel file is empty while the draft has
  one, is loaded: in place when the type matches (today's fast path, now under
  `ua_crit_enter`), by one begin and commit otherwise. New LUNs load in that same commit.
  With no LUN in the draft, `storage stop`. A LUN already serving the draft's file in the
  draft's mode is left alone with no kernel write (finding 20).
- `tether apply` and `hid apply`: if the draft differs from running, swap the function inside
  one begin and commit (the gadget stays bound: finding 35), restart the network side, write
  running. If nothing differs, do nothing and say so.
- `apply` on a function that is not running is `start`.

`status --json` gains, every key always present:

```
"boot_id": "…",
"setup":   {"udc": "", "storage_lun0": "auto:/…", …, "hid_interval": "0"},
"running": {… the same keys …},
"pending": {"storage": 0, "tether": 1, "hid": 0}
```

`pending.<fn>` is 1 when the function is linked in the gadget and its draft keys differ from
its running keys. `hid` also counts a draft interval the running keyboard did not get
(`status.hid[].interval` says what it runs, finding 11). The text `status` prints a
`Pending:` line when any is set.

The UDC: `ua_pick_udc` reads `setup.conf`'s `udc` when `--udc` is absent and more than one
controller is listed. The handoff's UDC still wins while the gadget is held. A pick whose
controller is not listed fails as today ("No such UDC now"); nothing clears it (finding 23).

Boot: nothing starts from the saved setup at boot. The user did not ask for it.

### 3.4 The app

**Cache.** One prefs file, `cache`, with the raw JSON of the last `status --json`, the last
`check --json`, the last `identity show`, the last backend state and Settings' two switches,
each with the `boot_id` it was read under (Droidspaces' `cachedHostCapabilitiesBootId`). On
launch the app reads `/proc/sys/kernel/random/boot_id` itself (no root needed) and parses
the cache: `setup`, `running`, caps, profile and backend are shown as they were; the gadget
part (`bound`, `storage`, `hid`, `tether`) is shown only when the boot id matches, else as
off. The first frame of every screen draws from this, with its final layout. No screen has a
`LaunchedEffect` that loads its own state.

**One poller.** `App()` runs the only status loop, 2 s while STARTED, and `Gadget.run`
takes a post-read. Each read gets a ticket `n = ++reads` and remembers `finished`; a result
is applied only if `n > applied` and no command finished since it began. `applied = n`.
Every `PollStatus()` call site goes.

**A failed read keeps the last status** and counts `misses`. The bar's detail line reads
`no answer from usb-arsenal` after three misses in a row; nothing else blanks. Settling
(`binds`) only runs on applied reads.

**The draft.** `Gadget.setup` is the cached `setup` map. A screen edits it through
`Gadget.set(key, value)`, which updates the map and the cache at once (the first frame is
right) and queues `setup set key=value` on the change lane, coalescing edits made within a
beat. `apply`, `start` and a pop flush the queue first; they run on the same lane, so order
holds. `Picks`, `TetherSetup`, `HidSetup.interval` and `Controller.picked` become views over
this map; `speed`, `natural`, `motion` and `haptics` stay app-side prefs.

**Pending and the sheet.** `pending(fn)` is `status.pending[fn]`, or true while an unsent
edit for that function is queued and the function is linked. Apply runs `<fn> apply`;
Discard runs `setup revert <fn>` and the map takes `running`'s values at once. Home's DIP on
is a bare `<fn> start`, off is `<fn> stop`. `startedX`, `recordStart`, `forgetStart`,
`storageApply`, `setupArgs`, `tetherArgs` and `startArgs` are deleted from Kotlin.

**Caps and profile** are re-read once per launch (and after Recheck, Reinstall, a controller
change, an identity command), replacing the cache. `check --json` runs on the change lane as
today, once, not per screen mount.

**Screens.** Settings' switches draw from the cache and are live from frame one. Tether's
Gateway row is always present and held off, with `no droidspaces` as its value, when the
cached probe says Droidspaces is absent; `net` is held off with `no nat in this kernel` when
caps say so. Storage's Drive face shows `running` and `status.storage` (the LED is what
runs), its body the draft; the strip shows every LUN in the draft or in running. The fault
and warning lines get a reserved line on each screen that has one, as Storage's mode hint
already does. `isOn(STORAGE)` becomes "the storage array is not empty".

**AGENTS.md** rewrites "a probe's result lasts until the app leaves recents" to: a probe's
result is cached across launches with the boot id; the first frame draws from the cache and
one live probe per launch corrects it; Back never re-runs a probe, only Recheck, Reinstall, a
controller change or an identity command does.

### 3.5 Open questions, with a recommendation each

1. **Profile defaults against the setup.** Recommendation: keep the profile's `storage_mode`
   and `tether_*` keys as defaults and let an empty setup value mean "the profile's". The
   profile is identity plus defaults, as `identity.md` documents; the setup is what this
   phone runs. Both files use the same key names on purpose, so the override reads as one.
   Running stores the raw setup value, so a profile switch is never a pending change.
2. **Storage paths.** Store the path as given, resolved with `realpath` on the side that
   opened it (the client's name travels with the fd as `identity set` does with `--path`).
   The app's picks are `/storage/emulated/0/...` under `--mount-master`, which is also what
   the kernel prints, so nothing is compared across spellings any more: pending is draft
   against running, both written by the backend. A saved image that is gone at the next start
   fails that start naming the file; the pick stays in the draft and the fault line says why.
   An image a container client passed as an fd keeps the client's path, which the daemon may
   not be able to open on a bare start or apply; that start fails naming it, and the
   container has to run `storage start IMAGE` again. Documented, not solved.
3. **The UDC pick** lives in `setup.conf` as `udc=`. The backend reads it when `--udc` is
   absent; the app stops passing `--udc` and stops clearing the pick.
4. **Boot.** No. The files make a boot start possible later with one line in `service.sh`.
5. **The stale marker.** Recommendation: keep the last status on a failed read and show
   `no answer from usb-arsenal` in the bar's detail line after three misses. DESIGN.md has no
   stale state yet; this is the smallest one.

### 3.6 Order of work

1. C: the key table and `K_LUN`, `setup show|set|reset|revert`, running written on start,
   stop and eject, `apply` for the three functions, `status --json`'s four new keys, the UDC
   from the setup, `do_start`'s "already serving" check. Tested with `tests/dummy_hcd.sh` and
   on a device per AGENTS.md.
2. App: the cache and the one poller with ordered reads, `Gadget.set` and the draft map,
   pending from status, the screens drawing from the cache, the deletions. `AuditTest` goes
   green and gains the ordering test.
3. The fixes outside the architecture, one commit each: findings 6, 7, 8, 9, 10, 12, 13, 15,
   17, 24, 25, 27, 30 to 38.
4. Docs: `usage.md` (`setup`, `apply`, the start rule, `status --json`), `app.md`,
   `architecture.md`, DESIGN.md's Backend truth table, the ROADMAP log, AGENTS.md's rule.
