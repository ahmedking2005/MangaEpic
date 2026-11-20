# Contributing to Manga Epic

Manga Epic is an Arabic-only fork of
[Kotatsu-Redo](https://github.com/Kotatsu-Redo/Kotatsu-Redo) (GPL-3.0).

## Ground rules

- **Arabic sources only.** The working set is 24 sources (15 manga,
  6 novels, 3 anime). New sources must be Arabic, working, and pass the
  fence audit (they must not surface non-Arabic or adult catalogues).
- **Parsers** live in the vendored `kotatsu-parsers/` tree in this repo.
- **Small, verified changes.** One change per pull request, and the
  `Build APK (Manga Epic)` workflow must be green.
- **No dead UI.** Every visible control must do what it promises;
  advanced settings are hidden behind «إعدادات متقدمة», never stubbed.
- **Do not commit** keystores, secrets, `.idea/`, or build outputs
  (`app/release/`).

## Building

JDK 21 + Android SDK (platform 37, build-tools 35). `./gradlew assembleRelease`
produces an unsigned APK; CI signs and publishes releases.
