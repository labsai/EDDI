## 🔒 fix(deps): minimatch 11.0.4 in Manager and Chat UI — Dependabot #75 and #76 (2026-11-03)

**Repo:** EDDI (`fix/minimatch-dos-alerts`)

Both alerts (minimatch globparts amplification DoS, patched in 11.0.1) were dev-only transitive dependencies
through ESLint and typescript-eslint, which pin `^10`. A lockfile refresh alone could not reach the fix, so
`ui/manager/package.json` and `ui/chat/package.json` gain an `overrides` entry `"minimatch": "^11.0.1"`, and both
lockfiles resolve to 11.0.4 (new transitive `@isaacs/nonrecursive-glob-match`). The lockfiles were edited by hand
rather than regenerated on Windows, which drops the `libc` and `peer` fields. `npm ci` and ESLint pass in both
packages.

**Decision:** the override is a major bump for tooling only; remove it once ESLint and typescript-eslint
accept `^11` themselves.
