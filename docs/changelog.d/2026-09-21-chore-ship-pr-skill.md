## 🛠️ chore(skills): a `ship-pr` skill that carries a PR from push to every review comment resolved (2026-09-21)

**Repo:** EDDI (`chore/ship-pr-skill`)

### Why

Shipping a PR here has a long tail of repo-specific traps, and every session rediscovers them.
The two that cost the most: the `CodeRabbit` check reads **`pass` while the description column
says `Review rate limited`** — 12 of 13 recent PRs — so "green checks, zero unresolved threads"
can mean *nothing was reviewed*; and a targeted `-Dtest='A,B'` **silently drops a missing class
and exits 0**, because surefire's `failIfNoSpecifiedTests` only trips when zero tests ran.

### What changed

- `.claude/skills/ship-pr/SKILL.md` — the workflow: pre-push gates, a PR-description shape built
  from the diff, what actually gates a merge (branch protection requires only `Build & Test` and
  `CodeQL Analysis`; everything else is advisory), and a review loop that covers all three places
  feedback lands.
- `.claude/skills/ship-pr/pr-threads.sh` — audits review threads via GraphQL, prints the node id
  the reply/resolve mutations take, and drops CodeRabbit's collapsed `<details>` block so the
  preview shows the finding rather than `🏁 Script executed:`.
- `.gitignore` — un-ignore `.claude/skills/` only; `settings.local.json`, `workflows/` and
  `worktrees/` stay ignored. Without this the skill files never land.

### Decisions

- **Tracked, not personal.** The content is repo-specific team knowledge, mirroring the precedent
  in `ui/manager/.claude/skills/`.
- **Nitpicks are handled separately from threads.** CodeRabbit puts nitpicks, duplicates and
  outside-diff-range findings in the *review body*, where they have no thread and cannot be
  resolved — and some are 🟠 Major. They get one disposition comment instead.
- **Only bot threads get resolved.** A human closes their own, whether the finding was fixed or
  argued down.

Sibling skills for `gnowbe-frontend` and `gnowbe-api-2` ship in those repos.
