# Translations

Every string the app shows lives in `Android/app/src/main/res/values/strings.xml`, and every
plural in `plurals.xml` beside it, the same layout as
[Droidspaces](https://github.com/ravindu644/Droidspaces-OSS). `strings.xml` runs screen by screen,
each section under a comment, and each key starts with its screen, so a translator can still work
through one screen at a time:

| Prefix | What it holds |
|---|---|
| `common_` | keys more than one screen uses (Cancel, Save, Log, `< Back`); `app_name`, which is not translated, sits with them |
| `home_` | Home: the hero, the bar's silence, the backend's attention key |
| `gadget_` | the function rows, the busy and fault texts, the Not applied sheet, what su answered |
| `storage_`, `tether_`, `hid_`, `keyboard_`, `quacky_`, `identity_` | each function's screen |
| `setup_`, `settings_`, `log_` | the install and check screens, Settings, Log |
| `parts_` | the shared widgets: the bottom bar's port states and Stop all, the drive and LUN controls, the step dots, the theme names |

## In code

Composables and plain functions read strings the same way, `str(R.string.x, args)` and
`plural(R.plurals.x, count, args)` from `Strings.kt`, not `stringResource()`: Home's row lines,
the busy text and the faults are built outside composition, and the unit tests check them. The
source is snapshot state, so a language change (the activity takes it without a recreate)
recomposes whatever read a string. The JVM tests read the same XML through `XmlStrings` in
`src/test`.

Adding a string:
- Put it in its screen's section of `strings.xml`, or in `plurals.xml` if it is a plural, named
  `<screen>_<what>`, under the comment that says where it shows. Weblate shows the comment above a string to the translator; say what each `%N$s` is.
- Placeholders are positional, `%1$s`, so a translation can reorder them. A count that changes
  the wording is a `<plurals>` with `%1$d` first.
- One whole phrase per string. Never glue translated pieces together.
- Never call `str()` while a class or file initialises (a `const`, a top-level `val`, an enum's
  argument): the source is set in `MainActivity.onCreate`. Use a getter, or keep the
  `@StringRes` id.
- What stays in code: commands, setup and JSON keys, paths, keycap legends (`ESC`, `F1`), units
  (`mA`, `MB`, `GB`, `G`, `ms`, `us`, `s`), Storage's mode labels (`auto`, `cd`, `dvd`, `disk`,
  `rw`), Quacky's keywords, the log's `[-]` glyphs, and anything the backend printed, which is
  shown as it came.

## Weblate

Two components, `Strings` and `Plurals`, both with these settings:

| Setting | `Strings` | `Plurals` |
|---|---|---|
| File format | Android String Resource | Android String Resource |
| File mask | `Android/app/src/main/res/values-*/strings.xml` | `Android/app/src/main/res/values-*/plurals.xml` |
| Monolingual base language file | `Android/app/src/main/res/values/strings.xml` | `Android/app/src/main/res/values/plurals.xml` |
| Template for new translations | the same base file | the same base file |
| Language code style | Android style (`values-pt-rBR`) | Android style (`values-pt-rBR`) |

Turn on the **Cleanup translation files** add-on. A key removed from a base file and left in a
translation is lint's `ExtraTranslation`, which is fatal, so the release build fails until it
goes. A missing translation only falls back to English.

The app lists its languages in Android 13's per-app language setting by itself:
`generateLocaleConfig` builds the list from the `values-*` folders, and
`res/resources.properties` says `res/values` is English.

## Fonts

The three faces cover Latin-1 and little else. Silkscreen, every label and key, has no Latin
Extended-A (Polish, Czech, Turkish letters), and none of the faces has Greek, Cyrillic, CJK,
Arabic or Devanagari; Android draws a missing glyph in the system font, so such a translation
reads, but not in the LCD's faces. VT323 alone also covers Latin Extended-A and Vietnamese.
