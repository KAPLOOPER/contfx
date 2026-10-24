# Continuum FX

A rootless, system-wide audio processor for Android, built in the **Continuum** design language: liquid black ink, Flow springs, flat surfaces and the Libron typeface.

The first release focuses on a **global parametric EQ** that can import EQ files exported from [squig.link](https://squig.link) and AutoEQ. ViPER-style effects are planned next (see the roadmap).

## How it works without root

Android doesn't let ordinary apps add effects to other apps' sound, so Continuum FX takes the same route as [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP):

1. It **captures** what other apps play, using Android's playback capture API (Android 10+).
2. It runs that audio through the **parametric EQ** and a safety limiter.
3. It **plays the processed audio** back.
4. It **silences the original copy** of each app's sound, so you only hear the processed version. Finding those sessions needs the `DUMP` permission, granted once with Shizuku or ADB.

### Limitations

- Apps that block audio capture can't be processed. **Spotify, Chrome and some games** are the common ones. They keep playing normally, just without the EQ, and the app lists them.
- Capture adds a little latency, so it suits music and video better than rhythm games.
- It may clash with other apps that add global effects (Wavelet, other DynamicsProcessing apps). Turn those off first.

## Setup

1. Install the APK from the latest [release](../../releases).
2. Open the app and tap the dock to start setup.
3. Allow audio capture (and notifications on Android 13+).
4. Grant system access once, either:
   - **With Shizuku:** start Shizuku, then tap *Use Shizuku* in the app; or
   - **From a computer:**
     ```
     adb shell pm grant dev.continuum.fx android.permission.DUMP
     adb shell appops set dev.continuum.fx PROJECT_MEDIA allow
     ```
     The second command is optional; it skips the system confirmation each time processing starts.
5. Tap the power button in the dock.

## Importing from squig.link

1. On any squig.link site, build your EQ and use the **parametric EQ export** to download the `.txt` file.
2. Open the file with Continuum FX (*Open with* or *Share*), or tap **Import** in the app and pick it.
   You can also copy the text and tap **Paste**.

Any file in the Equalizer APO parametric format works, including AutoEQ's `ParametricEQ.txt`. Supported filter types: peak (`PK`), low and high shelves (`LS`/`LSC`/`LSQ`, `HS`/`HSC`/`HSQ`), low and high pass, notch, band pass and all pass, with `Q` or `BW Oct`. **Share** exports the current profile in the same format.

## Building

The project builds with Gradle (JDK 17, Android SDK 35):

```
./gradlew testDebugUnitTest assembleRelease
```

Every push to `main` is built by GitHub Actions, which publishes the APK as a pre-release. The release build is signed with the debug key so it installs straight away; use your own signing key before distributing it.

## Roadmap

- [x] Rootless capture pipeline, with Shizuku or ADB setup
- [x] Global parametric EQ with draggable response graph
- [x] squig.link / AutoEQ / Equalizer APO import and export
- [ ] ViPER effects from the open-source reverse-engineered engine (bass, clarity, surround, convolver, compressor and more)
- [ ] Per-app exclusions and per-device profiles
- [ ] Liquid-ink transitions for the remaining sheets

## Credits and licences

- Licensed under the **GNU GPL v3.0** (see `LICENSE`), keeping it compatible with the GPL audio engines it builds on.
- The rootless approach (capture, then silence the originals through a per-session effect) follows [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP) by Tim Schneeberger.
- Filters follow Robert Bristow-Johnson's *Audio EQ Cookbook*.
- [Libron](https://github.com/nicoverbruggen/libron) by Nico Verbruggen, under the SIL Open Font License 1.1 (see `fonts/LIBRON-OFL.txt`).
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps.
