## ⬆️ chore(deps): major upgrades for the Manager and Chat UIs (2026-09-28)

**Repo:** EDDI (`chore/deps-major-ui`, on top of `chore/deps-minor-upgrades`)

### What changed

Every stable major (npm `latest`) for the two UIs, except where a required peer has no compatible
release yet. Those stay on their current version, and the peer that blocks each one is named below.

**Manager (`ui/manager`)**

| Package | From → To | Note |
|---|---|---|
| vite | 6 → **8.3** | Vite 8 bundles with Rolldown. `build.rollupOptions` is now `build.rolldownOptions` |
| @vitejs/plugin-react | 4 → **6.1** | Needs Vite 8. No options are used, so there is nothing to migrate |
| vitest, @vitest/coverage-v8 | 4 → **5.0** | Coverage thresholds unchanged and still met. `vitest.config.ts` now imports `./vite.config.ts` with its extension, which Vite's native config loader requires |
| jsdom | 26 → **30.0** (`~30.0.1`) | Held below 30.1, see below |
| @testing-library/jest-dom | 6 → **7.0** | The `/vitest` entry point is unchanged |
| @types/node | 25 → **24.19** | Follows the runtime, not the newest Node: the repo is moving to Node 24 LTS on another branch |
| eslint, @eslint/js | 9 → **10** | See below |
| eslint-plugin-react-hooks | 5 → **7.1** | See below |
| globals | 15 → **17** | |
| i18next | 24 → **26.4** | Nothing removed in 25 or 26 is used (legacy `interpolation.format`, `initImmediate`, `showSupportNotice`) |
| react-i18next | 15 → **17.0** | 17 changes only the keys `<Trans>` generates, and the Manager has no `<Trans>` |
| react-markdown | 9 → **10.1** | 10 drops the `className` prop, which the Manager does not pass |
| lucide-react | 0.577 → **1.48** | See below |
| diff | 8 → **9.0** | `diffLines` / `diffWordsWithSpace` are unchanged |
| monaco-editor | 0.56 → **0.57** | The `dompurify` override still applies to it |
| **typescript** | **stays 5.9** | **Blocked by typescript-eslint**: 8.71.0, the latest, declares `typescript: ">=4.8.4 <6.1.0"` for itself and `@typescript-eslint/parser`, and the Manager lints with it |

**Chat (`ui/chat`)**: vite 6 → **8.3**, @vitejs/plugin-react 4 → **6.1**, typescript 5.9 → **7.0**,
jsdom 26 → **30.0** (`~30.0.1`), @testing-library/jest-dom 6 → **7.0**, @types/node 22 → **24.19**.
Chat has no ESLint, so nothing blocks TypeScript 7. Its tsconfigs already avoided every option 7
removes (`baseUrl`, `moduleResolution: node`, a non-composite `tsc -b`). The configs now use
`import.meta.dirname` instead of `__dirname`, which Vite's native config loader does not support.

**jsdom is held at 30.0.x.** jsdom 30.1 moved `Blob`'s implementation object from a symbol-keyed
property into a private class field. Vitest 5.0.2's jsdom environment still finds that object by
scanning a Blob's own symbols. As a result, every `File`, `Blob` or `FormData` request body throws
`Cannot read properties of undefined (reading '_bytes')`. On 30.1.1 that failed 23 Manager tests,
covering attachments, backup import and RAG upload
([vitest-dev/vitest#11336](https://github.com/vitest-dev/vitest/issues/11336), fix pending in
[#11370](https://github.com/vitest-dev/vitest/pull/11370)). Lift the `~` pin once a Vitest release
carries that fix.

**ESLint 10.** Two rules are new in `eslint:recommended`, and the code is fixed to pass them:

- `no-useless-assignment` found 13 initialisers that every branch overwrites (for example
  `let nextIndex = null` ahead of an exhaustive `switch`). They are now bare declarations.
- `preserve-caught-error` found one error that dropped its cause. The operator's "rollback ALSO
  failed" error now carries the rollback failure as `cause`, and `tsconfig.app.json` /
  `tsconfig.design-sync.json` gain the `ES2022.Error` lib for the constructor's options argument.

**eslint-plugin-react-hooks 7 puts the React Compiler rules in `recommended`.** Spreading that
preset, as the config did, would have turned on 119 findings:

| Rule | Findings |
|---|---|
| `set-state-in-effect` | 66 |
| `refs` | 36 |
| `immutability` | 7 |
| `purity` | 6 |
| `static-components` | 2 |
| `preserve-manual-memoization` | 2 |

[`eslint.config.js`](../../ui/manager/eslint.config.js) now names the two rules the v5 preset
enabled, so the gate checks exactly what it checked before. Adopting the compiler rules is a change
of its own.

**lucide-react 1.0 removed every brand icon.** The Manager used one, `Github`, on the Updates page.
It is now a local `GithubIcon` in
[`update-check-card.tsx`](../../ui/manager/src/components/shared/update-check-card.tsx) with the same
outline path, so it renders unchanged. No other icon name changed for the Manager: `tsc -b` passes
against 1.48.

**One test bound was raised.** `app-routing.test.tsx` waited 5 s for each lazy page chunk. It is the
first test to import `/manage/agents`, so that wait includes transforming the page's whole import
graph. On an idle machine this takes about 1 s. Under the full suite's parallel load it measured
5.4 s and failed twice in four full runs on this branch, although the chunk resolved. The bound is
now 15 s. The test timeout is 30 s.

The four `oxide-wasm32-wasi` lockfile entries are still present. Both lockfiles were written by npm
inside a Linux `node:22.23.3` container, because npm on Windows prunes those entries and breaks CI's
`npm ci`.

### Verification

All checks below ran on Windows with the project's Node 22.23.3, from a host `npm ci` against the
final lockfiles.

- **Manager:**
  - `npm run lint`, `npm run typecheck` and `npm run i18n:check` (4,507 keys, 11 locales) pass.
  - `npx vitest run --coverage` passes: 426 files and 6,858 tests, with no unhandled errors.
    Coverage is 82.35 % statements, 77.17 % branches, 76.92 % functions and 83.99 % lines,
    against thresholds of 81 / 75 / 70 / 83.
  - `npm run build` passes and still emits the three shells, with all 473 font files kept as files.
  - Playwright `test:e2e` (MSW): 234 of 234 passed. The first run had one failure: the app shell
    missed its 15 s wait while the freshly started Vite 8 dev server was still cold.
- **Chat:** typecheck (on `tsc` 7.0.2), vitest (15 files, 278 tests) and build pass.
- **`./mvnw package -DskipTests`:** `BUILD SUCCESS`. It ran `npm ci` and the build for both UIs, and
  left no change in either lockfile.

### Next

- Remove jsdom's `~30.0.1` pin once Vitest ships the fix in vitest-dev/vitest#11370.
- Move the Manager to TypeScript 7 once typescript-eslint supports it.
- Adopt the React Compiler lint rules, one at a time.

```decision-log
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
```
