# Continuum Keyboard

**Continuum Keyboard is a modified version of [FUTO Keyboard](https://github.com/futo-org/android-keyboard)** by FUTO Holdings, Inc., redesigned in the Continuum design language (flat surfaces, pure black ink, Libron type, Flow springs) to sit alongside Continuum FX.

- Upstream: `futo-org/android-keyboard`, commit `70a5d390c505a6bbcc4e14966e5628e43ca3f1fc` (see `UPSTREAM.md`).
- Licence: the **FUTO Source First License 1.1-kb** in `LICENSE.md` applies to everything in this `keyboard/` folder, including our changes. The original AOSP keyboard code it builds on is Apache 2.0 (see `NOTICE`). The rest of this repository (Continuum FX) is GPL-3.0 and is a separate app.
- It is distributed free of charge, for non-commercial use. FUTO's payment options (Settings → Payment) are kept as they are: if you find it useful, you can pay FUTO for FUTO Keyboard there.

## What we changed

- App id `dev.continuum.keyboard` and the name Continuum Keyboard, so it installs beside FUTO Keyboard, plus a Continuum icon.
- New Continuum themes (Ink, the default; Light; and one that follows the system) with pill-shaped flat keys and Libron on the keys and in settings.
- Built and signed by this repository's CI, and updated through the Continuum FX app instead of FUTO's update checker.

FUTO's original README is kept as `FUTO-README.md`.
