# Continuum FX

The **Continuum** toolkit for Android, built in the Continuum design language: liquid black ink, Flow springs, flat surfaces and the Libron typeface. No root needed.

- **Sound:** a rootless, system-wide **parametric EQ** that imports EQ files from [squig.link](https://squig.link) and AutoEQ. ViPER-style effects are planned next.
- **Island:** a **liquid ink dynamic island** around your front camera, for music, calls, timers and notifications.
- **Keyboard:** **Continuum Keyboard**, a separate app built on [FUTO Keyboard](https://github.com/futo-org/android-keyboard) and redesigned in Continuum. Install it, switch it on and open its settings from the Keyboard tab.

## Continuum Keyboard

Continuum Keyboard keeps everything FUTO Keyboard does (on-device predictions and autocorrect that learn as you type, offline voice typing, swipe typing, many languages and layouts, clipboard history, emoji, and password-manager suggestions above the keys) and works entirely offline: it has no internet access. On top of that:

- **The Continuum look:** Continuum Ink by default (pure black, flat pill keys, Libron), or Continuum Light, or one that follows the system. Keys squash when pressed and bounce back.
- **Scan text:** take a picture, tap the lines you want, and they're typed where you were. The text is read on the phone.
- **Clipboard:** keep clips for 1 hour, a day, 3 days, a week, a month, or until you remove them; pin the ones you reuse.
- **Emoji:** a Frequently used row alongside Recent.

It lives in `keyboard/` under the **FUTO Source First License 1.1** (see `keyboard/LICENSE.md` and `keyboard/README.md`): free to use and share, not for commercial use, and FUTO's payment options stay in place. If you find it useful, you can pay FUTO for FUTO Keyboard from its settings, or from the Keyboard tab.

## The island

The island is one piece of ink that starts as your camera. It follows the Continuum liquid rule: ink never appears or vanishes, it only separates from, or merges with, ink already on screen.

- **Music and video** stretch the camera into a pill: artwork on one side, and three drops of the track's colour on the other that bob and fuse like liquid while it plays, settling into one calm drop when paused. Tap to open the player: the seek bar is a line of liquid ending in a drop that glides as the song plays and swells under your finger, play and pause melt into each other, and skip presses the triangle into the bar.
- **Calls** drip down from the camera when they ring, with Decline and Answer. During a call the pill keeps a green timer; tap it to end the call.
- **Timers** from your clock app count down in the pill, or in a second bubble that pinches off the side when music is playing too.
- **Notifications** drip down for a few seconds. Tap to open, swipe up to dismiss.
- **Pull a drop:** hold the island (or the bare camera) and drag down. A drop stretches out on a neck; let go early and it springs back in, pull past the snap and it tears free as a card of your recent notifications. Swipe it sideways for the next one, tap to open, swipe up to send it back.
- **Always on:** switch on *Time* and *Battery* to keep them in the island all the time, beside whatever else is live. The time can sit left of the artwork, right of it, or around the camera, with the hours on one side, the minutes on the other and the camera as the colon. The battery is an ink capsule on the far right with your charge inside it. With nothing playing, the island rests as a slim pill of time and battery (or time and date).
- **Charging and battery:** plugging in, by cable or wireless, makes the island show your charge in a liquid battery that ripples as it fills. It also speaks up at 20% and 10%, and when you're fully charged. Tap it for the time to full.

On Android 12 and later the ink is rendered as a true liquid (blur and threshold), so pieces fuse and necks thin before they snap. Older versions draw the necks without the blur.

### Setting up the island

Open the app, tap **Island**, then the dock. The island needs two switches:

1. **Accessibility:** draws the island over apps and the lock screen. It reads nothing on screen.
2. **Notification access:** lets it see what's playing, ringing or counting down.

*Switch on with Shizuku* flips both at once (keeping your other accessibility services on). Without Shizuku, use the settings buttons. On Android 13 and later, sideloaded apps need **Allow restricted settings** first: open App info, tap the menu in the corner and choose it. From a computer:

```
adb shell appops set dev.continuum.fx ACCESS_RESTRICTED_SETTINGS allow
adb shell cmd notification allow_listener dev.continuum.fx/dev.continuum.fx.island.IslandNotificationListener
```

then switch on *Continuum island* in accessibility settings. The **Try it** buttons send sample music, a call, a timer and a notification so you can see the motion straight away.

## Sound: how it works without root

Android doesn't let ordinary apps add effects to other apps' sound, so Continuum FX takes the same route as [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP):

1. It **captures** what other apps play, using Android's playback capture API (Android 10+).
2. It runs that audio through the **parametric EQ** and a safety limiter.
3. It **plays the processed audio** back.
4. It **silences the original copy** of each app's sound, so you only hear the processed version. Finding those sessions needs the `DUMP` permission, granted once with Shizuku or ADB.

### Limitations

- Apps that block audio capture can't be processed. **Spotify, Chrome and some games** are the common ones. They keep playing normally, just without the EQ, and the app lists them.
- Capture adds a little latency, so it suits music and video better than rhythm games.
- It may clash with other apps that add global effects (Wavelet, other DynamicsProcessing apps). Turn those off first.

## Sound setup

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

## Updates

Continuum FX updates itself. It checks this repository's releases when you open it (at most every six hours), and **Check for updates** at the bottom of any tab checks straight away. When a newer build is out, a card at the top offers **Update now**: it downloads the APK and installs it over the top, keeping your settings, profiles and permissions.

- With **Shizuku** running, the update installs without a prompt.
- Otherwise Android asks you to confirm; the first time, it also asks you to allow Continuum FX to install apps. Once the app has installed an update itself, Android 12 and later can let later ones through without asking.

Every build is signed with the same key and numbered by its build, so each one installs as an update rather than needing a reinstall. Android refuses any update signed with a different key, so a tampered APK can't replace this one.

## Building

The project builds with Gradle (JDK 17, Android SDK 35):

```
./gradlew testDebugUnitTest assembleRelease
```

Every push to `main` is built by GitHub Actions, which publishes the APK as a pre-release named `build-N`, with N as the app's version code.

Releases are signed with `keystore/continuum.p12`. The keystore is locked with a long random password kept only in the `SIGNING_PASSWORD` repository secret (Settings → Secrets and variables → Actions), so the file is safe in the repository without it. Builds without the secret (local builds, pull requests) use the debug key and can't update a release install. If you fork this, make your own key with `keytool -genkeypair -keystore keystore/continuum.p12 -storetype PKCS12 -alias continuum -keyalg RSA -keysize 4096 -validity 12000` and set your own secret.

## Roadmap

- [x] Rootless capture pipeline, with Shizuku or ADB setup
- [x] Global parametric EQ with draggable response graph
- [x] squig.link / AutoEQ / Equalizer APO import and export
- [ ] ViPER effects from the open-source reverse-engineered engine (bass, clarity, surround, convolver, compressor and more)
- [ ] Per-app exclusions and per-device profiles
- [x] Liquid ink island: music, calls, timers and notifications
- [ ] Island: navigation, charging and more sources; per-device camera fine-tuning
- [x] In-app updates that install over the top
- [ ] The Continuum keyboard (its options are already in the Keyboard tab)
- [ ] Liquid-ink transitions for the remaining sheets

## Credits and licences

- Continuum FX is licensed under the **GNU GPL v3.0** (see `LICENSE`), keeping it compatible with the GPL audio engines it builds on.
- Continuum Keyboard (`keyboard/`) is a modified version of FUTO Keyboard by FUTO Holdings, Inc., under the FUTO Source First License 1.1; it's a separate app and isn't covered by the GPL.
- The rootless approach (capture, then silence the originals through a per-session effect) follows [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP) by Tim Schneeberger.
- Filters follow Robert Bristow-Johnson's *Audio EQ Cookbook*.
- [Libron](https://github.com/nicoverbruggen/libron) by Nico Verbruggen, under the SIL Open Font License 1.1 (see `fonts/LIBRON-OFL.txt`).
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps.
- The island's overlay approach is adapted from [Essentials](https://github.com/sameerasw/essentials) by Sameera Sandakelum (MIT). See `THIRD_PARTY_NOTICES.md`.
- Phone and music glyphs from Google's Material Icons (Apache 2.0).
