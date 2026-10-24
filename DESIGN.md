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
    typography: "{typography.data}"
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
  slider:
    backgroundColor: "{colors.lcd}"
    textColor: "{colors.ink}"
    typography: "{typography.data}"
    height: 44px
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
(`NO IMAGE LOADED`, `READ-ONLY DISK`), it never shows a control the backend cannot
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
  which leads back to Root or Install only when the backend needs it. Setup's top bar holds no
  `< BACK` (Back still goes back) but its steps, centred LEDs 12px apart, lit up to the one on
  screen: Root 1 of 3, Check 2, Backend 3 (Root 1 of 4, Controller 2, Check 3, Backend 4 with
  several controllers). Welcome has none.
- **Controller** comes between Root and Check, only when `/sys/class/udc` lists more than one
  controller (`dummy_udc.*`, built into every GKI kernel, never counts). Root reads them once
  su says yes (the list needs root), and Controller's step joins Root's dots then: the row
  re-centres and the new LED blinks twice before it holds. `This phone has more than one USB
  controller. Pick the one your cable is in.`, then one Home-style row each: Pin happy (`^ ^`)
  on a `configured` controller and dozing on any other, its name, its state, and what that
  state means: `a computer is using it` (configured), `plugged in, the computer is asleep`
  (suspended), `plugged in, not set up by a computer` (attached, powered, default, addressed:
  a charger stops here), `nothing plugged in` (not attached); any other state has no line. The
  picked row is inverted, and `Next` waits for a pick. Check then runs with that `--udc`, and
  the pick goes into the backend's setup (`setup set udc=NAME`), so every command uses it and
  none carries a flag; with one controller nothing is asked. The controllers are read once a
  launch; a pick is never dropped by the app (a controller in host mode comes back), and a
  set-up app with several and no pick shows this step over Home, where Next goes back.
- **What setup finds is cached across launches.** Back never asks su or the kernel again; only
  Recheck does. A root manager takes a grant at the app's next start, so a denial has no retry,
  only the shade below. A result that was already there shows at once; only one that arrives on
  screen types or resolves.
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
1. **Top bar**, 44px: `< BACK` on function screens; Pin, `USB ARSENAL` and the version on Home;
   the steps (`.steps`) in setup.
   Nothing on the right unless it is a fact found nowhere else on the screen.
2. **Title** in `display-title` (`display-hero` on Home).
3. **Content.** A terminal or a shell takes the free height (`flex:1`), and never less than 240px
   (200px for a shell).
4. **Bottom keys**, a two-column grid of `key`s, or Home's bottom bar.

In landscape the content keeps clear of the cutout and the navigation bar, while Home's bottom
bar still runs edge to edge, its content inset. When the window is too short for a screen (landscape, split screen, a large font), the screen
scrolls as one page: the top bar and title scroll away with the content, and the bottom keys
stay pinned above the navigation bar, as a sheet's do. A tall window shows the screen exactly
as drawn, with no scroll. Home's Log and Settings keys belong to the content (they sit under
the rows), so they scroll with it.

### Scrollbar (`.scrollbar`)
Anything that scrolls shows a 4px ink thumb, 4px from the screen's right edge, in the gutter
clear of a row's or a rule's 10px bleed; inside a terminal it is amber, 4px from the panel's
edge. A terminal with nothing to scroll is no focus stop. Its length is the share of the content in
view (24px at least), it is drawn on the pixel grid, and it shows only while there is more to
scroll. It never moves on its own, so reduced motion leaves it as is.

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

### Functions: set up, then switch on at Home
Storage, Tether and HID work one way.
- **A function's screen only configures.** It edits the setup the backend keeps (`setup.conf`:
  picks per LUN with their modes, every `tether start` option, the HID interval), which the
  command line shares. It never touches the port and has no switch.
- **Home's row** opens that screen (`STORAGE >`), and its DIP is the only switch. It runs a bare
  `<function> start`, which starts the whole setup in one command, so every LUN comes up in one
  replug.
- **A change while the function runs** waits until Back. Back then asks, in a sheet, `Not
  applied`, with one line saying what applying does to the host. `Discard` puts the setup back
  as the function started; `Apply` applies it, and Home's bar shows it replugging. Either way
  the screen is left, so it never shows a setup that is not what runs. Nothing else on the
  screen says a change waits.
- **Apply is the backend's `<function> apply`,** decided against the kernel. Storage applies LUN
  by LUN, so the host keeps every drive it can: a LUN no longer picked is ejected in place, a
  changed image on a live LUN swaps in place when the type stays the same, a LUN already serving
  its pick is left alone. A new LUN, a new type, or a drive the host holds locked replugs, and
  with it every drive and function. Tether and HID swap their function in one replug of the whole
  gadget, so every drive and function reconnects with it. Whether a change waits (`pending`) is the backend's too, from its two
  files, whoever started the function; a function started from the command line asks the same.
  The chain, the counters and a drive's LED keep showing what runs; the LUN strip shows the
  setup and what runs.

### Function row (`.row`)
32px sprite, Silkscreen name with ` >` (the row opens its screen), two VT323 status lines, DIP
switch on the right. Selected row inverts, and a 2px rule divides one row from the next. On,
the status lines show live facts only (`LUN0 Win11_24H2_x64.iso · dvd`, `via rmnet_data0`,
`keyboard /dev/hidg1`). Off, they say what the function does (`ISOs and disk images` / `as
drives for the host`): the DIP already says off. While a function starts or stops, its lines
read `starting` or `stopping` and the command.

### DIP switch (`.dip`)
On or off for one function, 68×32 inside a 44px hit area. Knob right with `1` printed is on;
knob left with `O` is off, both in VT323 like a status line. The knob is 30×36: it runs over
the track's edge on its side and stands a dot past it above and below, so where it sits reads
at a glance. Flipping it runs `<function> start` with the screen's setup, or `stop`, at
once. It lives on Home's rows, and as a setting inside a screen (`Gateway`, `DHCP and NAT`).
It has two dead looks. `disabled` (a `.d50` track, no knob, skipped by focus) is only for what
the phone lacks: a function the kernel did not build, Droidspaces not installed, no NAT. `held`
(`.dip.held`) keeps the knob where it is and dashes the track's border, as a disabled key's: it
keeps focus but takes no flip, for a command running, a value not read yet, a backend that needs
fixing, or storage with nothing picked. Its haptic plays on the frame the knob moves, which for
a function is when its command ends.

### Key (`.key`, `.key.pri`)
The only button. Plain keys are amber; the primary action of a screen (`Create`, `Save`,
`Copy logs`) is inverted. Bottom keys come in pairs in a two-column grid. One primary per pair
at most.

### Segmented control (`.segs`, `.seg`)
One choice among several: storage mode, NCM or RNDIS, power. Options are
divided by 2px ink. The chosen one is inverted. Labels must fit: `CD`, not `CDROM`. An option
the kernel cannot honour sits on a `.d50` shade with its label in full ink (a ghost's dither
would eat Silkscreen's strokes), and is never picked or focused.

### Slider (`.slider`)
One value from a few dozen steps, in one 44px row: the label, a 2px track with a 28px pixel dot
on it, then the value in VT323 at its end (`1.0x`). A tap or a sideways drag puts the dot on the
step under the finger; it jumps a step at a time, never slides. D-pad Left and Right and L1 and
R1 step it, and at either end Left and Right move focus on. TalkBack reads it as a range with
its value. It lives on Keys + mouse (`Speed`).

### LUN strip (`.segs.luns`)
`0`, `1`, ..., then `+`. Left-aligned, 40px cells, `+` hidden at eight LUNs.

### Bottom bar (`.strip`)
Home only, flush to the screen edges, divided from the content by a 2px rule. One height in
every state, so nothing above it moves: a 72px stage, then a row with the state, one detail
line and a 140px `STOP ALL`, dashed (disabled) whenever there is nothing to stop. On the stage
the host's port hangs from the top, a dotted floor runs along the bottom, and Pin acts out
what the port is really doing:
- **Port with Android:** Pin dozes at home on the left, a `z` rising. No detail line.
- **Replugging:** inverted, `REPLUGGING` with the cursor and the command. Pin walks to the port,
  hopping, and hops under it until the command ends; from bound he pops out first.
- **Bound:** `BOUND`, the UDC name (`a600000.dwc3`), `STOP ALL` live. Pin is plugged in the
  moment the gadget is bound, wherever he had got to, `^ ^` eyes, sparks now and then.
- **Stopping:** inverted, `STOPPING` with the cursor and the command. Pin tugs at the plug; when
  the port is Android's again he pops out and walks home.
- **Failed:** after a command fails, for 2.4s or until the next command starts: `FAILED` (the
  fault itself is the fault line, above the bar), Pin runs into the port's edge, `x x` eyes and a
  star beside him. Still bound, he stays plugged in with `x x` eyes. `STOP ALL` stays live if the
  gadget is still bound.
- **Port with Android, an identity set:** set with nothing bound, it replugs nothing, so the bar
  stays `Port with Android` with `switching identity` as its detail.

### Drive (`.drive`)
150px box with an inverted 40px face: activity LED, `LUN0`, eject glyph. Its body shows the
disc, the filename and `size · type` with an access `.tag` (`ro` or `rw`). Ejected or empty,
the body shows a `.ghost` disc. The face never repeats what the body shows. The body is the
setup, the face what runs: the LED blinks while the host is served, an ejected pick too, is lit
for a pick not served yet, and hollow with neither. The type is the kernel's (`status --json`)
while that file runs, else the setup's, as the segments name it (`rw` is a `disk`, its tag says
the access), and the size is read for a file picked from outside the folder too.

### Files list
Rows of `filename` and size, under `FILES` and the folder path. An image in use on another LUN
shows its LUN as a `.tag` before the size and cannot be picked. The list scrolls; the keys stay.
An empty folder with nothing picked shows Pin and `No image loaded` where the rows go, under
`FILES`, so the mode above never moves when the listing comes back.

### Warning
An inverted VT323 line, shown only for a consequence the screen does not already show: a CD-ROM
image over 2.36G (`over 2.36G: the host reads a truncated disc`). Never for an explanation.

Under the storage mode segments, one line always holds the chosen mode's hint (`read-only
disk`), helper text because the wrong mode is a dead or truncated drive. The warning takes that
same line, inverted, so a mode change never moves the files below.

### Tether chain (`.node`, `.wire`)
Host, Phone (inverted), Upstream, joined by dashed 6px wires with stepping packets and a VT323
label (`ncm`, `masquerade`). Label left, value right. Addresses live in the key-value rows
below, not in the chain. The last node is its own setting, so nothing is named twice: `Upstream`
holds the input a start pins (`--upstream`), its placeholder what runs (`rmnet_data0`) or `auto`,
and in gateway mode the node is the container, which opens its Choice (` >`), with the iface it
runs on (or `waiting`, or `not running`) as its value.

### Key-value rows (`.kv`)
Silkscreen label left, VT323 value right, 44px tall. For settings and facts, one per line. A
row that opens something (`a.kv`: Identity, Fonts, a Choice) ends its value with ` >`, as
Home's rows end their name.

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
Root, Check and Backend play a shell session in the same window: a light 32px strip (the LED, what
runs in Silkscreen bold, `su` or `sh`, and the phone's codename on the right in VT323) over
the terminal, inside one 2px box. The codename is `ro.product.device` (`Build.DEVICE`, then
`Build.PRODUCT`, then `localhost`), so the prompts are just `$` and `#`. The LED blinks while
the session works, is lit when it succeeded, and is hollow when idle or refused.

A session is real commands with the app's narration as shell comments:
`# checking root access`, `$ su`, `# # root access granted !`, `# id -u`, `# su -v`, then an
idle `#`. Backend opens with `# usb-arsenal 1.2.0 for aarch64` (or `# update 1.1.0 → 1.2.0` and
`# the gadget stays up while it updates`) and types `./install-backend.sh` at once: opening it
(setup's Next, Home's update, Settings' Reinstall) was the ask, so it has no Install key, only
`Home`, dashed until the install ends; one that failed has `Copy log` and `Retry` in its place.
Opened from Home or Settings, `< BACK` is there from the start, dashed while the install runs. The installer's `[+]` lines are its output, typed straight
through as they come, with no beat between them. A denial prints what the
shell would have said (`su: inaccessible or not found`, su's own stderr, or the timeout).
Check types `# checking this kernel for usb arsenal` and `usb-arsenal check`, which runs the
moment it has typed; check's own report is the output, its blank lines included (no colour, no
indent, and its marks as `[+]` and `[-]`, since VT323 has no check mark or cross), so
a missing line names the kernel option it needs, and a line that needs a function (CD-ROM, the
HID interval) shows only with it. The LED is lit when every must have line is ok. Its keys,
`Recheck` and `Next`, wait for the report to finish typing. The report came as one piece, so it
types straight through, with one beat after it, not one between its lines.

**No root** shades the page, as HID off does: the D50 dither takes every touch, and an inverted
box steps in with Pin in tears and `Grant root, then reopen the app`. No key: root managers take
a grant at the app's next start. It covers Root when su says no, and every screen of a set-up
app that finds no root at launch. On Root the box sits on the terminal, so there it is light: lcd
ground, ink Pin and words.

### Trackpad
A `.d25` panel with a pixel pointer where the finger is, a 44px wheel strip on its right, and an
L/M/R bar under it. A drag moves the host pointer, a tap clicks, and a button is down for as
long as it is held. No report bytes are shown. Under L/M/R, `CONFIGURE`: `Speed` (a slider,
0.25x to 4x in quarters; 1.0x is half a host pixel per pixel of drag) and `Natural scroll` (a
DIP, on by default: the wheel strip moves content with the finger). On Keys + mouse the pad
takes the height those leave, never more than the Keys tab's height above the `TYPE` box.

While HID runs, a 44px key with the expand glyph sits in the pad's top-left corner. It makes
the pad full screen: landscape, the system bars hidden, MOUSE | KEYS, the title, Speed and
Natural scroll gone. The pad fills the window inside a 20px gutter, and under it a row holds
the collapse key and L/M/R. The collapse key or Back leaves; HID stopping leaves too. An edge
swipe shows the bars for a moment over the pad, and the pad claims its side edges from the
system's Back gesture. Turning the phone sideways on the Mouse tab while HID runs enters full
screen by itself; left while still sideways (a window under 600px tall), the Mouse tab
scrolls as a whole, so nothing is squeezed, and the pad takes half the window.
- **Platform:** `WindowInsetsControllerCompat` (androidx.core; API 30's `WindowInsetsController`,
  the old system UI flags on API 26 to 29) hides `systemBars()` with
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`. The activity asks for
  `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` (API 9) and handles the rotation itself. With
  auto-rotate off, that request moves the locked rotation, so leaving asks for the
  orientation the phone was held in rather than giving the request back, and the screen gives
  its original request back when it closes.

### Card (`.card`)
A big way into a screen, in the drive panel's look: an inverted 40px face with the name and
`>`, over a body with a 4x sprite and one VT323 line. The whole card is one key. Focused or
held it inverts (the face goes light, the body dark, the 2px edge stays ink), and a tap keeps
it inverted for 2 frames before the screen changes, so a quick tap is still seen to land. HID's
cards: `Keyboard and mouse` (the nodes while HID runs, `a trackpad and keys` before) and `Quacky` (`WIP`).

### HID, Keys + mouse, Quacky
- **HID:** the two cards, then `Interval`: the period on the left (one line, sliding if it does
  not fit), `-`, the exponent and `+` on the right, 1 to 16. The period is for the bus the host
  gave the gadget: 2^(N-1)×125µs at high speed, N ms on a USB 1.1 host. It is HID's setup; changed while HID runs, it waits for the `Not
  applied` sheet.
- **Keys + mouse:** `Mouse` and `Keys` tabs.
  - Mouse is the trackpad and `L M R`. The pad is the first focus stop and takes the dashed
    cursor; the wheel strip is a stop too, where Up and Down turn the wheel a step.
  - Keys is `F1` to `F12`, the `ESC TAB BKSP DEL / SHIFT CTRL ALT SUPER / INS HOME END PRTSC /
    ENTER PGUP PGDN` table, the arrows as an inverted T, and `TYPE`: a label over a tall box
    whose text goes to the host as typed (deleted text as Backspace), with no placeholder. Keys
    are 44px tall. The box keeps its text across a trip to the Mouse tab.
  - Modifier keys latch, shown inverted, for the next key or character.
  - With HID off the page is drawn under a D50 dither that takes no touch or focus, and an
    inverted box with Pin, `Enable HID on Home to use this page`, steps in over it; a kernel with
    no HID says `No HID in this kernel` instead.
- **Quacky:** not built yet. The duck beside Pin and `Under construction`; never shaded.

### Device panel (`.panel`, `.face`, `.led`, `.body`)
The drive's frame: a 2px box with an inverted 40px face (a LED and a short
name) over a 96px body. The LED is lit (`.on`) when the thing works, blinks (`.busy`) while it is
working or serving, and is hollow when there is nothing.

### Fact rows (`.kv.fact`) and group headings (`.grp`)
A fact that cannot be tapped is 32px tall, not 44, so long lists fit. A Silkscreen
bold heading groups them (`BACKEND`, `ABOUT`).

### Rule (`.rule`)
2px of ink across the 10px bleed, the line between Home's rows (and Controller's). Elsewhere it
parts only Settings' sections and Storage's mode from `FILES`. Nowhere else: a panel's box or a
heading already parts the rest (Storage's drive from its mode, Identity's panel from `PROFILES`,
Tether's counters from `CONFIGURE`, HID's cards from `Interval`, Keys + mouse's L M R from
`CONFIGURE`).

### Settings
In sections, a rule between each: `APP` (Motion, Haptics), `IDENTITY` (one row, `Profile` and the
profile in force by its name as the VT323 value, `Built-in` with none, opening Identity),
`BACKEND` (System path, Reinstall), `CREDITS` (Fonts) and `ABOUT` (App, Source), last. System
path is held until read, and while no backend is installed. Under it one line is kept, so the
sections below never move: a failed write's fault, or, once it is away from what it was first
read as, the warning `applies from the next boot`. With more than
one controller a `USB` section after Identity holds `Controller`, the pick, opening a choice
sheet with each controller's state; while the gadget is bound it is held, noted `stop all to
change it`, as the backend keeps the controller it holds.

### Identity
The profile picked here as a panel in the drive's look: an inverted face with an LED (blinking
while the host sees the gadget) and the name, over the USB sprite, product, maker, `vid:pid · N
mA` and its serial (`serial from this phone` when it has none), its body four lines tall whether
or not they are read yet. An identity command's fault sits under the panel (Home never shows it),
and a profile link whose file is gone says so there: `/…/x.conf is gone: the host gets Built-in`. Under it, `PROFILES` and the folder, then the rows like
`FILES`: `Built-in` first, then each profile with its `vid:pid`, the picked one inverted. `Edit`
(dead on Built-in) and `Create` open one sheet in the Create sheet's look: Name (locked when editing,
its border dashed), Product, Maker, Serial (empty: the phone's own), USB IDs as two four-digit inputs,
Power `100 / 250 / 500` segments with `mA` beside them in VT323 (Silkscreen would print `MA`); each
field says what is wrong under it, and the key waits until nothing is. With no gadget on the bus
a pick is set at once; while the host sees it, a pick waits for the `Not applied` sheet on Back,
whose Apply reconnects the gadget once.
### Attention key (`.alert`)
Home's one line about the backend, inverted, above Log and Settings: the state on the left in
Silkscreen (`BACKEND UPDATE`, `NOT INSTALLED`, `MODULE MISSING`, `BACKEND BROKEN`), the fix on
the right in VT323 (`1.1.0 → 1.2.0`, `install`, `reinstall`). It is a key: it opens Install. No
root is the shade, not this key. While it shows, the switches cannot move, except on an update.

### Unavailable function
The row stays so the user learns what the phone lacks. Its icon is `.ghost`, its DIP switch is
`disabled` (a `.d50` track with no knob, skipped by focus), and its two status lines say why:
`no hid in this kernel` and `CONFIG_USB_CONFIGFS_F_HID`. The text stays full ink.

### Fault line (`.fault`)
The one pattern for a failed command, on every screen. An inverted bar under the element that
was acted on, holding stderr's last line (`[-] ...`, up to three lines) and a `LOG` tag. Where
that would move what is below it, it goes where nothing moves: on Home at the end of the
content, above the bar, for a row as for the whole gadget (the row shakes); on Storage over its
keys; in Settings in the line kept under the Backend switches. A command refused because another
runs is a fault too (`[-] Another command is running`). It is a key that opens Log, and it stays until the next action. The element shakes for 3
frames with `Reject`, and switches show the real state again once `status --json` is re-read.
The app's own timeout reads `[-] Timed out (30s)`.

### Choice
A choice from a list the phone has (a gateway's container). A `LinkRow` names it (`Container  OpenWrt >`), `none` until
something is chosen, and opens a sheet of rows like the files list, the chosen one inverted.
With nothing to choose, the sheet holds one plain line (`No containers are running`, or `Cannot
read Droidspaces' containers`). A ghost is for sprites: its dither knocks VT323's strokes out.

### Sheet (`.sheet`, `.scrim`)
A bottom sheet for a short task (Create, a choice). It rises over the screen with a 2px top rule and the
normal gutters, its own `display-title`, and its keys at the bottom. The screen behind is
shaded with a `.d50` scrim, so it reads as unavailable. Back, Cancel or a tap on the scrim
closes it (not while it works: Create writing an image). The sheet
rides on the keyboard and stops under the status bar; when its body does not fit, the body
scrolls and the keys stay pinned above the keyboard, so a field never sits under it.

### Progress (`.cells`)
Ten cells in a 2px box, filled in whole tenths, with the amount in VT323 below
(`1.6G of 4.0G`) and the cursor while it runs. Only for work long enough to wait on.

### Pin's places
Home top bar (32×36, blinking), the bottom bar's stage, Welcome and the empty storage screen
(128×144), the splash, the app icon.

### Splash
Android's splash screen: the `lcd` ground and Pin at 6× (96×108dp), centred in the 288dp icon so
it stays inside the 192dp circle the system shows (with no icon background colour the system
lays the icon out at 288dp; a 240dp one is scaled up 1.2×). Pin blinks once, stepped (closed
from 360 to 440ms of 600ms), an animated vector that hides the top row of its eyes. It leaves in
one cut, never the system's fade. No scanline grid: the system draws it, not us. Android 8 to 11
have no splash screen API: the launch window is the same amber with the same Pin, still.

### App icon
An adaptive icon, Pin's App icon line below: its background the `lcd` amber, its foreground and
Android 13's monochrome layer the splash's own Pin, its mask the launcher's. No scanline grid at
icon sizes.

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
| Bottom bar state | 2 | The row's state, detail and key cut over in two bands. The stage never cuts, so Pin's moves run on |
| Pin in the bottom bar | 4 per step | Pin walks 20px a step with a 6px hop on every other one, plugs in or pops out in one frame, hops 8px or tugs 4px every 5 frames, and runs into the port's edge in three cuts. With reduced motion he is put where each state leaves him |
| Sheet | 4 | Rises from the bottom; its scrim appears on the first frame. Back plays it in reverse |
| Fault line | 1 | Appears with the error shake |
| Tray | 6 (3 steps) | The drive tray slides out over the body on eject, and back in when a file is loaded, in 3 steps of 2 frames |
| Shell session | 1 per step | A prompt appears at once and the cursor holds on it for a beat (7 frames, 280ms), then the command types at typing speed. Enter: the output types 4 characters a frame with a beat between its lines, and the next prompt comes the moment it ends. The work behind a command starts the moment it has typed. The keys appear one beat after the idle prompt |

Ambient loops:

| Motion | Timing | Where |
| --- | --- | --- |
| Cursor blink | 1s, `steps(1)` | Working states, the live terminal line, the bind headline |
| Pin double-blink | 2.6s loop | Everywhere Pin is shown large or in the top bar |
| Marquee | 8s, `steps(32)`, alternate | Text that does not fit its line (`.st`). Text that fits stays still |
| Packets | 1.2s, `steps(4)` | Tether wires |
| Activity LED | 1.2s blink pattern | The drive face while serving |
| Doze | 1.8s, `steps(3)` | The `z` beside Pin in the bottom bar while Android holds the port |
| Sparks | 2.6s, on for the first 320ms | Either side of the port while Pin is plugged in |

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
- The playful moments live in the bottom bar, tied to what the port really does, and never
  cover the screen.

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
| Segment change, LUN switch, slider step, D-pad or keyboard focus move | `SegmentTick` |
| Trackpad drag | `SegmentFrequentTick`, at most one per 8px of travel |
| Shell typing (Root, Check, Backend) | `SegmentFrequentTick`, one per frame that adds characters; none in the beat between lines |
| Gadget bound (the port left Android) | Rich: a thud on that frame, from whatever screen started it; on Home it is the frame Pin plugs in. A replug ending is a `SegmentTick` |
| Stop all, eject | `GestureEnd` |
| Command refused or failed | `Reject`, with the error shake |
| Save, image created, install finished | `Confirm` |
| Root denied, Check blocked, install failed | `Reject` |
| Warning shown | None. A warning is read, not felt |

Device tiers for the rich effects, chosen once at start-up and cached:

| Tier | When | How |
| --- | --- | --- |
| T1, flagship | API 36 and later, `Vibrator.areEnvelopeEffectsSupported()` and `PRIMITIVE_TICK` supported | The thud is a `VibrationEffect.BasicEnvelopeBuilder` (API 36): low sharpness, up in 20ms, down in 60ms. An envelope starts and ends at zero intensity, with control points at least 20ms apart |
| T2 | API 30 and later and `Vibrator.areAllPrimitivesSupported(...)` is true for every primitive used | `VibrationEffect.Composition`: `PRIMITIVE_THUD` (API 31; `PRIMITIVE_CLICK` at 1.0 where it is missing). One unsupported primitive silences the whole composition, so check first |
| T3 | API 29 and later | Predefined effects: `EFFECT_HEAVY_CLICK` for the thud |
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
  other screen (Keys + mouse: the pad; Tether: the function segments; a screen with neither:
  `< BACK`), requested from a `LaunchedEffect`, never from the composable body, once per screen
  (`focusOn`). A shade or sheet open then holds the ask until it closes, and a screen Back
  returns to lets its opener win.
- **Focus comes back** after Back to the row or key that opened the screen, and after a sheet
  closes to the key that opened it. A pushed screen leaves composition, so `focusRestorer` has
  nothing to restore: each opener carries an id (`Modifier.opener`) and the stack keeps the one
  that held focus at each push. After a touch nothing holds focus, so nothing comes back.
- **Tab comes round** at the end of a screen and of a sheet, to its first control.
- **Every focus move** plays `SegmentTick`.
- **Keys + mouse hands the gamepad to the host:** the left stick moves the host pointer,
  `BUTTON_A` is a left click, `BUTTON_X` a right click, and `BUTTON_B` still means Back.
- Built with `focusable`, `focusGroup`, `focusProperties` and `onKeyEvent` /
  `onPreviewKeyEvent`.
- **TalkBack hears words, not marks.** A control says what it is and its state, from its
  role: a switch is its function's name, on or off, never its printed `1` or `O`. Marks for the
  eye (`>`, `<`, `+`, `-`) are cleared or replaced by words (`Back`, `Add a LUN`, `Shorter
  interval`). A text field is read after its visible label, which comes first in the order.
  Behind an open sheet or shade, the screen is cleared from the tree, as it is from focus.

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
Others: `usb` (trident), `eject`, `spark`, `pointer`, `duck` (Quacky's), and the four arrow
keys. All of them are in the Pixel kit screen.

App icon: Pin in ink on an `lcd` square at half the icon's width, 96×108 on a 192 icon (36dp of
the 72dp an adaptive icon shows), so a launcher mask never clips it. The themed icon is the
system's tint of the same Pin.

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
| NCM / RNDIS | `tether start --ncm` / `tether start --rndis` |
| Tether chain, Tx, Rx | `status --json`: `tether`'s `iface`, `upstream`, `gateway_*`, `tx_bytes`, `rx_bytes` |
| Gateway, container, its iface | `--gateway CONTAINER`, `--gateway-iface IFACE`; the containers are `droidspaces show --json`'s `running`, and the gateway shows only with `/data/local/Droidspaces/bin/droidspaces` installed |
| Tether address, DNS, upstream, DHCP and NAT | `--address CIDR`, `--dns IP[,IP]` (127 characters at most), `--upstream IFACE` (the Upstream node's input), `--no-net` |
| Not applied sheet, Apply, Discard | `status --json`'s `pending`; `storage apply`, `tether apply`, `hid apply`; `setup revert <function>` |
| Each function's setup | `setup set KEY=VALUE` on every edit (one write a beat later), read back as `status --json`'s `setup`; the picks are `storage_lunN=MODE:PATH`, Tether's rows the `tether_*` keys, the interval `hid_interval` |
| HID interval | `hid start --interval N`, period 2^(N-1)×125µs, set at start; without the f_hid patch (`check`'s `hid_interval` is 0) it is `1 ms, fixed by this kernel` and the steppers are dead |
| Keys + mouse, the gamepad | app-side reports (8-byte keyboard, 4-byte mouse), each one write into `hid write NODE SIZE`, one per node, which never blocks on the node and keeps only the newest report while the host reads nothing; key reports are paced two poll periods apart. Speed and Natural scroll are app-side too, applied to the reports before they are written |
| HID period text | `status --json`'s `speed` (`full-speed` is a USB 1.1 host) and `hid[].interval` |
| Quacky | nothing yet: a WIP page |
| Identity | `identity show --json` (the profile in force, and `missing` for a link whose file is gone), `identity show --json FILE...` (the folder's profiles, read by the backend's own loader), `identity generate FILE --force` then the six keys written in (`product`, `manufacturer`, `serial`, `id_vendor`, `id_product`, `max_power`), an edit from the file as it is, through a rename, `identity set FILE`, `identity unset` for Built-in |
| Log | the output of every command the app ran this session, as it printed it; no tabs, no log file |
| Function names in the bind screen | `mass_storage.ua`, `ncm.ua`, `hid.ua_kbd`, `hid.ua_mouse` |
| Root screen | `id -u; su -v` (prints `3.3.0:KernelSU` or `<version>:MAGISK`), shown as a shell session; its `#` comments are the app's narration |
| Check screen | `check --json`, run with the bundled binary before anything is installed |
| Controller step, Settings' Controller | `/sys/class/udc/*` and each one's `state`; the pick is `setup set udc=NAME`, which every command then uses |
| Backend screen | the ported installer: the binary to `/data/local/USB-Arsenal/bin`, `module/` to `/data/adb/modules/usb-arsenal`, each step printing what it really printed, then `[+] Installed`, and `[!] Reboot to load the SELinux rule` only when the module had no rule before. `./install-backend.sh` is how the session shows it; there is no such script. The version is the app's own, never asked of root |
| Home attention key | the installer's backend check: not installed, module missing, corrupted, update available (no root is the shade) |
| Home status, live rows | `status --json` |

Every command runs through root in its own short-lived shell with a timeout (see AGENTS.md),
so one stuck command can never freeze the screen.

Every read is cached in the app's prefs with the boot id it was read in, so the first frame of
every screen draws the last known world and a live read replaces only what changed; a failed
read keeps the last value, and after three in a row Home's bar says `no answer from usb-arsenal`.

App-side features, done by the app itself: the Not applied sheet's asking (what it asks about is
the backend's `pending`), the `/sdcard/USB-Arsenal` file list, Create (a sparse
image, as `truncate -s` does, or a full one with progress), HID input written to `/dev/hidgN`,
the Motion and Haptics switches in Settings, and the profiles folder,
`/sdcard/USB-Arsenal/Profiles`, which Identity lists and writes. Settings' System path makes or
removes `/data/adb/modules/usb-arsenal/system/bin/usb-arsenal`, a link to the workspace binary
(Droidspaces' `SymlinkInstaller`), which acts at the next boot.

Not shown because the backend cannot: UVC (not built), host controller or port names, lease
countdowns, link uptime, a configfs write trace, a pending-changes queue (the Not applied sheet says
only that a setup waits, not what changed).

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
