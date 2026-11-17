# GDPR Compliance Guide

This guide helps EDDI operators handle data subject requests and meet
GDPR/CCPA requirements. What EDDI stores, what leaves the deployment and what
an erasure does to each store is inventoried once, in
[compliance-data-flow.md](compliance-data-flow.md); for an overview of data
processing, see [PRIVACY.md](../PRIVACY.md).

## Handling Data Subject Requests

### 1. Right to Erasure (GDPR Art. 17 / CCPA §1798.105)

When you receive an erasure request:

```bash
# Via REST API
curl -X DELETE https://your-eddi-instance/admin/gdpr/{userId} \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

The response carries a count for every store the cascade touches, plus whether
the cascade actually finished:
```json
{
  "userId": "user-123",
  "memoriesDeleted": 15,
  "conversationsDeleted": 8,
  "conversationMappingsDeleted": 3,
  "logsPseudonymized": 42,
  "auditEntriesPseudonymized": 156,
  "auditEntriesRedacted": 156,
  "attachmentsDeleted": 4,
  "journalEntriesDeleted": 2,
  "checkpointsDeleted": 9,
  "groupConversationsDeleted": 1,
  "sharedArtifactsDeleted": 0,
  "schedulesDeleted": 0,
  "connectionGrantsDeleted": 1,
  "autoVaultedSecretsDeleted": 0,
  "additionalDeleted": {},
  "failedSteps": [],
  "complete": true,
  "completedAt": "2026-04-02T15:30:00Z"
}
```

> **Check `complete` before filing the request as fulfilled.** The cascade
> deliberately continues past a failing store, so the categories after it are
> still erased — which is exactly why `conversationsDeleted: 0` on its own cannot
> be read as "this user had no conversations". When any step throws, the endpoint
> answers **207 Multi-Status**, `complete` is `false`, and `failedSteps` names the
> steps that did not run. Some of the user's data is still there: re-run the
> erasure and do not report it to the data subject as done. A cascade in which
> every step succeeded answers **200** with an empty `failedSteps`.
> Besides the step names above, `failedSteps` can also name `clusterStop`, `erasureTombstone`, `asyncWriteDrain`, `autoVaultedSecrets`, `deadLetters` and a participant by its name (`alerts`, `personalSpaceResources`, `conversationRatings`, `toolQuotas`, ...). The dead-letter purge runs in single-node mode too, not only in a cluster.
>
> The step names are `auditRewrite`, `inFlightConversations`, `runningGroupDiscussions`,
> `userMemories`, `restrictionCache`, `conversationIdLookup`,
> `attachments`, `hitlToolJournal`, `conversationDescriptors`,
> `conversationCheckpoints`, `conversations`, `conversationMappingIntents`,
> `conversationMappings`, `conversationMappingCache`, `groupConversations`,
> `sharedArtifacts`, `schedules`, `oauthStates`, `connectionGrants`, `userMemoriesResweep`,
> `databaseLogs`, `auditRedaction` and `auditLedger`, and they name the stores in the list below
> plus the two cache evictions, the two lookups the cascade needs to reach them,
> and the two signals that stop in-flight work first. `conversationIdLookup` is the worst one to
> see: the id resolution the per-conversation sweeps depend on failed, so those
> sweeps ran over nothing and their zero counts mean "not attempted", not
> "nothing to do".

**Via MCP:** Use the `delete_user_data` tool with `confirmation="CONFIRM"`. It
reports the same outcome: `status` is `"completed"` only when every step
succeeded and `"partially_completed"` otherwise, alongside the same `complete`
and `failedSteps`.

`__service__` (the owner of service-bound connection grants) is a reserved system
principal, not a user: erasing or exporting it is refused with 400.

**What happens:**

Before anything else, the erasure records durable **tombstones** of the user and of
each of their conversations, and every node drains its asynchronous writers — see
[The erasure guarantee for asynchronously written data](#the-erasure-guarantee-for-asynchronously-written-data).

0. The user's in-flight work on the node serving the request is **stopped first**:
   running conversation turns are cancelled (they then skip their write-back to
   user memory and their snapshot is discarded) and running group discussions are
   cancelled immediately. Without this, a turn or discussion still running wrote
   the data back seconds after the erasure reported success. In cluster mode
   (`eddi.messaging.type=nats`, see [Clustering](clustering.md)) the stop is also
   sent to every other replica — keyed by a hash of the user id, never the id
   itself, and that hash sealed (AES-GCM) with `eddi.cluster.signing.key` when one is set, since
   a plain SHA-256 of an e-mail address can be reversed from a list of addresses — and the
   user's dead letters are removed. The erasure waits up to `eddi.cluster.gdpr-stop-timeout`
   (10 s) for every replica of a member count read at that moment; a replica that does not
   confirm, or cannot open the sealed hash (it has a different key), marks the step
   incomplete. Each conversation is first
   marked as erased in a cluster-wide marker (`ERASED` bucket, expiring after
   `eddi.cluster.erasure-marker-ttl`, default `P7D`), then its dead letters are
   purged. A node that was cut off from NATS and kept a dead letter of that
   conversation locally checks the marker before it forwards the entry, and again
   right after publishing it, and discards it (or purges the published copy) when
   the marker is there. Without the marker the entry would be published again in
   the window between the purge and the deletion of the conversation's snapshot.
   A node that stays cut off for longer than the marker's lifetime falls back to
   the check that the conversation still exists, which by then has long failed. Work a replica cannot be
   reached for (NATS down) is stopped by the stores refusing to recreate a
   deleted conversation or group discussion, and step 12 removes any memory it
   managed to write in the meantime. From this point the
   node's audit ledger also writes the user's pseudonym instead of their id, and
   the redaction marker instead of their content, for an hour, so audit entries
   that cancelled work still flushes while it unwinds — or that were already
   queued — do not land raw after step 14. In cluster mode every replica that
   answers the stop does the same before it replies, so its late entries are
   pseudonymised and redacted too; see [Clustering](clustering.md#residual-limitations).
1. User memories — **permanently deleted**
2. Binary attachments of the user's conversations — **permanently deleted**. This
   includes the files shared in the user's group discussions (stored under the group
   conversation's id) and the blobs of every member conversation those discussions
   created; the discussions are looked up before step 8 deletes them. A failed lookup
   is reported as `groupConversationAttachments` — and then, as when a blob delete
   under one of those ids fails, step 8 **keeps** the discussion documents (reported
   as `groupConversations`, "kept until their attachments are erased"): they are the
   only record of the ids the blobs are stored under, so deleting them would leave a
   re-run unable to reach the blobs. Re-run the erasure once the store is back
3. HITL tool execution journal entries — **permanently deleted**
4. Conversation descriptors — **permanently deleted**
5. Conversation memory checkpoints — **permanently deleted**
6. Conversation snapshots — **permanently deleted**, unless an earlier step that finds
   its records by conversation id (attachments, dead letters, journal entries,
   descriptors, checkpoints, auto-vaulted secrets) failed: the snapshots are then
   **kept** (reported as `conversations`, "kept until … erased"), because they are what
   lists the conversations under the user, and a re-run needs them to reach what is left
7. Managed conversation mappings — **permanently deleted** (and evicted from their cache)
8. Group conversation transcripts — **permanently deleted** (kept for a re-run when
   step 2 could not finish with their blobs, see there)
9. Shared artifacts owned by the user — **permanently deleted**
10. Schedules owned by the user — **permanently deleted**
11. Pending OAuth authorization flows the user started are invalidated first (a
    callback completing afterwards would otherwise mint a new grant), then the OAuth connection grants (linked accounts) of the user, in every tenant — **permanently deleted**. Each holds a live refresh token for the user's account at the provider; the provider-side consent is not revoked by EDDI and may be revoked by the user there
12. User memories — **re-swept**, for writes that landed while the cascade ran
13. Database logs — userId **pseudonymized** with a *keyed* pseudonym: the audit
    ledger's `gdpr-erased:k1:…` when the ledger signs, otherwise
    `gdpr-erased:s1:…` under a random per-deployment salt kept in the vault's
    metadata store (`gdpr-pseudonym-salt`, created on first use, shared by every
    replica). Neither can be recomputed from a list of candidate ids. Rows erased
    by an earlier release keep their unsalted `gdpr-erased:<sha256>` value — there
    is no migration, as the raw id is gone from them; only when the metadata store
    cannot be read does an erasure fall back to that form (logged at WARN). The log
    message text is not rewritten; bound its lifetime with `eddi.logs.db-retention-days`
14. Audit ledger — rows **kept, content redacted, userId pseudonymized**. The write
    queue is flushed first, so entries still queued are caught. Each row's prompt,
    response, LLM detail and tool calls are replaced by a redaction marker and its
    userId by a keyed pseudonym; a row that verified is re-signed and still
    verifies, a row that did not keeps failing; no row is deleted, so the
    conversation's chain stays intact. `auditEntriesRedacted` counts the rows;
    a row that could not be redacted fails the step (`auditRedaction`) and the
    erasure is not `complete`. Details: [audit-ledger.md](audit-ledger.md#gdpr-erasure-redaction-not-deletion).
    Under `eddi.audit.erasure-mode=pseudonymize` only the userId is replaced and
    the content is **kept** — choose that only when a legal hold requires it
15. Stores that registered as GDPR participants — conversation ratings, tool quotas, alerts, personal-space secrets and variables (`personalSpaceResources`), the user directory, workspace
    notifications and the **A2A task store** (a peer's tasks and context bindings,
    each holding the message it sent and the reply it got, keyed by the peer's
    principal), the **email ledger** (HITL notification rows and conversation-mode
    reply rows addressed to the person, and inbound mail rows sent by them — matched
    through the user id when it is an address or an `email:<integrationId>:<address>`
    identity, and the email the user directory recorded; it runs before the
    directory is erased), the **email suppression list** (the addresses a reply
    bounced from permanently, stored as hashes, matched the same way — erasure
    deletes the entry rather than keeping a hash to honour "never mail me again":
    conversation mode only answers mail the person sends, so EDDI writes to them
    again only if they write first),
    and **email identities**: an erasure for an *address* runs this whole cascade
    again for every `email:<integrationId>:<address>` identity of that address —
    the conversations, memories and properties an [email integration](email-integration.md#identity)
    stored under it — for every current email integration and every integration
    the address's inbound ledger rows name. Export collects the same
    identities. A nested erasure that is incomplete fails the step. The one-off
    `email-anon:` identities of unauthenticated senders hold no memory and are not
    linked to an address. All of it is
    **permanently deleted**. Each reports under its own step name
    (`userDirectory`, `workspaceNotifications`, `a2aTasks`, `emailLedger`, `emailSuppressions`, `emailIdentities`)
16. **Conversation ratings** the user gave (score, tags, comment) — **permanently
    deleted** by the `conversationRatings` participant, which also forgets the user
    as the *reviewer* of ratings they triaged as a maintainer (those ratings are
    other people's data and stay). Ratings deliberately outlive retention purges
    of transcripts, so no conversation sweep reaches them. See
    [Conversation Ratings](conversation-ratings.md). The `hitlOversight` participant deletes the audit samples of the user's
    conversations and keeps their approval-decision facts without them ([HITL Oversight](hitl-oversight.md#gdpr))
17. **Tool quota counters** (`toolQuotas`) — **permanently deleted** by the `toolQuotas`
    participant: every counter whose subject equals the erased user id. A counter holds a subject
    identifier and a count, no content, and expires on its own an hour after its window ends. When an
    agent's `toolQuotas.subject` template derives an identifier other than the conversation's user id,
    the counters sit under that identifier and are deleted when *that* identifier is erased. The export
    carries the user's counters (tool or pool, window start, count) under `counters` in the
    `toolQuotas` section of `additionalData`, at most 1,000; past that the section says
    `"truncated": true` and the bundle reports `complete: false`

#### The erasure guarantee for asynchronously written data

Some per-user rows are written from a background queue, after the turn or request
that produced them: alert events (an `OUTPUT_MATCH` value, a rating comment
excerpt), and any other *async erasure writer* that joins the same mechanism. Such a
row, queued before an erasure, used to be able to reach the store after the erasure
had deleted everything — an erasure reported complete, followed seconds later by
the data it had removed. For these rows the erasure now gives a **guarantee**:

> `complete: true` means no guarded row of the user exists **and none can be written
> later**. When that cannot be ensured, a step fails — the erasure is never reported
> complete while a late write is still possible. Every such failure is retryable:
> re-running the erasure finishes it. The exceptions are listed, exactly, under
> [Residual limits](#residual-limits-of-the-erasure-guarantee) below.

How it holds:

1. **Tombstones first.** Before the first stop or delete, the erasure writes a durable
   tombstone of the user and one of each of their conversations
   (`erasuretombstones` / `erasure_tombstones`). The user is recorded as an HMAC of
   the SHA-256 the cluster stop carries — never the id, and never a plain hash anyone
   with a list of e-mail addresses could recompute (see
   [The tombstone key](#the-tombstone-key)). Conversations are recorded by id. A
   tombstone is kept for `eddi.gdpr.tombstone-retention` (default `400d`), longer
   than any guarded data can still arrive (alert events live 90 days). A tombstone
   that cannot be written fails the step `erasureTombstone`. On MongoDB every read
   and write of these collections goes to the primary with majority read and write
   concern, whatever the client's `nearest()` default: a lagging secondary must never
   answer "not erased".
2. **Guarded writes.** A writer asks before writing (`beforeWrite`) and does not store
   the row — an alert then fires without its value or excerpt — when the row's
   conversation is tombstoned, or its user is tombstoned and the content was
   captured before the erasure. "Before" means up to `eddi.gdpr.tombstone-clock-skew`
   (default `5m`) after the later of two clocks: the erasing node's, and the
   database's own clock when it stored the tombstone. Content captured later is new
   activity of a reused id and is stored. Right after writing the writer asks again
   (`afterWrite`): if an erasure started in between, the row removes itself; if that
   removal fails, a durable **pending removal** is recorded
   (`erasurependingremovals` / `erasure_pending_removals`) and retried every five
   minutes. Tombstones that cannot be read count as present (fail closed). There is
   no cache: a cached "not erased" is exactly what would let a late write through.
3. **Drain barrier.** After the stop, each node waits until the work its async
   writers had queued *before the tombstones existed* has been written (at most
   `eddi.gdpr.drain-timeout`, default `30s`, for all writers together). Work queued
   later sees the tombstones. Only the guarded **write** is waited for: an alert's
   Slack or webhook delivery runs on a separate stage, so a slow webhook or an alert
   storm cannot fail an erasure. On the node running the erasure a writer that does
   not drain in time fails the step `asyncWriteDrain`; every other node drains while
   answering the cluster stop (`gdpr-stop`) and confirms it with `drained: true` — a
   node that does not answer, reports a timeout, or answers without that
   confirmation (a node of an older release during a rolling upgrade) fails
   `clusterStop`.
4. **Delete, then verify.** The writer's participant deletes the user's rows and then
   checks — on MongoDB again from the primary — that none is left and no pending
   removal of the user remains; otherwise its step fails (`alerts` for alert events).
   A re-run first retries the pending removals, so it completes once the store
   accepts the removal.

**How long an erasure can take.** The barrier adds at most one drain timeout on the
erasing node, and in cluster mode the stop waits `eddi.cluster.gdpr-stop-timeout`
plus the drain timeout for the other nodes: with the defaults an erasure can take
about **70 seconds** longer than the deletes themselves when a node is slow
(normally it adds milliseconds). A REST or MCP client calling
`DELETE /admin/gdpr/{userId}` should allow for that in its timeout.

The same holds for an **explicit, permanent conversation delete**
(`DELETE /conversationstore/conversations/{id}?deletePermanently=true`): the
conversation is tombstoned, every node drains (the `erasure-drain` cluster RPC),
the conversation's alert values are removed and verified — all before anything else
of the conversation is deleted. If any of it fails, the delete fails with an error
naming the cause and **nothing else is deleted**, so retrying the same delete
finishes it. Rating comment excerpts are **not** removed by a conversation delete:
the rating itself outlives the conversation (ratings deliberately survive
transcript deletion), and the excerpt follows the rating — it is removed when the
rating or its author is erased.

**Not covered, by design.**

- The *retention* sweep of ended conversations
  (`deleteEndedConversationsOnceOlderThanDays`) runs no barrier: it deletes
  conversations that have been ended and untouched for at least that many days, so
  no work of theirs can still be queued; their alert events age out on
  `eddi.alerts.event-retention`.
- **Agent-test conversations** (the test runner's cleanup) are purged without the
  barrier and without tombstones: their user is the synthetic
  `agent-test:<runId>:…` id, no person, and a cluster-wide drain plus a 400-day
  tombstone per test conversation would tie every test run to the slowest node.
- A soft delete keeps the conversation and is no erasure.

##### The tombstone key

The user part of a tombstone is HMAC-SHA256 of the user id's SHA-256 under a
deployment key:

- **With the secrets vault configured** (`EDDI_VAULT_MASTER_KEY`), the key is derived
  (HKDF, label `eddi-gdpr-erasure-tombstone-key|v1`) from the vault's deployment key,
  which is stored only wrapped by the master key and survives a KEK rotation. It is
  never stored. A dump of the database alone — without the master key — cannot be
  used to test whether a given e-mail address was erased.
- **Without a vault**, the key is a random salt stored in the tombstone store's own
  meta table (`erasuremeta` / `erasure_meta`). That protects a copy of the tombstone
  table on its own, **not** a dump of the whole database: whoever holds both the
  salt row and the tombstones can test candidate addresses.

In both modes the first node to use the key records a check value
(`tombstone-key-check`), and every node compares its key with it. A key that
changed — a vault configured or removed after tombstones were written, a lost or
restored salt row, a restored vault metadata store from another deployment — would
make every existing tombstone silently stop matching. Instead the guard **fails
closed**: guarded writes are skipped (alerts fire without values or excerpts),
erasures and permanent conversation deletes fail, an ERROR names the cause, and
`eddi_gdpr_guard_key_mismatch_total` rises. The operator action: restore the vault
master key and metadata, or the salt row, that were in use; the guard recovers on its
own. Only if the old key is gone for good, delete the `tombstone-key-check` row — the
new key is adopted, and tombstones written under the old one stop matching (they
matter only for work that was queued at the time of those erasures, which by then
has long been written).

##### Residual limits of the erasure guarantee

Each of these is a precise edge, and each either surfaces as a failure or is the
consequence of a deployment condition the guarantee states it relies on:

1. **A node outside the cluster's presence view.** The barrier reaches the members of
   the presence view read when the erasure starts. A node that is running but cut
   off from NATS is not among them; work it queued before the erasure writes after
   the tombstones, so its `afterWrite` removes the row again — or leaves a pending
   removal, retried every five minutes and failing every re-run until it is gone. In
   that window, after the erasure has answered, the row exists. (A crashed node stays
   in the presence view until its entry expires and fails the cluster stop until
   then — a failure, not a leak.)
2. **A row that could neither be removed nor recorded.** If `afterWrite` finds its row
   erased, cannot remove it, and also cannot record the pending removal (the store
   is down for both), the row stays: ERROR log, `eddi_gdpr_guard_pending_removals_total{outcome="record_failed"}`.
   On a node the barrier reached, the erasure's own delete runs after that write and
   still removes and counts the row. On a node cut off from NATS (limit 1) nothing
   does, and no re-run is blocked by it: the row stays until the alert's retention
   deletes it — watch `record_failed`, which should be zero.
3. **Clocks.** The time-based half of a user tombstone ("captured before the
   erasure") assumes node clocks agree within `eddi.gdpr.tombstone-clock-skew` (5
   minutes) — run NTP on every node and the database. The database's own write time
   is used as well, so a single slow erasing node cannot move the cut-off back; a
   *writer* whose clock runs more than the skew ahead could still stamp content
   captured before the erasure as after it. Conversation tombstones have no time
   component and do not depend on clocks at all.
4. **Rolling upgrades.** A node of an older release does not drain, and the erasing
   node then reports `clusterStop` failed — a failure, re-run after the rollout. But
   an erasure **coordinated** by an older node writes no tombstones at all, so the
   late writes of the new nodes are not guarded (they log a WARN when such an
   erasure's stop reaches them). **Upgrade every node before relying on the
   guarantee**, and re-run erasures made during the rollout.

**For a new async writer** (an `AsyncErasureWriter` bean), the contract is: queue
work through a `DrainBarrier` and answer `awaitDrained` from it — and keep anything
slow (network sends) out of the barrier-tracked task; call
`ErasureGuard.beforeWrite(userId, conversationId, capturedAt)` before writing and
`ErasureGuard.afterWrite(name, rowId, userId, conversationId, capturedAt, remover)`
right after; store a user reference only as `ErasureGuard.subjectKey(userId)`;
implement `removeRow` (idempotent), `eraseConversation` and `countForConversation`,
with verification reads that see committed writes (MongoDB: primary, majority); and
in the writer's `IGdprParticipant.erase`, delete the user's rows and then call
`ErasureGuard.verifyUserErased(name, userId, remaining)`. The writer's `name()` is
stored in pending removals and must never change.

### 2. Right of Access (GDPR Art. 15) / Data Portability (Art. 20) / Right to Know (CCPA §1798.100)

```bash
curl https://your-eddi-instance/admin/gdpr/{userId}/export \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -o user-data.json
```

The export is a structured, machine-readable JSON bundle of these categories — not
yet every category EDDI erases; what it leaves out is named in `omittedCategories`
(see the note below):
- All persistent user memories
- Conversation outputs (the chat history as recorded in the conversation outputs). Conversation properties, step data and checkpoints are **not** exported
- All managed conversation mappings (intent→conversation bindings)
- Audit processing records: conversation id, agent id, task type, duration, LLM detail and timestamp (capped at 10,000 entries; the prompt input, output and tool calls of the entry are not included)
- Attachment metadata (conversation, storage reference, file name, MIME type, size) —
  the binaries themselves are not inlined and must be fetched via the attachment
  download API
- Linked OAuth accounts (`connectionGrants`: tenant, connection, status, scopes and
  dates) — never token material
- Conversation ratings the user gave (`conversationRatings`: conversation and step,
  agent and version, score, tags, comment, triage status and note — without the
  reviewing maintainer's identity; capped at 10,000). Processing restriction (Art. 18)
  blocks new ratings
- HITL oversight (`hitlOversight`): audit samples of the user's conversations (redacted tool arguments) and the approval-decision
  facts of their conversations and decisions (capped at 10,000 each)
- Alerting (`alerts`): for a user who runs agents, the alert rules they created or last
  edited — id, name, times and which role — and the alert events they acknowledged —
  id, rule name, source, agent, fired and acknowledged times; for a user who chatted,
  the `OUTPUT_MATCH` values alerts stored from their conversations (`outputMatchValues`:
  value, conversation, time). No other person's identity; capped at 10,000 each.
  Erasure blanks those values and forgets the user as acknowledger and rule author
- A2A tasks the user, as a peer, created (`a2aTasks`: task and context ids, the
  message sent, the reply, state and timestamps)
- Group discussions the user started (`groupConversations`: question, transcript,
  state and dates; capped at 1,000, `groupConversationsTruncated` when the cap bit),
  and the shared artifacts of those discussions (`sharedArtifacts`: content and
  revision history; capped at 1,000, `sharedArtifactsTruncated`). The attachment
  metadata above includes the discussions' files and those of their member
  conversations. A store that fails is named in `failedCategories`

> **Check `complete` before handing the bundle to the data subject.** The endpoint
> answers **200** only when the bundle covers everything EDDI holds on the user,
> and **207 Multi-Status** otherwise. Several things can make it incomplete, and each
> is named in the payload:
>
> - `omittedCategories` — personal-data categories this exporter does not reach.
>   **Today this list is never empty**: schedules and HITL journal entries are
>   erased by the Art. 17 cascade as this user's personal data but are not yet
>   exportable (group transcripts and shared artifacts are exported since the
>   2026-10-17 review). So the export endpoint currently answers **207 on every
>   request** and `complete` is always `false`. It becomes 200 when those two
>   exporters land.
> - `conversationsTruncated` — the per-request conversation cap (1,000) bit,
>   because each snapshot is a full document load assembled in memory on the
>   request thread. `totalConversations` says how many the user actually has, and
>   the omitted ones remain retrievable individually through the conversation API.
> - `failedConversationIds` — conversations the exporter could not load at all,
>   listed by id, and therefore absent from `conversations`. Retry the export or
>   fetch those ids individually.
> - `groupConversationsTruncated`, `sharedArtifactsTruncated` and
>   `failedCategories` — the same two signals for the group sections.
>
> A bundle with `complete: false` is **not** a complete Art. 15 / Art. 20
> response — do not file the request as fulfilled on it. (The audit-record
> cap of 10,000 is silent: the bundle does not flag it and `complete` ignores it, so
> a user with more audit rows than that needs a supplementary extract. A failure
> while reading the memories, mappings, audit, attachment or grant blocks is likewise
> only logged and does not appear in `failedCategories`.)
>
> 207 rather than 206 Partial Content, which earlier releases sent: 206 is a range
> status and RFC 9110 requires a `Content-Range` alongside it, which this endpoint
> neither reads nor produces. Any client still branching on 206 needs updating.

**Via MCP:** Use the `export_user_data` tool. It reports the same fields in the
payload — `complete`, `omittedCategories`, `conversationsTruncated`,
`totalConversations` and `failedConversationIds` — and an agent acting on its
answer must not report an incomplete bundle as fulfilled.

### 3. Right to Restriction of Processing (GDPR Art. 18 / LGPD Art. 18)

When a user disputes data accuracy or objects to processing, you can
freeze their processing without deleting data:

```bash
# Restrict processing
curl -X POST https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"

# Check restriction status
curl https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
# Returns: true/false

# Remove restriction
curl -X DELETE https://your-eddi-instance/admin/gdpr/{userId}/restrict \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

**What happens when restricted:**
- New conversation creation is blocked → returns **403 Forbidden**
- Message processing (`say`) is blocked → returns **403 Forbidden**
- Existing data is preserved (not deleted)
- Restriction status is stored as a user memory entry under the reserved key
  `_gdpr_processing_restricted` (category `gdpr`). Keys starting with `_gdpr_` are
  reserved for this bookkeeping: agents (`rememberFact`/`forgetFact`), property
  setters, the user-memory and properties REST endpoints and the MCP memory tools
  all refuse to write or delete them, the "delete all memories" surfaces keep them,
  and retention and Dream maintenance never touch them. Only this admin endpoint
  sets or lifts a restriction, and lifting removes every row under the key
- All restriction/unrestriction events are logged in the audit ledger

**Caching the restriction flag — `eddi.gdpr.restriction-cache-ttl-seconds` (default `0`, i.e. no caching)**

The flag is read at conversation start and again on every `say`/`sayStreaming`, so it sits on
the hottest path in the system. **By default it is nonetheless read from the store every
time**: no verdict is cached, a restriction applied on any replica takes effect on every
replica at once, and a store outage always answers **503**
(`ProcessingRestrictionUnavailableException`) rather than being absorbed by a cached verdict.
The cost of that default is one indexed lookup per turn.

Setting the property above `0` switches on a **node-local** cache with that TTL.
`restrict`/`unrestrict` publish through it, so the node serving the admin call applies the
change on the very next turn. In cluster mode the eviction is also sent to every other node, but
an event can be lost while NATS is unreachable, and then **another node keeps answering "not
restricted" from its own cache until the TTL expires** — and every node keeps answering from
cache during a store outage instead of failing closed. A cached negative verdict is a suspended
Art. 18 legal control, which is why it is not the default.

- **Several replicas** — keep the default (`0`), cluster mode included: cross-node invalidation
  is best-effort while NATS is unreachable.
- **Single node, or a cluster with conversation affinity** — every turn of a conversation and
  every admin call reach the same node, which is what makes the explicit invalidation
  sufficient. Setting e.g. `eddi.gdpr.restriction-cache-ttl-seconds=30` there buys back the
  per-turn lookup. Treat it as an explicit topology assertion, not a tuning knob: it is wrong
  the moment a second replica is added.

**Use cases:**
- User disputes accuracy of stored data (Art. 18(1)(a))
- Processing is unlawful but user requests restriction instead of erasure (Art. 18(1)(b))
- User objects to processing pending verification (Art. 21)

### 4. Response Timeline

| Regulation | Initial Deadline | Extension |
|---|---|---|
| **GDPR** | 30 days | Up to 90 days for complex requests |
| **CCPA** | 45 days | Up to 90 days with notification |

EDDI's erasure and export are normally quick for a typical user, though a user with
very many conversations, or a cluster that has to stop work on several nodes, takes
longer. The timeline constraint is usually your internal DSAR process, not the
technical execution.

## Retention Configuration

```properties
# Auto-delete ended conversations after N days (default: 365, -1 to disable).
# A database migrated from EDDI 5 holds this sweep until
# eddi.migration.v6-rename.retention-confirmed=true; the dev profile overrides these defaults.
eddi.conversations.deleteEndedConversationsOnceOlderThanDays=365

# End idle conversations after N days (default: 90; -1 disables)
eddi.conversations.maximumLifeTimeOfIdleConversationsInDays=90

# User memories — delete entries older than N days (default: -1, disabled)
eddi.usermemories.deleteOlderThanDays=-1

# Persisted database logs — delete entries older than N days (default: -1, kept)
eddi.logs.db-retention-days=-1
```

**Deleted conversations follow the same clock.** Deleting a conversation without
`deletePermanently=true` (the Manager's default) ends it, hides it from every
listing and denies access to anyone but its owner and admins. Its memory stays
until `deleteEndedConversationsOnceOlderThanDays` has passed since its **last
interaction**, and is then removed with the other ended conversations. With the
default of 365 days, a conversation deleted this way can stay stored for up to a
year. Use `deletePermanently=true` or a GDPR erasure when it has to go at once.
(Earlier releases purged such a conversation at the next daily sweep if it had
already ended, and never if it was still open.)

**A deletion is all or nothing.** The retention sweep and `deletePermanently=true`
remove the same things: the conversation's auto-vaulted secrets, attachments,
memory checkpoints (copies of its properties) and HITL tool-journal entries, then
its memory and descriptors. If any of the dependent deletes fails — or the secrets
vault is unavailable while the conversation references auto-vaulted secrets — the
memory and descriptors are kept: the permanent delete answers with an error and can
simply be retried, and the sweep tries again on its next run. (The sweep used to
skip checkpoints and tool-journal entries entirely, and both paths used to delete
the memory after a failed attachment or checkpoint delete, which left those copies
with nothing pointing at them.)

**Ending idle conversations does not restart the retention clock.** Both the
daily idle sweep and the admin bulk end
(`POST /conversationstore/conversations/end-inactive`, see
[Upgrading from EDDI 5](upgrading-from-5x.md)) set the conversation to `ENDED`
without touching its descriptor, so `deleteEndedConversationsOnceOlderThanDays`
keeps counting from the **last interaction**.

**Per-category retention** allows different retention periods for:
- **Conversations** — 365 days (default)
- **User memories** — disabled by default (configure per-deployment)
- **Database logs** — disabled by default; a daily sweep deletes persisted log
  entries older than `eddi.logs.db-retention-days`. Earlier releases documented
  this retention as configurable without any code that applied it

**The audit ledger has no retention property — by design.** `IAuditStore` is an
append-only contract that deliberately exposes no update or delete operation, so
EDDI never time-expires audit entries. Erasure requests are satisfied by
**redacting** the erased user's rows — their content replaced by a marker, their
`userId` by a pseudonym — not by deleting them. See
[Audit Ledger Legal Basis](#audit-ledger-legal-basis) below for the legal basis.
If your jurisdiction requires time-limited audit retention, implement it at the
operational or database layer (archival job, partition drop, storage-level TTL);
EDDI provides no application-level audit purge.

> **Operator note:** earlier releases shipped an `eddi.audit.retentionDays`
> property in `application.properties`. No code ever read it, so setting it was a
> silent no-op — no audit entry was ever deleted by it. The property has been
> removed; if your deployment sets `eddi.audit.retentionDays` (or
> `EDDI_AUDIT_RETENTIONDAYS`), drop it and use database-level archival instead.

### The audit dead-letter sink holds personal data, and erasure does not reach it

When the ledger cannot persist an entry it writes that entry to the dead-letter
sink — in cluster mode the `eddi.<prefix>.dlq.audit` subject of the JetStream
dead-letter stream while NATS takes it, otherwise the JSONL file at
`eddi.audit.dead-letter-path` (default
`/opt/eddi/data/eddi-audit-deadletter.jsonl`). **The record is the whole audit
entry**: the `userId`, the verbatim prompt and response, the LLM detail and the
tool calls, plus the HMAC and agent signature. It has to be, or a dropped entry
is neither replayable nor usable as evidence of what the ledger itself lost.

The consequence for Art. 17 is that the sink is a **second location holding
personal data that the erasure cascade does not touch**. `DELETE
/admin/gdpr/{userId}` redacts the ledger and pseudonymizes the database logs;
nothing rewrites the JSONL file or the JetStream subject. Secret *redaction* has already
been applied to the entry (see
[Secret Redaction](audit-ledger.md#secret-redaction)), but user content has not,
because preserving the entry is the whole point of the sink.

As the controller you must therefore:

- [ ] Treat `eddi.audit.dead-letter-path` (and, in cluster mode, the
      `eddi.<prefix>.dlq.audit` JetStream subject) as an audit-data location in your record of processing
      activities, with the same access controls and encryption at rest as the
      ledger itself.
- [ ] Include it in the erasure procedure: either replay and truncate it once
      the store is healthy again, or pseudonymize the affected records by hand.
      `eddi_audit_entries_dropped_total` counts per process and starts at zero
      on every restart, so a zero value does not prove the sink is empty. Check
      the JSONL file on every node and, in cluster mode, the
      `eddi.<prefix>.dlq.audit` subject of the dead-letter stream
      (`nats stream subjects <prefix>_DEAD_LETTERS`) before concluding there is
      nothing to do.
- [ ] Give the JSONL file a retention period: as with the ledger, EDDI never
      expires it. The JetStream stream is different: it drops its entries after
      `eddi.coordinator.dead-letter.max-age` (default `7d`), whether or not they
      were replayed, and it is their only copy — replay within that window.

A non-empty dead-letter sink is an incident, not a steady state — see
[Incident Response](incident-response.md).

**Data minimization (Art. 5(1)(e)):** Review the default retention periods
and reduce them to the minimum necessary for your use case.

## What erasure and export do not reach

Erasure covers every store EDDI keys by a user — the steps and the
participants listed above. Export is narrower: it is the bundle described under
[Right of Access](#2-right-of-access-gdpr-art-15--data-portability-art-20--right-to-know-ccpa-1798100),
and what it leaves out — schedules and HITL journal entries among them — is named in its
`omittedCategories`, so an export must not be handed over as complete without
reading that field. Beyond both, several classes of data sit outside EDDI's reach
altogether, each for a different reason; a controller's record of processing
should name them and the measure that bounds each one.

| Data | Why it is out of scope | What bounds it |
| --- | --- | --- |
| **RAG knowledge bases** (`ragstore`, pgvector, external vector stores) | Agent-level reference material an editor ingests for the agent, not records about a user. Nothing in a knowledge base is keyed by a user id, so no per-user sweep is possible — and personal data ingested as a source is a document the controller chose to publish to the agent, removed through the knowledge base's own source management | Review sources before ingestion, and delete a source that holds a data subject's record through the RAG store's source endpoints (see [rag.md](rag.md#ingestion-sources)) |
| **Application log files** (console, files a log shipper collects) | Written as text to destinations EDDI does not control and cannot rewrite afterwards. The persisted database logs *are* pseudonymised (step 13); the console copy is not. Credential material is redacted before any record is written, and user ids appear only where the logging context carries them | Keep file and shipper retention short, and treat the collected logs as a processor's data set in your record of processing |
| **Telemetry exports** (OpenTelemetry spans, Micrometer metrics) | Spans carry conversation and agent ids and timings, never message content or user ids; metrics are aggregates. Once exported they are the collector's data, outside EDDI's reach | Retention is set on the collector or metrics backend |
| **Approver identity in other users' HITL tool journals** | The journal is deleted by conversation id, so an approver's `decidedBy` entry inside another user's journal survives the approver's own erasure | Treat approver ids as pseudonymous staff data and bound them with the journal's retention |
| **Group discussions the user took part in but did not start** | The group store matches the discussion's owner, so only discussions the user started are erased or exported | Handle shared transcripts in your DSAR process |
| **LLM-provider retention** | Every LLM call sends the message and its context to the configured provider, a processor under its own DPA; EDDI cannot recall what the provider has logged | Choose providers with zero-retention terms, or self-host (Ollama, jlama); see [LLM Provider Data Flow](#llm-provider-data-flow) |

The dead-letter sink of the audit ledger is a further case, treated above under
[its own heading](#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it).

## Audit Ledger Legal Basis

The EDDI audit ledger is retained indefinitely — check the legal basis for
your deployment; the usual candidates are:

1. **GDPR Art. 17(3)(b)/(e)** — compliance with a legal obligation, or the
   establishment, exercise or defence of legal claims
2. **EU AI Act Arts. 12/19 (and 26(6) for deployers)** — record-keeping and
   automatically generated logs of high-risk AI systems

What those bases cover is the record that processing happened — which task ran,
when, for which agent version, at what cost, emitting which actions — not the
erased person's words. An erasure therefore keeps every row and its chain
position but removes the person from it: the `userId` is pseudonymized and the
recorded content (prompt, response, LLM detail, tool calls) is replaced by a
redaction marker. If your legal hold requires the content itself, set
`eddi.audit.erasure-mode=pseudonymize` and document why.

## Controller Checklist

As the data controller, you must:

- [ ] **Privacy Policy**: Document your use of EDDI and AI processing
- [ ] **Legal Basis**: Determine and document the legal basis for each
      processing activity (see PRIVACY.md for suggestions)
- [ ] **Consent**: Obtain consent before enabling conversational AI
      (if consent is your legal basis)
- [ ] **DPAs**: Establish Data Processing Agreements with:
  - Your EDDI hosting provider (if not self-hosted)
  - Each cloud LLM provider configured in your agents
- [ ] **User Notice**: Inform users that conversations are processed by AI
- [ ] **Retention**: Review default 365-day retention — adjust if needed, and
      set `eddi.logs.db-retention-days` (database logs are kept until deleted
      by default)
- [ ] **Memory Disclosure**: If using `enableMemoryTools`, inform users their
      interactions are remembered across sessions
- [ ] **Provider Selection**: Choose LLM providers that meet your data
      residency requirements (see provider table in PRIVACY.md)
- [ ] **Article 30 Register**: Document EDDI as a processing system in your
      records of processing activities
- [ ] **Erasure Process**: Document your internal process for handling DSARs
      using the GDPR API
- [ ] **Breach Response**: Prepare a breach notification plan (see
      [incident-response.md](incident-response.md))
- [ ] **CCPA Disclosure**: If serving California consumers, document that
      EDDI does not sell personal information

## LLM Provider Data Flow

**Conversation content leaves your deployment** on every LLM call, to the
provider configured on the agent — a third party and a processor unless you
self-host the model (Ollama, jlama). What is sent is inventoried in
[What reaches the LLM provider](compliance-data-flow.md#what-reaches-the-llm-provider):
besides the user message, the history window and the templated system prompt,
it can include the user's persistent memories — among them `global` memories
**other agents** wrote about the same user — retrieved documents and tool
results. Provider data locations and DPAs are listed in
[PRIVACY.md](../PRIVACY.md#supported-llm-providers-and-data-locations).

**For maximum data sovereignty**, use Ollama or jlama with self-hosted models.

## CCPA-Specific Requirements

The CCPA requests use the same two endpoints as GDPR, with the same results and
the same limits — read the sections above, not a shorter version of them.

### Do Not Sell (§1798.120)

EDDI does **not sell personal information** and has no mechanism to do so.
Document this in your CCPA privacy notice.

### Right to Know (§1798.100)

Use the export endpoint (`GET /admin/gdpr/{userId}/export`). It does **not**
yet cover every category: schedules and HITL journal entries are listed in
`omittedCategories`, so today the response is
always `207` with `complete: false` — see
[Right of Access](#2-right-of-access-gdpr-art-15--data-portability-art-20--right-to-know-ccpa-1798100).
Supplement those categories by hand before answering the consumer.

### Right to Delete (§1798.105)

Use the erasure endpoint (`DELETE /admin/gdpr/{userId}`) and check `complete`.
It deletes every store except two, which it keeps under §1798.105(d)
(legal obligation): the audit ledger (content redacted, identifier
pseudonymized) and the database logs (identifier pseudonymized). The audit
dead-letter sink and backups are not reached — see
[Right to Erasure](#1-right-to-erasure-gdpr-art-17--ccpa-1798105).

## International Privacy Regulations

EDDI's GDPR/CCPA infrastructure covers the technical requirements of
all major international privacy frameworks. Each regulation has a detailed
feature mapping and deployer checklist in PRIVACY.md:

- **[PIPEDA](../PRIVACY.md#pipeda--canada)** — Canada (10 Fair Information Principles)
- **[LGPD](../PRIVACY.md#lgpd--brazil)** — Brazil (Art. 18 data subject rights)
- **[APPI](../PRIVACY.md#appi--japan)** — Japan (2022 amendments, EU adequacy)
- **[POPIA](../PRIVACY.md#popia--south-africa)** — South Africa (8 processing conditions)
- **[PDPA](../PRIVACY.md#pdpa--southeast-asia)** — Singapore, Thailand & Malaysia
- **[PIPL](../PRIVACY.md#pipl--china)** — China (data localization, cross-border transfer)

The same erasure and export endpoints (`DELETE /admin/gdpr/{userId}`,
`GET /admin/gdpr/{userId}/export`) work for all jurisdictions. EDDI
provides the technical layer; deployer checklists cover organizational
measures (consent, officer appointments, breach notification).

## See Also

- [PRIVACY.md](../PRIVACY.md) — Data processing overview and international regulations
- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA deployment guide
- [eu-ai-act-compliance.md](eu-ai-act-compliance.md) — EU AI Act compliance
- [compliance-data-flow.md](compliance-data-flow.md) — Data flow diagram for auditors
- [incident-response.md](incident-response.md) — Breach response runbook
- [templates/baa-template.md](templates/baa-template.md) — Business Associate Agreement template
