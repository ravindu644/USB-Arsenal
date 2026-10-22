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
- **Secondary screens** (Log, Identity) sit outside the function list, as keys above the bar.
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
An inverted panel that fills the free height. Lines are `[+] message`, newest at the bottom.
The newest line flips back to amber with the cursor.

### Trackpad
A `.d25` panel with a pixel pointer, a 44px wheel strip on its right, and an L/M/R segmented
bar under it. Live report bytes sit below it in VT323.

### Pin's places
Home top bar (32×36, blinking), the bottom bar, the empty storage screen (128×144 with a pixel
`?`), the bind animation, the app icon.

## Motion

Motion is frame-based. Things step, blink and cut. Nothing eases, floats or fades.

| Motion | Timing | Where |
| --- | --- | --- |
| Cursor blink | 1s, `steps(1)` | Working states, the live terminal line, the bind headline |
| Pin double-blink | 2.6s loop | Everywhere Pin is shown large or in the top bar |
| Marquee | 8s, `steps(32)`, alternate | Text that does not fit its line (`.st`). Text that fits stays still |
| Tray | 0.3s, `steps(3)` | The drive tray sliding out on eject |
| Packets | 1.2s, `steps(4)` | Tether wires |
| Activity LED | 1.2s blink pattern | The drive face while serving |
| Bind | 5s loop | Pin climbs into the host port, sparks, one inverted flash frame, `GADGET BOUND` |

- Only `steps()` timing. Transform and visibility only.
- `prefers-reduced-motion: reduce` drops every animation and shows the resting frame: Pin
  plugged in, text at its start, cursor visible.
- The marquee is the only motion applied to text.

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

App-side features, done by the app itself: the `/sdcard/USB-Arsenal` file list, Create (a sparse
image, as `truncate -s` does), and HID input written to `/dev/hidgN`.

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
Do step every animation and honour `prefers-reduced-motion`.
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
