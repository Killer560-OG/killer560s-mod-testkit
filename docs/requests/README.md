# Change requests

After WP1 the shared files are frozen (list in `docs/wp/wp1-foundation.md`). A work package that needs one changed -
a new gradle property, a suite in `suites.properties`, an op in `HxPrimitives`, a method on `Session`/`Mod`/`Hx` -
does NOT edit it. It appends to `docs/requests/<wp>.md` (hx, menu, ui, logic, dsim, boss) on its own branch:

```
## <short title>
- file(s): <path>
- change: <exactly what, with the signature or the line>
- why: <the case that needs it, and what it does without it>
- workaround in use: <what the WP does meanwhile, or "blocked">
```

Mod-side needs (a hook, a counter, a getter) go in the same file under `## mod:` - testkit agents never edit
`C:\Users\Hunter\killer560s-mod`; the integrator does, and records the new hook in the mod's docs/TESTING-HOOKS.md.
