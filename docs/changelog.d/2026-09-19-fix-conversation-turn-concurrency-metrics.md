## 📈 docs(metrics): chart and document `eddi_conversation_store_conflict_count` (2026-09-19)

**Repo:** EDDI (`fix/conversation-turn-concurrency`, follow-up in PR #791)

The revision-guard commit registered `eddi_conversation_store_conflict_count` but did not add it to
`docs/metrics.md` or `docs/monitoring/eddi-full-metrics-dashboard.json`, so `MetricsDashboardCoverageTest`
failed in CI (`everyRegisteredMeterIsCharted` and `everyRegisteredMeterIsDocumented`). The counter now sits
beside undo/redo in the conversation-operations panel (legend "store conflict") and in the Conversation
Metrics reference. A non-zero rate means turn, undo/redo or resume writes that were refused because another
writer changed the conversation first. Before the revision guard, those were silent data loss.

Review follow-up in the same PR: `PostgresConversationMemoryStoreUnitTest` now stubs the existence probe
with its own statement and result set, and verifies the store closes the probe's result set. Before, it
reused the shared update mocks, which a code-quality bot reported as a possible `ResultSet` leak.
