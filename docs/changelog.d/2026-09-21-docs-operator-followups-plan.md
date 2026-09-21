## 📝 docs(planning): mark the operator follow-ups plan as a completed record (2026-09-21)

**Repo:** EDDI (`docs/operator-followups-plan`)

### What changed and why

Review follow-up on [#686](https://github.com/labsai/EDDI/pull/686). The document was written as a
hand-off for three unimplemented tasks; all three have since landed, so a cold reader was being told
to do work that already exists.

- **Status block rewritten.** It said "all three are unimplemented". A landed in `c031cc8c95`
  (`ui/manager/src/lib/operator/tool-scopes.ts` now carries the test-drive endpoints), B in
  `5550010a3d` (`use-operator-chat.ts` `hydrate()` plus `components/operator/operator-history.tsx`),
  and C in `08e76415cc` (`HttpCallToolsProvider` refuses a non-JSON `requestBody` before sending).
  The estimates are kept so they can be judged against what landed, and the block now says to read
  the body's "today" / "what exists today" as the state at the time of writing.
- **Task C's summary row contradicted its own section.** The table said the body "fails at the API,
  not before" — the inverse of §C's title and of C.3, which both require refusing the call *before*
  it is sent. Corrected.
- **The B test list overclaimed what a snapshot carries.**
  `SimpleConversationMemorySnapshot` has `conversationState`, `hitlPausedAt`, `hitlPauseType` and
  `hitlPendingToolCalls` — but no pause *reason*. The bullet now says the reason comes from
  `useApprovalStatus`, which the restored `isPaused` is what enables, matching §B.3.3.

**Files:** [`planning/operator-followups-plan.md`](../../planning/operator-followups-plan.md)
