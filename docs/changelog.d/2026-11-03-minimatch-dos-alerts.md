## 🔒 fix(deps): minimatch 11.0.4 in Manager and Chat UI — Dependabot #75 and #76 (2026-11-03)

**Repo:** EDDI (`fix/minimatch-dos-alerts`)

Both alerts (minimatch globparts amplification DoS, patched in 11.0.1) were dev-only transitive dependencies
through ESLint and typescript-eslint, which pin `^10`, and in the Manager also through
`@stryker-mutator/core`, which pins `~10.2.4`. A lockfile refresh alone could not reach the fix, so
`ui/manager/package.json` and `ui/chat/package.json` gain an `overrides` entry `"minimatch": "^11.0.1"`, and both
lockfiles resolve to 11.0.4 (new transitive `@isaacs/nonrecursive-glob-match`). The lockfiles were edited by hand
rather than regenerated on Windows, which drops the `libc` and `peer` fields. `npm ci` and ESLint pass in both
packages.

**Decision:** the override is a major bump for tooling only; remove it only once none of ESLint,
typescript-eslint and (Manager only) Stryker selects an affected `minimatch` 10.x range any more.

**Review follow-ups:** the dependency summary and the removal condition now name Stryker, whose `~10.2.4`
range would otherwise bring an affected 10.x back the moment the override is dropped.
