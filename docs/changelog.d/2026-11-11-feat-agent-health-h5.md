## ✨ feat(health): Agent Health business join — references, outcome events, business measures and export (2026-11-11)

**Repo:** EDDI (`feat/agent-health-h5`, on top of `feat/agent-health-h3`)

### What changed and why

Phase H5 of [`planning/agent-health-plan.md`](../../planning/agent-health-plan.md) (§5): what the business says happened
after a conversation becomes the strongest outcome evidence there is. Documented in
[`docs/agent-health.md`](../agent-health.md#business-outcomes), [`docs/webhook-triggers.md`](../webhook-triggers.md),
[`docs/agent-config-authoring.md`](../agent-config-authoring.md#healthconfig--how-agent-health-judges-conversations) and
[`docs/gdpr-compliance.md`](../gdpr-compliance.md); the backend's answers to the contract are its new §15 in
[`planning/agent-health-api.md`](../../planning/agent-health-api.md).

- **`healthConfig`** gains `externalRefs` (property names identifying a business object, at most 10), `outcomeEvents`
  (event type → outcome, or `NONE` to count only, at most 50) and `hashRefs` (default `false`). Identifier-like ref
  names (`email`, `phone`, `iban`, `address`, `first_name`, … as whole words) are refused without `hashRefs`; a ref no
  `property.json` sets is saved with `X-Eddi-Warnings: health-ref-not-set:<name>` (`HealthRefDeclarations`).
- **Capture.** `HealthTurnRecorder` reads the declared refs from the conversation properties on the turn's thread
  (never secrets, at most 128 characters) and stores them on the turn row (`refs`) as `name=value`, or `name#<hmac>`
  with `hashRefs` — HMAC-SHA256 under a per-deployment salt in the vault metadata store (`HealthRefHasher`, the
  `GdprPseudonymizer` pattern; no salt → nothing recorded). Session rows carry their turns' refs (`HealthRefs.union`).
  Postgres `refs` columns are added only when the catalog lacks them; a GIN index serves the ref lookup, a sparse
  `(agentId, refs)` index on Mongo.
- **Ingest.** A webhook channel target of `"kind": "health-outcomes"` with `agentIds` (1–50; EDIT on each to save)
  hands its events to `HealthEventIngest` instead of starting a turn — signature, replay cache, delivery budget,
  vault secret rules and CloudEvents unchanged. Single, array or `{"events": [...]}` bodies, ≤ 100 per request,
  ≤ 10 000 unmatched per agent; per-event refusals with reasons (`agent_not_bound`, `ref_not_declared`, …); an id-less
  event is identified by its content, so a redelivery is stored once. New store `IHealthEventStore` (`healthevents` /
  `health_events`) with Mongo and Postgres implementations and a shared contract (`HealthEventStoreContract`).
- **Matching** (`HealthEventMatcher`, first step of every `health-rollup` pass): by conversation id → that
  conversation's session started last before the event; by ref → per conversation the earliest session carrying it
  (the one that set it), the latest such before the event; a plain ref also matches hashed rows. Unmatched events wait
  `eddi.health.event-match-window` (72 h), then are deleted and counted. A match marks the session due.
- **Precedence.** `HealthSessionEvaluator` ranks a matched, mapped event above everything (`external_event` /
  `conversation_id_event`, counted as confirmed); of several events the one that happened last decides.
- **GDPR.** Matched events carry the session's user: erased and exported (`agentHealthEvents`), purged with their
  conversation and by the retention and orphan sweeps. A match for a user with restricted processing (Art. 18) is
  dropped; an unreadable restriction keeps the event pending. Turn and session exports include refs.
- **Erasure guarantee (rebuilt on h3).** The events are rows of `HealthErasureWriter` (`agentHealthTurns`), like the
  session outcomes, not a writer of their own: they are joined to the same sessions in the same rollup pass, and an
  erasure must drain and verify all of them together. The matcher and the ingest each run through a `DrainBarrier`
  the writer's `awaitDrained` waits on; a match is a guarded write (`beforeWrite` at the session's start, `afterWrite`
  removing the event, pending removals as `event:<id>`); a refused match leaves the event pending (it names no user);
  an event naming a tombstoned conversation is refused at ingest (`conversation_unavailable`) and removed again when a
  delete starts during the insert. `eraseConversation` / `countForConversation` include the conversation's events
  (pending ones too); `HealthEventGdprParticipant` verifies with `verifyUserErased`; the new `countByUserId` /
  `countByConversationId` read committed data from the Mongo primary. The sessions export checks each conversation's
  rows with the new read-side `ErasureGuard.erasedForReading`, reusing a verdict for at most 1 s, so a row is handed to
  the response stream only if its tombstone check passed at most 1 s before (rows handed on within that second after
  an erasure began, or still buffered, are not recalled).
- **Review follow-ups.** `ErasureGuard.Admission.checkFailed()` tells "tombstones unreadable" from "erased" (every
  other caller still refuses both): ingest answers `erasure_check_unavailable`, and `503` + `Retry-After` when that is
  every refusal, instead of a permanent `422`. Event rows of the guard are `event:<conversationId>|<eventId>` and are
  removed with `deleteInConversation(id, conversationId)`, so a sender reusing an event id across conversations never
  has another conversation's row deleted. An ingest event removed by `afterWrite` counts `erased`, not `accepted`.
  The matcher runs `afterWrite` in a `finally` around `markMatched`. The unguarded agent-test purge deletes sessions
  and events even when the turn rows are kept for the orphan sweep. A guard-refused match ends as `expired`; plain
  by-reference pending events survive an erasure until the 72 h window (documented in the GDPR guide).
- **Business section and export.** One `event:<type>` measure per declared type (matched count, baseline, significance,
  `byReference` / `byConversationId` / `valueSum`). `GET /agents/{agentId}/health/export?format=json|csv&kind=daily|sessions`
  — daily needs VIEW, sessions the drill-down rule; fixed CSV columns, formula cells defused, no user ids.
- **Metric** `eddi_health_events_total{outcome}` (charted on the Full Metrics dashboard); settings
  `eddi.health.event-match-window`, `eddi.health.events.max-per-request`, `eddi.health.events.max-pending-per-agent`.
- **Platform Operator**: the health section explains outcome events, the `health-outcomes` target and ref privacy; the
  export GET is allow-listed; operator revision bumped; OpenAPI operations snapshot gains the export.

### Design decisions

- Refs are recorded whenever a turn *ends* with them set (not only when the turn changed them): no per-step history of
  property values exists, and the "earliest session per conversation" rule recovers the setting session.
- Only matched events count as business measures — attribution stays honest (plan §5.4).
- MCP gains no export tool: `read_agent_health` already returns the business section; the export is a file download.

### Still open

- Not run locally: the Mongo and Postgres event-store integration tests (`MongoHealthEventStoreIT`, `PostgresHealthEventStoreIT`) need Docker; CI is the check.
- The Manager's Channels page has no fields for `kind` / `agentIds` (it keeps them); business measures are not series
  ids and not in the version compare.

```decision-log
| 2026-11-11 | Agent Health H5: refs plain by default, salted HMAC opt-in per agent; identifier-like names refused without hashing; external outcome events outrank every source and the latest event wins | Plan §5 / decision 6; the business's latest state is the truth | Hashing by default; first event wins |
```
