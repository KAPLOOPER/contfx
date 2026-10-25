# Continuum Keyboard

Continuum's own keyboard: one sheet of ink that keys rise out of and fall back into. Written from scratch for Continuum;
it shares no code with other keyboards.

## Layout

- `src/main/java/dev/continuum/keyboard/engine/` — the word engine, plain Kotlin with no Android in it: the word list as
  a packed trie (`Words`), the touch-aware corrector (`Corrector`), next words (`NextWords`), learning (`Learned`), the
  swipe decoder (`Swipe`) and text rules (`TextRules`). Tested in `src/test/`.
- `layout/` — the sheets of keys and where they sit.
- `ui/KeyboardView.kt` — draws the keys, drops, accent bar, trail and delete tongue, and handles touch.
- `ui/Panels.kt` — clipboard and emoji, in Compose.
- `ContinuumIME.kt` — the input method: the word in progress, corrections and undo, spacing, swipes, panels, autofill.
- `scan/` — Scan text (CameraX and ML Kit, on the phone).
- `settings/` — settings and setup.
- `tools/` — scripts that build the word and emoji data in `src/main/assets/`.

Build: `./gradlew -p keyboard testDebugUnitTest assembleRelease`.

## Licence

Copyright © 2026 the Continuum authors. Licensed under the GNU General Public License v3.0 (see `../LICENSE`).

Additional permission under GNU GPL version 3 section 7: if you modify this program, or any covered work, by linking
or combining it with Google ML Kit or Google Play services libraries (or a modified version of those libraries),
containing parts covered by the terms of their own licences, the licensors of this program grant you additional
permission to convey the resulting work.

Data files keep their own licences; see `../THIRD_PARTY_NOTICES.md`.
