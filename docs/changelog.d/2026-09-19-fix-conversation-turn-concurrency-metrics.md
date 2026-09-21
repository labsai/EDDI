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

### Review follow-ups (2026-09-21)

- **The lifecycle panel still said "All six".** `id: 9` has carried a seventh target since the
  store-conflict counter landed. The description is now "Every `eddi_conversation_*_count_total`
  counter" rather than a number that goes stale the next time one is added.
- **The probe-close assertion could not see half of what it claimed.**
  `stubConversationExists` returned only the `ResultSet`, so its own comment — "the test verifies
  the store closes both" — was not true of the `PreparedStatement`. It now returns a
  `ProbeResources` record and both tests verify `close()` on each. `ResultSet.close()` is not
  specified to close the statement that produced it, so the missing half is the one that would
  leak a server-side portal per refused write.
- **A dead full-document serialization ran on every store.**
  `storeConversationMemorySnapshot` opened with `jsonSerialization.serialize(snapshot)` whose
  result no branch used: the append path returns before reaching it, the full-replace path
  overwrites it after stamping the new revision, and the insert path serializes again once the id
  exists. On the append path that is exactly the cost the append path exists to avoid — one full
  serialization of the whole conversation, every turn, thrown away. Removed;
  `storeSnapshot_pureAppend_serializesTheSnapshotOnceForTheBody` pins it at the one call
  `appendConversationSteps` legitimately makes for the body.

The `IN_PROGRESS` entry in `NON_APPENDABLE_STATES` was also queried in review and is left as is:
`Conversation.runStep` sets that state on the in-memory snapshot only, and the say path persists
once from `ConversationStepRunner.onComplete`, after `runStep`'s `finally` has restored `READY`. A
persisted `IN_PROGRESS` means a resume is running — which is the lost update the filter is there
to refuse.

