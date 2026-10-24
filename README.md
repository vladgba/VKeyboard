# VKeyboard

A customizable, lightweight virtual keyboard for Android. It has **zero library dependencies** and uses only the Android framework and the Kotlin stdlib.

## Building

Open the folder in Android Studio and run `app`. For release signing, put a `keystore.properties` file in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without it, the release build is unsigned and the build no longer fails.

Use JDK 17 or 21 for command-line builds. Run `./gradlew :app:verifyDictionaryImport` for dependency-free dictionary format checks using synthetic data.
Run `./gradlew :app:verifyWordIndex` to check suggestion ranking against a reference implementation and benchmark 200,000 generated words.

Run `./gradlew :app:validateLayouts` (Windows: `tools/validate-layouts.ps1`) to validate shipped layouts. Use `-PlayoutDir=<directory>` or the script `-LayoutDir` argument for a custom folder. Checks cover parser warnings, balanced structure, row/key shape, finite positive dimensions, actions, popup limits and missing layout references. The command exits unsuccessfully on errors and never rewrites files.

With an authorized Android device connected, `tools/device-checks.ps1 all` runs the existing framework-only integration cases. It installs debug app/test APKs and restores settings/learned words after each case. Hardware hotplug and live microphone capture require separate manual testing; automated checks cover hardware policy and provider discovery/routing.

`./gradlew :app:verifyKeyboardLogic` runs independent JVM checks for compose/dead-key state, generated rows, modifier maps, Unicode validation, emoji extraction and randomized layout serialization round-trips. The standard `:app:check` task includes these checks, dictionary/index checks and layout validation. Device cases `learning` and `async` cover privacy/persistence and deterministic cancellation of stale suggestion work; `all` includes both. Android installs the temporary `x.vladgba.keyboard.debug.test` instrumentation package, which normally has no Home-screen icon and can trigger a vendor installer notice. It is removed after the testing session.

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
| `split` | `1` | Split keyboard: `0` off, `1` on wide screens (keyboard at least 600dp wide: unfolded foldables, tablets, phones in landscape), `2` always. Each row splits at the key boundary nearest the middle; a wide key across the middle (the space bar) is stretched over the gap. |
| `splitGap` | `25` | Width of the empty middle of a split keyboard, in % of its width. |
| `learnWords` | `1` | Learn words as they are typed, for suggestions (never in password fields or where the app asks not to). Learned words are in `learned.words` (`word<TAB>count`), part of backups. |
| `suggestions` | `1` | Word suggestion bar above the keys in text fields (not in password, number or "no suggestions" fields): completes the word being typed from learned words, best in the middle; tap one (or use `@COMPLETE_1`/`_2`/`_3`, `@COMPLETE_EMOJI`, 1 = best) to replace the word and add a space. |
| `spaceAccept` | `0` | Space replaces a typed word that isn't a learned word with the top suggestion (completion or one-edit typo correction); Backspace right after restores the typed word. |
| `emojiSuggestions` | `1` | Suggest an emoji for the typed word from imported keywords (Learned words → Import emoji keywords: Unicode CLDR annotations `.xml`/`.json`, or `keyword<TAB>emoji` lines; kept in `emoji.keywords`). Tapping it (or `@COMPLETE_EMOJI`) adds the emoji after the word. |
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
- **Learned words** (home screen): search and delete learned words; import a plain word list (one word per line, optionally with a count), a Gboard personal dictionary (zip or `dictionary.txt`), an AOSP `.combined` word list, a static AOSP v2 binary `.dict`, or an XML dictionary backup (`.xml` / `.zip`); export as a word list or a Gboard zip; copy words from/to Android's personal dictionary. XML imports support preference records with `word` and `freq` values grouped by locale, and `<wordlist><w f="120">hello</w></wordlist>` files. All locales are merged into learned words; existing words keep the higher frequency. ZIP imports read all recognized dictionaries and skip unrelated settings XML and readme files. Imports are limited to 32 MiB, including the total expanded ZIP contents; ambiguous archives with multiple unnamed text lists require importing one file at a time. Binary imports preserve unigram frequencies, omit blocked/non-word entries and ignore shortcuts/bigrams. Dynamic dictionaries and other binary versions require a text export. No word lists are bundled.
- **Suggestion performance**: an in-memory trie caches the best candidates for completion and prunes nonmatching branches during typo correction. Dictionary loading, queries, learning and keyboard-triggered saves run on a background worker; results from older input are discarded. Space auto-accept uses a completed result for the current word; if it is still loading, Space types normally. The index is rebuilt from `learned.words` when loaded and updated as words are learned or removed. No native build tools are required.
- **Compiled dictionary import**: Learned words → Import a file also accepts `.cdict` format version 1, directly or inside a ZIP. All contained dictionaries are merged; alias/shortcut spellings are omitted. The stored frequency ranks (0–15) become counts of 1–16, preserving their order; original typing counts cannot be recovered. Older versions require a text export. Existing 32 MiB import limits apply, and no dictionaries are bundled.
- **Compose key (`@COMPOSE` / `"compose": "1"`)**: Sequential character composition, e.g. tap `⎄` then `'` + `e` → `é`, `c` + `o` → `©`, `s` + `s` → `ß`, `1` + `2` → `½`, `a` + `e` → `æ`.
- **Modifier indicators**: Distinct pip indicator for one-shot active modifiers and auto-shift; solid bar for locked modifiers (Caps Lock).
- Key option `"holdRepeat": "1"` repeats the key while it is held. All bundled layouts now use it on Backspace.
- Theme colors: `keyPressedBackgroundColor` (key being pressed) and `metaPressedBackgroundColor` (locked modifier).
- **Backup & restore**: Full backup (`.zip`) covering all settings (`settings.txt`), custom layouts (`.txt`), color themes (`.ini`), dictionary abbreviations (`dictionary.dic`), macros (`macros.macro`), and hitbox autotune calibration (`touchstats.stats`). Partial backups support standalone `settings.txt` export/import, config-only, or layouts & themes only. Backup archives can also be opened directly from external file managers to restore.

## Layout format (Flexaml)

`tabbedEmoji=1` opens a recycled, scrollable grid with tabs for the nine existing emoji categories. It includes related variants from the emoji pages and keeps multi-code-point sequences intact. The current typing layout stays selected; use the back arrow to return, or Backspace to delete. The last category is remembered only while the keyboard lives. Off by default in Settings; disabling it restores the existing emoji layouts. No emoji history is stored, and existing custom emoji pages are read without being rewritten.

`visualControls=1` enables Settings sliders for `borderWidth` (0–6 dp), `keyboardOpacity` (35–100%) and `keyOpacity` (0–100%). The master switch is off by default and restores theme-defined borders/opacity when disabled; key-fill transparency does not fade labels. Whole-keyboard opacity includes suggestions and popups.

Settings → Voice includes a recognition-provider picker. `customVoiceProvider=1` uses the chosen installed service (`voiceProvider` stores its component); disabled by default. System default, a removed service or a service that cannot be created falls back to the existing system/on-device recognition path. Choosing a provider never grants microphone permission or starts listening by itself.

`hideWithPhysicalKeyboard=1` hides the software keyboard when a non-virtual alphabetic keyboard connects and restores a keyboard hidden for that reason on disconnect. Off by default, in Settings → Hardware. DPAD remotes, touchscreens and virtual devices do not trigger it.

Enable **Expanded language layouts** in Settings to make the additional Arabic, Hebrew, Greek, Turkish, Italian, Portuguese, Czech, Hungarian, Swedish, Finnish, Norwegian and Danish layouts available in Built-in layouts. Add only the ones you want; existing files are preserved. The feature is off by default and disabling it removes these layouts from normal cycling/selection. See [layout sources](docs/layout-sources.md) for provenance and mobile adaptations.

`orientationLayouts=1` remembers manual typing-layout choices separately in portrait and landscape (`portraitLayout`, `landscapeLayout`). Enable it in Settings; it is off by default. App-specific layouts take priority, and temporary number/emoji pages do not replace the remembered choice.

`sideNumpad`: `0` off (default), `1` right, `2` left, `3` right in landscape only. `numpadWidth` sets its width (15–45%, default 25%). `invertNumpad=1` swaps 1–3 and 7–9 on the side and standalone pads. These settings never rewrite layout files.

`extraKeysRow=1` adds a toolbar to typing layouts. Configure up to 16 pipe-separated `extraRowKeys` in Settings: navigation names, Ctrl/Alt/Shift/Meta, F1–F12, or literal text. Default: `Esc|Tab|Ctrl|Alt|←|↓|↑|→`. Off by default.

`rtlMarks=1` adds Unicode direction controls (LRM/RLM/ALM and LRI/RLI/FSI/PDI), also available as `@RTL:<name>` actions. `mirrorBrackets=1` swaps single typed bracket/angle-quote pairs on layouts marked `rtl=1` or containing RTL letters. Both options are off by default and configurable in Settings.

`combiningMarks=1` adds Arabic vowel marks or Hebrew niqqud on matching layouts. Keys insert the combining character after the base letter; custom layouts can use `@MARK:<hex codepoint>`. Off by default. Word learning, completion-prefix detection and word deletion keep diacritics attached to their word.

`modifierMappings=1` enables layout-defined `modmap` tables (off by default). On a key: `"modmap": ("alt": "α", "ctrl+shift": "macro:name")`. At layout level: `"modmap": ("alt": ("a": "α", "b": "β"))`. Key mappings override layout mappings. Names: shift, ctrl, alt, altgr, meta, or none. Values use normal action syntax (text, negative key code, combo, macro or layout action). Source modifiers are consumed; specify output modifiers explicitly in a combo.

```
(                                   # layout
    (                               # row
        ("key": "q", "", "", "", "", "", "", "1"),   # label + 8 popup chars (positions 0..7)
        ("key": "←", "code": "-67", "holdRepeat": "1", "width": "2")
    )
)
```

`code`: a negative value is an Android `KeyEvent` keycode. `mode`: `popup` / `joy` / `meta` / `random`. `layout`: `@NEXT`, `@PREV`, `@NUM`, `@TEXT`, `@EMOJI` or a layout name. Other options: `hold`, `hard`, `text`, `textCurOffset`, `clipboard`, `record`, `action`, `@VOICE`, `@COMPOSE`, `@DELETE_WORD`, `@DELETE_WORD_FORWARD`, `@CLIPBOARD`, `@DEAD:<name>`, `@COMPLETE_1`/`_2`/`_3`, `@tr_voice/<src>-<dst>` (e.g. `@tr_voice/ru-en` for voice translation), `sound*`, `vib*`, and the style keys listed in `core/Constants.kt`.
