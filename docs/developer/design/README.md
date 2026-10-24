# Reference screens

The Android app's design language, as built and reviewed screen by screen. The rules are in
[DESIGN.md](../../../DESIGN.md); this folder is what they look like.

| Path | What it is |
| --- | --- |
| `lcd.css` | Every shared rule (tokens, type, keys, segments, switches, rows, bar, terminal, motion). Start a new screen from it |
| `rendered/*.html` | Standalone renders. Open in any browser; fonts load from Google Fonts |
| `hero.html` | The README's banner, a `.lcd.orange` panel on `lcd.css`; the render command is in its head |
| `screens/*.dc.html`, `screens/canvas.json` | The Design canvas sources with their interactive state (switches, mode picks, the trackpad). They need the canvas runtime to run; read them for structure and logic |

| Screen | Shows |
| --- | --- |
| `Main` | Home: function rows, DIP switches, the marquee, the Log and Settings keys, the bottom bar in its three states |
| `Bind` | The bind animation and its four frames |
| `Log` | Segmented tabs, the terminal panel, the live line |
| `Storage` | LUN strip, drive face, `ro`/`rw` tag, mode segments, warnings, the Files list |
| `StorageEmpty` | The empty drive, Pin with `?` |
| `Tether` | The tether chain, gateway switch, key-value rows, inputs |
| `Hid` | Trackpad, mouse buttons, report bytes, keys, the interval stepper |
| `Identity` | Fields, profile segments, power segments, Reset and Save |
| `Tablet` | The 420px list pane beside a function's screen |
| `Kit` | Pin, every sprite, colours, dither levels, type, the switch and the key |
| `Icon` | The launcher icon at 512, 192, 96 and 48, round and themed |
| `Welcome` | First launch: Pin, the name, what it does, `Set up` |
| `Root` | The su probe in its three states: asking, granted, denied |
| `Check` | `check --json` as fact rows: checking, all met, met with caveats, blocked |
| `Install` | Before (fresh and update), running in the terminal panel, success, failure |
| `HomeStates` | Home's attention key (update, not installed) with every variant, and a function this kernel lacks |
| `Failed` | The fault line under the element acted on, on Home and Storage, with sample messages |
| `Create` | The bottom sheet: editing, invalid, writing with progress cells, done |
| `Settings` | Motion, Haptics, Identity, Backend, About, and the font licence viewer |
| `Splash` | The splash, its icon geometry and the one stepped blink |

A screen with several states shows them side by side as captioned phones on one board.
Filenames, addresses, the UDC name and traffic counts on these screens are sample data.
