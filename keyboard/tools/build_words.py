#!/usr/bin/env python3
"""
Builds Continuum Keyboard's English word list and next-word table.

Inputs (fetch them yourself, then pass the paths):
  --spelling  a plain list of correctly spelt, correctly cased English words, one per line.
              We use SCOWL-derived en_US words from the @cspell/dict-en_us npm package
              (decode en_US.trie.gz with cspell-trie-lib), MIT / SCOWL licence.
  --variants  a folder of wordlist-english JSON files (npm "wordlist-english", SCOWL licence)
              for British, Canadian and Australian spellings.
  --bigrams   SymSpell's frequency_bigramdictionary_en_243_342.txt (pip "symspellpy", MIT),
              counts from the Google Books Ngram data (CC BY 3.0).
Frequencies come from the wordfreq package (pip "wordfreq", data CC BY-SA 4.0).

Outputs:
  en_words.txt  word<TAB>frequency, most frequent first. Frequency is wordfreq's Zipf value x10.
  en_next.txt   word<TAB>next<TAB>score, where score is log10 P(next | word) x100 (negative).
"""
import argparse
import glob
import json
import math
import re
from collections import defaultdict

import wordfreq

# Everyday words a spelling list leaves out, but people type all the time.
EVERYDAY = """
ok lol omg wifi haha hahaha idk tbh thx pls plz btw imo imho fyi asap brb ttyl lmao lmk nvm ikr smh tho cuz
bday gf bf bc ty np yw jk ily irl dm dms selfie selfies emojis vibe vibes lowkey highkey
TikTok WhatsApp YouTube Spotify Snapchat Netflix Uber Google Gmail iPad AirPods Instagram Facebook Reddit
Discord Zoom PlayStation Xbox Bluetooth Wi-Fi
""".split()

# Single letters people don't mean as words.
LONE_LETTERS_KEPT = {"a", "I"}

WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ][A-Za-zÀ-ÖØ-öø-ÿ'\-]*")


def zipf(word: str) -> float:
    return wordfreq.zipf_frequency(word.lower(), "en", wordlist="large")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spelling", required=True)
    ap.add_argument("--variants", required=True)
    ap.add_argument("--bigrams", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--min-zipf", type=float, default=1.0)
    args = ap.parse_args()

    words = set()
    for line in open(args.spelling, encoding="utf8"):
        w = line.strip()
        if not w or w[0] in "~!+":
            continue
        words.add(w)
    for f in glob.glob(f"{args.variants}/*-words-*.json"):
        size = int(re.search(r"-(\d+)\.json$", f).group(1))
        if size <= 60:
            words.update(json.load(open(f, encoding="utf8")))
    words.update(EVERYDAY)

    kept = {}
    for w in words:
        if not WORD.fullmatch(w) or w.endswith("-") or w.endswith("'"):
            continue
        if len(w) == 1 and w not in LONE_LETTERS_KEPT:
            continue
        if w.isupper() and len(w) > 1 and w not in EVERYDAY:
            # Acronyms collide with ordinary words (US, IT, AM); keep only common ones.
            if zipf(w) < 3.5:
                continue
        f = zipf(w)
        if f < args.min_zipf and w not in EVERYDAY:
            continue
        kept[w] = f

    # Several spellings can share a lower-case form ("may" / "May", "us" / "US").
    # The plain lower-case one is the common word; the others step back.
    by_lower = defaultdict(list)
    for w in kept:
        by_lower[w.lower()].append(w)
    for low, forms in by_lower.items():
        if len(forms) > 1 and low in kept:
            for w in forms:
                if w != low:
                    kept[w] = max(0.0, kept[w] - 1.2)
    if "i" in kept:
        del kept["i"]
    kept["I"] = max(kept.get("I", 0.0), zipf("i"))

    ordered = sorted(kept.items(), key=lambda kv: (-kv[1], kv[0]))
    with open(f"{args.out}/en_words.txt", "w", encoding="utf8") as out:
        for w, f in ordered:
            out.write(f"{w}\t{int(round(f * 10))}\n")
    print("words", len(ordered))

    # Next-word table from book bigrams, limited to words we know. P(next | word) is each
    # pair's share of everything that follows the word in the bigram list.
    known = {w.lower() for w in kept}
    nexts = defaultdict(list)
    totals = defaultdict(int)
    for line in open(args.bigrams, encoding="utf8"):
        parts = line.split()
        if len(parts) != 3:
            continue
        a, b, c = parts[0], parts[1], int(parts[2])
        totals[a] += c
        if a not in known or b not in known:
            continue
        nexts[a].append((b, c))
    rows = 0
    with open(f"{args.out}/en_next.txt", "w", encoding="utf8") as out:
        for a in sorted(nexts):
            best = sorted(nexts[a], key=lambda bc: -bc[1])[:16]
            for b, c in best:
                p = c / totals[a]
                out.write(f"{a}\t{b}\t{int(round(math.log10(p) * 100))}\n")
                rows += 1
    print("next", rows, "from", len(nexts))


if __name__ == "__main__":
    main()
