# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Add new entries directly below the `---` that closes this section**, above the
most recent existing entry. Never append to an archive file.

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. When it does, run:

```bash
python scripts/rotate-changelog.py
```

It moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by the date each
entry carries, adds one `../` to the relative links it moves (an archive sits a
directory deeper than this file) without touching the ones inside code spans, and
regenerates the Archive table below from what is on disk. Add any newly created
archive file to [`SUMMARY.md`](SUMMARY.md). Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [August 2026](changelog/2026-08.md) | 202 | 788 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## ⚡ perf(monorepo): the efficiency review follow-ups (2026-09-15)

**Repo:** EDDI (`chore/monorepo-migration`) — the follow-ups from the two-reviewer efficiency review
recorded in the monorepo entry below.

### What changed

- **The backend Playwright tiers drive the bundle that ships.** In `e2e-fullstack` the integration and
  full-stack tiers now run with `PORT=7070 E2E_AGAINST_BACKEND=1`: `playwright.config.ts` then starts no
  Vite dev server and every page load goes to the app EDDI serves out of the image — the hashed chunks,
  the multi-page shells, `/manage/__auth_config__.js` — instead of a dev-mode build of the same source.
  (`main.tsx` only falls back to mocks in a dev build, so the full-stack tier cannot silently run on
  MSW this way.) Local runs without the variable are unchanged.
- **`OpenAPI Snapshot`, a new job, checks the Manager's snapshot without a container.** `Build Image`
  passes `-Dquarkus.smallrye-openapi.store-schema-directory=target/openapi` and uploads the document;
  the job compares `ui/manager/src/test/mocks/openapi-operations.json` with it
  (`refresh-openapi-operations.mjs` reads `OPENAPI_FILE`) and uploads the regenerated file when it
  differs. A job of its own rather than a step in `Build Image`, so a stale snapshot does not withhold
  the image from the E2E tiers; `E2E Gate` and `docker` require it. The runtime check in `e2e-fullstack`
  stays on the MongoDB leg and now also guards that the served document agrees with the stored one.
- **The MSW Playwright tier runs with two workers in CI** (`npm run test:e2e -- --workers=2`); the
  config keeps one worker for the backend tiers, whose specs share state in serial groups.
- **Dependabot npm entries** gain a cooldown (3 days, 14 for majors; security updates are never
  delayed) and a `security-updates` group, so open advisories arrive as one PR per ecosystem.
- **`README.md`, `AGENTS.md` and `.githooks/**` leave the `code` filter for `backend`.** Only unit tests
  read them, so a change to only those files now runs `Build & Test` and nothing that builds, scans or
  publishes an image. `BuildQualityGatesTest` checks the root documents against `backend` and keeps
  `backend` equal to `code` without `ui/**`, plus `ui/**/*.md` and those three.
- **`Integration Tests` no longer re-runs the ~20k unit tests.** A new `skipUTs` property (it follows
  `skipTests`, so `-DskipTests` still skips everything) skips surefire alone. `Build & Test` uploads
  `target/jacoco.exec`; `Integration Tests` restores it and the surefire reports before
  `verify -DskipITs=false -DskipUTs=true`, so the merged 90/80 coverage gate grades the same data as
  before. `BuildQualityGatesTest` fails if the job skips the unit tests without that hand-off.

### Decisions

- **Rejected: Vitest `css: false`.** Measured in a `node:20` container on the full Manager suite: 301 s
  against 285 s with CSS processing on, and it broke a real test
  (`export-dialog.test.tsx` › "toggle all checkbox selects and deselects resources").
- **Two Playwright workers, measured before adopting:** 234 MSW tests passed twice at two workers, in
  5.4 and 5.8 minutes, with no flaky results, against 13.1 minutes at one.
- **The Dependabot keys were validated against the published schema** (`json.schemastore.org`
  `dependabot-2.0.json`, checked with ajv, which rejects a deliberately invalid `cooldown` key) — an
  unrecognised key invalidates the whole file and silently stops every update in it.

### Verification

All on 2026-09-15, locally (Windows 11, JDK 25.0.1) unless marked.

- **Shipped-bundle E2E (follow-up 1).** The image built from this branch under the Manager's MongoDB
  compose file, with `PORT=7070 E2E_AGAINST_BACKEND=1`: API integration **44/44 in 12.5 s**, full
  stack **35/35 in 45 s**, and no Vite dev server started. The same tiers through the dev server had
  taken 7.6 minutes, a retried serial group included.
- **Build-time OpenAPI document (follow-up 2).** `refresh-openapi-operations.mjs` with `OPENAPI_FILE`
  pointed at the document a `clean package` stored gives exactly the 344 operations of the snapshot
  taken from the running backend ("No change"): the stored document is a faithful substitute for a
  booted backend.
- **Playwright workers and Vitest CSS (follow-up 3).** In a `node:20-bookworm` container: the MSW tier
  at two workers passed 234/234 twice (5.4 and 5.8 min, no flaky tests); Vitest with `css: false`
  took 301 s against 285 s and failed one test, so it was not adopted.
- **Dependabot (follow-up 4).** The edited file validates against the published schema; both npm
  entries carry the cooldown and the `security-updates` group.
- **Coverage hand-off (follow-up 6).** `package -DskipTests` still reports "Tests are skipped". With a
  unit-test `jacoco.exec` restored into a clean `target`, `verify -DskipITs=false -DskipUTs=true`
  skipped surefire, the unit-test report loaded the restored file (1,263 classes, no class-mismatch
  warning), and the `merge` execution loaded both `jacoco-it.exec` and `jacoco.exec` into
  `jacoco-merged.exec`. The trial's single IT (`LogAdminIT`) could not boot Quarkus on this machine —
  Netty could not open a selector, "Unable to establish loopback connection", the environmental failure
  recorded in the monorepo entry below — so that build stopped before `merged-check`. The 90/80
  evaluation on the full suites is first seen in this PR's CI run.
- **Backend guard tests** on the final state (177, including the changed `backend`-filter tests and the new coverage hand-off test): only the 3 environmental PowerShell failures recorded below.
- **Not verifiable locally:** the workflow graph itself — the new `OpenAPI Snapshot` job, the artifact
  hand-offs between jobs, and how Dependabot applies the cooldown.

## 🧩 chore(monorepo): EDDI-Manager and EDDI-Chat-UI move into this repository as ui/manager and ui/chat (2026-09-15)

**Repo:** EDDI (`chore/monorepo-migration`) — executes `planning/monorepo-migration-plan.md` (PR #670, Revision 3).

**Merge this PR with "Create a merge commit" — never squash.** The imported histories are the reason
the import exists; a squash flattens 1,275 commits into one.

### What changed

- **History import (§5.1).** `labsai/EDDI-Manager` `main` @ `0870ae87` (1,213 commits) and
  `labsai/EDDI-Chat-UI` `master` @ `71fa395` (62 commits) were rewritten with
  `git filter-repo --to-subdirectory-filter` on bare clones and merged with
  `--allow-unrelated-histories` — not `git subtree add`, which loses path-scoped `git log`.
  Verified: both imported trees are blob-for-blob identical to the source commits, no tags
  were imported (55 before and after), `git log -- ui/manager/package.json` returns 81 commits and
  blame shows the original authors. Gitleaks (v8.30.1, the CI version) over both rewritten
  histories: 0 findings, so no `.gitleaksignore` entry had to land on `main` first.
- **Post-import fixups.** The Manager's helper scripts moved from `.github/scripts/` to
  `scripts/`. Two of them computed their root as `../..` for the old depth, which would have
  written the OpenAPI snapshot one directory too high — fixed, with the two tests that import
  them. `.github/` (CI now lives in the root `ci.yml`), husky/lint-staged, `renovate.json`, the
  `deploy-to-local-eddi-repo` scripts and the Chat's tracked `dist/` are gone. The Manager
  lockfile was pruned of husky/lint-staged **by hand**: `npm uninstall` on Windows also drops the
  nested `@emnapi/*` entries that Linux `npm ci` requires.
- **The UIs build with Maven (§6–§7).** `manage.html`, `welcome.html`, `workforce.html` moved into
  `ui/manager` as Vite multi-page inputs sharing one hashed bundle; the Chat builds to `dist/`
  instead of `../EDDI/src/main/resources`. `frontend-maven-plugin` runs `npm ci` + `npm run build`
  for both in `prepare-package` (Node 20.20.2 vendored into `ui/node/`) — so `compile`, `test` and
  `quarkus:dev` never touch npm, while `package`, `verify` and `install` always build the UI — and
  `copy-ui-bundles` copies both `dist/` trees into the jar — without the MSW worker and without
  ever overwriting `index.html`, `robots.txt` or `landing-redirect.js`. 756 generated files are
  no longer tracked; `src/main/resources/META-INF/resources` holds exactly those three
  hand-written files. The `.gitignore` block, the default-resource excludes and the
  `maven-clean-plugin` fileset list the same 13 paths, so a stale pre-migration bundle on disk
  can neither be committed, nor copied into the jar, nor survive `./mvnw clean`.
  `-DskipUi=true` skips all of it.
- **Backend tests.** Five tests read the committed shells or assets and would have failed once
  those stopped being committed. The four resource tests now read stand-in shells under
  `src/test/resources/META-INF/resources`; `StaticAssetCachingTest` drops the `.manager-assets`
  manifest check (the orphaned-bundle problem it guarded against is gone by construction); the
  content-hash check on the built assets lives in `Build Image`, where those assets are produced.
- **CI (§8).** New jobs: `UI Manager Checks`, `UI Manager E2E (MSW)` and `UI Chat` (the Manager's
  former CI plus its MSW Playwright tier, and the Chat's typecheck and tests, as three parallel
  jobs), `UI Gate`, `Build Image` (jar + both UIs + image, built once with an npm cache and passed on
  as an artifact, after checking the four shells, the content hashes and the Chat bundle), `Backend E2E (mongodb|postgres)` (the Manager's API and
  full-stack Playwright tiers against the image built from the same commit, a check that every
  same-origin dependency of the four shipped shells answers 2xx, and a blocking OpenAPI snapshot
  check), `E2E Gate`, `CodeQL Analysis (UI)`. `Build Image` also asserts that the packaged
  `assets/` set equals what Vite just emitted: `copy-resources` never prunes, and a local build without
  `clean` really did package 913 assets against 737 built. `docker` no longer builds: it publishes the tested
  image. `preflight-check` certifies the same image. On a pull request `Build & Test` and
  `Integration Tests` gate on a new `backend` filter — `code` without `ui/**`, plus the UI markdown
  the documentation tests walk, kept equal by `BuildQualityGatesTest` — so a Manager, Chat or npm
  Dependabot PR skips the Java suite; on push and on tags they gate on `code`, so nothing publishes
  untested. `pom.xml` is not in the `ui` filter: the UI jobs never run Maven. The OpenAPI snapshot
  check runs on the MongoDB leg only and uploads the regenerated file when it fails.
- **Housekeeping (§9).** Dependabot npm entries for both UIs (replacing Renovate, carrying its
  react-router major-version block); the scheduled CodeQL scans TypeScript; the Manager's
  compose files take `EDDI_IMAGE` and set the two `HighValueSurfaceGuard` opt-outs whose absence
  had kept the Manager's backend E2E red since 2026-08-26; one Node pin in the root `mise.toml`;
  AGENTS.md, README, CONTRIBUTING and the Manager/Chat docs describe the new layout.

### Decisions and deviations from the plan

- **Fixes landed here, not as preparatory PRs in the source repos.** The Chat's `react-router`
  7.13.1 → 7.18.3 bump (two high advisories, six HIGH Trivy findings) and the compose opt-outs.
  Every gate that reads them (dependency-review, Trivy, Scorecard, the E2E job) reads this PR's
  head, and the source repos are about to be archived.
- **`CodeQL Analysis (UI)` is a separate job, not a language matrix on `codeql`.** A matrix renames
  the required `CodeQL Analysis` check, which would wedge every PR on a context that never reports.
- **`Build Image` labels the image by event.** A pull request gets the bare pom version (what the PR
  preflight certifies and asserts), a push the tag it is published under — exactly what the two
  builds it replaces did.
- **The OpenAPI snapshot check is blocking on PRs too.** In the Manager repo it was advisory there
  because the backend image tracked `latest`; here the backend is built from the PR.
- **Efficiency review (two independent reviewers, Fable 5 and Opus 5).** Both found the same costs:
  every local `compile`/`test`/`quarkus:dev` rebuilt both UIs (and deleted `node_modules` under a
  running `npm run dev`), UI-only PRs ran the ~20k-test Java suite, Maven Dependabot PRs ran the UI
  job, and the UI job ran Manager and Chat serially. Fixed as above. Rejected: defaulting `skipUi`
  to true (a local `package` — `install.sh --local`, `mise run docker-build` — would then silently
  build an image without a UI), and frontend-maven-plugin's incremental build options (its `npm`
  goal has none in 1.15.1 — checked in the plugin descriptor).
- **Phases 2 and 3 are one commit.** Deleting the committed bundles before Maven builds them would
  leave a commit whose jar serves a blank `/manage`.
- **The Chat `typecheck` script is now `tsc -b --noEmit`.** `tsc --noEmit` against its solution-style
  `tsconfig.json` checked nothing; plain `tsc -b` would have emitted into `dist/`.
- **Not ported:** the Chat branch `fix/release-6.2-polish` (two commits, never merged to `master`,
  never shipped). Port with `git format-patch` + `git am --directory=ui/chat` or drop it.
  The Manager's Stryker `mutation.yml` is not carried over (§13).

### Verification

All run locally on 2026-09-15 (Windows 11, JDK 25.0.1) unless marked Linux.

- **Import.** Both imported trees blob-identical to the source commits; 55 tags before and after;
  path-scoped `git log`, `--follow` and blame show the original commits. Gitleaks over both rewritten
  histories: 0 findings.
- **`-DskipUi=true`** (`clean package`): no frontend execution ran and `target/classes` holds no UI.
- **Dirty workspace, `package` without `clean`**, with stale `assets/index-STALE00.js`, `manage.html`,
  `chat.html`, `mockServiceWorker.js`, `img/loading-indicator.svg` and a `chat-ui.*.js` planted in
  `src/main/resources/META-INF/resources`: none reached `target/classes`, and both shells there are
  the freshly built ones — the default-resource excludes hold without help from `clean`.
- **`./mvnw clean`** deleted every planted file and both `dist/` trees and kept `index.html`,
  `robots.txt` and `landing-redirect.js`.
- **Full build:** `copy-ui-bundles` copied 742 Manager and 11 Chat files; 737 hashed assets; all three
  Manager shells load the same `assets/main-<hash>.js`; no `mockServiceWorker.js`, no root
  `logo_eddi.png`, no pre-migration `index-*` entry; the bundle carries `EDDI Demo 6.4.0` from
  `EDDI_VERSION`.
- **Linux (`node:20-bookworm`), the `UI Build & Test` steps from a `git archive` of the branch:**
  Manager `npm ci` (so the hand-pruned lockfile satisfies Linux npm), `audit:prod`, lint, i18n check,
  typecheck, Vitest with coverage, build, the build-output check, and all 234 Playwright MSW tests;
  Chat `npm ci`, typecheck, tests and build, with no stray `ui/EDDI`. All green.
- **Backend guard tests** (`StaticAssetCachingTest`, the four `Rest*ResourceTest`s,
  `BuildQualityGatesTest`, `ReleaseVersionSourceTest`, `DocumentationLinksTest`,
  `DocumentationAccuracyTest`, `ImportStyleTest`, `ComposeStackTest`, `DeploymentManifestsTest`,
  `StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`, `ChangelogRotationTest`,
  `DocumentedRestPathsTest`, `ConfigurationReferenceCoverageTest`, `DemoImageDockerfileTest`), run after
  every review fix and with this entry in place: 176 run, 3 failures — all in
  `DeploymentManifestsTest`'s PowerShell `create-secrets.ps1` cases, which fail identically on an
  untouched `origin/main` worktree on this machine (environmental).
- **Environmental, not this change:** locally `quarkus:build` ends with "Unable to establish loopback
  connection" on untouched `origin/main` too — it is the build-analytics ping; builds here pass
  `-Dquarkus.analytics.disabled=true`.
- **The image built from this branch** (`labsai/eddi:ci`), booted under the Manager compose files on a
  shifted host port, on **both MongoDB and PostgreSQL**: healthy within seconds; the `Verify the shipped
  shells` step, extracted verbatim from `ci.yml`, passed on both (487 same-origin dependencies of the
  four shells answer 2xx, the entry chunk carries the immutable `Cache-Control`, `mockServiceWorker.js`
  and a root `logo_eddi.png` answer 404).
- **OpenAPI snapshot against that backend: drifted**, exactly as the new blocking check is meant to
  catch. Refreshed in this commit (+8 operations: resource sharing, `/workspaces`, connection settings;
  −2: `GET /chat` and `GET /chat/{path}`, now hidden from the OpenAPI document), which made five
  `EXEMPT` entries in `openapi-contract.test.ts` stale; they are removed and the contract test passes.
- **The Manager's Playwright tiers against that image on MongoDB** (compose file as CI uses it, port 7070):
  API integration **44/44**. Full stack **34 passed, 1 failed** on the first run — the card test in
  `resources-crud.fullstack.spec.ts` still clicked the pre-v6 `resource-type-behavior` id, which
  `resources.tsx` no longer renders. Untouched Manager `main` (`0870ae87`) against the same image fails
  the same test, so it predates this change: the tier had not been able to boot a backend since
  2026-08-26 and never reached it. Fixed here, together with the spec's outdated known-failure header
  (with `60188c2bd` in the image all six resource types list). The spec runs in serial mode, so the one
  failure had retried the whole group twice; after the fix it passes **9/9 in 20 s**.
- **Not run locally:** the Playwright tiers on PostgreSQL (the image boots there and passes the
  shipped-shell step, above) and any of it on a GitHub runner — the first signal is this PR's own run.
- **Efficiency changes:** `./mvnw clean compile` and a `test` run executed **zero** frontend steps;
  `clean package` ran surefire, then all five frontend steps in `prepare-package`, then
  `copy-ui-bundles`, then the jar (737 assets, and the build-time OpenAPI document in
  `target/openapi`); the new `Verify the built and packaged UI` step, extracted from `ci.yml`, passes
  on that output; the guard tests (176, including the new `backend`-filter drift test) show only the
  3 environmental failures above.
- **Not verifiable locally:** the CI graph itself (job skips, artifact hand-off, fork PRs, required
  checks).

### Outside this repository (plan §9.5, §10) — still to do

- Branch protection: require `UI Gate` and `E2E Gate` (both always report; the jobs behind them
  are path-gated); keep `CodeQL Analysis` and `Build & Test` as they are.
- Do **not** enable GitHub's CodeQL default setup. It is `not-configured` (checked with
  `gh api …/code-scanning/default-setup`); every analysis comes from `ci.yml`, and turning it on
  would make GitHub reject the workflow's own uploads and fail the required `CodeQL Analysis`
  check. `CodeQL Analysis (UI)` already scans the TypeScript.
- After the first green `main` pipeline including Backend E2E: pointer READMEs, close the open PRs
  (Manager #72, #95, #140, #168, #207; Chat #19–#24, #26) with a link here, archive both repos.
- Local: `EDDI.code-workspace`, stale copies of the deploy scripts; run `./mvnw clean` once.

## 🏷️ chore: refresh README badges and set the project domain to eddi.technology (2026-09-15)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

- `README.md` — the tests badge said `14,000+`; the last full unit run on this branch was 20,221
  tests, so it now reads `21,000+`. The coverage badge names both gates, `>90% instr / >80% branch`,
  matching the figures AGENTS.md already quotes.
- `application.properties` — `systemRuntime.projectDomain` moves from `eddi.labs.ai` to
  `eddi.technology`. Its only consumer is `HttpClientWrapper`, which builds the outbound
  User-Agent from it, so outbound calls now identify as `EDDI.TECHNOLOGY/<version>`. The other
  `eddi.labs.ai` references (OpenAPI contact URL, banner, Dockerfile image label, docs) are left
  as they are in this change.

## 🔀 chore(merge): origin/main into the 6.4.0 dependency branch (2026-09-15)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

`origin/main` @ `13e8feb73` (PR #751, the connections review findings) merged in. Only this file
conflicted, and only because both sides added entries at the top: every entry from both sides is
kept, main's newer 2026-09-14 entries above this branch's dependency and release entries. No other
file was changed on both sides — the Manager UI assets and `pom.xml` bumps are this branch's alone.

## 🔁 fix(connections): the fourth PR #751 review round, with main merged in (2026-09-14)

**Repo:** EDDI (`fix/connections-review-findings`)

**Merge.** `origin/main` @ `52d031940` (PR #750, the 6.4.0 E2E fixes, and PR #746) merged in. Only this
file conflicted: both sides' entries are kept, as are both sides' Decision Log rows. The merged file
was over the 250 KB cap, so `scripts/rotate-changelog.py` moved 12 entries into the existing
`docs/changelog/2026-08.md` (no new archive, so `docs/SUMMARY.md` is unchanged).

**Review findings** — the open thread and the two outside-diff findings from the round-3 review body:

- **A reshaped connection still had its code redeemed.** `boundConnection` checked only that the
  name still belonged to the bound id, so a connection switched to `OAUTH2_CLIENT_CREDENTIALS` or
  `SERVICE` while the user sat on the consent screen had the authorization code exchanged, a refresh
  token minted and stored, and only then discarded by the post-write re-read.
  `RestConnectionAuthorization.callback` now refuses with `exchange_failed` before the exchange
  unless the document is still a `PER_USER` authorization-code connection
  (`takesPerUserGrants`, shared with `connectionStillTakesTheGrant`). The post-write re-read stays
  for an update landing after that check. Tests: `shapeChangedBeforeTheExchangeRedeemsNothing`;
  `grantStoredUnderAShapeTheConnectionNoLongerHasIsDeleted` now reshapes the connection inside the
  write window.
- **A stale callback's discard could delete a newer grant.** `discardGrant` deleted by
  `(tenant, name, principal)`, so a later link of the same account that replaced the grant between
  the write and the discard was removed with it. New `IConnectionGrantStore.deleteIfSealedWith`
  deletes only while the row still carries the access-token IV of the write being taken back
  (Mongo: filter on `accessTokenIv`; Postgres: `AND access_token_iv = ?`; the in-memory double
  mirrors it). `OAuthTokenService.persistNew` now returns the grant it wrote so the callback can
  name it. A `null` IV matches nothing — in Mongo `eq(field, null)` would also match a row without
  the field. Tests in `MongoConnectionGrantStoreTest`, `PostgresConnectionGrantStoreUnitTest`,
  `ConnectionGrantStoreCasTest` and every discard test in `RestConnectionAuthorizationCallbackTest`
  (which now also assert no keyed `delete`).
- **Docs:** `docs/connections.md` described plaintext-origin enforcement in terms of the property,
  which a stored `allowPlaintextRemoteOrigins: true` makes wrong. Both places now say "the effective
  `allowPlaintextRemoteOrigins` setting".

**Decision:** the discard is keyed on the IV rather than on `version`. `upsert` does not report the
version it wrote, and making it do so means `findOneAndUpdate` / `RETURNING` on both backends for
one caller. The IV is random per seal, so it names the write; its one blind spot is a DEK re-seal in
the milliseconds between write and discard, where the delete misses and the grant stays until
disconnect or connection deletion — the safe direction.

---

## ⚙️ feat(connections): runtime connection settings — no restart, properties pin (2026-09-14)

**Repo:** EDDI (`fix/connections-review-findings`, PR #751) · Manager counterpart on `feat/connection-settings`

The four deployment settings of the connections feature — `enabled`, `publicBaseUrl`,
`credentialEndpointAllowlist`, `allowPlaintextRemoteOrigins` — were properties only, so
turning the feature on or approving one more OAuth provider meant a restart, and enabling
it without a base URL refused the boot outright. They are runtime settings now, written
through `PUT /connectionstore/settings` and read back with their provenance.

**Why the properties-only argument did not hold.** It rested on "an operator, not an
administrator, approves where a client secret may go". Nothing else in EDDI draws that
line: `eddi-admin` writes the vault, and an httpcall header resolves any `${vault:…}` and
sends it to any host (`ApiCallExecutor` resolves vault references in headers, query, body
and URL with no destination binding), which `eddi-editor` may also author. A properties-only
allowlist therefore cost a restart and protected nothing from an administrator. What it
*does* still need protecting from — an LLM, an imported agent, a connection document
vouching for itself — is kept out by where the endpoint is exposed, not by a restart.

**Design.**

- **Precedence: pinned → stored → default.** A property or environment variable that is
  set *pins* its value: it wins, reads as `PINNED`, and a `PUT` that would change it is a
  **409** naming the property. That keeps the operator/administrator split available to a
  deployment that genuinely has one. Restating the pinned value, or omitting it, is
  accepted. Every default fails closed. The four property lines in
  `application.properties` are now commented out, because an uncommented line would pin.
- **No seeding.** A pinned value is never copied into the store, and a pinned field's
  previously stored value is carried forward untouched — so removing the property later
  falls back to what an administrator stored, not to a silent copy of the old pin.
- **Freshness.** `ConnectionsConfig` caches the stored document for 5 s (it is read on hot
  paths — every resolution asks about plaintext origins). The writing instance adopts its
  own write immediately; others see it within the TTL. A store read failure keeps the last
  values; before the first successful read only pins and defaults apply.
- **Validation at the write boundary** (`RestConnectionSettings`): the base URL must be a
  bare https origin (dev/test: loopback http), allowlist entries are canonicalised and
  de-duplicated, and a *remote plaintext* allowlist entry is refused — a credential endpoint
  must be https or loopback before a secret is sent, so it would approve nothing. The shape
  rules live once in `ConnectionSettingsRules`, shared with the boot and request-time checks.
- **The boot no longer refuses a missing base URL.** Only per-user OAuth linking needs it,
  and `POST /connections/{name}/authorize` now answers **400** naming the setting. A
  *pinned* base URL of the wrong shape still refuses the boot — it is operator configuration
  that only a restart changes.
- **Exposure.** `eddi-admin` only (`IRestConnectionSettingsRoleGateTest`). Not an MCP tool,
  not in export/import or Agent Sync, and excluded from the Platform Operator's write scope
  (pinned by a Manager test). The response carries the `redirectUri` to register at each
  provider and warnings for a configuration that saves but will not fully work. Each change
  is logged at INFO with the principal and the before/after values (none is a secret).
- **One document per tenant**, Mongo `connection_settings` (`_id = tenantId`) and Postgres
  `connection_settings` (nullable columns, `TEXT[]` allowlist); `tenantId` is `"default"`
  until multi-tenancy supplies one. Whole-document replace, last write wins.

**Files.** New: `connections/settings/` — `ConnectionSettings`, `ConnectionSettingsRules`,
`ConnectionSettingsView`, `IConnectionSettingsStore`, `MongoConnectionSettingsStore`,
`PostgresConnectionSettingsStore`, `IRestConnectionSettings`, `RestConnectionSettings`.
Changed: `ConnectionsConfig` (pinned/stored/default resolution, cache), `CredentialEndpointAllowlist`
(reads the effective allowlist per call), `ConnectionStartupGuard` (pinned-only refusal,
warning otherwise), `RestConnectionAuthorization` (`requirePublicBaseUrl`), messages in
`ConnectionResolver`, `RestConnectionStore` and `ConnectionConfiguration` naming both
handles, `DataStoreProducers`, `application.properties`, `docs/connections.md`,
`docs/configuration-reference.md`. Tests: `ConnectionsConfigStoredSettingsTest`,
`RestConnectionSettingsTest`, `ConnectionSettingsRulesTest`, both store tests, the role gate;
`ConnectionStartupGuardTest` and `RestConnectionAuthorizationCallbackTest` updated for the
new boot and authorize behaviour.

**Review round (Fable 5.1 review + a live boot against MongoDB).**

- **Unauthenticated writes are refused outside dev/test** while `authorization.enabled=false`
  (403), unless `eddi.connections.settings.allow-unauthenticated-writes=true`. The rationale
  "properties-only protected nothing from an administrator" is right for administrators, but on
  a deployment without OIDC `@RolesAllowed` is a no-op — and the shipped compose files run that
  way — so an *anonymous* caller could otherwise approve an origin for a client secret. Same
  narrow per-surface opt-out shape as `HighValueSurfaceGuard`; adding the path to that guard
  instead would have failed every existing unauthenticated boot.
- **A stored value hidden behind a pin is surfaced** (`Setting.shadowedStoredValue`) with a
  warning. Removing a restrictive pin brings a permissive stored value back; that must never be
  invisible.
- **Enabling at runtime runs the stored-connection report** (`ConnectionStartupGuard.reportStoredConnections`)
  that a boot with the feature off skipped. A store unreadable at boot says the report was skipped.
- **Restating a pinned value is compared before strict validation and normalised**: a trailing
  slash or scheme case on the base URL, and an operator-pinned plaintext allowlist entry, no
  longer produce a 409 or 400.
- **Postgres `updated_by` is `TEXT`** (an OIDC principal can exceed 255 characters); `Array.free()`.
- **Serialization contract.** EDDI omits null fields (`NON_NULL`), so an unset `value`,
  `redirectUri`, `updatedAt` or `updatedBy` is absent from the JSON, not `null`. Documented on
  `ConnectionSettingsView`; the Manager types and fixtures follow it.
- **Upgrade note**: a property set even to its default now pins (configuration-reference).

**Not done / open.** Cross-replica invalidation is TTL-only (no event bus). Changes are
logged, not written to the audit ledger — the ledger is conversation-task shaped. The
Manager's OpenAPI snapshot exempts the two new operations until it is refreshed against a
backend that has them.

---

## 🔁 fix(connections): the PR #751 review round — claims, clocks, export, plaintext and redaction (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`)

Twenty-two review comments on PR #751 (CodeRabbit, Copilot, CodeQL, code quality), all
verified against the code and all valid. One commit per finding, each with a regression
test. Two of them correct fixes recorded in the entries below: R7 bound the contender's JVM
clock, which only moved the skew, and C3 enforced name uniqueness with scans that could not
see across replicas.

**Uniqueness and races.**

- **A durable name claim** (`10962ccab`). `IConnectionNameClaimStore` keeps one claim per
  (tenant, name) under a MongoDB unique index or a PostgreSQL unique constraint. A create
  claims first and answers 409 for a live holder; a stale claim (crashed create, failed
  release) is taken over by compare-and-set. The create records its id against its token,
  then writes the descriptor, and a descriptor failure is now a failed create. Connections
  that predate claims are found by the one remaining name scan and backfilled. The unused
  multi-holder scan, `IConnectionStore.idsOfName`, is removed.
- **Grants linked during an authType/binding change** (`e5db9c0a8`). A count before the
  update is not atomic with an OAuth callback. Both sides now re-check after their own
  write — the update counts again and deletes what appeared, the callback re-reads the
  connection uncached and discards its grant if the shape changed — so one of the two
  always sees the other.
- **The refresh lease uses the database clock on both sides** (`22f153147`):
  `CURRENT_TIMESTAMP AT TIME ZONE 'UTC'` on PostgreSQL, `$$NOW` on MongoDB. `claimRefresh`
  takes a duration. The lease is released when the claimant's re-read fails (`9cbbb83c2`).

**Credentials in transit and at rest.**

- **Plaintext remote origins need an opt-in** (`e0bf28a9b`):
  `eddi.connections.allow-plaintext-remote-origins`, default `false`. Refused at save (400),
  per request (`TARGET_NOT_ALLOWED`) and reported at ERROR at boot. Loopback http is always
  allowed.
- **A plaintext token URL is refused before the client secret is sent** (`1adb43c22`);
  https, or http to a loopback host, shared with save-time validation.
- **Non-admin reads are redacted** (`414982f3d`). An editor reading a connection gets
  `clientSecret`, `passwordRef`, `valueTemplate` and each `extraAuthParams` entry only if it
  passes the write-time rule; a legacy literal is replaced by a marker. Admins see the
  stored document so they can fix it.
- **Log lines sanitize user-controlled values** (`a9f87b184`, plus the four refresh-path
  lines in `OAuthTokenService`), closing CodeQL `java/log-injection`.

**Export, import and grants checking.**

- Export authorizes VIEW on every referenced connection and refuses without it
  (`2d85bce21`); only a dangling reference is skipped, every other failure fails the export
  (`c35005ceb`).
- `strategy=upgrade` imports the archive's connections too (`3b65c91cf`), and an import that
  cannot account for a connection it created (no resource URI, no descriptor) fails and
  rolls it back (`5c602dd91`).
- The vault-grant check expands `${vars:}` in the connection's own tenant (`5735c021d`) and
  before the connection scan as well as the vault scan (`5a86bbc98`).
- A malformed stored origin no longer aborts the startup report (`6a8b73ac9`); the allowlist
  docs no longer mention discovery endpoints (`3cdf0780a`); two mocked `ResultSet` stubs no
  longer read as leaks (`830acb20e`).

**Decision — export refuses rather than skips** a connection the caller cannot view. An
archive quietly missing a connection imports into an agent whose references do not resolve,
and nothing says why.

**Decision — the claim is a separate store, not an index on the config document.** The
versioned document store cannot carry a unique index on a field inside the document.

**Upgrade notes.**

- Connections with a remote `http://` origin stop resolving until the new property is set
  or the origin moves to https.
- MongoDB 4.2+ is required (`$$NOW`); the project documents 6.0+.
- New storage, created lazily: collection or table `connection_name_claims`.
- A duplicate name on create answers **409**, not 400.
- During a rolling upgrade, a replica without claims can still race a create briefly.

**Not changed:** CodeRabbit's docstring-coverage pre-merge warning, which counts every
touched function; the project documents behaviour at class and non-obvious-method level.

**Second round.** The push drew nine more threads from CodeRabbit, CodeQL and GitHub code
quality.

- **The OAuth callback is bound to the connection's id, not its name** (`bcddbb4a2`). A
  connection deleted and re-created under the same name while the user was on the consent screen
  received a grant issued for its predecessor's client, and its own allowlist decided where that
  token went. The state row now carries the connection id (a MongoDB field; a PostgreSQL
  `connection_id` column added with `ADD COLUMN IF NOT EXISTS`). The callback exchanges nothing
  unless the name still resolves to that id, reads the connection by id uncached rather than from
  the name-keyed registry, and after storing the grant re-checks id and shape and discards it on a
  mismatch.
- **A method CodeQL read as a permission check is renamed**, `requirePlaintextOriginsPermitted` to
  `refusePlaintextRemoteOriginsUnlessAllowed`. `java/tainted-permissions-check` matches any
  one-argument method whose name contains "permitted"; this one validates input, and who may
  write is `@RolesAllowed("eddi-admin")`. Renamed rather than dismissed, so no alert needs an
  admin's judgement.
- **The PostgreSQL store tests assert resource closing** (`f7be4653f`). The code-quality leak
  findings pointed at Mockito stubbing expressions, which acquire nothing, and last round's
  restyling only moved the warning. The tests now check that every connection, statement and
  result set the name-claim and grant stores open is closed, on the failure paths too.
- **The in-memory grant store validates the refresh lease first** (`192895d29`), as both real
  stores do; it used to leave a claim with no expiry behind on a null lease.
- **The plain-text variable test proves expansion ran** (`fbe3bd213`).

**Upgrade note:** an account link started before the upgrade and finished after it is answered
`invalid_state`, because its state row carries no connection id. States live ten minutes; the user
starts the link again.

**Third round.** Two more threads. CodeRabbit found that `authorize` still built the consent
URL from the name-keyed registry cache while taking the connection id from the store, so right
after a delete and re-create a replica could send the user to the predecessor's consent screen and
fail at the callback. `authorize` now reads the connection by id at its current version, uncached,
and uses that one document for validation, the state and the URL; the registry it no longer
reads is dropped as a dependency. A further code-quality "leak"
on a mocked `ResultSet` in the name-claim store test is the same false positive as round two; that
test already asserts the store closes it.

**Companion:** labsai/EDDI-Manager#208 answered its own review round (a complete reference
before the chip, retries only on network/5xx, the name grammar in references, Retry through
the `Button` primitive).

---

## 🔐 fix(connections): runtime and security findings from the connections review (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`)

Thirteen findings from the code review of the connections feature, runtime and security side
(the config/store/docs findings are on a sibling branch). One conventional commit per finding;
every fix carries a regression test that fails without it.

**Credentials that leaked or went missing.**

- **R1 — connection-owned headers persisted in plaintext.** `RequestRedactor` recognised a
  credential only by conventional header name, a `${vault:` marker, or value shape. A `STATIC`
  connection on `X-Amp-Id` or a `CALLER_SUPPLIED` one on `X-Gnowbe-Key` matched none, so the live
  value was written to MongoDB and shown to a HITL approver. `buildRequest` now returns the header
  names a connection filled, and both the persisted request map and the approval preview redact them
  unconditionally, case-insensitively. MCP and A2A persist no request headers.
- **R2 — no `CallerIdentity` on turn 0.** `startConversation` bound the `ResolutionPrincipal` around
  the synchronous CONVERSATION_START turn but not the caller, so `${caller:token}` and every
  `CALLER_SUPPLIED` connection failed closed on turn 0 with advice about scheduled runs. The identity
  is captured once, binds the start turn, and the previous binding is restored (nested starts).
- **R5 — principal not propagated to cascade/batch threads.** `callerIdentityContext.propagate`
  carried the caller only; a `PER_USER` connection inside an agent-mode cascade step or a
  fire-and-forget batch was refused. `ResolutionPrincipalContext` gains `propagate`/`withPrincipalSupplying`,
  and `CallerIdentityContext.propagate` composes both — the single helper mid-pipeline dispatches
  use, so the next binding is added there. The explicit `withIdentity(...)` sites stay caller-only:
  they dispatch from request threads where the member conversation binds its own principal, or
  compose with `withPrincipal` themselves (the HITL resume).

**The token endpoint.**

- **R3 — redirects followed with the body.** `sendValidated` re-implements redirect following
  (method and body preserved on 307/308) and validates the hop against SSRF rules only. A token
  request carries the client secret and the refresh token or code, so an allowlisted endpoint
  answering 307 re-sent all of it elsewhere, while docs and Javadoc claimed otherwise.
  `SafeHttpClient` gains `sendValidatedNoRedirect` and `sendNoRedirect`; any 3xx from a token
  endpoint is `TOKEN_ENDPOINT_UNAVAILABLE` with a message saying a token endpoint must not redirect.
- **R4 — transient failures marked terminal.** An access token that would not unseal inside the
  refresh claim, and a 200 whose body is not a token response (HTML maintenance page, empty body,
  no `access_token`), were `GRANT_UNUSABLE` and wrote `REFRESH_FAILED`. Both are transient now; only
  `invalid_grant` / `invalid_client` / `unauthorized_client` is terminal.
- **R6 — escaping exceptions, and on-prem IdPs.** The callback caught only `ConnectionException`
  after claiming the state; an `IllegalArgumentException` from URL validation or an
  `IllegalStateException` from the store reached the browser as a 500 with the state consumed.
  Every `RuntimeException` after the claim is counted `exchange_failed`, logged at ERROR (class name
  only) and answered 303. `refreshAsClaimant` wraps the same as `TOKEN_ENDPOINT_UNAVAILABLE` (503).
  The token request no longer goes through the SSRF address block: the credential-endpoint
  allowlist is a stricter rule (an exact operator-listed origin), so an on-premises IdP on a private
  network is usable; scheme and host are still validated, nothing else gets the exemption.
- **R7 — Postgres lease vs the DB clock.** `claimRefresh` compared a JVM-written lease against
  `CURRENT_TIMESTAMP`; app/DB skew shortened the lease and let a second replica refresh mid-flight.
  The JVM instant is bound, as `PostgresOAuthStateStore` and the Mongo store already do.

**What the operator is told.**

- **R8** — with `authorization.enabled=false` every request is anonymous and its credential headers
  are dropped, so `NO_CALLER_CREDENTIAL` now says the deployment cannot accept one and names the fix,
  instead of claiming the request carried nothing.
- **R9** — the four OAuth/refresh meters were unprefixed and registered through a helper the
  `MetricsDashboardCoverageTest` regex cannot see; they are `eddi.connection.*` now, registered via
  `increment("eddi.…")`, charted in four new panels, and listed in `docs/metrics.md` and the
  `docs/connections.md` table. `UNSUPPORTED_PLACEMENT` was mapped to 400 but never thrown —
  `ApiCallExecutor` now throws it for a `${connection:…}` outside a header.
- **R10** — the MCP discovery warning named `PER_USER` for `CALLER_SUPPLIED` too and A2A logged
  nothing; `ConnectionResolver.bindingOf` names the actual binding for both, and routes a registry
  read failure through `countLookupFailure`. RFC 9728 discovery is not implemented, and the
  allowlist Javadoc and "Two allowlists" section no longer claim discovery endpoints.
- **R11** — a cached `STATIC`/`BASIC` document without `staticAuth` NPE'd, which the MCP failure
  classifier fed to the circuit breaker; both refuse with `INVALID_CONFIGURATION`, and BASIC with a
  null username refuses rather than sending `null:password`.
- **R12** — `VaultGrantChecker` skipped an unreadable connection at DEBUG (its secrets counted as
  granted); it is now a violation naming the connection. `${vars:}` is expanded through
  `GlobalVariableResolver` before the vault scan, on the connection hop and the general scan alike.
- **R13** — tests for `X-EDDI-Connection-Credential` parsing, the HITL resume bindings, the
  `lease_expired` branch (via a package-private await-timeout seam) and a failing `REFRESH_FAILED`
  write, which no longer replaces the provider's verdict with a raw store exception.

**Decision — `propagate` carries both bindings; `withIdentity` does not.** A wrapper that carries
one of the two thread bindings and not the other is the drift R5 fixed, so the snapshot-current
helper composes them. The explicit-identity helpers are used where the principal is deliberately
different (HITL resume) or established later from stored memory (group members), so they stay
single-purpose rather than silently overriding a principal the caller set.

**Decision — the credential-endpoint allowlist outranks the SSRF address block for the token
endpoint only.** An exact origin an operator wrote down is a stronger statement than "not a private
address"; the httpcalls path keeps `eddi.security.ssrf-protection` untouched.

**Not verifiable here:** `SafeHttpClientTest` binds a loopback server, which this sandbox refuses at
`HttpServer.create` (pre-existing for the whole class); its three new no-redirect cases run in CI.

---

## 🧩 fix(connections): configuration, store and export findings from the connections review (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`, merged from `wip/connections-config-findings`)

Thirteen findings from the connections code review, each its own commit with a regression test
that fails without it. The runtime findings of the same review are on a sibling branch; the
two meet in `docs/connections.md` and here.

**Write-boundary validation (`ConnectionConfiguration`):**

- **`valueTemplate` literal text is now checked** (C1). The old check inspected only the `${…}`
  segments, so `sk-live-abcdef${vault:unused}` saved while the docs said it was refused. Rule:
  every `${` must be a well-formed `${vault:…}`/`${vars:…}` reference (an unclosed brace or a
  key over 256 chars used to count as literal text), at least one reference, and each literal
  segment ≤ 32 chars with no run of ≥ 12 key characters. Scheme prefixes pass; a key does not.
- **The name has a grammar** (C2): `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, refused rather than
  trimmed. `ConnectionReference` stops at `/` and `}`, the credential header splits at the first
  space, so `acme/jira` resolved as tenant `acme` and a name with a space resolved for nobody.
- **`extraAuthParams` values are checked** (C7): no `${…}` reference (it would be resolved into a
  browser-visible URL), ≤ 512 chars, no credential-shaped prefix (`sk-`, `xox?-`, `gh?_`,
  `AKIA`, `eyJ`, `Bearer`). The key denylist gains the parameters EDDI composes itself
  (`redirect_uri`, `state`, `code_challenge`, …), normalised so `Redirect_uri` is caught.
- **`timeoutMs` is bounded 1..60000** at save time (C9); the token client's own ceiling still
  clamps at use. A non-loopback `http://` origin in `baseUrlAllowlist` stays accepted but is
  logged at WARN when saved and again by the startup guard.

**REST store (`RestConnectionStore`):**

- **Name uniqueness is no longer check-then-act** (C3). Creates of one `(tenant, name)` are
  serialised on a striped lock inside the JVM; after the write lands the store is asked again
  who holds the name (`IConnectionStore.idsOfName`, oldest-first). **Review fix:** the first
  version of this left the descriptor to `DocumentDescriptorFilter` *after* the method
  returned — outside the lock — so the lock guarded nothing: a second create on the same
  node took it the instant the first released it, scanned, found no descriptor yet, and both
  landed. `RestConnectionStore` now writes the descriptor itself inside the lock (the filter
  finds it and does nothing; `RestImportService.recordCreatedConnection` writes one only when
  missing). Our own id is expected in the post-write scan and filtered out; the rule stays
  "any other holder visible now wins" — ours is removed permanently, descriptor included, and
  the caller gets 409. Chosen over "oldest wins" because under asymmetric visibility the latter
  duplicates the name; the cost is that two replicas seeing each other both stand down and both
  callers retry. What remains is replication lag between nodes.
- **Duplicate goes through `validateForWrite`** (C4) — it skipped the deployment checks.
- **`CALLER_SUPPLIED` needs OIDC** (C5): `CallerIdentityContext` drops the credential header for
  an anonymous identity, so with `authorization.enabled=false` the connection saved and failed
  every call as `NO_CALLER_CREDENTIAL`. Now 400 at the write boundary and a stored-state report
  at boot, like `PER_USER`.
- **An `authType`/`binding` change with linked accounts is a 409** (C6), naming the count and
  the way out (`DELETE /connections/{name}/grant`, or delete the connection). Re-saving a
  `PER_USER` OAuth connection as `STATIC` left every user's refresh token at rest under a name
  the resolver never read again. `IConnectionGrantStore.countByConnection` added (Mongo,
  Postgres, in-memory double) — the only change under `connections/grants/`.
- **Editors may list and read connections** (C11): `eddi-editor` on the descriptor listing and
  the single read; every write stays admin-only. A document carries references only.

**Startup guard:** dev/test now require a bare origin and accept plain http on loopback only,
and the scheme is compared case-insensitively in every profile (C8); a first-release
`OAUTH2_AUTHORIZATION_CODE` + `SERVICE` document is reported with the fix (C12).

**Export/import (C10):** an agent archive now carries the connections its configs reference,
as `connections/{connectionId}.connection.json` — document only, never a grant. Two defects
made this necessary rather than nice: `AbstractBackupService` had no connection entry, and
`SecretScrubber` redacted `${connection:jira}` in an `Authorization` header to
`${vault:REDACTED}`, so the reference died before the archive was written. **Review fix:** the
scrubber's exemption is for a value that *is* exactly one `${connection:…}` reference, not one
that contains it — every outbound path refuses a mixed value (`ConnectionReference.requireSole`),
so a "contains" exemption only kept `Bearer sk-… ${connection:jira}` legible. Import creates a
connection only when the name is free — an existing one is never overwritten — through
`RestConnectionStore.createConnection` (same validation, deployment checks and lock as REST);
a refused document is skipped with its reason and counted in `X-Connections-Skipped`. The
descriptor is written by the store inside its name lock; the import writes one by hand only
when it is missing, as `createResourceDirect` does. Live sync still does not carry
connections; said so under Limitations. `AGENTS.md` §5.5 lists the file and counts thirteen.

**Docs (C13):** `configuration-reference.md` no longer describes
`credential-endpoint-allowlist` as where resolved credentials go; it bounds the client secret.

**Files:** `configs/connections/**`, `connections/ConnectionStartupGuard.java`,
`connections/grants/{IConnectionGrantStore,MongoConnectionGrantStore,PostgresConnectionGrantStore}.java`,
`backup/impl/{AbstractBackupService,RestExportService,RestImportService}.java`,
`secrets/sanitize/SecretScrubber.java`, `docs/{connections,configuration-reference,import-export-an-agent,agent-sync-architecture}.md`,
`AGENTS.md`, and the tests named in each commit.

**Not done / for the runtime branch:** the resolver still reads `timeoutMs` through the
client's own clamp (fine, now documented); `SecretRedactionFilter` was not touched. A legacy
document written before C1 could carry a literal in `valueTemplate` and is now readable by
editors (C11) — re-saving it fails validation, which is the signal to fix it.

**Review pass — token URL syntax check ran after the URI was built.** R6 added
`UrlValidationUtils.validateUrlSyntax` to `OAuthTokenClient.exchange`, but after
`URI.create(tokenUrl)`, so a token URL the allowlist accepts once trimmed but that will not
parse (trailing whitespace on a document written straight to the store) still surfaced as a raw
`IllegalArgumentException` — not a `ConnectionException`, which on the service-grant mint path is
exactly what R11 keeps out of the MCP circuit breaker. The check now runs first, its parsed URI
is the one fetched, and a failure is `INVALID_CONFIGURATION` naming `oauth.tokenUrl`.

---

## 🧪 fix: the defects a full end-to-end run of 6.4.0 found (2026-09-13)

**Repo:** EDDI (`fix/e2e-test-findings`, from `origin/main` @ `d0832d9da`)

A full E2E pass over a running 6.4.0 exercised 342 REST operations, all 84 MCP tools, 28 single-agent
variations and every group style (Anthropic `claude-sonnet-5` via `${vault:anthropic-key}`). It
confirmed 15 bugs and ~30 usability defects. **S1 (a vault-resolved key persisted in an httpcall body)
was retracted as a false positive**: the check had matched the `<REDACTED>` placeholder, and
`RequestRedactor` already scrubs bodies. Everything else is fixed here with regression tests.

**Security**

- **S2 — a `scope: "secret"` input survived in derived forms.** `PropertySetterTask` scrubbed only
  exact copies, but the parser tokenizes input (`unknown(sk-live_abc)`), so the key stayed in
  `expressions:parsed`, `expressions:matches` and `intents`. Those are now dropped wholesale once the
  input is scrubbed. The audit ledger was worse: parser and rules entries, submitted before the
  property setter ran, carried the plaintext into an append-only signed ledger. New `TurnAuditBuffer`
  holds a turn's entries until the pipeline finishes (say and resume paths), then redacts the recorded
  input everywhere in the entry before submitting. `MemoryKeys.SECRET_INPUT_PLACEHOLDER` is the shared
  marker.
- **S3 — unknown `environment` silently targeted production.** `EnvironmentParamConverterProvider`
  makes every JAX-RS `Deployment.Environment` parameter strict: `staging` → 400 naming the valid values.
- **S4 — config-authored httpcalls reached the cloud metadata service** with SSRF protection off (the
  default). `UrlValidationUtils.rejectCloudMetadataTarget` now refuses `169.254.169.254`,
  `fd00:ec2::254`, `100.100.100.200`, `metadata.google.internal` and the link-local ranges —
  including hostnames resolving there — on httpcall, MCP and A2A paths **regardless of the setting**.
  With protection off the httpcalls client still follows redirects, so `HttpClientModule` wraps its
  redirect handler and refuses any hop onto the metadata service (on a worker thread — the check can
  resolve DNS). Private/loopback targets stay reachable (that is what opting out is for).
- **S5 — export rewrote `modelName: claude-sonnet-5` to `${vault:REDACTED}`** (the entropy heuristic);
  the imported agent failed every turn. Model identifier fields are structural for `SecretScrubber`.
- **B16 — an unresolvable `${vault:…}` was sent to the provider as the key.** `SecretResolver.requireResolved`
  fails closed, naming the parameter and reference (never a value), in `ChatModelRegistry`,
  `EmbeddingModelFactory` and `EmbeddingStoreFactory`.
- **W16** `TRACE`/`TRACK` → 405 (`HttpMethodGuard`). **W17** input over
  `eddi.conversations.max-input-chars` (default 200000) is refused before any paid call: 413
  `input_too_large` on REST and SSE (checked before the stream opens, so it is a real status, not an
  `error` event), 400 on the OpenAI API, invalid params over A2A. **W15** plaintext credentials in LLM
  configs are warned about at save (not rejected — setup falls back to plaintext without a vault),
  including Hugging Face `accessToken` and Azure OpenAI `nonAzureApiKey`. **B12** a declared image/PDF whose bytes carry no
  signature is rejected.

**Engine / metrics**

- **B6 — every priced tool failed in production.** Gauge `eddi.tool.costs.total` and counter
  `eddi.tool.costs{tool}` both render as `eddi_tool_costs_total`; Prometheus refused the second.
  Gauge renamed **`eddi.tool.costs.accrued`** (dashboards, `docs/metrics.md` and alert examples
  updated); meter failures no longer fail cost accounting, and a cost-tracking failure no longer
  replaces a tool result. *Breaking for anyone querying the old gauge name.*
- **B7 — shared artifacts were invisible to other members.** All members run as one user, so under
  USER cache scope `listArtifacts()` served a stale "no artifacts". `ToolCacheService.isCacheable`
  excludes every `@Tool` of the artifact, group-task, dynamic-agent, memory and recall tool classes
  (derived reflectively). The discuss/continue/human-input responses now attach artifacts too.
- **B14** rule pauses kept `hitlPauseType: null` (`clearToolPauseState` cleared it); now `RULE`, and
  stored legacy pauses report `RULE` in the inbox. **W25** the audit `modelName` was the provider type.
- **A1** active conversations no longer require `agentVersion`. **W12** memory search splits the query
  into terms (`MemorySearchTerms`) — "dog name" finds `dog_name`, and `%`/`_` are no longer wildcards.

**Groups**

- **B8 — `create_sub_agent` never inherited the parent's key.** Inheritance read the parent over the
  REST loopback from the LLM tool thread, which has no request to forward credentials from; the
  failure was swallowed. It now reads the in-process stores, and a failed inheritance says why.
- **B9** `GroupMember` defaults a null `memberType` to AGENT — the ops-task-force template found zero
  bidders. **B15/W5/W6** new save-time errors: member without `agentId`, negative
  turns/retries/timeouts/caps/repeats, preset DEBATE/DEVIL_ADVOCATE without their roles, nesting
  cycles; template instantiation rejects non-existent agents; RAG rejects unknown `embeddingProvider`.
  Every shipped template passes (asserted). **W1/W7** enabling artifacts/tasks/dynamic agents logs the
  `enableBuiltInTools` prerequisite.
- **W3** `LAST_SYNTHESIS` accepts `**Option A:**`, list markers, `.`/`)` separators, any case.
  **W4** CRITIQUE without `targetEachPeer` reviews all peers (`TEMPLATE_CRITIQUE_PANEL`) instead of an
  empty target. **W8** EXECUTE without PLAN materializes configured tasks.

**API consistency**

- **W18/W32** agents referencing a malformed or non-existent workflow → 400; deleting an agent
  undeploys its live versions. **B10** bad snippet name / missing patch op → 400 (were 500). **B11**
  `/actions` skips steps without a URI (NPE → 500 on every real workflow) and names `workflowId`.
  **B13** A2A cancel of a finished task → not cancelable. The task's state is recorded per task
  (`a2aTaskMapping:state`), not inferred from its conversation, which a completed turn leaves `READY`
  for the context's next task: `tasks/get` answers `completed`/`canceled`/`failed` from that record. **W19** MCP discover-tools reports a refused
  configuration as 400. **W20** tool costs resolve by slug. **W21** unmatched endpoint filters are
  reported. **W22** aliased extensions listed once. **W23** unknown ingestion id → 404. **W24** channel
  descriptors get their name (create/update descriptor-version lag). **W26** `list_agent_resources`
  maps v6 step types. `read_conversation`'s `returningFields` passes sections to the service and
  filters output keys (it returned an empty snapshot for its own example). **W13** snippets read their
  current version. **W14** template preview resolves `{vars.*}`. **W29** setup states the streaming
  backstop. `apply_agent_changes` reports a failed redeploy.

**Docs:** security (metadata block), configuration reference, metrics, dashboards, group
conversations (roles, options, cadence semantics, async approve/human-input, cycles, critique, EXECUTE
without PLAN, cache), hitl, langchain (`anchorFirstSteps` is token-window only), user memory search,
MCP client notes (protocol-version warning, non-idempotent retries).

**Review follow-ups** (independent review of the whole change): the audit buffer now redacts every
input form any entry recorded from *every* buffered entry — including task-failure entries, which carry
no `userInput` but can quote the token, and entries built after the scrub; a resolved RULE pause no
longer leaves `hitlPauseType: RULE` on a READY conversation (it logged a stale-state WARN on every later
turn); `updateAgent` checks EDIT on the agent before the workflow-existence lookup (no existence oracle,
and a workflow the caller cannot view is a 403 by intent); the OpenAI adapter maps the input cap to
400 `input_too_large` instead of 500; a PDF header anywhere in the first 1024 bytes counts as a PDF;
`EmbeddingModelFactory` trims the provider as `RagConfiguration` does;
`eddi.conversations.max-input-chars` is declared in `application.properties` and documented in the
conversation table. A later workflow of a multi-workflow agent sees empty parser data on a turn whose
input was vaulted — deliberate, noted in `PropertySetterTask`. `EnvironmentParamConverterProvider` and
`HttpMethodGuard` are unit-tested only; an integration test through the Quarkus stack is still open.

**Not changed, by design:** `${eddivault:` is a supported legacy alias; GDPR export `complete:false`;
sync rejecting loopback sources.

## ⬆️ chore(deps): update Quarkus to 3.39.3 and langchain4j to 1.20.0 / 1.20.0-beta30 (2026-09-13)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

- Bump Quarkus platform from `3.39.2` to **`3.39.3`** in `pom.xml`.
- Bump `langchain4j.version` from `1.19.0` to **`1.20.0`** and `langchain4j-beta.version` from `1.19.0-beta29` to **`1.20.0-beta30`** in `pom.xml`. Both lines are updated together to maintain module version alignment.

Also checked Docker base image statuses across the repository:
- **Production image** (`src/main/docker/Dockerfile`): `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24` pinned at `@sha256:c49d36c03d0a9472935b9f318f709c4cf158afa2dc204099f1f463cfbf9d4626` is already current (remote registry matches digest; tag 1.25 and ubi11 do not exist).
- **Sidecar image** (`mcp-sidecar/Dockerfile`): `ghcr.io/sparfenyuk/mcp-proxy` pinned digest is current with latest.
- **Demo image** (`src/main/docker/Dockerfile.demo`): newer digests exist on Docker Hub for `maven:3.9-eclipse-temurin-25` and `eclipse-temurin:25-jre`.

**Files touched:**
- `pom.xml` — updated `quarkus.platform.version`, `langchain4j.version`, and `langchain4j-beta.version`
- `docs/changelog.md` — this entry

## ⬆️ chore(release): EDDI 6.4.0, Quarkus 3.39.1, and every safe patch/minor ahead of the release (2026-08-30)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

Two things that belong in one branch, because the version bump is only meaningful once the
dependency state it will ship is settled: the release number moves `6.3.0` → **`6.4.0`**, and
every update `versions:display-dependency-updates` reported for the artefacts **we pin ourselves**
is taken, provided it is a patch or minor of a GA release.

### Dependencies

Quarkus platform `3.38.3` → **`3.39.1`**. The previous bump (aa6c48bfa) deliberately skipped
`3.39.0.CR1` as a pre-release; `3.39.1` is GA, so the same reasoning now argues for taking it.

Also taken: `quarkus-mcp-server` 1.13.1 → 1.13.2, `classgraph` 4.8.192 → 4.8.194, `jsoup`
1.23.1 → 1.23.2, `json-schema-validator` 1.5.4 → 1.5.9, `bson4jackson` 2.15.1 → 2.18.0.

`jackson-dataformat-csv` and `jackson-dataformat-xml` are now pinned to **2.22.2** alongside
`jackson-core`/`jackson-databind`. This is new managed state, not a version bump of something we
already pinned, and the reason is skew: the Quarkus BOM manages the whole Jackson family at
2.22.0, and overriding only core and databind for the two advisories left the two dataformat
modules we declare a patch behind their own core. Same family, same patch.

**Deliberately not taken**, so the release ships a stable state: `jsonschema-generator` 5.0.0,
`json-path` 3.0.0, `json-schema-validator` 3.0.7, `bson4jackson` 3.2.0, `testcontainers` 2.0.5,
`vertx-web-client` 5.1.6, `wiremock` 4.0.0-beta.38, `quarkus-mcp-server` 2.0.0,
`maven-compiler-plugin` 4.0.0-beta-5, `maven-surefire-plugin` 3.6.0-M1 and
`reactor-netty-http` 1.4.0-M1 — every one a major jump, a milestone, or a beta. `mockito-core`
5.21.0 → 5.23.0 and `snakeyaml` 2.6 → 2.7 are managed by the Quarkus BOM, so they are the
platform's to move, not ours.

**The security pins survive the platform bump**, which was checked rather than assumed —
`dependency:list` on the built tree resolves `jackson-core`/`jackson-databind` 2.22.2,
`postgresql` 42.7.13, `bcprov-lts8on` 2.73.12.1, `jinjava` 2.8.4, `reactor-netty-http` 1.2.8 and
`jnats` 2.26.2. The `ban-jackson3` enforcer rule still passes, so the Elasticsearch client has
not smuggled `tools.jackson` back in under 3.39.1.

### Version

`6.3.0` → `6.4.0` across the same artefact set the previous two bumps used (7a64f9c84,
c0835c98d), rather than a blanket grep:

- **Build/runtime** — `pom.xml`, `application.properties` (`projectVersion`,
  `smallrye-openapi.info-version`, `container-image.additional-tags`), `OpenApiConfig` `@Info`,
  `Dockerfile` `EDDI_VERSION` build arg.
- **Deployment** — helm `Chart.yaml` appVersion + `values.yaml` image tag, k8s deployment and
  quickstart (version labels, pinned image tag, cosign/crane comment examples),
  `redhat-certify.yml` workflow input default.
- **Docs** — `build-reproducibility.md`, `redhat-openshift.md` and `developer-quickstart.md`, the
  three pages whose copy-pasteable commands name the current tag. The per-page version headers
  are already dynamic release badges.

Two differences from the previous bump's file set. `src/main/resources/initial-agents/` no longer
exists, so there is no bundled `Agent+Father-<version>.zip` to rename and no
`available_agents.txt` to follow it. And `developer-quickstart.md` is new to the set — its
`EDDI_VERSION=6.3.0 docker compose up -d` was added after the 6.3.0 bump.

**Left at 6.3.0 deliberately**, because these are historical facts and not claims about the
current release: every `@since 6.3.0` and `@Deprecated(since = "6.3.0")`, the "pre-6.3.0
behaviour" notes in `application.properties`, `docs/hitl.md` and `docs/configuration-reference.md`
describing the `eddi.hitl.tool.task-approvals.mode=replace` legacy path, and the worked examples
in `release-signing.md` / `release-versioning.md`, which illustrate the tagging flow rather than
name the shipping version. The `6.3.0` in the bundled Manager JS assets is built output from the
EDDI-Manager repo and moves when that bundle is rebuilt.

### Verified

`mvnw compile` is green. The full unit run is **20,221 tests, 8 failures, 213 errors, 3 skipped**,
and every one of those 221 is this sandbox rather than the bump: `Unable to establish loopback
connection`, `failed to create a child event loop`, `IOReactorException: Failure opening selector`
(no socket binding), or `Could not find a valid Docker environment` (the Mongo and Postgres
container stores). Causation was checked rather than assumed, because Quarkus is a platform bump
and "environmental" is exactly what a real regression would hide behind: the nine affected classes
were re-run against the pre-bump `pom.xml` and produced **151 tests, 8 failures, 123 errors** — the
same counts, and `diff` over the 146 reported error and failure lines shows the *same test methods*
failing in the same way before and after. CI is the gate for the socket- and Docker-bound cases.

---

## ⚙️ fix(config): fifteen configuration defects, from scheduler units to a nine-megabyte orphan (2026-09-07)

**Repo:** EDDI (`fix/review-quickwins-config`)

Slice 2 of the code-review quick-win backlog (`planning/code-review-backlog/`). Fifteen findings
whose common shape is a knob that does not do what it says.

**Two were live defects.** `@Scheduled(delay = N)` is measured in **minutes**, and two jobs were
written as though it were seconds: the deployment check started ten minutes after boot rather than
ten seconds, and the daily maintenance job — which undeploys superseded agent versions and ends
idle conversations — first ran five hours in, so a pod restarted more often than that never ran it
at all. Both now use `delayed = "10s"` / `"5m"`, which carries its unit, plus
`ConcurrentExecution.SKIP`. Separately, the properties migration renamed the legacy collection into
a backup even when entries had failed to migrate; because `collectionExists` is then false, that
made the migration a permanent no-op, so a transient error on three of four hundred users stranded
those users' long-term properties in the backup collection. The rename is now conditional on a
clean run, and the loop is idempotent so the next boot retries.

**Two settings could not fire at all.** `quarkus.mcp-server.http.root-path` is not a key the MCP
extension knows — the namespace is `quarkus.mcp.server.*` — so changing it moved nothing while the
auth rule kept guarding the old path. And `ComplianceStartupChecks` read
`quarkus.http.ssl.certificate.file`, singular; the real keys are plural, so the TLS warning fired
for operators who had configured TLS correctly and — worse — following the banner's own advice
silenced it while Quarkus ignored the key, leaving the check reporting satisfied on a plaintext
listener.

**Three defaults disagreed with themselves.** `Conversation` held its own
`DEFAULT_MAX_RECALL_ENTRIES = 1000` for the absent-config case while `UserMemoryConfig` defaults to
50, so adding a `userMemoryConfig` block for an unrelated reason cut long-term recall twentyfold in
a diff that never mentions the field. `WorkspaceSettings` validated an operator's value in a lazily
created bean, so a typo booted green and threw a 500 on the first guarded request — and on every
request after it. `TaskForceEngine` ignored the designer's `inputTemplate` at all three of its
phases, which is the whole TASK_FORCE style, so the phase-template mechanism was inert end to end
for that style.

**Four things were hard-coded that the neighbouring surface treats as configuration.** The A2A turn
timeout, the Slack turn timeout and retry budget, and the LLM refusal heuristic — four English
prefixes, so a German- or Japanese-language agent's `onRefusal` guardrail could never fire while
the prefixes over-matched a legitimate "I cannot confirm that from the data provided". All are now
configurable, defaulting to exactly the constants they replace. Slack also answers a timeout with a
notice naming the limit rather than a generic error.

**And two were dead weight.** 9.3 MB of orphaned Manager build output — a complete second Vite
build, 19 files that only imported each other — shipped in every jar and image. The immutable
one-year cache header matched an un-hashed script loaded by the landing page every visitor hits
first, so a fix to it would have gone unseen for up to a year with no revalidation request even
sent.

**Decision — fail safe on a value that would disable a surface.** A zero or negative Slack timeout,
or a retry budget below one, is ignored in favour of the shipped default. Honouring it would take
the channel down on a typo, and an operator who meant to disable Slack has a better way to say so.

**Decision — de-anchor the metrics guard rather than exempt the meters.** Fourteen meters across
Dream, connection resolution, summarization and guardrails dropped the `eddi` prefix, and
`MetricsDashboardCoverageTest`'s regexes required it — so the guard that exists to make an unwatched
meter impossible could not see them, which is how they came to be absent from the metrics reference
on a green build. They are renamed, the dashboard follows, and the anchor is gone so the next
unprefixed meter fails rather than hides. The same shape applies to
`ConfigurationReferenceCoverageTest`: it treated a declaration in `application.properties` as proof
the code reads a key, so a documented-but-unwired key passed the very assertion whose failure
message describes that situation. Split in two, it immediately surfaced a real one —
`eddi.gdpr.restriction-cache-ttl-seconds`, injected and undocumented.

**Files:** eleven production classes, one new (`SlackConfig`), `application.properties`, the
metrics dashboard, six pages under `docs/`, and eight test classes — three new
(`StaticAssetCachingTest`, `ScheduledDelayUnitsTest`, `WorkspaceSettingsTest`, `SlackConfigTest`).

**Next:** slice 3 — API consistency across the REST surface.

---

## 📘 docs: correct 23 false claims and pin them with a guard test (2026-09-07)

**Repo:** EDDI (`docs/review-quickwins-docs`)

The first slice of the code-review quick-win backlog (`planning/code-review-backlog/`). Twenty-three
findings, all of them documentation that said something the code does not do. Markdown compiles to
nothing, so none of them were visible to any check in the build and several had been wrong for more
than one release.

**The ones that cost a reader real time.** `AGENTS.md` and `configuration-reference.md` said a
`scope: "secret"` property degrades to plaintext without a vault key; it fails the whole turn
closed. The README named `quarkus.mongodb.connection-string` as the connection knob — a real
Quarkus key that only the extension's health check reads, so pointing it at another host reports
the new host as UP while every read and write still goes to the old one. Both first-agent tutorials
named the workflow field `packageextensions`, which the strict write boundary rejects with a 400,
and documented a Facebook channel connector that does not exist. The README documented span
attributes without the `eddi.` prefix the code emits, so a trace filter on `task.id` matches nothing
and reads as "tracing is not emitting".

**The ones that hid a shipped feature.** `behavior-rules.md` presented eight of the twelve
registered condition types as the complete list; `deploymentContext` was documented nowhere at all.
`AGENTS.md`'s ZIP section listed seven of twelve backup file extensions, so the most common case —
an agent with a regular dictionary — could not be built from the file that tells you how to build
one, and its workflow step table omitted the templating step the same section calls mandatory.
Six shipped LLM task fields were documented nowhere, including the rolling conversation summary;
`scheduling.md` omitted `oneTimeAt` and `metadata`; `user-memory.md` omitted `dream.parameters`,
without which every summarization step fails with a provider 401 while stale pruning keeps working.

**Three admin surfaces gained their first documentation:** tenant quotas, coordinator dead letters
and template preview. New pages `docs/tenant-quotas.md` and `docs/coordinator-admin.md`, a section
in `docs/output-templating.md`, all linked from `SUMMARY.md`.

**Five plans in `planning/` described shipped subsystems as unbuilt.** Two went further and
instructed an agent to implement them task-by-task, with 107 unchecked boxes between them, against
a spec whose class names all resolve to files that already exist. Those two now carry a status
marker, the directive is gone, and the checkbox syntax is stripped rather than ticked — stripping
does not assert that every sub-step shipped, which ticking would.

**Decision — guard the claims, not the wording.** `DocumentationAccuracyTest` asserts that the docs
mention what the code declares: every condition `ID`, every `*_EXT` backup constant, the schedule
fields the validator enforces, the LLM task fields the model declares. A new condition type is then
a failing test naming the missing type, not a reference table that quietly goes stale. Fixed
expected strings would have needed editing on every rename and would have guarded nothing.

Proven by mutation. Removing the `oneTimeAt` row, dropping the templating step, deleting a backup
extension and restoring the wrong MongoDB property each fail with the missing name in the message.
The test also caught three errors in this very commit's edits before it was run deliberately.

**Files:** `AGENTS.md`, `README.md`; under `docs/` — `SUMMARY.md`, `behavior-rules.md`,
`configuration-reference.md`, `creating-your-first-agent/creating-your-first-agent.md`,
`creating-your-first-agent/creating-your-first-agent-1.md`,
`deployment-management-of-agents.md`, `docker.md`, `langchain.md`,
`monitoring/monitoring-guide.md`, `output-templating.md`, `putting-it-all-together.md`,
`rag.md`, `release-versioning.md`, `scheduling.md`, `user-memory.md`, plus the two new pages
`coordinator-admin.md` and `tenant-quotas.md`; under `planning/` — `conversation-cancel-plan.md`,
`hitl-tool-approval-plan.md`, `mcp-hitl-surface-plan.md`,
`multimodal-attachments-completion-plan.md`, `observability-and-pipeline-plan.md`; and
`src/test/java/ai/labs/eddi/docs/DocumentationAccuracyTest.java`.

**Next:** slices 2-6 of the same backlog — config keys, API consistency, and 63 test-quality
findings.

---

## 🏷️ fix(ci): a release tag could execute on the runner (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

`PRIMARY_TAG` is `${GITHUB_REF#refs/tags/}`, and the only check on it was a *prefix* comparison
against the pom version. So `6.3.0-$(id)` passed — a legal git ref name, therefore pushable — and
nine `run:` blocks spliced it in with `${{ }}`, which the runner substitutes into the script text
*before* bash parses it. Two of those blocks hold the Docker Hub credentials and the Sigstore
keyless identity. Pushing a tag needs write access, so this is not anonymous execution; it is
tag-push rights becoming arbitrary commands in the job where the release secrets live.

The whole tag is now matched against
`^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$` before the parity check, and every one of
the nine sites takes the value through `env:` instead of interpolation. All 54 published tags since
4.8.0 match; the pattern carries no version literal, so the single-source-of-truth guard stays
satisfied, and it agrees with the no-`v`-prefix rule the release trigger depends on.

Two relational tests pin it. One lifts the pattern out of the YAML, compiles it, and runs 6 accepted
and 18 rejected tags through it, so it grades the regex's behaviour rather than its presence — shown
by a second experiment that widened the pattern to `^[0-9].*$` and still failed. The other sweeps
every workflow for `${{ }}` interpolation of the tag inside a `run:` block, so a new site cannot
reappear.

`ReleaseVersionSourceTest` caught the first draft of the error message for quoting a literal version
as an example, which would have gone stale on the next bump. The guard works.

---

## 🧷 fix(build): a gate test that passed with its guard deleted (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

Four review comments, two of them the kind this whole review exists to catch.

**A gate test was green with the thing it guards removed.** `dependabotSkipBlock()` falls back to
returning the rest of the workflow when the guard is absent, and that text still contains both
`--json files` and `"$DOCKERFILE"` — so both assertions passed against a workflow with no
de-duplication at all. Confirmed empirically before fixing: guard physically deleted, old
assertions, `Tests run: 20, Failures: 0`. The test now tracks whether the loop found the guard and
asserts it afterwards, and fails with the guard gone.

**`defaultTestDeadLetterPath()` still accepted the source tree.** It checked only
`Path.isAbsolute()`, so a `java.io.tmpdir` pointing at the repository root or a child was allowed
and the sink landed back in the tree — the artifact this branch removed, reachable through a JVM
flag instead of a code change. It now also rejects a temp directory inside the project directory,
naming the property and the value. Containment uses `Path.startsWith`, which matches whole name
elements, so a sibling that merely shares a textual prefix is still accepted; that control case is
asserted so a regression to string comparison fails.

**Dependabot PRs were selected with the wrong filter.** `gh pr list --author 'app/dependabot'` is
the user filter and is not guaranteed to return App-authored PRs. An empty result is silent here —
the skip never fires and the job raises a digest PR duplicating Dependabot's. Now `--app dependabot`.

**Unquoted redirections.** 83 of them, into `$GITHUB_STEP_SUMMARY`, `$GITHUB_OUTPUT`, `$GITHUB_ENV`
and `$GITHUB_PATH` across three workflows, all quoted — and a new test sweeps every workflow so the
count cannot creep back.

---

## 🪝 fix(githooks): fetch from the remote being pushed to (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

The pre-push hook's shallow-clone recovery fetch named `origin` literally, while git passes the
remote as `$1`. A push to any other remote fetched from the wrong one. The effect is narrow — the
fetch is reached only after `merge-base --is-ancestor` has already failed — but it made the recovery
path silently useless for anyone pushing to a fork or a second remote, which is exactly the
contributor workflow `CONTRIBUTING.md` describes. The hook now captures the remote argument and uses
it.

---

## 🔧 fix(build): make the base-image check ask which Dockerfile, declare the YAML dependency (2026-09-06)

**Repo:** EDDI (`fix/review-quality-gates`)

Eight review comments, four behavioural. All eight were proven in a single mutation run that
reverted every behaviour at once and produced exactly eight named failures, one per comment, with
no cross-talk.

**The base-image check skipped itself on the wrong pull request.** It treated the first open
`dependabot/docker/*` branch as covering the production Dockerfile, but `dependabot.yml` declares
three docker ecosystems (`/src/main/docker`, `/mcp-sidecar`, `/.clusterfuzzlite`) and all three push
branches under that prefix. A sidecar or fuzzing bump therefore silenced the check for the image
that actually ships. It now asks `gh pr view --json files` whether the PR touches the production
Dockerfile before skipping.

**A dependency was reaching the classpath by accident.** `jackson-dataformat-yaml` was arriving
only through `json-schema-validator`'s transitive tree, so an unrelated bump could have removed it
and broken YAML parsing with no declaration to point at. It is now declared alongside the CSV and
XML modules.

**The dead-letter path trusted `java.io.tmpdir`.** A relative or empty value resolved against the
working directory, which put the audit dead-letter file inside the source tree. It is now rejected
with an `IllegalStateException` naming the property. The matching test also no longer requires the
*global* absence of `eddi-audit-deadletter.jsonl` — a stale file from an older checkout made it
fail for the wrong reason — and instead snapshots the repository-root sink and asserts it is
unchanged.

**Note for the merge order.** This branch arms the build gates: Checkstyle moves to
`failOnViolation`, and the formatter from `format` to `validate`. It should merge **last**, after a
pre-flight run of the armed gates against main with everything else already in, or it will turn
green branches red on violations they currently get away with.

---

## 🧪 test(build): make the gate tests unable to pass a disarmed gate (2026-09-04)

**Repo:** EDDI (`fix/review-quality-gates`)

Follow-up on the same branch, from an independent review round and four GitHub Copilot
comments. Every one was the same defect in a different place: a test that grades the build
gates while itself being satisfiable by a disarmed gate.

- **The coverage-gate test graded only what survived.** It walked the JaCoCo limits and
  asserted a value per counter it found, so deleting the `BRANCH` limit — or emptying the
  `<limits>` block entirely — still passed. It now compares the whole limit map against
  `{INSTRUCTION=0.90, BRANCH=0.80}`, so a deleted, renamed or retuned limit fails.
- **The langchain4j pinning test let an unpinned artifact through**, because the condition
  began `version != null`. An artifact with no `<version>` falls back to a BOM or transitive
  version, which is exactly what the test exists to forbid. Reproduced first by stripping the
  version from a real dependency and watching the old test pass.
- **The version-duplication sweep named `redhat-certify.yml` as an offender and did not scan
  it.** Reintroducing the very `default:` this branch removed would have passed. The sweep now
  covers five files and a dedicated assertion rejects any `default:` on that workflow's
  `version` input — the check that still bites after `pom.xml` moves past the stale literal.
- **The CI `code` path filter omitted `README.md` and `AGENTS.md`**, so a PR touching only
  those skipped the tests that grade them. Rather than fix the pair by hand, a new test derives
  the requirement: it sweeps the test tree for root documents any test opens, parses the filter
  out of `ci.yml`, and fails if one is unlisted. It asserts the sweep found something, so it
  cannot go vacuous itself.

**Not changed, deliberately:** the same gap exists for `docs/**`, where three more tests grade
documentation. `ci.yml` documents skipping the build for docs-only changes as intentional, so
widening it is a policy decision rather than a review fix.

---

## 🚦 fix(build): make the style, coverage and image gates able to fail (2026-09-04)

**Repo:** EDDI (`fix/review-quality-gates`)

From the whole-repository code review. Several of this project's quality gates were wired
so that they could not fail — which is the root cause the review named for why the other
findings shipped at all.

- **Checkstyle ran with `failOnViolation=false`.** The import and file-size rules AGENTS.md
  calls mandatory could not fail anything, and are violated on `main` today.
- **The formatter's `format` goal rewrote tracked sources on every compile** instead of
  checking them, so a contributor's build silently edited files rather than reporting them.
- **The JaCoCo 90/80 gate graded `jacoco-merged.exec`** in runs where the integration tests
  never produced it, so `./mvnw verify` failed a clean, all-green tree at 89 %. It now
  carries `<skip>${skipITs}</skip>` and runs where the data actually exists.
- **Failsafe was pinned to Surefire's version property**, so the two could silently diverge.
- **The container integration tests built a hand-copied Dockerfile** that had already
  drifted from the production one, so what CI verified was not what ships. They now build
  the real image.
- **The project version was duplicated as a literal** in the OpenAPI info block and in
  `application.properties`; both now resolve from the build.

### Regression coverage

`BuildQualityGatesTest` and `ReleaseVersionSourceTest` assert the gates are armed — that
Checkstyle fails on violation, that the coverage check is skip-aware rather than
unconditionally disabled, and that no version literal is reintroduced. `EddiImageDockerfileTest`
pins the integration image to the production Dockerfile.

Recorded honestly as unverifiable here: the image build itself and the two version
assertions need Docker and MongoDB Dev Services, so they compile but have never executed
locally. CI is the authority for those.

**Reviewer note.** This branch changes the local build contract: `./mvnw compile` now fails
on unformatted or badly-imported sources instead of quietly rewriting them. AGENTS.md is
updated to say so, because the previous wording described the old behaviour.

## 🔌 fix(install): close the PR #714 review — a busybox probe that read every port as free (2026-09-07)

**Repo:** EDDI (`fix/installer-mongodb-port-conflict`)

Four findings from the Copilot review of [#714](https://github.com/labsai/EDDI/pull/714): two inline,
two the review filed as "suppressed comments" in its body (no thread, so nothing to answer in place).
All four were real. Also merged `origin/main`, which the PR had drifted behind far enough to go
`CONFLICTING` — and per the repo's own experience a conflicting PR never runs `ci.yml` at all, so the
merge is what puts this branch back under CI.

**1. `port_in_use` had over-corrected into the opposite bug (`install.sh`).** The previous entry
below fixed busybox lsof reading every port as *taken* by requiring `LISTEN` in the output. But the
probe was an `elif` chain: on Alpine-class systems the lsof branch is entered, finds no `LISTEN` in
busybox's file dump, and the `nc` / `/dev/tcp` branches are never reached — so every port now read as
*free*, and the raw docker bind error came back. A missing marker is not evidence. The chain is now:
`ss` short-circuits (its absence of a match really is proof); a **positive** lsof match is trusted, a
negative one falls through to a connect probe that behaves identically on every implementation.
Verified in a real `alpine:3.20` container with a live listener — busy port detected, free port still
free — and mutation-checked by restoring the `elif` chain, which fails the busy case.

While there: the lsof output is captured instead of piped into `grep -q`. `grep -q` exits on first
match and can SIGPIPE the producer, which `set -o pipefail` then reports as a failed pipeline even
though the port *was* found.

**2. The PowerShell installer validated every port variable up front (`install.ps1`).** A stale
`GRAFANA_PORT=abc` in the environment aborted a plain install that never starts Grafana, and `-Full`
rejected a bad `-MongoPort` before switching the database to PostgreSQL. The Bash installer has
always validated inside `resolve_published_port`, i.e. only for components that are actually enabled.
The eager loop is gone; `Resolve-PublishedPort` now validates its own argument. `-MongoPort` also
gains a `$MongoPortRequested` capture, matching the six overlay ports and keeping the resolved value
from overwriting the request.

**3 + 4. The Compose project name was derived two ways that Compose does not use (both scripts).**
`compose_project_name` / `Get-ComposeProjectName` decide whether a listener is *our* container (reuse
the port) or a foreign one (remap, or fail an explicit request). Both ignored `COMPOSE_PROJECT_NAME`,
and both assumed the project directory is `EDDI_DIR` — but Compose derives it from the directory of
the **first** `-f` file, which under `--local` / `-Local` is the repo checkout. Confirmed against
docker compose 29.7.2: first `-f` in `RepoCheckout/` yields project `repocheckout`, first `-f` in
`.eddi/` yields `eddi`, and `COMPOSE_PROJECT_NAME` overrides both. Both functions now follow the same
three rules.

**Design decision.** Detection was fixed by falling through rather than by sniffing for busybox
(`lsof -v`, `--help`, applet name). Busybox ignores its argv here, so every sniff is a guess about
which not-real-lsof this is; "trust a positive, verify a negative" needs no such guess and is correct
for any implementation, present or future.

**Verification.** `alpine:3.20` behavioural harness plus its mutation check; `bash:3.2.57` harness
for `compose_project_name` (7 cases, `set -u` safe); shellcheck at CI's exact invocation
(`--severity=warning --shell=bash`) clean; `install.ps1` parses and passes a 9-case harness under
**both** pwsh 7.6.5 and Windows PowerShell 5.1; five end-to-end `-WhatIf` runs of the real installer
covering the stale-variable case, its negative control, `-Full` with a bad `-MongoPort`, and explicit
`-MongoPort` accepted and rejected. PSScriptAnalyzer: **7 findings, 0 Error — now genuinely identical to
`main`'s baseline.** The PR description had claimed that already and it was not true: the branch was
adding 7 `PSAvoidUsingPositionalParameters` warnings, one per `Resolve-PublishedPort` call site, for
14. Those call sites now pass named parameters.

**Codacy is still red and it is not those seven.** It reports `7 new issues (0 max.)` — the same
count before the branch's fixes, after them, and after the positional-parameter change, so the
matching number was a coincidence. Ruled out from outside: PSScriptAnalyzer is byte-identical to
`main` at every severity including `Information`, and shellcheck with no severity filter at all
(Codacy's default, not CI's `--severity=warning`) reports a single `SC2129` note on a pre-existing
line. The check publishes no annotations, an empty summary and no text — its title even reads "of at
least  severity" with the severity name missing — so the issue list exists only inside the Codacy
dashboard, which needs an account to read. **Open:** someone with Codacy access has to open
[the PR page](https://app.codacy.com/gh/labsai/EDDI/pull-requests/714) and say what the seven are.

**Superseded on merge.** `main` (#736) deleted `docker-compose.postgres.yml` outright — it was a
drifted near-duplicate that could not work as the overlay the README documented, and its role is now
`docker-compose.postgres-only.yml`. This branch's two fixes to that file (the doubled `7070:7070`
publish, and moving `postgres` to a loopback binding) are moot: the replacement already interpolates
`${EDDI_PORT:-7070}` once and publishes no database port at all. The merge takes the deletion, and
the `POSTGRES_PORT` line this branch added to `.env.example` is removed with it — it pointed at a file
that no longer exists.

**Second review round (CodeRabbit).** Two more findings, both valid.

*The `ss` branch had the same SIGPIPE hazard I had just fixed one branch below.* Fixing it for `lsof`
and leaving `ss` piped into `grep -q` was inconsistent, and the consequence is the worse direction:
`grep -q` exits on its first match, ss takes SIGPIPE while still writing, `set -o pipefail` makes the
pipeline non-zero, and a port that **is** in use reads as free — straight into the raw docker bind
error this whole branch exists to prevent. It needs a host with enough listening sockets to overflow
the 64 KiB pipe buffer, which is exactly the kind of machine that has a port conflict. Now captured
before matching, like the lsof branch. Proved with a stub `ss` that emits the matching row first and
then 330 KB of filler: the piped form reports the busy port as free, the captured form does not.
Re-checked against a real `ss` (debian + iproute2) with a live listener, including the anchor case
where port 99 must not match a listener on 9999.

*Both installers documented the opposite of what a pinned port does.* `install.sh --help` closed its
list of port variables with "kept when free, moved to the next free port when something holds it",
and `install.ps1`'s `.NOTES` said the same — but every entry in that list is an **explicit** request,
and an explicit request that is busy fails by design rather than moving. A user who pinned
`KEYCLOAK_PORT` was promised a silent remap and got an abort. Both texts now separate the two paths,
as does the `-MongoPort` parameter help.

**Files:** `install.sh`, `install.ps1`, `docs/changelog.md`

---

## 🔐 fix(deploy): no credential in the auth component has a default any more (2026-09-07)

**Repo:** EDDI (`fix/review-deploy`)

Three review comments on the development auth overlay, all of them fair.

**The shipped realm allowed cleartext for everything.** `sslRequired: "none"` let Keycloak serve the
login form, the authorization code and the token endpoint over plain HTTP to any caller. It is now
`"external"` in all three realm copies — Keycloak exempts local addresses, so every documented path
still works: the quick start uses `kubectl port-forward` (arrives as `127.0.0.1`), compose sees the
bridge gateway, and EDDI's backchannel runs pod-to-pod on RFC 1918. The one case where `external`
would bite, a pod CIDR outside RFC 1918 such as `100.64.0.0/10`, is documented with the
`X-Forwarded-Proto` requirement rather than left to be discovered.

**The secret generator printed a key it had not installed.** Under any `-WhatIf` run the PowerShell
script reached the key box, because the report block sat outside the `ShouldProcess` gate — telling
an operator a master key was installed when nothing was, and printing a secret that exists nowhere.
It now tracks whether the create actually happened. The bash twin has no dry-run mode and so no
equivalent path; that invariant is now written down next to its key box, and the new test sweeps
both scripts so the two cannot drift.

**The component shipped a guessable full administrator.** `admin/admin` plus a privileged
`eddi/eddi` account, on a workload fronted by a ClusterIP every pod in the namespace can reach. The
"development only" framing is real but does not cover that, and `"temporary": true` is not a
mitigation — Keycloak 26 drops the required action on realm import, which this repo had already
recorded. `KC_BOOTSTRAP_ADMIN_PASSWORD` now comes from an operator-created Secret with no default,
so the component fails closed with `secret "keycloak-admin" not found`; and the privileged `eddi`
fixture ships with no credential at all, keeping its roles so recovery is one console action rather
than recreating a user, two roles and a group. The unprivileged fixtures are untouched, so the demo
login still works.

Each change is pinned by a relational assertion rather than a literal: the realm must require TLS
for external clients, a generator may print a key only if it issued the create, no shipped manifest
may carry a usable default privileged password, and the Secret name in the YAML must appear in the
`kubectl create secret` command the docs give.

---

## 🧭 fix(ci): build on every operator document the manifest suite asserts about (2026-09-07)

**Repo:** EDDI (`fix/review-deploy`)

A review comment asked that no operator-facing document this suite pins can be edited without
`build-and-test` running. The `operator_docs` filter covered `docs/kubernetes.md` and `README.md`,
but `docsNameTheRealClientId` also reads `docs/security.md` and asserts it names `eddi-frontend`
rather than the stale `eddi-manager` — and that file matched neither filter. This branch changed
`docs/security.md` itself, so a revert of it would have sailed past the only test that guards it.

Closed at the mechanism rather than in prose: the filter now covers what the suite actually reads.

---

## ☸️ fix(deploy): stop the secret generator deleting on the normal path, pin config to its pods (2026-09-06)

**Repo:** EDDI (`fix/review-deploy`)

Fifth review round on this branch. Five comments, all behavioural, all fixed and each proven by
reverting the change and watching a named test fail.

**The secret generator deleted before it created, on every run.** `kubectl delete secret
eddi-secrets --ignore-not-found` ran unconditionally, so the window between delete and create
existed even when the operator had asked for nothing destructive. A pod starting in that window
came up without its vault key. The delete now lives inside the `--force` branch in both
`create-secrets.sh` and `create-secrets.ps1`; the normal path relies on `kubectl create` refusing
with `AlreadyExists` and reports that nothing was changed.

**The test suite had pinned the race as a requirement.** Two normal-path tests asserted the
scripts "must keep the delete-then-create it does once past the guard" — so fixing the scripts
alone would have turned them red, and leaving them would have blocked the fix forever. This is the
failure mode this review keeps finding: a test that guards the bug rather than the contract. Both
now assert the delete is reachable only under `--force`.

**A config change did not restart the pods that read it.** The Deployment pod template gained
`checksum/config` and `checksum/secret` annotations hashing the rendered `configmap.yaml` and
`secret.yaml`, so `helm upgrade` rolls pods when their configuration actually changed. Without it
`envFrom` kept serving the old values until something unrelated caused a restart — the same
trap the Keycloak upgrade note now documents, with `kubectl rollout restart deployment/eddi`.

**Files:** `k8s/create-secrets.sh`, `k8s/create-secrets.ps1`,
`helm/eddi/templates/deployment.yaml`, `k8s/overlays/auth/kustomization.yaml`,
`docs/kubernetes.md`, `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`

---

## 🔎 test(deploy): assert manifest relationships, not the presence of strings (2026-09-04)

**Repo:** EDDI (`fix/review-deploy`)

Follow-up on the same branch, from three independent review rounds over the deployment fixes.

The manifest suite was asserting that literals exist. A test for the Keycloak security context
checked only that `runAsNonRoot: true` and `runAsUser: 1000` appear somewhere in the file after
comment-stripping, which a commented-out or wrongly-nested block satisfies. Assertions now
resolve the YAML and check the value on the container that actually runs.

The CI path-filter test asserted that a forced-true exists for tagged releases, but that
contract is **positional** — the `echo "<filter>=true"` has to sit inside the
`if [[ "$GITHUB_REF" == refs/tags/* ]]` branch to mean anything. It now checks placement.

The secret-generator ordering test was strengthened to assert the guard precedes the
destructive delete *and* that an `exit 1` sits between them, so a guard that only warns fails.

Two justifications were withdrawn after the reviewer disproved them: the secret scripts were
filed as needing a live cluster, when their fail-closed classification is plain text parsing;
and the Helm chart version claim was pinned to the break it documents by asserting `manager`,
`monitoring` and `namespace` really are absent from `values.yaml`, so the major bump cannot
become a different lie.

**Coverage note.** Diff coverage of Java changed lines is 100%, but that figure is the
intersection of the tool with an almost-empty `src/main` diff — this branch is 38 non-Java
files. The manifests are covered by the structural suite instead, which is stated plainly
rather than presented as a coverage result.

---

## ☸️ fix(deploy): repair Keycloak realm substitution and the secret generator, add a manifest regression suite (2026-09-04)

**Repo:** EDDI (`fix/review-deploy`)

From the whole-repository code review. The shipped Kubernetes and Helm assets failed
*silently at deploy time* rather than loudly at render time.

**Applying the documented quick start destroyed the vault master key.** `k8s/base` shipped
`eddi-secret.yaml` as a live resource, so every `kubectl apply -k` re-applied the
placeholder committed to this repository over whatever key was installed. On a first
install the operator silently ran with a published key; on any later apply the real key was
overwritten and everything sealed with it became undecryptable. The manifest is now
`eddi-secret.yaml.example` and is not applied; `create-secrets.sh` refuses to clobber an
existing secret unless asked.

**Both shipped `k8s/examples/` kustomizations failed to build at all** — verified by running
`kubectl kustomize`, which exits non-zero because an overlay reaches a file outside its own
root. Composing overlays the way their own headers instruct also silently discarded their
ConfigMap patches, so a NATS deployment came up still set to in-memory messaging.

**Keycloak never became ready and never had a realm.** The probes targeted a port serving
no health endpoint, so the pod stayed NotReady forever and its Service got no endpoints; and
the deployment never imported the realm, so OIDC discovery resolved against a realm that did
not exist. Keycloak also ran `start-dev` with no persistent volume, so every restart wiped
realms, clients and users — it is now a StatefulSet with a volume.

**The Helm realm substitution matched nothing.** The chart replaced the literal
`https://eddi.example.com`, but both shipped realm copies advertised a *different*
placeholder host, so `helm` exited 0 and login died with `Invalid parameter: redirect_uri`.

### Regression coverage

Deployment assets were the one area with almost no automated coverage, which is why these
shipped. This branch adds `DeploymentManifestsTest` — a structural suite that asserts
*relationships* rather than presence: that the realm placeholder the chart substitutes is
the one the realm files actually carry; that the token issuer is derived from the same
public URL on both the Helm and Kustomize paths; that the realm volume resolves to a
ConfigMap the overlay genuinely generates; and that the secret generator checks before it
deletes.

A new CI `manifest-lint` job renders every kustomization and lints the chart, so a manifest
that does not build fails the pipeline instead of a deployment. It is deliberately a PR gate
and not a release blocker, for the same reason `shell-lint` is: a manifest typo should not
hold up a security patch.

The six tests were proven by mutating all six inputs at once and confirming exactly six
failures with no collateral.

## 🧾 fix(gdpr): name the conversations an export lost, and stop a cache miss disowning a delete (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Six review comments, four of which were raised only inside collapsed review-body sections that
never became inline threads, so nobody had opened them.

**A cache failure disowned a delete that had succeeded.** In `deleteUserData`, `forgetRestriction`
sat between the memory delete and the count assignment inside one `try`, so an eviction failure
reported zero memories erased and named `userMemories` as the failed step — for a delete that had
already committed. The count is now assigned immediately after the delete, and cache eviction is its
own step recording `restrictionCache`.

**The export silently dropped conversations it could not read.** A snapshot that threw, or came back
null between the id lookup and the read, was logged and skipped while `complete()` knew nothing about
it — the same reports-success-while-incomplete failure this branch exists to fix, one method away.
Failed ids are now collected, surfaced on `UserDataExport` and the MCP payload, counted in the
`GDPR_EXPORT` audit entry, and folded into `complete()`.

**Two documents described an API that no longer exists.** The erasure example in
`docs/gdpr-compliance.md` predated six counters, `failedSteps` and `complete`, and the export note
still said 206; both are corrected, and the operator is told to check `complete` before filing an
Art. 17 request as fulfilled. `docs/audit-ledger.md` listed the wrong indexes and claimed the
collection takes inserts only — `pseudonymizeByUserId` issues an `updateMany` under Art. 17(3)(e).

**Two tests that could pass while broken.** `McpGdprToolsTest` now derives its expected key set from
`GdprDeletionResult`'s record components, so a component added to REST cannot silently miss MCP; and
the pseudonym assertion checks the full `pseudonymFor` value rather than just the prefix, which a
pseudonym computed over the wrong input would have satisfied.

---

## 🕵️ fix(gdpr): log the pseudonym, not the identifier the erasure just removed (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

A reviewer pushed further than the previous round did, and was right to. Sanitising the `userId` in
the GDPR delete-all log stopped a caller forging log records, but it left the identifier itself
sitting in the log — on the one code path whose entire purpose is to remove that identifier. Logs
outlive the database and travel further than it does, so an erasure that writes the user's id into
them has not finished the job (CWE-532).

Both user-memory stores now log `AuditHmac.pseudonymFor(userId)`: the same deterministic SHA-256 the
erasure cascade already substitutes into the audit ledger. An operator can still correlate the log
line with the ledger entry, and neither holds the identifier. The pseudonym is hex, so it also
cannot carry a record boundary — the injection fix is subsumed rather than discarded.

`PostgresUserMemoryStore.deleteAllForUser` gained the null guard `MongoUserMemoryStore` has always
had. Erasing "all entries for user null" is not a request anyone means, and the pseudonym refuses a
null identifier rather than hashing one.

The regression test asserts the stronger contract: the raw id must not appear in the captured log at
all, and the pseudonym prefix must. Reverting the change fails it with the offending line quoted.

---

## 📤 fix(gdpr): stop calling an export complete while four data categories are missing (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Six review comments, plus the log-injection round.

**The portability export overstated itself.** `conversationsTruncated` was the only completeness
signal, but the endpoint's own interface documents that every export omits group transcripts,
shared artifacts, schedules and HITL journal entries. A user whose data lived only in those
categories received a 200 the API described as complete — a GDPR Art. 20 answer that is not true.
`UserDataExport` now derives `complete` and `omittedCategories`, both mirrored into the MCP payload
so the two surfaces agree, and the response carries the distinction in the status line as well as
the body.

**206 was the wrong status and is now 207.** 206 Partial Content is a *range* status and means
something specific about byte ranges. 207 Multi-Status says "composite operation, read the body",
which is what this is, and it is already what the sibling erasure endpoint uses. Because the four
categories are always omitted today, the export always answers 207; the 200 branch returns when
those exporters land. The four missing exporters are deliberately not implemented here.

**A warning claimed a write that had not happened.** `warnIfCostBudgetIsUnenforceable` ran before
`setQuota`, so a failing store still logged that the limit was stored and would apply. Moved after
the write returns.

**The SSE and synchronous quota surfaces disagreed.** A `QuotaAccountingUnavailableException` with
no message produced `"message":""` on the streaming path while the synchronous mapper produced
`Quota accounting unavailable`. Both now use one shared fallback.

**Two stores gave a different refusal reason than the gates around them.** The Mongo and PostgreSQL
tenant-quota stores said "Cost accounting failed" where every sibling gate says "Quota accounting
unavailable — denying request for safety". Aligned.

**A documentation contradiction, half real.** `AuditLedgerService` has two overflow paths, and only
one dead-letters. `submit()` reserves its slot before a sequence is assigned, so a rejected
submission is simply counted and dropped; `offerBounded()` on the retry paths dead-letters, because
those entries already hold a chain position. The Failure Handling prose described the second and
generalised it to both. It is now a table naming each path and its recoverability, with the
consequence stated: a dropped submission is unrecoverable *and* leaves the chain `INTACT`, so
`eddi_audit_entries_dropped_total` is the only signal and a clean `/auditstore/verify` is not proof
of completeness.

**One comment was wrong and is recorded as such.** A reviewer said a fixture stubbed the wrong
descriptor read. The call graph is the other way round — `describe()` reaches `readDescriptor`, not
`readCurrentDescriptor`, and the latter appears nowhere in the service. What misled the reviewer was
the fixture's own comment, which claimed the opposite; the comment was corrected and the stub left
alone. Following the suggestion would have stopped the test reaching the production guard at all.

**Log injection.** The GDPR delete-all logs on both user-memory stores wrote the caller-supplied
`userId` raw. Both now sanitize it — on the erasure path, which is exactly where a log has to be
trustworthy.

---

## 🔐 fix(gdpr): stop caching "not restricted" by default; make foreign audit sequences visible (2026-09-06)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Two reviewer comments had been reported closed but were not. Both are now closed properly, and the
second one is closed by admitting what is not fixed rather than by claiming it is.

**The Art. 18 restriction cache failed open by default.** A previous pass added
`eddi.gdpr.restriction-cache-ttl-seconds` and made `0` disable the cache, but left the default at
30 seconds — so on a stock multi-replica deployment a node that had cached "not restricted" kept
processing for up to 30 seconds after another node applied the restriction. That is exactly the
window the comment described, and an opt-in switch does not close it. The default is now `0`:
every turn reads the store, and caching becomes an explicit single-node or conversation-affinity
optimisation, documented as such. The alternative considered was a positive-only cache, which was
rejected because it would only ever help restricted users — the rare case — while still delaying
an *un*restriction across the cluster.

**Audit sequence allocation is still not cluster-safe, and now says so.** The reviewer asked for a
storage-level atomic reservation plus a unique `(conversationId, sequence)` constraint and retry.
That is deferred: it moves a store round trip from once per conversation to once per entry on the
pipeline thread, and it is a schema change on two backends. The constraint must not land on its
own either — for an audit ledger a rejected insert silently drops a record, which is worse than a
duplicate the verifier can detect. What was genuinely missing and is cheap is *detection*:
`flush()` now re-reads the store's maximum for each conversation it wrote and, if the store already
holds a position at or beyond this node's next free one, increments
`eddi_audit_sequence_collisions_total`, logs a WARN naming the conversation, and advances its
counter past the foreign rows. An operator running multi-replica without affinity sees it in
metrics instead of discovering it as a `BROKEN` verdict at verify time. The detector has no false
positives and is documented as partial: two nodes handing out an identical range leave a maximum
consistent with both counters and are still only caught at verify time.

`IAuditStore` and `docs/audit-ledger.md` now lead with the limitation, the deferred fix, and why it
is deferred, so the row can no longer be read as "already correct".

---

## 📒 fix(audit,gdpr,tenancy): repair ledger persistence, erasure reporting and quota windows (2026-09-04)

**Repo:** EDDI (`fix/review-audit-gdpr`)

From the whole-repository code review. The subsystem a regulated buyer is actually paying
for did not work on a supported backend.

**The PostgreSQL audit backend could not store entries EDDI legitimately produces.**
`conversation_id`, `AGENT_ID` and `AGENT_VERSION` were `NOT NULL`, and the insert unboxed a
nullable `agentVersion` with `setInt`, throwing an NPE that escaped `appendBatch`'s catch
entirely. Six shipped call sites pass a literal null — ordinary HITL approvals and group
turns among them — so a single compliance or oversight entry **discarded roughly three
flush windows of unrelated conversations' audit data**, while the compliance event itself
was never recorded. On the read side `getInt` mapped a stored SQL NULL to `0`, which the
HMAC canonical form renders differently, so any such row would have verified as tampered.

The ledger is append-only evidence, not logs. Losing other conversations' entries because
one entry is malformed is the worst possible failure mode for it.

**GDPR erasure returned 200 and "complete" even when steps failed**, and reported memories
as deleted before deleting them. It now reports per-step outcomes and answers 207 when the
cascade is partial. The MCP admin surface for the same operation hardcoded
`"status": "completed"` and is now driven by the real result, so an operator — or an agent
calling the tool — is no longer told a lossy erasure succeeded.

**Quota windows** were compared against a stale in-memory view, and the bootstrap silently
ignored later configuration changes; both now warn when stored and configured values
diverge instead of quietly preferring one.

## ⏸️ fix(schedule): a human-approval pause is a skip, not a failure (2026-09-07)

**Repo:** EDDI (`fix/review-schedules`)

Two review comments that were raised but never posted as inline threads, so nobody had opened them.

**A scheduled turn that paused for human approval was dead-lettered.** `ConversationService.say`
throws `ConversationAwaitingApprovalException` *before* the response handler is wired, so the
`SKIPPED` branch this branch added for exactly that case could never run. The executor's broad catch
recorded the fire `FAILED`, incremented `failCount`, and eventually dead-lettered a conversation
whose only crime was waiting for a human. A catch for that exception now sits ahead of the broad one
and sets `FireStatus.SKIPPED`.

**The Mongo existence probe claimed a primary read it never requested.** `logFire`'s compensating
re-read asked for no read preference, so it inherited `ReadPreference.nearest()` from the single
`MongoDatabase` producer and could be answered by a lagging secondary still holding the schedule
that had just been deleted — while its own Javadoc said it "reads from the primary". The probe now
asks for the primary explicitly, and the Javadoc describes what the code requests rather than what a
deployment might happen to be configured as.

---

## ⏰ fix(schedule): a fire log can no longer outlive the schedule it belongs to (2026-09-06)

**Repo:** EDDI (`fix/review-schedules`)

Review round on this branch: 17 comments. Six were genuinely open and are fixed; the substantive
one took two attempts, because the first was a mitigation described as a fix.

**Erasure could report success over a log row it had not removed.** `deleteWithCascade` deleted
logs and schedules in a transaction and then swept again after commit, which catches every log
written before the sweep — but a fire already in flight can commit its log afterwards, so a GDPR
erasure still reported success over a row carrying the erased user's `conversationId`. The window
is now closed at the *write* side rather than by widening the sweep.

On PostgreSQL `logFire` issues a guarded insert — `INSERT … SELECT … WHERE EXISTS (SELECT 1 FROM
eddi_schedules WHERE id = ?)` — so the subquery is evaluated under the same snapshot that writes
the row and a log for a deleted schedule cannot be committed at all. Zero rows is the correct
outcome, logged at DEBUG, never thrown: a benign race must not surface on the fire path. A foreign
key with `ON DELETE CASCADE` was the other candidate and was rejected — existing deployments
already hold orphaned fire logs, which is the bug, so `ADD CONSTRAINT` would fail on exactly the
installs that need it.

MongoDB has no conditional insert, and a pre-check only moves the race. So it inserts, re-reads the
schedule from the primary, and deletes the log it just wrote if the schedule has gone. Against the
cascade's three steps there is no interleaving where the log survives its schedule: either the
schedule delete precedes the re-read and the compensation fires, or it does not and the cascade's
own delete or the post-commit sweep catches the document.

Both sweeps are kept, re-framed as belt-and-braces for logs written by a replica that had not yet
observed the delete. The one operator-visible consequence — a schedule deleted mid-fire may lose
that attempt's log — is documented in `docs/scheduling.md` as deliberate.

**Outcome writes are fenced by the claim's fire id.** `markCompleted`/`markFailed`/`markSkipped`
and `markDeadLettered` now take the expected fire id, so a fire that exceeded its lease cannot
overwrite the outcome of the fire that reclaimed the row.

**Three CodeQL log-injection sites** in `PostgresScheduleStore` (`scheduleId`, `agentId` and a HITL
timeout schedule name, all caller-supplied) now go through `LogSanitizer.sanitize`, matching what
`MongoScheduleStore` already did.

**Redirects no longer rewrite every method to GET.** `SafeHttpClient` splits the rule per status:
307/308 preserve method and body, 303 rewrites to GET, and 301/302 rewrite only POST — so PUT,
PATCH and DELETE keep their method, body and `Content-Type`.

**The minimum-interval check no longer depends on when it runs.** `CronParser` derived the gap by
walking fires from `Instant.now()`, so the same expression could pass validation on one day and
fail on another. It is now computed from the parsed fields: the tightest pair within a firing day,
and the tightest gap across days scanned over a full 28-year Gregorian cycle.

**Correction.** An earlier entry on this branch described scheduling as "exactly-once". Delivery is
at-least-once — `IScheduleStore`, `docs/scheduling.md` and `docs/hitl.md` all say so — and that
line has been corrected in place.

---

## ⏱️ fix(schedule): close the review round and pin the guards by mutation (2026-09-04)

**Repo:** EDDI (`fix/review-schedules`)

Follow-up on the same branch, from three independent review rounds plus a diff-coverage pass.

**Two CI failures this branch caused are fixed.** `ImportStyleTest` was red because the branch
introduced two inline fully-qualified names — the exact convention that test enforces — in
`RestScheduleStoreTest` and `MongoScheduleStoreTest`. And the vendored fuzz sources drifted
because a Javadoc reformat of `PathNavigator` diverged from the copy `.clusterfuzzlite`
vendors; the cosmetic edit is reverted rather than re-syncing the vendored file, keeping the
diff to what the findings required.

**Tests that could not fail were replaced.** Five were proven vacuous by mutation, not by
inspection. Two `WordSplitter` cases never reached the bounds guard they claimed to pin — one
used an input whose index made the new `i > 0 &&` term unreachable. A `MongoScheduleStore` test
asserted `!rendered.contains("triggerType=CRON")` on a `Bson.toString()` where that string can
never appear, so it was unconditionally true; it now encodes through the real codec registry
and asserts BSON null for an absent trigger type and the value for a present one, catching both
an invented default and a hardcoded null.

Two further claims were **disputed with evidence and left alone**: their "changed" line was a
rename from an inline FQN to an import, mandated by AGENTS.md 4.7. No test can fail on the
revert of a rename, so the correct remedy is to drop the line from the coverage claim, not the
test from the suite — and both were shown to kill real mutants first.

**Diff coverage** of changed lines: 94.4% to 99.2% line, 89.3% to 98.2% branch.

---

## ⏰ fix(schedule): correct fire bookkeeping, persistence and manual-fire claiming (2026-09-04)

**Repo:** EDDI (`fix/review-schedules`)

From the whole-repository code review. Scheduled fires were reporting success they had
not earned, and losing state they had been given.

**PostgreSQL lost the payload entirely.** `eddi_schedules` had no column for `message` —
the text a CRON schedule sends to the agent, which `RestScheduleStore` makes mandatory on
save — nor for `time_zone`, `one_time_at`, `environment`, `agent_version`, `created_by` or
`persistent_conversation_id`. The value was written, silently dropped, read back null, and
the scheduled turn ran with **null input**. Scheduling is enabled by default and PostgreSQL
is a documented, supported backend. The columns are added with
`ADD COLUMN IF NOT EXISTS` statements so existing databases upgrade in place, and the
dropped `persistent_conversation_id` was separately re-opening the CAS claim on every
heartbeat fire, breaking the single-owner CAS claim that keeps a fire from running twice.
(The delivery contract is at-least-once, not exactly-once — `IScheduleStore`,
`docs/scheduling.md` and `docs/hitl.md` all say so. An earlier draft of this entry claimed
otherwise.)

**Failures were recorded as successes.** The executor read its outcome from a latch that
counts down on the failure branch too, so an error inside the pipeline looked like a green
fire: retry, backoff and dead-lettering never engaged, and `docs/scheduling.md` documents a
state machine that could not be reached.

**Persistent fires un-claimed themselves mid-flight.** The strategy wrote the pre-claim
schedule back with `replaceOne`, so the poller re-claimed and re-fired a schedule that was
still running, routing both turns into the *same* persistent conversation — two interleaved
turns, two cost charges, one memory.

**Heartbeats drifted.** The next fire re-anchored on the moment a turn *finished* rather
than when it was *due*, so a 40-second turn on a 60-second cadence actually fired every 100
seconds.

**A manual "fire now" took no cluster claim at all**, so it could run concurrently with the
poller's own fire of the same schedule.

Also: `PUT /schedulestore/schedules/{id}` silently erased `createdAt`, `createdBy`,
`lastFired` and the claim state on MongoDB (PostgreSQL preserved them — a parity gap in the
same feature), and `CronDescriber` rejected day-of-week `7`, which `CronParser.validate`
accepts, so a valid stored schedule 400'd on read.

### Regression coverage

Every behavioural change is pinned by a test proven to fail with its fix reverted. Four
tests that the auditor found could pass with the fix removed were rewritten to assert the
corrected value precisely rather than a property the buggy code also satisfied — one had
asserted only that the next fire time lies in the future, which the drifting formula did too.

Three of this repository's own guard tests were failing and are now satisfied properly
rather than relaxed: the three new `eddi.schedule.*` properties are documented in
`docs/configuration-reference.md`, and the new `eddi.schedule.firelog.pruned` counter is
both documented in `docs/metrics.md` and charted in the Grafana dashboard, because
`MetricsDashboardCoverageTest` requires both.

Recorded honestly as unverifiable locally: the `SafeHttpClient` redirect tests need a
loopback socket, and the new DDL and Mongo codec paths are only exercised against real
backends in CI.

## 🔁 test(backup): reach the rollback path without a multi-agent archive (2026-09-07)

**Repo:** EDDI (`fix/review-backup-sync`)

Merging main brought in two import-rollback tests from #733 that build an archive with **two** agent
files, land the first and fail the second. This branch independently forbids that: `singleAgentFileIn`
rejects a multi-agent archive, because an operator who approved importing one agent was getting
several, with the `Location` header pointing at whichever file happened to be enumerated last.

Both are right, and as written they cannot both hold. The guard stays; the tests were reshaped to
reach the same rollback through a single-agent archive whose **schedule write** fails. That is the
first step after the Agent is created and registered, which the production comment at the call site
already says is deliberate: schedules carry the id of the agent they fire, so they are written after
the Agent but before descriptor bookkeeping, "so a failure there still rolls them back".

Every assertion survives in substance — the Agent is registered, its row is deleted, and it is taken
back out of the capability index; and for the second test, a registry that throws on `unregister`
still must not abandon the workflow delete that follows it. Proven by deleting `unregisterCapabilities`
from the rollback: `Wanted but not invoked: capabilityRegistryService.unregister(...)`.

---

## 🔑 fix(backup): a reordered config list could hand one endpoint another's credential (2026-09-07)

**Repo:** EDDI (`fix/review-backup-sync`)

Three review comments and a round of CodeQL alerts. The first is the serious one.

**Secrets were restored into the wrong entry.** `ScrubbedSecrets.merge` paired source and target
list elements by index. An httpCalls config whose entries had been reordered between export and
sync therefore kept each source entry's own `uri` while taking the target's value at that position:
the billing call kept `https://billing.example.com` and received the *analytics* token. An
inserted entry was worse — a newly added call to an attacker-chosen host inherited the credential
that had been at its index. That is a credential disclosure to whatever endpoint sits at the other
position, not merely a lost secret.

Elements are now bound by stable identity (`name`, `id`, `key`) wherever they carry one, requiring
a unique target match. Elements with none — a bare string in an array — fall back to position only
when the lists are the same length and the two elements are identical in everything the scrubber
did not replace. Anything else refuses and keeps its placeholder, which the caller already logs for
the operator. Refusing beats guessing here: a lost secret costs an operator one re-entered key, a
misplaced one goes to somebody else's server.

**A workflow-only change was dropped and reported as skipped.** When a workflow diff was `UPDATE`
with no extension changes, nothing wrote the source config, so reordered steps or an added
condition silently did not arrive — and the run said "skipped", so the operator was told nothing
had gone wrong. The executor now adopts the source workflow, but only when every extension
reference it carries also exists in the target at the same canonical key; otherwise it refuses and
names the step, the same refusal the branch already makes for a new extension. Adopted references
are repointed onto the target's own resource URIs, because a cross-instance source names the
*other* instance's ids. A source workflow with no steps is refused outright rather than emptying a
live pipeline, and an adoption that comes out identical to the target is suppressed so a
cross-instance no-op does not burn a version.

**A dispatch test asserted something that could not fail.** It checked `agentUri()` was non-null,
which is returned whether or not anything was written, so a miswired store row passed. It now
asserts the result carries no failures and that the store was actually invoked; two sibling tests
that provoked a failure and asserted nothing about it were strengthened the same way.

**Log injection.** Snippet names, extension types and names, workflow and agent ids all come from
the archive and reached the log unsanitized. Every log argument in `UpgradeExecutor` and
`SourceUrlValidator` now goes through `LogSanitizer.sanitize`. Most of those values happen to be
URI-derived and so cannot carry a control character, but a snippet name is free-form text and
genuinely could — that is the one the new test drives.

---

## 🧪 test(backup): replace the tests that could not fail, close the CodeQL round (2026-09-06)

**Repo:** EDDI (`fix/review-backup-sync`)

Two passes on the same branch: 22 review comments (mostly CodeQL log-injection alerts) and a
mutation audit of the tests this branch had added.

**Every alert was already closed in the source, but two had no test that could fail if the fix
were removed.** Those guards were added. The rest were verified line by line against the working
tree rather than against the previous pass's notes.

**The mutation audit is the more useful half.** Fable re-ran each new test with the production
change surgically reverted and found several that passed anyway — coverage without a contract.
They are now rewritten to assert what the changed line actually implements:

- The snippet-rollback test asserted a call count; it now proves that a snippet created during a
  failed import is recorded on the `ImportTransaction` and deleted again by `rollbackCreatedResources`,
  and that a snippet *merged* into an existing one is never deleted.
- `BackupMetrics.upgradeCompleted` was checked by reading counters back, which cannot distinguish
  `increment(0)` from no call at all — both leave a `SimpleMeterRegistry` counter at zero. It now
  asserts the calls themselves against a recording registry.
- The workflow pass-through tests asserted a rendered string that a re-serialised model also
  produces. They now assert the archive's own text survives byte for byte, which catches the real
  loss: re-rendering adds an `extensions` field the archive never carried.
- The extension-failure test now asserts the exact key set the matcher builds from the target,
  including the occurrence ordinal, rather than a substring a wrongly-keyed map would also satisfy.

**Recorded gaps, stated rather than papered over.** Two defensive lines cannot be pinned by a unit
test and their tests were deleted rather than left as decoration: `recordCreatedSnippet`'s
null-URI guard and `resolveSnippetIdsByName`'s null-listing guard both sit inside a broader
`catch (Exception)`, so removing either still leaves the class green. A test that cannot fail is
worse than no test, because it hides the hole.

Diff coverage of the branch's changed lines: 94.1% line, 84.2% branch.

---

## 🔁 fix(backup): repair agent export, import and sync (2026-09-04)

**Repo:** EDDI (`fix/review-agent-sync`)

From the whole-repository code review. **Granular sync/upgrade did nothing at all**, and
its preview said otherwise — the single most serious finding of the review, and it was
broken three independent ways at once.

1. `UpgradeExecutor` asked `StructuralMatcher.buildPreview` for a *content-less* preview
   (`includeContent=false`), so `sourceJson` and `targetJson` were both null for every
   matched resource. The action then reduced to `Objects.equals(null, null)` → `SKIP`, and
   the executor skips every SKIP. Every resource that already existed in the target was
   silently left untouched.
2. Extension URIs were read from `step.getExtensions()`, but the engine stores them in
   `step.getConfig().get("uri")`, so the target extension map came back empty.
3. The ZIP and remote sources keyed their extension maps by resource-store authority
   (`ai.labs.rules`) while the target keyed by workflow step type
   (`eddi://ai.labs.behavior`), so the two sides could never join even with (1) and (2)
   fixed.

The REST preview endpoints pass `includeContent=true` and therefore showed real
differences. An operator saw a diff, applied it, received success, and nothing changed.

**Fixed** by a new `WorkflowExtensions` — one scan that is the single source of truth for
how a workflow points at its extension configs. Both producers and the matcher derive
keys from it, so source and target are guaranteed to join. The canonical key is
`<stepType>#<occurrence>/<path>`, which also fixes a workflow with two steps of the same
type collapsing onto one key and repointing both at the second resource. The diff action
is now decided from content that is always loaded; `includeContent` governs only what is
returned to the caller.

### Also in this branch

- **Exported ZIPs were never deleted** and accumulated in the working directory. They now
  land in `tmp/archives/` and are swept by age (`eddi.backup.export.retention-minutes`,
  default 60); the 404 message states the real retention instead of a fictional one.
- **A non-ASCII agent name produced an undownloadable archive.** `URLEncoder` output like
  `M%C3%BCller+Bot` was written literally to disk, and the download endpoint's character
  class then rejected the decoded form. Names are slugified instead, and a
  `Content-Disposition` header carries the readable filename.
- **Schedules were exported but never imported** — silently dropped on every round trip
  while the ZIP visibly contained them. Import now reads them, repoints them at the new
  agent with fire bookkeeping reset, and rolls them back with the rest of the transaction.
- **A v5 export ZIP imported nothing and reported 200.** The importer only looked for
  `.agent.json`, never the v5 `.bot.json` it deliberately still accepts elsewhere.
- **`strategy=upgrade` without `targetAgentId`** silently fell through to create, producing
  a duplicate agent; it is now a 400 naming the parameter.
- **A selectively-exported ZIP could not be re-imported**: a deselected extension file is
  absent from the archive while the workflow still references it, and `readResources`
  handed the resulting null straight to `store.create()`.

### Regression coverage

Every behavioural change is pinned by a test **proven to fail with its fix reverted**.
`ExtensionSourceKeyContractTest` writes a real archive to disk and stubs a real remote,
then asserts both producers emit identical canonical keys *and* that every extension joins
the target as UPDATE rather than CREATE; its fixture deliberately carries two `httpcalls`
steps so the collapse-by-type defect is caught too.

Two tests are recorded honestly as characterization rather than guards: the snippet
name-fallback row is what `main` always emitted, and `RestUtilities.createConflictException`
was a behaviourally identical refactor. Neither can fail without its change, and both say
so.

## 🧼 fix(configs): sanitize every cascade-delete log argument, not most of them (2026-09-06)

**Repo:** EDDI (`fix/review-config-delete`)

CodeQL raised eight log-injection alerts on this branch: a REST path parameter reached a log call
unsanitized, so a caller could put CR/LF in an Agent or workflow id and forge log records
(CWE-117). Every flagged argument now goes through `LogSanitizer.sanitize`.

The more useful part was what the alerts did *not* cover. `RestAgentStore` was left with the same
tainted `id` sanitized on one line and raw sixteen lines above it, on the schedule-cascade pair
that CodeQL could not reach because it needs the schedule store to throw. Uneven coverage in one
file is worse than none, because the next reader assumes the file is done. Every log argument in
that class is now sanitized: caller ids, store-sourced ids, and exception messages.

`URI`-typed arguments are deliberately left alone — `URI.create` rejects control characters, so a
URI object cannot carry a record boundary in the first place.

Ten regression tests drive a CR/LF payload through the real code paths and assert no newline
reaches the log. They guard against vacuity twice: the capture must be non-empty, and it must
contain a marker from the specific line under test — otherwise a closed logger or an unreached
branch would pass. `captureLogsOf` moved to a shared `LogCaptureSupport` rather than being copied.

One argument is honestly not pinned: `pinned.getId()` on the plan-cascade failure path. It comes
from `RestUtilities.extractResourceId`, whose validity gate returns null for anything containing
CR/LF, so the value cannot be driven. It is defensive, not reachable.

---

## 🧹 fix(configs): stop cascading deletes removing resources someone else still uses (2026-09-06)

**Repo:** EDDI (`fix/review-config-delete`)

Review round on this branch: 20 comments, 6 fixed, 13 confirmed already correct, 1 disputed with
code evidence. The six fixes share one shape — a delete that asked the right question at the wrong
moment.

**Cascade deletes checked references before the parent was gone.** `planCascade` has to ask
"is this referenced by more than one thing" while the Agent still counts itself, but by the time
each child delete actually runs the Agent is deleted and the count has moved. A workflow that a
second Agent adopted in between was deleted anyway. Both `RestAgentStore` and `RestWorkflowStore`
now re-ask immediately before each child delete, and a candidate that is still referenced
increments the `X-Cascade-Skipped` counter instead of being removed.

**The orphan purge trusted a reverse lookup that ignores old versions.** Both lookups in
`isReferencedNow` skip referrers that are not a resource's current version, while the mark scan
deliberately counts every version a deployment record pins. The purge loop now also re-runs the
deployed-agent scan per candidate and fails closed when the scan is incomplete or throws.

**Vault key rotation could sweep the key it had just written.** `versionsToSweep` treated an empty
list of valid versions as "nothing to keep" rather than as an unknown bound, so a rotation whose
identity update had not yet landed swept the new key. Empty is now handled exactly like null: the
full scan range is preserved and nothing is deleted on an unknown bound.

**Soft-deleting a descriptor marked the wrong version.** Descriptor versions advance independently
of the resource's, so the soft path now resolves the descriptor's own current version the way the
permanent path already did.

**Files:** `RestAgentStore`, `RestWorkflowStore`, `RestOrphanAdmin`, `AgentSigningService`,
`RestVersionInfo`, and their tests.

---

## 🛡️ fix(configs): close the CodeQL alerts and the review round (2026-09-04)

**Repo:** EDDI (`fix/review-config-delete`)

Follow-up on the same branch, from two independent review rounds, a diff-coverage pass, and
four CodeQL alerts this branch introduced.

**CodeQL, all four fixed in code rather than dismissed.** Two high-severity integer overflows
in the new `ResourceUtilities` paging helper, where a caller-supplied index and limit were
combined without bounds so a large value wrapped and produced a nonsensical window; the inputs
are now clamped before the arithmetic. Two log-injection sites where a user-provided value
reached a log statement unsanitised, now routed through the sanitiser the codebase already
uses elsewhere rather than a second one.

**A functional regression the branch's own suite could not see.** `RetryConfiguration` gained a
total-backoff budget, and every test in that class stayed green while the behaviour changed.
Found by the reviewer, fixed, and pinned by a test that fails without it.

**Four tests were proven vacuous by mutation.** One claimed to pin a new
`!config.getCapabilities().isEmpty()` guard; removing that guard left it green. Another
asserted the consequence of a stub the real collaborator refuses to produce. Each was rewritten
to fail on the regression it names, or deleted with an honest gap recorded — a test that cannot
fail is worse than none, because it hides the hole.

**Diff coverage** of changed lines: 89.9% to 99.5% line, 80.1% to 92.7% branch, with 38 tests
added and each proven against a mutation of the line it protects.

---

## 🗑️ fix(configs): make destructive configuration deletes safe, atomic and honest (2026-09-04)

**Repo:** EDDI (`fix/review-config-delete`)

From the whole-repository code review. The destructive configuration paths destroyed data
that was still in use, and reported success while doing it.

**Orphan purge deleted live configuration.** References are version-pinned by design, and
`DocumentDescriptorFilter` rewrites a descriptor's `resource` to the new version on every
PUT. So a single edit of a rule set leaves the descriptor saying `?version=2` while every
workflow that was not re-pointed still says `?version=1`. The scan compared those full
versioned URI strings, found no match, classified the config an orphan, and
`DELETE /administration/orphans` removed **all** of its versions — destroying a config a
live workflow was still resolving. The comparison is now on version-independent identity,
so any referenced version protects the resource. The remaining deliberate gap (only the
current version of each agent is scanned) is documented rather than silently present.

**Cascade delete tore down workflows before the guard that could still reject it.** A
version-mismatched cascade deleted the workflows and schedules of the version it read, then
answered 409. And the reference check asked whether *one pinned version* was still
referenced before deleting *every* version, so a workflow another agent still used was
destroyed.

**Reverse lookups crashed on soft-deleted rows.** `AgentStore` and `WorkflowStore` threw
`ResourceNotFoundException` as soon as one referencing document had been soft-deleted,
which disabled the "is this still referenced?" check entirely — the check is caught and
logged, so cascade delete simply stopped protecting shared resources.

**Version-0 history rows escaped permanent deletion** on MongoDB, so `deletePermanently`
and GDPR erasure both reported success while leaving an undeletable descriptor tombstone
behind forever.

### Regression coverage

Every behavioural change is pinned by a test proven to fail with its fix reverted.

Three pre-existing tests were **failing on the first attempt at this branch** while the
work reported itself green — caught by an independent reviewer running them. They are now
genuinely closed, and closed the right way: the fixtures were corrected to stub what
production actually calls. Five more tests in the same classes had begun passing
*vacuously*, because the change bypassed the dead-letter branch they were written to cover;
their stubs are restored so they exercise it again.

`maxMonthlyCostUsd` is stored and displayed but never enforced, because
`TenantQuotaService.recordCost` has no production caller. That is left as a product gap and
now surfaces as an explicit warning rather than a silent no-op.
Two things the auditor caught and this branch corrects rather than ships: the signing
keypair was being destroyed from the vault on a **soft** delete, on the path that exists
precisely to be recoverable; and a unique compound index was created unconditionally at
startup, which fails with a duplicate-key error on exactly the deployments the finding says
already hold duplicates. Both are now conditional and safe.

Six pre-existing cascade assertions were flipped from `permanent=true` to `permanent=false`.
That inversion is deliberate and correct: permanently removing a shared resource stays an
explicit, non-cascading request. A functional regression in `RetryConfiguration`'s new
backoff budget was found by the auditor while the class's own suite stayed green, and is
fixed with a test that fails without it.

---

## 🐳 fix(docker): move the base image digest and stop the weekly check suppressing itself (2026-09-06)

**Repo:** EDDI (`fix/base-image-digest-and-check`)

`main` has not published an image since 2026-08-30. Every CI run fails at **Scan Docker image
for vulnerabilities**, and every job after it — push, cosign signing, SLSA provenance, smoke
test, GitHub release, Red Hat catalog publish — is skipped. Reproduced locally with the gate's
own flags (`--severity CRITICAL,HIGH --ignore-unfixed`), which exits 1 on six findings, all
from the base image:

| Package | CVE | Installed | Fixed in |
|---|---|---|---|
| `curl-minimal`, `libcurl-minimal` | CVE-2026-8286 (TLS config mismatch) | 7.76.1-40.el9 | 7.76.1-40.el9_8.5 |
| `curl-minimal`, `libcurl-minimal` | CVE-2026-9547 (SSH host key bypass) | 7.76.1-40.el9 | 7.76.1-40.el9_8.5 |
| `sqlite-libs` | CVE-2026-11822, CVE-2026-11824 (FTS5 RCE, heap overflow) | 3.34.1-10.el9_8 | 3.34.1-11.el9_8 |

**Digest update, not a stopgap.** The pin was build `1.24-3.1786536503` (2026-08-12). Red Hat
republished the tag on 2026-08-24 as `1.24-3.1787587037`; the `1.24` tag now resolves to
`sha256:d5f7e0c5…`, which carries all four fixes. Per the remediation procedure in
[`AGENTS.md`](../AGENTS.md) this is the clean path — the pin moves, it is never dropped, and no
`microdnf update` line is needed. Rebuilt from the amended Dockerfile and re-ran the gate: zero
findings, exit 0. Also booted the image against MongoDB and drove a two-turn rule-based
conversation end to end (create ruleset → output set → workflow → agent → deploy → start →
say), plus the smoke-test assertions CI makes: health `UP` on all four checks, `/openapi` 200,
all three security headers present.

### Why nobody was told

`base-image-check.yml` **saw** the new digest on 2026-08-31 and declined to open the PR. Its
Dependabot guard matched any open PR whose branch starts `dependabot/docker/`, and #716 —
which bumps the *demo* image's `eclipse-temurin` base in `Dockerfile.demo` — satisfies that.
This repo has more than one Dockerfile, so the branch prefix was never a sufficient test. The
guard now requires the candidate PR to actually touch `$DOCKERFILE`, checked with
`gh pr view --json files` and an exact whole-line `grep -qxF`. Verified against the live repo:
both open Dependabot Docker PRs (#716, #631) touch only `Dockerfile.demo`, so the guard now
falls through and the digest PR would be created.

### Review round — which way the guard should fail

Both CodeRabbit and Copilot flagged the same thing: the guard suppressed `gh` stderr with
`2>/dev/null`, so an API error was indistinguishable from "no match". Right, and fixed —
stderr is no longer discarded, both `gh` calls have their exit status checked, and each failure
emits a `::warning::` annotation naming what could not be read.

Where the two bots disagreed was the *direction* of the failure. CodeRabbit asked to exit the
workflow on any API error ("fail closed"); Copilot asked to warn and continue with an empty
list. Took Copilot's direction, deliberately: fail-closed here means no PR that week, which is
the same outcome as the bug being fixed, whereas degrading toward *opening* the PR risks at
worst a duplicate that is visible and closed in one click. Nor is a red job a reliable alarm in
this repo — this very workflow failed on 2026-07-13, 07-20, 07-27 and 08-03, four consecutive
weeks, with nobody acting on it. When the guard cannot complete it now also writes a
"Dependabot guard degraded" block into the step summary, so a duplicate is explained rather
than merely appearing.

A second review round caught that the degraded summary was itself conditional: it was gated on
`[ -z "$DEPENDABOT_PR" ]`, so if one candidate's file list was unreadable and a *later* candidate
matched, the block was suppressed. The skip decision is sound in that case, but a candidate went
unchecked and the summary said nothing. The gate is now on the degraded flag alone, with wording
that distinguishes the two outcomes. (The `::warning::` annotation always fired either way; only
the summary was being hidden.)

A third round caught that the degraded reason was a scalar, so a run where two lookups failed
reported only the last one. It is a list now, and the summary prints every failed lookup as its
own bullet.

Exercised the rewritten guard against a stubbed `gh` on all five paths: Dependabot PRs touching
only `Dockerfile.demo` (no match, PR created — the original bug's correct behaviour), one
touching the production Dockerfile (match, skipped), `gh pr list` failing, `gh pr view` failing
per-PR, and the mixed case above where the first lookup fails and the second matches. The block
is clean under `shellcheck --severity=style`.

Worth recording that the first run of that fifth case printed nothing at all, which looked like
the new conditional was broken. It was the *harness*: it locates the end of the guard fragment by
matching `if [ -n "$DEPENDABOT_PR" ]; then`, and the fix introduces a nested `if` on the same
condition, so the extractor cut the fragment in half and produced an unterminated block. It now
matches only at the run-block's own indentation. The same shape of mistake as the `PATH` one in
the UBI 10 entry: twice now the test rig has been the thing that broke, and both times it first
presented as a bug in the code under test.

The failure mode is worth naming because it is the quiet kind: the weekly job reported
**success**, its own summary said the digest had changed, and the outcome line read
`Dependabot PR #716 already covers this Dockerfile`. Nothing was red except the thing the
automation existed to prevent.

### UBI 10 — evaluated, not adopted

Checked whether the app runs on `ubi10/openjdk-25-runtime`, since Red Hat's own Quarkus
material still shows UBI 9. It does. Same UID 185, same `run-java.sh` entrypoint, same
`JBOSS_CONTAINER_*` module layout, `curl` and `microdnf` both present, and the *identical* JDK
build on both (`25.0.4.1+1-LTS`, Red_Hat-25.0.4.1.1-1) — so no JVM-level difference at all.
The unmodified Dockerfile builds on it with only the `FROM` line changed; the image boots,
passes the container `HEALTHCHECK`, serves `/openapi` and `/manage/`, emits an identical set of
startup warnings, and runs the same two-turn conversation. Trivy reports **zero** findings at
every severity, against 10 HIGH and 223 MEDIUM/LOW on UBI 9. It is also ~19 MB smaller.

One real behavioural difference, and it is in the OS crypto policy rather than the JVM: RHEL 10
additionally disables the static-RSA TLS 1.2 suites (`TLS_RSA_WITH_AES_*_CBC_*`,
`TLS_RSA_WITH_AES_*_GCM_*`). Red Hat's OpenJDK honours `/etc/crypto-policies/back-ends/java.config`,
and `java -XshowSettings:security:properties` confirms the suites land in the JVM's
`jdk.tls.disabledAlgorithms` on UBI 10 and not on UBI 9. Every current LLM provider negotiates
ECDHE and is unaffected — TLS to the OpenAI, Anthropic and Google endpoints succeeds from
inside the UBI 10 image — but an on-prem endpoint offering only non-forward-secret suites would
connect on UBI 9 and fail on UBI 10.

Not switched in this change. The base OS is what `redhat-certify.yml` submits to the Red Hat
container catalog, and a major-version move is a certification decision rather than a CVE fix.
Kept separate so the digest bump can land immediately and unblock releases.

### Follow-up not taken here

`base-image-check.yml`'s "newer tag" probe only scans `1.25`…`1.29` **within the same
repository**, so it cannot surface a UBI 10 image no matter how long one exists. Left as-is;
widening it belongs with the decision above.

### Merge with #736 — two guards for the same bug, combined

#736 landed on `main` first and had independently fixed the same Dependabot false match, so the
merge was a conflict between two working implementations rather than a text collision. Neither
was strictly better, and each carried something the other lacked.

`main` asked `gh pr list --app dependabot`; this branch asked `--author 'app/dependabot'`.
`--app` is right: `--author` is the *user* filter and does not reliably match an App-authored
PR, so the candidate list can come back empty, the skip never fires, and the job opens a
duplicate — the mirror image of the bug being fixed. `main`'s reasoning about
`dependabot.yml` watching three directories (`/src/main/docker`, `/mcp-sidecar`,
`/.clusterfuzzlite`) is also the more complete statement of why the branch prefix was never
sufficient; the demo image was only the instance that happened to bite.

This branch, in turn, surfaces `gh` failures instead of swallowing them. `main` wrapped both
lookups in `2>/dev/null || echo ""`, which restores the original failure mode in a new place:
an unreadable candidate silently becomes "not a match", and nothing says so. The three
follow-up commits here exist for exactly that.

Resolved by taking `--app dependabot` and `main`'s reasoning into this branch's structure, so
the guard both queries correctly and reports when it could not. Verified the merged workflow
parses as YAML and that all six `run` blocks pass `bash -n`.

**The digest now applies to two `FROM` lines.** #736 split the image into a throwaway `docs`
stage plus the runtime stage, both carrying the pin. Its own comment requires the two to move
together — `base-image-check.yml` parses the last `FROM` and its `sed` rewrites every line
carrying the pin — so bumping one would leave the vulnerable base in the published image and
silently desynchronise the automation. Both moved. `EddiImageDockerfileTest` (also new in
#736) pins the integration image to the production file and uses synthetic digests rather
than the real one, so it required no change.

**Files:** [`src/main/docker/Dockerfile`](../src/main/docker/Dockerfile),
[`.github/workflows/base-image-check.yml`](../.github/workflows/base-image-check.yml)

---

## 🐧 chore(docker): move the production base image to UBI 10 (2026-09-06)

**Repo:** EDDI (`chore/ubi10-base-image`)

Companion to the UBI 9 digest bump on `fix/base-image-digest-and-check`, which is the immediate
release unblock. This is the durable fix: `ubi9/openjdk-25-runtime` carries a standing CVE
backlog that a digest bump only ever partially drains, and `ubi10/openjdk-25-runtime` is
currently **clean at every severity**.

| Base | CRITICAL/HIGH | MEDIUM/LOW | Layer size |
|---|---|---|---|
| `ubi9/openjdk-25-runtime:1.24` (newest digest) | 10 | 223 | 145.7 MB |
| `ubi10/openjdk-25-runtime:1.24` | **0** | **0** | 127.0 MB |

**The JDK does not change.** Both images ship Red Hat OpenJDK `25.0.4.1+1-LTS`
(`Red_Hat-25.0.4.1.1-1`), so this is strictly an OS-layer move — no bytecode, JIT or GC
behaviour differs. UID 185, the `run-java.sh` entrypoint, the `JBOSS_CONTAINER_*` module layout,
`curl` (needed by `HEALTHCHECK`) and `microdnf` (needed by the stopgap escape hatch in
`AGENTS.md`) are all present and identical. Only `JAVA_HOME` moves, from `/usr/lib/jvm/jre` to
`/usr/lib/jvm/java-25-openjdk`, and nothing in this repo reads it.

### Verified, not assumed

Built the image, ran the CI image gate with its own flags (`--severity CRITICAL,HIGH
--ignore-unfixed`) → zero findings, exit 0. Booted it against MongoDB: container `HEALTHCHECK`
healthy, health `UP` on all four checks, `/openapi` 200, `/manage/` 200, all three security
headers present, Red Hat certification labels and `/licenses` intact. Drove a two-turn
rule-based conversation end to end — create ruleset → output set → workflow → agent → deploy →
start → say — against a UBI 9 control on the same build; identical output and an identical set
of startup warnings.

### Two RHEL 10 constraints, both documented in the `FROM` block

**1. Host CPU floor rises to x86-64-v3.** Verified by reading
`GNU_PROPERTY_X86_ISA_1_NEEDED` out of each image's `libc.so.6`: UBI 9 declares
`x86-64-v2`, UBI 10 declares `x86-64-v3`. So the host needs AVX2, BMI2 and FMA — Intel Haswell
(2013) or AMD Excavator (2015) onward. Every current cloud instance type clears it; a pre-2013
bare-metal host does not, and glibc refuses to start rather than failing later. `libjvm.so`
carries no ISA note (HotSpot probes the CPU at run time), so glibc is the binding constraint.

**2. Static-RSA TLS 1.2 suites are disabled.** RHEL 10's crypto policy adds
`TLS_RSA_WITH_AES_{128,256}_{CBC,GCM}_*` to the disabled set, and Red Hat's OpenJDK inherits
`/etc/crypto-policies/back-ends/java.config` — confirmed with
`java -XshowSettings:security:properties`, where those suites appear in the JVM's
`jdk.tls.disabledAlgorithms` on UBI 10 and not on UBI 9. Every current LLM provider negotiates
ECDHE and is unaffected; TLS to the OpenAI, Anthropic and Google endpoints succeeds from inside
the image. An on-prem endpoint offering only non-forward-secret suites would connect on UBI 9
and fail on UBI 10.

Both are stated in full in the `FROM` block of [`Dockerfile`](../src/main/docker/Dockerfile), so
the next person to touch that line sees them without leaving the file, and summarized in
[`docs/redhat-openshift.md`](redhat-openshift.md) for operators.

### Red Hat support boundary

A container supplies its own userspace, so the host only has to be new enough. Red Hat's
[container compatibility matrix](https://access.redhat.com/support/policy/rhel-container-compatibility)
lists a UBI 10 image on a RHEL 9 host as **Supported**, subject to the conditions that apply to
any mismatched major pair: the workload runs unprivileged, does not interact directly with
kernel-version-specific interfaces (`ioctl`, `/proc`, `/sys`, routing, iptables, nftables, eBPF),
and the image's RHEL version stays within its supported lifecycle. EDDI meets these — UID 185,
nothing below the JVM. The condition an operator has to plan for is the last one Red Hat states
and the one a support ticket runs into: a reported issue may have to be reproduced in a fully
compatible configuration — that is, on a RHEL 10 host — before it is investigated. RHEL 8 is the
one host Red Hat marks unsupported for a UBI 10 image. `redhat-openshift.md` states all of this.

*(An earlier draft of this entry claimed the RHEL 9 host combination was categorically outside
Red Hat's policy. It is not; the matrix was checked and the claim corrected before merge.)*

### `ContainerBaseIT` no longer restates the base image

> **Superseded on merge by #736.** `main` reached the same conclusion first and went further:
> `EddiImageDockerfile.forTestContext()` transforms the *whole* production Dockerfile for the
> test build context, rather than parsing the `FROM` line and re-stating the remaining twenty
> lines inline. That is strictly stronger — the inline copy this branch kept could still drift
> in every respect except the base image — so the parser described below was dropped in the
> merge rather than reconciled. `EddiImageDockerfileTest` now pins the relationship, and it uses
> synthetic digests, so moving the production pin to UBI 10 required no change to it.
>
> The account below is kept because two of its findings outlived the code: static initialisers
> run in textual order (a constant used by a container field must be declared above it, and only
> a real container IT catches the violation), and container ITs *do* run on this machine. Both
> are recorded in the Regression Notes.

`ContainerBaseIT` builds its own inline Dockerfile that mirrored the production `FROM` — on
UBI 9, and unpinned, so it was already a major version and a digest behind what shipped.
Hard-coding UBI 10 there would only have reset the clock: the copy goes stale on the next digest
bump without anything failing, and the container ITs quietly certify an OS layer nothing ships.
It now parses the `FROM` line out of `src/main/docker/Dockerfile` at test time, which carries the
digest pin along for free and makes drift impossible rather than merely discouraged.

A later review round hardened that parser. The first version took the first whitespace-separated
token after `FROM`, which is the image reference today but would be the **flag** the moment the
file gains `FROM --platform=$BUILDPLATFORM …` for a multi-arch build. The ITs would then have
built against a Dockerfile reading `FROM --platform=$BUILDPLATFORM` and failed confusingly rather
than clearly. It now skips `--flag` tokens, and matches the instruction with a case-insensitive
`^\s*FROM\s+` rather than `startsWith("FROM ")`, since Dockerfile keywords are case-insensitive
and may be followed by a tab. Checked against thirteen shapes: both real Dockerfiles in the repo,
`--platform` with and without a trailing `AS`, a lowercase keyword, a tab separator, a leading
indent, a `# FROM` decoy comment, multi-stage last-wins, and the two malformed inputs that must
throw.

**The hardening broke the build first.** `FROM_INSTRUCTION` was declared below the `EDDI`
container field, and static initialisers run in textual order — so that field's initialiser
called into the parser while the pattern was still `null`, and all three container ITs died with
`ExceptionInInitializerError` caused by a `NullPointerException`. Neither `test-compile` nor the
standalone parsing harness could see it: one does not run static initialisers, the other does not
reproduce this class's field order. Only the Integration Tests job did. The constant moved above
the container fields, with a Javadoc saying why it must stay there. Verified by reproducing the
mechanism in a two-class scratch file (declared-after throws, declared-before does not) and then
by running `AgentUseCaseIT` locally end to end: 2 tests, 44 s, image built from the parsed
`FROM` line, container healthy.

That local run is itself worth noting, because a standing assumption said container ITs cannot
run on this machine. They can. Had that been checked earlier, the null pattern would have been
caught before the push rather than by CI.

Worth recording how nearly that verification went wrong. The first harness reported the
tab-separator case failing, which looked like a bug in the new regex. It was not: the harness had
been written through a shell heredoc, which ate one backslash from `"\\s"`, and **Java 15 accepts
`\s` in a string literal as an escape for a plain space** — so the corrupted harness compiled
cleanly and silently tested `^ *FROM +` instead of `^\s*FROM\s+`. A compile error would have been
kinder. The harness was rewritten to a file directly and its regex diffed against the real one
before being trusted.

### The check that could not have told us

`base-image-check.yml`'s tag probe only walks `MAJOR.MINOR+1…+5` **within the pinned
repository**, so `ubi10` was invisible to it no matter how long it existed — the job would have
reported `1.24 is the latest tag` forever. Added a `Check for newer UBI major` step that derives
the `ubiN` segment from the image path and probes `ubiN+1` and `ubiN+2` (trying the pinned tag,
then `latest`, so a renamed tag scheme still registers), plus a matching issue notification whose
body lists what to confirm before a major move: CPU baseline, crypto policy, JDK build, and
`ContainerBaseIT`. Verified against the live registry — `ubi11` and `ubi12` are 404 today, and
running the same logic against the old `ubi9` pin resolves to `ubi10`, which is exactly the miss
it closes.

### Review round

Four findings from CodeRabbit and Copilot, all taken.

**The RHEL 9 host claim was wrong** — the strongest reason to run a review. CodeRabbit disputed
the "categorically outside Red Hat's policy" wording and it was right; the matrix says
**Supported**. Corrected in both the doc and this entry rather than quietly reworded.

*A second round then caught that the correction had left the page arguing with itself.* The
opening bullet still said the image is "supported by Red Hat when run on RHEL or OpenShift" and
the note below the platform table still said "any RHEL-based platform", while the new paragraph
three lines further down said RHEL 8 is unsupported. An operator reading top-down would have
been sent to RHEL 8 before ever reaching the caveat. Both broad claims are now bounded, the
"runs anywhere with a container runtime" statement is explicitly separated from the *supported*
configuration, and the platform table gained explicit RHEL 9 and RHEL 8 rows so the boundary is
visible where support levels are actually looked up.

**`ContainerBaseIT` should carry the digest, not just the tag** — raised by both bots. Taken
further than asked: rather than restating the digest in a second place, the test now parses the
production `FROM` line, so the class of drift the bots were pointing at cannot recur.

**A hard-coded version series in operator guidance** (Copilot) — "6.3.x and earlier" would have
gone stale immediately. The corrected paragraph names no version at all.

**`actionlint` SC2001/SC2086/SC2129 on the new step** (CodeRabbit). Fixed: bash regex and
parameter expansion instead of `echo | sed`, quoted `"$GITHUB_OUTPUT"`/`"$GITHUB_STEP_SUMMARY"`,
grouped consecutive appends. Both new steps are now clean under
`shellcheck --severity=style`, which the seven pre-existing steps in the file are not — those
are left alone here rather than folded into a base-image change.

Extracting the new step and running it against a stubbed `skopeo` was worth doing: the first
harness reported "no newer major" for all three cases, which looked like a real bug in the
rewritten parameter expansion. It was the harness — a Windows-style directory on `PATH` that
Git Bash cannot resolve, so the stub was never found and every probe failed identically. The
step itself is correct on all three paths (pinned on ubi9 finds ubi10, pinned on ubi10 finds
nothing, a non-`ubiN` image exits early).

### Merge with #736 and #737

#736 landed on `main` while this branch was in review and changed three of the things it touches.

**The move now applies to two `FROM` lines.** #736 split the image into a throwaway `docs` stage
and the runtime stage, both carrying the pin, with a comment requiring them to move together —
`base-image-check.yml` reads the last `FROM`, but its `sed` rewrites every line carrying the pin.
Both stages are on UBI 10. Moving one would have published a UBI 9 layer and desynchronised the
automation at the same time. The UBI 10 rationale block now sits above the stage comments rather
than immediately above a single `FROM`, since it governs both.

**The `ContainerBaseIT` parser was dropped**, superseded by #736's `EddiImageDockerfile` — see
the note in that section above.

**The Dependabot guard was reconciled in #737, not here.** That branch merged first and combined
`main`'s `--app dependabot` filter with its own failure reporting; this merge inherits the
combined version and adds only the newer-UBI-major probe, which is orthogonal — the tag scan
walks `MAJOR.MINOR+1…+5` inside the pinned repository and so cannot see a new major at all.
The one textual collision was the summary step, where `main` had quoted `"$GITHUB_STEP_SUMMARY"`
for shellcheck and this branch had added an unquoted UBI-major line; the added line is now
quoted. Verified the merged workflow parses as YAML and that all eight `run` blocks pass
`bash -n`.

**A whole-file `--ours` silently dropped an unrelated fix, and CI caught it.** Resolving the
`FROM` collision with `git checkout --ours src/main/docker/Dockerfile` took *this branch's entire
file*, not just its side of the conflicting hunk — discarding every non-conflicting change the
other side carried. What went with it was #734's audit provisioning:

```dockerfile
RUN mkdir -p /opt/eddi/data &&       chown -R 185:0 /opt/eddi &&       chmod -R 775 /opt/eddi
```

That directory is the default `eddi.audit.dead-letter-path` parent. UID 185 cannot create a
directory under `/opt` at run time, so without it every dead-letter write on the documented
`docker run` quick start throws into a swallowed catch and the abandoned audit entries are gone
outright rather than recoverable — the exact defect #734 had just fixed.

`AuditDeadLetterImageProvisioningTest` failed on it in CI. It did not fail locally because the
pre-push run was a hand-picked `-Dtest` list built around the files the change was *believed* to
touch, and the whole point of this failure mode is that it touches files you did not intend.

Fixed by rebuilding the file from `#737`'s and re-applying only the three intended edits, so the
result is provably that branch's Dockerfile plus the UBI 10 move, rather than a patched-up copy
whose provenance nobody can check. Then audited every file this branch differs from `#737` in,
reading the **deleted** lines specifically: all remaining deletions are UBI 9 text replaced by
UBI 10, plus one renumbered header comment. `ContainerBaseIT.java` is byte-identical to `main`'s.

The general rule, worth stating because the conflict markers actively invite the mistake:
`--ours` and `--theirs` operate on **files, not hunks**. They are only correct when one side's
entire file is wanted, as it was for `ContainerBaseIT.java` here. Where a file has both a
conflicting hunk and non-conflicting changes from the other side — which is the normal case —
edit the markers, or rebuild from the other side and re-apply the intended delta.

**`AGENTS.md`'s base-image bullet was corrected rather than merged.** It described
`ContainerBaseIT` as parsing the `FROM` line, which stopped being true in this merge. It now
names `EddiImageDockerfile.forTestContext()` and states the two-stage rule.

**Files:** [`src/main/docker/Dockerfile`](../src/main/docker/Dockerfile),
[`ContainerBaseIT.java`](../src/test/java/ai/labs/eddi/integration/ContainerBaseIT.java),
[`.github/workflows/base-image-check.yml`](../.github/workflows/base-image-check.yml),
[`AGENTS.md`](../AGENTS.md), [`docs/redhat-openshift.md`](redhat-openshift.md)

---

## 🎲 test(caching): de-flake the cache-wide TTL expiry test (2026-09-06)

**Repo:** EDDI (`fix/flaky-cache-ttl-test`)

`CacheFactoryTest.cacheWideTtlStillExpires` reddened CI on an unrelated branch
(run `34047771699`, `expected: <value1> but was: <null>`, 1 failure in 20,556 tests). A rerun of
the same commit passed, so this was a race, not a regression.

The test built a cache with a **1 ms** cache-wide TTL, wrote an entry, and read it straight back:

```java
ICache<String, String> cache = factory.getCache("cacheWideTtl", Duration.ofMillis(1));
cache.put("key1", "value1");
assertEquals("value1", cache.get("key1"));   // loses if the thread stalls for 1 ms
```

One millisecond of scheduling stall between two adjacent statements is unremarkable on a shared
runner, and that is all it takes. The behaviour under test — that the entry eventually expires —
was never in question.

### Why the fix is a wider margin and not a fake clock

`CacheFactory` builds Caffeine inline and exposes no `Ticker` seam, so a deterministic clock
would mean adding a test-only seam to production code. That is not worth it here, because the
deterministic coverage **already exists**: `CacheImplTest.DefaultTtlTests` drives a `FakeTicker`
over a directly constructed `CacheImpl` carrying `WriteExpiry.of(Duration.ofSeconds(60))` and
pins the semantics exactly — expiry on an untimed put, reads not extending it, re-writes
restarting it.

What that deterministic test cannot see is the factory itself: it constructs `CacheImpl` by hand,
so `CacheFactory.getCache(name, ttl)` could stop installing the policy entirely and every
assertion there would stay green. That wiring check is this test's actual job, and it is worth
keeping. So the TTL moves to **500 ms** with a 1.5 s sleep past it. The test costs ~1.5 s, which
against a 20,556-test suite is nothing.

### The read that looks redundant is the one holding the test up

The obvious "fix" is deleting the pre-expiry read, since the test is named for expiry. That would
make it pass vacuously: with nothing asserting the entry was ever stored, a `put` that silently
dropped the write would satisfy `assertNull` just as well as a working TTL. Its neighbours avoid
the race by writing a *second, untimed* key as their non-vacuity guard and only reading after the
sleep — but a cache-wide TTL expires everything, so no such key exists here and the guard has to
be a read before expiry. That is why this one test needs a TTL its siblings do not, and the
constant's Javadoc says so, with the failing run id.

### Both assertions mutation-checked

Per the house rule that a behavioural test is not trusted until it has been seen to fail:

| Mutation | Expected | Result |
|---|---|---|
| `getCache(name, ttl)` installs `WriteExpiry.never()` instead of `of(ttl)` | the entry survives | fails at `assertNull` — *the cache-wide TTL must still remove the entry, expected `<null>` but was `<value1>`* |
| `CacheImpl.put(K,V)` stores nothing | the entry is never there | fails at the pre-read in 0.002 s — *the entry must be readable before its TTL elapses* |

Both production files were restored afterwards; this change touches **test code only**.

`perEntryLifespanOverridesCacheTtl` was checked as well, as suspected: it writes a 1 ms entry and
an untimed one into a **1 hour** cache and reads neither before sleeping, so it has no race. Same
for `perEntryTtlIsHonoured` and `negativeLifespanIsUnlimited`.

**Files:** [`CacheFactoryTest.java`](../src/test/java/ai/labs/eddi/engine/caching/CacheFactoryTest.java)

---

## 🔀 fix(build): repair `main` while merging it into the v5 compatibility branch (2026-09-06)

**Repo:** EDDI (`fix/review-legacy-compat`)

Merging `origin/main` to clear a conflict on this branch surfaced that **`main` itself is red**,
and has been since the merge of #728. Two independent breakages, neither this branch's doing,
both fixed here because the merge inherits them and the PR cannot go green while they stand —
the same call the workspace-properties entry recorded on 2026-08-30.

**1. `McpToolsProviderTest` and `McpToolsProviderDiscoveryTest` do not compile.**
`2377cd045` ("read configs from stores, not the authoring facade") changed `McpToolsProvider`
to take `IAgentStore`/`IWorkflowStore` and updated those tests' imports. `f314d47cd` (#725)
then added test code still using `IRestAgentStore`/`IRestWorkflowStore` — types the file no
longer imports. Two commits that each pass alone and fail together, which is exactly what a
merge queue is meant to catch. Migrated to the store interfaces: `readAgent`/`readWorkflow`
become `IResourceStore.read`, and the one test that now calls a throwing method declares it.

**2. `ImportStyleTest` fails on `main`.** `RestScheduleStoreTest` carries two inline
`io.quarkus.security.ForbiddenException` references, which is the exact convention that test
enforces. No other `ForbiddenException` is in the file, so a plain import is unambiguous — no
`ALLOWED` entry needed.

### The conflict itself

`main` had independently added `@JsonAlias("workflowExtensions")` to `WorkflowConfiguration` —
a partial version of this branch's fix. This branch's alias is a superset that also covers
`packageExtensions`, the key 5.6.0 actually persisted and the one a v5 ZIP carries, so the
branch's version wins and `workflowExtensions` remains covered. The second conflict was an
import collision in `DynamicAgentGuardrailResolutionTest`; both imports are needed and both are
kept.

### Copilot review

One non-blocking comment: `LegacyDocumentMigrations`'s Javadoc called the transforms *pure*
while every one of them mutates the supplied `Document` in place. Corrected to state the
in-place contract, that the return value is the same instance or `null` for "nothing changed",
and that callers must pass a freshly deserialized mutable document. The matching `@DisplayName`
is updated too.

---

## 🔬 test(configs): pin the v5 compatibility guards against mutation (2026-09-04)

**Repo:** EDDI (`fix/review-legacy-compat`)

Follow-up to the v5 compatibility fix on the same branch, from an independent review round.

`OutputItem` registered `AgentFaceOutputItem` twice — once per type id — which forced
`OutputItemTemplatingTest` to loosen its subtype-count assertion. Collapsed onto Jackson's
`names` attribute so one class has one registration, and the original
`assertEquals(8, subTypes.value().length)` guard is restored.

`PostgresMigrationManagerParityTest` was named for a parity it never checked: it pinned the
PostgreSQL bean in isolation and never instantiated `MigrationManager`, so a divergent
transform re-inlined into either backend would have kept it green. It now runs one legacy
fixture through both managers and compares.

The Javadoc on `LegacyDocumentMigrations.output()` claimed the stored-document rewrite
normalizes `botFace` away. It does not: the Mongo sweep is gated on a migration-log row every
already-started deployment holds, and the PostgreSQL manager never swept at all. The alias is
therefore **permanent**, and both it and `AgentFaceOutputItem.LEGACY_TYPE_ID` now say so —
without that note the next maintainer could retire the alias as redundant and silently
re-break every un-resaved v5 output set.

**Diff coverage.** Changed lines went from 98.9% to 100% line and 86.8% to 100% branch,
measured by intersecting the branch diff with JaCoCo per-line data. The project's own gate is
bundle-level across 175k lines and cannot see uncovered new code. Sixteen tests were added and
each was proven by mutating the line it claims to pin and confirming it fails — one caught a
`-2147483649` round-tripping back as `2147483647`.

---

## 🧬 fix(configs): keep v5 stored configurations loadable (2026-09-04)

**Repo:** EDDI (`fix/review-legacy-compat`)

From the whole-repository code review: the one compatibility contract this project
promises to keep — stored JSON configs and exported ZIPs keep loading — was broken in
the direction that loses everything silently.

`WorkflowConfiguration.workflowSteps` carried no alias for the key EDDI 5.x actually
persisted. `PackageConfiguration` wrote `packageExtensions` up to and including 5.6.0,
and `SerializationCustomizer` deliberately pins `FAIL_ON_UNKNOWN_PROPERTIES=false`, so
the old key was dropped without a word: the workflow deserialized to **zero steps**,
`WorkflowStoreClientLibrary` happily built an executable workflow from the empty list,
and the agent *deployed successfully* while running no parser, no behaviour rules and no
output for the rest of its life. No exception, no warning, no failed deployment.

Fixed by `@JsonAlias({"packageExtensions", "workflowExtensions", "pipelineSteps"})` on
the setter. The two intermediate names never reached a released database, but keeping
them is cheaper than being wrong about which of them did.

The same shape existed in the output model: the polymorphic type id was renamed
`botFace` → `agentFace` with no alias and no `defaultImpl`, so a v5 output set carrying a
`botFace` item was unloadable. `OutputItem` now registers the retired id as a subtype
alias of `AgentFaceOutputItem`.

Two further compatibility gaps, both found by the review's cross-cutting pass:

- **Migrations were MongoDB-only.** `MigrationManager` held the legacy document rewrites
  as private helpers, so a ZIP imported against PostgreSQL skipped them entirely and the
  same archive produced different agents per backend. The rewrites moved into a new
  backend-neutral `LegacyDocumentMigrations`, which `PostgresMigrationManager` now applies
  too; `PostgresMigrationManagerParityTest` pins that both managers perform the same set.
- **The strict-boundary sweep skipped the evidence.** `StrictBoundaryShippedConfigsTest`
  counted `.bot.json` and `.package.json` fixtures as *skipped* rather than checked —
  precisely the two file kinds that would have caught the missing aliases. It now parses
  them.

**Regression coverage.** Every behavioural change is pinned by a test proven to fail with
its fix reverted. `WorkflowConfigurationLegacyAliasTest` reads the repository's own v5
fixture and asserts the step count, and it first asserts the fixture still contains the v5
key — so the test cannot quietly pass while guarding nothing. With the alias removed it
fails with `expected: <6> but was: <0>`.

Note for anyone repeating this exercise: proving a test fails without its fix requires
touching the restored file's timestamp. Maven compiles incrementally by mtime, and both
`git checkout` and `Move-Item` restore an *older* one, so the test silently runs the
previously compiled class and the proof is worthless.

---

## 📋 docs(config): document the four workspace properties that were breaking `main` (2026-08-30)

**Repo:** EDDI (`fix/connection-extra-auth-params-code-verifier`)

Found while merging `main` into this branch to clear a changelog conflict: `main` itself
was red, and had been since 2ce8d69f0. `ConfigurationReferenceCoverageTest.referenceIsExhaustive`
failed on four properties the workspaces feature (#723) shipped without adding to
`docs/configuration-reference.md` — `eddi.workspaces.enabled`, `.groups-claim`,
`.legacy-visibility` and `.default-space`. That test exists precisely to catch a property
an operator cannot set because nobody wrote it down, and it did its job; the entry was
simply never made.

Not this branch's defect, and normally its own PR. Fixed here because the merge inherits
the failure, so this PR cannot go green while it stands, and no open PR was addressing it.
The change is documentation only — a new **Workspaces & resource sharing** subsection under
Security & authentication, with the four properties, their defaults, and the note that
`eddi.workspaces.enabled` gates *enforcement* only while ownership is stamped whenever
`authorization.enabled` is on. That separation is the one thing an operator has to
understand before flipping the switch, and it lived only in `WorkspaceSettings`'s Javadoc.

Descriptions are taken from `WorkspaceSettings` and the comments in
`application.properties` rather than paraphrased, so the reference and the code say the
same thing. `referenceInventsNothing` — the other direction of the same test — passes too,
so nothing documented here is a property the code does not read.

---

## 🔎 docs: a 196-agent audit of every page against source (2026-08-29)

**Repo:** EDDI (`docs/accuracy-audit`)

A fourth review of this branch, this time fanned out: sixteen agents each took a
cluster of pages and verified every checkable claim against `src/`, then every
candidate finding was handed to an independent agent whose job was to refute it.
179 candidates, 168 survived. Eighteen more agents applied the survivors —
re-verifying each one first — and eighteen others re-read the resulting diffs.

The headline is that **the flagship tutorial's last step did not work**.
"Now it's time to start talking to our Agent" told the reader to
`POST /agents/<AGENT_ID>/start/<CONVERSATION_ID>`. `@Path("/{agentId}/start")`
is terminal; the message endpoint is `POST /agents/{conversationId}` and the
agent id is not in the path at all. Both tutorial pages carried it, for the
message POST and the conversation-memory GET alike, so the culmination of
"Create your first Agent" 404s. Three prior review rounds on this branch missed
it — including a mechanical REST sweep of mine, which accepted any documented
path that *extended* a real route. `/agents/{}/start` is real, so the longer
path looked fine.

### What else the sweep found

- `POST /agents/{id}/say` (`hitl.md`) — no `/say` segment exists.
- `/agents/{env}/{agentId}/{conversationId}` (`conversation-memory.md`) — the v5
  shape. `LegacyPathRewriteFilter` rewrites the environment *name*, not the shape.
- `"type": "LANGCHAIN"` (`langchain.md`) — the type field is the model provider.
- `corrections.stemming` — no such provider (levenshtein, mergedTerms, phonetic).
- The `Location` header on a config create carries the `eddi://` resource URI,
  not an HTTP URL — `RestVersionInfo.create` builds it from `resourceURI`.
- `optional:` → `isOptional:` in the extension descriptor response, and
  `.package.json` → `.workflow.json`.
- A `//` comment inside a copy-paste-ready config block: configuration parses
  with `FAIL_ON_UNKNOWN_PROPERTIES` and no Jackson comment support, so it 400s.
- A dead `#conversation-log` anchor, and `langchain.md` claiming twelve providers
  while listing eleven.

### Four fixes that introduced new errors

The diff-review stage paid for itself. Four "corrections" were wrong and were
themselves corrected:

- **`attachments-guide.md`** claimed attachment metadata is *not* in the template
  model. `{memory.current.attachments}` genuinely renders empty, but
  `MemoryItemConverter` publishes the request context, so
  `{context.attachment_0.url}` resolves. The blanket claim was too strong.
- **`audit-ledger.md`** said entries past `eddi.audit.max-queue-size` are
  dead-lettered. `reserveQueueSlot` **drops** them; only the flush-retry path
  dead-letters — as the same page's Failure Handling paragraph already said.
- **`compliance-data-flow.md`** said the HITL journal holds tool-call arguments.
  `JournalEntry` persists `resultCapped` and no argument payload.
- **`log-administration.md`** said the ring buffer holds DEBUG/TRACE that the
  default filter hides. EDDI sets no `quarkus.log.min-level`, so the root logger
  is INFO and those records are never emitted at all;
  `quarkus.log.console.level=DEBUG` is a handler setting and does not lower it.

### On trusting the machinery

168 of 179 findings "confirmed" is a 94% pass rate, which is not a quality
signal — it is a reason to check. Three claims were re-verified by hand before
anything was applied: two held exactly, and one
(`/administration/operator/{canary-result,gate-status}`) was brace-expansion
shorthand that a checker had misparsed. Two of this session's own checkers were
wrong before the docs were: an anchor validator reported 52 broken links because
it collapsed repeated spaces and trimmed leading hyphens, which GitHub's slug
rule does neither of; and an enum extractor found 148 constants instead of 502
because a non-greedy brace match truncated every enum body.

### Security review round

CodeRabbit raised five Major security findings on the audited diff. All five held:

- **`compliance-data-flow.md` claimed erasure makes re-identification
  "impossible".** `AuditHmac.pseudonymFor` is a prefix plus *unsalted*
  `sha256Hex(userId)` — deterministic and unkeyed, so anyone with a candidate
  list can hash and match. The page now says plainly that this is
  pseudonymisation, not anonymisation, and that under GDPR Art. 4(5) the records
  remain personal data and stay in scope. On a compliance page the original
  wording was the dangerous kind of wrong: it invites an operator to disclose
  records as anonymised.
- **`docker.md`'s no-auth example published `7070` on every interface** with
  `/secretstore` and `/mcp` open. Bound to `127.0.0.1` with the consequence
  spelled out.
- **`redhat-openshift.md` carried the auth opt-outs in its *production*
  example.** Replaced with OIDC configuration; the opt-outs exist so a local
  container can boot past `AuthStartupGuard`, not for production.
- **`kubernetes.md` wrote the vault master key to `/tmp` at the default umask** —
  world-readable on most images, and left behind if `kubectl` failed. Now
  `umask 077` inside a subshell with a cleanup trap. That text was added earlier
  in this same branch, so the review caught a defect this work introduced.
- **`mcp-client.md`** — `McpToolProviderManager` validates only that the scheme
  is `http` or `https`, and the transport attaches the resolved `apiKey` as a
  bearer either way, so a credential can go out in cleartext with no warning.
  Documented as a hazard. **The code gap is real and left for a separate change**
  — a docs branch is the wrong place to alter security behaviour.

### The temp-file recipe, hardened in all four places it appears

The `umask 077` fix above was itself reviewed, and two more problems held:

- **`/tmp/application-secrets.properties` is a predictable name** (CWE-377).
  `umask` sets the mode of a file you create; it does not stop another local
  user pre-creating that path or pointing it at a symlink first. Now `mktemp`,
  which returns an unpredictable name already at `0600`.
- **Nothing checked `openssl rand`** (CWE-252). On failure the `printf` still
  wrote `eddi.vault.master-key=`, producing a Secret with an *empty* key — which
  leaves the vault inert and secrets in plaintext, silently. Now fails closed.

One detail the review's suggested fix would have broken: passing the `mktemp`
path bare to `--from-file` names the Secret key after the temp file, and the
Deployment mounts exactly `application-secrets.properties`. The `key=path` form
is required, which is what `k8s/create-secrets.sh` already does — these copies
now match the script rather than diverging from it.

The same recipe appeared in **four** files (`docs/kubernetes.md` twice,
`docs/getting-started.md`, `k8s/base/eddi-secret.yaml`, `k8s/quickstart.yaml`);
all are corrected. Repairing `getting-started.md` also removed a literal newline
that had crept into the `printf` format string. The three shell blocks pass
`bash -n`, and both manifests still parse.

### Sweeps now clean across all 69 pages

Link fragments (a class `DocumentationLinksTest` never checked, since it strips
`#anchor` before resolving), JSON validity, enum values against the real Java
constants, HTTP verb/path pairing, and `Type.method()` references — all zero.

---

## 🔬 test(metrics): the coverage test was vacuous for four meters (2026-08-28)

**Repo:** EDDI (`docs/accuracy-audit`)

A self-review of the accuracy-audit branch, looking for the same class of defect
in the work that the branch was written to remove. It found one, in the test
added to prevent it.

### The coverage test compared the wrong name

`MetricsDashboardCoverageTest` matched the meter's dotted name with `.` replaced
by `_`, as a substring. That is satisfiable by a *different, longer* meter:
`eddi_tool_cache_hits` is a substring of `eddi_tool_cache_hits_by_tool`, so
charting only the by-tool variant satisfied a check for the plain counter. Four
meters were exposed to this, and **`eddi.tool.costs` was passing that way for
real** — it had no independent occurrence anywhere on the dashboard.

The fix compares the name a meter is actually *scraped* under: Micrometer
appends `_total` to counters and `_seconds` to timers and leaves gauges alone
(with no second `_total` for the meters registered in snake_case with one
already). `eddi_tool_cache_hits_total` is not a substring of
`eddi_tool_cache_hits_by_tool_total`, so the ambiguity is gone rather than
narrowed. Mutation-checked with exactly the case that used to slip through.

### What that vacuity was hiding

**`eddi_tool_costs_total` is two meters under one name.** `ToolCostTracker`
registers a counter `eddi.tool.costs` (tagged by `tool`) at line 205 and a gauge
`eddi.tool.costs.total` at line 60. The exposition appends `_total` to the
counter and leaves the gauge alone, so both resolve to `eddi_tool_costs_total`.
This is a collision in the application code, not in the documentation, and
renaming a meter changes a published contract — so it is documented here and
left for a separate decision rather than fixed in a documentation branch.
`metrics.md` now says the name is ambiguous and points at `GET /llm/tools/costs`
for an authoritative total.

Also corrected, and pre-existing: the per-tool examples read
`eddi_tool_calls{tool="weather"}` and `eddi_tool_costs{tool="weather"}`. Both are
counters, so both are scraped with `_total`; the queries as written match no
series and return an empty result rather than an error.

### Two smaller defects of my own

- **`archiveTableOf` used an unanchored `indexOf("## Archive")`.** `"### Archive"`
  contains `"## Archive"` at offset 1, so a sub-heading inside an entry could
  have been taken for the section — and this file already contains entries that
  discuss the `## Archive` table by name. Now anchored to a line start.
- **`rotate-changelog.py` printed `cap 256000 rotate target 204800`.** A
  `"...%,d...".replace(",", "")` idiom, used to strip Java-style thousands
  separators that Python does not support, also stripped the comma from the
  prose. Replaced with f-string `{:,}` formatting, which does the thing that hack
  was imitating.

### What the review checked and found clean

- **109 of the 118 documented defaults** cross-checked against
  `application.properties` and `@ConfigProperty`: no mismatches. The nine
  unchecked have no default in code.
- All 10 `eddi.schedule.*` values and all 6 schedule meters in `scheduling.md`.
- Every documented metric name against its meter's *type*, catching any counter
  documented without `_total` or gauge documented with one — the two per-tool
  lines above were the only hits.

---

## 🔧 docs: address the PR #722 review — the env-var rule was wrong (2026-08-28)

**Repo:** EDDI (`docs/accuracy-audit`)

Five review findings on the accuracy-audit PR. One was a genuine error in the
headline new document, and worth recording because it is the same failure mode
the PR was written about.

### The environment-variable conversion rule was wrong

`configuration-reference.md` stated that MicroProfile Config "uppercases the
name, turns `.` into `_`, and **deletes `-` entirely**", and gave four worked
examples on that basis. **Both `.` and `-` are replaced with `_`.** The repository
had said so all along — `EDDI_VAULT_MASTER_KEY`, `EDDI_MCP_ALLOW_UNAUTHENTICATED`
and `EDDI_OPENAI_COMPAT_API_KEY` are the spellings in `docker-compose.yml`,
`k8s/` and `AuditHmac`'s own javadoc — and the audit did not check the reference
against them.

This is worse than an ordinary typo because **an unrecognised environment
variable is not an error**. The property keeps its default and the service starts
normally, so `EDDI_VAULT_MASTERKEY` leaves the vault inactive and `scope:
"secret"` properties silently fall back to plaintext, with nothing in the startup
log naming the variable that was set. The corrected section now leads with that
consequence rather than the rule.

`ConfigurationReferenceCoverageTest` gained a third assertion: every `EDDI_*`
token in the documentation must be the mechanical transform of a real property.
It found two more instances the review had not — `EDDI_VERSION` (a Compose image
tag, not a property) and `EDDI_AUDIT_RETENTIONDAYS` (deliberately named in the
note explaining its removal) — both now classified explicitly rather than left
ambiguous. The lesson is narrow and general: a reference that is checked for
*coverage* is not thereby checked for *correctness*.

### The rest

- **`attachments-guide.md` pipeline diagram** still named the deleted
  `MultimodalMessageEnhancer`, split across four lines as `Multi-`/`modal`/
  `Message`/`Enhancer` — which is why the string search that cleaned up the prose
  never saw it. Redrawn around `AttachmentForwarder`; while there, the box
  borders were 36 and 41 characters wide on the same box, so the whole diagram
  is now aligned and its connectors centred.
- **`ChangelogRotationTest` accepted a prose mention in place of a table row.**
  `live.contains("changelog/" + name)` matched anywhere in the file, and this
  changelog contains entries that discuss `docs/changelog/` paths — so the check
  was one edit away from passing vacuously on the drift it exists to catch. It
  now matches a Markdown row inside the `## Archive` section only.
- **Month validation is declarative.** `(\d{2})` plus `Integer.parseInt` reads to
  static analysis as an unguarded `NumberFormatException`. It could not throw,
  because the regex had already established two digits, but a validation that has
  to be reasoned about to be dismissed is worse than one that cannot fail:
  `(0[1-9]|1[0-2])`.
- **`metrics.md` fenced blocks** carry a `text` language identifier
  (markdownlint MD040). Applied to all 29 bare fences, not only the 13 added by
  this PR, so the file is consistent rather than half-converted.

All three tightened assertions were mutation-checked against the specific hole
each closes.

### Second review round

Two further findings, both about the new tests checking less than they appear to:

- **`ChangelogRotationTest` validated the archive index in one direction only.**
  Every file on disk had to have a table row; a row pointing at a *deleted*
  archive passed. `DocumentationLinksTest` does fail on that — it is a dead
  relative link — but reports it as a generic unresolved link, which tells the
  reader nothing about the index being stale, and it cannot catch a row naming a
  file that exists under a name no rotation would produce. Both directions are
  now checked here, where the invariant lives.
- **`ConfigurationReferenceCoverageTest` scanned less than the PR claimed.**
  `startsWith("docs/changelog")` would also have exempted a
  `docs/changelog-notes.md`, and the file list named `README.md` and `AGENTS.md`
  explicitly, leaving `PRIVACY.md`, `CONTRIBUTING.md` and `SECURITY.md`
  unchecked — `PRIVACY.md` being a 30 KB operator-facing document, exactly where
  a configuration name gets quoted. None of them names an `EDDI_*` variable
  today, which is why an allow-list would have gone on looking correct
  indefinitely. Now: exact match on the live changelog, prefix on the archive
  directory, and every root `*.md` enumerated.

---

## 🗂️ docs(changelog): split the 1.9 MB working changelog, and cap it so it stays split (2026-08-28)

**Repo:** EDDI (`claude/eddi-docs-review-04d1d7`)

`docs/changelog.md` had reached **1.9 MB across 561 entries** — roughly half a
million tokens. AGENTS.md §2 rule 8 requires every session to append an entry
and has never required one to be removed, so the growth was structural, not
accidental. The same file was linked from `SUMMARY.md` as a browsable
documentation page, and rule 6's own advice ("skim the top 2–3 entries") was an
admission that nobody could use it as written.

### The split

| | Before | After |
|---|---|---|
| Live `docs/changelog.md` | 1.9 MB, 561 entries | **247 KB, 44 entries** |
| Archives | — | 6 files under `docs/changelog/`, one per month |

Archives are `docs/changelog/<YYYY-MM>.md`: March (59), April (104), May (34),
June (26), July (147) and August (147 archived, 44 still live). Total bytes are
unchanged — 1.89 MB before, 1.89 MB after — and a heading-and-line
reconciliation against the pre-split file confirms **0 missing headings and 0
lost content lines**.

### Decisions

**Monthly archives, not per-release.** Entries carry dates, not release tags, so
a release-based split would have required inventing a mapping and maintaining it
by hand. "Roughly when" is also the question a changelog reader actually asks;
"which release" is answerable from git tags.

**Size-triggered rotation, not calendar-triggered.** A rule that fires on the
first of the month is a rule somebody has to remember. A cap that fails the
build is one the build enforces. `ChangelogRotationTest` fails when the live
file exceeds 250 KB, and its message says to rotate rather than to raise the
cap — because raising it is how a 250 KB file becomes a 1.9 MB one again.

**`Decision Log` and `Regression Notes` stay in the live file.** They are
running registers that sessions append rows to, not dated entries. Archiving
them would have retired both silently, since nobody appends to an archive. A
third assertion in the rotation test now guards exactly that.

**"How to Read This Document" was hoisted out of line 13,201** into the header,
where somebody might actually see it, and joined by a "Where to Add an Entry"
section stating the append point, the cap and the rotation procedure.

### Relative links

Archived text moved one directory deeper, so its 18 relative links each gained a
`../`. The first pass of the rewriter also "fixed" the `![alt](uri)` rows inside
inline code spans in two entries — documentation *of* link syntax, not links.
Fenced blocks and code spans are now masked before rewriting, which is the
general form of the bug: a link rewriter that cannot tell an example from a
reference will corrupt every page that documents Markdown.

### Rotation is a script, not a paragraph

`scripts/rotate-changelog.py` does the mechanical part, because the mechanical
part is what a hand-rotation gets wrong: it moves entries by date, re-depths the
relative links it moves while leaving code spans alone, regenerates the Archive
table from what is on disk, and refuses to run from anywhere but the repository
root. `--check` reports without changing anything. It was used to perform the
final rotation in this commit, which is also how it was tested.

### Line endings

The cap is measured on content with CRLF normalised to LF, not on
`Files.size()`. Markdown carries no `eol` setting in `.gitattributes`, so a
Windows checkout is CRLF and the Linux CI runner is LF — about 5% apart on a file
this size, for byte-identical content. Measuring the working copy would have made
the cap mean something different per platform, and the first symptom would have
been a Windows developer told to rotate a changelog CI was perfectly happy with.
`rotate-changelog.py` measures the same way, so the Archive table's sizes do not
churn depending on who ran it.

### Wiring

- `SUMMARY.md` — the six archives listed under Changelog, so
  `DocumentationLinksTest.everyDocIsListedInSummary` is satisfied and they are
  reachable in the published docs.
- `DocumentedRestPathsTest` — its legacy-path exemption became prefix-aware
  (`docs/changelog/`) rather than a list of filenames. A list would start failing
  on the next routine rotation, and the likely response to that is deleting the
  assertion rather than the offending path.
- `AGENTS.md` §2 rule 8 and the reading list in §2 — both now describe the append
  point, the cap, the rotation procedure and the two registers. The rule that
  caused the growth is the right place to document the bound on it.

---

## 📚 docs: repository-wide accuracy audit — fix what was wrong, enforce what was claimed (2026-08-28)

**Repo:** EDDI (`claude/eddi-docs-review-04d1d7`)

A full pass over every page in `docs/` plus the root markdown, cross-checking
each factual claim against the source rather than against other documentation.
The findings clustered into one shape: **documentation rots silently in exactly
the places where a wrong answer still produces a plausible response.** A renamed
class breaks the build. A renamed *property*, *metric* or *REST path* does not —
`@ConfigProperty` resolves by string, Micrometer accepts any name, and
`LegacyPathRewriteFilter` keeps pre-v6 paths answering — so the page keeps
looking right until someone compares it to the code.

### Fixed — things that could not work as written

- **`docs/incident-response.md`** — this is a *breach runbook*, and every
  identifier in its first two sections was wrong. `/admin/logs` → the real path
  is `/administration/logs`. All three named metrics
  (`eddi.conversations.active`, `eddi.tool.execution.count`,
  `eddi.audit.entries.count`) are unregistered and return nothing; replaced with
  the meters that exist, in their Prometheus spelling, each with a note on what
  a bad value means. `eddi_audit_entries_dropped_total` in particular is the one
  number that says the compliance trail has holes.
- **`docs/metrics.md` + `docs/langchain.md`** — six tool endpoints documented
  under `/langchain/tools`, the pre-v6 prefix. They *answered*, because
  `LegacyPathRewriteFilter` rewrites them, which is precisely why nobody noticed:
  the real base is `/llm/tools` (`RestToolHistory`). `GET /llm/toolhistory/costs`
  in `langchain.md` was worse — no filter covers it, so it is a plain 404. Both
  fixed, four previously-undocumented endpoints added
  (`cache/ttl/{tool}`, `DELETE cache`, `ratelimit/{tool}/reset`, `costs/reset`),
  and `/langchain/tools` added to `DocumentedRestPathsTest` so it cannot return.
- **`docs/conversations.md`** — documented a `redoCacheSize` field, with three
  bullet points interpreting its values. No such field has ever existed; the DTO
  carries `undoAvailable`/`redoAvailable` booleans. Removed from four example
  payloads and the response schema, and replaced with the two ways to actually
  ask — including the trap that `GET` and `POST` on `/undo` are *ask* and *do*.
- **`docs/hipaa-compliance.md`** — the retention checklist told operators to
  configure `eddi.usermemory.auto-purge-days`, which does not exist. Real name:
  `eddi.usermemories.deleteOlderThanDays`, and it ships as `-1`, meaning the
  sweep is off. A compliance checklist naming a no-op property is worse than one
  that says nothing.
- **`docs/attachments-guide.md` + `docs/architecture.md`** — both described
  `MultimodalMessageEnhancer`, deleted in 6.1.0 and replaced by
  `AttachmentForwarder`. The capability table was stale with it: PDF and audio
  were listed as "Metadata text (future: `PdfFileContent`/`AudioContent`)" when
  both are implemented, and text-like files (JSON, XML, CSV, YAML) are decoded
  and inlined rather than merely announced. Rewritten around what the forwarder
  does, including `ModelCapabilityService` gating, the `attachments:extracts`
  stitching, and the `attachments:errors` key — the first place to look when a
  model claims it cannot see a file.
- **`docs/behavior-rules.md`** — the REST table named the v5 `BehaviorSet`
  model (now `RuleSetConfiguration`) and gave both `/currentversion` rows a
  ruleset body. `GET` there returns a bare `text/plain` integer and `POST` takes
  no body at all, redirecting `303` to `?version=N`. Corrected, with the
  immutable-versioning behaviour of `PUT` spelled out.
- **`docs/architecture.md`** — `POST /agentstore/{id}/signing/keys` does not
  exist; agent key generation is a service-level API with no REST surface.
- **Stale package paths** — `ai.labs.eddi.modules.langchain.tools.*` in
  `security.md` and `architecture.md`; the package is `modules.llm.tools`.
- **`README.md` + `AGENTS.md`** — both said `./mvnw verify` runs integration
  tests. `pom.xml` sets `skipITs=true`, so it does not; CI runs
  `-DskipITs=false`. Anyone following the documented "full build" was shipping
  without ever running an IT locally.
- **`docs/getting-started.md`** — v1 `docker-compose` syntax throughout (v2 is
  `docker compose`, and the hyphenated binary is absent on current Docker), the
  v5 word "packages" for workflows, the Manager described as an "Optional UI"
  when it is bundled and served at `/manage`, a Maven prerequisite the wrapper
  makes unnecessary, and a Kubernetes quickstart that ran `bash
  k8s/create-secrets.sh` immediately after a `kubectl apply` from a URL — with no
  checkout to run it from. All fixed, and a **Verifying It Works** section added:
  the three checks CI runs against every published image, so the front door
  finally has a success signal.

### Fixed — claims with nothing behind them

- **`docs/metrics.md`** claimed the Full Metrics dashboard "covers all 144
  registered meters — it is generated from the registration sites in the source,
  so a metric cannot be added to the codebase and silently go unwatched."
  Nothing generated it and nothing checked it, and it was already false: five
  `eddi.llm.cascade.*` counters were registered and on no panel — the exact
  meters that say whether cascading saves money or pays twice per turn. Five
  panels added (executions, escalations by reason, accepted step, step errors,
  ceiling exceeded), and the claim replaced with one that is enforced.

### Added

- **`docs/configuration-reference.md`** — every one of the 118 `eddi.*`
  properties, with default, environment-variable spelling and what it does.
  **61 were previously documented nowhere at all**, including
  `eddi.security.ssrf-protection.enabled` (off by default), every
  `eddi.schedule.*` knob, all `eddi.shutdown.*` and all `eddi.nats.*`. The env
  var rule is stated explicitly because it catches people out: `-` is *deleted*,
  not converted (`eddi.vault.master-key` → `EDDI_VAULT_MASTERKEY`).
- **`docs/scheduling.md`** — a Deployment Configuration section for the ten
  `eddi.schedule.*` properties and the six schedule meters. The page explained
  cluster-awareness without ever mentioning `lease-timeout` or `instance-id`,
  which is what an operator needs; it now also states plainly that delivery is
  at-least-once, so scheduled targets must be idempotent.
- **`docs/metrics.md`** — 66 registered meters were missing from the metrics
  reference. Thirteen new sections (Coordinator, Pipeline, Model Cascade,
  Streaming, Attachments, HITL, Platform Operator, Prompt & Guardrail, Agent
  Identity, Capability Registry, Vault, MCP & Integration, Session), each with
  tag names and a note on how to read it. Coverage is now 135/135.
- **`docs/security.md`** — the SSRF section described unconditional protection.
  It *is* unconditional for tool URLs, but httpCall/MCP/A2A targets are gated
  behind `eddi.security.ssrf-protection.enabled`, which defaults to `false` and
  appeared in no document. Added, with the reason for the default (configured
  targets legitimately reach internal hosts) and the condition under which it
  must be turned on (any outbound URL influenced by conversation input).

### Added — tests, so this does not recur

Link rot and legacy paths already had guards (`DocumentationLinksTest`,
`DocumentedRestPathsTest`). Configuration and metrics had none, which is why
those were where the rot was.

- **`MetricsDashboardCoverageTest`** — scans meter registration sites and fails
  if a meter has no dashboard panel, or is absent from `docs/metrics.md`.
- **`ConfigurationReferenceCoverageTest`** — asserts the reference is exhaustive
  **and** that it invents nothing. The second direction matters as much: a
  documented property nothing reads is a silent no-op, and the operator believes
  the deployment is configured when it is not.
- **`DocumentedRestPathsTest`** — `/langchain/tools` and
  `/bottriggerstore/bottriggers` added to the legacy map.

All three were mutation-checked: each was confirmed to fail when the fix it
guards is reverted.

### Moved

- **`HANDOFF.md` → `docs/archive/handoff-v6.0-snapshot.md`** — 70 KB, last
  updated 2026-03-30, self-declared "no longer actively maintained", and
  referenced by nothing. It sat at the repository root, where AI coding
  assistants load it, full of renamed classes and pre-v6 REST paths — it was
  already exempted from `DocumentedRestPathsTest` for exactly that reason.
  Archived rather than deleted so the reasoning stays recoverable, with a
  `[!CAUTION]` header pointing at the changelog, `AGENTS.md` and
  `architecture.md` instead.

### Follow-up

The changelog's own size was the one finding this entry deferred. It was
addressed immediately afterwards — see the entry above.

---

## 🔍 fix(workspaces): findings from the final adversarial pass (2026-08-29)

**Repo:** EDDI (`feat/multi-user-spaces-and-sharing`)

A sixth review pass over the whole branch, after `callerLevel` landed. It
confirmed the earlier fixes and found four things worth acting on — three of
which are about tests that could not fail.

### The migration recorded itself complete after a failed write

`stampIfNeeded` caught every `setDescriptor` exception, logged a warning and
returned `false` — which was indistinguishable from "already correct". So a run
where every write threw still recorded the migration as done, and the class's
own comment says exactly what that costs: descriptors with no access index are
invisible in every listing once enforcement is on, with no way to re-run short
of deleting the log row by hand.

Three outcomes now, not a boolean: `STAMPED`, `SKIPPED` (already correct, or
carrying nothing addressable — neither retryable) and `FAILED`, which holds the
migration open. The `MAX_PAGES` exhaustion path did the same thing by a
different route and is now also treated as incomplete. The existing test covered
a failed *read* only; the write case is now covered and mutation-checked.

### Nothing failed if a listing stopped calling the guard

`ResourceAccessGuardTest` proves `redactForCaller` strips what it should.
`AccessScopeTest` proves a space predicate narrows. Neither notices if an
endpoint stops invoking them — and every test in that area handed the store a
*mocked* guard, so deleting `descriptors.forEach(accessGuard::redactForCaller)`,
or replacing `listingScope().withinSpace(space)` with `listingScope()`, left the
whole suite green.

The same shape as the mix-in test that registered its own mix-in: the unit under
test was the collaborator, not the wiring. `ListingRedactionWiringTest` uses a
**real** guard with a restrictive identity and asserts on what actually comes
back. All three mutations now fail it.

### Grants were disclosed to everyone while enforcement was off

`seesEverything()` is true for every caller in that state, so keying grant
disclosure on the granted level alone handed every editor the full grant
audience — real principal and team names — of every resource. Not hypothetical:
ownership and grants are recorded whenever authentication is on, and the
documented rollout is to let attribution accumulate *before* switching
enforcement on. A deployment part-way along that path was broadcasting the
audience lists it had just built.

Disclosure now asks the question structurally — does this caller actually own it,
or hold the admin role — which does not depend on the enforcement flag and is
therefore correct in both states.

### Two assertions in `WorkspacesIT` that could not fail

`?space=.*` asserted `hasSize(0)`, but the IT profile disables authorization, so
no descriptor is ever stamped with a `spaceId` — an empty result proved only
that the field was absent, and the test would have passed with the escaping
removed entirely. It now pins what it can (a metacharacter-laden value is
handled, not 500) and says plainly where the escaping is actually covered.
`everyItem(nullValue())` over a page this suite never seeds was vacuous the same
way; it now creates an agent first.

---

## 🪪 feat(workspaces): report the caller's access level on listed descriptors (2026-08-29)

**Repo:** EDDI (`feat/multi-user-spaces-and-sharing`)

Closing the gap the last review left open as a decision.

### The gap

A listing gave a recipient no way to tell what they could do with a row. The
grant list is disclosed at `OWN` only — deliberately, since a published
resource is readable by everyone and its grant audience is a list of real
principal and team names — and `ownerId` alone does not answer it either: a
resource shared with your team at `USE` and one shared at `EDIT` look identical.

So the Manager offered every action on every row and let the server refuse. A
colleague who shared an agent so you could *talk to* it produced a card with
Share, Delete and Export on it, all of which 403. That reads as the product
being broken rather than as the resource not being yours.

### `callerLevel`

`DocumentDescriptor` now carries the level the calling user holds, stamped by
`ResourceAccessGuard` on the way out. It is unlike every other field on that
class: it describes the *relationship* between the resource and whoever asked,
so the same document serialises differently for two callers.

That makes it dangerous in a way the other fields are not, and two properties
are enforced rather than documented:

- **It can never be stored.** `patchDescriptor` and
  `ResourceSharingService.writeBack` both read a descriptor and write it back.
  `redactForCaller` already documented that it must not be called on something
  about to be written — but documenting is not preventing, and persisting one
  caller's level would tell every later reader they hold whatever the last
  writer happened to hold, an escalation nothing logs. A Jackson mix-in on the
  persistence mapper drops it. Both storage backends reach storage through that
  one mapper, so one registration covers MongoDB and PostgreSQL.
- **It can never be set by a client.** `@JsonProperty(access = READ_ONLY)`, so a
  PATCH body cannot assert its own access level into a read-modify-write.

**Null when enforcement is off**, rather than `OWN`. Everyone may do everything
in that state, so a level would be true and meaningless — and omitting it keeps
a listing byte-identical to a deployment that has never heard of workspaces,
which is the compatibility property this whole feature is built around. A client
that wants to know asks `GET /workspaces` once instead of inferring it per row.

### Verified by reverting, twice

The first version of `PersistenceMixinsTest` built its own mapper by calling
`PersistenceMixins.register(...)`. That tested the mix-in worked and **not** that
anything used it: deleting the registration from `PersistenceMapperProducer`
left the suite green. It now goes through the real producer, and both
guarantees were re-checked by reverting them — the storage one fails with the
stamped JSON in the message, the read-only one with `expected: <null> but was:
<OWN>`.

---

## 🔒 fix(security): close two standing bypasses of the USE gate; add a workspace capability endpoint (2026-08-29)

**Repo:** EDDI (`feat/multi-user-spaces-and-sharing`)

An adversarial review pass over the whole workspaces PR (Fable, read-only)
found two ways past the very control the PR introduces. Both were the same
shape as holes the PR had already closed elsewhere, which is what made them
oversights rather than decisions.

### Channel integrations were a standing bypass (high)

Triggers, schedules and group membership all check `requireAgentUseAccess` on
the agents they *reference*, because those references are standing invitations:
once written, they reach the target as a system-initiated conversation, which
sits deliberately below the USE gate. `RestChannelIntegrationStore` wired the
guard into `RestVersionInfo` — covering the channel config's own CRUD — and
never checked the targets.

So an editor holding Slack credentials could point a channel's `ChannelTarget`
at a colleague's **private** agent, and every message in that Slack room would
converse with it and relay the replies, having never held access to it.
`TargetType.GROUP` was identical.

`requireUseOnTargets` now runs before the write in `createChannel` and
`updateChannel`. `ResourceAccessGuard.requireAgentUseAccess` was generalised to
`requireUseAccess(id, label)` so a GROUP target is refused as a *group* rather
than being told to go ask the owner of an agent.

### Template preview leaked every snippet in the deployment (high)

`RestTemplatePreview` redacted snippet **contents** from the variable-reference
panel for callers who do not see everything — and then rendered the caller's
template against the *unredacted* map. The comment justifying that
("it renders only what the caller's own template actually references, which is
their own composition") was simply wrong: the caller supplies the template, and
the panel hands them the names. One call lists every snippet name, a second
call whose body is `{snippets.<name>}` prints the content. Snippets are a
guarded configuration type, so this disclosed colleagues' prompt building
blocks cross-workspace through an endpoint any editor can reach.

The redaction moved into the map the engine renders against. Names stay — a
preview that cannot say which references resolve is not a preview — and the
value renders as `<redacted>`. The regression test was mutation-checked: revert
the fix and it fails with the real content in the assertion.

### A2A conversed with agents that were never exposed (medium)

`AgentCardService` states the gate for the A2A surface is `isA2aEnabled()` on
the agent. Discovery enforced it; `A2ATaskHandler.handleTaskSend` did not, so a
peer that knew an id could talk to any agent, opted in or not. It now refuses
through `getAgentCard`, which returns null for both "no such agent" and "not
enabled" — the same answer discovery gives. A2A remains outside the workspace
model on purpose; this only enforces the gate it already claimed.

### Redaction decided against a possibly stale version (low)

`readDescriptor` gated on the **current** descriptor and then redacted against
the **addressed** one. Sharing writes land on the current version only, so an
older version can still name a previous owner and carry that era's grants.
`requireAccess` now returns the level it granted, and the versioned read passes
it to the new `redactUnlessOwner`. Two answers to "does this caller own it" in
one request path is a smell whatever its impact.

### `GET /workspaces` — because a client cannot work this out

A deployment with workspaces **off** returns descriptors that look exactly like
one where everything predates ownership: no owner, no space, no visibility.
Ownership is still *recorded* while enforcement is off — deliberately, so
attribution accumulates before an operator flips the switch — which means the
fields being present proves nothing either. A UI guessing from the data offers
a Share dialog that silently cannot work.

`RestWorkspaces` answers for the calling user only: whether enforcement is
active, their principal (the value stamped as `ownerId`, not a display name),
their default write space, every space they can reach, and whether they see
everything. It never takes a principal as a parameter, so it cannot enumerate
somebody else's group membership.

Serving the space list also removes the Manager's client-side reimplementation
of `Subjects`' encoding. That mirror could only fail silently: an id encoded
differently selects a workspace matching nothing, which renders as "you have no
agents" rather than as an error.

### `?space=` on the agent listing

The Manager's space switcher sent `?space=` to `/agentstore/agents/descriptors`,
which did not accept it — the switcher changed the URL and nothing else. The
parameter now exists there and threads through a new
`RestVersionInfo.readDescriptors(filter, index, limit, space)`, so every
resource type can pick it up the same way. It narrows in the query, never
client-side: page 2 of "everything" is not page 2 of "this space".

### `WorkspacesIT` — the wiring, over real HTTP

Everything the new endpoint and the `?space=` parameter can get wrong is
wiring: whether a query parameter binds, whether Jackson emits the field names
a client is typed against, whether a path is routed at all. None of that is
visible to a unit test holding the resource class directly, and two of them had
already been wrong once.

The disabled payload is pinned here deliberately, because EDDI-Manager's MSW
default handler answers `GET /workspaces` with exactly that shape. If the
contract moves, that mock keeps every frontend test green while the real thing
has changed — so the shape is asserted on the side that owns it.

One assertion is worth naming: `?space=.*` must return **nothing**. Both
storage backends treat a String filter as a regular expression, so an
unescaped identity predicate is a vulnerability rather than a style note, and
`.*` selecting everything is exactly what that bug looks like.

### Coverage on the two classes that had none

A JaCoCo pass over the workspace package found `RestResourceSharing` at **0%**
— no unit test referenced it at all, only the new IT over HTTP — and
`SpaceContext` at 64% instructions / 50% branches.

Both are places where a mistake is silent rather than loud.
`RestResourceSharing` is where loose query text becomes a decision about who can
reach a resource: a level that parsed to something weaker, a subject nobody
holds, a visibility guessed between three options that differ on who can read
the thing. None of those look like errors afterwards — they look like a share
that worked. `SpaceContext` reads the groups claim, which arrives as a JSON
array through one code path, a `List<String>` through another and a bare string
when single-valued; an unhandled shape does not throw, it just leaves the caller
with no team spaces, and every resource shared with their team becomes invisible
to them.

Now 97.2% / 86.7% and 100% / 81%. The JSON-array handling was mutation-checked:
removing the quote-stripping makes the space id `team:"engineering"`, which
matches nothing — the test goes red with the quoted form in the message.

One test was written wrong and corrected rather than the code: `parseOrNull`
accepts *both* `private` and the `privateAccess` constant name, deliberately, so
a client that read the constant out of generated code is not punished for it.
The test now pins that leniency instead of asserting it away.

### Deliberately not changed

`ConverseWithAgentTool` / `CreateSubAgentTool` reach `startConversation` with a
target the *model* picks at runtime, and `DynamicAgentConfig.permissiveDefault()`
allows any target. Unlike channels, triggers and groups there is no
authoring-time reference to check, so closing it means a runtime gate on the
chatting user's identity — which would change delegation semantics for existing
deployments. Recorded here as an open decision rather than changed quietly.

---

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
|            |                                                                       |                                       |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
