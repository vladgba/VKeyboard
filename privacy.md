# VKeyboard Privacy Policy

**Effective date:** 2026-10-07
**App:** VKeyboard (`x.vladgba.keyboard`), an open-source keyboard for Android
**Developer:** Vladyslav Tishyn (vladgba), vladgba@gmail.com

## Summary

- VKeyboard has **no internet permission**, so the app itself cannot send anything off your phone.
- The developer **receives no data** from the app: no analytics, no crash reports, no ads, no accounts, no third-party libraries.
- Everything VKeyboard stores stays **on your phone**, inside the app's private storage.

The sections below explain what is stored locally, and the few cases where *other* software (Android, Google Play, your speech recognition service) may handle data under its own policy.

## What the app stores on your phone

The app saves only what you create or turn on:

- your settings, keyboard layouts, themes, macros and text-expansion dictionary;
- the app language and editor preferences;
- **touch statistics**, only if you turn on the touch heatmap (off by default). These are counts of where keys were pressed and how far from the key centre, not the text you typed or the order of key presses. Nothing is recorded in password fields or in fields that ask keyboards not to learn (for example, incognito mode). You can clear them at any time in the app.

VKeyboard does not keep a history or log of what you type. Text passes through the keyboard to the app you are typing in, the same as with any keyboard.

Uninstalling the app, or clearing its data in Android settings, deletes all of this.

## Permissions

| Permission | Why |
|---|---|
| Vibrate | Key-press vibration feedback |
| Microphone (`RECORD_AUDIO`) | Voice typing only. Asked the first time you start voice typing; you can refuse or revoke it at any time |

There is no internet, location, contacts, storage or other permission.

## Voice typing

VKeyboard does not recognise speech itself. When you start voice typing, it uses the speech recognition service installed on your phone (for example, Google's). While you are dictating, your audio goes to that service, which may process it on the device or online under **its own** privacy policy. The *Prefer offline recognition* setting asks the service to stay on the device where supported. The microphone is used only while the voice overlay is shown.

## Data handled by other software

- **Android backup.** If you have turned on backup for your phone (for example, Google One / Google Drive backup), Android may include VKeyboard's settings and files in that backup. This is controlled by you in Android settings and handled under your backup provider's policy; the developer has no access to it.
- **Google Play.** If you install from Google Play, Google handles installation and its own statistics under [Google's privacy policy](https://policies.google.com/privacy). VKeyboard contains no Google libraries.
- **Sharing and links.** Exporting a layout or theme, or opening a link (such as the project page on GitHub), happens only when you choose to. The file or link goes to the app you pick, under that app's or website's policy.

## Children

VKeyboard collects no personal data from anyone, including children.

## Changes to this policy

If the app's handling of data changes, this policy will be updated before or together with that version, with a new effective date. The current version is always in the [project repository](https://github.com/vladgba/VKeyboard).

## Contact

Questions or concerns: vladgba@gmail.com
