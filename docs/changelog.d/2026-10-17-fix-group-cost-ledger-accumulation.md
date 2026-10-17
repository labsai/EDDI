## fix(groups): count every turn of a group member toward the group's cost ceiling (2026-10-17)

**Repo:** EDDI · **Branch:** `fix/group-cost-ledger-accumulation`

### What was wrong

`GroupCostLedger.accumulateMemberCost` fed a group's `totalCost`, which the
`maxCostPerDiscussion` ceiling (I1) is checked against, through two faults:

- **It read the wrong value.** It took `audit:cost` from the last step of the
  member turn's snapshot and recorded it by replacement, on the assumption that
  the value was the conversation's running total. It is not: `LlmTask`
  accumulates `audit:cost` on the *current step*, and every new step starts
  without one. A member that spoke in several turns had only its latest turn
  counted. The comment in `MemberTurnExecutor` and the ledger's own Javadoc
  repeated the wrong assumption, and the tests stubbed the same wrong model
  (a growing "cumulative" `audit:cost`), so they passed.
- **Since 2026-09-26 it read nothing at all.** Member turns call `say` with
  `returnDetailed=true`, and the redaction of that projection (`f94036125`)
  withholds every `audit:*` key from the step data. From then on the ledger
  found no cost, so member spend counted $0 toward the ceiling. Only the
  discussion's own system costs (summarizer, stance summaries) still counted.
  Every unit test mocked `say` with a hand-built snapshot, so none of them
  went through the redaction.

### What changed

- `SimpleConversationMemorySnapshot` gains `conversationCost`, the sum of
  `audit:cost` over **every** step of the conversation, including the steps
  `returnCurrentStepOnly` leaves out. It is `@JsonIgnore` and in-process only,
  so it does not widen what any REST, MCP or SSE caller receives. The redaction
  is unchanged. `ConversationMemoryUtilities.convertSimpleConversationMemory`
  fills it, skipping NaN, infinite and negative values. It is `null` when no
  step carried a cost.
- `GroupCostLedger.accumulateMemberCost` records that total. It no longer reads
  step data. Replacement is now correct, because the value really is the
  conversation's running total. That holds in every case:
  - harvesting the same turn twice;
  - a skipped turn, whose snapshot shows the previous turn;
  - a HITL pause, whose resumed spend arrives with the member's next turn.
- `GroupCostLedger.retireConversation`: when `MemberTurnExecutor` swaps a
  member's ended private conversation for a fresh one under the same key, it
  first moves the ended conversation's entry to `key:endedConversationId`. That
  is the `key:child` shape nested groups already use, and the Manager's
  per-member digest already sums it. Before, the fresh conversation's first
  total overwrote the member's whole earlier spend.

### Design decisions

- **Not widening the redaction.** Letting `audit:cost` through, the way
  `audit:token_usage` is, would also expose the operator's configured pricing
  to any chatting user who sets `returnDetailed=true`. An in-process field gives
  the group engine what it needs and exposes nothing.
- **A conversation total, not per-turn deltas.** The snapshot carries no step
  index, so adding per-turn deltas could not tell a repeated harvest or a
  skipped turn's stale step apart from new spend. A total recorded by
  replacement is idempotent by construction.

### Tests

- `GroupCostLedgerTest` builds its snapshots through the real projection, with
  the flags a member turn uses. It checks that a two-turn member is counted in
  full ($0.05 + $0.07 = $0.12), and that two $0.06 turns trip a $0.10 ceiling
  that neither turn trips alone.
- `ConversationMemoryUtilitiesTest`: the sum covers steps outside
  `returnCurrentStepOnly`, poisoned values are skipped, and the field never
  reaches JSON.
- `MemberTurnExecutorTest`: an ended conversation's spend survives its
  replacement.
- Mutation-checked. Each of these reverts fails the new tests:
  - restoring the last-step read;
  - summing only the projected steps;
  - dropping the `retireConversation` call.
