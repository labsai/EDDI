## fix(alerts): review findings on the alerts backend — https-only webhooks, GDPR export, digest healing, bulk acknowledge (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138; Manager follow-ups on `feat/alerts-manager`, PR #1139)

### What changed and why

- **Webhook URLs must be https.** `AlertRuleValidator` refuses a plain `http` URL at save time; `AlertSenders` checks again at delivery, which is the only place a `${vault:…}` URL is known — one that resolves to `http` (or to no host) is not delivered to. STRICT SSRF and no redirects are unchanged. Docs: [Alerts → Delivery](../alerts.md#delivery).
- **GDPR export** (`AlertGdprParticipant.export` returned `null`): it now lists the rules the person created or last edited (id, name, created/updated time, `author` and/or `lastEditor`) and the events they acknowledged (id, rule name, source, agent, fired and acknowledged time), at most 10,000 of each, and no other person's identity or event content. New store reads `IAlertRuleStore.findByAuthor` and `IAlertEventStore.findAcknowledgedBy`, implemented for MongoDB and PostgreSQL and covered by `AlertStoreContract`. Docs: [Alerts → Privacy](../alerts.md#privacy), [GDPR Compliance](../gdpr-compliance.md).
- **A digest could stay stuck.** When the digest's insert hit its dedupe key — another runner sent it, or this one crashed between the insert and marking the held events — the held events stayed `DIGEST_PENDING` and every pass retried a digest that never inserted. `AlertMaintenanceService.sendDigest` now marks them on a dedupe hit too, still without delivering.
- **`actions exists` fired on every clean turn**: an empty action list is now absent (`OutputMatcher.resolve`); `contains` is unchanged.
- **A cache miss copied the turn even when nothing would run it**: when submitting the output-needs loader fails (alerts off, queue full), `OutputMatchHook` returns before building a snapshot.
- **Bulk acknowledge:** a present `ids` list is the selector. An empty list or an empty entry is a 400 `INVALID_PARAMETER` instead of falling through to "everything matching the filter"; the filter applies only when `ids` is left out. The MCP tool acknowledges one event by id and the Manager sends `{}` when acknowledging by filter, so both stay valid.
- **Wording:** `OUTPUT_MATCH` added to the `source` tag of `eddi_alerts_fired_total` ([Metrics](../metrics.md#alert-metrics)), to `AlertEventQueryParams.source` and to `list_alert_events`' description and `source` argument. "No conversation text" is corrected wherever it was repeated (REST Javadoc, MCP description, [Alerts](../alerts.md), [MCP Server](../mcp-server.md), the `eddi-admin` skill): a rating comment and, only with `includeValue` and conversation review on, an `OUTPUT_MATCH` value appear, each cut to 280 characters.

### Design decisions

- **On a digest dedupe hit every held event is marked**, including any held after the existing digest was written. They stay in the inbox but are not listed in a digest message. The alternative, reading the existing digest to mark only what it covered, needs the event ids on the digest, which it does not store.
- The export re-checks each row against the user id, so a store returning a wider set cannot leak another person's rule or acknowledgement into it.

### Tests

448 unit tests across the alert, output-match, REST, MCP, step-runner and GDPR classes pass, among them the new `AlertGdprParticipantTest`, `RestAlertEventsTest.bulkEmptyIdsRefused`, `AlertMaintenanceServiceTest.digestCrashBetweenInsertAndMark`, `OutputMatchHookTest.cacheMissLoaderRefused`, `OutputMatcherTest.emptyActionsAreAbsent` and `AlertSendersTest.httpsOnly`. Store ITs: 32/32 (16 MongoDB + 16 PostgreSQL, with the two new contract cases). The doc and style guards pass. Mutation check: 9 of 9 mutants killed — http accepted at save, an http vault URL delivered to, an empty export, another person's acknowledgement exported, the lastEditor role lost, a dedupe hit leaving events pending, a refused loader submit still snapshotting, an empty ids list falling back to the filter, a blank id entry accepted.

## fix(alerts): erasable output values, queued rating alerts, exact digest recovery, targetId-based URL restore (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138) — the second round of review findings

### What changed and why

- **OUTPUT_MATCH values can be erased.** An event that stores a matched value (only with `includeValue` and conversation review on) also records the conversation owner in an internal field, `AlertEvent.subjectUserId`. It is `@JsonIgnore`d, written by both stores and never read back, so no REST row, MCP answer, operator answer, Slack message or webhook payload can carry it (asserted on the serialized row and on both payloads). Events without a stored value never get it. `AlertGdprParticipant.erase` blanks the value and the field (`IAlertEventStore.redactOutputValues`, partial index on both stores); the owner's export lists those values (value, conversation, time) under `outputMatchValues` — their own conversation content, as the ratings export lists their ratings.
- **A rating alert queued across an erasure.** `AlertEngine.evaluateRating` re-reads the rating first and skips it when it is gone or its `updatedAt` changed (the newer save has its own alert). After the event is written it re-reads again; if the rating was erased in between, the event's excerpt is blanked (`redactRatingComment`) before anything is delivered. No per-user lock.
- **Digest recovery marks exactly what the digest covers.** The digest records the held event ids it covers (`digestEventIds`, internal, at most the 500-event batch); on a dedupe hit the maintenance pass reads that digest (`findByDedupeKey`) and marks only those ids, so events held later stay `DIGEST_PENDING` for the next digest.
- **Webhook URLs are restored by `targetId`, never by position or host.** Every delivery target gets a server-assigned `targetId`, kept across updates and returned by every read. An update or test send that sends a masked or empty URL keeps the stored URL of the target with the same `targetId` only; a masked or empty URL without a known id is a 400 `INVALID_RULE`; a new target must bring a real URL; ids the stored rule does not have (and all ids on a create) are replaced. Removing or reordering two masked targets on one host no longer hands one target the other's URL.
- [Alerts → Privacy](../alerts.md#privacy) now says what is stored: the acknowledger's id (shown in the inbox, never sent out) and the internal subject id of a stored output value, both until erasure or retention.

### Design decisions

- **Rules saved before ids existed need no migration.** A target without an id gets `t` + its existing hashed `key()` on every read — the same id each time — so the first update of such a rule matches the ids the client read, and that save stores them. Both stores already persist targets as JSON, so `targetId` is stored without a schema change.
- **The subject id is write-only.** Erasure and the export query by it in the store; no read maps it back into an event, which is a stronger guarantee than hiding it at each serializer.
- **A digest written before covered ids existed** would match nothing on a dedupe hit. Alerts have not shipped, so no such digest exists outside development databases.

### Tests

477 unit tests across the alert, output-match, REST, MCP, step-runner, GDPR and rating classes pass, including the new `AlertPrivacyRaceTest` (both race orderings, the subject recorded only with a value, never serialized or sent) and the `targetId` cases in `RestAlertRulesTest` (remove one of two same-host targets, reorder, new target, unknown id refused, client ids replaced, legacy rule). Store ITs: alert stores 20/20 on MongoDB and on PostgreSQL (new contract cases: erasable subject, covered ids and `findByDedupeKey`, one-event excerpt redaction, persisted `targetId`), rating stores 15/15 on each. Mutation check: 9 of 9 for the erasure, race and digest changes, 5 of 5 for the `targetId` match.

## fix(alerts): erasure fence for queued alert work, deliveries send the stored event (2026-10-25)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138) — two more review findings

### What changed and why

- **A queued OUTPUT_MATCH value could land after an erasure.** [`AlertErasureFence`](../../src/main/java/ai/labs/eddi/engine/alerts/AlertErasureFence.java) is a `UserErasureParticipant`: the erasure's step 0 (`stopInFlightWork`, and `stopInFlightWorkByHash` on every other node through the existing cluster `gdpr-stop` broadcast, as `ConversationService` and `AuditLedgerService` receive it) records the time the erasure started, per user, keyed by the user id's hash. `TurnSnapshot` now carries its capture time. `AlertEngine.evaluateOutput` stores no value (and no subject) when the snapshot was captured before a live fence — the alert still fires — and after the insert re-checks the fence and blanks value and subject (`redactOutputValues`) before delivery when the fence went up meanwhile. Queued rating alerts use the same fence on top of the re-read: a fenced rating is not evaluated, and an excerpt written while the fence went up is blanked. Fences last 15 minutes (`AlertErasureFence.FENCE_TTL`).
- **A hand-made schedule could pass for `alerts-maintenance`.** The schedule API refused only bodies carrying the alert marker, so a plain schedule named `alerts-maintenance` could be created, and `AlertMaintenanceSchedule.ensure` — which looked the schedule up by name — then took it for the installed one, leaving retries, digests and retention unprocessed. The API now also refuses the reserved name (trimmed, case-insensitive) on create and on a rename (`AlertSchedules.isReservedName`), and `ensure` counts only a row carrying the maintenance marker; a same-named row without it (possible on a deployment from before the name was reserved) triggers a bounded scan for the marked row, and the real schedule is created when none exists. `AlertMaintenanceScheduleTest` and a new `RestScheduleStoreExpandedTest` case cover it; the docs now say a rating alert captured before an erasure is dropped altogether, while an `OUTPUT_MATCH` alert fires without its value.
- **Delivery could send a copy older than the store.** `AlertDeliveryService.deliver` skips an event that no longer exists and reads the event again right before each send, sending the stored (possibly redacted) version — first delivery and every retry, on any node. No cross-node lock: the remaining window is between that read and the send, and a send already on its way cannot be recalled (said in the code and in [Alerts → Privacy](../alerts.md#privacy)).

### Design decisions

- **A dedicated fence bean**, not the dispatcher or the engine: it has one job, both the engine and the cluster handler reach it through CDI, and it keeps the engine free of erasure bookkeeping.
- **The fence only holds back content captured before the erasure started**, so expiry is a matter of memory, not correctness: a later conversation of a reused id is never blocked, and 15 minutes is far longer than the alert queue takes to drain.

### Tests

486 unit tests across the alert, output-match, REST, MCP, step-runner, GDPR, rating and cluster-handler classes pass, including the fence cases in `AlertPrivacyRaceTest` (captured before the fence stores no value, fence up between check and insert blanks before delivery, captured after keeps the value, expiry, set by hash, fenced rating not evaluated) and `AlertDeliveryServiceTest` (first delivery and a retry send the redacted stored version, a deleted event is not sent). Mutation check: 7 of 7 killed.

## fix(alerts): a per-event send claim closes the erasure-vs-delivery window (2026-10-26)

**Repo:** EDDI (`feat/alerts-backend`, PR #1138)

### What changed and why

A delivery could read an event, an erasure redact it and finish, and the delivery then send what it had read. Now **no send starts with erased content after an erasure has completed**.

- **Send claim.** `IAlertEventStore.claimSend(eventId, token, now, until)` is an atomic conditional write that succeeds only when no claim is live (none, or an expired one); `releaseSend(eventId, token)` clears it only for its own token. Both stores keep the claim in internal fields (`sendClaimToken` / `sendClaimUntil`; `send_claim_token` / `send_claim_until`) that no read maps into an event. `AlertDeliveryService` — the one path for first deliveries and retries — claims for `SEND_CLAIM_LEASE` (a fixed 30 s, three times the default 10 s webhook timeout; not tied to a configured `eddi.alerts.webhook-timeout`), reads the event, checks that more than the send timeout is left of the lease, sends, and releases in a `finally`. A claim it cannot take, or a lease too short to finish in, leaves the delivery pending, due again after one lease; the worker never blocks.
- **Erasure waits for live claims.** The redactions erasure uses (`redactOutputValues`, `redactRatingComments`, and the engine's `redactRatingComment`) take `now` and skip an event whose claim is live. [`AlertErasureRedactor`](../../src/main/java/ai/labs/eddi/engine/alerts/AlertErasureRedactor.java) — used by `AlertGdprParticipant` and `ConversationRatingGdprParticipant` — repeats the redaction with a short pause until `countOutputValues` / `countRatingComments` report nothing left, so the erasure completes only after every send that had read the content has finished or its claim has expired.
- **The engine's own post-insert redactions cannot wait on themselves:** they run before the event's first delivery and before the maintenance pass may pick it up (its `nextAttemptAt` is a claim lease away), so no claim on it can exist yet (said in the code).
- [Alerts → Privacy](../alerts.md#privacy) states the guarantee and its one limit: a send that started before the erasure completed cannot be recalled.

### Design decisions

- **Claim per send, not per event delivery:** an event with five targets would otherwise hold one claim across up to five webhook timeouts.
- **The erasure's wait is bounded** by ten leases (5 minutes). A claim always expires within one lease, so only a store that keeps failing the update — or deliveries re-claiming without pause, which the backoff rules out — reaches the bound; the step is then reported failed instead of hanging the erasure. This is the one place the implementation is stricter than "keep going until the claims expire".

### Tests

`AlertSendClaimTest` drives the races with latches, not sleeps: a send paused after its final read holds the erasure (asserted not complete after two redaction passes), the erasure completes once the send finishes, and a retry then sends the redacted event; erasure first, then delivery, sends the redacted event; a live claim makes the attempt skip, an expired one is taken over; a lease too short after the read releases and skips; the erasure's bound fails the step. Store contract (both ITs): conditional claim, token-checked release, expiry takeover, redaction skipping a claimed event until it is released or expired.
