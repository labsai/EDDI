## CodeRabbit reviews every PR, including stacked ones (2026-10-04)

**Repo:** EDDI · **Branch:** `chore/coderabbit-config`

**What changed:** a repository `.coderabbit.yaml` now enables automatic reviews for pull requests into any base branch, including draft PRs and every later push. Before, CodeRabbit only reviewed PRs into `main`. Stacked PRs (a PR based on another feature branch, such as the NATS cluster parts 2 and 3) were skipped with "Auto reviews are disabled on base/target branches other than the default branch".

**Decisions:** the review profile stays `chill`, matching the setting reviews have used so far. Settings in this file take precedence over the CodeRabbit UI.
