# Reference screens

The Android app's design language, as built and reviewed screen by screen. The rules are in
[DESIGN.md](../../../DESIGN.md); this folder is what they look like.

| Path | What it is |
| --- | --- |
| `lcd.css` | Every shared rule (tokens, type, keys, segments, switches, rows, bar, terminal, motion). Start a new screen from it |
| `rendered/*.html` | Standalone renders. Open in any browser; fonts load from Google Fonts |
| `screens/*.dc.html`, `screens/canvas.json` | The Design canvas sources with their interactive state (switches, mode picks, the trackpad). They need the canvas runtime to run; read them for structure and logic |

| Screen | Shows |
| --- | --- |
| `Main` | Home: function rows, DIP switches, the marquee, the bottom bar in its three states |
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

Filenames, addresses, the UDC name and traffic counts on these screens are sample data.
