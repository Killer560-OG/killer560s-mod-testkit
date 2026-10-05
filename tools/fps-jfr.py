"""Attribute render-thread CPU samples and allocations in a JFR file to killer560smod code.

usage: python nt-fps-jfr.py file.jfr [top]
Needs `jfr` (JDK bin) on PATH or JAVA_HOME set.
"""
import collections
import json
import os
import subprocess
import sys

MOD = "com.killer560."


def jfr_exe():
    jh = os.environ.get("JAVA_HOME")
    if jh:
        for name in ("jfr.exe", "jfr"):
            p = os.path.join(jh, "bin", name)
            if os.path.exists(p):
                return p
    return "jfr"


def load(path, event):
    out = subprocess.run([jfr_exe(), "print", "--json", "--stack-depth", "96", "--events", event, path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if out.returncode != 0:
        sys.exit(out.stderr)
    return json.loads(out.stdout)["recording"]["events"]


def frames(ev):
    st = ev["values"].get("stackTrace")
    if not st:
        return []
    res = []
    for f in st["frames"]:
        m = f["method"]
        cls = m["type"]["name"]
        res.append(cls.replace("/", ".") + "." + m["name"])
    return res  # innermost first


def thread_name(ev):
    t = ev["values"].get("sampledThread") or ev["values"].get("eventThread") or {}
    return t.get("javaName") or t.get("osName") or ""


def main():
    path = sys.argv[1]
    top = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    samples = [e for e in load(path, "jdk.ExecutionSample") if thread_name(e) == "Render thread"]
    n = len(samples)
    self_c = collections.Counter()
    incl_mod = collections.Counter()
    entry_mod = collections.Counter()
    mod_any = 0
    for e in samples:
        fr = frames(e)
        if not fr:
            continue
        self_c[fr[0]] += 1
        mods = [f for f in fr if f.startswith(MOD)]
        if mods:
            mod_any += 1
            entry_mod[mods[-1]] += 1
            for f in set(mods):
                incl_mod[f] += 1
    print(f"{path}: {n} render-thread samples, {mod_any} ({100.0 * mod_any / max(n, 1):.1f}%) with mod code on the stack")
    print("\n-- outermost mod frame (entry point), inclusive --")
    for k, v in entry_mod.most_common(top):
        print(f"{v:6d} {100.0 * v / n:5.1f}%  {k}")
    print("\n-- any mod frame, inclusive --")
    for k, v in incl_mod.most_common(top):
        print(f"{v:6d} {100.0 * v / n:5.1f}%  {k}")
    print("\n-- self (top frame), all code --")
    for k, v in self_c.most_common(top):
        print(f"{v:6d} {100.0 * v / n:5.1f}%  {k}")

    allocs = [e for e in load(path, "jdk.ObjectAllocationSample") if thread_name(e) == "Render thread"]
    by_site = collections.Counter()
    by_entry = collections.Counter()
    total = 0
    mod_total = 0
    for e in allocs:
        w = int(e["values"].get("weight", 0))
        total += w
        fr = frames(e)
        mods = [f for f in fr if f.startswith(MOD)]
        if mods:
            mod_total += w
            cls = e["values"]["objectClass"]["name"]
            by_site[mods[0] + " -> " + cls] += w
            by_entry[mods[-1]] += w
    print(f"\n-- allocation (sampled weight) on render thread: total {total / 1e6:.1f} MB, with mod on stack {mod_total / 1e6:.1f} MB --")
    for k, v in by_entry.most_common(top):
        print(f"{v / 1e6:9.2f} MB  {k}")
    print("\n-- allocation by innermost mod frame -> class --")
    for k, v in by_site.most_common(top):
        print(f"{v / 1e6:9.2f} MB  {k}")


if __name__ == "__main__":
    main()
