#!/usr/bin/env python3
"""Catalogue every Pattern.compile in killer560s-mod into a JSON file the LogicSuite reads.

    python tools/extract-patterns.py                       (modSource from testkit.properties / TESTKIT_MOD_SOURCE)
    python tools/extract-patterns.py -PmodSource=<killer560s-mod checkout>
    python tools/extract-patterns.py --mod-source <killer560s-mod checkout> --out <file> [--check]

Default output: src/gametest/resources/testkit-logic/pattern-catalog.json (outside testkit-fixtures/, whose every
.json is read as a fixture array).

One entry per Pattern.compile call site:

    id       "<Class>#<FIELD>" for a static Pattern field, "<Class>#<FIELD>[i]" for the i-th compile in a static
             field's initializer (List.of(...), arrays), "<Class>@<line>" for anything else
    cls      class name relative to com.killer560.hub, nested classes with '$' (what Class.forName wants)
    field    the field name, or null
    index    position inside a multi-pattern static initializer, or null
    file     path relative to the mod repo root (what a fixture's source.file cites)
    line     1-based line of the Pattern.compile token
    regex    the regex text when it is built only from string literals and resolvable String constants, else null
    flags    the flags argument as written in the source ("Pattern.CASE_INSENSITIVE"), or null
    kind     "field"      a static (final) Pattern field: reflection (Mod.pattern) reaches it, fixtures can cite it
             "element"    one of several compiles in a static field's initializer: reachable only through the field
             "instance"   an instance field
             "local"      a local variable or an inline argument
             "dynamic"    the regex is computed at run time (config value, constructor argument)
    expr     the first argument as written (trimmed, one line), for a reader

--check exits 1 when an entry's regex is null for a kind of "field" (a reader expected to resolve it could not), so
a change to the extractor that loses constants is noticed. It never touches the mod repo.
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys

HUB = "src/main/java/com/killer560/hub"
DEFAULT_OUT = os.path.join("src", "gametest", "resources", "testkit-logic", "pattern-catalog.json")


# ---- lexing ---------------------------------------------------------------------------------------------------

def mask_comments(src):
    """Return src with comments replaced by spaces (newlines kept), and a parallel list of string-literal spans."""
    out = list(src)
    strings = []  # (start, end_exclusive) of each string literal INCLUDING quotes (text blocks too)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = ' '
            i = j
        elif c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if out[k] != '\n':
                    out[k] = ' '
            i = j
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3)
            while j > 0 and src[j - 1] == '\\':
                j = src.find('"""', j + 1)
            j = n if j < 0 else j + 3
            strings.append((i, j))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            strings.append((i, j + 1))
            i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            i = j + 1
        else:
            i += 1
    return ''.join(out), strings


ESC = {'n': '\n', 't': '\t', 'r': '\r', 'b': '\b', 'f': '\f', 's': ' ', '0': '\0', '"': '"', "'": "'", '\\': '\\'}


def unescape_java(body):
    """Decode a Java string literal body (between the quotes)."""
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c == '\\' and i + 1 < len(body):
            d = body[i + 1]
            if d == 'u':
                j = i + 1
                while j < len(body) and body[j] == 'u':
                    j += 1
                out.append(chr(int(body[j:j + 4], 16)))
                i = j + 4
                continue
            if d in '01234567' and not (d == '0' and (i + 2 >= len(body) or body[i + 2] not in '01234567')):
                m = re.match(r'[0-7]{1,3}', body[i + 1:])
                out.append(chr(int(m.group(0), 8)))
                i += 1 + len(m.group(0))
                continue
            out.append(ESC.get(d, d))
            i += 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def split_top(expr, sep):
    """Split on sep at paren/bracket/brace depth 0, outside string and char literals."""
    parts, depth, cur, i = [], 0, [], 0
    while i < len(expr):
        c = expr[i]
        if c == '"':
            j = i + 1
            while j < len(expr) and expr[j] != '"':
                j += 2 if expr[j] == '\\' else 1
            cur.append(expr[i:j + 1])
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < len(expr) and expr[j] != "'":
                j += 2 if expr[j] == '\\' else 1
            cur.append(expr[i:j + 1])
            i = j + 1
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        if c == sep and depth == 0:
            parts.append(''.join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    parts.append(''.join(cur))
    return parts


def matching_paren(text, open_idx):
    """Index of the ')' matching text[open_idx] == '(' (text is comment-masked; strings are skipped)."""
    depth, i = 0, open_idx
    while i < len(text):
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = j + 3
                continue
            j = i + 1
            while j < len(text) and text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < len(text) and text[j] != "'":
                j += 2 if text[j] == '\\' else 1
            i = j + 1
            continue
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


# ---- evaluation of constant string expressions ----------------------------------------------------------------

class Unresolved(Exception):
    pass


def eval_str(expr, consts, cls_simple, global_consts):
    """Evaluate a Java String expression built from literals, constants, '+' and Pattern.quote()."""
    expr = expr.strip()
    while expr.startswith('(') and matching_paren(expr, 0) == len(expr) - 1:
        expr = expr[1:-1].strip()
    parts = split_top(expr, '+')
    if len(parts) > 1:
        return ''.join(eval_str(p, consts, cls_simple, global_consts) for p in parts)
    e = expr
    if e.startswith('"""'):
        body = e[3:-3]
        body = body.split('\n', 1)[1] if '\n' in body else body
        lines = body.split('\n')
        indent = min((len(l) - len(l.lstrip()) for l in lines if l.strip()), default=0)
        return unescape_java('\n'.join(l[indent:] for l in lines))
    if e.startswith('"') and e.endswith('"'):
        return unescape_java(e[1:-1])
    if e.startswith("'") and e.endswith("'"):
        return unescape_java(e[1:-1])
    m = re.fullmatch(r'Pattern\.quote\((.*)\)', e, re.S)
    if m:
        return '\\Q' + eval_str(m.group(1), consts, cls_simple, global_consts) + '\\E'
    m = re.fullmatch(r'String\.valueOf\((.*)\)', e, re.S)
    if m:
        return eval_str(m.group(1), consts, cls_simple, global_consts)
    if re.fullmatch(r'-?\d+', e):
        return e
    if re.fullmatch(r'[A-Za-z_]\w*', e):
        if e in consts:
            return consts[e]
        raise Unresolved(e)
    m = re.fullmatch(r'([A-Z]\w*)\.([A-Za-z_]\w*)', e)
    if m and (m.group(1), m.group(2)) in global_consts:
        return global_consts[(m.group(1), m.group(2))]
    raise Unresolved(e)


STR_CONST = re.compile(r'\bstatic\s+final\s+(?:String|char)\s+([A-Za-z_]\w*)\s*=\s*', re.S)


def string_constants(masked, raw_consts_out):
    """name -> expression text for every static final String/char constant in a file."""
    for m in STR_CONST.finditer(masked):
        start = m.end()
        end = statement_end(masked, start)
        raw_consts_out[m.group(1)] = masked[start:end]


def statement_end(text, start):
    depth, i = 0, start
    while i < len(text):
        c = text[i]
        if c == '"':
            if text.startswith('"""', i):
                i = text.find('"""', i + 3) + 3
                continue
            j = i + 1
            while j < len(text) and text[j] != '"':
                j += 2 if text[j] == '\\' else 1
            i = j + 1
            continue
        if c in '([{':
            depth += 1
        elif c in ')]}':
            depth -= 1
        elif c == ';' and depth == 0:
            return i
        i += 1
    return len(text)


def resolve_constants(raw, cls_simple, global_consts):
    done = {}
    for _ in range(8):
        progress = False
        for name, expr in raw.items():
            if name in done:
                continue
            try:
                done[name] = eval_str(expr, done, cls_simple, global_consts)
                progress = True
            except (Unresolved, ValueError, IndexError):
                pass
        if not progress:
            break
    return done


# ---- class structure ------------------------------------------------------------------------------------------

TYPE_DECL = re.compile(r'\b(class|enum|record|interface)\s+([A-Z]\w*)')


def type_spans(masked):
    """[(start, end, name)] for every type body in the file."""
    spans = []
    for m in TYPE_DECL.finditer(masked):
        brace = masked.find('{', m.end())
        if brace < 0:
            continue
        depth, i = 0, brace
        while i < len(masked):
            c = masked[i]
            if c == '"':
                j = i + 1
                while j < len(masked) and masked[j] != '"':
                    j += 2 if masked[j] == '\\' else 1
                i = j + 1
                continue
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
                if depth == 0:
                    break
            i += 1
        spans.append((brace, i, m.group(2)))
    return spans


def class_at(spans, pos, top):
    chain = [s for s in spans if s[0] < pos < s[1]]
    chain.sort(key=lambda s: s[0])
    if not chain:
        return top
    return '$'.join(s[2] for s in chain)


def member_depth(masked, spans, pos):
    """Brace depth of pos relative to its innermost type body: 1 = member level, >1 = inside a method/initializer."""
    chain = [s for s in spans if s[0] < pos < s[1]]
    if not chain:
        return 0
    body_start = max(chain, key=lambda s: s[0])[0]
    depth, i = 0, body_start
    while i < pos:
        c = masked[i]
        if c == '"':
            j = i + 1
            while j < len(masked) and masked[j] != '"':
                j += 2 if masked[j] == '\\' else 1
            i = j + 1
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        i += 1
    return depth


def statement_start(masked, pos):
    """Start of the statement/declaration containing pos (after the previous ';', '{' or '}' at depth 0)."""
    depth, i = 0, pos - 1
    while i >= 0:
        c = masked[i]
        if c == '"':
            j = i - 1
            while j >= 0 and not (masked[j] == '"' and (j == 0 or masked[j - 1] != '\\')):
                j -= 1
            i = j - 1
            continue
        if c in ')]':
            depth += 1
        elif c in '([':
            depth -= 1
        elif c == '}':
            if depth <= 0:
                return i + 1
            depth += 1
        elif c == '{':
            if depth <= 0:
                # an array initializer "= {" or "[] {" belongs to the statement
                k = i - 1
                while k >= 0 and masked[k].isspace():
                    k -= 1
                if k >= 0 and masked[k] in '=]':
                    i -= 1
                    continue
                return i + 1
            depth -= 1
        elif c == ';' and depth <= 0:
            return i + 1
        i -= 1
    return 0


DECL = re.compile(r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*((?:(?:public|private|protected|static|final|volatile|transient)\s+)*)'
                  r'([\w.$]+(?:\s*<[^=;]*>)?(?:\s*\[\s*\])*)\s+([A-Za-z_]\w*)\s*=', re.S)


# ---- main -----------------------------------------------------------------------------------------------------

def git_sha(root):
    try:
        sha = subprocess.run(['git', '-C', root, 'rev-parse', 'HEAD'], capture_output=True, text=True, timeout=20)
        dirty = subprocess.run(['git', '-C', root, 'status', '--porcelain', '--', HUB], capture_output=True, text=True,
                               timeout=20)
        return sha.stdout.strip(), bool(dirty.stdout.strip())
    except (OSError, subprocess.SubprocessError):
        return None, None


def java_files(root):
    base = os.path.join(root, *HUB.split('/'))
    for d, _, files in os.walk(base):
        for f in sorted(files):
            if f.endswith('.java'):
                yield os.path.join(d, f)


def default_mod_source():
    """modSource as tools/testkit-config.ps1 resolves it: TESTKIT_MOD_SOURCE, testkit.local.properties (this checkout,
    then the main checkout of a worktree), testkit.properties, else killer560s-mod beside the main checkout."""
    if os.environ.get('TESTKIT_MOD_SOURCE'):
        return os.environ['TESTKIT_MOD_SOURCE']
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main = repo
    git = os.path.join(repo, '.git')
    if os.path.isfile(git):
        with open(git, encoding='utf-8') as fh:
            line = fh.readline().strip()
        if line.startswith('gitdir:'):
            gd = line[7:].strip()
            gd = gd if os.path.isabs(gd) else os.path.join(repo, gd)
            cd = os.path.join(gd, 'commondir')
            if os.path.isfile(cd):
                with open(cd, encoding='utf-8') as fh:
                    main = os.path.dirname(os.path.normpath(os.path.join(gd, fh.read().strip())))
    for f in (os.path.join(repo, 'testkit.local.properties'), os.path.join(main, 'testkit.local.properties'),
              os.path.join(repo, 'testkit.properties')):
        if os.path.isfile(f):
            with open(f, encoding='utf-8') as fh:
                for raw in fh:
                    t = raw.strip()
                    if t.startswith('modSource=') and t[len('modSource='):].strip():
                        v = t[len('modSource='):].strip()
                        return v if os.path.isabs(v) else os.path.join(main, v)
    return os.path.join(os.path.dirname(main), 'killer560s-mod')


def main(argv):
    pre = []
    for a in argv:
        if a.startswith('-PmodSource='):
            pre += ['--mod-source', a.split('=', 1)[1]]
        else:
            pre.append(a)
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument('--mod-source', default=default_mod_source())
    ap.add_argument('--out', default=None)
    ap.add_argument('--check', action='store_true')
    args = ap.parse_args(pre)
    root = os.path.abspath(args.mod_source)
    if not os.path.isdir(os.path.join(root, *HUB.split('/'))):
        print(f'[patterns] no {HUB} under {root}', file=sys.stderr)
        return 2
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    out = args.out or os.path.join(here, DEFAULT_OUT)

    files = []
    for path in java_files(root):
        with open(path, encoding='utf-8') as fh:
            src = fh.read()
        masked, _ = mask_comments(src)
        rel = os.path.relpath(path, root).replace('\\', '/')
        cls_rel = rel[len(HUB) + 1:-5].replace('/', '.')
        files.append((rel, cls_rel, src, masked))

    # Pass 1: every String constant in the mod, so Class.CONST references across files resolve.
    global_consts = {}
    per_file_raw = {}
    for rel, cls_rel, src, masked in files:
        raw = {}
        string_constants(masked, raw)
        per_file_raw[rel] = raw
    for _ in range(3):
        for rel, cls_rel, src, masked in files:
            simple = cls_rel.rsplit('.', 1)[-1]
            for k, v in resolve_constants(per_file_raw[rel], simple, global_consts).items():
                global_consts[(simple, k)] = v

    entries = []
    for rel, cls_rel, src, masked in files:
        simple = cls_rel.rsplit('.', 1)[-1]
        consts = resolve_constants(per_file_raw[rel], simple, global_consts)
        spans = type_spans(masked)
        per_stmt = {}
        for m in re.finditer(r'\b(?:java\.util\.regex\.)?Pattern\.compile\s*\(', masked):
            open_idx = m.end() - 1
            close = matching_paren(masked, open_idx)
            if close < 0:
                continue
            args_text = masked[open_idx + 1:close]
            parts = split_top(args_text, ',')
            expr = parts[0].strip()
            flags = parts[1].strip() if len(parts) > 1 else None
            line = src.count('\n', 0, m.start()) + 1
            nested = class_at(spans, m.start(), simple)
            cls_name = cls_rel.rsplit('.', 1)[0] + '.' + nested if '.' in cls_rel else nested
            try:
                regex = eval_str(expr, consts, simple, global_consts)
            except (Unresolved, ValueError, IndexError):
                regex = None
            st = statement_start(masked, m.start())
            head = masked[st:m.start()]
            d = DECL.match(head)
            depth = member_depth(masked, spans, m.start())
            field = None
            kind = 'local'
            if d and depth == 1:
                mods = d.group(1).split()
                type_ = re.sub(r'\s+', '', d.group(2))
                if 'static' in mods:
                    field = d.group(3)
                    kind = 'field' if type_ == 'Pattern' or type_.endswith('.Pattern') else 'element'
                else:
                    field = d.group(3)
                    kind = 'instance'
            if regex is None and kind in ('local', 'instance'):
                kind = 'dynamic' if kind == 'local' else kind
            key = (cls_name, field, st)
            per_stmt.setdefault(key, []).append(len(entries))
            entries.append({
                'cls': cls_name, 'field': field, 'index': None, 'file': rel, 'line': line, 'regex': regex,
                'flags': flags, 'kind': kind, 'expr': re.sub(r'\s+', ' ', expr)[:300],
            })
        for (cls_name, field, _), idxs in per_stmt.items():
            if field is None:
                continue
            if len(idxs) > 1 or entries[idxs[0]]['kind'] == 'element':
                for i, ix in enumerate(idxs):
                    entries[ix]['index'] = i
                    if entries[ix]['kind'] == 'field':
                        entries[ix]['kind'] = 'element'
    for e in entries:
        if e['field'] is None:
            e['id'] = f"{e['cls']}@{e['line']}"
        elif e['index'] is None:
            e['id'] = f"{e['cls']}#{e['field']}"
        else:
            e['id'] = f"{e['cls']}#{e['field']}[{e['index']}]"
    ids = {}
    for e in entries:
        ids.setdefault(e['id'], []).append(e)
    for dup_id, group in ids.items():
        if len(group) > 1:
            for e in group:
                e['id'] = f"{e['cls']}@{e['line']}"
    entries = [{k: e[k] for k in ('id', 'cls', 'field', 'index', 'file', 'line', 'regex', 'flags', 'kind', 'expr')}
               for e in entries]

    sha, dirty = git_sha(root)
    kinds = {}
    for e in entries:
        kinds[e['kind']] = kinds.get(e['kind'], 0) + 1
    doc = {
        'generatedBy': 'tools/extract-patterns.py',
        'generatedAt': datetime.datetime.now().replace(microsecond=0).isoformat(),
        'modSource': os.path.basename(root),  # the folder name only: a full path names one machine
        'modGitSha': sha,
        'modSourceDirty': dirty,
        'count': len(entries),
        'byKind': dict(sorted(kinds.items())),
        'unresolved': sum(1 for e in entries if e['regex'] is None),
        'patterns': entries,
    }
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, 'w', encoding='utf-8', newline='\n') as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
        fh.write('\n')
    print(f"[patterns] {len(entries)} Pattern.compile site(s) in {len(files)} file(s) at "
          f"{sha[:7] if sha else '?'}{' (dirty)' if dirty else ''}: {doc['byKind']}, "
          f"{doc['unresolved']} with a non-constant regex -> {out}")
    if args.check:
        bad = [e['id'] for e in entries if e['kind'] == 'field' and e['regex'] is None and '(' not in e['expr']]
        if bad:
            print('[patterns] static Pattern fields whose regex did not resolve: ' + ', '.join(bad), file=sys.stderr)
            return 1
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
