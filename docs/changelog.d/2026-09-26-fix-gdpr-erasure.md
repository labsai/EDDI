## 🐛 fix(gdpr): erasure reaches connection grants and in-flight work; `_gdpr_` keys are reserved (2026-09-26)

**Repo:** EDDI (`fix/gdpr-erasure`)

Three gaps in the GDPR framework, from the 2026-09-25 code review (H9a, H9b, H9c).

### H9a: connection grants were outside erasure and export

OAuth connection grants hold a live refresh token for the user's account at a third
party. `deleteUserData` never touched them, so an erased identity kept a working
credential, and `exportUserData` did not list them.

- `IConnectionGrantStore` gains `findAllByPrincipal` and `deleteAllByPrincipal`, which
  span every tenant, like the rest of the cascade. They are implemented in Mongo (plus
  a new `idx_grant_principal` index) and Postgres (plus a new `idx_cg_principal`).
- The cascade adds a `connectionGrants` step, and `GdprDeletionResult` adds
  `connectionGrantsDeleted`, which also appears in the MCP `delete_user_data` payload.
- The export adds `connectionGrants`, the linked accounts as metadata only (tenant,
  connection, status, scopes, dates). Fields are copied one by one, so no token
  ciphertext or IV can reach the bundle.
- Not done: EDDI does not revoke consent at the provider. The docs say the user can
  revoke it there.

### H9b: in-flight work rewrote erased data

A turn running during an erasure wrote its longTerm properties back at teardown.
`UserMemoryTool` writes mid-turn. A running group discussion upserted its document on
the next phase, so the transcript came back.

- New SPI `UserErasureParticipant`, found through CDI like
  `SealedDataRotationParticipant`. The cascade calls it first, as step 0.
  `ConversationService` cancels this node's in-flight turns for the user, using the
  cooperative cancel flag. That flag already skips the longTerm write-back and discards
  the snapshot. `GroupConversationService` sends `CANCEL_IMMEDIATE` to the user's running
  discussions. If a participant throws, the step is recorded as failed
  (`inFlightConversations` / `runningGroupDiscussions`).
- `UserMemoryTool` refuses writes once its turn is cancelled
  (`ContextualToolsProvider` passes `memory::isCancelled`).
- `GroupConversationStore.update` no longer creates the document. It replaces an
  existing row via `storeIfCurrentVersion(SINGLE_VERSION)` and throws
  `GroupConversationGoneException` when the row is gone. This covers discussions on
  other replicas, and the plain delete endpoint as well as erasure.
- The cascade re-sweeps user memories at the end (step `userMemoriesResweep`). A step-0
  signal cannot stop a write that is already in progress, or one made by a turn on
  another replica.
- Residual: a turn on another replica that finishes after the re-sweep can still
  upsert its longTerm properties. Conversation snapshots already refuse to recreate a
  deleted document. Closing this fully needs a cross-node erasure tombstone.

### H9c: the Art. 18 flag could be forged, overwritten or deleted

`isProcessingRestricted` read the first row under `_gdpr_processing_restricted`,
whatever its category. So a model calling `rememberFact` could lock its own user out
with a GDPR 403. A global `rememberFact` or a REST `mergeProperties` overwrote the admin's
row in place, because global rows are keyed on `(userId, key)`. REST/MCP entry deletes,
REST `deleteProperties`/delete-all, and whole-set Dream pruning could all lift a
restriction without the audited endpoint. `unrestrictProcessing` removed only one row.

- `IUserMemoryStore.upsert` and `mergeProperties` refuse reserved keys in both backends,
  with `ReservedMemoryKeyException` (an `IllegalArgumentException`). The only write path
  for them is the new `upsertReserved`, used by `GdprComplianceService`.
- `isProcessingRestricted` counts only rows in the `gdpr` category, and considers every
  one of them. `unrestrictProcessing` deletes every row under the key.
- `UserMemoryTool` (`rememberFact`/`forgetFact`), `RestUserMemoryStore` (upsert/delete →
  400), `RestPropertiesStore.mergeProperties` (400) and `McpMemoryTools` refuse
  reserved keys. The delete-all surfaces now use `deleteAllExceptReserved`, and the
  store's `deleteProperties` keeps reserved rows. The MCP `delete_all_user_memories`
  description now says so. Dream never maintains reserved keys. A reserved longTerm
  property is skipped at teardown with a WARN rather than failing the turn. The legacy
  properties migration skips reserved keys instead of counting them as failures.
- Postgres retention and `deleteProperties` now escape the LIKE underscores. The old
  `'_gdpr_%'` also matched keys such as `agdpr1`, and the retention sweep never pruned
  those.
- Save-time rejection of a property-setter config that names a `_gdpr_` key was not
  added. It is skipped at runtime.

### Pre-push review follow-up

- **Deleted mid-run.** A running discussion whose document is deleted mid-run now ends
  as a cancel. `executeDiscussion` handles `GroupConversationGoneException`, also when
  it arrives wrapped, from any write inside the leg. The R2 cancel branch tolerates a
  document that is already gone. The listener gets `onCancelled` rather than
  `onGroupError`, and the leg no longer logs an ERROR, bumps the failure metric or
  throws a 5xx. Previously the second `update()` inside the catch threw Gone again and
  left SSE streams hanging. `GroupLifecycleOps.failConversation` has a corrected
  comment.
- **Late audit entries.** A cancelled turn still flushes its audit buffer while it
  unwinds. `AuditLedgerService.markUserErased` makes this node write the user's
  pseudonym for one hour, both at submit and when queued entries are drained. The
  v3 HMAC still verifies, because it covers the identity token, not the raw id.
- **Pending OAuth flows.** `IOAuthStateStore.deleteByPrincipal` (Mongo, Postgres)
  runs as step `oauthStates`, before the grants are deleted. A late callback can no
  longer mint a grant for an erased principal.
- **Signalling continues past errors.** `GroupConversationService.stopInFlightWork`
  keeps signalling after a store read error and reports the failure once, at the end.
- **Stricter restriction check.** A row counts as an Art. 18 restriction only if it
  also has no `sourceAgentId`, which is the shape only `restrictProcessing` writes.
- **`__service__` is refused.** Erasure and export reject this system principal:
  400 on REST, an error on MCP, and an `IllegalArgumentException` in the service.
- **LIKE escape character.** The Postgres LIKE escape is now `!`, which does not
  depend on `standard_conforming_strings`.
- **Access check before key check.** REST `upsertMemory` runs the ownership check
  before the reserved-key 400.
- **Docs.** Stale "GDPR delete-all" wording in `IRestUserMemoryStore` (OpenAPI),
  `docs/user-memory.md` and `docs/mcp-server.md` is fixed, and the step numbering in
  `docs/gdpr-compliance.md` is corrected.
- **Remaining residuals.** A turn or callback still in flight on another replica is
  not covered. Neither is a callback that claimed its state before step `oauthStates`
  and stores its grant after step `connectionGrants`, a window the length of one token
  exchange.

### Compatibility

- REST/MCP shapes only gain fields: `connectionGrantsDeleted`, and `connectionGrants`
  in the export. The old constructors of both records remain.
- Writes of `_gdpr_*` keys through the memory and property REST/MCP endpoints now
  answer 400 or an error, where they used to succeed.
- Stored rows need no migration. Pre-existing forged rows outside the `gdpr` category
  are ignored by the check and removed by unrestrict.

### Overlap

H14b on `fix/group-conversation-state` also changes group-discussion delete and
resurrection. This branch makes only the store-level non-upsert change and the erasure
cancel, and leaves delete-endpoint cancel tokens and CAS writes to that branch.

```decision-log
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
```
