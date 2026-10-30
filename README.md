# Continuum FX

The **Continuum** toolkit for Android, built in the Continuum design language: liquid black ink, Flow springs, flat surfaces and the Libron typeface. No root needed.

- **Sound:** a rootless, system-wide **parametric EQ** that imports EQ files from [squig.link](https://squig.link) and AutoEQ, an EQ of its own for any app, and backup of everything.
- **Effects:** analog bass, punch, speaker bass, tube warmth, an exciter, hi-res detail, stage, crossfeed, dynamics, loudness and Bluetooth care, on top of the EQ.
- **Island:** a **liquid ink dynamic island** around your front camera, for music, calls, timers, notifications, alarms, your calendar and more.
- **Volume:** Continuum's own **volume panel**, in liquid ink, with each app's own volume, play-alongside and a boost past 100%.
- **Keyboard:** **Continuum Keyboard**, Continuum's own keyboard, as a separate app. Install it, switch it on and open its settings from the Keyboard tab.

The ink rule holds across the app too. The drop under the page's title and the dock are the app's ink: an update, or an app that sent you here, drips out of the drop on a neck and spreads into a card where it sits (and gathers back into it when it goes), and messages bud off the top of the dock, hang on a thread of ink and sink back in.

## Continuum Keyboard

Continuum's own keyboard, written from scratch so every part of it can follow the liquid ink rule. It lives in `keyboard/` and builds as its own app (`./gradlew -p keyboard assembleRelease`). It has no internet access: your typing, learned words and clipboard stay on the phone.

- **Ink keys:** pressed letters rise out of their key as a drop and fall back into it; a long press grows a bar of accents out of the key; keys pop in row by row; Ink (pure black) or Tint (from your wallpaper).
- **Corrections and suggestions:** a 115,000-word English list with a touch-aware corrector (a slip onto a neighbouring key costs less than a real typo), completions, next-word predictions, and learning that picks up your words and phrases. Delete straight after a correction to undo it; long-press a suggestion to stop it coming up.
- **Swipe typing** with an ink trail that's drawn back in when you lift.
- **Delete swiping:** drag left from delete to select whole words, lift to delete them.
- **Clipboard and emoji:** clipboard history kept from 1 hour to forever, with pins and a paste suggestion in the strip; every emoji your phone can draw, with Frequent and skin tones.
- **Scan text:** take a picture, tap the lines you want, and they're typed where you were.
- **Autofill:** passwords and addresses from your autofill service as chips in the strip.
- **Edit panel:** arrows that keep going while held, jumps by word and to either end of the line, *Select* to grow a selection with the same arrows, Select all, Copy, Cut, Paste, Undo and Redo.
- Caps lock shows a bar under the shift arrow, so it never reads as a one-letter shift.
- Slide on space to move the cursor, number row, key height, double-space full stop.

## Volume

Press a volume key and an ink capsule grows out of the screen edge beside the buttons. Liquid fills it to the level and sloshes as it moves; a drop pinches off its side with the number and merges back when you stop. Two more drops separate from its ends: the ringer (ring, vibrate, silent) above, and below, the way into everything else. Tap that and the capsule spreads into a sheet: where sound is going (tap it for Android 14's output picker), the ringer, media, calls, ring, notifications and alarms, and a slider for each app that's playing. Drag the capsule itself to set the level, with a tick on every step.

- **Each app's own volume** (with Shizuku): turn one app down without touching the rest. The level is kept while the app is open, re-applied whenever it opens a new player, and remembered for next time; back to full forgets it.
- **Play alongside** (with Shizuku): switch an app on and it neither pauses others when it starts nor gets paused when they do; calls and alarms still pause it, and directions still lower it. While any app is on, the helper looks after audio focus for the phone (as an audio focus policy, run the way Android runs it), because simply refusing an app focus, the usual trick, stops most music and video apps from playing at all.
- **Boost past 100%:** off until you switch it on. At full media volume, press volume up twice to go louder in 5% steps (each one +1.5 dB, up to 150%); volume down brings it back first, and a held key never climbs into it. With the EQ processing, the boost sits inside its chain in front of the limiter; otherwise Android's loudness effect does it through Shizuku. It clears itself when the sound moves to another device.
- **Silent without Do Not Disturb** (with Shizuku): silent goes through Android's own internal path, the way its panel does it.
- **Knows when to step aside:** a ringing call (the keys silence it), casting (the keys move the cast device) and the camera (the keys are its shutter) are left to Android.
- **The Volume page:** every stream with − and + (hold to keep going) and its real step, `73% · 11/15`, so it's clear why a level lands where it does; the ringer; apps; play alongside; the panel's side and height; and a **Volume** Quick Settings tile for when the buttons aren't to hand.

It needs one accessibility switch (*Continuum volume*), which listens to the two volume keys and nothing else. *Switch on with Shizuku* in the dock's setup flips it for you; Shizuku also starts a small helper for the per-app parts.

## The island

The island is one piece of ink that starts as your camera. It follows the Continuum liquid rule: ink never appears or vanishes, it only separates from, or merges with, ink already on screen.

- **Music and video** stretch the camera into a pill: artwork on one side, and three drops of the track's colour on the other that bob and fuse like liquid while it plays, settling into one calm drop when paused. Tap to open the player: the seek bar is a line of liquid ending in a drop that glides as the song plays and swells under your finger, play and pause melt into each other, and skip presses the triangle into the bar.
- **Calls** drip down from the camera when they ring, with Decline and Answer. During a call the pill keeps a green timer; tap it to end the call.
- **Timers** from your clock app count down in the pill, or in a second bubble that pinches off the side when music is playing too.
- **Notifications** drip down for a few seconds with their own buttons, Reply included. Tap to open, swipe sideways to clear, up to tuck away. *Only the island* switches Android's own pop-ups off while the island shows them.
- **Alarms:** your next alarm sits in the island for the 12 hours before it (or whenever one is set). Alarms and finished timers drip down as they ring, with the clock's Snooze and Stop.
- **Calendar:** your next event from 15 minutes before it starts, counting down by the minute and turning green at *Now*. Open it for the time and place, and **Join** when it has a Meet, Zoom, Teams or similar link. Off until you switch it on and allow calendar access; it's read on the phone only.
- **Downloads and live updates:** progress rings for downloads, plus directions, deliveries and rides while they're happening.
- **Sound mode and headphones** speak up for a moment: ring, vibrate or silent as you switch, and headphones, speakers or the car as they connect.
- **Pull a drop:** hold the island (or the bare camera) and drag down. A drop stretches out on a neck; let go early and it springs back in, pull past the snap and it tears free as a card of your recent notifications. Swipe it sideways for the next one, tap to open, swipe up to send it back.
- **Always on:** switch on *Time* and *Battery* to keep them in the island all the time, beside whatever else is live. The time can sit left of the artwork, right of it, or around the camera, with the hours on one side, the minutes on the other and the camera as the colon. The battery is a ring round the camera that fills with your charge (green while charging, with a glint running along it; red when low), so it takes no room in the island at all; with nothing else on, the ink is just big enough to hold it. Prefer the ink capsule on the far right? It's still there. With nothing playing, the island rests as a slim pill of the time and date.
- **Charging and battery:** plugging in, by cable or wireless, sweeps the ring up from empty to your charge, with the percentage beside the camera (or shows it in a liquid battery that ripples as it fills). It also speaks up at 20% and 10%, and when you're fully charged. Tap it for the time to full.

- **Placement:** the island starts fitted to the camera. Move it up, down, left or right, fit it to your lens with *Camera size* (so the ink and the ring hug it), scale everything with *Size*, and shape the pill with *Height* and *Width*. It shows itself while you adjust it, and *Reset* puts it back on the camera.

On Android 12 and later the ink is rendered as a true liquid (blur and threshold) while it changes shape, so pieces fuse and necks thin before they snap; at rest it's drawn as plain shapes, with no blur running. Older versions draw the necks without the blur. Nothing on the island animates unless it has a reason to (music playing, charging, an indeterminate download), so a still island isn't redrawn at all, and with the screen off it rests completely. The ink itself is pure black, so on an AMOLED screen it's pixels switched off.

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

## Sound

- **Two ways to run:** *Capture* records what apps play and plays it back through the EQ's exact filters. *Direct* puts the EQ on each app's own sound instead, with no screen-capture prompt or icon, less battery and delay, and apps that block capture included; the filters are matched by 96 fine bands (about a third of a decibel off on a typical headphone EQ).
- **Parametric EQ** with a response graph you drag: sideways for frequency, up and down for gain.
- **Live spectrum:** while processing, the music itself moves behind the curve as a soft liquid outline.
- **Hold to compare:** hear the music without the EQ, at matched loudness, so the louder of the two can't win just by being louder. The curve drains flat while you hold.
- **EQ per device:** link an EQ to your headphones, speaker, car or USB DAC, and it comes on by itself whenever they connect.
- **Polarity:** turn the sound over for earphones that come out the wrong way round (a push in the recording arriving as a pull), or mend one channel wired backwards. It's saved with the EQ, stays on with the EQ switched off, and goes into shared files as Equalizer APO's `Copy: L=-1*L R=-1*R`. Capture mode only: Direct mode's effect can raise and lower the sound but not turn it over.
- **EQ per app:** give an app its own EQ (a podcast app a voice EQ, say). In Direct mode each app plays through its own, side by side; in Capture mode, where everything is mixed first, an app's own EQ is used while it's what's playing. A music player's **Equalizer** button opens Continuum FX and offers that app one.
- **Backup:** everything you've set up (EQs and their links, effects, island, volume panel, each app's volume) in one file, to keep or move to a new phone.
- **Quick Settings tiles:** *Continuum EQ* (on or off, showing which EQ is playing) and *Island*.
- Changes glide in over 10 ms, so dragging a band never clicks, and apps that start playing are picked up within moments.

## Effects

The effects sit on top of the EQ, on their own page. Each says in a line what it's doing, opens to explain itself, and says when it's resting (speaker bass on headphones, say) or works more simply in Direct mode. Hold to compare takes them away too, at matched loudness.

- **Analog bass:** the Pultec trick (lift and cut at once, so the bass swells while the mud just above it dips away) at 20, 30, 60 or 100 Hz, with drive (harmonics that thicken the low end), warmth (from edgy odd harmonics to round even ones) and drift (old gear's slight wander).
- **Punch:** bass notes are lifted at their start, by how fast they rise, and left alone while held: kicks hit, held notes don't boom.
- **Speaker bass:** for the phone's own speaker, the missing fundamental: deep notes it can't play are taken out and their harmonics put in where it can, and hearing fills the notes back in.
- **Tube warmth:** a low-end bloom, a softer top, second-order-led harmonics and a slow glue whose level is made up, so it's never quieter.
- **Exciter:** harmonics of the upper mids, added back above them: presence without a treble boost's hiss.
- **Hi-res detail:** the air above 5 kHz is lifted while there's music in it, not when there's only hiss, and a top octave cut off by lossy coding is rebuilt faintly from the octave below.
- **Stage:** Natural, Front or Wide, by mid and side, so only what differs between your ears is widened and voices stay exactly centred.
- **Crossfeed:** for headphones, each ear hears a little of the other, low-passed and slightly late, as from speakers; anything in the middle comes out exactly as it went in.
- **Dynamics:** *Pure* rounds peaks with a soft 2:1 shoulder instead of a wall and lets nothing else squeeze the music; *Restore* lets quiet passages of loud, flattened masters back down and opens their top.
- **Loudness:** follows your volume and puts back the bass and treble hearing loses as it comes down, by the ISO 226:2003 equal-loudness contours. At full volume it does nothing.
- **Bluetooth care:** eases off the top octave so Bluetooth's basic SBC codec has more of its fixed budget of bits for the music.
- **Listening on:** say whether each output is in-ear, on-ear, over-ear or a speaker, and bass and treble effects ask no more than that kind of driver can give.

In **Capture** mode every effect is real signal processing on the captured sound. Harmonics come from Chebyshev polynomials of band-limited signals, so they're exact and nothing aliases, with no oversampling. **Direct** mode runs inside Android's own DynamicsProcessing effect, which has a band EQ, a seven-band compressor and a limiter and can't make harmonics or mix the ears. There, plain tone joins the EQ's 96 bands, what moves with the music is done with the compressor (slow-attack compression for punch, a gated lift for hi-res detail), Stage widens by lifting and lowering alternate stretches of each ear's treble, and crossfeed waits for Capture.

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
- [x] Effects: analog bass, punch, speaker bass, tube, exciter, hi-res detail, stage, crossfeed, dynamics, loudness, Bluetooth care
- [x] An EQ per app, the players' Equalizer button, and backup
- [ ] Convolver and room effects
- [x] EQ per output device, live spectrum, hold to compare, Quick Settings tiles
- [x] A volume panel in liquid ink: per-app volume, play alongside, boost, silent without Do Not Disturb
- [ ] Per-app exclusions
- [x] Liquid ink island: music, calls, timers and notifications
- [x] Island: alarms, calendar, downloads and directions, charging, sound mode, headphones
- [x] Island: placement and camera fitting, battery ring
- [x] In-app updates that install over the top
- [x] The Continuum keyboard
- [ ] Liquid-ink transitions for the remaining sheets

## Credits and licences

- Continuum FX is licensed under the **GNU GPL v3.0** (see `LICENSE`), keeping it compatible with the GPL audio engines it builds on.
- Continuum Keyboard (`keyboard/`) is original code, also under the GPL v3.0, with an additional permission to use Google's ML Kit text recognition (see `keyboard/README.md`). Its word data comes from wordfreq (CC BY-SA 4.0), SCOWL and the Google Books Ngram data via SymSpell (CC BY 3.0); emoji data from Emojibase (MIT).
- The volume panel's key handling, Shizuku helper, per-app volume sessions and boost scale are adapted from [Volume++](https://github.com/noel-digital-fan/volume_plus_plus) by noel-digital-fan (MIT). See `THIRD_PARTY_NOTICES.md`.
- The rootless approach (capture, then silence the originals through a per-session effect) follows [RootlessJamesDSP](https://github.com/ThePBone/RootlessJamesDSP) by Tim Schneeberger.
- Filters follow Robert Bristow-Johnson's *Audio EQ Cookbook*. Loudness uses the equal-loudness model and table of ISO 226:2003.
- The effects' line-up follows [JadOO DSP](https://github.com/rudrakshchatterjee3-creator/Jadoo-DSP) by rudrakshchatterjee3-creator. Its code is proprietary, so none of it is used: Continuum's effects are written from scratch, from the published descriptions of what each one does.
- [Libron](https://github.com/nicoverbruggen/libron) by Nico Verbruggen, under the SIL Open Font License 1.1 (see `fonts/LIBRON-OFL.txt`).
- [Shizuku](https://github.com/RikkaApps/Shizuku) by RikkaApps.
- The island's overlay approach is adapted from [Essentials](https://github.com/sameerasw/essentials) by Sameera Sandakelum (MIT). See `THIRD_PARTY_NOTICES.md`.
- Phone and music glyphs from Google's Material Icons (Apache 2.0).
