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
| `errorToasts` | `1` | Shows errors as toasts. With `0`, errors are only logged. |

New key option: `"holdRepeat": "1"` repeats the key while it is held. All bundled layouts now use it on Backspace.
New theme colors: `keyPressedBackgroundColor` (key being pressed) and `metaPressedBackgroundColor` (locked modifier).

## Layout format (Flexaml)

```
(                                   # layout
    (                               # row
        ("key": "q", "", "", "", "", "", "", "1"),   # label + 8 popup chars (positions 0..7)
        ("key": "←", "code": "-67", "holdRepeat": "1", "width": "2")
    )
)
```

`code`: a negative value is an Android `KeyEvent` keycode. `mode`: `popup` / `joy` / `meta` / `random`. `layout`: `@NEXT`, `@PREV`, `@NUM`, `@TEXT`, `@EMOJI` or a layout name. Other options: `hold`, `hard`, `text`, `textCurOffset`, `clipboard`, `record`, `action`, `sound*`, `vib*`, and the style keys listed in `core/Constants.kt`.
