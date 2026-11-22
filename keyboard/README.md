# Continuum Keyboard

Continuum's own keyboard: bare keys on one sheet of ink, and a pill of bright ink that runs from key to key as you
type. Written from scratch for Continuum; it shares no code with other keyboards.

It comes in three looks, all with the same runner. **Ink** is pure black; **Tint** takes its colour from the wallpaper
(Android 12 and later). **Print** is printed the way Continuum Kit's poster is: the keys are a sheet of grainy paper
with the letters inked on it in Google Sans Flex, and wherever the keyboard would put bright ink (the runner, shift and
send while they're on, the word space will use) a window is cut through the paper instead, to the poster's halftone
sky. The runner runs, carries its letter, gives as it lands and leaves its drops exactly as in Ink, only the drops are
small stars, and it settles into a star under the last key. Shift held on is a star-shaped window; long-press
accents and the delete tongue are slips of paper pasted over the keys. The sky drifts while you type and holds still
when you stop. Print is paper by day and the negative in dark mode.

## Layout

- `src/main/java/dev/continuum/keyboard/engine/` — the word engine, plain Kotlin with no Android in it: the word list as
  a packed trie (`Words`), the touch-aware corrector (`Corrector`), next words (`NextWords`), learning (`Learned`), the
  swipe decoder (`Swipe`) and text rules (`TextRules`). Tested in `src/test/`.
- `layout/` — the sheets of keys and where they sit.
- `ui/KeyboardView.kt`: draws the keys, the runner and its drops, the accent bar, trail and delete tongue, and handles touch.
- `ui/Print.kt` — the Print look's materials: its type, the halftone sky behind the windows, and the paper grain.
- `ui/Panels.kt` — clipboard and emoji, in Compose; `ui/EditPanel.kt` — the edit panel (cursor, selection, clipboard, undo).
- `ContinuumIME.kt` — the input method: the word in progress, corrections and undo, spacing, swipes, panels, autofill.
- `scan/` — Scan text (CameraX and ML Kit, on the phone).
- `Codes.kt` — one-time codes, read from Continuum Kit through a provider only apps signed by the same key can open.
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
