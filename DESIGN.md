---
version: alpha
name: USB Arsenal
description: The visual language of the USB Arsenal Android app. A 1-bit backlit LCD for a pocket USB gadget tool.
colors:
  primary: "{colors.ink}"
  lcd: "#FFA62E"
  ink: "#1C1005"
  scanline: "#1C10050F"
typography:
  display-hero:
    fontFamily: Jersey 10
    fontSize: 104px
    fontWeight: 400
    lineHeight: 0.8
    letterSpacing: 0px
  display-title:
    fontFamily: Jersey 10
    fontSize: 72px
    fontWeight: 400
    lineHeight: 0.8
    letterSpacing: 0px
  label:
    fontFamily: Silkscreen
    fontSize: 16px
    fontWeight: 400
    lineHeight: 20px
    letterSpacing: 0px
  label-strong:
    fontFamily: Silkscreen
    fontSize: 16px
    fontWeight: 700
    lineHeight: 20px
    letterSpacing: 0px
  data:
    fontFamily: VT323
    fontSize: 22px
    fontWeight: 400
    lineHeight: 20px
    letterSpacing: 0px
rounded:
  none: 0px
spacing:
  pixel: 2px
  key-depth: 4px
  lines: 5px
  tight: 12px
  section: 16px
  gutter: 20px
  top-inset: 22px
  nav-inset: 48px
  bottom-inset: 68px
  touch: 44px
  row: 80px
  lun-cell: 40px
components:
  screen:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: 22px 20px 68px
  scanline-grid:
    backgroundColor: "{colors.scanline}"
  key:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 14px
  key-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 14px
  segment:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 4px
  segment-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 4px
  dip-switch:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    width: 68px
    height: 32px
    padding: 2px
  row:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.data}"
    rounded: "{rounded.none}"
    height: 80px
    padding: 10px
  row-selected:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.data}"
    rounded: "{rounded.none}"
    height: 80px
    padding: 10px
  strip:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    padding: 14px 20px 68px
  strip-busy:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    padding: 14px 20px 68px
  terminal:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.data}"
    rounded: "{rounded.none}"
    padding: 10px
  shell-strip:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    height: 32px
    padding: 0 10px
  terminal-live-line:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.data}"
    padding: 3px 4px
  tag:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.data}"
    padding: 0 6px
  drive-face:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    height: 40px
    padding: 0 4px 0 12px
  node:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 10px
  node-phone:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 10px
  input:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.data}"
    rounded: "{rounded.none}"
    height: 44px
    padding: 0 10px
  warning:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.data}"
    padding: 8px 10px
  sprite:
    textColor: "{colors.ink}"
    size: 32px
  alert:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.label-strong}"
    height: 44px
    padding: 8px 10px
  fault:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.lcd}"
    typography: "{typography.data}"
    padding: 8px 10px
  sheet:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: 20px 20px 68px
  cells:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    height: 28px
    padding: 2px
---

# DESIGN.md

The visual language of the USB Arsenal Android app. It was designed screen by screen against the
C backend in `src/`, and every control on every screen maps to a command or flag that exists
today. The reference screens are in [docs/developer/design/](docs/developer/design/): canvas
sources, standalone renders and the shared stylesheet `lcd.css`.

The front matter is the normative part. The prose says when each value applies.

## Overview

USB Arsenal turns a rooted phone into one composite USB gadget: storage (ISO as CD-ROM, DVD or
disk, writable images), tethering (NCM or RNDIS, or an OpenWrt gateway) and HID (keyboard and
mouse), any combination at once. The app is the front panel of that gadget.

It is a pocket hardware hacking tool. It should feel like a small device with a backlit
monochrome LCD that lives in a drawer next to a soldering iron and a logic analyzer: amber glow,
black pixels, bitmap type, chunky pixel icons. It is not a Material app.

Personality, in three words: **terse, honest, mechanical.** It speaks like firmware
(`NO IMAGE LOADED`, `HOST CAN WRITE TO THIS FILE`), it never shows a control the backend cannot
honour, and things move in frames, like hardware.

It is original. It takes the 1-bit LCD handheld genre, not Flipper Zero's mascot, layouts,
assets or branding. The mascot is Pin (see Pixel art and Pin).

Structure:
- **Home is the gadget.** One row per function: icon, name, two live status lines, a DIP
  switch. No tab bar.
- **A function drills down** into its own screen. Back returns to Home.
- **Gadget state is always visible** in the bottom bar on Home: bound, replugging, or port
  with Android.
- **Secondary screens** sit outside the function list, as two keys above the bar: Log and
  Settings. Identity lives inside Settings, because it is configuration.
- **First launch** runs Welcome, Root, Check and Install, then Home. Later launches open Home,
  which leads back to Root or Install only when the backend needs it.
- **What setup finds lasts until the app leaves recents.** Back never asks su or the kernel
  again; only Retry and Recheck do. A result that was already there shows at once; only one
  that arrives on screen types or resolves.
- **New functions are new rows.** The structure does not change.
- **Tablets and landscape** keep the function list as a 420px pane beside the selected
  function's screen.

## Colors

Two colours and nothing between them. The screen is a backlit LCD: a pixel is lit or it is not.

| Token | Value | Where it goes |
| --- | --- | --- |
| `lcd` | `#FFA62E` | The backlight. Every ground, every unlit pixel |
| `ink` | `#1C1005` | Every lit pixel: text, borders, sprites, fills |
| `primary` | `ink` | An alias for tools that expect a primary colour. The accent of this system is ink |
| `scanline` | `#1C10050F` | The LCD pixel grid over the whole screen, and nothing else |

How colour is used:
- **Inversion means selected or live.** Ink fill with amber text marks the focused row, the
  chosen segment, a primary key, the busy bottom bar, a warning, the terminal panel. Nothing
  else is inverted.
- **Shade is dither, never grey.** 25% (`.d25`) for large panels like the trackpad, 50%
  (`.d50`) for a disabled state, and `.ghost` (amber dither knocked out of an ink sprite) for an
  empty slot such as a missing disc.
- **One theme.** The LCD has no dark mode, by decision (see Decided exceptions).

## Typography

Three pixel faces, each with one job. Load them from Google Fonts in one request:
`family=Jersey+10&family=Silkscreen:wght@400;700&family=VT323`.

| Token | Face | Size | Use |
| --- | --- | --- | --- |
| `display-hero` | Jersey 10 | 104px | Home's count (`3 UP`, `IDLE`) and the bind headline. One per screen at most |
| `display-title` | Jersey 10 | 72px | The title of every other screen (`STORAGE`, `LOG`) and the Identity ID digits |
| `label` / `label-strong` | Silkscreen | 16px | Menus, keys, row names, field labels. Always uppercase |
| `data` | VT323 | 22px | Paths, filenames, addresses, report bytes, log lines. Keeps its case |

- Four sizes exist: 104, 72, 22 and 16. Nothing else.
- Silkscreen is uppercase by `text-transform`. VT323 opts out, because `/dev/hidg1` and
  `Win11_24H2_x64.iso` are case-sensitive facts.
- Font smoothing is off (`-webkit-font-smoothing: none`). Pixel faces must stay pixel-sharp.

## Layout

The phone frame is 390×844.

| Token | Value | Rule |
| --- | --- | --- |
| `gutter` | 20px | Left and right edge of every screen |
| `top-inset` | 22px | Space above the top bar |
| `bottom-inset` | 68px | `gutter` plus `nav-inset`. Bottom keys sit above Android's navigation bar |
| `nav-inset` | 48px | Left empty for the system navigation bar. Nothing is drawn there |
| `section` | 16px | The gap between every block on a screen |
| `tight` | 12px | Gaps inside a block: between keys, between icon and text |
| `lines` | 5px | Between the stacked lines of one item |
| `touch` | 44px | Minimum height of anything tappable |
| `row` | 80px | Minimum height of a function row |
| `lun-cell` | 40px | Width of a LUN cell |

Screen anatomy, top to bottom:
1. **Top bar**, 44px: `< BACK` on function screens; Pin, `USB ARSENAL` and the version on Home.
   Nothing on the right unless it is a fact found nowhere else on the screen.
2. **Title** in `display-title` (`display-hero` on Home).
3. **Content.** Lists that scroll take `flex:1` so the keys never leave the screen.
4. **Bottom keys**, a two-column grid of `key`s, or Home's bottom bar.

Rules:
- **Everything sits on the gutter.** Rows bleed 10px past it and pad 10px back, so their text
  lines up with the title and the keys.
- **The LUN strip** is one 40px cell per LUN plus `+`. Eight cells fit the width; `+`
  disappears at eight, which is the backend's limit.
- **Tablet:** a 1280×800 frame. A 420px list pane on the left, the detail on the right, the
  bottom bar across both. Panes and columns are divided by 2px ink rules.

Responsive rules for the app:
- **Window size classes decide the layout, not the device.** Compact width (under 600dp) is
  the single-pane phone layout. Medium and expanded widths (600dp and up), including a phone
  in landscape and a foldable opened, show the list-detail panes.
- **Insets come from the system.** `WindowInsets.navigationBars` replaces the fixed 48px when
  the real bar is shorter (gesture navigation) or on the side (landscape). Keys never sit
  under a system bar.
- **Text is in `sp`.** Layouts must survive font scale 1.3: rows grow taller, long lines
  fall back to the marquee, nothing clips or overlaps.

## Elevation & Depth

The screen is flat. There are no layered surfaces, no shadows and no blur.

The one exception is the `key`: a 4px hard ink offset (`key-depth`) makes it a physical button.
Pressing it moves the key 4px into its own shadow. A disabled key loses the shadow and gets a
dashed border.

## Shapes

- Square corners everywhere. `rounded.none` is the only radius.
- Borders and rules are 2px of ink, the size of one LCD pixel (`pixel`).
- Separators are solid 2px ink lines or 2px ink dashes. No hairlines.
- Sprites sit on a 16px grid and are drawn at whole-number scales only.
- The one round shape is the launcher's round icon mask, applied by Android, not by us.

## Components

Class names are those in `docs/developer/design/lcd.css`.

### Function row (`.row`)
32px sprite, Silkscreen name, two VT323 status lines, DIP switch on the right. Selected row
inverts. Status lines show live facts only (`LUN0 Win11_24H2_x64.iso · dvd`, `via rmnet_data0`,
`keyboard /dev/hidg1`). While a function starts or stops, its lines read `starting` or
`stopping` and the command.

### DIP switch (`.dip`)
On or off for one function, 68×32 inside a 44px hit area. Knob right with `ON` printed is on;
knob left is off. Flipping it runs `<function> start` or `stop` at once. There is no pending
state and no apply step.

### Key (`.key`, `.key.pri`)
The only button. Plain keys are amber; the primary action of a screen (`Create`, `Save`,
`Copy logs`) is inverted. Bottom keys come in pairs in a two-column grid. One primary per pair
at most.

### Segmented control (`.segs`, `.seg`)
One choice among several: storage mode, NCM or RNDIS, log file, profile, power. Options are
divided by 2px ink. The chosen one is inverted. Labels must fit: `CD`, not `CDROM`.

### LUN strip (`.segs.luns`)
`0`, `1`, ..., then `+`. Left-aligned, 40px cells, `+` hidden at eight LUNs.

### Bottom bar (`.strip`)
Home only, flush to the screen edges, divided from the content by a 2px rule.
- **Bound:** Pin, `BOUND`, the UDC name (`a600000.dwc3`), then `STOP ALL` full width below.
- **Replugging:** inverted, Pin blinking, `REPLUGGING` with the cursor, the command running.
- **Port with Android:** Pin, `PORT WITH ANDROID`. No key; flipping any switch binds again.

### Drive (`.drive`)
150px box with an inverted 40px face: activity LED, `LUN0`, eject glyph. Its body shows the
disc, the filename and `size · type` with an access `.tag` (`ro` or `rw`). Ejected or empty,
the body shows a `.ghost` disc. The face never repeats what the body shows.

### Files list
Rows of `filename` and size, under `FILES` and the folder path. An image in use on another LUN
shows its LUN as a `.tag` before the size and cannot be picked. The list scrolls; the keys stay.

### Warning
An inverted VT323 line, shown only for a consequence: a CD-ROM image over 2.36G
(`cd-rom stops at 2.36G. the host reads a truncated disc`) and RW mode
(`host can write to this file`). Never for an explanation.

### Tether chain (`.node`, `.wire`)
Host, Phone (inverted), Upstream, joined by dashed 6px wires with stepping packets and a VT323
label (`ncm`, `masquerade`). Label left, value right. Addresses live in the key-value rows
below, not in the chain.

### Key-value rows (`.kv`)
Silkscreen label left, VT323 value right, 44px tall. For settings and facts, one per line.

### Input (`.inp`) and field (`.field`)
2px ink box, VT323 text, label above in Silkscreen. A placeholder states the default
(`from this phone`).

### Terminal (`.term`, `.ln`)
An inverted panel that fills the free height. It fills from the top down like a terminal and
scrolls only once it is full, held at the newest line; a line already drawn never moves. A
`[+] message` line puts its glyph in a 28px column so wrapped text lines up after it; any
other line (a prompt, stderr) uses the whole width (`.ln.wide`). The newest line flips back to
amber with the cursor.

### Shell window (`.win`, `.strip`)
Root and Backend play a shell session in the same window: a light 32px strip (the LED, what
runs in Silkscreen bold, `su` or `sh`, and the phone's codename on the right in VT323) over
the terminal, inside one 2px box. The codename is `ro.product.device` (`Build.DEVICE`, then
`Build.PRODUCT`, then `localhost`), so the prompts are just `$` and `#`. The LED blinks while
the session works, is lit when it succeeded, and is hollow when idle or refused.

A session is real commands with the app's narration as shell comments:
`# checking root access`, `$ su`, `# # root access granted !`, `# id -u`, `# su -v`, then an
idle `#`. Backend opens with `# usb-arsenal 1.2.0 for aarch64` and `# press install to install the
backend` (or `# update 1.1.0 → 1.2.0`, `# the gadget stays up while it updates` and `# press
update to update the backend`), waits at an idle `#`, and Install types
`./install-backend.sh`; the installer's `[+]` lines are its output. A denial prints what the
shell would have said (`su: inaccessible or not found`, su's own stderr, or the timeout).

### Trackpad
A `.d25` panel with a pixel pointer, a 44px wheel strip on its right, and an L/M/R segmented
bar under it. Live report bytes sit below it in VT323.

### Device panel (`.panel`, `.face`, `.led`, `.body`)
The drive's frame: a 2px box with an inverted 40px face (a LED and a short
name) over a 96px body. The LED is lit (`.on`) when the thing works, blinks (`.busy`) while it is
working or serving, and is hollow when there is nothing.

### Fact rows (`.kv.fact`) and group headings (`.grp`)
A fact that cannot be tapped is 32px tall, not 44, so long lists like Check fit. A Silkscreen
bold heading groups them (`MUST HAVE`, `FUNCTIONS`, `BACKEND`, `ABOUT`).

### Attention key (`.alert`)
Home's one line about the backend, inverted, above Log and Settings: the state on the left in
Silkscreen (`BACKEND UPDATE`, `NOT INSTALLED`, `MODULE MISSING`, `BACKEND BROKEN`, `NO ROOT`),
the fix on the right in VT323 (`1.1.0 → 1.2.0`, `install`, `reinstall`, `grant`). It is a key:
it opens Install or Root. While it shows, the switches cannot move, except on an update.

### Unavailable function
The row stays so the user learns what the phone lacks. Its icon is `.ghost`, its DIP switch is
`disabled` (a `.d50` track with no knob, skipped by focus), and its two status lines say why:
`no hid in this kernel` and `CONFIG_USB_CONFIGFS_F_HID`. The text stays full ink.

### Fault line (`.fault`)
The one pattern for a failed command, on every screen. An inverted bar directly under the
element that was acted on, holding stderr's last line (`[-] ...`, up to three lines) and a `LOG`
tag. It is a key that opens Log, and it stays until the next action. The element shakes for 3
frames with `Reject`, and switches show the real state again once `status --json` is re-read.
The app's own timeout reads `[-] Timed out (30s)`.

### Sheet (`.sheet`, `.scrim`)
A bottom sheet for a short task (Create). It rises over the screen with a 2px top rule and the
normal gutters, its own `display-title`, and its keys at the bottom. The screen behind is
shaded with a `.d50` scrim, so it reads as unavailable. Back or Cancel closes it.

### Progress (`.cells`)
Ten cells in a 2px box, filled in whole tenths, with the amount in VT323 below
(`1.6G of 4.0G`) and the cursor while it runs. Only for work long enough to wait on.

### Pin's places
Home top bar (32×36, blinking), the bottom bar, Welcome and the empty storage screen
(128×144), the bind animation, the splash, the app icon.

### Splash
Android's splash screen: the `lcd` ground and Pin at 6× (96×108dp), centred in the 288dp icon so
it stays inside the 192dp circle the system shows (with no icon background colour the system
lays the icon out at 288dp; a 240dp one is scaled up 1.2×). Pin blinks once, stepped (closed
from 360 to 440ms of 600ms). It leaves in one cut, never the system's fade. No scanline grid:
the system draws it, not us.

## Motion

Motion is frame-based. Things step, blink and cut, like an LCD redrawing. Nothing eases,
floats or fades.

**The frame clock.** Interaction motion is counted in frames of 40ms, and every duration is a
whole number of frames. Every animation uses step easing, `Easing { t -> floor(t * n) / n }`
with `n` the number of frames, so values jump between whole positions. Ambient loops keep the
step timings in the second table.

Interaction motion:

| Motion | Frames | Rule |
| --- | --- | --- |
| Screen change | 4 (160ms) | The LCD redraw: the new screen paints in four horizontal bands, top to bottom. Back paints bottom to top. The old screen stays drawn underneath but takes no touch, and focus leaves it |
| Typing | 2 per 3 characters | Whatever a person would type (titles, shell commands) types 3 characters every 2 frames, with the cursor at its end. A title types on each time its screen is drawn; the cursor's place is kept so it never re-wraps |
| Key press | 1 | Down 4px into its shadow on press, back on release |
| DIP switch | 1 | The knob snaps to the other side. No slide |
| Focus cursor | 0 | Inversion moves to the focused element on the next frame |
| Rows typing on | 1 per row | On first entry to a list, rows appear one per frame, six at most, then the rest at once |
| Error shake | 3 | 2px left, 2px right, home. Paired with the `Reject` haptic |
| Number change | 1 | Digits flip to the new value. No counting up |
| Bottom bar state | 2 | Bound, replugging and port with Android cut over in two bands |
| Sheet | 4 | Rises from the bottom; its scrim appears on the first frame. Back plays it in reverse |
| Fault line | 1 | Appears with the error shake |
| Tray | 6 (3 steps) | The drive tray slides out over the body on eject, and back in when a file is loaded, in 3 steps of 2 frames |
| Shell session | 1 per step | A prompt appears at once and the cursor holds on it for a beat (7 frames, 280ms), then the command types at typing speed. Enter: the output types 4 characters a frame with a beat between its lines, and the next prompt comes the moment it ends. The work behind a command starts the moment it has typed. The keys appear one beat after the idle prompt |
| Check answers | 4 per row | Check's cursors hold for at least one beat, then its rows resolve top to bottom, one every 4 frames. A probe that answers in 30ms would otherwise only flash |

Ambient loops:

| Motion | Timing | Where |
| --- | --- | --- |
| Cursor blink | 1s, `steps(1)` | Working states, the live terminal line, the bind headline |
| Pin double-blink | 2.6s loop | Everywhere Pin is shown large or in the top bar |
| Marquee | 8s, `steps(32)`, alternate | Text that does not fit its line (`.st`). Text that fits stays still |
| Packets | 1.2s, `steps(4)` | Tether wires |
| Activity LED | 1.2s blink pattern | The drive face while serving |
| Bind | 5s loop | Pin climbs into the host port, sparks, one inverted flash frame, `GADGET BOUND` |

Rules:
- Only stepped timing. Animate position, visibility and inversion; never opacity ramps,
  scale or rotation.
- **Reduced motion.** When the system "Remove animations" setting is on (Animator duration
  scale 0, which Compose applies through `MotionDurationScale`) or the in-app motion switch is
  off, every loop shows its resting frame: Pin plugged in, text at its start, cursor visible,
  LED lit. Infinite loops must check this themselves, because a zero duration scale only
  finishes finite animations. Screen changes become a cut. The app reads
  `Settings.Global.ANIMATOR_DURATION_SCALE` and watches it with a `ContentObserver`, which
  works on every API level (`ValueAnimator.registerDurationScaleChangeListener` is API 33).
- The marquee is the only motion applied to text.
- One playful moment at a time. The bind animation plays when the gadget first binds, not on
  every replug.

## Haptics

Haptics make the switches and keys feel like hardware. They follow Android's haptics
principles: clear and crisp, never buzzy, in sync with the frame they belong to.

Principles:
- **No haptics beats buzzy haptics.** Never use `VibrationEffect.createOneShot`,
  `Vibrator.vibrate(long)` or a hand-made waveform for UI feedback.
- **Frequent events are subtle, important events are strong.** A focus move is a tick; a
  bind is a sequence.
- **Same interaction, same effect,** everywhere in the app.
- **Fire on the frame the visual changes,** never before the state changes.
- **Respect the user.** No haptics when the system touch feedback setting is off, when the
  in-app switch is off, or while the app is in the background. Below API 33 the app reads
  `Settings.System.HAPTIC_FEEDBACK_ENABLED`; from 33 that constant is deprecated and the system
  applies the setting to `performHapticFeedback` and `USAGE_TOUCH` vibrations itself.

Event map. Simple effects go through Compose's `LocalHapticFeedback` (`HapticFeedbackType`);
the rich ones go through the tiers below.

| Event | Effect |
| --- | --- |
| Key press | `VirtualKey` |
| DIP switch on / off | `ToggleOn` / `ToggleOff` |
| Segment change, LUN switch, D-pad or keyboard focus move | `SegmentTick` |
| Trackpad drag | `SegmentFrequentTick`, at most one per 8px of travel |
| Gadget bound (the bind animation) | Rich: three rising ticks as Pin climbs, a thud on the frame it plugs in |
| Stop all, eject | `GestureEnd` |
| Command refused or failed | `Reject`, with the error shake |
| Save, image created, install finished | `Confirm` |
| Root denied, Check blocked, install failed | `Reject` |
| Warning shown | None. A warning is read, not felt |

Device tiers for the rich effects, chosen once at start-up and cached:

| Tier | When | How |
| --- | --- | --- |
| T1, flagship | API 36 and later, `Vibrator.areEnvelopeEffectsSupported()` and `PRIMITIVE_TICK` supported | The climb ticks as in T2. The thud is a `VibrationEffect.BasicEnvelopeBuilder` (API 36): low sharpness, up in 20ms, down in 60ms. An envelope starts and ends at zero intensity, with control points at least 20ms apart |
| T2 | API 30 and later and `Vibrator.areAllPrimitivesSupported(...)` is true for every primitive used | `VibrationEffect.Composition`: `PRIMITIVE_TICK` (API 30) at scales 0.4, 0.6, 0.8, then `PRIMITIVE_THUD` (API 31; `PRIMITIVE_CLICK` at 1.0 where it is missing). One unsupported primitive silences the whole composition, so check first |
| T3 | API 29 and later | Predefined effects: `EFFECT_TICK` for the climb, `EFFECT_HEAVY_CLICK` for the thud |
| T4 | Anything else | `View.performHapticFeedback` with `CONFIRM` (API 30) or `CONTEXT_CLICK` |

- Amplitude scales only mean something when `Vibrator.hasAmplitudeControl()` is true; without
  it, keep to presets, primitives at full scale, or predefined effects.
- `HapticFeedbackType` values newer than the device fall back inside Compose. Verify each
  constant's API level against the `HapticFeedbackConstants` reference before relying on it
  (Confirm, Reject and GestureEnd are API 30; ToggleOn, ToggleOff, SegmentTick and
  SegmentFrequentTick are API 34).
- Vibrations play with `VibrationAttributes.USAGE_TOUCH` on API 33 and later, and need the
  `VIBRATE` permission. `performHapticFeedback` does not.

## Input and focus

Every screen is fully usable with touch, a hardware keyboard, a D-pad, and a gamepad or
joystick. A pocket tool gets used with whatever is plugged in.

The focus cursor is the LCD cursor:
- **Rows, segments and file rows invert** when focused, exactly like the selected state.
- **Keys, DIP switches and inputs** get a 2px dashed ink outline, 3px outside the element,
  because they have no inverted resting state.
- Focus is drawn by our components. Never rely on a default indication.

Key map:

| Input | Action |
| --- | --- |
| Arrows, D-pad, left stick | Move focus in that direction |
| Enter, D-pad center, `BUTTON_A` | Activate (press the key, flip the switch, open the row) |
| Back, Escape, `BUTTON_B` | Back |
| Tab, Shift+Tab | Next and previous element in reading order |
| `BUTTON_L1`, `BUTTON_R1` | Step the focused segmented control or the LUN strip |

Rules:
- **Initial focus** goes to the first row on Home and the primary key or first row on every
  other screen, requested from a `LaunchedEffect`, never from the composable body.
- **Focus comes back** to where it was after Back (`focusRestorer` on each list).
- **Every focus move** plays `SegmentTick`.
- **The HID screen hands the gamepad to the host:** the left stick moves the host pointer,
  `BUTTON_A` is a left click, `BUTTON_X` a right click, and `BUTTON_B` still means Back.
- Built with `focusable`, `focusGroup`, `focusProperties` and `onKeyEvent` /
  `onPreviewKeyEvent`.

## Pixel art and Pin

Sprites are bitmaps written as rows of `#` (lit) and `.` (unlit), turned into one SVG path at
build time and drawn with `shape-rendering: crispEdges` in `currentColor`, so they invert with
their row.

Pin is the mascot and the app icon: a USB-A plug that walks. The two square holes in a real
USB-A shell are its eyes; its cable is its tail. Its 16×18 bitmap:

```
...##########...
...#........#...
...#.##..##.#...
...#.##..##.#...
...#........#...
..############..
.##############.
.##############.
.######..######.
.##############.
.##############.
..############..
....##.##.##....
....##.##.##....
...###.##.###...
.......##.......
.......####.....
..........##....
```

The blink frame replaces rows 2 and 3 (counting from 0) with `...#........#...` and `...#.##..##.#...`.

Function icons are 16px sprites: `storage` (disc), `tether` (RJ45 jack), `hid` (keyboard).
Others: `usb` (trident), `eject`, `spark`, `pointer`. All of them are in the Pixel kit screen.

App icon: Pin in ink on an `lcd` square, scaled to about 63% of the icon so a launcher mask
never clips it (288×324 on 512, 96×108 on 192, 32×36 on 48). The themed icon inverts.

## Copy

The app talks like firmware: short, uppercase in Silkscreen, exact in VT323.

**Say it once.** A fact appears once per screen. These were all removed, and must not return:
- A status line that repeats what an element already shows: `host sees dvd` under a mode
  switch that says DVD, `serving` on a drive whose LED is blinking, `configured` after `BOUND`.
- A header note that echoes the title or a box below it: `dvd · read-only · lun0` beside
  `STORAGE` when the drive box says it.
- Attribution nobody asked for: `· by arsenal` after an address.
- Explanatory subtitles under controls: `hand the link to a container`, OS hints beside NCM.
- Paths and meta in the top bar that push `< BACK` out of place.
- A second label for the same thing (`Upstream` as a node and as a row).

Helper text exists only to prevent a mistake (`lsusb: Linux Foundation Multifunction Composite
Gadget` under the USB IDs, so a user knows what the host will call it). Warnings exist only for
consequences.

Samples on the reference screens are sample data: filenames, the UDC name, addresses, traffic
counts.

## Backend truth

A control exists only if the backend can do it. Check `src/` before adding one.

| UI | Backend |
| --- | --- |
| Function DIP switches | `storage` / `tether` / `hid` `start` and `stop` |
| Stop all | `usb-arsenal stop` |
| Eject | `storage eject` |
| Mode segments | `storage start --mode auto\|cdrom\|dvd\|disk\|rw` |
| LUN strip | `--lun 0-7` |
| NCM / RNDIS | `tether start` / `tether start --rndis` |
| Gateway, container, its iface | `--gateway CONTAINER`, `--gateway-iface IFACE` |
| HID interval | `hid start --interval N`, period 2^(N-1)×125µs, set at start |
| Identity fields, Save, Reset | profile keys, `identity set FILE`, `identity unset` |
| Log tabs | command output (CLI) and `Logs/usb-arsenald.log` (Daemon) |
| Function names in the bind screen | `mass_storage.ua`, `ncm.ua`, `hid.ua_kbd`, `hid.ua_mouse` |
| Root screen | `id -u; su -v` (prints `3.3.0:KernelSU` or `<version>:MAGISK`), shown as a shell session; its `#` comments are the app's narration |
| Check screen | `check --json`, run with the bundled binary before anything is installed |
| Backend screen | the ported installer: the binary to `/data/local/USB-Arsenal/bin`, `module/` to `/data/adb/modules/usb-arsenal`, each step printing what it really printed, then `[+] Installed`, and `[!] Reboot to load the SELinux rule` only when the module had no rule before. The daemon is Settings' daemon mode, never the install's. `./install-backend.sh` is how the session shows it; there is no such script. The version is the app's own, never asked of root |
| Home attention key | the installer's backend check: not installed, module missing, corrupted, update available, no root |
| Home status, live rows | `status --json` |

Every command runs through root in its own short-lived shell with a timeout (see AGENTS.md),
so one stuck command can never freeze the screen.

App-side features, done by the app itself: the `/sdcard/USB-Arsenal` file list, Create (a sparse
image, as `truncate -s` does, or a full one with progress), HID input written to `/dev/hidgN`,
and the Motion and Haptics switches in Settings.

Not shown because the backend cannot: UVC (not built), host controller or port names, lease
countdowns, link uptime, a configfs write trace, a pending-changes queue.

## Do's and Don'ts

Do use two colours: `lcd` and `ink`.
Do invert to mean selected or live, and for nothing else.
Do shade with dither.
Do keep every corner square and every border 2px.
Do keep anything tappable at least 44px tall.
Do put a heading on every screen and nothing beside `< BACK`.
Do leave the bottom 48px empty for the navigation bar.
Do step every animation and honour reduced motion.
Do make every screen work with a keyboard, a D-pad and a gamepad.
Do play the haptic from the event map, on the frame the visual changes.
Do draw sprites at whole-number scales on the 16px grid.
Do map every control to a backend command before drawing it.

Don't use a gradient, glow, blur, soft shadow or radius.
Don't add a third colour, a grey, or a translucent fill other than the scanline grid.
Don't use any face other than Jersey 10, Silkscreen and VT323, or any size other than 104, 72, 22 and 16.
Don't repeat a fact on the same screen.
Don't explain a control under the control.
Don't draw a fake status bar or navigation bar.
Don't use emoji or icon fonts. Sprites only.
Don't ease, fade or float anything.
Don't use `createOneShot` or a hand-made waveform for touch feedback.
Don't play a rich haptic without checking the device tier first.
Don't show a feature the backend does not have.
Don't use an em dash. Use a comma, a full stop, or rewrite the sentence.

## Decided exceptions

Looked at and kept. Do not "fix" these without a reason the original one misses.

- **The scanline grid is translucent.** It is the LCD's own pixel grid at 6% ink, the only
  non-solid colour, and it sits over everything with `pointer-events: none`.
- **Keys have a shadow.** A hard 4px ink offset, because a key is a physical object. It is the
  only depth on the screen.
- **Labels are uppercase.** Silkscreen is a menu face and the firmware voice needs it. VT323
  keeps its case.
- **The terminal is a whole inverted panel.** Live output reads as live; the newest line flips
  back to show where output ends.
- **One theme.** An LCD has one look. Both system themes get the same screen.
- **The marquee moves text.** Only for text that does not fit, in steps, so long filenames stay
  readable without wrapping the row.
