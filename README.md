# VKeyboard

A customizable, lightweight virtual keyboard for Android. It has **zero library dependencies** and uses only the Android framework and the Kotlin stdlib.

## Building

Open the folder in Android Studio and run `app`. For release signing, put a `keystore.properties` file in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the release build is unsigned and the build no longer fails.

## Versioning

The version lives in `version.properties` (`major`, `minor`, `patch`, semantic versioning):

- `versionName` = `MAJOR.MINOR.PATCH` (debug builds add `-debug`)
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (e.g. 1.4.2 → 10402), so minor and patch stay below 100

Bump it with `./gradlew bumpPatch` (fixes), `bumpMinor` (new features) or `bumpMajor` (breaking changes, e.g. file formats); `./gradlew printVersion` shows the current one.

Style inheritance chain: **Key → Row → Layout → Theme → settings.txt → built-in defaults**.

## Settings (in `settings.txt`)

| Key | Default | Meaning |
|---|---|---|
| `metaOneShot` | `1` | Modifier keys: tap once for the next key only, tap again to lock, tap a third time to release. `0` restores the old on/off toggle. |
| `textExpansion` | `1` | Expands abbreviations from the Dictionary (Settings → Dictionary) when you type a space, Enter or punctuation. |
| `autoNumLayout` | `1` | Switches to the `123` layout in number, phone and date fields. |
| `landscapeHeight` | `1` | Keyboard height multiplier in landscape, e.g. `0.75`. |
| `bottomPadding` | `0` | Bottom margin in dp elevating the keyboard from the gesture navigation bar. |
| `joyAcceleration` | `100` | Joystick keys (`"mode": "joy"`, e.g. the space bar cursor slider): fast drags need less distance per step, in %; `0` turns it off. Sideways drags never jump lines, and with Ctrl held steps need twice the distance. |
| `selectionSlider` | `1` | With Shift on or text selected, dragging the space bar grows or shrinks the selection (see below). `0` turns it off. |
| `circleGestures` | `1` | Circle and roundtrip gestures on keys (see below). `0` turns them off. |
| `edgeGestureGuard` | `1` | Android 10+ gesture navigation: swipes that start at the screen edge over the keyboard go to the keyboard instead of Back (the system honours up to 200dp of keyboard height). `0` turns it off. |
| `clipHistory` | `1` | Remember copied text (clipboard history, opened with `@CLIPBOARD`). `0` turns it off. |
| `clipExpiry` | `15` | Minutes unpinned clips are kept; `0` = never expire. Pinned clips stay until removed. |
| `numberRow` | `0` | Adds a row of digits on top of typing layouts that don't have one: `0` off, `1` digits, `2` digits with `! @ # $ % ^ & * ( )` on swipe-up. A layout can set its own `"numberRow"` (e.g. `"0"`) to override it. The layout files are not changed. |
| `smartPunctuation` | `1` | Cleans space before punctuation: typing punctuation (. , ! ? : ;) after a space removes the space. |
| `appLayouts` | `()` | Default layout per app package, e.g. `("com.termux": "code")`. |
| `errorToasts` | `1` | Shows errors as toasts. With `0`, errors are only logged. |

New features & options:
- **Undo text expansion on Backspace**: Tapping Backspace immediately after an abbreviation expands reverts the text back to the abbreviation.
- **Selection slider**: with Shift on, or while text is selected, dragging the space bar (any joystick key whose left/right are the cursor arrows) grows or shrinks the selection one character per step instead of moving the cursor; up/down extend it by lines.
- **Circle gestures**: draw a circle on a key. Clockwise on a letter types it as a capital, on Backspace deletes the previous word, on Shift locks it (Caps Lock). Key options `"circle"` (clockwise), `"anticircle"` (counter-clockwise) and `"roundtrip"` (swipe out from the centre and back before lifting) set any action instead, with the same values as `"hold"` (key code, `ctrl+…` combination, `macro:name` or text).
- **Word deletion**: actions `@DELETE_WORD` (word before the cursor, drawn as ⌫⌫) and `@DELETE_WORD_FORWARD` (word after it, ⌦⌦) work anywhere an action goes: popup positions, swipes, hold, heavy press, circles, or as a key label. In the bundled layouts, swiping Backspace up deletes the previous word and swiping it down the next one.
- **Clipboard history**: copied text is remembered (in memory only, never apps' sensitive clips such as passwords) and listed by the `@CLIPBOARD` action, drawn as 📋 and on swipe-up of 123 in the bundled layouts. Tap a clip to paste it, ☆ pins it (pinned clips are saved in `clipboard.pins` and included in backups), ✕ deletes it, Clear removes all unpinned clips. Unpinned clips are forgotten after `clipExpiry` minutes.
- **Dead keys** (`@DEAD:<name>`): the next character typed is changed. Accents: `acute`, `grave`, `circumflex`, `diaeresis`, `tilde`, `macron`, `breve`, `dotabove`, `dotbelow`, `ring`, `doubleacute`, `caron`, `cedilla`, `ogonek`, `horn`, `hook`, `doublegrave` (´ + e → é; letters without a precomposed form keep the combining mark). Others: `stroke` (d → đ, l → ł, o → ø), `superscript` (2 → ²), `subscript` (2 → ₂), `ordinal` (a → ª), `smallcaps` (a → ᴀ), `arrows` (number-pad directions, 6 → →) and `box` (number-pad positions, 5 → ┼). Space or the same dead key again types the accent itself; Backspace cancels.
- **Compose key (`@COMPOSE` / `"compose": "1"`)**: Sequential character composition, e.g. tap `⎄` then `'` + `e` → `é`, `c` + `o` → `©`, `s` + `s` → `ß`, `1` + `2` → `½`, `a` + `e` → `æ`.
- **Modifier indicators**: Distinct pip indicator for one-shot active modifiers and auto-shift; solid bar for locked modifiers (Caps Lock).
- Key option `"holdRepeat": "1"` repeats the key while it is held. All bundled layouts now use it on Backspace.
- Theme colors: `keyPressedBackgroundColor` (key being pressed) and `metaPressedBackgroundColor` (locked modifier).
- **Backup & restore**: Full backup (`.zip`) covering all settings (`settings.txt`), custom layouts (`.txt`), color themes (`.ini`), dictionary abbreviations (`dictionary.dic`), macros (`macros.macro`), and hitbox autotune calibration (`touchstats.stats`). Partial backups support standalone `settings.txt` export/import, config-only, or layouts & themes only. Backup archives can also be opened directly from external file managers to restore.

## Layout format (Flexaml)

```
(                                   # layout
    (                               # row
        ("key": "q", "", "", "", "", "", "", "1"),   # label + 8 popup chars (positions 0..7)
        ("key": "←", "code": "-67", "holdRepeat": "1", "width": "2")
    )
)
```

`code`: a negative value is an Android `KeyEvent` keycode. `mode`: `popup` / `joy` / `meta` / `random`. `layout`: `@NEXT`, `@PREV`, `@NUM`, `@TEXT`, `@EMOJI` or a layout name. Other options: `hold`, `hard`, `text`, `textCurOffset`, `clipboard`, `record`, `action`, `@VOICE`, `@COMPOSE`, `@DELETE_WORD`, `@DELETE_WORD_FORWARD`, `@CLIPBOARD`, `@DEAD:<name>`, `@tr_voice/<src>-<dst>` (e.g. `@tr_voice/ru-en` for voice translation), `sound*`, `vib*`, and the style keys listed in `core/Constants.kt`.
