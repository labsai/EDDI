## AGENTS.md: no merge without a completed CodeRabbit review (2026-11-14)

**Repo:** EDDI · **Branch:** `docs/agents-coderabbit-required`

**What changed:** `AGENTS.md` §2 rule 4 gains a merge gate. A PR may only be merged once CodeRabbit has reviewed its current head, every CodeRabbit finding (inline, nitpick and outside-diff) is fixed or answered, and every CodeRabbit thread is resolved. A "Review limit reached" / "Review rate limited" reply counts as not reviewed. A human approval, green CI and an agent's own review are required as well, but none of them replaces CodeRabbit. Only the user can waive the rule, for a named PR.

**Why:** on 2026-11-12 seventeen PRs (#1272 #1296 #1298 #1320 #1321 #1322 #1326 #1329 #1330 #1337 #1338 #1339 #1347 #1350 #1353 #1362 #1373) were squash-merged by an assistant session that treated a human approval plus its own review as sufficient, while CodeRabbit was rate limited on all of them. Those merges are being reviewed after the fact. Writing the rule into `AGENTS.md` makes it binding on every session and assistant, not only the one that was told.

**Design decisions:** the rule sits next to the push discipline and the `ship-pr` pointer, which is where every assistant reads before opening or merging a PR. It names the two CodeRabbit commands for re-requesting a review rather than restating the skill's full command table.
