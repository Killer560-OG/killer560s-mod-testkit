"""Builds a walkable stand-in for a Skyblock island from its SkyHanni navigation graph and NEU's fairy soul list.

Hypixel's real terrain is not available, so every graph edge becomes a corridor whose floor follows the edge's heights,
and each edge draws one movement class from a stable hash: block steps, slab half-steps, stairs, a ladder up a cliff
(any edge steeper than 1:1), a single-wide lane between fences, an open door in a wall, a water pool to swim, a low
tunnel, a carpet/snow/path/soul-sand floor. Nodes get a pad, and each soul head (NEU's coordinate, "the soul itself and
not where you stand") gets the block under it as a ledge plus a strip of ground from its graph node toward it - which
reproduces killer560's 2026-11-01 picture: the soul node is up to 3.6 blocks from the soul itself.

Output per island (src/gametest/resources/fairy-islands/): <NAME>.blocks.gz ("x y z state" lines, supports first,
then the attached blocks), <NAME>.meta.json (translated graph, souls, spawn, crowd spots, per-edge classes).

    python -I tools/fairy-islands/gen.py
"""
import gzip
import hashlib
import json
import math
import os
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "src", "gametest", "resources", "fairy-islands")

# name, graph file, NEU key, y offset (keeps the floors above natural terrain), optional x/z box, label
ISLANDS = [
    ("GOLD_MINES", "GOLD_MINES.json", "mining_1", 140, None),
    ("THE_PARK", "THE_PARK.json", "foraging_1", 95, None),
    ("BACKWATER_BAYOU", "BACKWATER_BAYOU.json", "fishing_1", 110, None),
    ("SPIDER_DEN", "SPIDER_DEN.json", "combat_1", 125, None),
    ("HUB_EAST", "HUB.json", "hub", 100, (20, 170, -203, -60)),
]

CLASSES_FLAT = ["plain", "plain", "fence_lane", "door", "water", "tunnel", "low_tunnel", "carpet", "snow", "path",
                "soul_sand", "narrow", "crowd"]
STEP_STYLES = ["block", "slab", "stairs"]


def h(*parts):
    return int(hashlib.md5("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


def load(name, file, key, box):
    g = json.load(open(os.path.join(HERE, file)))
    nodes = {}
    for k, v in g.items():
        x, y, z = (int(round(float(c))) for c in v["Position"].split(":"))
        if box and not (box[0] <= x <= box[1] and box[2] <= z <= box[3]):
            continue
        nodes[k] = {"x": x, "y": y, "z": z, "tags": v.get("Tags") or [], "name": v.get("Name"),
                    "nb": {n: d for n, d in v["Neighbours"].items()}}
    for v in nodes.values():
        v["nb"] = {n: d for n, d in v["nb"].items() if n in nodes}
    # largest connected component only (a box cuts the hub)
    seen, best = set(), []
    for k in nodes:
        if k in seen:
            continue
        comp, q = [], deque([k])
        seen.add(k)
        while q:
            a = q.popleft()
            comp.append(a)
            for b in nodes[a]["nb"]:
                if b not in seen:
                    seen.add(b)
                    q.append(b)
        if len(comp) > len(best):
            best = comp
    keep = set(best)
    nodes = {k: v for k, v in nodes.items() if k in keep}
    for v in nodes.values():
        v["nb"] = {n: d for n, d in v["nb"].items() if n in keep}
    neu = json.load(open(os.path.join(HERE, "neu_fairy_souls.json")))[key]
    neu = [tuple(int(c) for c in s.split(",")) for s in neu]
    souls = []
    for k, v in nodes.items():
        if "fairy_soul" in v["tags"]:
            p = (v["x"], v["y"], v["z"])
            s = min(neu, key=lambda n: math.dist(n, p))
            souls.append({"node": k, "head": list(s)})
    return nodes, souls


class World:
    """Blocks with a priority: a later write of lower priority does not replace an earlier one of higher."""

    def __init__(self):
        self.b = {}
        self.air = set()  # cells that must stay passable (ladder shafts, headroom over every walking cell)

    def put(self, x, y, z, state, prio=1):
        cur = self.b.get((x, y, z))
        if cur is None or prio >= cur[1]:
            self.b[(x, y, z)] = (state, prio)

    def get(self, x, y, z):
        c = self.b.get((x, y, z))
        return None if c is None else c[0]


def columns(ax, az, bx, bz):
    """Integer columns along a line, each once, in order (a supercover-ish walk at 0.25 steps)."""
    n = max(1, int(math.ceil(math.hypot(bx - ax, bz - az) / 0.25)))
    out = []
    for i in range(n + 1):
        t = i / n
        c = (int(math.floor(ax + (bx - ax) * t + 0.5)), int(math.floor(az + (bz - az) * t + 0.5)))
        if not out or out[-1] != c:
            out.append(c)
    return out


def side_offsets(dx, dz, width):
    """Perpendicular offsets for a corridor of this width (1 or 3)."""
    if width <= 1:
        return [(0, 0)]
    if abs(dx) >= abs(dz):
        return [(0, -1), (0, 0), (0, 1)]
    return [(-1, 0), (0, 0), (1, 0)]


def facing(dx, dz):
    if abs(dx) >= abs(dz):
        return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"


OPP = {"east": "west", "west": "east", "north": "south", "south": "north"}


def floor_state(cls):
    return {"carpet": None, "snow": None, "path": "minecraft:dirt_path", "soul_sand": "minecraft:soul_sand"}.get(
        cls, "minecraft:stone")


def build(name, file, key, dy, box):
    nodes, souls = load(name, file, key, box)
    w = World()
    log = []
    crowds = []
    for k, v in nodes.items():
        v["y"] += dy
    for s in souls:
        s["head"][1] += dy
    soul_nodes = {s["node"] for s in souls}

    # node pads (3x3), lowest priority so corridors and ladders shape them
    for k, v in nodes.items():
        for ox in (-1, 0, 1):
            for oz in (-1, 0, 1):
                w.put(v["x"] + ox, v["y"] - 1, v["z"] + oz, "minecraft:grass_block", 1)
        for yy in range(3):
            w.air.add((v["x"], v["y"] + yy, v["z"]))

    done = set()
    for a, va in nodes.items():
        for b in va["nb"]:
            if (b, a) in done:
                continue
            done.add((a, b))
            vb = nodes[b]
            lo, hi = (va, vb) if va["y"] <= vb["y"] else (vb, va)
            hd = math.hypot(hi["x"] - lo["x"], hi["z"] - lo["z"])
            rise = hi["y"] - lo["y"]
            seed = h(name, a, b)
            cols = columns(lo["x"], lo["z"], hi["x"], hi["z"])
            ddx, ddz = hi["x"] - lo["x"], hi["z"] - lo["z"]
            if rise >= 2 and rise > len(cols) - 1:
                cls = "ladder"
            elif rise == 0 or len(cols) < 4:
                cls = CLASSES_FLAT[seed % len(CLASSES_FLAT)]
                if rise != 0 and cls in ("low_tunnel", "door", "water"):
                    # a 2-high tunnel over a step cannot be jumped, and a door or pool needs a flat stretch
                    cls = "tunnel" if cls == "low_tunnel" else "plain"
            else:
                cls = "ramp_" + STEP_STYLES[seed % len(STEP_STYLES)]
            width = 1 if cls in ("fence_lane", "door", "narrow") else 3
            log.append({"a": a, "b": b, "class": cls, "len": round(hd, 1), "rise": rise})
            sides = side_offsets(ddx, ddz, width)
            if cls == "ladder":
                # floor at lo's level up to the column before hi; a cliff pillar in hi's column; a ladder on its face.
                f = facing(ddx, ddz)
                stepx, stepz = {"east": (1, 0), "west": (-1, 0), "south": (0, 1), "north": (0, -1)}[f]
                lx, lz = hi["x"] - stepx, hi["z"] - stepz
                run = columns(lo["x"], lo["z"], lx, lz)
                for (cx, cz) in run:
                    for (sx, sz) in side_offsets(stepx, stepz, 3):
                        w.put(cx + sx, lo["y"] - 1, cz + sz, "minecraft:stone", 2)
                for yy in range(lo["y"] - 1, hi["y"]):
                    w.put(hi["x"], yy, hi["z"], "minecraft:stone", 4)
                for yy in range(lo["y"], hi["y"]):
                    # the ladder hangs on the pillar: its FACING points away from the wall, back toward lo
                    w.put(lx, yy, lz, "minecraft:ladder[facing=" + OPP[f] + "]", 6)
                for yy in range(lo["y"], hi["y"] + 3):
                    w.air.add((lx, yy, lz))
                for yy in range(hi["y"], hi["y"] + 3):
                    w.air.add((hi["x"], yy, hi["z"]))
                continue
            # a height per column: the rise spread evenly, never more than one block per column
            n = len(cols) - 1
            heights = [lo["y"] + int(math.floor(rise * i / n + 0.5)) if n else lo["y"] for i in range(n + 1)]
            style = cls[5:] if cls.startswith("ramp_") else "block"
            water_at = set()
            if cls == "water" and n >= 7:
                mid = n // 2
                water_at = {mid - 1, mid, mid + 1}
            door_at = n // 2 if cls == "door" and n >= 4 else -1
            for i, (cx, cz) in enumerate(cols):
                y = heights[i]
                prev = heights[i - 1] if i else y
                nxt = heights[i + 1] if i < n else y
                for (sx, sz) in sides:
                    x, z = cx + sx, cz + sz
                    if i in water_at:
                        w.put(x, y - 3, z, "minecraft:stone", 3)
                        w.put(x, y - 2, z, "minecraft:water", 3)
                        w.put(x, y - 1, z, "minecraft:water", 3)
                        continue
                    fs = floor_state(cls)
                    w.put(x, y - 1, z, fs if fs else "minecraft:grass_block", 2)
                    if cls == "carpet":
                        w.put(x, y, z, "minecraft:white_carpet", 2)
                    elif cls == "snow":
                        w.put(x, y, z, "minecraft:snow[layers=2]", 2)
                    if nxt > y:
                        # the step up into the next column
                        if style == "slab" and (i == 0 or heights[i - 1] == y):
                            w.put(x, y, z, "minecraft:stone_slab[type=bottom]", 3)
                        elif style == "stairs":
                            w.put(x, y, z, "minecraft:oak_stairs[facing=" + facing(ddx, ddz) + ",half=bottom]", 3)
                    for yy in range(3):
                        w.air.add((x, y + yy + (1 if nxt > y and style != "block" else 0), z))
                if i in water_at:
                    # pool walls on both sides
                    for (sx, sz) in ((sides[0][0] * 2, sides[0][1] * 2), (sides[-1][0] * 2, sides[-1][1] * 2)) if width > 1 else (
                            ((1, 0), (-1, 0)) if abs(ddz) > abs(ddx) else ((0, 1), (0, -1))):
                        for yy in range(y - 3, y):
                            w.put(cx + sx, yy, cz + sz, "minecraft:stone", 3)
                    continue
                out = [(1, 0), (-1, 0)] if abs(ddz) > abs(ddx) else [(0, 1), (0, -1)]
                if width > 1:
                    out = [(o[0] * 2, o[1] * 2) for o in out]
                if cls == "fence_lane":
                    for (ox, oz) in out:
                        w.put(cx + ox, y, cz + oz, "minecraft:oak_fence", 2)
                if cls in ("tunnel", "low_tunnel"):
                    ceil = 3 if cls == "tunnel" else 2
                    for (sx, sz) in sides:
                        w.put(cx + sx, y + ceil, cz + sz, "minecraft:cobblestone", 1)
                if i == door_at:
                    f = facing(ddx, ddz)
                    for (ox, oz) in out:
                        for yy in range(3):
                            w.put(cx + ox, y + yy, cz + oz, "minecraft:oak_planks", 2)
                    w.put(cx, y + 2, cz, "minecraft:oak_planks", 2)
                    w.put(cx, y, cz, "minecraft:oak_door[facing=%s,half=lower,hinge=left,open=true]" % f, 5)
                    w.put(cx, y + 1, cz, "minecraft:oak_door[facing=%s,half=upper,hinge=left,open=true]" % f, 5)
                if cls == "crowd" and i == n // 2:
                    crowds.append([cx + 0.5, y, cz + 0.5])

    # souls: a ledge under each head, a strip of ground from the node toward it
    for s in souls:
        hx, hy, hz = s["head"]
        nd = nodes[s["node"]]
        for (cx, cz) in columns(nd["x"], nd["z"], hx, hz)[:-1]:
            w.put(cx, nd["y"] - 1, cz, "minecraft:grass_block", 1)
        w.put(hx, hy - 1, hz, "minecraft:mossy_cobblestone", 7)
        w.air.add((hx, hy, hz))
        w.air.add((hx, hy + 1, hz))

    # headroom wins over everything but ladders (a corridor crossing under another one's floor gets a hole)
    for c in w.air:
        st = w.get(*c)
        if st is not None and not st.startswith("minecraft:ladder") and not st.startswith("minecraft:oak_door") \
                and "carpet" not in st and "snow" not in st and "slab" not in st and "stairs" not in st \
                and "water" not in st:
            del w.b[c]

    attached = ("ladder", "door", "carpet", "snow", "fence", "water")
    first = [(p, s) for p, (s, _) in w.b.items() if not any(a in s for a in attached)]
    second = [(p, s) for p, (s, _) in w.b.items() if any(a in s for a in attached)]
    # door halves: lower then upper
    second.sort(key=lambda e: (0 if "water" not in e[1] else 1, e[0][1]))
    lines = ["%d %d %d %s" % (p[0], p[1], p[2], s) for p, s in first + second]

    # spawn: the node nearest the island's middle that is a plain pad
    mx = sum(v["x"] for v in nodes.values()) / len(nodes)
    mz = sum(v["z"] for v in nodes.values()) / len(nodes)
    spawn_k = min(nodes, key=lambda k: (nodes[k]["x"] - mx) ** 2 + (nodes[k]["z"] - mz) ** 2)
    sp = nodes[spawn_k]
    graph = {}
    for k, v in nodes.items():
        o = {"Position": "%d.0:%d.0:%d.0" % (v["x"], v["y"], v["z"]), "Neighbours": v["nb"]}
        if v["tags"]:
            o["Tags"] = v["tags"]
        if v["name"]:
            o["Name"] = v["name"]
        graph[k] = o
    xs = [p[0] for p in w.b]
    zs = [p[2] for p in w.b]
    ys = [p[1] for p in w.b]
    meta = {"island": name, "yOffset": dy, "graph": graph, "souls": souls,
            "spawn": [sp["x"] + 0.5, sp["y"], sp["z"] + 0.5], "crowds": crowds,
            "bounds": [min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)], "edges": log}
    os.makedirs(OUT, exist_ok=True)
    with gzip.open(os.path.join(OUT, name + ".blocks.gz"), "wt", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(OUT, name + ".meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    counts = {}
    for e in log:
        counts[e["class"]] = counts.get(e["class"], 0) + 1
    print(name, "nodes", len(nodes), "souls", len(souls), "blocks", len(lines), "crowds", len(crowds), counts)


if __name__ == "__main__":
    for isl in ISLANDS:
        build(*isl)
