## fix(engine): price the conversation without converting every step on the current-step path (2026-10-19)

**Repo:** EDDI · **Branch:** `fix/security-review-authz` (carried by #1081 to repair `main`)

### What changed and why

#1066 (group cost ledger) and #1093 (convert only the current step for a turn's response) merged
within minutes of each other, each green on its own. Together they broke `main`:
`ConversationMemoryUtilities.conversationCost` sums `audit:cost` over every step of the snapshot, and on
the current-step path that list is the lazy view #1093 introduced, so pricing the conversation converted
every step again. `ConversationMemoryCurrentStepConversionTest.convertsOnlyCurrentStep` failed, and the
default turn response lost the saving #1093 was for.

- `LazyStepSnapshots.conversationCost()` sums the live steps' `audit:cost` entries with one
  `IConversationStepStack.getExactDataPerStep` read. Nothing is converted. A step holds at most one value
  per key, which is exactly what the full conversion sums per step, with the same non-finite and
  negative filtering.
- `conversationCost(List)` uses it when it is handed the lazy view; every other caller is unchanged.
- New test: with costs on every step, the current-step path returns the same total as the full
  conversion and still reads only the newest step.
