## 🔧 chore(ci): gate docker on the auth tier, pin and verify Node, harden the changelog guard, lint ui/chat (2026-09-26)

**Repo:** EDDI (`chore/ci-hardening`)

A batch of CI and build-tooling fixes from the 2026-09-25 whole-repo review (fix-plan item 17). No runtime code changed; nothing here touches branch protection or secrets.

### What changed and why

- **B2: docker waited on neither the auth tier nor the E2E gate.** `docker` publishes, tags, signs and attests the image, but its `needs` listed `e2e-fullstack` and not `e2e-auth`. A push to main whose only failure was the Keycloak tier still shipped. It now needs `e2e-auth` and `e2e-gate`. `BuildQualityGatesTest.dockerNeedsEveryE2eJob` checks that every job the gate grades, plus the gate itself, is in docker's `needs`.
- **B3: fragments passed the Java test but failed the collator.** The Changelog Discipline job now runs `python3 scripts/collate-changelog.py --check` on every PR. `ChangelogFragmentTest` also mirrors the three collator refusals it missed:
  - text above the first heading
  - a fence that never closes
  - a register block spelt almost right (`decision_log`, `Regression-Notes`)

  These are tested with synthetic fragments in `collationProblemsMirrorTheCollator`.
- **B6: the in-place guard could be evaded.** It counted only dated `## … (YYYY-MM-DD` headings, so an undated heading or a setext (`---`-underlined) heading went through. The whole job was also skipped for any head branch *named* `chore/collate-changelog`, a fork's included.
  - The guard now counts every `## ` heading, which is what `split_sections` treats as an entry, plus setext heading pairs. Added and removed counts still net against each other, so edits and rotations pass.
  - The job runs on every PR. Only a same-repository `chore/collate-changelog` branch skips the guard, and that branch gets its own step that fails if it changes anything besides `docs/changelog.md`, `docs/changelog.d/*`, `docs/changelog/*.md` and `docs/SUMMARY.md`.
  - `ChangelogDisciplineJobTest` pulls both steps' shell out of `ci.yml` and runs it under bash, with a stub `git` that prints canned diffs. It tests the workflow's actual code, not a copy.
- **B5: Node was floating and never verified.** Every `setup-node` said `22`, while `pom.xml` vendors `v22.23.2` for the shipped UI build. frontend-maven-plugin 1.15.1 downloads Node without any checksum and reuses whatever archive sits in the local Maven repository, which setup-java restores from cache.
  - A workflow-level `NODE_VERSION` now feeds every `setup-node`.
  - Build Image places the archive at the path the plugin's `RepositoryCacheResolver` resolves (`~/.m2/repository/com/github/eirslett/node/<v>/node-<v>-linux-x64.tar.gz`) and verifies it against `NODE_SHA256_LINUX_X64`, taken from nodejs.org's `SHASUMS256.txt`. A cached copy that doesn't match is fetched again, and the fresh download must match too.
  - Verified locally: the plugin unpacked the pre-seeded archive and downloaded nothing.
  - `BuildQualityGatesTest.nodeVersionIsPinnedOnceAndVerified` keeps ci.yml, `pom.xml` and `mise.toml` in step.
  - A local `./mvnw package` still trusts nodejs.org over TLS, as documented in `pom.xml`.
- **B4: `.gitignore` re-included nested `.claude/` directories.** `!.claude/` was unanchored, while `.claude/*` is root-only, so `ui/chat/.claude/settings.local.json` was not ignored. The three root rules are now anchored with `/`. `nestedClaudeDirectoriesStayIgnored` asks `git check-ignore` directly. `.gitignore` joins the `backend` path filter so a change to it runs that test.
- **B7: vitest security updates were not grouped.** A group without `applies-to` covers version updates only. Both UIs now have a `vitest-security` group (`applies-to: security-updates`, `vitest` + `@vitest/*`) declared ahead of the catch-all. This has a limit: Dependabot security updates touch only packages that carry an advisory, so a fix for `vitest` alone can still leave `@vitest/*` behind. The comment in `dependabot.yml` says so and says what to do then.
- **ui/chat had no ESLint.** Added `ui/chat/eslint.config.js`, using the Manager's base rules and its no-tautology test rule, plus an `npm run lint` script and a `Lint` step in the `UI Chat` job. The six findings it raised are fixed:
  - a literal zero-width space, now written `​`
  - one `any`, which gets the same disable comment as its neighbour
  - three `useCallback` dependency arrays that listed `environment`/`agentId`, which those callbacks never read
- **NUL bytes in Manager sources.** `ui/manager/src/lib/api/groups.ts:1622` held a literal U+0000 in a string, and so did `ui/manager/scripts/audit-prod.mjs:99` (found by the new guard). Both are now written `\u0000`, so the runtime value is identical. `src/__tests__/source-control-chars.test.ts` fails on any raw control character under `src/`, `e2e/` and `scripts/`.
- **L7: README dev commands lacked the Jlama flag.** Every README `quarkus:dev` command now passes `-Djvm.args=--add-modules=jdk.incubator.vector`, as `mise run dev` does, with a note that only `jlama` agents need it. `JlamaRuntimeFlagsTest.readmeDevCommandsPassTheFlag` checks this.
- **A5: realm test gaps in `DeploymentManifestsTest`.**
  - A client that omitted `standardFlowEnabled` was treated as unable to log in. Keycloak defaults that flow on, and bearer-only clients are now the explicit exemption.
  - The loopback-redirect check was a prefix test that accepted `http://localhost.attacker.com`. It now requires a loopback host.
  - Both checks get negative tests.
- **Slack notification is best effort.** `curl -sf` failed the job with exit 22 whenever the webhook refused the post, so every failing run showed a second red job. A delivery failure is now a warning in the run summary. Failing PRs from this repository still notify: the job's documented policy ("only failures and releases notify") covers them deliberately, so PR notifications were kept rather than skipped. An empty secret was already skipped.

### Decisions

- The changelog guard stayed inline bash in `ci.yml`, not a separate script. It is tested by executing the workflow's own step, so there is still one copy.
- The frontend-maven-plugin upgrade (2.x) was not taken. Pre-seeding a verified archive closes the gap for the build that ships without changing the plugin.

**Files:** [`ci.yml`](../../.github/workflows/ci.yml), [`dependabot.yml`](../../.github/dependabot.yml), [`BuildQualityGatesTest.java`](../../src/test/java/ai/labs/eddi/BuildQualityGatesTest.java), [`ChangelogDisciplineJobTest.java`](../../src/test/java/ai/labs/eddi/docs/ChangelogDisciplineJobTest.java), [`ChangelogFragmentTest.java`](../../src/test/java/ai/labs/eddi/docs/ChangelogFragmentTest.java), [`DeploymentManifestsTest.java`](../../src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java), [`JlamaRuntimeFlagsTest.java`](../../src/test/java/ai/labs/eddi/JlamaRuntimeFlagsTest.java), [`ui/chat/eslint.config.js`](../../ui/chat/eslint.config.js), [`groups.ts`](../../ui/manager/src/lib/api/groups.ts)
