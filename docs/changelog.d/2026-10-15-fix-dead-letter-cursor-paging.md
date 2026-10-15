## fix(coordinator): a dead-letter cursor no longer skips a node-local entry recorded concurrently (2026-10-15)

**Repo:** EDDI · **Branch:** `fix/dead-letter-cursor-paging`

### What changed and why

- **The bug.** `AbstractQueuedConversationCoordinator.recordLocalDeadLetter` took the
  entry's id from the counter *before* entering `deadLetterLock`, but appended the entry
  *inside* it, after describing and classifying the failed turn. Two failures recorded at
  the same time could therefore land in the ring out of id order — `local-2` ahead of
  `local-1`. `ClusterConversationCoordinator.getDeadLetters(limit, after)` pages past a
  local cursor by skipping every entry whose number is at or below the cursor's (so a
  discarded or forwarded cursor still pages correctly), and with the ring in that order
  a page ending on `local-2` made the next page skip `local-1`: an operator paging the
  dead-letter list never saw it. CI hit it once as a flaky
  `ClusterConversationCoordinatorTest.localDeadLettersPage` (three failing turns on
  three conversations, recorded concurrently by the pool) on PR #1046; it passes
  locally because the interleaving is rare.
- **The fix.** The id is now taken under the same lock as the append, so ring order is
  always id order and the numeric skip is sound. The cursor semantics themselves are
  unchanged.
- **Regression test.** `ClusterConversationCoordinatorTest.concurrentLocalDeadLettersPageInIdOrder`
  holds one failure open inside `describe()` (between "starts recording" and "is in the
  ring"), records a second to completion, then pages one entry at a time. It is
  deterministic — latches, no timing — and mutation-checked: with the id taken outside
  the lock again it fails with the CI message (`expected: <1> but was: <0>`, ring
  `[local-2, local-1]`).

Single-node (`InMemoryConversationCoordinator`) ids share the same counter and get the
same ordering guarantee; nothing there pages by number, so it had no visible symptom.
