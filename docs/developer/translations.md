# Translations

Every string the app shows lives in `Android/app/src/main/res/values/strings_<screen>.xml`, one
file a screen, so each file is one Weblate component and a translator works on one screen at a
time.

| File | What it holds |
|---|---|
| `strings_common.xml` | keys more than one screen uses (Cancel, Save, Log, `< Back`), and `app_name`, which is not translated |
| `strings_home.xml` | Home: the hero, the bar's silence, the backend's attention key |
| `strings_gadget.xml` | the function rows, the busy and fault texts, the Not applied sheet, what su answered |
| `strings_storage.xml`, `strings_tether.xml`, `strings_hid.xml`, `strings_keyboard.xml`, `strings_quacky.xml`, `strings_identity.xml` | each function's screen |
| `strings_setup.xml`, `strings_settings.xml`, `strings_log.xml` | the install and check screens, Settings, Log |
| `strings_parts.xml` | the shared widgets: the bottom bar's port states and Stop all, the drive and LUN controls, the step dots, the theme names |

## In code

Composables and plain functions read strings the same way, `str(R.string.x, args)` and
`plural(R.plurals.x, count, args)` from `Strings.kt`, not `stringResource()`: Home's row lines,
the busy text and the faults are built outside composition, and the unit tests check them. The
source is snapshot state, so a language change (the activity takes it without a recreate)
recomposes whatever read a string. The JVM tests read the same XML through `XmlStrings` in
`src/test`.

Adding a string:
- Put it in its screen's file, named `<file>_<what>`, under the comment that says where it
  shows. Weblate shows the comment above a string to the translator; say what each `%N$s` is.
- Placeholders are positional, `%1$s`, so a translation can reorder them. A count that changes
  the wording is a `<plurals>` with `%1$d` first.
- One whole phrase per string. Never glue translated pieces together.
- Never call `str()` while a class or file initialises (a `const`, a top-level `val`, an enum's
  argument): the source is set in `MainActivity.onCreate`. Use a getter, or keep the
  `@StringRes` id.
- What stays in code: commands, setup and JSON keys, paths, keycap legends (`ESC`, `F1`), Quacky's
  keywords, the log's `[-]` glyphs, and anything the backend printed, which is shown as it came.

## Weblate

One component per file, all with these settings:

| Setting | Value |
|---|---|
| File format | Android String Resource |
| File mask | `Android/app/src/main/res/values-*/strings_<screen>.xml` |
| Monolingual base language file | `Android/app/src/main/res/values/strings_<screen>.xml` |
| Template for new translations | the same base file |
| Language code style | Android style (`values-pt-rBR`) |

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
