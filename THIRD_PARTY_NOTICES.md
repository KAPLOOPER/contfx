# Third-party notices

## Essentials

The island's overlay approach (an accessibility overlay that only draws, with a small
touch window forwarding input to it, camera position from the display cutout) and the
overlay lifecycle owner in `app/src/main/java/dev/continuum/fx/island/OverlayLifecycleOwner.kt`
are adapted from [Essentials](https://github.com/sameerasw/essentials).

```
MIT License

Copyright (c) 2025 Sameera Sandakelum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Material Icons

The phone, end-call and music-note glyphs (`ic_call`, `ic_call_end`, `ic_music`) use
paths from Google's [Material Icons](https://github.com/google/material-design-icons),
licensed under the Apache License 2.0.

## Libron

[Libron](https://github.com/nicoverbruggen/libron) by Nico Verbruggen, under the SIL Open
Font License 1.1. See `fonts/LIBRON-OFL.txt`.

## Volume++

The volume panel's key handling (`volume/VolumeService.kt`), its Shizuku helper
(`volume/VolumeHelperService.kt`, `IVolumeHelper.aidl`), per-app volume sessions
(`volume/AppVolumes.kt`) and the boost scale (`BoostPolicy`) are adapted from
[Volume++](https://github.com/noel-digital-fan/volume_plus_plus) by noel-digital-fan.

```
MIT License

Copyright (c) 2026 noel-digital-fan

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Shizuku

[Shizuku](https://github.com/RikkaApps/Shizuku) API by RikkaApps, Apache License 2.0.

## Continuum Keyboard data

- **Word frequencies:** [wordfreq](https://github.com/rspeer/wordfreq) by Robyn Speer and contributors. Its data is
  licensed under CC BY-SA 4.0; the frequencies in `keyboard/src/main/assets/words/en_words.txt` are derived from it
  and shared under the same licence.
- **Spellings:** SCOWL by Kevin Atkinson (via the `@cspell/dict-en_us` and `wordlist-english` packages), under the
  SCOWL licence (copyright notice and permission notice included with those packages; permission to use, copy,
  modify, distribute and sell granted provided the notice is kept).
- **Word pairs:** `frequency_bigramdictionary_en_243_342.txt` from [SymSpell](https://github.com/wolfgarbe/SymSpell)
  by Wolf Garbe (MIT), built from the Google Books Ngram data (CC BY 3.0). `keyboard/src/main/assets/words/en_next.txt`
  is derived from it.
- **Emoji:** [Emojibase](https://github.com/milesj/emojibase) data by Miles Johnson, MIT licence.
- **Text recognition:** Google ML Kit, under the ML Kit terms.

`keyboard/tools/` has the scripts that build these files.
