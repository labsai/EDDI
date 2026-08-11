# Monorepo Migration Plan — EDDI + EDDI-Manager + EDDI-Chat-UI

> **Status:** Proposed, not started. **Revision 2** — after two adversarial review passes with
> local dry-runs of the critical build steps (2026-08-11).
> **Author context:** This document is self-contained. An implementing agent should need no
> other conversation context. Read it top to bottom before touching anything.
> **⚠ THE PR THAT LANDS THIS MUST BE MERGED WITH A MERGE COMMIT — NEVER SQUASH. See §2.**

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

```
14c7046bc chore: update Manager UI assets (Manager@44a0e684)
2925707d7 chore: update Chat UI assets (chat-ui@71fa395)
```

**562 files** sit in `src/main/resources/META-INF/resources/assets/` as committed build
output. Nothing verifies they match the source that produced them — and they demonstrably
don't stay in sync: a fresh build of `chat-ui.DHWeOytM.js` from the chat repo differs
byte-wise from the committed copy of the **same filename** (line-ending normalization at
commit time; see §3 evidence). That is the silent-drift failure mode in the wild.

Neither frontend has an independent version, release, deployment, or consumer.
`eddi-manager` is `"private": true`, version `6.2.0` — identical to `pom.xml`. Both ship
inside the backend jar, in the backend's Docker image, under the backend's git tag. The
standalone `Dockerfile` / `build-service.sh` in the Manager repo are confirmed relics.

| Cost today | Fixed by the monorepo |
|---|---|
| Full-stack E2E runs against `labsai/eddi:latest` pulled from Docker Hub — never the code under test | E2E runs against the image built from the PR's own commit |
| Manager PRs are validated only against MSW mocks; mock drift is silent by construction | Same PR builds the real backend; the API-integration tier runs for free |
| A backend PR that breaks a Manager contract is caught post-publish and blamed on the wrong repo | Caught in the PR that caused it |
| Generated assets committed by a human running a shell script; provably drifted already | Built in CI, correct by construction |
| Three HTML production shells orphaned from their source | They become Vite multi-page inputs (dry-run verified, §3) |

---

## 2. Decisions already made (do not re-litigate)

| Decision | Rationale |
|---|---|
| **Merge both frontends**, not just the Manager | Chat-UI literally cannot build without the backend checked out |
| **Maven stays at the repo root**; Java is not moved to `backend/` | Avoids touching the Dockerfile, all CI path references, and ~40 docs files |
| **`git subtree add` without `--squash`** | Preserves history and blame; all three repos are open source |
| **THE MONOREPO PR MERGES WITH A MERGE COMMIT** | A squash-merge flattens both imported histories into one commit, destroying the attribution the subtree approach exists to preserve. `AGENTS.md` §2 recommends "Squash and merge" as the normal cleanup path — **that guidance is explicitly overridden for this one PR.** Verified: `labsai/EDDI` allows merge commits and `required_linear_history` is off. Tell the person merging. |
| **No npm workspaces** | Hoisting can break Vite/Tailwind resolution; a migration should not change two things at once. Revisit later. |
| **Stop committing build output** | The entire point. Requires Maven to build the UI (§7). |
| **Single-source every static file** (§6.4) | Both frontends' `public/` dirs duplicate files committed in the backend; after migration each file has exactly one source |
| **Keep Node 20** (matches current Manager CI) | Bumping to 22 is a separate, independently reversible change |
| **Keep `ui/manager/AGENTS.md` in place** — do NOT fold into root AGENTS.md | AI assistants auto-load nested instruction files per directory; the root file is already very large. Add a `ui/manager/CLAUDE.md` stub instead. |
| **Archive the old repos, never delete** | They are public; forks and stars exist |

---

## 3. Verified facts and dry-run evidence (2026-08-11)

An implementing agent can rely on these without re-deriving them. If reality disagrees with
this table, stop and investigate before proceeding.

| # | Fact | How verified |
|---|---|---|
| V1 | `/manage`, `/welcome/**`, `/workforce/**` are served by `RestManagerResource.java`, `RestWelcomeResource.java`, `RestWorkforceResource.java` reading `META-INF/resources/{manage,welcome,workforce}.html` from the classpath by exact name | grep of `src/main/java/ai/labs/eddi/ui/` |
| V2 | **The multi-page Vite build works.** With `manage.html`/`welcome.html`/`workforce.html` as rollup inputs (each pointing at `/src/main.tsx`), `vite build` completed in ~29 s and emitted all three shells referencing one shared hashed entry (`/assets/main-<hash>.js` + `/assets/main-<hash>.css`), with `__auth_config__.js` and the `.app-loader` block preserved, and **no** `dist/index.html` | Executed locally in the Manager checkout, then reverted |
| V3 | The entry chunk renames from `index-<hash>.js` to `main-<hash>.js` under multi-page inputs. Nothing references the old pattern (the deploy scripts that did are deleted by this plan). New bundle 8.36 MB vs committed 8.10 MB — same monolith, no regression | Dry-run output + `ls -la` on committed asset |
| V4 | **Vite copies `public/` into dist root.** Manager `public/` = `eddi-icon.ico`, `eddi-icon.svg`, `logo_eddi.png`, **`mockServiceWorker.js`**. The MSW worker must be excluded from the shipped jar (§7.2) | Dry-run dist listing |
| V5 | Manager `public/` icons are **byte-identical** to the backend's committed copies | `cmp` all three |
| V6 | Chat `public/` = `fonts/` (6 files) + `img/` (2 files), matching the backend's committed copies except `img/loading-indicator.svg`, which exists only in the backend and is **referenced nowhere** in any of the three repos | `diff -rq` + grep |
| V7 | The chat build with `outDir: "dist"` emits exactly `chat.html` + `scripts/js/chat-ui.<hash>.js` + `scripts/css/chat-ui.<hash>.css` + the `public/` copy. (A `dist/index.html` seen mid-test was pre-existing tracked content, not Vite output) | Executed locally in the chat checkout, then reverted |
| V8 | **`eddi-chat-ui` tracks files under `dist/`** (`dist/index.html`, `dist/assets/index-*.{js,css}`, `dist/fonts/**`, `dist/img/**`) despite `/dist` in its `.gitignore` — tracked files override ignore rules. These must be `git rm`'d (§5.2) | `git status` after test deletion showed `D dist/...` |
| V9 | Chat sets `emptyOutDir: false` (to protect the old backend outDir). Must flip to `true` when retargeting to `dist/` (§6.3) | Read of `vite.config.ts:15` |
| V10 | A fresh `chat-ui.DHWeOytM.js` differs byte-wise from the committed file of the same name — committed assets have already drifted (almost certainly LF/CRLF normalization at commit time) | `cmp` |
| V11 | Branch protection on `labsai/EDDI` `main`: required checks are exactly `["CodeQL Analysis", "Build & Test"]` (job **names**), 1 review required, `required_linear_history: false`, all three merge methods allowed | `gh api repos/labsai/EDDI/branches/main/protection` |
| V12 | `dependency-review.yml` runs on every PR with `fail-on-severity: high` and `deny-licenses: GPL-3.0, AGPL-3.0` — the monorepo PR introduces the full npm dependency graphs to this gate at once. **Caveat:** the action now emits a deprecation warning that `deny-licenses` "is deprecated for possible removal in the next major release" (upstream issue 997), so do not build the license strategy on it long-term | Read of the workflow + the action's own PR comment on #670 |
| V13 | There are **three** CodeQL surfaces, not two: the `codeql` job in `ci.yml`, the scheduled deep scan `codeql.yml` (both with their own `java-kotlin` language list), **and a GitHub-managed dynamic run** (`path: dynamic/github-code-scanning/codeql`, check name `Analyze (java-kotlin)`, surfaced as "Code Quality: PR #N"). The third is configured in **repo settings, not in any file in this repo**, so §9.2's TypeScript enablement is two file edits *plus* a settings change | Read of both workflows + `gh api repos/labsai/EDDI/actions/runs/<id>` on PR #670 |
| V13b | As of `00420daa5` the ci.yml `codeql` job is **deliberately ungated** — no `needs: detect-changes`, no `if:` — so it builds with Maven on *every* PR including docs-only ones (done so OpenSSF Scorecard sees a SAST run on each PR head SHA). This makes its `-DskipUi=true` (§8.2) load-bearing: without it, every docs-only PR would run the full npm build | Read of ci.yml on current main |
| V14 | `sbom` job invokes `cyclonedx:makeBom` as a direct plugin goal — it runs **no lifecycle phases** and therefore will not trigger the frontend build; it needs no `skipUi` flag | Maven invocation semantics + workflow read |
| V15 | `git subtree` is available in the local Git for Windows | `git subtree -h` |
| V16 | `gitleaks` is **not** installed locally; the Phase-0 history pre-scan (§4.3) needs it installed first | `command -v gitleaks` |
| V17 | Maven lifecycle: `generate-resources` precedes `compile`, so **every** `mvnw compile/test/verify` triggers the frontend build unless `-DskipUi=true` (§7.4) | Lifecycle definition |
| V18 | `.gitattributes` has three `linguist-generated` entries pointing at the committed bundles, plus a comment claiming chat-ui is "a separate repo" — all stale after this migration (§6.5) | Read |

---

## 4. Phase 0 — Preconditions (all mandatory, none skippable)

### 4.1 Branch and worktree freeze

`EDDI-Manager` has 30+ live branches, and **as of writing the local checkouts themselves sit
on unmerged feature branches** (`EDDI-Manager` on `feat/operator-write-scope`, `eddi-chat-ui`
on `fix/release-6.2-polish`) plus active `.claude/worktrees/` in the Manager. The migration
orphans all of it.

1. Merge or close everything mergeable; land the two in-flight local branches.
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

If it fails, fix or `.trivyignore` (with justification) **as a preparatory PR in
EDDI-Manager** before the migration. Note `.trivyignore` is read from the PR checkout, so
last-resort entries *can* ride in the migration PR itself — but prefer fixing first.

### 4.3 Gitleaks history pre-scan — **sequencing trap, read carefully**

The `gitleaks` CI job scans `PR_BASE..PR_HEAD` — and for the migration PR that range contains
**the entire imported history of both repos** (~1,079 commits). Any historical test fixture,
MSW mock token, or storage-state file that pattern-matches a secret fails the PR.

The trap: the CI job deliberately takes `.gitleaksignore` **from the base branch** on PRs
(so a PR cannot allowlist its own secrets). Therefore any needed ignore entries **must land
on `main` in a separate PR *before* the migration PR is opened.**

```bash
gitleaks git --no-banner --redact=100 /c/dev/git/EDDI-Manager   # full history
gitleaks git --no-banner --redact=100 /c/dev/git/eddi-chat-ui
```

(Install gitleaks first — it is not on the machine; V16.) For each finding: real secret →
rotate + handle before import; false positive → add a fingerprint line to the backend's
`.gitleaksignore` and merge that to `main` first. **Note:** fingerprints reference commit
SHAs, and subtree add **preserves** original SHAs for the imported commits, so fingerprints
computed against the source repos remain valid in the monorepo.

### 4.4 Dependency-review pre-check

Per V12, the migration PR presents every npm dependency (direct + transitive) to
`dependency-review-action` at `fail-on-severity: high` / `deny-licenses: GPL-3.0, AGPL-3.0`.
Pre-check both repos:

```bash
npm audit --omit=dev --audit-level=high        # in each of EDDI-Manager and eddi-chat-ui
npx license-checker-rseidelsohn --production --excludePrivatePackages \
  --failOn 'GPL-3.0;AGPL-3.0'                  # or equivalent license sweep
```

Resolve findings in the source repos first. Unlike gitleaks, this action's config lives in
the workflow file and is read from the PR's merge ref, so a config adjustment *can* ride in
the migration PR if a finding is genuinely unactionable — but treat that as last resort.

### 4.5 Baseline capture

```bash
git rev-parse HEAD                                        # backend baseline
git -C ../EDDI-Manager rev-parse origin/main              # manager baseline
git -C ../eddi-chat-ui rev-parse origin/main              # chat baseline
git ls-files src/main/resources/META-INF/resources | wc -l   # expect 583
```

Record the current hashed asset names from `manage.html` (e.g. `index-CeAE4N_O.js`) — after
migration the entry renames to `main-<hash>.js` (V3) and you want proof of the swap.

### 4.6 Confirm merge settings with whoever merges

Repo allows all three merge methods (V11). The migration PR must use **"Create a merge
commit."** Put this in the PR description in bold, first line.

---

## 5. Phase 1 — Bring the code in

Work on a branch off `origin/main`. **Each phase ends in a state that compiles and is
committed separately.**

```bash
git fetch origin main
git checkout -b chore/monorepo-migration origin/main
```

### 5.1 Add the subtrees

```bash
git remote add manager-origin https://github.com/labsai/EDDI-Manager.git
git remote add chat-origin    https://github.com/labsai/EDDI-Chat-UI.git
git fetch manager-origin main
git fetch chat-origin main

git subtree add --prefix=ui/manager manager-origin main
git subtree add --prefix=ui/chat    chat-origin    main

git remote remove manager-origin
git remote remove chat-origin
```

> Use `main` from the **remotes** — the local sibling checkouts sit on feature branches.

Expect pack growth ~173 → ~260 MiB. Accepted (§2).

### 5.2 Delete what does not survive the move

First, move the audit script that `package.json` references **before** deleting `.github`:

```bash
mkdir -p ui/manager/scripts
git mv ui/manager/.github/scripts/audit-prod.mjs   ui/manager/scripts/audit-prod.mjs
git mv ui/manager/.github/scripts/audit-prod.d.mts ui/manager/scripts/audit-prod.d.mts
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
git rm -r --cached --ignore-unmatch ui/manager/.claude   # local settings, never should have been tracked
```

In `ui/manager/package.json`:
- `"audit:prod"` → `node scripts/audit-prod.mjs`
- remove `"prepare": "husky"`
- remove the now-dead `"lint-staged"` config block

> **Accepted loss:** the Manager's pre-commit hook (lint-staged + tsc). CI runs both anyway.

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

### 6.2 Manager `vite.config.ts` — add the `build` block

The file already imports `fileURLToPath, URL` from `node:url`. Add alongside the existing
`server` block (everything else — the ~45 proxy entries, `define`, `resolve`, `optimizeDeps`,
`worker` — stays untouched):

```ts
  build: {
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
from `pom.xml` (§7.2 passes the env var):

```ts
  define: {
    __APP_VERSION__: JSON.stringify(process.env.EDDI_VERSION ?? pkg.version),
  },
```

**Verify with `npm run build` in `ui/manager` (expected per V2/V3/V4):**
- `dist/manage.html`, `dist/welcome.html`, `dist/workforce.html` exist; **no `dist/index.html`**
- all three reference the **same** `/assets/main-<hash>.js` and `/assets/main-<hash>.css`
- `__auth_config__.js` + `.app-loader` present in each
- `dist/` root additionally contains `eddi-icon.ico`, `eddi-icon.svg`, `logo_eddi.png`,
  `mockServiceWorker.js` (the `public/` copy — the worker is excluded later at §7.2)

### 6.3 Chat `vite.config.ts`

```ts
outDir: "dist",
emptyOutDir: true,   // was false to protect the old backend outDir (V9) — flip it
```

Leave `base`, the `chat.html` input, and the `chat-ui.[hash]` output naming untouched.
Verify `npm run build` emits exactly: `chat.html`, `scripts/js/chat-ui.<hash>.js`,
`scripts/css/chat-ui.<hash>.css`, `fonts/**`, `img/**` (V7).

### 6.4 Untrack generated output and single-source the statics

Every static file gets exactly one source of truth. Ownership after this step:

| File(s) | Single source | Rationale |
|---|---|---|
| `index.html` (redirect shell), `robots.txt`, `scripts/js/landing-redirect.js` | **backend** `src/main/resources` | Hand-written, no frontend build involved |
| `eddi-icon.ico`, `eddi-icon.svg`, `logo_eddi.png` | **`ui/manager/public/`** | Byte-identical today (V5); Manager dist provides them |
| `fonts/**`, `img/favicon.ico`, `img/logo_eddi.png` | **`ui/chat/public/`** | Chat dist provides them (V6) |
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

**Commit:** `chore(ui): build the frontends into dist/ instead of the backend source tree`

---

## 7. Phase 3 — Wire the UI into the Maven build

Without this, `mvnw package` produces a jar with a broken `/manage`. Mandatory.

### 7.1 Properties (existing `<properties>` block of `pom.xml`)

```xml
<frontend-maven-plugin.version>1.15.1</frontend-maven-plugin.version>
<maven-resources-plugin.version>3.3.1</maven-resources-plugin.version>
<node.version>v20.19.0</node.version>
<skipUi>false</skipUi>
```

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
    <version>${maven-resources-plugin.version}</version>
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

```bash
./mvnw clean package -DskipTests -DskipUi=true    # must succeed, run zero npm
```

Then a real container check: build the image, run it with MongoDB, and:

```bash
for p in /manage /welcome /workforce /chat; do curl -sf localhost:7070$p | grep -o 'assets/main-[^"]*\.js\|scripts/js/chat-ui[^"]*\.js'; done
curl -s -o /dev/null -w "%{http_code}" localhost:7070/mockServiceWorker.js   # expect 404
```

### 7.4 Developer-experience notes (document in AGENTS.md §7 update)

- Per V17, **every** `mvnw compile/test/verify/quarkus:dev` now runs the frontend build
  (~1–3 min) unless `-DskipUi=true`. Backend-only work: `./mvnw test -DskipUi=true`,
  `./mvnw quarkus:dev -DskipUi=true`.
- Quarkus live-reload never rebuilds the UI. Frontend dev continues exactly as today:
  `npm run dev` in `ui/manager` (port 3000, proxies to :7070).
- `.dockerignore` needs no change (deny-all + `target/quarkus-app/**` allowlist; the UI now
  travels inside the app jar).

**Commit:** `build(ui): build and bundle the Manager and Chat UIs via Maven`

---

## 8. Phase 4 — CI: the actual payoff

All edits in `.github/workflows/ci.yml` unless stated. This section is a **complete rewiring
spec** — the job splits change output plumbing that three downstream jobs depend on; follow
the reference tables exactly.

### 8.1 `detect-changes`

- Add to the paths-filter: `ui: ['ui/**']`; expose as output `ui`, with the same tag-push
  forcing as `code` (a tag must force `ui=true`).
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

In `ui/manager`: `npm ci` → `npm run audit:prod` → `npm run lint` → `npm run typecheck` →
`npx vitest run --coverage` → `npx playwright install --with-deps chromium` →
`npm run test:e2e` (MSW tier, ~184 tests, no backend) → upload `playwright-report/` +
`coverage/`. Then in `ui/chat`: `npm ci` → `npm run typecheck` → `npm test`.

Replaces the old Manager `ci-cd.yml` and the `ui-tests` job of its `e2e.yml` one-for-one.

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

`needs: build-image`; matrix `database: [mongodb, postgres]`, `fail-fast: false`; skip the
postgres leg on PRs:
`if: matrix.database == 'mongodb' || github.event_name != 'pull_request'`.

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

In **both** compose files, parameterize the image:

```yaml
services:
  eddi:
    image: ${EDDI_IMAGE:-labsai/eddi:latest}
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
```

| Downstream consumer | What it reads | Breaks without re-export |
|---|---|---|
| `release` | `needs.docker.outputs.primary-tag` | GitHub Release body wrong/empty |
| `smoke-test` | `needs.docker.outputs.primary-tag` | signature verify + run target empty |
| `preflight-push` | `needs.docker.outputs.primary-tag`, `is-release` | pulls empty tag |

`smoke-test`, `release`, `preflight-push` themselves need no other changes.

### 8.7 `preflight-check` (PR dry-run) — reuse the artifact

Change to `needs: build-image`; replace its JDK + `mvnw package` + `docker build` steps with
artifact download + `docker load` + retag to `eddi-preflight-check:test`. Saves a full
duplicate Maven+npm+Docker build per PR and preflights the actual artifact.

### 8.8 Concurrency and notifications

- Add a workflow-level concurrency group (the pipeline is now much heavier):

```yaml
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: ${{ github.event_name == 'pull_request' }}
```

  (Never cancel main/tag pipelines mid-publish.)
- `notify-slack`: add `ui-build-and-test`, `build-image`, `e2e-fullstack` to `needs:` and to
  the status-fields block, following the existing `status_icon` pattern.

**Commit:** `ci(monorepo): build once, E2E the built image on both DBs, then publish it`

---

## 9. Phase 5 — Repository housekeeping

### 9.1 `.github/dependabot.yml` — add npm ecosystems

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
2. The **third** surface is the GitHub-managed dynamic run (`Analyze (java-kotlin)`, V13)
   configured in **repo settings**, not in a file here. Enable `javascript-typescript` there
   too, or the newly in-repo TypeScript stays unscanned by it. This is a settings change and
   cannot be done in the PR — put it on the §10 checklist.

Own commit; independently revertible.

### 9.3 `CODEOWNERS`

Add `ui/manager/` and `ui/chat/` entries mirroring the backend rules.

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
> **Confirm empirically before adding the contexts:** once #670 has an approving review, check
> whether `mergeStateStatus` becomes `CLEAN`. If it does, skipped-as-satisfied is proven and
> the contexts are safe to add. If it stays `BLOCKED`, do **not** add path-gated jobs as
> required — instead add a tiny always-running job that depends on them and reports success
> when they are skipped, and require *that* instead.

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
- [ ] Migration PR was merged with a **merge commit**; `git log ui/manager` shows original
      Manager history with original SHAs
- [ ] Branch-protection contexts updated (§10)
- [ ] Old repos archived with pointer READMEs; `docs/changelog.md` records the migration

---

## 12. Risks and rollback

| Risk | Severity | Mitigation |
|---|---|---|
| Squash-merge of the migration PR destroys imported history | **High** | §2 decision + §4.6 + PR description warning |
| Gitleaks scans 1,079 imported commits; ignores only honored from base branch | **High** | §4.3 pre-scan; land `.gitleaksignore` on main **first** |
| dependency-review blocks the PR on npm CVEs/licenses | **High** | §4.4 pre-check in source repos |
| Trivy fs scan newly gates backend releases on npm CVEs | **High** | §4.2 scratch scan before merge |
| 30+ orphaned Manager branches / in-flight local work | **High** | §4.1 freeze + `git am --directory=` recipe |
| MSW service worker ships to production | Medium | §7.2 exclude + §7.3/§8.5/§11 404 checks |
| CI/dev builds slow down from unconditional npm | Medium | §8.2 skipUi wiring + §7.4 dev docs |
| Multi-page build regression blanks a shell | Medium | Dry-run verified (V2); §8.5 shipped-shell gate makes it permanent |
| `ui/node/` accidentally committed | Low | §6.4 gitignore (exact-path entry) |
| ~90 MiB pack growth | Low | Accepted; irreversible without forbidden history rewrite |
| Version drift `pom.xml` ↔ UI footer | Low | §7.2 `EDDI_VERSION` injection |

**Rollback:** every phase is its own commit; nothing force-pushed; `git revert` unwinds any
phase. Full retreat = revert the merge commit + un-archive the source repos. Do not delete
the source repos until the monorepo has been green for a full release cycle.

---

## 13. Explicitly out of scope (each is its own later change)

- Node 20 → 22; npm workspaces
- The 13 backend ITs with no PostgreSQL twin (`A2aEndpointIT`, `ComplexRulesAgentEngineIT`,
  `CreateApiAgentIT`, `GroupHitlIT`, `HitlPauseResumeIT`, `HitlToolPauseResumeIT`,
  `HttpCallsAgentEngineIT`, `ImportMergeIT`, `LlmAgentEngineIT`, `LogAdminIT`,
  `McpEndpointIT`, `OpenAiCompatIT`, `PropertySetterAgentEngineIT`)
- Authenticated E2E path (start from `ui/manager/docker-compose.keycloak.yml`; today every
  E2E runs `quarkus.oidc.tenant-enabled=false`)
- Re-adding DAST (standing note at `ci.yml` "Job 4b")
- Lint/vitest parity for `ui/chat`; code-splitting the 8.4 MB Manager bundle
- npm SBOM generation alongside the Maven CycloneDX one
