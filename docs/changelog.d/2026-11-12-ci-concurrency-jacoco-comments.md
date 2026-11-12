## CI: every main commit gets its own run; JaCoCo exclusion comments corrected (2026-11-12)

**Repo:** EDDI. **Branch:** `chore/ci-concurrency-jacoco-comments`.

**CI concurrency.** `.github/workflows/ci.yml` grouped runs by workflow and ref with `cancel-in-progress` true only for pull requests. For pushes to `main` the ref is the same for every commit, so GitHub kept one running and one pending run and replaced the pending run whenever a newer one queued. With several PRs merging in quick succession the intermediate commits were silently skipped: a commit that broke `main` never got its own result and its `<pom>-b<run_number>` image was never published. Non-PR events now add `github.sha` to the group key; PRs keep one group per ref with cancel-in-progress.

**Design decision, no publishing guard.** Overlapping `main` runs cannot race on a moving tag: a branch push publishes only the unique `<pom>-b<run_number>` tag, and `:latest` plus the major/minor aliases move only for stable release tags (`docker` job, "Push to Docker Hub"), whose ref already gave each tag its own group. A job-level `concurrency` group on `docker` was considered and rejected: without cancel it still replaces a pending job, which would re-introduce exactly the skipped publishes this change removes.

**JaCoCo comments.** The package exclusions in `pom.xml` (`**/bootstrap/**`, `**/runtime/client/**`, `**/integrations/slack/**`) carried comments that did not describe what the patterns exclude (the runtime client libraries are hand-written and unit-tested, not generated; the Slack package also holds logic-bearing classes). Comments now state what is excluded; which classes are excluded and every threshold are unchanged. All three patterns still match files. The `**/llm/impl/builder/**` exclusion this branch also re-commented was narrowed on `main` in the meantime (#1363) to the two builders that cannot be built offline; the merge keeps `main`'s narrowed exclusion and its comment.

**Files:** `.github/workflows/ci.yml`, `pom.xml`.
