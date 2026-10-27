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
0. The user's in-flight work on the node serving the request is **stopped first**:
   running conversation turns are cancelled (they then skip their write-back to
   user memory and their snapshot is discarded) and running group discussions are
   cancelled immediately. Without this, a turn or discussion still running wrote
   the data back seconds after the erasure reported success. In cluster mode
   (`eddi.messaging.type=nats`, see [Clustering](clustering.md)) the stop is also
   sent to every other replica — keyed by a hash of the user id, never the id
   itself — and the user's dead letters are removed. Work a replica cannot be
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
6. Conversation snapshots — **permanently deleted**
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
15. Stores that registered as GDPR participants — the user directory, workspace
    notifications and the **A2A task store** (a peer's tasks and context bindings,
    each holding the message it sent and the reply it got, keyed by the peer's
    principal), and the **email ledger** (HITL notification rows addressed to the
    person — matched through the user id when it is an address and the email the
    user directory recorded; it runs before the directory is erased) —
    **permanently deleted**. Each reports under its own step name
    (`userDirectory`, `workspaceNotifications`, `a2aTasks`, `emailLedger`)
16. **Conversation ratings** the user gave (score, tags, comment) — **permanently
    deleted** by the `conversationRatings` participant, which also forgets the user
    as the *reviewer* of ratings they triaged as a maintainer (those ratings are
    other people's data and stay). Ratings deliberately outlive retention purges
    of transcripts, so no conversation sweep reaches them. See
    [Conversation Ratings](conversation-ratings.md)

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
- All conversation transcripts (with full chat history)
- All managed conversation mappings (intent→conversation bindings)
- All audit processing records (capped at 10,000 entries)
- Attachment metadata (conversation, storage reference, file name, MIME type, size) —
  the binaries themselves are not inlined and must be fetched via the attachment
  download API
- Linked OAuth accounts (`connectionGrants`: tenant, connection, status, scopes and
  dates) — never token material
- Conversation ratings the user gave (`conversationRatings`: conversation and step,
  agent and version, score, tags, comment, triage status and note — without the
  reviewing maintainer's identity; capped at 10,000). Processing restriction (Art. 18)
  blocks new ratings
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
> response — do not file the request as fulfilled on it. (The audit-record cap of
> 10,000 is still reported in the server log only.)
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

EDDI's erasure and export operations complete in seconds — the timeline
constraint is your internal DSAR process, not the technical execution.

## Retention Configuration

```properties
# Auto-delete ended conversations after N days (default: 365, -1 to disable)
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

Erasure covers every store EDDI keys by a user — the fourteen steps and the
participants listed above. Export is narrower: it is the bundle described under
[Right of Access](#2-right-of-access-gdpr-art-15--data-portability-art-20--right-to-know-ccpa-1798100),
and what it leaves out — schedules and HITL journal entries among them — is named in its
`omittedCategories`, so an export must not be handed over as complete without
reading that field. Beyond both, four classes of data sit outside EDDI's reach
altogether, each for a different reason; a controller's record of processing
should name them and the measure that bounds each one.

| Data | Why it is out of scope | What bounds it |
| --- | --- | --- |
| **RAG knowledge bases** (`ragstore`, pgvector, external vector stores) | Agent-level reference material an editor ingests for the agent, not records about a user. Nothing in a knowledge base is keyed by a user id, so no per-user sweep is possible — and personal data ingested as a source is a document the controller chose to publish to the agent, removed through the knowledge base's own source management | Review sources before ingestion, and delete a source that holds a data subject's record through the RAG store's source endpoints (see [rag.md](rag.md#ingestion-sources)) |
| **Application log files** (console, files a log shipper collects) | Written as text to destinations EDDI does not control and cannot rewrite afterwards. The persisted database logs *are* pseudonymised (step 13); the console copy is not. Credential material is redacted before any record is written, and user ids appear only where the logging context carries them | Keep file and shipper retention short, and treat the collected logs as a processor's data set in your record of processing |
| **Telemetry exports** (OpenTelemetry spans, Micrometer metrics) | Spans carry conversation and agent ids and timings, never message content or user ids; metrics are aggregates. Once exported they are the collector's data, outside EDDI's reach | Retention is set on the collector or metrics backend |
| **LLM-provider retention** | Every LLM call sends the message and its context to the configured provider, a processor under its own DPA; EDDI cannot recall what the provider has logged | Choose providers with zero-retention terms, or self-host (Ollama, jlama); see [LLM Provider Data Flow](#llm-provider-data-flow) |

The dead-letter sink of the audit ledger is a fifth case, treated above under
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
