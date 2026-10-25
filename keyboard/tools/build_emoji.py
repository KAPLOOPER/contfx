#!/usr/bin/env python3
"""
Builds Continuum Keyboard's emoji table from emojibase-data (npm "emojibase-data", MIT).

  python3 build_emoji.py path/to/emojibase-data/en/data.json out/emoji.txt

Each line: group<TAB>emoji<TAB>label<TAB>skin variants (comma separated, light to dark).
Groups follow Unicode: 0 smileys, 1 people, 3 animals, 4 food, 5 travel, 6 activities,
7 objects, 8 symbols, 9 flags. Components (2) are left out.
"""
import json
import sys


def main():
    data = json.load(open(sys.argv[1], encoding="utf8"))
    rows = []
    for e in data:
        group = e.get("group")
        if group is None or group == 2 or "order" not in e:
            continue
        skins = []
        for s in e.get("skins") or []:
            tone = s.get("tone")
            if isinstance(tone, int):
                skins.append((tone, s["emoji"]))
        skins = [emoji for _, emoji in sorted(skins)]
        if len(skins) != 5:
            skins = []
        label = e["label"].replace("\t", " ")
        rows.append((e["order"], group, e["emoji"], label, ",".join(skins), e.get("version", 0)))
    rows.sort()
    with open(sys.argv[2], "w", encoding="utf8") as out:
        for _, group, emoji, label, skins, version in rows:
            out.write(f"{group}\t{emoji}\t{label}\t{skins}\t{version}\n")
    print(len(rows))


if __name__ == "__main__":
    main()
