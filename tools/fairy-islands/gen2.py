"""Terrain v2 for the fairy-island stand-ins (doc-fairy-1101, 2026-11-02).

v1 (gen.py) laid one corridor per graph edge and then patched the overlaps; dense graph clusters became a jumble of
crossing ramps that no player could walk, so most misses on the island runs were the terrain's, not the walker's.
v2 builds GROUND instead: every column within 2 blocks of an edge's centerline gets a surface at the height of the
nearest centerline point, so the paths of a cluster merge into one plaza and a slope is a slope. Different levels
(bridges, upper ledges, caves) stay apart only where they really are 3+ blocks apart vertically; a lower path keeps 3
blocks of headroom and the upper ground over it is cut back. Edges steeper than 1:1 keep v1's ladder cliffs.
Movement classes are drawn per edge from the same hash as v1, but laid onto that ground: half-slab step rims, stairs,
fence lanes, an open door in a wall across the path, water pools, low tunnels, carpet/snow/path/soul-sand surfaces,
villager crowds. Soul heads get NEU's coordinate and a block under them.

    python -I gen2.py <tools/fairy-islands dir> <output dir>
"""
import gzip
import hashlib
import json
import math
import os
import sys
from collections import defaultdict

sys.path.insert(0, sys.argv[1])
import gen as v1  # noqa: E402  (load(), ISLANDS, columns())

OUT = sys.argv[2]
RADIUS = 2.4
FLAT = ["plain", "plain", "plain", "fence_lane", "door", "water", "tunnel", "low_tunnel", "carpet", "snow", "path",
        "soul_sand", "crowd"]
RAMP = ["block", "block", "slab", "stairs"]
BACK = {"east": (-1, 0), "west": (1, 0), "south": (0, -1), "north": (0, 1)}


def h(*parts):
    return int(hashlib.md5("|".join(map(str, parts)).encode()).hexdigest()[:8], 16)


def facing(dx, dz):
    if abs(dx) >= abs(dz):
        return "east" if dx > 0 else "west"
    return "south" if dz > 0 else "north"


def build(name, file, key, dy, box):
    nodes, souls = v1.load(name, file, key, box)
    for v in nodes.values():
        v["y"] += dy
    for s in souls:
        s["head"][1] += dy

    blocks = {}  # (x,y,z) -> (state, prio)
    air = set()
    shaft = set()

    def put(x, y, z, st, prio):
        cur = blocks.get((x, y, z))
        if cur is None or prio >= cur[1]:
            blocks[(x, y, z)] = (st, prio)

    # 1. centerline samples of every walkable edge: column -> [(level, dist, edgeclass, edgekey)]
    near = defaultdict(list)
    edges = []
    ladders = []
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
            if rise >= 2 and rise > hd - 0.5:
                ladders.append((lo, hi))
                cls = "ladder"
            elif rise == 0 or hd < 3:
                cls = FLAT[seed % len(FLAT)]
                if rise != 0 and cls in ("low_tunnel", "door", "water"):
                    cls = "plain"
            else:
                cls = "ramp_" + RAMP[seed % len(RAMP)]
            edges.append({"a": a, "b": b, "class": cls, "len": round(hd, 1), "rise": rise})
            if cls == "ladder":
                continue
            n = max(1, int(math.ceil(hd / 0.25)))
            for i in range(n + 1):
                t = i / n
                x = lo["x"] + 0.5 + (hi["x"] - lo["x"]) * t
                z = lo["z"] + 0.5 + (hi["z"] - lo["z"]) * t
                y = lo["y"] + rise * t
                for ox in range(-3, 4):
                    for oz in range(-3, 4):
                        cx, cz = int(math.floor(x)) + ox, int(math.floor(z)) + oz
                        d = math.hypot(cx + 0.5 - x, cz + 0.5 - z)
                        if d <= RADIUS:
                            near[(cx, cz)].append((y, d, cls, (a, b), t))
    # node pads
    for k, v in nodes.items():
        for ox in range(-2, 3):
            for oz in range(-2, 3):
                d = math.hypot(ox, oz)
                if d <= RADIUS:
                    near[(v["x"] + ox, v["z"] + oz)].append((v["y"], d, "pad", (k, k), 0.0))

    # 2. levels per column: the nearest sample decides; samples 3+ blocks away vertically are another level
    surface = {}  # (x, z) -> list of (level, cls, dist, edgekey)
    for col, samples in near.items():
        samples.sort(key=lambda s: s[1])
        levels = []
        used = [False] * len(samples)
        for i, (y, d, cls, ek, t) in enumerate(samples):
            if used[i]:
                continue
            # one level: every sample within 2.5 blocks of this one's height, weighted by closeness (a smooth surface,
            # not the nearest edge's height column by column)
            wsum = 0.0
            ysum = 0.0
            for j, (y2, d2, _, _, _) in enumerate(samples):
                if not used[j] and abs(y2 - y) < 2.5:
                    used[j] = True
                    w = 1.0 / (d2 + 0.35) ** 2
                    wsum += w
                    ysum += w * y2
            lv = int(math.floor(ysum / wsum + 0.5))
            if all(abs(lv - L[0]) >= 3 for L in levels):
                levels.append((lv, cls, d, ek))
        surface[col] = levels

    # 3. ground and headroom; an upper level whose ground cuts a lower level's headroom is cut back (a hole / overhang)
    walk = {}  # (x, level, z) -> cls
    for (x, z), levels in surface.items():
        levels.sort()
        for i, (lv, cls, d, ek) in enumerate(levels):
            below = levels[i - 1][0] if i > 0 else None
            if below is not None and lv - 1 <= below + 2:
                continue  # its ground would sit in the lower path's headroom
            walk[(x, lv, z)] = cls
            top = "minecraft:grass_block"
            if cls == "path":
                top = "minecraft:dirt_path"
            elif cls == "soul_sand":
                top = "minecraft:soul_sand"
            put(x, lv - 1, z, top, 2)
            for k in range(2, 5):
                put(x, lv - k, z, "minecraft:dirt", 1)
            for k in range(3):
                air.add((x, lv + k, z))
            if cls == "carpet" and d < 1.6:
                put(x, lv, z, "minecraft:white_carpet", 3)
            elif cls == "snow" and d < 1.6:
                put(x, lv, z, "minecraft:snow[layers=2]", 3)

    # 4. ramp styles: a slab in the lower column wherever a ramp edge's centerline steps up by one
    for (x, lv, z), cls in list(walk.items()):
        if not cls.startswith("ramp_") or cls == "ramp_block":
            continue
        for (dx, dz) in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if walk.get((x + dx, lv + 1, z + dz), "").startswith("ramp_") and (x, lv + 1, z) not in walk:
                if cls == "ramp_slab":
                    put(x, lv, z, "minecraft:stone_slab[type=bottom]", 3)
                else:
                    put(x, lv, z, "minecraft:oak_stairs[facing=%s,half=bottom]" % facing(dx, dz), 3)
                break

    # 5. per-edge features on the centerline
    crowds = []
    pools = []
    for e in edges:
        cls = e["class"]
        if cls in ("ladder", "plain", "carpet", "snow", "path", "soul_sand") or cls.startswith("ramp_"):
            continue
        va, vb = nodes[e["a"]], nodes[e["b"]]
        cols = v1.columns(va["x"], va["z"], vb["x"], vb["z"])
        if len(cols) < 6:
            continue
        ddx, ddz = vb["x"] - va["x"], vb["z"] - va["z"]
        side = [(0, 1), (0, -1)] if abs(ddx) >= abs(ddz) else [(1, 0), (-1, 0)]
        mid = len(cols) // 2
        lv = int(math.floor(va["y"] + (vb["y"] - va["y"]) * mid / (len(cols) - 1) + 0.5))
        if cls == "fence_lane":
            for (cx, cz) in cols[2:-2]:
                for (sx, sz) in side:
                    put(cx + sx * 2, lv, cz + sz * 2, "minecraft:oak_fence", 4)
        elif cls == "door":
            cx, cz = cols[mid]
            f = facing(ddx, ddz)
            for k in range(-3, 4):
                if k == 0:
                    continue
                for yy in range(3):
                    put(cx + side[0][0] * k, lv + yy, cz + side[0][1] * k, "minecraft:oak_planks", 4)
            put(cx, lv + 2, cz, "minecraft:oak_planks", 4)
            put(cx, lv, cz, "minecraft:oak_door[facing=%s,half=lower,hinge=left,open=true]" % f, 5)
            put(cx, lv + 1, cz, "minecraft:oak_door[facing=%s,half=upper,hinge=left,open=true]" % f, 5)
        elif cls == "water":
            pool = []
            for (cx, cz) in cols[mid - 1:mid + 2]:
                for k in range(-2, 3):
                    x, z = cx + side[0][0] * k, cz + side[0][1] * k
                    put(x, lv - 3, z, "minecraft:stone", 3)
                    put(x, lv - 2, z, "minecraft:water", 6)
                    put(x, lv - 1, z, "minecraft:water", 6)
                    pool.append((x, lv, z))
            pools.append(pool)
        elif cls in ("tunnel", "low_tunnel"):
            ceil = 3 if cls == "tunnel" else 2
            for (cx, cz) in cols[1:-1]:
                for k in range(-2, 3):
                    put(cx + side[0][0] * k, lv + ceil, cz + side[0][1] * k, "minecraft:cobblestone", 1)
        elif cls == "crowd":
            cx, cz = cols[mid]
            crowds.append([cx + 0.5, lv, cz + 0.5])

    # 6. ladder cliffs (v1's construction)
    for lo, hi in ladders:
        f = facing(hi["x"] - lo["x"], hi["z"] - lo["z"])
        stepx, stepz = {"east": (1, 0), "west": (-1, 0), "south": (0, 1), "north": (0, -1)}[f]
        lx, lz = hi["x"] - stepx, hi["z"] - stepz
        for (cx, cz) in v1.columns(lo["x"], lo["z"], lx, lz):
            for k in range(-1, 2):
                x, z = cx + (k if stepz else 0), cz + (k if stepx else 0)
                put(x, lo["y"] - 1, z, "minecraft:stone", 2)
                for yy in range(3):
                    air.add((x, lo["y"] + yy, z))
        # The smoothed ground at the top can sit a block or two above the node: the cliff and the ladder go up to it, or
        # the ladder ends below the ledge it should lead onto.
        top = hi["y"]
        for ox in (-1, 0, 1):
            for oz in (-1, 0, 1):
                for yy in range(hi["y"] + 2, hi["y"] - 2, -1):
                    st = blocks.get((hi["x"] + ox, yy, hi["z"] + oz))
                    if st is not None and any(k in st[0] for k in ("grass", "dirt", "stone", "soul_sand")):
                        top = max(top, yy + 1)
                        break
        for yy in range(lo["y"] - 1, top):
            put(hi["x"], yy, hi["z"], "minecraft:stone", 8)
        for yy in range(lo["y"], top):
            put(lx, yy, lz, "minecraft:ladder[facing=%s]" % v1.OPP[f], 9)
        for yy in range(lo["y"], top + 3):
            shaft.add((lx, yy, lz))
        for yy in range(top, top + 3):
            shaft.add((hi["x"], yy, hi["z"]))

    # 7. souls
    for s in souls:
        hx, hy, hz = s["head"]
        put(hx, hy - 1, hz, "minecraft:mossy_cobblestone", 9)
        shaft.add((hx, hy, hz))
        shaft.add((hx, hy + 1, hz))

    # 8. headroom: walking cells keep air above them (features excepted), shafts are open
    keep = ("ladder", "oak_door", "water", "carpet", "snow", "slab", "stairs", "fence", "oak_planks")
    for c in air:
        st = blocks.get(c)
        if st is None:
            continue
        if any(k in st[0] for k in keep) or st[1] >= 8:
            continue
        del blocks[c]
    for c in shaft:
        st = blocks.get(c)
        if st is not None and "ladder" not in st[0]:
            del blocks[c]
    # Still water only: a pool any of whose water has open air beside or under it would spill and keep flowing (Bayou's
    # v2 pool spilled past a ladder and the walker swam in the current); such a pool becomes ground instead.
    def solid_or_water(c):
        st = blocks.get(c)
        return st is not None and "ladder" not in st[0] and "door" not in st[0] and "fence" not in st[0] \
            and "carpet" not in st[0] and "snow" not in st[0]

    spilled = 0
    for pool in pools:
        cells = set()
        for (x, lv, z) in pool:
            cells.add((x, lv - 2, z))
            cells.add((x, lv - 1, z))
        # a stone rim wherever the pool's water has open air beside or under it (ground level, so it stays walkable)
        for (x, y, z) in list(cells):
            for (dx, dy, dz) in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0)):
                c = (x + dx, y + dy, z + dz)
                if c not in cells and c not in blocks:
                    blocks[c] = ('minecraft:stone', 6)
        leaks = False
        for (x, y, z) in cells:
            if (x, y, z) not in blocks or "water" not in blocks[(x, y, z)][0]:
                leaks = True
                break
            for (dx, dy, dz) in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, -1, 0)):
                if not solid_or_water((x + dx, y + dy, z + dz)):
                    leaks = True
                    break
            if leaks:
                break
        if leaks:
            spilled += 1
            for (x, y, z) in cells:
                if (x, y, z) in blocks and "water" in blocks[(x, y, z)][0]:
                    blocks[(x, y, z)] = ("minecraft:grass_block" if (x, y + 1, z) not in cells else "minecraft:dirt", 6)
    # every ladder on a solid backing block
    for (x, y, z), (st, _) in list(blocks.items()):
        if "ladder" in st:
            bx, bz = BACK[st.split("facing=")[1].rstrip("]")]
            sup = blocks.get((x + bx, y, z + bz))
            if sup is not None and "ladder" in sup[0]:
                continue  # backed by another ladder edge's ladder: never overwrite that one (it emptied its shaft)
            if sup is None or not any(k in sup[0] for k in ("stone", "dirt", "grass", "planks", "cobble"))                     or "slab" in sup[0] or "stairs" in sup[0]:
                blocks[(x + bx, y, z + bz)] = ("minecraft:stone", 9)

    attached = ("ladder", "door", "carpet", "snow", "fence", "water")
    first = [(p, st) for p, (st, _) in blocks.items() if not any(a in st for a in attached)]
    second = sorted([(p, st) for p, (st, _) in blocks.items() if any(a in st for a in attached)],
                    key=lambda e: (1 if "water" in e[1] else 0, e[0][1]))
    lines = ["%d %d %d %s" % (p[0], p[1], p[2], st) for p, st in first + second]

    mx = sum(v["x"] for v in nodes.values()) / len(nodes)
    mz = sum(v["z"] for v in nodes.values()) / len(nodes)
    # spawn on a node whose column has a walking cell at its own level
    cands = sorted(nodes, key=lambda k: (nodes[k]["x"] - mx) ** 2 + (nodes[k]["z"] - mz) ** 2)
    spawn_k = next(k for k in cands if (nodes[k]["x"], nodes[k]["y"], nodes[k]["z"]) in walk)
    sp = nodes[spawn_k]
    graph = {}
    for k, v in nodes.items():
        o = {"Position": "%d.0:%d.0:%d.0" % (v["x"], v["y"], v["z"]), "Neighbours": v["nb"]}
        if v["tags"]:
            o["Tags"] = v["tags"]
        if v["name"]:
            o["Name"] = v["name"]
        graph[k] = o
    xs = [p[0] for p in blocks]
    ys = [p[1] for p in blocks]
    zs = [p[2] for p in blocks]
    meta = {"island": name, "terrain": "v2", "yOffset": dy, "graph": graph, "souls": souls,
            "spawn": [sp["x"] + 0.5, sp["y"], sp["z"] + 0.5], "crowds": crowds,
            "bounds": [min(xs), min(ys), min(zs), max(xs), max(ys), max(zs)], "edges": edges}
    os.makedirs(OUT, exist_ok=True)
    with gzip.open(os.path.join(OUT, name + ".blocks.gz"), "wt", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.path.join(OUT, name + ".meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    counts = defaultdict(int)
    for e in edges:
        counts[e["class"]] += 1
    print(name, "nodes", len(nodes), "souls", len(souls), "blocks", len(lines), "walk cells", len(walk), "pools",
          len(pools), "of them filled in", spilled)


if __name__ == "__main__":
    for isl in v1.ISLANDS:
        build(*isl)
