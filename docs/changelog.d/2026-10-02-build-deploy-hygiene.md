## 🧹 chore(build,deploy): auto-approve gaps, pinned Keycloak/MongoDB, no surge pod, installer refs, repo hygiene (2026-10-02)

**Repo:** EDDI (`chore/build-deploy-hygiene`)

Fixes the Build/CI, Deploy and Hygiene findings of the 2026-10-02 review (§4.11).

### Auto-approve (`.github/workflows/auto-approve-copilot.yml`)

- **A Copilot review with findings only in its body counted as clean.** `REQUIRE_CLEAN_COPILOT`
  counted inline comments, but Copilot folds low-confidence findings into a collapsed section of
  the review body (`Suppressed comments (2)`), where no inline comment and no thread exists — PR
  #672 got three such reviews, each of which this job would have approved. The body now has to say
  in Copilot's own words that nothing was found — legacy format: "generated no (new) comments";
  the overview-v2 format Copilot has used since 2026-09: "Approval recommended" with
  "**Findings:** None" — and carry no collapsed section with a non-zero count (suppressed
  comments, v2's `Open (n)` list of unresolved findings) and no could-not-review notice. An empty
  body or an unknown format fails closed: it costs a human review, never a silent approval. The
  rule was checked against every Copilot review body on the last 120 PRs plus a sample of older
  ones (one clean v2 review, #866, approves; every other is refused).
- **The never-auto-approve list covered `.github/**` only.** It is now a glob list
  (`PROTECTED_GLOBS`): scripts, every `pom.xml`, the Maven wrapper, Checkstyle and formatter config,
  every Dockerfile, `mcp-sidecar/`, ClusterFuzzLite, Helm, Kustomize, `gcp/`, the Keycloak realm,
  every `docker-compose*.yml`, both installers, `mise.toml`, `.gitleaksignore`, `.trivyignore`, and
  the UI `package*.json`, `.npmrc` and `scripts/`, and the configs the UI checks run against
  (`vite`, `vitest`, `playwright`, `eslint`, `stryker`, `tsconfig*`) and every `.dockerignore`.
  Renames are checked on both paths, as before.
- [`AutoApproveCopilotPolicyTest`](../../src/test/java/ai/labs/eddi/deploy/AutoApproveCopilotPolicyTest.java)
  lifts both policy blocks out of the workflow and runs them under `node`.

### Third-party images

- **Keycloak `26.0` → `26.8.0`** in `docker-compose.auth.yml`, the Helm chart, `k8s/overlays/auth`
  and the Manager's two Keycloak compose stacks (which ran a floating `26.7`, so the auth E2E tier
  tested a different IdP from the one users ran). Verified live: the realm imports, the login theme
  renders, a token is issued and EDDI accepts it, the `eddi-mcp` PKCE/redirect rules hold.
- **MongoDB: one pin, `mongo:7.0.43`,** everywhere: compose (was `7.0.14`), CI and `ContainerBaseIT`
  (`7.0.14`), Helm and Kustomize (a floating `7.0`), the Manager's compose stacks (end-of-life `6.0`).
  `7.0.14` is also inside the CVE-2025-14847 range, fixed in `7.0.28`.
- [`ThirdPartyImagePinsTest`](../../src/test/java/ai/labs/eddi/deploy/ThirdPartyImagePinsTest.java)
  requires one full `MAJOR.MINOR.PATCH` version of each across compose, `ui/*` compose, Kustomize,
  Helm values and (for MongoDB) the workflows.

### No surge pod during upgrades

The EDDI Deployment rolled with `maxSurge: 1`, so every upgrade ran **two EDDI JVMs against one
database** for the length of the rollout, with every node-local mechanism (turn lock, replay nonces,
rate limits, caches, HITL crash recovery) diverging between them. The Helm chart gains
`eddi.updateStrategy` (default `Recreate`; `RollingUpdate` documented as an opt-in in
[`kubernetes.md`](../kubernetes.md#upgrades-replace-the-pod-no-surge)), and the Kustomize base and
`quickstart.yaml` use `Recreate`. Chart version **2.4.0**.

### Installers

- **`latest` fetched the compose files from `main`.** `install.sh` and `install.ps1` now resolve
  `EDDI_VERSION=latest` to the newest release tag through the GitHub releases API and download the
  files from that tag; when the API cannot be reached they fall back to `main` and say so. The reason
  is recorded as `EDDI_BRANCH_SOURCE` in `.eddi-config`, and `eddi update` (both the bash and the
  `eddi.cmd` wrapper) looks `latest` up again, so an update never pulls a new image with old files.
  `install.ps1` now honours `EDDI_VERSION` too, and writes it to `.env` like `install.sh`.
- **The "prevent path traversal" check did not.** It was a character class that admits `.` and `/`,
  so `EDDI_BRANCH=../../someone/else/main` passed and curl, which resolves dot segments, fetched
  another repository's files. Both installers and both wrappers now also refuse `..`, `//`, a leading
  or trailing `/`, a component starting with `.`, a trailing `.` and `.lock` — none of which git
  allows in a ref name.
- New `install.sh --dry-run` / `install.ps1 -DryRun` print the image tag, the ref and why, and exit.
- The `eddi update` wrapper writes `.env`/`.eddi-config` without `sed -i`, whose syntax differs on
  macOS.
- **`eddi.cmd` checked the ref through a pipe, which cmd.exe re-parses.** Each side of a pipe runs
  in a child `cmd.exe` that parses the expanded value again, so `EDDI_BRANCH=x&<command>&rem
  /../../../other/repo/main` ran `<command>` and was then checked as the harmless `x` — and the
  traversal behind it was downloaded. The check now writes the value to a temp file and runs
  `findstr` on the file, and the release lookup keeps only a `MAJOR.MINOR.PATCH` tag inside
  PowerShell, so no API text reaches a cmd.exe parser.
- An install from before `EDDI_BRANCH_SOURCE` existed, on a ref set by hand, keeps that ref on
  `eddi update` (bash and `eddi.cmd`) instead of being moved to the newest release: the old
  installers only ever chose `main` or the version tag themselves.
- `install.sh` validates `EDDI_VERSION` against Docker's tag grammar, as `install.ps1` and
  `eddi update` already did; `install.ps1`'s ref, tag and version checks anchor with `\z`, since
  .NET's `$` also matches before a trailing newline.
- Helm: `strategy` falls back to `Recreate` when `eddi.updateStrategy` is absent — `helm upgrade
  --reuse-values` from chart 2.3.0 carries no such value, and the template rendered
  `strategy: null`, which Kubernetes reads as a 25% `RollingUpdate`.
- [Upgrading from 6.4](../upgrading-from-6.4.md) now says how to keep the 6.4 and 6.5 pods from
  overlapping with the manifests 6.5.0 shipped (scale to zero first), and lists the three changes
  after 6.5.0 an operator sees: `Recreate`, Keycloak 26.8.0 (forward-only database migration; the
  *Full scope allowed* deprecation warning on `eddi-frontend`) and MongoDB 7.0.43.
- [`InstallerReleaseRefTest`](../../src/test/java/ai/labs/eddi/deploy/InstallerReleaseRefTest.java)
  runs `install.sh --dry-run`, the installed `eddi update` and `install.ps1 -DryRun` against a
  stand-in `curl`.

### Stargazer roster (`docker-pull-notify.yml`)

The metrics job stored every stargazer's login and star time on the `metrics` branch daily, so its
history recorded who unstarred and when, kept forever. Collection has stopped (data minimisation —
the aggregate star count answers the project-health question); the step removes
`data/stargazers.csv` from the branch tip and the branch README gives the `git filter-repo` recipe
to purge the history. Nothing else read the file; `data/metrics.csv` and the digests are unchanged.

### Repo hygiene

- Removed the AI-tool leftovers `ui/chat/docs/superpowers/**` and `ui/manager/docs/superpowers/**`
  (design specs and a task plan for features that shipped). The one with open items, the Chat UI
  follow-ups, moved to [`planning/chat-ui-followups.md`](../../planning/chat-ui-followups.md).
- Removed the Medium articles `planning/eddi-6.1-medium-article.md` and `eddi-6.2-medium-article.md`
  (published elsewhere; nothing linked them).
- Plan status headers that contradicted what shipped now say so: `hitl-framework-plan.md`
  (implemented; added to the shipped-plans guard), `multi-tenancy-plan.md`, `saas-connectors-plan.md`,
  `rag-ingestion-source-types-plan.md`, `multi-agent-ux-improvements.md`,
  `documentation-updates-plan.md`, `agentic-improvements-plan.md`, `manager-ui-handoff.md`.
- The test count was "about 18,000" in AGENTS.md, "21,000+" on the README badge and "~19,800" in
  `ci.yml`. Measured: 20,241 `@Test`/`@ParameterizedTest` methods in `src/test/java`. All three now
  say "20,000+", and AGENTS.md gives the command that counts them.
- `ui/manager/HANDOFF.md` is kept: it is the Manager's documented pre-monorepo history log, marked
  "no longer appended to" and pointed at from `ui/manager/AGENTS.md`.

```decision-log
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
```
