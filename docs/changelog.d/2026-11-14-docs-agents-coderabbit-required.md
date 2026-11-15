## AGENTS.md: no merge without a completed CodeRabbit review (2026-11-14)

**Repo:** EDDI · **Branch:** `docs/agents-coderabbit-required`

**What changed:** `AGENTS.md` §2 rule 4 gains a merge gate. A PR may only be merged once CodeRabbit has reviewed its current head, every CodeRabbit finding (inline, nitpick and outside-diff) is fixed or answered, and every CodeRabbit thread is resolved. A "Review limit reached" / "Review rate limited" reply counts as not reviewed. A human approval, green CI and an agent's own review are required as well, but none of them replaces CodeRabbit. Only the user can waive the rule, for a named PR.

**Why:** on 2026-11-12 seventeen PRs (#1272 #1296 #1298 #1320 #1321 #1322 #1326 #1329 #1330 #1337 #1338 #1339 #1347 #1350 #1353 #1362 #1373) were squash-merged by an assistant session that treated a human approval plus its own review as sufficient, while CodeRabbit was rate limited on all of them. Those merges are being reviewed after the fact. Writing the rule into `AGENTS.md` makes it binding on every session and assistant, not only the one that was told.

**Design decisions:** the rule sits next to the push discipline and the `ship-pr` pointer, which is where every assistant reads before opening or merging a PR. It names the two CodeRabbit commands for re-requesting a review rather than restating the skill's full command table.

## AGENTS.md: never merge into a red main, never merge in bulk (2026-11-14)

**Repo:** EDDI · **Branch:** `docs/agents-coderabbit-required`

**What changed:** a second merge gate in `AGENTS.md` §2 rule 4:
- Check `main`'s latest `CI/CD` run before every merge, and merge nothing while it is red.
- Merge through the merge queue once `main` has one.
- Without a queue, merge one PR at a time and re-test a PR after `main` changed under it, or test-compile the whole set together before landing several.
- Watch `main`'s run after each merge and revert or fix a red one first.

**Why:** "Require branches to be up to date" is off on `main`, so a PR's green CI only covers the `main` it was tested on. On 2026-11-12, #1352 renamed `ResourceAccessGuard.callerIsAdmin()` to `isAdmin()` and #1345 added a test calling `callerIsAdmin()`. Both were green and were merged minutes apart, and `main` stopped compiling its tests (fixed by #1407). Seventeen more PRs were merged while `main` was red.

**Design decisions:** the rule points at a merge queue as the real fix: a separate PR adds the `merge_group` trigger to `ci.yml`, and an admin enables the queue. It does not ask for "require branches to be up to date", which would make every open PR re-run CI after each merge, too slow for this repository's CI capacity.
