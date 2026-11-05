#!/usr/bin/env python3
"""Re-aim fixture source citations that 351-logic-fixture-integrity rejects, and pin them with an anchor.

    python -I tools/fix-fixture-lines.py --mod-source <killer560s-mod checkout> [--write]

A fixture's `source` is {"file", "line"}; the optional "anchor" is text on the cited line. With an anchor the check finds
the line by the text (a moved line cannot rot it) and fails when the text is gone. For every fixture whose cited line
is not substantive or does not relate to the payload (the same tests as FixtureCases), this picks the best line of the
file (a line naming a cited field with Pattern.compile, then one sharing a payload word / menu title word, nearest to the
old line), and rewrites the source with the new line and an anchor. Fixtures that already pass are left alone, unless
they have an anchor, in which case the line is refreshed from it. Without --write it only prints the plan.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "src/gametest/resources/testkit-fixtures"
SRC_RE = re.compile(r'"source"\s*:\s*\{[^{}]*\}')


def texts(f):
    p, k = f.get("payload"), f["kind"]
    if k in ("chat", "overlay", "title"):
        return [p] if isinstance(p, str) else []
    if k == "sidebar":
        return list(p["lines"])
    if k == "tab":
        return [e["text"] if isinstance(e, dict) else e for e in p["entries"]]
    return []


def refs(f):
    out = list(f.get("matches", [])) + list(f.get("mustNotMatch", []))
    for g in ("gates", "gate"):
        v = f.get(g)
        out += v if isinstance(v, list) else ([v] if isinstance(v, str) else [])
    return out


def fields(f):
    return [re.sub(r"\[\d+]$", "", r.split("#", 1)[1]) for r in refs(f) if "#" in r]


def words(f):
    ts = texts(f)
    p = f.get("payload")
    if f["kind"] == "menu" and isinstance(p, dict):
        ts.append(str(p.get("title", "")))
    return {w.lower() for t in ts for w in re.split(r"[^A-Za-z]+", t) if len(w) >= 3}


def substantive(t):
    return len(t) > 3 and not re.fullmatch(r"[{}();,\s]*", t)


def relates(t, f):
    if "Pattern.compile" in t or any(x in t for x in fields(f)):
        return True
    low = t.lower()
    if any(w in low for w in words(f) if f["kind"] != "menu"):
        return True
    for x in texts(f):
        if any(ord(c) > 0x7F and c != "§" and not c.isspace() and c in t for c in x):
            return True
    return not texts(f)


def best(f, lines, old):
    flds = fields(f)
    ws = words(f)
    scored = []
    for i, raw in enumerate(lines, 1):
        t = raw.strip()
        if not substantive(t):
            continue
        s = 0
        if any(re.search(r"\b%s\b" % re.escape(x), t) for x in flds):
            s = 4 if "Pattern.compile" in t or "static final" in t else 3
        elif f["kind"] == "menu" or not texts(f):
            s = 2 if any(w in t.lower() for w in ws) else 0
        elif relates(t, f):
            sym = any(ord(c) > 0x7F and c != "§" and not c.isspace() and c in t for x in texts(f) for c in x)
            s = 1 + (1 if any(w in t.lower() for w in ws) else 0) + (2 if sym else 0)
        if s:
            scored.append((-s, abs(i - old), i))
    return min(scored)[2] if scored else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mod-source", required=True)
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()
    mod = Path(a.mod_source)
    changed = bad = 0
    for path in sorted(ROOT.rglob("*.json")):
        if path.parent == ROOT:
            continue
        text = path.read_text(encoding="utf-8")
        data = json.loads(text)
        ms = list(SRC_RE.finditer(text))
        if len(ms) != len(data):
            print("SKIP", path, "source count", len(ms), "!=", len(data))
            continue
        out, pos = [], 0
        for f, m in zip(data, ms):
            s = f["source"]
            sp = mod / s["file"]
            if not sp.is_file():
                print("MISSING FILE", f["id"], s["file"])
                bad += 1
                continue
            lines = sp.read_text(encoding="utf-8").split("\n")
            line = s["line"]
            if "anchor" in s:
                hit = [i for i, l in enumerate(lines, 1) if s["anchor"] in l]
                line = hit[0] if hit else -1
            ok = 1 <= line <= len(lines) and substantive(lines[line - 1].strip()) and relates(lines[line - 1].strip(), f)
            if ok and "anchor" in s and line == s["line"]:
                continue
            if ok and "anchor" not in s:
                continue
            new = line if ok else best(f, lines, s["line"])
            if new is None:
                print("NO CANDIDATE", f["id"], s["file"], s["line"])
                bad += 1
                continue
            anchor = re.split(r"\s=(?:\s|$)", lines[new - 1].strip())[0][:90]  # the declaration head, not a regex that gets tuned
            src = {"file": s["file"], "line": new, "anchor": anchor}
            print("%s %s:%d -> %d  %s" % (f["id"], s["file"].rsplit("/", 1)[-1], s["line"], new, anchor))
            out.append(text[pos:m.start()])
            out.append('"source": ' + json.dumps(src, ensure_ascii=False))
            pos = m.end()
            changed += 1
        out.append(text[pos:])
        if a.write and pos:
            path.write_text("".join(out), encoding="utf-8", newline="")
    print("re-aimed", changed, "unresolved", bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
