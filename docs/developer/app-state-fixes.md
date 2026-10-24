# App state fixes

Every finding of the 2026-10-24 audit ([research/06-app-state.md](research/06-app-state.md)),
one box each. Tick a box when its fix is committed; strike a line only with the reason. Items
marked **arch** are closed by the setup/running architecture of that note's Part 3; the rest
are fixes of their own. Agent claims that turned out wrong are not listed here; they are under
"checked, not bugs" in the research note.

## The sync layer (state-core)

- [x] **arch** `Gadget.status` writes are unordered; a poll begun during a command lands after the command's post-read (`ui/Gadget.kt:143`). One poller, a ticket per read, reads begun before the last command finished dropped.
- [x] **arch** Several `PollStatus` loops run at once during a `Redraw` (`ui/Home.kt:39-51`, every screen's call site). One loop in `App()`.
- [x] **arch** `apply()` decides storage steps from `Gadget.status?.storage` (`ui/Gadget.kt:270`); null drops the eject and re-sends kept picks. `storage apply` in C, against the kernel. Proof: `AuditTest.applyWithUnknownStatusChangesNothing`.
- [x] **arch** `pending()` needs `status.isOn(fn)` so a null or mid-command status lets Back leave silently (`ui/Gadget.kt:219`). Pending from `status.pending`.
- [x] **arch** `getOrNull()` blanks `status` on any failed read (`ui/Gadget.kt:143`): Home idle, bar "Port with Android", Identity ignores taps, Settings' Controller row tappable while bound. Keep the last status, count misses, `no answer from usb-arsenal` after three.
- [x] `isOn(STORAGE)` is "a LUN holds a medium" (`ui/Gadget.kt:371`); a bound gadget with empty drives reads as off and cannot be stopped from the app. "On" is `storage.isNotEmpty()`. Proof: `AuditTest.emptyDrivesAreStillOn`.
- [x] **arch** `startedPicks`, `startedPlan`, `startedInterval` are process-only (`ui/Gadget.kt:109-126`): CLI starts and process death never offer Apply; a stale record makes Discard write the wrong setup back. `running.conf`.
- [x] **arch** `loadOnce()` runs `check --json` on the change lane at every screen mount while `caps` is null (`ui/Gadget.kt:161`); a failing check, or an old backend missing a key that makes `parseCheck` throw, queues a 30 s probe ahead of every command. Caps cached with the boot id, read once per launch; optional keys in `parseCheck`.
- [x] `RootExec.kill` with no pid line yet only destroys the su client (`backend/RootExec.kt:349-351`); under Magisk the shell lives on and the lane is freed while the command runs. Wait briefly for the pid line before giving up, or kill by the command's own name.
- [x] `settle()` counts a CLI replug as a bind: one unbound poll then a bound one thuds (`ui/Gadget.kt:152-157`). Two unbound polls in a row before a thud (the ponytail note says so).
- [x] `HidSetup.fixed` flips with HID's run state on a backend older than the interval probe (`ui/Gadget.kt:145, 613`). Resolve `fixed` once per launch from caps, and from status only while HID runs.
- [x] `apply()` when `Gadget.run` refuses the first step leaves Home with nothing applied and no fault (`ui/Gadget.kt:285-295`). Show a fault.
- [x] `stopAll` failure keeps `startedX` though teardown may have removed functions (`ui/Home.kt:106`). **arch** (running cleared by teardown).
- [x] DESIGN.md's Backend truth table says Apply is "eject, then one `storage start`"; the code (and now `storage apply`) swaps LUN by LUN. Rewrite the row.
- [x] Home's Fault lines move the rows and keys below them (`ui/Home.kt:148, 157`). A reserved line under each row, or the fault inside the row's height. Done: every Home fault sits at the end, above the bar; the row shakes.
- [x] `status` on a non-Android system mkdirs and rmdirs `usb_arsenal` on every read (`src/gadget.c:300-320`) and can race a command's `build_skeleton`. Probe with `access` on `functions/` or take the lock for the probe.
- [x] A daemon keeps executing its pinned binary after an in-place update; reads run the new one (`src/daemon.c:386`). `version` should say so, or the installer should restart a running daemon. Half of it was wrong: sessions already exec the new file. The daemon's own loop now re-execs itself between connections when its binary changes, keeping the socket.

## Storage

- [x] **arch** Apply from a stale or null status (above).
- [x] The Drive's LED and tray show the pick, not what runs (`ui/Panels.kt:69, 106`, `ui/Storage.kt:159-161`): Eject drops the pick and the LED goes off while the kernel serves. The face shows `running` and `status.storage`, the body the draft.
- [x] **arch** `storage stop` with empty picks removes LUNs the app never showed (`ui/Gadget.kt:259`, `ui/Storage.kt:119`). Every LUN is in the setup file whoever added it.
- [x] `do_start`'s in-place swap ejects the old medium before it knows the new one loads, and a failure after the eject returns with no restore (`src/gadgets/storage.c:140-163, 485-492`). Open and prepare first (already done); on a refused `file` write, put the old file back.
- [x] The in-place swap runs outside the critical section (`src/gadgets/storage.c:480-490`). `ua_crit_enter` around it.
- [x] `storage start` of the image a LUN already holds ejects and reloads it (`src/gadgets/storage.c:480-490`); a host lock turns it into a replug of every function. Compare first and say "already serving".
- [x] Pick paths come in two spellings (`/storage/emulated/0`, `/mnt/user/0`) and are compared as strings for size, selection and the LUN tag (`backend/Images.kt:43-53`, `ui/Storage.kt:125, 195-196`). **arch** for pending; the list still needs `realpath` on the picked file (one `readlink -f`).
- [x] Storage re-lays itself: the Mode and FILES block swaps for "No image loaded" after `listImages` (`ui/Storage.kt:163`); the Fault line sits above the strip (`:155`); the Drive's size appears after Create's reload (`:159, 202-206`). Final layout from the cache; a reserved fault line.
- [x] Drive `kind` is the pick's mode, so `4.0G · rw` plus the `rw` tag says it twice (`ui/Storage.kt:159`, `ui/Panels.kt:56`). Kind is the kernel's mode from status while running, the draft's otherwise; the tag alone says access.
- [x] **arch** After process death a changed setup never asks (`ui/Gadget.kt:219-220`).
- [x] `encodePicks` breaks on a path with a newline (`ui/Gadget.kt:657-662`). **arch** (the backend stores paths).
- [x] Create: `name` is `rememberSaveable` so the sheet reopens with the last name and `name taken` lit; `taken` ignores a directory of that name; `tooBig` compares a sparse size with free space (`ui/Storage.kt:216-299`). Reset the name on open; `test -e`; skip `tooBig` for sparse.
- [x] `storageWarning` gets null bytes for a picker file so a big CD-ROM pick gets no warning (`ui/Storage.kt:180`). `stat` the picked file too.
- [x] `modeHint("auto")` omits that auto falls to dvd past the CD limit (`ui/Storage.kt:82`). Say it.
- [x] An image whose name starts with `-` is read as an option (`src/gadgets/storage.c:742`); the app never sends `--`. Send `--` before the images. Not a bug: `--` already works, and the app starts bare from `setup.conf`, so it sends no paths.
- [x] A failed `Root.read` inside `realPath` is reported as "That picker gives no file path" (`ui/Storage.kt:139`). Say the read failed.
- [x] Eight host-locked LUNs can exceed the 30 s change timeout and the kill lands mid-commit (`backend/RootExec.kt:295`, `src/gadgets/storage.c`). Raise the storage timeout, or have `lun_eject_wait` share one budget. Done in C: one 5 s eject budget per command, and every locked LUN out in one rebind.

## Tether

- [x] The `leaving()` effect rewrites the saved plan (gateway off) on every dispose when the container is not running, bypassing the sheet, undoing Discard and Apply, firing when Log opens (`ui/Tether.kt:59-62`). Delete it; a stopped container is a fact the chain shows.
- [x] `caps.nat` is never read: the DHCP and NAT switch and Apply are live on a kernel without NAT and the start is refused (`ui/Tether.kt:128`, `src/gadgets/tether.c:938-942`). Hold `net` off with `no nat in this kernel`.
- [x] The Gateway row appears then vanishes after the Droidspaces read, and Droidspaces is probed on every visit (`ui/Tether.kt:50-55, 120`). Always present, held off with `no droidspaces`; the probe cached per launch.
- [x] `Upstream` is a node and a row, the duplicate DESIGN.md names; in gateway mode the container and its iface appear twice (`ui/Tether.kt:106-109, 122-143`). One `Upstream`: the node shows what runs, the row becomes the setting under another label, or moves into the node. Decided: the Upstream node holds the input (placeholder what runs), and the gateway node is the container's Choice; the rows are gone.
- [x] `Host IPv4` is computed from the plan while `Host IPv6` beside it is live (`ui/Tether.kt:134-135`). Both from status while running, from the setup otherwise; IPv6 out of "Configure".
- [x] The Container row says `none` while the plan names a container Home will start (`ui/Tether.kt:122`). Show the saved name with `not running` as its detail.
- [x] Profile placeholders and `Host IPv4` go stale after a CLI `identity set` (`ui/Gadget.kt:161-164`, `ui/Tether.kt:131-137`). **arch** (the profile is re-read with every status cycle that reports a change, or once per launch and after identity commands).
- [x] The function segment jumps when caps arrive; NCM shows while the kernel runs RNDIS until then (`ui/Tether.kt:85-90`). Cached caps draw the right segment from frame one.
- [x] Plan equality counts an edit made back again as a change (`"ncm"` against `null`) (`ui/Gadget.kt:221`). **arch**: compare resolved values. Proof: `AuditTest.functionPickedBackIsNoChange`.
- [x] **arch** `TetherSetup.load` in a `LaunchedEffect` draws a default plan on a process-death restore (`ui/Tether.kt:44`).
- [x] An empty `Its iface` is refused by the app and fine for the backend (`ui/Tether.kt:123`, `ui/Gadget.kt:480`). Empty means `usb0`.
- [x] `validIface` accepts names the kernel rejects (`:`, `.`, `..`) (`ui/Gadget.kt:579`); `ua_valid_dns` validates a copy cut at 127 bytes so a long valid list passes the app and fails the backend (`src/config.c:120-123`). Match `dev_valid_name`; size the DNS buffer to the key's.
- [x] `--rndis`/`--ncm` is ignored when a tether function exists unbound after a crash, and `undo()` then leaves it linked (`src/gadgets/tether.c:948-955`). Remove the leftover function when the flag disagrees with it.
- [x] A poll during another function's replug draws Phone `off` and `-` counters for one beat (`src/main.c:160-179`). **arch** (mid-command reads dropped).
- [x] The ChoiceSheet's empty line says `No containers are running` while the read is still running (`ui/Tether.kt:152`). Say `reading` until `ds` is known, or draw from the cache.
- [x] `tether apply` as stop then start hands the port to Android between the two when tether is the only function (`ui/Gadget.kt:271`). **arch**: `tether apply` in C inside one begin and commit.
- [x] The chain names the plan's container for a CLI `--gateway-pid` start, and Home's line reads `gateway ` with a trailing space (`ui/Tether.kt:106`, `ui/Gadget.kt:406`). Show `gateway` alone when the name is empty.
- [x] No initial focus on Tether (`ui/Tether.kt`). `focusOn` the function segments.

## HID

- [x] `hid start` on a running HID exits 0 with `--interval` ignored, and the app records an interval that does not run (`src/gadgets/hid.c:224-245`, `ui/Gadget.kt:229-231`). Exit non-zero when an interval was asked and ignored; **arch** pending from `status.hid[].interval`.
- [x] The Interval row wraps and re-lays itself when `fixed` flips after the caps read (`ui/Hid.kt:45-53`). Cached caps; a one-line form for the fixed text.
- [x] `HidWriter`'s channel bound guards nothing (the 64 KiB pipe buffers thousands of reports, replayed when the host wakes); a blocked write cannot be cancelled so a dead or stopped HID leaves `dd` and the IO thread alive and a second writer starts on the node; the report in flight is lost when `dd` dies, so a release is lost and a key stays held (`ui/KbdMouse.kt:106-130`). A small non-blocking writer in C (`hid write`, `O_NONBLOCK`, drop on EAGAIN) in place of `dd`. Done: `hid write NODE SIZE` in C, and a release-all report on every writer start.
- [x] A paste of hundreds of characters drops reports through `DROP_OLDEST` and the box diverges from the host (`ui/KbdMouse.kt:537-542`). Same writer; pace the burst.
- [x] A mid-replug poll (`bound 0, hid []`) tears the writers down and leaves full screen, which is not re-entered (`ui/KbdMouse.kt:175-182, 223-262`). **arch** (dropped mid-command reads).
- [x] Gamepad A and X work only while something has focus; A hijacks `< Back` and the Keys segment; the wheel strip has no key path; L/M/R play no haptic (`ui/KbdMouse.kt:288-300, 435-512`). A and X only over the pad; a key path for the wheel; `VirtualKey` on L/M/R.
- [x] Keys tab keys are 40 dp (`ui/KbdMouse.kt:141`). 44 dp.
- [x] `HidSetup.load` is never called on Keys + mouse, so Speed and Natural scroll show defaults after a process-death restore (`ui/KbdMouse.kt:346`). Load from the cache at construction.
- [x] Stale `startedInterval` after CLI activity (`ui/Gadget.kt:222, 241`). **arch**.
- [x] Period text and "1 ms, fixed by this kernel" assume high speed (`ui/Reports.kt:154-162`, `ui/Hid.kt:48`). Export `current_speed` in status, or say "at high speed". Done: `status --json` has `speed`.
- [x] `save_perm` on every `hid start` resets a CLI `--perm`/`--group` to the profile's (`src/gadgets/hid.c:223`). Save only when given, or make them setup keys.
- [x] `find_node` waits up to 2 s per node inside `status --json` (`src/gadgets/hid.c:140-157, 273`). Status does not wait.
- [x] `ua_hid_reapply` runs after `ua_crit_leave`, so a client hangup's SIGTERM can land mid-chown (`src/gadget.c:676-677`). Move it inside.
- [x] A crash-left function directory keeps an old `interval` that a start without `--interval` never resets (`src/gadgets/hid.c:231`). Write the attribute always (0 for the default) when it exists.
- [ ] Found while fixing the line above: the f_hid interval patch (`kernel-patches/4.14/0007`, `5.15/0004`) overrides the static, shared endpoint descriptors only when the interval is nonzero, so after one bind with an interval a later 0 keeps that value until a reboot, for the mouse too. The patch needs an else branch that restores the defaults (high and super speed 4, full speed 10).
- [x] The Keys + mouse shade says "Enable HID on Home" when the kernel has no HID (`ui/KbdMouse.kt:594`). Say why, as Home's row does.
- [x] `KeysTab`'s `text` is `rememberSaveable` inside a conditional, so a tab switch empties the box while the host keeps the text; `full` restored true before `on` is known flashes the bars (`ui/KbdMouse.kt:522, 222`). Hoist `text`; restore `full` only once `on`.

## Identity, settings, controller, Log

- [x] Identity's Discard writes the old profile text on the screen's coroutine scope and `back()` cancels it (`ui/Identity.kt:170`). `Gadget.scope`.
- [x] `chosen` is seeded from `active` once (a Boolean key) and reset before `reloadProfile()`; Back then runs `identity set` with a stale pick and reverts a CLI pick, a second tap while busy, or a set that linked and failed to replug (`ui/Identity.kt:101-106, 123-125, 135-138`). Seed on every profile change; reload before resetting; Back sets only this visit's pick.
- [x] A generated profile's `serial=   # comment` is read as the comment (`backend/Profiles.kt:352-356`). Match `(^|\s)#`. Proof: `AuditTest.generatedProfileSerialIsEmpty`.
- [x] Picks are ignored with no sign until status and the profile are read, permanently when either fails (`ui/Identity.kt:89, 130`). Draw from the cache; a fault when the read failed.
- [x] Built-in is inverted and its panel drawn as "in force" until the profile is read; the panel grows when the list arrives (`ui/Identity.kt:108, 156, 205-224`). Cache; a fixed-height body.
- [x] Identity's Fault line takes every whole-gadget fault and sits above the panel; Home shows identity faults above the bar and shakes it (`ui/Identity.kt:110, 152`, `ui/Home.kt:104-105`). An identity owner for faults; the line under the panel.
- [x] `withKeys` drops the six keys a hand-made file lacks; `listProfiles` misses ` key = v`; `hex4` reads a bare `0104` as decimal where C reads octal (`backend/Profiles.kt:346-347, 359, 392-405`). Read profiles through the C loader (`identity show FILE --json`), never parse them in Kotlin.
- [x] A failed Apply leaves `edited` and `undo` set; a null status at Back abandons a pending pick silently (`ui/Identity.kt:118-126`, `ui/Gadget.kt:601`). Reset on failure; **arch** for the status.
- [x] A dangling profile symlink reads as Built-in with no fault; the warning is on stderr (`src/config.c:268-271`). `identity show` names the missing file; the app shows it.
- [x] Settings' `backend`, `daemon`, `linked` are null on every visit, the switches dead until three root reads return; `changed` and `fault` lines move the sections; the profile name relabels late; `changed` is set when a switch returns to its boot value (`ui/Settings.kt:62-124`). Cache; reserved lines; compare with the boot value.
- [x] Settings' Identity row prints the file name in Silkscreen uppercase as a label (`ui/Settings.kt:101`). The name as the VT323 value.
- [x] Settings does not poll status: "stop all to change it" and the Controller row go stale after a CLI stop (`ui/Settings.kt:107-109`). **arch** (one poller).
- [x] The NoRoot shade is driven only by Home's one read (`ui/Setup.kt:235`, `ui/Settings.kt:75`). **arch** (one cached `backend`).
- [x] The controller pick is cleared when the UDC is missing at launch or only one is listed, and nothing re-asks (`backend/Backend.kt:72`); the USB section never shows when the launch read failed; the choice sheet shows states as read at launch (`ui/Settings.kt:105, 148`). **arch**: the pick in `setup.conf`, never cleared by the app; the list read when the sheet opens.
- [x] Home's bar says `REPLUGGING switching identity` for an unbound `identity set`, which replugs nothing (`ui/Gadget.kt:176`). A plain "switching identity" state without the replug choreography when `bound` is false.
- [x] Log: a rotated file appends its last 200 lines under the old ones with no mark; Clear can re-emit a few lines; a missing log prints toybox's error as a line (`ui/Log.kt:56-57, 91-96`). A rule line on rotation; restart the tail before clearing the list; test the file first.
- [x] The daemon-mode switch is live while the backend is not installed, so a marker can be written for a missing binary (`backend/Installer.kt:174`). Hold it with the System path switch.

## Navigation, setup, install

- [x] A multi-controller launch with no pick runs `check --json` without `--udc`; `check` prints every function as 0 and `caps` says MISSING for the process; the Controller step never resets `caps` (`ui/Gadget.kt:162`, `ui/Setup.kt:548-553`, `src/check.c:171-207, 251-257`). **arch**: the UDC from `setup.conf` on every command and read; `caps` re-read on a controller change.
- [x] `backendState` maps an su denial (Magisk exits 13 with empty stdout) to NOT_INSTALLED, so Home offers Install (`backend/Installer.kt:49-50`). A non-zero exit with no `bin` line is NO_ROOT, as `rootInfo` reads it.
- [x] Setup's root denial is sticky for the process; "reopen the app" cannot be satisfied while cached (`ui/Setup.kt:88-93, 310`). Clear `Setup.root` on a failure when the activity restarts.
- [x] Install then Home draws the pre-install attention key and removes it a read later (`ui/Home.kt:76, 152`, `backend/Installer.kt:139-142`). Store the installer's final state in `Gadget.backend`.
- [x] A plain launch draws Home without the attention key, then inserts it under the rows (`ui/Home.kt:76, 152`). **arch** (cached backend state).
- [x] A process-death restore onto `[HOME, CONTROLLER]` pushes a second Controller screen (`ui/Setup.kt:180-184`). Do not push when the top is already CONTROLLER.
- [x] A restore onto INSTALL re-runs a finished install (`ui/Setup.kt:481-485`). Persist DONE.
- [x] Deep restores: `Gadget.backend` null dashes Apply on every screen until Home composes; `Controller.udcs` null hides Settings' USB section and shows 3 setup dots then 4 (`ui/Gadget.kt:361`, `ui/Settings.kt:105`, `ui/Setup.kt:415`). **arch** (cache).
- [x] `check`'s report through the app's pipe shows stderr lines above its header (`src/utils.c:30-32`). Line-buffer stdout when it is not a tty.
- [x] No initial focus on Controller and Settings; Controller's rows do not type on; Install's `< Back` pops in on failure; `focusOn` is not keyed on `SheetsOpen.count` so Welcome gets no cursor after the NoRoot shade; Root blocks Back before the command has typed while Check allows it; the Terminal is always a Tab stop (`ui/Setup.kt:245-249, 320, 395, 524, 538-564`, `ui/Parts.kt:668`). One `focusOn` rule for every screen; `keyScroll(enabled = canScroll)`.
- [x] The joystick hat pass-through can start a repeating synthetic D-pad key while the stick is deflected (`MainActivity.kt:60-70`). Zero the stick axes on the event passed through.
- [x] `rootInfo` with a root shell and no `su` on PATH shows an empty `su -v` line (`backend/Backend.kt:158`). Print what `su -v` said or `(no su)`.
- [x] `ruleIsNew` tests only that `sepolicy.rule` exists, so a changed rule gets no reboot line (`backend/Installer.kt:90`). Compare contents.
- [x] `assetMd5` null on an unsupported ABI means UPDATE_AVAILABLE forever (`backend/Installer.kt:53`). CORRUPTED with the reason, or READY.

## Design conformance

- [x] The Dip's only disabled look is the "kernel lacks it" knobless track, used for busy, the unread backend, no picks and Settings' unread switches; focus leaves a DIP when it is flipped; the haptic fires before the knob moves (`ui/Controls.kt:261-266`, `ui/Home.kt:89, 145`). A held look that keeps the knob and takes no input; `enabled` only for a missing function; the haptic on the frame the knob moves.
- [x] No initial focus request on Storage, Tether, HID, Keys + mouse, Log, Settings, Fonts, Identity, Quacky, Controller. `focusOn` everywhere.
- [x] Bottom bar: a failure while bound pops Pin out and later re-plugs him with a tick; `FAILED` comes back after a command shorter than 2.4 s (`ui/Controls.kt:729-738, 844-863`). Gate the pop-out on `port !is Bound`; cancel `bonking` on the next command.
- [x] `Card` has no two-frame inverted hold after a tap (`ui/Controls.kt:494-526`). Hold the inversion two frames before `onOpen`.
- [x] Storage's mode hint clips at font scale 1.3 (`ui/Storage.kt:182-186`). `Marquee`.
- [x] Identity's Power segments read `100 MA` (`ui/Identity.kt:316`). Keep case for the unit.
- [x] The doze `z` is drawn as `Z` (`ui/Controls.kt:906`). A lower-case glyph or a sprite.
- [x] T2 requires `PRIMITIVE_TICK` though the composition plays only `thud` (`lcd/Haptics.kt:49`). Check the primitives used.
- [x] Create's failure plays `Reject` with no shake, and `dd == null` plays nothing (`ui/Storage.kt:243-253, 268-272`). Shake with the haptic.
- [x] `Segs` ghosts a disabled Silkscreen label, which the dither eats (`ui/Controls.kt:207`). D50.
- [x] The Keys `TYPE` placeholder and the HID card's two-line text are explanatory copy (`ui/KbdMouse.kt:545`, `ui/Hid.kt:237`). One line that states a fact.
- [x] Install's FAILED footer swaps `Home` for `Copy log` and `Retry`, which DESIGN.md does not have (`ui/Setup.kt:504-517`). Add them to DESIGN.md or keep `Home` and put Retry in the shell. Kept, and recorded in DESIGN.md.
- [x] The terminal's scrollbar sits 3 dp from the panel edge against 4 dp elsewhere (`ui/Parts.kt:703`). Decide and record in DESIGN.md. 4 dp, as lcd.css; recorded.
- [x] The scrim tap dismisses a sheet though DESIGN.md names only Back or Cancel (`ui/Controls.kt`). Record it in DESIGN.md. Recorded in DESIGN.md.
