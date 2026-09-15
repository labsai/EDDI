# Monorepo Migration Plan — EDDI + EDDI-Manager + EDDI-Chat-UI

> **Status:** Proposed, not started. **Revision 3** — re-verified against all three repos on
> 2026-09-15, five weeks and 391 backend commits after Revision 2 (2026-08-11). See §0 for what
> changed and why the plan still stands.
> **Author context:** This document is self-contained. An implementing agent should need no
> other conversation context. Read it top to bottom before touching anything.
> **⚠ THE PR THAT LANDS THIS MUST BE MERGED WITH A MERGE COMMIT — NEVER SQUASH. See §2.**
> **⚠ Import with `git filter-repo --to-subdirectory-filter` + `git merge --allow-unrelated-histories`,
> NOT `git subtree add` — the subtree approach loses path-scoped history. See §0.6 F1 and §5.1.**

---

## 0. Revision 3 — re-verification on 2026-09-15

**Verdict: the plan is still the right move, and the case is stronger than when it was
written.** Nothing in the five weeks since Revision 2 has removed a reason for the migration;
three things have added to it. The mechanics were re-checked, one dry-run was re-executed, and
the two Phase-0 scans that could be run without installing anything were run for real. The
body of this document has been corrected in place wherever a fact changed; this section is the
summary an implementer should read first. A second, adversarial pass the same day (§0.6) tested
the remaining mechanical claims empirically and changed one §2 decision.

### 0.1 The problem got worse, not better

| Then (2026-08-11) | Now (2026-09-15) | Source |
|---|---|---|
| 562 generated files committed under `META-INF/resources/assets/` | **738** (759 tracked files under `META-INF/resources` in total) | `git ls-files` on `main` @ `b2310a6ed` |
| Asset syncs are hand-rolled submodule pointers | **18** `chore: update Manager UI assets (Manager@…)` commits on `main` in five weeks — roughly one every two days, each a human running a shell script | `git log --since=2026-08-11` |
| Full-stack E2E runs against `labsai/eddi:latest`, so a backend break is caught late and blamed on the wrong repo | **The Manager's backend E2E on `main` has been red on every one of its last 15 runs (2026-08-26 → 2026-09-15).** Both DB legs fail on `resources-crud.fullstack` with *"Rules was created but never appeared in rulestore/rulesets/descriptors"*; the `API Integration` tier is skipped; only the MSW tier is green. Manager PRs stay green because they run MSW only — so the one cross-repo signal that exists is permanently red and, since no PR ever turns red, nobody is on the hook to fix it | `gh run list -R labsai/EDDI-Manager -w e2e.yml -b main` |
| Mock drift is silent by construction | The Manager grew a **second hand-synced pointer**: `src/test/mocks/openapi-operations.json`, a snapshot of EDDI's OpenAPI operations that `openapi-contract.test.ts` checks the MSW handlers against. It is refreshed by hand against a *running* backend (`EDDI_URL=http://localhost:7070 npm run openapi:refresh`). Last refreshed **2026-08-25**; **14** backend commits have touched `IRest*.java` interfaces since. This is a well-built workaround for exactly the gap the monorepo closes — in one repo the snapshot is generated from the same commit in CI and drift becomes a red PR instead of a stale file | `.github/scripts/refresh-openapi-operations.mjs`, `git log -1 -- src/test/mocks/openapi-operations.json` |

The Chat UI, by contrast, has gone **dormant**: zero commits on its default branch since
2026-07-22, seven open PRs (six Renovate, one Mend), and the one in-flight human branch
(`fix/release-6.2-polish`, two commits, pushed 2026-07-27) is still unmerged. That does not
weaken the case for folding it in — it still cannot be built without a sibling backend
checkout (§1) — but it does change the Phase-0 shape: the Chat half is cheap to port and its
single blocker is a dependency CVE, not branch churn (§0.3).

### 0.2 Corrections to facts the plan relied on

Every row below has been folded into the body; this table exists so a reader of Revision 2
can see what moved.

| Was | Is | Plan sections touched |
|---|---|---|
| `EDDI-Chat-UI` default branch assumed `main` | **`master`** — `git fetch chat-origin main` in §5.1 would fail outright | §5.1 |
| V1: three shell-serving resources | **Four** — `RestHtmlChatResource` serves `/chat` from `META-INF/resources/chat.html` by exact name, same pattern | §3 V1 |
| V4/V5: Manager `public/` = ico + svg + `logo_eddi.png` + MSW worker | `logo_eddi.png` is **gone** from Manager `public/` (the sidebar now imports it from `src/assets/`). The backend's root `logo_eddi.png` is referenced by nothing — the shells use `/img/logo_eddi.png`, which Chat ships. Root copy is deleted, not single-sourced | §3 V4/V5, §6.4 |
| V2/V3: multi-page build verified once; "same monolith" | **Re-executed 2026-09-15** on an isolated copy of Manager `main` @ `0870ae87`: still works — `main-<hash>.js/.css` shared by all three shells, `__auth_config__.js` and `.app-loader` preserved, no `dist/index.html`, 46 s. The bundle is **no longer a monolith**: Vite now emits a 4.3 MB `editor-registry-<hash>.js` chunk beside the entry. The §8.5 shipped-shell gate already follows every `/assets/*` reference in the HTML, so split chunks are covered as long as they are statically referenced; dynamically imported chunks are exercised only by the Playwright fullstack tier | §3 V2/V3 |
| Manager `vite.config.ts` has no `build` block | It **has one** (`assetsInlineLimit` keeping woff2 fonts as files because EDDI's CSP is `font-src 'self'`) and already `define`s `__APP_VERSION__` from `pkg.version`. §6.2 merges into both, it does not add them | §6.2 |
| `.github/scripts/` holds one script (`audit-prod`) | **Five**: `audit-prod.{mjs,d.mts}`, `check-i18n.{mjs,d.mts}`, `refresh-openapi-operations.mjs`, `assert-mutants-tested.mjs`; `package.json` references three of them (`audit:prod`, `i18n:check`, `openapi:refresh`) | §5.2, §8.3 |
| Manager CI = `ci-cd.yml` + `e2e.yml` | Plus **`mutation.yml`** (Stryker on PRs + weekly cron + `assert-mutants-tested.mjs`), and `ci-cd.yml` now also runs `npm run i18n:check`. The Manager E2E workflow has **three** tiers (`UI E2E (MSW)`, `API Integration (mongodb)`, `Backend E2E (<db>)`), not two | §8.3, §8.5, §13 |
| `ui/manager/.claude` is "local settings, never should have been tracked" | **Deliberately tracked** now: `launch.json`, `settings.local.json`, and three skills (`eddi-data`, `eddi-screens`, `eddi-ui`). Keep the skills and `launch.json`; only `settings.local.json` is a candidate for untracking | §5.2 |
| Manager dependency updates: unspecified; §9.1 adds Dependabot | Manager (and Chat) run **Renovate** — `renovate.json` with automerge for minor/patch, 23 Renovate PRs to date, reviewer `kennethlynne`. EDDI runs Dependabot + `auto-approve-copilot.yml`. The monorepo must pick one; `renovate.json` is orphaned by the import unless the Renovate app is installed on `labsai/EDDI` | §9.1 |
| "Keep Node 20" | Manager CI is still Node 20, but its own `mise.toml` now pins **`node = "v25.9.0"`**, and both frontends are on Vite 6 (needs ≥ 20.19). The decision stands (CI is the contract), but the Manager pin means local `mise` users already build on a different major — flag in §7.4 | §2, §7.4 |
| V15/V16: `git subtree` available; `gitleaks`, `trivy` not installed | `git filter-repo` **is** installed (needed by the §5.1 recipe that replaces subtree, §0.6 F1); `gitleaks` and `trivy` still are not — Trivy was run through `docker run aquasec/trivy` instead | §3 V15/V16, §4.2 |
| §7.1 pins Node `v20.19.0`, `maven-resources-plugin` 3.3.1 | Latest Node 20 is **v20.20.2** (2026-09-15); `maven-resources-plugin` **3.3.1 is already the managed version** in the effective POM, so no property or `<version>` is needed for it. `frontend-maven-plugin` 1.15.1 is still current | §7.1, §7.2 |
| V11: protection = `["CodeQL Analysis", "Build & Test"]` | **Unchanged.** Additionally `enforce_admins: true`. Two rulesets exist: one disabled, one (`Frozen release branches`) active on `refs/heads/release/*` only — no effect on `main` or this PR | §3 V11 |
| V13: GitHub-managed CodeQL runs `Analyze (java-kotlin)` | Now also **`Analyze (python)`** (picked up `scripts/rotate-changelog.py`). Same conclusion: the settings-side surface auto-detects, and TypeScript must be enabled there too | §3 V13, §9.2 |
| §8.6 re-exports `primary-tag` and `is-release` | `ci.yml` grew a **`redhat-publish`** job (reusable `redhat-certify.yml`) that reads `needs.docker.outputs.is-stable` and `.primary-tag` — **`is-stable` must be re-exported too**, and the job goes in the downstream-consumer table | §8.6 |
| §8.8 "add a workflow-level concurrency group" | **Already present** (`ci-${{ github.workflow }}-${{ github.ref }}`, cancel on PRs only). Nothing to add | §8.8 |
| `ci.yml` job list as of Revision 2 | Now also `shell-lint`, `manifest-lint` (`Deployment Manifests`), `redhat-publish`; ~1,700 lines. The `docker` job additionally cosign-signs and writes SLSA provenance — those steps stay in the publish job because they need the pushed digest; only the *build* moves to `build-image` (with `-Plicense-gen`, which the current command carries) | §8.4, §8.6 |
| Pack growth "~90 MiB", "~1,079 commits" | Manager pack is **101.9 MiB**, 1,213 commits; Chat 1.95 MiB, 62 commits → **1,275** commits enter the gitleaks range | §4.3, §12 |
| Manager: 30+ live branches | 28 remote branches, **5 open PRs** (4 Renovate + `#95 scheduled-ingest-service` from an external contributor, open since before Revision 2). Local checkout is clean and on `main` (the in-flight `feat/operator-write-scope` has landed) | §4.1 |

### 0.3 Phase-0 scans run today (results, not predictions)

| Gate | Manager | Chat | Consequence |
|---|---|---|---|
| `npm audit --omit=dev --audit-level=high` (§4.4) | **0 vulnerabilities** | **2 high** (`react-router-dom` → `react-router`) | `dependency-review` (`fail-on-severity: high`) **blocks the migration PR** as-is |
| Trivy `fs` over both lockfiles, `--severity CRITICAL,HIGH --ignore-unfixed` (§4.2), run via `docker run aquasec/trivy` | **clean** | **6 HIGH** in `react-router 7.13.1` (CVE-2026-33245, -34077, -42211, -42342, -55685, GHSA-qwww-vcr4-c8h2; all fixed by **7.18.2**) | `trivy-scan` is `exit-code: 1` and gates `docker`: importing the Chat lockfile unfixed **stops every backend release** from the first push |
| Gitleaks history scan (§4.3) | not run — `gitleaks` still not installed locally (V16) | same | Still a mandatory pre-step; 1,275 commits |

| Boot `labsai/eddi:latest` under the Manager's `docker-compose.integration.yml` (what `Backend E2E` does) | — | — | **The container exits(1) at startup.** EDDI's `HighValueSurfaceGuard` (added 2026-08-21, `bf6f922634`) refuses `authorization.enabled=false` unless `EDDI_MCP_ALLOW_UNAUTHENTICATED=true` **and** `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true` are also set; the compose files only set `EDDI_SECURITY_ALLOW_UNAUTHENTICATED`, which the guard's own Javadoc says does not cover it. **This is the entire cause of the 15-run red streak in §0.1.** With the two variables added (isolated re-run on port 17070, image `sha256:7e9d0ba4…`, built 2026-09-15), the backend boots, `POST /rulestore/rulesets` returns 201 and the new id is listed by `/rulestore/rulesets/descriptors` — i.e. the failing assertion passes. Fix: §4.7 |

The one concrete blocker for the *import* is therefore a **single Chat bump**: `react-router-dom` / `react-router`
to ≥ 7.18.2 in `labsai/EDDI-Chat-UI` (a Renovate PR for the router already sits open there),
merged **before** the import — or, as the last resort §4.2/§4.4 allow, in the import
commit itself. Note the Chat audit above was run against the **committed** lockfile; the local
checkout's lockfile is dirty (+227/−236) and must not be what gets imported.

### 0.4 Was a smaller move considered?

Yes, and rejected again. The alternatives to a monorepo are (a) have `e2e.yml` build the backend
image from `labsai/EDDI` `main` instead of pulling `:latest`, and (b) publish a
`labsai/eddi:main-<sha>` tag per push and pin it. Both fix *staleness* but not *attribution*:
a backend PR still cannot run the Manager's tests before merging, so the red-on-main-only
pattern of §0.1 persists — the failure just arrives sooner. Neither touches the 738 committed
files, the manual OpenAPI snapshot, or the Chat UI's inability to build. The Chat's dormancy
argues for the opposite of a scope cut: it is the *cheap* half, and leaving it out would keep
`../EDDI` hard-wired into a repo nobody is maintaining.

### 0.5 What this revision does not re-verify

V7–V10 (Chat build output shape, tracked `dist/`, `emptyOutDir`, byte drift) were re-read, not
re-executed; the Chat repo has not changed since they were taken. V12's `deny-licenses`
deprecation note still holds (the workflow now documents it inline). The §10 skipped-check
question is **now answered** (observed on #670 after this revision was pushed, 2026-09-15):
with `Build & Test` reported as `SKIPPED` and `CodeQL Analysis` green, `mergeStateStatus`
went from `BLOCKED` (while CodeQL was still running) to `UNSTABLE` — GitHub's "required
checks satisfied, some non-required checks still pending" state — never `BLOCKED` on the
skipped check. So a job-level `if:` skip satisfies a required context, and the path-gated
jobs in §8 can be made required directly; the aggregator in §8.6b remains the right tool
for the `docker` publish gate (that is a `needs` problem, not a protection problem).

### 0.6 Second pass — findings that changed the plan (2026-09-15, later the same day)

Each of these was tested, not reasoned about. F1 changes a §2 decision.

| # | Finding | Evidence | Where applied |
|---|---|---|---|
| **F1** | **`git subtree add` does not preserve path-scoped history.** After a subtree add, `git log -- ui/manager` and `git log -- ui/manager/<file>` show **only the import merge commit**; `git log --follow -- ui/manager/<file>` shows **nothing at all**. Only `git blame` (via rename detection) and the un-scoped `git log` see the original commits. GitHub's per-file History view has the same blind spot. Rewriting the imported history into the subdirectory first (`git filter-repo --to-subdirectory-filter`) and merging with `--allow-unrelated-histories` gives full path-scoped `git log`, `--follow`, and `git blame`, at the cost of new commit SHAs — which is acceptable because the archived source repos keep the originals and no SHA of theirs is referenced from this repo | Two throwaway repos, both recipes, same day | §2 (decision changed), §4.3, §5.1, §11, §12 |
| **F2** | **The red Manager E2E is two missing env vars, not a contract break.** See §0.3, last row. Trivial to fix, and exactly the shape the monorepo removes: the guard and the compose file that must know about it will live 3 directories apart instead of in different repos | Container log: `HighValueSurfaceGuard.onStart` IllegalStateException; green re-run with the two variables | §4.7 (new), §8.5 |
| **F3** | `npm run build` in the Manager (which is what §7.2 runs, i.e. `tsc -b && vite build`) takes **112 s** locally; `vite build` alone 46–55 s. Budget ~2 min per un-skipped `mvnw` invocation, not "1–3 min" hand-waved | Measured on the isolated copy | §7.4 |
| **F4** | Stale generated files in a pre-migration checkout are excluded from the *Maven* resource copy by §7.2, but nothing removes them from disk, and Quarkus dev mode watches `src/main/resources` directly. Make removal deterministic: `maven-clean-plugin` filesets delete the gitignored generated paths on `mvnw clean`, and §7.3 gains a dev-mode check | Reasoned from the Quarkus dev-mode resource watcher; the check in §7.3 is what proves or disproves it | §7.2, §7.3 |
| **F5** | Only `IRestManagerResource` serves `/manage/__auth_config__.js`; all three shells reference **that** path (not per-shell paths). Confirms §6.1's "preserve verbatim" — nothing to add, recorded so nobody "fixes" it | grep of the shells and `ui/` resources | §6.1 |
| **F6** | The root `.editorconfig` declares `[*] indent_size = 4`. The Manager brings its own `root = true` file (2 spaces), so it is unaffected; the Chat has **none**, so editors would apply 4-space indentation to `ui/chat/**` | Read both files | §6.5 |
| **F7** | `CODEOWNERS` is a single `* @ginccc @rolandpickl` line — it already covers `ui/**`; §9.3 needs no change | Read | §9.3 |
| **F8** | The migration PR will carry ~1,275 commits and >1,000 changed files. CodeRabbit stops reviewing above ~100 files and Copilot above ~300, so the PR gets **no automated review** and `auto-approve-copilot.yml` cannot satisfy the required review. Plan for a human review of the *mechanics* (the imported trees are not reviewable and do not need to be — they are the archived repos' `main`/`master` byte-for-byte) | Known bot limits; PR size from `git ls-files` counts | §4.6 |
| **F9** | The `ui` path filter should also list `.github/workflows/ci.yml`, or a CI-only change never exercises `ui-build-and-test` | Read of `detect-changes` | §8.1 |
| **F10** | Neither `EDDI-Manager` nor `EDDI-Chat-UI` has any tags today, so nothing can collide with EDDI's `tags: ["[0-9]*"]` release trigger — but fetch with `--no-tags` anyway, and assert the tag count is unchanged, because an imported numeric tag would be a release trigger waiting to happen | `git tag | wc -l` in both; `ci.yml` line 6 | §5.1 |
| **F11** | The Chat's `react-router` CVEs will also drag OpenSSF Scorecard's *Vulnerabilities* check down the moment the lockfile lands (Scorecard reads OSV over lockfiles) — one more reason the bump precedes the import | Same data as §0.3 | §4.4 |

---

## 1. Why

The three repos are already one build unit — but held together by hand.

**`EDDI-Chat-UI` cannot be built standalone at all.** Its `vite.config.ts` sets:

```ts
outDir: resolve(__dirname, "../EDDI/src/main/resources/META-INF/resources"),
emptyOutDir: false, // Keep existing files (index.html, manage.html, dashboard, etc.)
```

The build writes straight into a sibling checkout of the backend, with `emptyOutDir` disabled
so it doesn't delete the backend's other static files.

**`EDDI-Manager` is worse in a subtler way.** It builds to its own `dist/`, and then
`deploy-to-local-eddi-repo.sh` copies the assets into the backend and **`sed`-patches three
HTML shells that live only in the backend repo**: `manage.html`, `welcome.html`,
`workforce.html`. Those three files are the real production entry points — served by
`RestManagerResource`, `RestWelcomeResource`, and `RestWorkforceResource`, which read them
from the classpath **by exact name**. They are *not* in the Manager repo, are not generated
by Vite, and have no source-of-truth relationship to the Manager source. The Manager's own
`index.html` is dev-server-only.

Both then commit generated output into the backend, with commit messages that are hand-rolled
submodule pointers:

```text
14c7046bc chore: update Manager UI assets (Manager@44a0e684)
2925707d7 chore: update Chat UI assets (chat-ui@71fa395)
```

**738 files** (562 when this plan was first written) sit in
`src/main/resources/META-INF/resources/assets/` as committed build output. Nothing verifies they match the source that produced them — and they demonstrably
don't stay in sync: a fresh build of `chat-ui.DHWeOytM.js` from the chat repo differs
byte-wise from the committed copy of the **same filename** (line-ending normalization at
commit time; see §3 evidence). That is the silent-drift failure mode in the wild.

Neither frontend has an independent version, release, deployment, or consumer.
`eddi-manager` is `"private": true`, version `6.4.0` — identical to `pom.xml`. Both ship
inside the backend jar, in the backend's Docker image, under the backend's git tag. The
standalone `Dockerfile` / `build-service.sh` in the Manager repo are confirmed relics.

| Cost today | Fixed by the monorepo |
|---|---|
| Full-stack E2E runs against `labsai/eddi:latest` pulled from Docker Hub — never the code under test | E2E runs against the image built from the PR's own commit |
| Manager PRs are validated only against MSW mocks; mock drift is silent by construction | Same PR builds the real backend; the API-integration tier runs for free |
| The Manager's backend E2E on `main` has been red on 15 consecutive runs (§0.1) with no PR ever going red for it | The break lands in the PR that causes it, on whichever side |
| The Manager pins a hand-refreshed snapshot of EDDI's OpenAPI operations (`openapi-operations.json`, 3 weeks stale) | Generated from the same commit in CI; drift is a diff in the PR |
| A backend PR that breaks a Manager contract is caught post-publish and blamed on the wrong repo | Caught in the PR that caused it |
| Generated assets committed by a human running a shell script; provably drifted already | Built in CI, correct by construction |
| Three HTML production shells orphaned from their source | They become Vite multi-page inputs (dry-run verified, §3) |

---

## 2. Decisions already made (do not re-litigate)

| Decision | Rationale |
|---|---|
| **Merge both frontends**, not just the Manager | Chat-UI literally cannot build without the backend checked out |
| **Maven stays at the repo root**; Java is not moved to `backend/` | Avoids touching the Dockerfile, all CI path references, and ~40 docs files |
| **Import by `git filter-repo --to-subdirectory-filter` + `git merge --allow-unrelated-histories`** — *changed in Revision 3 from `git subtree add`* | Revision 2 chose `subtree` "to preserve history and blame". Tested (§0.6 F1): subtree preserves `git blame` and the un-scoped log, but **`git log -- ui/manager/...` sees only the import commit** and `--follow` sees nothing. Rewriting the history into the subdirectory first preserves everything path-scoped. Cost: the imported commits get new SHAs. Acceptable: the archived repos keep the originals, nothing in this repo references a Manager/Chat SHA, and the §4.3 gitleaks fingerprints are computed on the *rewritten* clones. `git filter-repo` is installed locally (V15) |
| **THE MONOREPO PR MERGES WITH A MERGE COMMIT** | A squash-merge flattens both imported histories into one commit, destroying the attribution the import exists to preserve. `AGENTS.md` §2 recommends "Squash and merge" as the normal cleanup path — **that guidance is explicitly overridden for this one PR.** Verified: `labsai/EDDI` allows merge commits and `required_linear_history` is off. Tell the person merging. |
| **No npm workspaces** | Hoisting can break Vite/Tailwind resolution; a migration should not change two things at once. Revisit later. |
| **Stop committing build output** | The entire point. Requires Maven to build the UI (§7). |
| **Single-source every static file** (§6.4) | Both frontends' `public/` dirs duplicate files committed in the backend; after migration each file has exactly one source |
| **Keep Node 20** (matches current Manager CI) | Bumping to 22 is a separate, independently reversible change |
| **Keep `ui/manager/AGENTS.md` in place** — do NOT fold into root AGENTS.md | AI assistants auto-load nested instruction files per directory; the root file is already very large. Add a `ui/manager/CLAUDE.md` stub instead. |
| **Archive the old repos, never delete** | They are public; forks and stars exist |

---

## 3. Verified facts and dry-run evidence (2026-08-11, re-checked 2026-09-15)

An implementing agent can rely on these without re-deriving them. If reality disagrees with
this table, stop and investigate before proceeding.

| # | Fact | How verified |
|---|---|---|
| V1 | `/manage`, `/welcome/**`, `/workforce/**`, `/chat/**` are served by `RestManagerResource.java`, `RestWelcomeResource.java`, `RestWorkforceResource.java`, `RestHtmlChatResource.java` reading `META-INF/resources/{manage,welcome,workforce,chat}.html` from the classpath by exact name | grep of `src/main/java/ai/labs/eddi/ui/` (re-checked 2026-09-15) |
| V2 | **The multi-page Vite build works.** With `manage.html`/`welcome.html`/`workforce.html` as rollup inputs (each pointing at `/src/main.tsx`), `vite build` emitted all three shells referencing one shared hashed entry (`/assets/main-<hash>.js` + `/assets/main-<hash>.css`), with `__auth_config__.js` and the `.app-loader` block preserved, and **no** `dist/index.html` | Executed 2026-08-11 in the Manager checkout (29 s); **re-executed 2026-09-15** on an isolated copy of Manager `main` @ `0870ae87` (46 s, same result) |
| V3 | The entry chunk renames from `index-<hash>.js` to `main-<hash>.js` under multi-page inputs. Nothing references the old pattern (the deploy scripts that did are deleted by this plan). As of 2026-09-15 the bundle is **code-split** (a 4.3 MB `editor-registry-<hash>.js` chunk sits beside the entry) — the §8.5 gate follows every statically referenced `/assets/*` URL, so this needs no extra handling | Dry-run output, both dates |
| V4 | **Vite copies `public/` into dist root.** Manager `public/` = `eddi-icon.ico`, `eddi-icon.svg`, **`mockServiceWorker.js`** (`logo_eddi.png` was removed from `public/` after Revision 2; the sidebar imports it from `src/assets/`). The MSW worker must be excluded from the shipped jar (§7.2) | Dry-run dist listing (2026-09-15) |
| V5 | Manager `public/` icons are **byte-identical** to the backend's committed copies. The backend's root `logo_eddi.png` is referenced by nothing (all four shells use `/img/logo_eddi.png`, which Chat ships) and is deleted in §6.4 | `cmp` both icons; `grep logo_eddi` over shells + Manager `src/` |
| V6 | Chat `public/` = `fonts/` (6 files) + `img/` (2 files), matching the backend's committed copies except `img/loading-indicator.svg`, which exists only in the backend and is **referenced nowhere** in any of the three repos | `diff -rq` + grep |
| V7 | The chat build with `outDir: "dist"` emits exactly `chat.html` + `scripts/js/chat-ui.<hash>.js` + `scripts/css/chat-ui.<hash>.css` + the `public/` copy. (A `dist/index.html` seen mid-test was pre-existing tracked content, not Vite output) | Executed locally in the chat checkout, then reverted |
| V8 | **`eddi-chat-ui` tracks files under `dist/`** (`dist/index.html`, `dist/assets/index-*.{js,css}`, `dist/fonts/**`, `dist/img/**`) despite `/dist` in its `.gitignore` — tracked files override ignore rules. These must be `git rm`'d (§5.2) | `git status` after test deletion showed `D dist/...` |
| V9 | Chat sets `emptyOutDir: false` (to protect the old backend outDir). Must flip to `true` when retargeting to `dist/` (§6.3) | Read of `vite.config.ts:15` |
| V10 | A fresh `chat-ui.DHWeOytM.js` differs byte-wise from the committed file of the same name — committed assets have already drifted (almost certainly LF/CRLF normalization at commit time) | `cmp` |
| V11 | Branch protection on `labsai/EDDI` `main`: required checks are exactly `["CodeQL Analysis", "Build & Test"]` (job **names**), 1 review required, `required_linear_history: false`, `enforce_admins: true`, all three merge methods allowed. Rulesets: one disabled; `Frozen release branches` is active but scoped to `refs/heads/release/*` (deletion + non-fast-forward only) and does not touch `main` | `gh api repos/labsai/EDDI/branches/main/protection` + `/rulesets` (re-checked 2026-09-15, unchanged) |
| V12 | `dependency-review.yml` runs on every PR with `fail-on-severity: high` and `deny-licenses: GPL-3.0, AGPL-3.0` — the monorepo PR introduces the full npm dependency graphs to this gate at once. **Caveat:** the action now emits a deprecation warning that `deny-licenses` "is deprecated for possible removal in the next major release" (upstream issue 997), so do not build the license strategy on it long-term | Read of the workflow + the action's own PR comment on #670 |
| V13 | There are **three** CodeQL surfaces, not two: the `codeql` job in `ci.yml`, the scheduled deep scan `codeql.yml` (both with their own `java-kotlin` language list), **and a GitHub-managed dynamic run** (`path: dynamic/github-code-scanning/codeql`, check names `Analyze (java-kotlin)` and — since a Python script landed under `scripts/` — `Analyze (python)`, surfaced as "Code Quality: PR #N"). The third is configured in **repo settings, not in any file in this repo**, so §9.2's TypeScript enablement is two file edits *plus* a settings change | Read of both workflows + check-runs on `main` @ `b2310a6ed` (2026-09-15) |
| V13b | As of `00420daa5` the ci.yml `codeql` job is **deliberately ungated** — no `needs: detect-changes`, no `if:` — so it builds with Maven on *every* PR including docs-only ones (done so OpenSSF Scorecard sees a SAST run on each PR head SHA). This makes its `-DskipUi=true` (§8.2) load-bearing: without it, every docs-only PR would run the full npm build | Read of ci.yml on current main |
| V14 | `sbom` job invokes `cyclonedx:makeBom` as a direct plugin goal — it runs **no lifecycle phases** and therefore will not trigger the frontend build; it needs no `skipUi` flag | Maven invocation semantics + workflow read |
| V15 | `git filter-repo` **is** installed locally (`git filter-repo --version` → `a40bce548d2c`); `git subtree` is too but is no longer used (§2) | `git filter-repo --version` (2026-09-15) |
| V16 | `gitleaks` and `trivy` are **not** installed locally; the Phase-0 history pre-scan (§4.3) needs gitleaks installed first. Trivy can be run as `docker run --rm -v "<dir>:/scan" aquasec/trivy:latest fs …` (done in §0.3) | `command -v gitleaks trivy` (2026-09-15) |
| V17 | Maven lifecycle: `generate-resources` precedes `compile`, so **every** `mvnw compile/test/verify` triggers the frontend build unless `-DskipUi=true` (§7.4) | Lifecycle definition |
| V18 | `.gitattributes` has three `linguist-generated` entries pointing at the committed bundles, plus a comment claiming chat-ui is "a separate repo" — all stale after this migration (§6.5) | Read (still true 2026-09-15) |
| V19 | `pom.xml` (6.4.0) declares **no** `<resources>` block and no frontend plugin — §7.1/§7.2 add both; nothing to merge with | Read of `pom.xml` on `main` (2026-09-15) |
| V20 | `labsai/EDDI-Chat-UI`'s default branch is **`master`**, not `main` | `gh repo view` (2026-09-15) |
| V21 | After `git subtree add`, `git log -- <prefix>` shows only the import commit and `git log --follow -- <prefix>/<file>` shows nothing; `git blame` still attributes lines to the original commits. After `git filter-repo --to-subdirectory-filter <prefix>` + `git merge --allow-unrelated-histories`, all three show the full original history | Throwaway repos, both recipes (2026-09-15) |
| V22 | `labsai/eddi:latest` (`sha256:7e9d0ba4…`, 2026-09-15) exits(1) under the Manager's `docker-compose.integration.yml`: `HighValueSurfaceGuard` requires `EDDI_MCP_ALLOW_UNAUTHENTICATED=true` and `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true` in addition to `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`. With both added the backend boots and a created ruleset is listed by `/rulestore/rulesets/descriptors` | Isolated compose run on port 17070 (2026-09-15) |
| V23 | Manager `npm run build` (`tsc -b && vite build`) = **112 s**; `vite build` alone 46–55 s; output `dist/` = `manage.html`, `welcome.html`, `workforce.html`, `assets/`, `eddi-icon.ico`, `eddi-icon.svg`, `mockServiceWorker.js` | Isolated copy of Manager `main` @ `0870ae87` (2026-09-15) |
| V24 | Neither `EDDI-Manager` nor `EDDI-Chat-UI` has any git tags | `git tag` in both (2026-09-15) |
| V25 | Only `IRestManagerResource` declares `@Path("/manage/__auth_config__.js")`; `manage.html`, `welcome.html` and `workforce.html` all load exactly `/manage/__auth_config__.js` | grep (2026-09-15) |

---

## 4. Phase 0 — Preconditions (all mandatory, none skippable)

> **"Blocking" here means blocking a *release*, not blocking a *merge* — today those differ.**
> Only `CodeQL Analysis` and `Build & Test` are required contexts on `main` (V11).
> `trivy-scan`, `gitleaks` and `dependency-review` are **not**, and `trivy-scan` reaches a
> release only indirectly, by gating the `docker` job — which is push-only. So on a pull
> request a failed Trivy or Gitleaks run is *advisory*: the PR can be merged, and the failure
> surfaces afterwards on the push run, where it stops the publish but not the bad commit.
> That is exactly the discover-it-after-merge shape this migration is trying to remove
> elsewhere. §10 therefore requires making these contexts merge-blocking; until that is done,
> read every "release-blocking" claim below as "will stop the publish, not the merge."

### 4.1 Branch and worktree freeze

`EDDI-Manager` has 28 remote branches and 5 open PRs (as of 2026-09-15: four Renovate PRs and
`#95 scheduled-ingest-service` from an external contributor). The Manager checkout is clean
and on `main`, but **the Chat checkout still sits on the unmerged `fix/release-6.2-polish`**
(two commits, pushed 2026-07-27, never merged — the committed backend bundle was built from
`master` @ `71fa395`, so those two fixes have never shipped) with a dirty `package-lock.json`.
The migration orphans all of it.

1. Merge or close everything mergeable. Concretely, as of 2026-09-15:
   - **Chat:** merge `fix/release-6.2-polish` (2 commits: "offer real agents instead of a
     made-up id; load the agent name", "keep the URL's environment when picking an agent")
     into `master` — they were never shipped. Then the `react-router` bump (§4.2).
   - **Manager:** the four Renovate PRs (#72, #140, #168, #207) can be closed — Renovate is
     replaced by §9.1. **`#95 scheduled-ingest-service`** (external contributor) needs a
     decision: merge, or close with a link to this plan and the port recipe below. Do not
     let it be silently orphaned.
   - **Manager:** land the §4.7 compose fix.
2. Announce a freeze on both repos.
3. For each branch that must survive, record it and port after cutover with:

```bash
git -C ../EDDI-Manager format-patch origin/main..<branch> --stdout > /tmp/<branch>.patch
git checkout -b <branch> origin/main            # in the EDDI repo
git am --directory=ui/manager /tmp/<branch>.patch
```

Verify with `git log --stat -1` that files landed under `ui/manager/`.

### 4.2 Trivy pre-scan (release-blocking gate)

`ci.yml`'s `trivy-scan` job runs `scan-type: fs`, `scan-ref: .`, `exit-code: 1` on
CRITICAL/HIGH. Committing `ui/*/package-lock.json` makes npm production dependencies a
release-blocking gate on the backend (Trivy excludes npm dev-deps by default). On a scratch
checkout with both lockfiles copied in:

```bash
trivy fs --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 .
```

**Run on 2026-09-15 (via `docker run aquasec/trivy`, since `trivy` is not installed):** the
Manager lockfile is clean; the Chat lockfile has **six HIGH** findings, all in `react-router
7.13.1`, all fixed by **7.18.2** (§0.3). That bump is the one preparatory PR this gate needs.

If it fails, fix or `.trivyignore` (with justification) **as a preparatory PR in the source
repo** before the migration. Note `.trivyignore` is read from the PR checkout, so
last-resort entries *can* ride in the migration PR itself — but prefer fixing first.

### 4.3 Gitleaks history pre-scan — **sequencing trap, read carefully**

The `gitleaks` CI job scans `PR_BASE..PR_HEAD` — and for the migration PR that range contains
**the entire imported history of both repos** (1,213 Manager + 62 Chat = **1,275** commits as
of 2026-09-15). Any historical test fixture,
MSW mock token, or storage-state file that pattern-matches a secret fails the PR.

The trap: the CI job deliberately takes `.gitleaksignore` **from the base branch** on PRs
(so a PR cannot allowlist its own secrets). Therefore any needed ignore entries **must land
on `main` in a separate PR *before* the migration PR is opened.**

Scan the **rewritten** clones from §5.1 step 1 — not the source checkouts — because
`git filter-repo` changes every SHA and gitleaks fingerprints are `<sha>:<path>:<rule>:<line>`:

```bash
gitleaks git --no-banner --redact=100 /tmp/manager-rewrite   # full rewritten history
gitleaks git --no-banner --redact=100 /tmp/chat-rewrite
```

(Install gitleaks first — it is not on the machine; V16.) For each finding: real secret →
rotate + handle before import; false positive → add a fingerprint line to the backend's
`.gitleaksignore` and merge that to `main` first. The fingerprints are valid in the monorepo
because the rewritten commits are byte-for-byte what §5.1 merges — **so §5.1 step 1 (the
rewrite) must be done once, kept, and reused for the import; do not re-run `filter-repo`
after scanning**, a second rewrite from a moved-on `main` would produce different SHAs.

### 4.4 Dependency-review pre-check

Per V12, the migration PR presents every npm dependency (direct + transitive) to
`dependency-review-action` at `fail-on-severity: high` / `deny-licenses: GPL-3.0, AGPL-3.0`.
Pre-check both repos:

```bash
npm audit --omit=dev --audit-level=high        # in each of EDDI-Manager and eddi-chat-ui
npx license-checker-rseidelsohn --production --excludePrivatePackages \
  --failOn 'GPL-3.0;AGPL-3.0'                  # or equivalent license sweep
```

**Run on 2026-09-15:** Manager 0 vulnerabilities; Chat **2 high** (the same `react-router`
chain as §4.2). License sweep not run.

These same CVEs also lower OpenSSF Scorecard's *Vulnerabilities* score (OSV over lockfiles)
the moment the Chat lockfile lands (§0.6 F11).

Resolve findings in the source repos first. Unlike gitleaks, this action's config lives in
the workflow file and is read from the PR's merge ref, so a config adjustment *can* ride in
the migration PR if a finding is genuinely unactionable — but treat that as last resort.

### 4.5 Baseline capture

```bash
git rev-parse HEAD                                        # backend baseline
git -C ../EDDI-Manager rev-parse origin/main              # manager baseline
git -C ../eddi-chat-ui rev-parse origin/main              # chat baseline
git ls-files src/main/resources/META-INF/resources | wc -l   # 759 on 2026-09-15 (583 in Aug); record the actual number
```

Record the current hashed asset names from `manage.html` (e.g. `index-CeAE4N_O.js`) — after
migration the entry renames to `main-<hash>.js` (V3) and you want proof of the swap.

### 4.6 Confirm merge settings with whoever merges

Repo allows all three merge methods (V11). The migration PR must use **"Create a merge
commit."** Put this in the PR description in bold, first line.

Also tell the reviewer what to review (§0.6 F8): the PR will show ~1,275 commits and >1,000
files, CodeRabbit and Copilot will both decline it on size, and `auto-approve-copilot.yml`
therefore cannot supply the required approval. The reviewable surface is small and should be
listed in the PR description: the §5.2 deletions, the §5.3/§6.x config edits, `pom.xml`,
`.gitignore`/`.gitattributes`, and `ci.yml`. The imported trees are the archived repos'
`main`/`master` and need no line-by-line review.

### 4.7 Fix the Manager's compose files (restore the full-stack baseline)

The `Backend E2E` tiers have been red on Manager `main` since 2026-08-26 because
`labsai/eddi:latest` no longer boots under `docker-compose.integration.yml` /
`docker-compose.integration-postgres.yml` (V22). **Fix this in a preparatory Manager PR
before the import**, otherwise `e2e-fullstack` (§8.5) is red from its first run and §11 cannot
be met. In **both** compose files, under `services.eddi.environment`, add:

```yaml
      # HighValueSurfaceGuard (EDDI ≥ 2026-08-21) refuses authorization.enabled=false
      # unless these two are set explicitly; EDDI_SECURITY_ALLOW_UNAUTHENTICATED does
      # NOT cover them. Throwaway test backend on loopback — see the comment above.
      EDDI_MCP_ALLOW_UNAUTHENTICATED: "true"
      EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED: "true"
```

Verify with `npm run infra:up:mongo` → `curl -sf localhost:7070/q/health/live` → `npm run
test:e2e:fullstack` in the Manager. On 2026-09-15 this exact change made the previously
failing `resources-crud.fullstack` assertion pass against `:latest`. If a *different* failure
appears once the backend is up, that is a genuine contract break and belongs in the same
preparatory PR (or a backend fix), not in the migration PR.

---

## 5. Phase 1 — Bring the code in

Work on a branch off `origin/main`. **Each phase ends in a state that compiles and is
committed separately.**

> **⚠ The imported Chat config points outside the repo — fix it in this phase, not later.**
> `ui/chat/vite.config.ts` arrives from the import still carrying
> `outDir: resolve(__dirname, "../EDDI/src/main/resources/META-INF/resources")`. Evaluated
> from `ui/chat/`, `../EDDI` now resolves to **`<repo>/ui/EDDI`** — a directory that does not
> exist. A `npm run build` there would silently create a stray `ui/EDDI/` tree and produce no
> usable artifact, and the Phase-1 "it compiles" checkpoint would be a lie. §5.3 retargets it
> immediately, before any frontend build is attempted.

```bash
git fetch origin main
git checkout -b chore/monorepo-migration origin/main
```

### 5.1 Import the two histories (filter-repo + unrelated-history merge)

**Not `git subtree add`** — see §2 and V21. Two steps: rewrite each source history into its
target subdirectory on a fresh clone, then merge the rewritten branch into the monorepo.

**Step 1 — rewrite (do this once; §4.3 scans these clones and they must not be regenerated):**

```bash
git clone --no-tags https://github.com/labsai/EDDI-Manager.git /tmp/manager-rewrite
git -C /tmp/manager-rewrite filter-repo --to-subdirectory-filter ui/manager

git clone --no-tags https://github.com/labsai/EDDI-Chat-UI.git /tmp/chat-rewrite
git -C /tmp/chat-rewrite filter-repo --to-subdirectory-filter ui/chat
```

`filter-repo` insists on a fresh clone (it refuses to run on a repo with other remotes or
local state) — that is why these are new clones and not the sibling checkouts, which also sit
on the wrong branches (§4.1). After the rewrite, `git -C /tmp/manager-rewrite ls-files | head`
must show every path under `ui/manager/`, and `git log --oneline | wc -l` must equal the
source repo's commit count (1,213 / 62 as of 2026-09-15).

**Step 2 — merge into the migration branch:**

```bash
git remote add manager-rewrite /tmp/manager-rewrite
git remote add chat-rewrite    /tmp/chat-rewrite
git fetch --no-tags manager-rewrite main
git fetch --no-tags chat-rewrite    master      # Chat's default branch is `master` (V20)

git merge --allow-unrelated-histories --no-edit \
  -m "chore(monorepo): import EDDI-Manager history under ui/manager" manager-rewrite/main
git merge --allow-unrelated-histories --no-edit \
  -m "chore(monorepo): import EDDI-Chat-UI history under ui/chat" chat-rewrite/master

git remote remove manager-rewrite
git remote remove chat-rewrite
```

**Assert afterwards:**
- `git tag | wc -l` is unchanged (V24 says neither repo has tags, `--no-tags` guarantees it;
  an imported numeric tag would match EDDI's `tags: ["[0-9]*"]` release trigger)
- `git log --oneline -- ui/manager/package.json | wc -l` is **> 1** (path-scoped history
  survived — this is the assertion that fails under `subtree add`)
- `git blame ui/manager/package.json | head -3` shows Manager commits, not the merge

Expect pack growth of roughly the Manager's 102 MiB plus the Chat's 2 MiB. Accepted (§2).

### 5.2 Delete what does not survive the move

First, move the **five** helper scripts that `package.json` and the workflows reference
**before** deleting `.github` (there was one when this plan was written; §0.2):

```bash
mkdir -p ui/manager/scripts
git mv ui/manager/.github/scripts/audit-prod.mjs                 ui/manager/scripts/
git mv ui/manager/.github/scripts/audit-prod.d.mts               ui/manager/scripts/
git mv ui/manager/.github/scripts/check-i18n.mjs                 ui/manager/scripts/
git mv ui/manager/.github/scripts/check-i18n.d.mts               ui/manager/scripts/
git mv ui/manager/.github/scripts/refresh-openapi-operations.mjs ui/manager/scripts/
git mv ui/manager/.github/scripts/assert-mutants-tested.mjs      ui/manager/scripts/
```

Then delete (use `git rm --ignore-unmatch` for the deploy scripts — some copies are
untracked local helpers and may not exist in the imported tree):

```bash
git rm -r ui/manager/.github
git rm --ignore-unmatch ui/manager/Dockerfile ui/manager/build-service.sh
git rm --ignore-unmatch ui/manager/deploy-to-local-eddi-repo.sh ui/manager/deploy-to-local-eddi-repo.ps1
git rm -r ui/manager/.husky
git rm --ignore-unmatch ui/chat/deploy-to-local-eddi-repo.ps1 ui/chat/deploy-to-local-eddi-repo.sh
git rm -r --ignore-unmatch ui/chat/dist          # tracked build output (V8)
git rm --cached --ignore-unmatch ui/manager/.claude/settings.local.json   # machine-local; the skills and launch.json are deliberately tracked (§0.2)
```

Also delete `ui/manager/renovate.json` once §9.1's decision is Dependabot (it is orphaned
either way: the Renovate app is installed on the old repos, not on `labsai/EDDI`).

In `ui/manager/package.json`:
- `"audit:prod"` → `node scripts/audit-prod.mjs`
- `"i18n:check"` → `node scripts/check-i18n.mjs`
- `"openapi:refresh"` → `node scripts/refresh-openapi-operations.mjs`
- remove `"prepare": "husky"`
- remove the now-dead `"lint-staged"` config block

`ui/manager/.github/workflows/mutation.yml` (Stryker) is deleted with the rest of `.github`;
its replacement is a §13 follow-up, not part of the migration PR.

> **Accepted loss:** the Manager's pre-commit hook (lint-staged + tsc). CI runs both anyway.

### 5.3 Point the Chat build back inside the repo — immediately

Do this in the *same* commit as the import. Until it lands, no frontend build in `ui/chat` is
safe to run (see the warning at the top of this phase). In `ui/chat/vite.config.ts`:

```ts
outDir: "dist",
emptyOutDir: true,   // was false to protect the old backend outDir (V9)
```

Leave `base`, the `chat.html` input, and the `chat-ui.[hash]` output naming alone — §6.3 only
verifies this change; it does not repeat it.

**Phase-1 checkpoint:** `./mvnw compile -DskipUi=true` succeeds (the Maven wiring does not
exist yet, so `skipUi` is a no-op here — the point is that the Java build is untouched), and
`git status` shows no stray `ui/EDDI/` directory.

**Commit:** `chore(monorepo): vendor EDDI-Manager and EDDI-Chat-UI as ui/manager and ui/chat`

---

## 6. Phase 2 — Make the frontends build themselves

The riskiest mechanics here were **dry-run verified** (V2–V9); follow the specifics exactly.

### 6.1 Move the three production HTML shells into the Manager

```bash
git mv src/main/resources/META-INF/resources/manage.html    ui/manager/manage.html
git mv src/main/resources/META-INF/resources/welcome.html   ui/manager/welcome.html
git mv src/main/resources/META-INF/resources/workforce.html ui/manager/workforce.html
```

In each moved file, replace the two hashed asset tags:

```html
<!-- DELETE both: -->
<script type="module" crossorigin src="/assets/index-<hash>.js"></script>
<link rel="stylesheet" crossorigin href="/assets/index-<hash>.css">

<!-- ADD in their place: -->
<script type="module" src="/src/main.tsx"></script>
```

**Preserve verbatim:** `<script src="/manage/__auth_config__.js"></script>` in `<head>`
(Vite leaves absolute non-module scripts alone; Quarkus serves it at runtime), the inline
`.app-loader` style block and loader markup, and the favicon link.

### 6.2 Manager `vite.config.ts` — extend the existing `build` block

The file already imports `fileURLToPath, URL` from `node:url` and — since Revision 2 —
**already has a `build` block** (an `assetsInlineLimit` that keeps woff2 fonts as files
because EDDI serves the Manager under `font-src 'self'`). Add `rollupOptions` **inside that
block**; do not add a second `build` key, and keep `assetsInlineLimit` (everything else — the
~45 proxy entries, `resolve`, `optimizeDeps`, `worker` — stays untouched):

```ts
  build: {
    assetsInlineLimit: /* existing — keep */ ...,
    // dist/ is copied into the Quarkus jar by maven-resources-plugin (§7.2).
    // index.html is deliberately NOT an input — it is the dev-server entry only.
    // The backend keeps its own hand-written index.html redirect shell.
    rollupOptions: {
      input: {
        manage:    fileURLToPath(new URL("./manage.html", import.meta.url)),
        welcome:   fileURLToPath(new URL("./welcome.html", import.meta.url)),
        workforce: fileURLToPath(new URL("./workforce.html", import.meta.url)),
      },
    },
  },
```

Also inject the Maven version so the sidebar's `EDDI Demo ${__APP_VERSION__}` stops drifting
from `pom.xml` (§7.2 passes the env var). The `define` block **already exists** with
`JSON.stringify(pkg.version)`; change that one expression:

```ts
  define: {
    __APP_VERSION__: JSON.stringify(process.env.EDDI_VERSION ?? pkg.version),
  },
```

**Verify with `npm run build` in `ui/manager` (expected per V2/V3/V4):**
- `dist/manage.html`, `dist/welcome.html`, `dist/workforce.html` exist; **no `dist/index.html`**
- all three reference the **same** `/assets/main-<hash>.js` and `/assets/main-<hash>.css`
- `__auth_config__.js` + `.app-loader` present in each
- `dist/` root additionally contains `eddi-icon.ico`, `eddi-icon.svg`, `mockServiceWorker.js`
  (the `public/` copy — the worker is excluded later at §7.2). `logo_eddi.png` is **not**
  there any more (V4)
- `dist/assets/` contains the entry **plus** at least one large split chunk
  (`editor-registry-<hash>.js`, V3) — expected, not a leak

### 6.3 Chat `vite.config.ts` — verify only (already retargeted in §5.3)

The `outDir` / `emptyOutDir` change was made in Phase 1, because leaving it until here would
let a Phase-1 build write outside the repo. Nothing to edit; just confirm `npm run build` in
`ui/chat` emits exactly `chat.html`, `scripts/js/chat-ui.<hash>.js`,
`scripts/css/chat-ui.<hash>.css`, `fonts/**`, `img/**` (V7) — and that no `ui/EDDI/`
directory was created.

### 6.4 Untrack generated output and single-source the statics

Every static file gets exactly one source of truth. Ownership after this step:

| File(s) | Single source | Rationale |
|---|---|---|
| `index.html` (redirect shell), `robots.txt`, `scripts/js/landing-redirect.js` | **backend** `src/main/resources` | Hand-written, no frontend build involved |
| `eddi-icon.ico`, `eddi-icon.svg` | **`ui/manager/public/`** | Byte-identical today (V5); Manager dist provides them |
| `fonts/**`, `img/favicon.ico`, `img/logo_eddi.png` | **`ui/chat/public/`** | Chat dist provides them (V6); all four shells reference `/img/logo_eddi.png` |
| `logo_eddi.png` (root) | **deleted** | Manager no longer ships it (V4) and nothing references the root copy (V5) |
| `img/loading-indicator.svg` | **deleted** | Referenced nowhere in any repo (V6) |
| `assets/**`, `manage/welcome/workforce/chat.html`, `chat-ui.*` bundles | **build output** | Never committed again |

```bash
git rm -r src/main/resources/META-INF/resources/assets
git rm    src/main/resources/META-INF/resources/chat.html
git rm    src/main/resources/META-INF/resources/scripts/js/chat-ui.*.js
git rm    src/main/resources/META-INF/resources/scripts/css/chat-ui.*.css
git rm    src/main/resources/META-INF/resources/eddi-icon.ico \
          src/main/resources/META-INF/resources/eddi-icon.svg \
          src/main/resources/META-INF/resources/logo_eddi.png
git rm -r src/main/resources/META-INF/resources/fonts \
          src/main/resources/META-INF/resources/img
```

> Consequence, accepted: a `-DskipUi=true` build serves `index.html` whose favicon 404s —
> that build has no UI at all anyway.

Append to `.gitignore`:

```gitignore
# === Generated frontend output (built by frontend-maven-plugin into target/) ===
ui/node/
ui/*/node_modules/
ui/*/dist/
# Protective: nothing writes here any more; stale local deploy scripts must not
# be able to silently re-introduce committed assets.
src/main/resources/META-INF/resources/assets/
src/main/resources/META-INF/resources/manage.html
src/main/resources/META-INF/resources/welcome.html
src/main/resources/META-INF/resources/workforce.html
src/main/resources/META-INF/resources/chat.html
src/main/resources/META-INF/resources/scripts/js/chat-ui*.js
src/main/resources/META-INF/resources/scripts/css/chat-ui*.css
```

> `ui/node/` (not `ui/*/node/`) — frontend-maven-plugin's `installDirectory` is `ui/`, so the
> vendored Node lands at exactly `ui/node/`, which `ui/*/node/` does NOT match.

### 6.5 `.gitattributes` cleanup

Remove the three stale `linguist-generated` lines (`chat-ui.*.js`, `chat-ui.*.css`,
`assets/**`) and the comment block claiming chat-ui is "built by the separate eddi-chat-ui
repo and committed here" — false after this change. Optionally add
`package-lock.json text eol=lf` to prevent the CRLF churn that already corrupted one
committed bundle (V10).

Also add `ui/chat/.editorconfig` (`root = true`, 2-space, LF — copy the Manager's) so the
root `.editorconfig`'s `[*] indent_size = 4` does not govern the Chat's TypeScript (§0.6 F6).
The Manager already carries its own `root = true` file.

**Commit:** `chore(ui): build the frontends into dist/ instead of the backend source tree`

---

## 7. Phase 3 — Wire the UI into the Maven build

Without this, `mvnw package` produces a jar with a broken `/manage`. Mandatory.

### 7.1 Properties (existing `<properties>` block of `pom.xml`)

```xml
<frontend-maven-plugin.version>1.15.1</frontend-maven-plugin.version>
<node.version>v20.20.2</node.version>
<skipUi>false</skipUi>
```

`maven-resources-plugin` needs no property: 3.3.1 is already the managed version in the
effective POM (checked 2026-09-15), so the plugin block below declares no `<version>`.
`v20.20.2` was the newest Node 20 on 2026-09-15 (`nodejs.org/dist/index.json`); bump to the
newest 20.x at implementation time. `frontend-maven-plugin` 1.15.1 is still the latest.

### 7.2 Plugins (append inside the existing root `<build><plugins>`)

```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <version>${frontend-maven-plugin.version}</version>
    <configuration>
        <skip>${skipUi}</skip>
        <nodeVersion>${node.version}</nodeVersion>
        <installDirectory>${project.basedir}/ui</installDirectory>
    </configuration>
    <executions>
        <execution>
            <id>install-node</id>
            <goals><goal>install-node-and-npm</goal></goals>
            <phase>generate-resources</phase>
        </execution>
        <execution>
            <id>manager-install</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration>
                <arguments>ci</arguments>
                <workingDirectory>${project.basedir}/ui/manager</workingDirectory>
            </configuration>
        </execution>
        <execution>
            <id>manager-build</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration>
                <arguments>run build</arguments>
                <workingDirectory>${project.basedir}/ui/manager</workingDirectory>
                <environmentVariables>
                    <!-- keeps the UI footer version in lockstep with pom.xml (§6.2) -->
                    <EDDI_VERSION>${project.version}</EDDI_VERSION>
                </environmentVariables>
            </configuration>
        </execution>
        <execution>
            <id>chat-install</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration>
                <arguments>ci</arguments>
                <workingDirectory>${project.basedir}/ui/chat</workingDirectory>
            </configuration>
        </execution>
        <execution>
            <id>chat-build</id>
            <goals><goal>npm</goal></goals>
            <phase>generate-resources</phase>
            <configuration>
                <arguments>run build</arguments>
                <workingDirectory>${project.basedir}/ui/chat</workingDirectory>
            </configuration>
        </execution>
    </executions>
</plugin>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-resources-plugin</artifactId>
    <!-- no <version>: 3.3.1 is managed (§7.1) -->
    <executions>
        <execution>
            <id>copy-ui-bundles</id>
            <goals><goal>copy-resources</goal></goals>
            <phase>process-resources</phase>
            <configuration>
                <skip>${skipUi}</skip>
                <outputDirectory>${project.build.outputDirectory}/META-INF/resources</outputDirectory>
                <resources>
                    <resource>
                        <directory>${project.basedir}/ui/manager/dist</directory>
                        <excludes>
                            <!-- MSW dev worker: needed in public/ for dev + Playwright MSW
                                 tier, but must NEVER ship in the production jar (V4) -->
                            <exclude>mockServiceWorker.js</exclude>
                        </excludes>
                    </resource>
                    <resource><directory>${project.basedir}/ui/chat/dist</directory></resource>
                </resources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

**Also add these excludes to the project's *default* `<resources>` entry for
`src/main/resources`** — not just to `copy-ui-bundles`:

```xml
<resources>
    <resource>
        <directory>src/main/resources</directory>
        <excludes>
            <!-- Generated by the UI build and copied from ui/*/dist in
                 process-resources. A developer upgrading from a pre-monorepo
                 checkout still has these on disk (they are gitignored, not
                 deleted), and without this the default resource copy would
                 stage the STALE committed bundle into target/classes, where a
                 fresh one may not overwrite it. -->
            <exclude>META-INF/resources/assets/**</exclude>
            <exclude>META-INF/resources/manage.html</exclude>
            <exclude>META-INF/resources/welcome.html</exclude>
            <exclude>META-INF/resources/workforce.html</exclude>
            <exclude>META-INF/resources/chat.html</exclude>
            <exclude>META-INF/resources/scripts/js/chat-ui*.js</exclude>
            <exclude>META-INF/resources/scripts/css/chat-ui*.css</exclude>
        </excludes>
    </resource>
</resources>
```

> **Why `.gitignore` is not enough.** Ignoring a path stops it being *committed*; it does
> nothing to stop Maven copying it. Anyone who had the repo checked out before the migration
> keeps the 562 generated files on disk, and the default `src/main/resources` copy would put
> them in `target/classes` — an obsolete `index-<hash>.js` that no shell references, or worse
> a stale `manage.html` pointing at it.
>
> **`copy-resources` also never deletes.** It overwrites and adds; it does not prune. On an
> incremental (non-`clean`) build, hashed assets from previous builds accumulate in
> `target/classes` indefinitely and get packaged. Two consequences for the plan: §7.3's
> verification must always run after `clean`, and the CI `build-image` job must use
> `mvnw clean package` (it does).
>
> **And the excludes only protect the Maven copy.** Quarkus dev mode watches
> `src/main/resources` itself for live reload, so a stale `manage.html` left on disk by a
> pre-migration checkout can still reach `quarkus:dev` (§0.6 F4; §7.3 has the check). Make
> removal deterministic instead of hoping: on `mvnw clean`, delete the gitignored generated
> paths from the source tree too. Add to the root `<build><plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-clean-plugin</artifactId>
    <!-- no <version>: 3.2.0 is managed -->
    <configuration>
        <filesets>
            <fileset>
                <!-- Pre-monorepo checkouts still carry these on disk. They are
                     gitignored (§6.4) and excluded from the resource copy above, but
                     quarkus:dev serves src/main/resources directly, so remove them. -->
                <directory>src/main/resources/META-INF/resources</directory>
                <includes>
                    <include>assets/**</include>
                    <include>manage.html</include>
                    <include>welcome.html</include>
                    <include>workforce.html</include>
                    <include>chat.html</include>
                    <include>scripts/js/chat-ui*.js</include>
                    <include>scripts/css/chat-ui*.css</include>
                </includes>
            </fileset>
            <fileset>
                <directory>ui/manager/dist</directory>
            </fileset>
            <fileset>
                <directory>ui/chat/dist</directory>
            </fileset>
        </filesets>
    </configuration>
</plugin>
```

> The upgrade note for developers (§7.4) is then one line: run `./mvnw clean` once after
> pulling the migration.

The two dist trees do not collide (Manager: three shells + `assets/**` + icons; Chat:
`chat.html` + `scripts/**` + `fonts/` + `img/`).

### 7.3 Verification gates for this phase

```bash
./mvnw clean package -DskipTests
```

In `target/classes/META-INF/resources/` assert:
- `manage.html`, `welcome.html`, `workforce.html`, `chat.html`, non-empty `assets/`
- `index.html` still the redirect shell; `scripts/js/landing-redirect.js` present
- `eddi-icon.svg`, `logo_eddi.png` (from Manager dist), `fonts/`, `img/` (from chat dist)
- **`mockServiceWorker.js` ABSENT**
- **no `assets/index-*.js` or `assets/index-*.css`** — that is the pre-migration entry name
  (V3); its presence means a stale file leaked through, not a fresh build

**Dirty-workspace test — run this explicitly, it is the case the excludes exist for.** A
clean clone cannot reproduce it, but every existing developer checkout can:

```bash
# simulate an upgraded pre-monorepo checkout
mkdir -p src/main/resources/META-INF/resources/assets
echo "stale" > src/main/resources/META-INF/resources/assets/index-STALE00.js
echo "stale" > src/main/resources/META-INF/resources/manage.html
./mvnw clean package -DskipTests
test ! -e target/classes/META-INF/resources/assets/index-STALE00.js || { echo "LEAK"; exit 1; }
grep -q 'assets/main-' target/classes/META-INF/resources/manage.html || { echo "STALE SHELL"; exit 1; }
# clean up the simulation
rm -rf src/main/resources/META-INF/resources/assets src/main/resources/META-INF/resources/manage.html
```

```bash
./mvnw clean package -DskipTests -DskipUi=true    # must succeed, run zero npm
```

**Dev-mode stale-file check (proves or disproves §0.6 F4 — run it, do not assume):**

```bash
echo "stale" > src/main/resources/META-INF/resources/manage.html   # simulate again, AFTER the clean above
./mvnw quarkus:dev -DskipUi=true &                                  # needs MongoDB on 27017
sleep 60; curl -s localhost:7070/manage | head -c 40; echo          # must NOT print "stale"
kill %1; rm src/main/resources/META-INF/resources/manage.html
```

If it prints `stale`, dev mode serves the source tree and the `maven-clean-plugin` fileset in
§7.2 is load-bearing (document "run `./mvnw clean` once" prominently in §7.4). If it does not,
the fileset is belt-and-braces — keep it anyway; it is also what cleans `ui/*/dist`.

Then a real container check: build the image, run it with MongoDB, and:

```bash
for p in /manage /welcome /workforce /chat; do curl -sf localhost:7070$p | grep -o 'assets/main-[^"]*\.js\|scripts/js/chat-ui[^"]*\.js'; done
curl -s -o /dev/null -w "%{http_code}" localhost:7070/mockServiceWorker.js   # expect 404
```

### 7.4 Developer-experience notes (document in AGENTS.md §7 update)

- Per V17, **every** `mvnw compile/test/verify/quarkus:dev` now runs the frontend build
  (**~2 min** measured: Manager `npm run build` is 112 s on a developer machine, V23; the Chat
  adds seconds; `npm ci` adds more on a cold cache) unless `-DskipUi=true`.
- **Once, after pulling the migration:** `./mvnw clean` — the clean plugin removes the
  pre-migration generated files still on disk (§7.2). `git status` will show them as
  untracked-and-ignored before, nothing after. Backend-only work: `./mvnw test -DskipUi=true`,
  `./mvnw quarkus:dev -DskipUi=true`.
- Quarkus live-reload never rebuilds the UI. Frontend dev continues exactly as today:
  `npm run dev` in `ui/manager` (port 3000, proxies to :7070).
- **Node versions:** Maven vendors `v20.20.2` (§7.1) and CI uses Node 20, matching the Manager's
  own CI — but the Manager's `mise.toml` pins `node = "v25.9.0"` and both frontends are on
  Vite 6 (≥ 20.19 required). Local `mise` users therefore already build on a different major
  than CI. Reconcile in the root `mise.toml` (one `node` pin for the repo) as part of §9.4; the
  §2 "keep Node 20" decision is about *CI*, and a later bump remains its own change.
- `.dockerignore` needs no change (deny-all + `target/quarkus-app/**` allowlist; the UI now
  travels inside the app jar).

**Commit:** `build(ui): build and bundle the Manager and Chat UIs via Maven`

---

## 8. Phase 4 — CI: the actual payoff

All edits in `.github/workflows/ci.yml` unless stated. This section is a **complete rewiring
spec** — the job splits change output plumbing that three downstream jobs depend on; follow
the reference tables exactly.

### 8.1 `detect-changes`

- Add to the paths-filter: `ui: ['ui/**', '.github/workflows/ci.yml']` (the workflow itself,
  so a CI-only change exercises the UI job — §0.6 F9); expose as output `ui`, with the same
  tag-push forcing as `code` (a tag must force `ui=true`).
- Add `'ui/**'` to the existing `code:` filter list (a UI change must produce an image).

### 8.2 Add `-DskipUi=true` to every Maven job that doesn't ship the UI

| Job | Command gains |
|---|---|
| `build-and-test` | `-DskipUi=true` |
| `integration-test` | `-DskipUi=true` (ITs are API-level; ContainerBaseIT needs no UI) |
| `codeql` (in ci.yml) | `-DskipUi=true` |
| `codeql.yml` (scheduled — V13) | `-DskipUi=true` |
| `sbom` | **nothing** — direct goal invocation, no lifecycle (V14) |

### 8.3 New job: `ui-build-and-test`

Gated on `needs.detect-changes.outputs.ui == 'true'`. Node 20,
`cache: npm`, `cache-dependency-path: ui/*/package-lock.json`.

In `ui/manager`: `npm ci` → `npm run audit:prod` → `npm run lint` → `npm run i18n:check` →
`npm run typecheck` → `npx vitest run --coverage` (this includes `openapi-contract.test.ts`,
which checks the MSW handlers against the committed `openapi-operations.json` snapshot) →
`npx playwright install --with-deps chromium` → `npm run test:e2e` (MSW tier, no backend) →
upload `playwright-report/` + `coverage/`. Then in `ui/chat`: `npm ci` → `npm run typecheck`
→ `npm test`.

Replaces the old Manager `ci-cd.yml` and the `UI E2E (MSW)` job of its `e2e.yml` one-for-one.
The Manager's `mutation.yml` (Stryker, PR + weekly) is **not** carried over in the migration
PR — see §13.

### 8.4 New job: `build-image`

`needs: [detect-changes]` **only** — deliberately parallel to `build-and-test` so E2E isn't
serialized behind unit tests; the publish gate (§8.6) still requires everything. Runs when
`code == 'true'`, **including pull requests** (no secrets used → fork PRs get full E2E).

1. The existing `Compute Docker tags` step moves here **verbatim**; declare job outputs:
   `primary-tag`, `is-release`, `is-stable`, `minor-tag`, `major-tag`.
2. `./mvnw clean package -DskipTests -Plicense-gen -B` (builds the UI — no skipUi here).
3. `docker build … -t labsai/eddi:ci` (single tag; real tags are applied at publish).
4. `docker save labsai/eddi:ci | gzip > eddi-ci.tar.gz`; upload artifact `eddi-ci-image`,
   **retention 7 days** (1 day breaks Monday re-runs of a Friday pipeline).

### 8.5 New job: `e2e-fullstack`

`needs: build-image`; `fail-fast: false`; both DBs on push/tag, MongoDB only on PRs.

> **Do NOT write `if: matrix.database == 'mongodb' || …` on the job.** GitHub evaluates
> `jobs.<id>.if` **before** matrix expansion, and the `matrix` context is not among the
> contexts available there (`github`, `needs`, `vars`, `inputs`). The condition would not
> filter the legs — it errors or silently misbehaves, and the MongoDB leg you were counting
> on may not run at all. `matrix` is only usable in `steps.*.if`.

Build the matrix as data instead. Have `detect-changes` emit it:

```yaml
      - name: Resolve E2E matrix
        id: dbs
        run: |
          if [ "${{ github.event_name }}" = "pull_request" ]; then
            echo 'databases=["mongodb"]' >> $GITHUB_OUTPUT
          else
            echo 'databases=["mongodb","postgres"]' >> $GITHUB_OUTPUT
          fi
```

and consume it:

```yaml
  e2e-fullstack:
    name: Backend E2E (${{ matrix.database }})
    needs: [detect-changes, build-image]
    strategy:
      fail-fast: false
      matrix:
        database: ${{ fromJSON(needs.detect-changes.outputs.databases) }}
```

Map the database to its compose file with a `steps`-level expression or a small `case`, since
the previous `include:` mapping no longer has a static matrix to attach to.

1. Download `eddi-ci-image`, `docker load`.
2. Node 20; `npm ci` in `ui/manager`; `npx playwright install --with-deps chromium`.
3. `EDDI_IMAGE=labsai/eddi:ci docker compose -f ui/manager/docker-compose.integration<-postgres>.yml up -d --wait`
4. Reuse the existing 60×2s health poll on `/q/health/live`.
5. **Shipped-shell verification** (closes the gap that Playwright's webServer is the Vite
   *dev* server, so nothing else ever loads the built shells):
   ```bash
   for p in manage welcome workforce chat; do
     HTML=$(curl -sf http://localhost:7070/$p) || { echo "::error::/$p failed"; exit 1; }
     for a in $(echo "$HTML" | grep -o '/assets/[^"]*\|/scripts/[^"]*'); do
       curl -sf -o /dev/null "http://localhost:7070$a" || { echo "::error::/$p asset $a failed"; exit 1; }
     done
   done
   curl -s -o /dev/null -w "%{http_code}" http://localhost:7070/mockServiceWorker.js | grep -q 404
   ```
6. `npm run test:e2e:integration` then `npm run test:e2e:fullstack` (both in `ui/manager`).
7. Always: dump `docker compose logs` on failure, upload Playwright report,
   `docker compose down -v`.

In **both** compose files, parameterize the image (the two guard opt-outs from §4.7 must
already be there — if they are not, the backend exits(1) and every step below fails, V22):

```yaml
services:
  eddi:
    image: ${EDDI_IMAGE:-labsai/eddi:latest}
    environment:
      EDDI_SECURITY_ALLOW_UNAUTHENTICATED: "true"
      EDDI_MCP_ALLOW_UNAUTHENTICATED: "true"          # §4.7
      EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED: "true"  # §4.7
```

(Local `npm run infra:up:mongo` keeps working via the fallback.)

> Do not carry over the old workflow's dead `github.event_name == 'schedule'` condition — it
> never had a `schedule:` trigger.

### 8.6 `docker` job becomes publish-only — exact rewiring

Keep its `if:` guard exactly as-is (push-only, repo check, `[skip docker]`, tag
short-circuit) and the no-`always()` comment. Change:

- `needs: [detect-changes, build-and-test, integration-test, trivy-scan, codeql, gitleaks, build-image, e2e-fullstack]`
- Remove: JDK setup, `Build application`, `Build Docker image`, `Compute Docker tags`.
- Add: download `eddi-ci-image` → `docker load` → retag `labsai/eddi:ci` to the computed
  tags. **The published bytes are the tested bytes.**
- **Every former `steps.meta.outputs.X` reference becomes `needs.build-image.outputs.X`** —
  this hits the Trivy image scan (`image-ref`), the push step, and the cosign step.
- **The job must re-export outputs** (downstream jobs consume `needs.docker.outputs.*`):

```yaml
    outputs:
      primary-tag: ${{ needs.build-image.outputs.primary-tag }}
      is-release:  ${{ needs.build-image.outputs.is-release }}
      is-stable:   ${{ needs.build-image.outputs.is-stable }}
```

| Downstream consumer | What it reads | Breaks without re-export |
|---|---|---|
| `release` | `needs.docker.outputs.primary-tag` | GitHub Release body wrong/empty |
| `smoke-test` | `needs.docker.outputs.primary-tag` | signature verify + run target empty |
| `preflight-push` | `needs.docker.outputs.primary-tag`, `is-release` | pulls empty tag |
| `redhat-publish` (added after Revision 2; reusable `redhat-certify.yml`) | `needs.docker.outputs.is-stable`, `primary-tag` | never runs on a stable tag, or certifies an empty version |

`smoke-test`, `release`, `preflight-push`, `redhat-publish` themselves need no other changes.
The cosign signature and SLSA attestation steps **stay in `docker`** — they sign the pushed
digest, which does not exist until this job pushes.

#### 8.6b Gating publish on UI health — use an aggregator, never a bare `needs`

As listed above, `docker` does **not** depend on `ui-build-and-test`, so a UI change that
fails lint or the MSW suite could still publish an image. That is a real hole — but the
obvious fix is a trap:

> **Do not simply add `ui-build-and-test` to `docker.needs`.** It is gated on
> `outputs.ui == 'true'`, so on a backend-only change it is *skipped* — and GitHub's default
> `needs` semantics skip any dependent job when a dependency is skipped. `docker` would then
> skip too, and **backend-only pushes would silently stop publishing images.**

Add a small always-running aggregator and depend on that instead:

```yaml
  ui-gate:
    name: UI Gate
    runs-on: ubuntu-latest
    needs: [detect-changes, ui-build-and-test]
    if: always()
    steps:
      - name: Require UI success only when the UI actually changed
        run: |
          CHANGED='${{ needs.detect-changes.outputs.ui }}'
          RESULT='${{ needs.ui-build-and-test.result }}'
          if [ "$CHANGED" = 'true' ] && [ "$RESULT" != 'success' ]; then
            echo "::error::UI changed but ui-build-and-test=$RESULT"; exit 1
          fi
          echo "ui changed=$CHANGED result=$RESULT — gate satisfied"
```

Then add `ui-gate` (not `ui-build-and-test`) to `docker.needs`. The same pattern is the
fallback for branch protection in §10 if skipped checks turn out not to satisfy it.

### 8.7 `preflight-check` (PR dry-run) — reuse the artifact

Change to `needs: build-image`; replace its JDK + `mvnw package` + `docker build` steps with
artifact download + `docker load` + retag to `eddi-preflight-check:test`. Saves a full
duplicate Maven+npm+Docker build per PR and preflights the actual artifact.

### 8.8 Concurrency and notifications

- A workflow-level concurrency group **already exists** (`ci-${{ github.workflow }}-${{
  github.ref }}`, cancel-in-progress on PRs only — added after Revision 2). Nothing to add;
  just do not remove it, the pipeline is now much heavier and main/tag runs must never be
  cancelled mid-publish.
- `notify-slack`: add `ui-build-and-test`, `build-image`, `e2e-fullstack` to `needs:` and to
  the status-fields block, following the existing `status_icon` pattern.

**Commit:** `ci(monorepo): build once, E2E the built image on both DBs, then publish it`

---

## 9. Phase 5 — Repository housekeeping

### 9.1 `.github/dependabot.yml` — add npm ecosystems

> **Decision required first (new since Revision 2):** the Manager and the Chat UI run
> **Renovate** (`renovate.json`: pin devDependencies, automerge minor/patch once CI is green,
> `react-router` majors blocked, reviewer `kennethlynne`; 23 Renovate PRs on the Manager so
> far). EDDI runs **Dependabot** plus `auto-approve-copilot.yml`. Two bots on one repo is the
> one option that is clearly wrong. Recommended: Dependabot (below), delete `renovate.json`
> in §5.2, and accept that minor/patch automerge is lost unless `auto-approve-copilot.yml` is
> extended to npm — check that before deciding, because Renovate's automerge is what kept the
> Manager's dependency PR count at five.

```yaml
  - package-ecosystem: npm
    directory: /ui/manager
    schedule: { interval: weekly, day: monday }
    open-pull-requests-limit: 5
    labels: [dependencies, ui]
    groups:
      minor-and-patch:
        patterns: ["*"]
        update-types: [minor, patch]

  - package-ecosystem: npm
    directory: /ui/chat
    schedule: { interval: weekly, day: monday }
    open-pull-requests-limit: 3
    labels: [dependencies, ui]
```

Review `auto-approve-copilot.yml` to confirm its rules behave sensibly for npm dependabot PRs.

### 9.2 CodeQL for TypeScript — **all three** surfaces (V13)

1. Convert the language to a matrix `[java-kotlin, javascript-typescript]` in the ci.yml
   `codeql` job **and** in `codeql.yml`. The JS leg needs no Maven build step; the Java leg
   keeps its build with `-DskipUi=true` (§8.2).

   > **⚠ This renames the check and will break branch protection if done naively.** A
   > matrixed job emits one check per leg, named `<job name> (<matrix value>)`. The ci.yml
   > job is currently `name: CodeQL Analysis` with no matrix, so it emits exactly
   > **`CodeQL Analysis`** — which is one of only two required contexts on `main` (V11).
   > Matrixing it replaces that with `CodeQL Analysis (java-kotlin)` and
   > `CodeQL Analysis (javascript-typescript)`; the required `CodeQL Analysis` would then
   > never report and every PR would sit at "Expected — waiting for status".
   >
   > Worse, `codeql.yml`'s job is **also** named `CodeQL Analysis` and already has a
   > `language` matrix, so both files would emit an identically-named
   > `CodeQL Analysis (java-kotlin)` check — ambiguous for branch protection.
   >
   > Therefore: give the two jobs **distinct** `name:` values (e.g. `CodeQL PR` in ci.yml,
   > `CodeQL Scheduled` in codeql.yml), and update the required contexts to the exact emitted
   > names **in the same change** — protection edits and workflow renames must land together
   > or `main` is either wedged or unguarded in the window between them.
2. The **third** surface is the GitHub-managed dynamic run (`Analyze (java-kotlin)`, V13)
   configured in **repo settings**, not in a file here. Enable `javascript-typescript` there
   too, or the newly in-repo TypeScript stays unscanned by it. This is a settings change and
   cannot be done in the PR — put it on the §10 checklist.

Own commit; independently revertible.

### 9.3 `CODEOWNERS`

**No change.** The file is a single `* @ginccc @rolandpickl` rule, which already covers
`ui/**` (§0.6 F7). Add path-specific owners only if frontend ownership is meant to differ.

### 9.4 Documentation

- `AGENTS.md` §1 Ecosystem table: Manager and chat-ui become directories of this repo.
- **Keep `ui/manager/AGENTS.md` where the subtree put it** (§2); fix its repo-relative
  references (e.g. "the EDDI repo" → "the repo root"); add a one-line
  `ui/manager/CLAUDE.md` containing `Read and follow @AGENTS.md`.
- `AGENTS.md`: add the §7.4 dev-experience notes (skipUi, npm run dev workflow) and a `ui/`
  row in Key Files.
- `README.md` + `CONTRIBUTING.md`: build instructions, badges.
- Sweep stale references: `grep -rn "EDDI-Manager\|eddi-chat-ui\|EDDI-Chat-UI" docs/ README.md CONTRIBUTING.md`
- Update `docs/changelog.md` **in the same commit** (AGENTS.md §2.8).
- **`docs/` is published to docs.labs.ai via GitBook**, with `docs/SUMMARY.md` as the table of
  contents — a GitBook check runs on every PR and renders a preview. Any *new* page added
  under `docs/` needs a `SUMMARY.md` entry or it is written but never published. (Files not
  listed there — `changelog.md` among them — are deliberately unpublished, and `planning/`
  lies outside the GitBook space entirely, which is why plan documents belong there.)

### 9.5 Archive the old repos — sequencing

**Only after the monorepo has produced at least one green `main` pipeline including
`e2e-fullstack`.** For both `labsai/EDDI-Manager` and `labsai/EDDI-Chat-UI`:

1. Replace `README.md` with a pointer to `labsai/EDDI` `ui/manager` / `ui/chat`, stating
   history is preserved and **the deploy-to-local scripts are dead** (stale local copies
   must not be run — the backend paths they write to are gitignored now).
2. Close open issues/PRs with a link; archive via settings. **Never delete.**

---

## 10. Post-migration reconfiguration checklist (outside this repo's code)

| Where | What |
|---|---|
| GitHub branch protection on `labsai/EDDI` | Required contexts are currently `["CodeQL Analysis", "Build & Test"]` (V11). **Add** `UI Build & Test`, `Build Image`, `Backend E2E (mongodb)` (exact job `name:` strings once written). Without this the new gates are decorative — **but read the skipped-check caveat below first.** |
| GitHub repo settings | Confirm nothing later enables "require linear history" (would forbid the merge-commit this migration depends on for history) |
| GitHub code-scanning settings | Add `javascript-typescript` to the **GitHub-managed** CodeQL surface (`Analyze (java-kotlin)`, V13) — it lives in repo settings, not in a workflow file, so §9.2's file edits do not reach it |
| Branch protection — **make the security scans merge-blocking** | `Secret Scanning` (gitleaks), `Trivy Filesystem Scan` and `Dependency Review` are not required contexts today, and `docker` (which consumes Trivy) is push-only — so a PR can merge with a failed scan and only fail afterwards on push. Add all three as required contexts, **or** add one always-running `security-gate` aggregator (same shape as §8.6b) that depends on them and require that. Note Trivy is itself path-gated on `outputs.code`, so if it is required directly, the §10 skipped-check caveat applies to it too — which is the main argument for the aggregator. |
| Branch protection — **rename-safe CodeQL contexts** | If §9.2 lands, the required `CodeQL Analysis` context stops being emitted. Update the required contexts to the new distinct names **in the same change**, or `main` wedges on "Expected". |
| Local machines | `EDDI.code-workspace` references the separate folders — update. The `EDDI-Manager-track-2` checkout becomes obsolete. Delete stale local copies of the deploy scripts. |
| eddi-website / docs.labs.ai | Repo links for Manager and chat-ui |
| Docker Hub `labsai/eddi` description | Repo links |
| Old repos | Dependabot/CodeQL/e2e workflows stop on archive — expected; their replacements live here |
| Unaffected (verified) | `quarkus-eddi` SDK (consumes the Docker image only); `ContainerBaseIT`'s inline Dockerfile (UI rides inside `quarkus-app/`) |

> **⚠ Caveat before making any path-gated job a required context.** All three proposed new
> contexts are gated (`ui-build-and-test` on `outputs.ui`, `build-image` / `e2e-fullstack` on
> `outputs.code`), so on a docs-only PR they do not run. GitHub reports a **job-level** `if:`
> skip as a check run with conclusion `skipped`, which branch protection treats as satisfied
> — unlike a **workflow-level** path filter, which leaves the context "Expected" forever and
> wedges the PR. The existing `Build & Test` is job-level gated the same way, so the pattern
> should be safe.
>
> This had never actually been exercised here: every recent `docs(...)` PR (#667, #652, #623)
> also touched `src/`, so `Build & Test` really ran. **PR #670 — the plan PR itself — is the
> first truly docs-only PR**, and it sat at `mergeStateStatus: BLOCKED` with
> `Build & Test: SKIPPED`. That block was attributable to `reviewDecision: REVIEW_REQUIRED`,
> not the skipped check.
>
> **Confirmed empirically on 2026-09-15 (§0.5):** with #670 approved, `Build & Test` skipped
> and `CodeQL Analysis` green, `mergeStateStatus` was `UNSTABLE` (required checks satisfied),
> not `BLOCKED`. Skipped-as-satisfied is proven; the path-gated contexts are safe to add
> directly. Keep the always-running aggregator pattern only where a *`needs`* edge is involved
> (§8.6b), since `needs` — unlike branch protection — treats a skipped dependency as a skip.

---

## 11. Definition of done

- [ ] `./mvnw clean package -DskipTests` → `target/classes/META-INF/resources/` contains the
      four shells + `assets/` + statics per §7.3, **without** `mockServiceWorker.js`
- [ ] `./mvnw clean package -DskipTests -DskipUi=true` succeeds, zero npm
- [ ] `git ls-files src/main/resources/META-INF/resources | wc -l` ≈ **3**
      (`index.html`, `robots.txt`, `scripts/js/landing-redirect.js`)
- [ ] `git grep -n "\.\./EDDI" -- ui/` returns nothing
- [ ] Running container serves `/manage`, `/welcome`, `/workforce`, `/chat` with `main-<hash>`
      / `chat-ui.<hash>` assets resolving 200, `/mockServiceWorker.js` → 404, sidebar shows
      the pom version
- [ ] `ui-build-and-test` green; `e2e-fullstack` green on **both** DBs against the local
      image (no `docker pull labsai/eddi` in its logs)
- [ ] Published image digest == digest E2E ran against
- [ ] `release`, `smoke-test`, `preflight-push` still function on the first post-merge push
      (they consume re-exported outputs — §8.6)
- [ ] Migration PR was merged with a **merge commit**; `git log --oneline -- ui/manager/package.json | wc -l`
      is > 1 and `git blame ui/manager/package.json` shows Manager commit messages (SHAs are
      the rewritten ones from §5.1; the archived repo keeps the originals)
- [ ] `git tag | wc -l` unchanged by the import (§5.1)
- [ ] Both `ui/manager/docker-compose.integration*.yml` carry the two `HighValueSurfaceGuard`
      opt-outs (§4.7) and `e2e-fullstack` reached "backend healthy" — not just "tests ran"
- [ ] Branch-protection contexts updated (§10)
- [ ] Old repos archived with pointer READMEs; `docs/changelog.md` records the migration

---

## 12. Risks and rollback

| Risk | Severity | Mitigation |
|---|---|---|
| Squash-merge of the migration PR destroys imported history | **High** | §2 decision + §4.6 + PR description warning |
| Importing with `git subtree add` silently loses path-scoped `git log` / `--follow` (V21) | **High** | §2 decision changed to `filter-repo` + unrelated-history merge; §5.1 asserts it |
| `e2e-fullstack` red from day one because the compose files cannot boot current EDDI (V22) | **High** | §4.7 preparatory Manager PR; §8.5 env block |
| Re-running `filter-repo` after the gitleaks scan changes every SHA and invalidates the fingerprints | Medium | §4.3/§5.1: rewrite once, scan that, merge that |
| Migration PR too large for CodeRabbit/Copilot; `auto-approve-copilot` cannot approve it | Low | §4.6: human review of the listed reviewable surface |
| Gitleaks scans 1,275 imported commits; ignores only honored from base branch | **High** | §4.3 pre-scan; land `.gitleaksignore` on main **first** |
| dependency-review blocks the PR on npm CVEs/licenses — **confirmed live for Chat (§0.3)** | **High** | §4.4 pre-check in source repos; bump `react-router` ≥ 7.18.2 in Chat first |
| Trivy fs scan newly gates backend releases on npm CVEs — **confirmed live for Chat (§0.3)** | **High** | §4.2 scratch scan before merge; same bump |
| Import fails because Chat's default branch is `master` | Low | §5.1 / V20 |
| 30+ orphaned Manager branches / in-flight local work | **High** | §4.1 freeze + `git am --directory=` recipe |
| Matrixing the CodeQL job renames its check and wedges `main` on a required context that never reports | **High** | §9.2 warning — distinct job names + protection update in the same change |
| Adding `ui-build-and-test` to `docker.needs` silently stops publishing on backend-only pushes (skipped dependency ⇒ skipped dependent) | **High** | §8.6b `ui-gate` aggregator |
| Phase-1 build writes to a stray `ui/EDDI/` because the imported chat `outDir` still points at a sibling | Medium | §5.3 retargets it in the import commit, before any frontend build |
| Stale pre-migration assets in a developer's `src/main/resources` leak into the jar via the default resource copy | Medium | §7.2 default-`<resources>` excludes + §7.3 dirty-workspace test |
| `matrix` used in a job-level `if` does not filter legs — the MongoDB PR leg may not run | Medium | §8.5 `fromJSON` dynamic matrix from `detect-changes` |
| A PR merges with failed Trivy/Gitleaks because neither is a required context | Medium | §4 preamble + §10 `security-gate` aggregator |
| MSW service worker ships to production | Medium | §7.2 exclude + §7.3/§8.5/§11 404 checks |
| CI/dev builds slow down from unconditional npm | Medium | §8.2 skipUi wiring + §7.4 dev docs |
| Multi-page build regression blanks a shell | Medium | Dry-run verified (V2); §8.5 shipped-shell gate makes it permanent |
| `ui/node/` accidentally committed | Low | §6.4 gitignore (exact-path entry) |
| ~104 MiB pack growth | Low | Accepted; irreversible without forbidden history rewrite |
| Version drift `pom.xml` ↔ UI footer | Low | §7.2 `EDDI_VERSION` injection |

**Rollback:** every phase is its own commit; nothing force-pushed; `git revert` unwinds any
phase. Full retreat = revert the merge commit + un-archive the source repos. Do not delete
the source repos until the monorepo has been green for a full release cycle.

---

## 13. Explicitly out of scope (each is its own later change)

- Node 20 → 22 (or the Manager's own `mise` pin of 25); npm workspaces
- Porting the Manager's Stryker mutation workflow (`mutation.yml` + `assert-mutants-tested.mjs`)
  as a path-gated, scheduled job in `ci.yml`
- Generating `ui/manager/src/test/mocks/openapi-operations.json` in CI from the freshly built
  image (the `e2e-fullstack` job already has a running backend) and failing on drift — this
  replaces the hand-run `npm run openapi:refresh`
- The 13 backend ITs with no PostgreSQL twin (`A2aEndpointIT`, `ComplexRulesAgentEngineIT`,
  `CreateApiAgentIT`, `GroupHitlIT`, `HitlPauseResumeIT`, `HitlToolPauseResumeIT`,
  `HttpCallsAgentEngineIT`, `ImportMergeIT`, `LlmAgentEngineIT`, `LogAdminIT`,
  `McpEndpointIT`, `OpenAiCompatIT`, `PropertySetterAgentEngineIT`)
- Authenticated E2E path (start from `ui/manager/docker-compose.keycloak.yml`; today every
  E2E runs `quarkus.oidc.tenant-enabled=false`)
- Re-adding DAST (standing note at `ci.yml` "Job 4b")
- Lint/vitest parity for `ui/chat`; code-splitting the 8.4 MB Manager bundle
- npm SBOM generation alongside the Maven CycloneDX one
