## test(coordinator): deterministic regression test for local dead-letter cursor paging (2026-10-15)

**Repo:** EDDI · **Branch:** `fix/dead-letter-cursor-paging`

### What changed and why

- **Background.** `ClusterConversationCoordinatorTest.localDeadLettersPage` failed CI
  twice on #1046 (`only the entry after the cursor: [] ==> expected: <1> but was: <0>`).
  The cause was in production: `recordLocalDeadLetter` took the local id before entering
  `deadLetterLock` but appended inside it, so two concurrent failures could land in the
  ring out of id order (`local-2` ahead of `local-1`). The cluster cursor skips local
  entries by number, so a page ending on `local-2` silently dropped `local-1` from the
  next page. The fix — taking the id under the lock — landed on `main` in #1040
  (`fix(coordinator): record local dead letters in id order`) while this branch was
  open; this branch carried the same fix and now contributes only the test.
- **Why another test.** #1040's `InMemoryConversationCoordinatorTest.concurrentDeadLettersAreRecordedInIdOrder`
  races eight writers and checks ring order, so it catches a regression only when the
  scheduler happens to interleave them, and it never pages.
  `ClusterConversationCoordinatorTest.concurrentLocalDeadLettersPageInIdOrder` forces the
  interleaving with latches — one failure is held inside `describe()` while a second one
  is recorded to completion — and then pages one entry at a time through
  `getDeadLetters(limit, after)`, the path operators use. Mutation-checked: with the id
  taken outside the lock it fails every run with the CI message (ring
  `[local-2, local-1]`).
