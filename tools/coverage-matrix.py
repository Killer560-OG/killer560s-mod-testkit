#!/usr/bin/env python3
"""Per mod package: how many patterns it has, how many a fixture cites, which work package owns it, and whether a
run touched it.

    python tools/coverage-matrix.py                         # this checkout's fixtures, last report if present
    python tools/coverage-matrix.py --worktrees             # also every fixture in every sibling git worktree
    python tools/coverage-matrix.py --report build/testkit-report --out build/coverage-matrix.md

Inputs
    --catalog   src/gametest/resources/testkit-logic/pattern-catalog.json (tools/extract-patterns.py writes it)
    fixtures    src/gametest/resources/testkit-fixtures/**.json (SCHEMA.md); --worktrees adds the same folder from
                every worktree `git worktree list` names, so WP branches that have not merged yet are counted too
    --report    a run's build/testkit-report: coverage.md ("touched" per package) and summary.json (case verdicts)
    --mod-source / -PmodSource   the mod checkout, to list packages that have no patterns at all

Output: a markdown table (and a .json twin) - package, owner WP, patterns (static/element/other), cited by a
fixture (matches / mustNotMatch), fixtures whose source is in the package, touched by the run, cases. "Cited" means
a fixture names the pattern, never that the feature works; the run's verdicts say that.
"""

import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CATALOG = os.path.join(HERE, 'src', 'gametest', 'resources', 'testkit-logic', 'pattern-catalog.json')
FIXTURES = os.path.join('src', 'gametest', 'resources', 'testkit-fixtures')
HUB = 'src/main/java/com/killer560/hub'

# Owner work package per mod package, from TESTKIT-COVERAGE-PLAN.md section 2 ("Covers ..." lists).
OWNERS = {
    'WP2 hx': 'secrets scorecalc dungeoninfo runstats runsummary rngmeter blessings dungeonalerts dungeonqueue autokick '
              'chatcommands partycommands commandshortcuts commandkeybinds autojoinskyblock autocorrect emotes cringe '
              'automeow leapmessage interop teammates players social namechanger lagdisplay playerstats '
              'abilitycooldown quiver scoreboard mining livemap doorkeys architect puzzlesolvers',
    'WP3 menu': 'terminals experiments croesus autoclosechest leapmenu fastleap autosell invsort itemprotect slotbinds '
                'itemrarity enchantcolors revertmasterstars armourdye helditem tooltipscroll inventorytheme '
                'inventoryhud inventorysearch storageoverlay storagesearch petwheel loadoutkeybinds dungeonclass '
                'partyfinder auction bazaarflip cheatutils',
    'WP4 ui': 'gui mainmenu termism profiles configversion hud',
    'WP6 dsim': 'roomsim autoroutes etherwarp etherwarpoverlay dungeonextras secrettrigger secretwaypoints '
                'doorhelpers dungeonbreaker mobesp witherdoors pathfinding routes',
    'WP7 boss': 'ap3 terminalaura terminaltrigger leveraura simonsays arrowalign i4sensors p3nav p4platform goldor '
                'splittimers ticktimers maxor witherdragons autodebuff ragaxe maskinvincibility melody boss thorn '
                'f7spots posmsg diorite bloodcamp',
}
OWNER_OF = {pkg: wp for wp, pkgs in OWNERS.items() for pkg in pkgs.split()}


def pkg_of(cls):
    """'secrets.DungeonState' -> 'secrets'; '(root)' for classes directly in com.killer560.hub."""
    return cls.split('.', 1)[0] if '.' in cls else '(root)'


def worktrees():
    try:
        out = subprocess.run(['git', '-C', HERE, 'worktree', 'list', '--porcelain'], capture_output=True, text=True,
                             timeout=20).stdout
    except (OSError, subprocess.SubprocessError):
        return []
    return [line[len('worktree '):].strip() for line in out.splitlines() if line.startswith('worktree ')]


def load_fixtures(roots):
    """[(fixture, file, root)], deduplicated by id (first root wins), plus a list of problems reading them."""
    seen, out, problems = {}, [], []
    for root in roots:
        base = os.path.join(root, FIXTURES)
        if not os.path.isdir(base):
            continue
        for d, _, files in os.walk(base):
            if os.path.normcase(os.path.abspath(d)) == os.path.normcase(os.path.abspath(base)):
                continue  # top-level files (prism-accounts-empty.json) are not fixtures
            for f in sorted(files):
                if not f.endswith('.json'):
                    continue
                path = os.path.join(d, f)
                try:
                    with open(path, encoding='utf-8') as fh:
                        arr = json.load(fh)
                except (OSError, ValueError) as e:
                    problems.append(f'{path}: {e}')
                    continue
                for fx in arr if isinstance(arr, list) else []:
                    fid = fx.get('id')
                    if fid in seen:
                        if seen[fid] != json.dumps(fx, sort_keys=True):
                            problems.append(f'{fid}: differs between worktrees ({path})')
                        continue
                    seen[fid] = json.dumps(fx, sort_keys=True)
                    out.append((fx, path, root))
    return out, problems


def touched_from_report(report):
    touched, cases = {}, []
    cov = os.path.join(report, 'coverage.md')
    if os.path.isfile(cov):
        with open(cov, encoding='utf-8') as fh:
            for line in fh:
                m = re.match(r'^\| ([\w()]+) \| (yes|no) \| (.*) \|$', line.strip())
                if m and '.' not in m.group(1):
                    touched[m.group(1)] = m.group(2) == 'yes'
    summ = os.path.join(report, 'summary.json')
    if os.path.isfile(summ):
        with open(summ, encoding='utf-8') as fh:
            cases = json.load(fh).get('cases', [])
    return touched, cases


def main(argv):
    pre = []
    for a in argv:
        pre += ['--mod-source', a.split('=', 1)[1]] if a.startswith('-PmodSource=') else [a]
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--catalog', default=CATALOG)
    ap.add_argument('--worktrees', action='store_true')
    ap.add_argument('--report', default=os.path.join(HERE, 'build', 'testkit-report'))
    ap.add_argument('--mod-source', default=os.environ.get('TESTKIT_MOD_SOURCE', 'C:/Users/Hunter/killer560s-mod'))
    ap.add_argument('--out', default=os.path.join(HERE, 'build', 'coverage-matrix.md'))
    args = ap.parse_args(pre)

    with open(args.catalog, encoding='utf-8') as fh:
        catalog = json.load(fh)
    roots = [HERE] + ([w for w in worktrees() if os.path.normcase(w) != os.path.normcase(HERE)]
                      if args.worktrees else [])
    fixtures, problems = load_fixtures(roots)
    touched, cases = touched_from_report(args.report)

    packages = set()
    hub = os.path.join(args.mod_source, *HUB.split('/'))
    if os.path.isdir(hub):
        for name in os.listdir(hub):
            if os.path.isdir(os.path.join(hub, name)) and name != 'mixin':
                packages.add(name)
    rows = {}

    def row(pkg):
        return rows.setdefault(pkg, {'package': pkg, 'owner': OWNER_OF.get(pkg, '-'), 'field': 0, 'element': 0,
                                     'other': 0, 'cited': 0, 'citedIds': [], 'fixtures': 0,
                                     'touched': touched.get(pkg), 'cases': []})

    for pkg in packages:
        row(pkg)
    cited = {}
    for fx, path, root in fixtures:
        for ref in (fx.get('matches') or []) + (fx.get('mustNotMatch') or []):
            cited.setdefault(ref, set()).add(fx.get('id'))
        src = (fx.get('source') or {}).get('file') or ''
        if src.startswith(HUB + '/'):
            rest = src[len(HUB) + 1:]
            row(rest.split('/', 1)[0] if '/' in rest else '(root)')['fixtures'] += 1
    for e in catalog['patterns']:
        r = row(pkg_of(e['cls']))
        r[e['kind'] if e['kind'] in ('field', 'element') else 'other'] += 1
        if e['kind'] == 'field' and e['id'] in cited:
            r['cited'] += 1
            r['citedIds'].append(e['id'])
    for c in cases:
        m = re.match(r'^(\d+)-(\w+)-', c.get('name', ''))
        # cases do not name packages; the report's coverage.md is what ties a run to a package
    statics = sum(1 for e in catalog['patterns'] if e['kind'] == 'field')
    cited_total = sum(r['cited'] for r in rows.values())

    lines = ['# coverage matrix', '',
             f"- catalog: {catalog.get('count')} Pattern.compile sites at mod {str(catalog.get('modGitSha'))[:7]}"
             f" ({catalog.get('generatedAt')})",
             f"- fixtures: {len(fixtures)} from {len(roots)} checkout(s): " + ', '.join(roots),
             f"- static Pattern fields cited by a fixture: {cited_total} / {statics}"
             f" ({100.0 * cited_total / max(1, statics):.1f}%)",
             f"- report: {args.report if touched else '(none read)'}",
             '', '"cited" = a fixture lists the pattern in matches/mustNotMatch. It proves agreement with the regex, '
             'not that Hypixel sends the line or that the feature works.', '',
             '| package | owner | static patterns | cited | % | other patterns | fixtures | touched by run |',
             '|---|---|---|---|---|---|---|---|']
    for pkg in sorted(rows):
        r = rows[pkg]
        pct = f"{100.0 * r['cited'] / r['field']:.0f}" if r['field'] else '-'
        t = '-' if r['touched'] is None else ('yes' if r['touched'] else 'no')
        lines.append(f"| {pkg} | {r['owner']} | {r['field']} | {r['cited']} | {pct} | {r['element'] + r['other']} "
                     f"| {r['fixtures']} | {t} |")
    if problems:
        lines += ['', '## problems reading fixtures', ''] + [f'- {p}' for p in problems]
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('\n'.join(lines) + '\n')
    with open(os.path.splitext(args.out)[0] + '.json', 'w', encoding='utf-8', newline='\n') as fh:
        json.dump({'staticPatterns': statics, 'cited': cited_total, 'fixtures': len(fixtures), 'roots': roots,
                   'problems': problems, 'packages': [rows[k] for k in sorted(rows)]}, fh, indent=1)
    print(f'[coverage-matrix] {len(rows)} package(s), {cited_total}/{statics} static patterns cited by '
          f'{len(fixtures)} fixture(s) -> {args.out}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
