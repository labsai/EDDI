# Audit Ledger

> **Status:** Available since v6.0.0
> **EU AI Act:** Articles 12/19 — record-keeping and automatically generated logs

The Audit Ledger provides a **write-once, append-only** trail of every lifecycle task execution. It captures what data each task read, what it produced, LLM-specific details (compiled prompts, model responses, token usage), tool calls, actions, costs, and timing — signed with HMAC-SHA256 for tamper detection. It also records every administrative REST action (see [Administrative actions](#administrative-actions)) and the GDPR operations.

**What is not in it.** Reads are not recorded — neither REST `GET`s nor MCP reads. The MCP user-memory tools are not recorded. Conversation traffic is recorded per pipeline task, not per HTTP request.

## Overview

Every time a conversation turn is processed, each lifecycle task (parser, behavior rules, HTTP calls, LangChain, output, etc.) generates an audit entry. These entries are:

1. **Scrubbed** — secrets are redacted (API keys, bearer tokens); vault references are left legible
2. **Signed** — HMAC-SHA256 computed over all fields for tamper detection
3. **Batched** — queued in-memory and flushed to the database every few seconds
4. **Immutable** — stored in a write-once collection with no delete operation and no update except the two a GDPR erasure performs (see [GDPR erasure](#gdpr-erasure-redaction-not-deletion))

## Configuration

| Property                            | Default | Description                                                 |
| ----------------------------------- | ------- | ----------------------------------------------------------- |
| `eddi.audit.enabled`                | `true`  | Enable/disable the audit ledger                             |
| `eddi.audit.flush-interval-seconds` | `3`     | How often to flush queued entries to the database           |
| `eddi.audit.max-queue-size`         | `100000` | Bound on the in-memory queue. Entries arriving past the bound are **dropped** and counted on `eddi_audit_entries_dropped_total` — only the flush-retry path dead-letters |
| `eddi.audit.dead-letter-path`       | `/opt/eddi/data/eddi-audit-deadletter.jsonl` | File-based dead-letter log, used when NATS is unavailable |
| `eddi.audit.erasure-mode`           | `redact` | What a GDPR erasure does to the user's rows: `redact` (pseudonymise the user id **and** replace the recorded content) or `pseudonymize` (the user id only — prompts, responses and tool calls are kept; only for a legal hold that requires the content). An unknown value means `redact` |
| `eddi.audit.admin-actions.enabled`  | `true`  | Record administrative REST actions — see [Administrative actions](#administrative-actions) |
| `EDDI_VAULT_MASTER_KEY`             | (none)  | Vault master key — also used to derive the HMAC signing key |

> **Note:** If `EDDI_VAULT_MASTER_KEY` is not set, audit entries are stored without HMAC integrity hashes. A warning is logged at startup.

## Audit Entry Structure

Each audit entry captures:

| Field            | Type    | Description                                                |
| ---------------- | ------- | ---------------------------------------------------------- |
| `id`             | UUID    | Auto-generated unique identifier                           |
| `conversationId` | String  | Conversation this entry belongs to                         |
| `agentId`        | String  | Agent identifier                                           |
| `agentVersion`   | Integer | Agent version                                              |
| `userId`         | String  | User identifier                                            |
| `environment`    | String  | Deployment environment (e.g., `production`)                |
| `stepIndex`      | int     | 0-based step position in the conversation                  |
| `taskId`         | String  | Lifecycle task ID (e.g., `ai.labs.parser`)                 |
| `taskType`       | String  | Task type (e.g., `expressions`, `langchain`)               |
| `taskIndex`      | int     | 0-based task position in the pipeline                      |
| `durationMs`     | long    | Task execution time in milliseconds                        |
| `input`          | Map     | Data read by the task (user input, actions)                |
| `output`         | Map     | Data written by the task (output text, tool results)       |
| `llmDetail`      | Map     | LLM-specific: compiled prompt, model response, token usage |
| `toolCalls`      | Map     | Tool execution: name, args, result, cost                   |
| `actions`        | List    | Actions emitted by this task                               |
| `cost`           | double  | Monetary cost of this step                                 |
| `timestamp`      | Instant | When the task completed                                    |
| `hmac`           | String  | HMAC-SHA256 integrity hash                                 |

## REST API

The audit ledger exposes a **read-only** REST API. No create, update, or delete endpoints exist.

### Get Audit Trail by Conversation

```
GET /auditstore/{conversationId}?skip=0&limit=100
```

Returns audit entries for a conversation, newest first.

### Get Audit Trail by Agent

```
GET /auditstore/agent/{agentId}?agentVersion=1&skip=0&limit=100
```

Returns audit entries for an agent. The `agentVersion` parameter is optional.

### List Administrative Actions

```
GET /auditstore/admin-actions?actor=admin-ann&skip=0&limit=100
```

Returns the [administrative-action records](#administrative-actions), newest first — who changed, deployed, imported, erased or refused what. `actor` (optional) narrows the list to one principal. `limit` defaults to `100`, at most `1000`.

### Get Entry Count

```
GET /auditstore/{conversationId}/count
```

Returns the total number of audit entries for a conversation.

### Verify a Conversation's Integrity

```
GET /auditstore/verify/{conversationId}?skip=0&limit=1000
```

Recomputes every entry's HMAC and checks the per-conversation `sequence` for gaps. `limit` defaults to `1000` with a hard ceiling of `10000`; a non-positive value falls back to the default rather than meaning "unbounded". A non-zero `skip` makes the chain check report the range's own continuity only — the run can then not be anchored at sequence 0, so a deleted *first* entry is invisible on a paginated sweep.

### Verify an Agent's Integrity

```
GET /auditstore/verify/agent/{agentId}?agentVersion=1&skip=0&limit=1000
```

Same per-entry HMAC check across all of an agent's conversations. Because the sweep spans many conversations, the chain verdict is `NOT_APPLICABLE`. The `agentVersion` parameter is optional; the `skip`/`limit` semantics are those of the conversation sweep.

## HMAC Integrity

When the vault master key or `eddi.audit.hmac-key` is configured, each audit entry is signed with HMAC-SHA256:

1. A **signing key** is derived using PBKDF2 with a distinct salt (`eddi-audit-hmac-v1`, 600K iterations), so an audit key never doubles as the vault's KEK. Which secret it is derived from is described under [Signing keys and rotation](#signing-keys-and-rotation).
2. A **canonical string** is built from all entry fields (excluding the HMAC itself), with map keys sorted alphabetically for deterministic output. Nested maps and lists are canonicalized recursively.
3. The HMAC is computed and stored as `v5:<key id>:<64 hex chars>`. The v5 canonical form signs the user identifier as a keyed identity token rather than verbatim (so a GDPR pseudonymisation does not invalidate the signature it had), includes the per-conversation `sequence`, signs the timestamp as epoch milliseconds, and names the key that signed it.

### Signing keys and rotation

The signing key is chosen in this order:

1. **`eddi.audit.hmac-key`** (`EDDI_AUDIT_HMAC_KEY`) — a secret for the ledger alone. Rotating the vault master key never touches it.
2. **The key pinned in the vault.** The first time the ledger starts with the vault available, it stores the key derived from the master key *at that moment* in the vault, sealed (insert-if-absent, so every replica pins the same one), and uses that pinned key from then on. A KEK rotation re-wraps the vault's keys, so the pinned value survives it and the ledger's key does not change.
3. **The key derived from the current master key** — what the ledger signs with until the vault is up, and the only option on a deployment without a vault.

Before this, the audit key was always derived from the current master key, so a routine KEK rotation made every entry ever written fail verification after the restart.

Every v5 entry names its key (a truncated HMAC of a fixed label — it identifies the key without revealing it), and is verified with exactly that key. The verification set is the signing key plus every key the deployment can derive: `eddi.audit.hmac-key`, the pinned key, the key from the current master key, and anything listed in `eddi.audit.hmac-previous-keys`. An entry naming a key outside that set cannot be checked. The key id is plain text in the row, though, so anyone able to edit a row can write one, and an unknown id proves nothing by itself. Every key the ledger pins or signs with is therefore **recorded** in the vault as a sealed system value, which cannot be forged without the vault's keys. An entry naming a recorded key the deployment no longer holds reports **`UNKNOWN_KEY`**: not proven intact, counted with the invalid entries, and resolved by listing that key in `eddi.audit.hmac-previous-keys`. An entry naming a key that was never recorded reports **`INVALID`**, like any other row that does not verify. Treat `UNKNOWN_KEY` as unverified, not as clean. Pre-v5 entries name no key, so each key in the set is tried.

A failed pin at startup is retried with backoff (starting at 30 seconds, up to 10 minutes) while entries are signed, so a transient database error does not leave a node on the master-derived key until its next restart. If an operator adopts a new master key after losing the old one (see the Secrets Vault guide, *Lost master key*), the pinned key and the key records are discarded with the rest of the lost key's system values: the ledger pins a new key, and entries signed with the old one report `INVALID` unless the old audit key is listed in `eddi.audit.hmac-previous-keys`.

To rotate `eddi.audit.hmac-key`, set the new value and move the old one into `eddi.audit.hmac-previous-keys`.

### GDPR erasure: redaction, not deletion

`DELETE /admin/gdpr/{userId}` keeps every one of the user's ledger rows — the record that a task ran, when, for which agent version, at what cost, emitting which actions — but removes the user's data from it. Up to 6.5.0 an erasure only replaced the user id, so the verbatim prompt, response, LLM detail and tool calls (a social-security number the user typed, say) stayed in the ledger while the erasure answered `complete: true`.

For each row of the user — found under the raw id and under any pseudonym an earlier erasure gave it, so re-running an erasure after upgrading cleans up rows an older release only pseudonymised:

1. **The queue is flushed first.** Entries still waiting in the write queue are written before anything is redacted. They used to reach the store after the store-side pseudonymisation and were missed by it (the response reported `auditEntriesPseudonymized: 0`). An entry that cannot be stored right now stays queued and is redacted as it leaves the queue; entries produced afterwards by work the erasure cancelled are redacted before they are signed.
2. **The row is verified.** Its HMAC is checked as `/auditstore/verify` would.
3. **The content is replaced.** `userId` becomes the keyed pseudonym; `input` becomes a marker; `output`, `llmDetail` and `toolCalls` are removed. `id`, `conversationId`, `agentId`/`agentVersion`, `taskId`/`taskType`, `stepIndex`/`taskIndex`, `durationMs`, `cost`, `actions`, `timestamp` and `sequence` are kept.

   ```json
   "input": {
     "gdprRedaction": {
       "reason": "GDPR Art. 17 erasure",
       "redactedAt": "2026-10-03T09:12:44.512Z",
       "redactedFields": ["input", "output", "llmDetail", "toolCalls"],
       "originalHmac": "v5:3f0c…:9a1e…",
       "integrityBeforeRedaction": "VALID"
     }
   }
   ```

4. **Only a row that verified is re-signed.** It gets a new v5 HMAC over its redacted form, so it verifies as `VALID` afterwards; the marker — with the original HMAC, which lets an auditor holding an earlier copy of the row match the two — is inside that signature and cannot be edited or stripped. A row that did **not** verify (tampered, signed with a key this deployment no longer holds, or unsigned) is redacted but keeps its old HMAC, so it keeps failing verification: redaction never turns a failing row into a passing one. Its marker records the verdict at the time.

The **chain survives**: no row is deleted and no `sequence` changes, so `/auditstore/verify` still reports `INTACT` for the conversation. The GDPR compliance events and the administrative-action records carry no conversation content; they are pseudonymised, not redacted.

The write is conditional on the row's stored HMAC, and the erasure is reported as **incomplete** (`complete: false`, failed step `auditRedaction`) when any row could not be redacted. `auditEntriesRedacted` in the response counts the rows redacted, including queued entries redacted on their way out of the queue.

**Not reached by an erasure:** the [dead-letter sink](#failure-handling), and copies outside EDDI (database backups, log shippers). The [GDPR guide](gdpr-compliance.md#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it) covers both.

**`eddi.audit.erasure-mode=pseudonymize`** keeps the behaviour of earlier releases — the user id only — for a deployment under a legal hold that requires the content itself. It logs a WARN at startup, and the erasure response then reports `auditEntriesRedacted: 0`.

### Administrative actions

Every **mutating** REST request — `POST`, `PUT`, `PATCH`, `DELETE` — outside the conversational data plane that reaches an endpoint writes one ledger record, whatever the endpoint answered — so a call the endpoint refused (a `403` from its `@RolesAllowed` check, a `404`, a `409`) is on record as well. A request turned away **before** it reaches an endpoint is not: a `401`/`403` from an HTTP path policy (the authentication layer runs before resource matching) and a path no endpoint serves. Your reverse proxy's access log or Keycloak's event log covers those:

| Field | Value |
| ----- | ----- |
| `userId` | the caller's principal, or `anonymous` when authentication is off |
| `taskId` / `taskType` | `ai.labs.admin` / `admin` |
| `input` | `method`, `path`, `resource` (the endpoint class and method) |
| `output` | `status` — the HTTP status of the response |
| `actions` | `ADMIN_<method>` |

That covers configuration changes (every `…store` resource), deployments (`/administration`), the vault (`/secretstore`), backup import/export and sync (`/backup`), GDPR and other administrative endpoints (`/admin`), conversation deletion (`/conversationstore`), schedules, groups and channel integrations. **Not recorded** here: reads; the conversation and chat APIs (`/agents`, `/userconversationstore`, `/chat` — audited per pipeline task instead); `/v1`, `/a2a`, OAuth and channel callbacks (`/connections`, `/integrations`); the MCP transport and the UI shells. What is excluded is listed, so a new administrative endpoint is covered by default.

A record never holds the request body or the query string. A path parameter that names a person (`userId` and the like) is replaced by that person's keyed pseudonym, so erasing a user does not leave their id in a `DELETE /admin/gdpr/…` record. The records have no conversation, so they take no chain position; they are HMAC-signed like every other entry.

**MCP:** tools that act through the REST API — agent, resource, group, schedule and channel administration — reach this filter on the loopback call with the MCP caller's identity. `delete_user_data` and `export_user_data` call the GDPR service directly and are recorded by its compliance event (`GDPR_ERASURE`, `GDPR_EXPORT`), which names the subject's pseudonym but not the caller. The MCP user-memory tools are not recorded.

List them with [`GET /auditstore/admin-actions`](#list-administrative-actions). Switch the records off with `eddi.audit.admin-actions.enabled=false`.

### Keyed GDPR pseudonyms

GDPR erasure replaces a user id in the ledger with a pseudonym. Up to v4 both the signature's identity token and the stored pseudonym were `gdpr-erased:<sha256(userId)>` — an unsalted hash, so anyone with a list of candidate ids could tell whose rows were erased. v5 rows use `gdpr-erased:k1:<HMAC(key, userId)>`, computed under a key derived from the signing key, which cannot be recomputed without it; erasure writes that form into the v5 rows each key signed. Rows written before v5 still receive the unkeyed form, because their signatures cover it — changing it would make them read as tampered. The database logs keep the unkeyed form as well (see the follow-up in the changelog).

To verify entries have not been tampered with, use the verification endpoints above rather than recomputing digests by hand — verification has to pick the canonicalizer from the entry's own version tag.

### Canonical form versioning

The stored value carries the version of the canonical form it was computed over, and verification picks the canonicalizer from that tag:

| Stored value      | Canonical form | Written by                                  |
| ----------------- | -------------- | ------------------------------------------- |
| `v5:<key id>:<hex>` | v5           | current                                     |
| `v4:<hex>`        | v4             | before the signing key was named and the pseudonym keyed |
| `v3:<hex>`        | v3             | before the timestamp was signed as epoch-millis |
| `v2:<hex>`        | v2             | before the identity token and `sequence`    |
| `<hex>` (no tag)  | v1             | before delimiter escaping                   |

**v1** joined keys and values with `=`, `,`, `{}`, `[]` and `|` without escaping them, so the map-to-string mapping was not injective: `{"a": "x", "b": "y"}` and `{"a": "x,b=y"}` canonicalize to the same bytes and therefore share one valid HMAC — a tampered entry could verify as intact. That became reachable once `toolCalls` started carrying tool-trace `arguments`/`result` strings, which the model and the user write.

**v2** escapes every delimiter inside keys and scalars and type-tags every value (`s:` scalar, `m` map, `l` list, `n` null), so a string can never render like a nested structure.

**v3** changes two fields: the user identifier is signed through an identity token rather than verbatim, so a GDPR pseudonymisation no longer invalidates the signature it had, and the per-conversation `sequence` joins the signed payload, so an entry cannot be renumbered and a deletion leaves a gap verification can see.

**v4** signs the timestamp as epoch milliseconds instead of `Instant.toString()`. No backend stores the precision v3 signed — PostgreSQL's `TIMESTAMPTZ` keeps microseconds, MongoDB's `Date` keeps milliseconds — so an entry read back never carried the value that had been signed and no v3 digest recomputed cleanly. Milliseconds is the coarser floor of the two, so a v4 signature round-trips through either backend without loss.

Verification never falls back from v2 to v1 — that would hand the collision straight back — and pre-existing untagged rows keep verifying under v1, so an upgrade does not turn the historical ledger into a wall of "tampered".

### Chain sequences and multi-replica deployments

The `sequence` signed into v3/v4 is a per-conversation chain position, allocated from a
counter each node seeds by reading `MAX(sequence)` for that conversation from the store.

**That read is not an atomic reservation.** Entries sit in a node-local queue for up to
`eddi.audit.flush-interval-seconds` before the store can see them, so two nodes serving
consecutive turns of the *same conversation* inside that window both read the same maximum
and both hand out the positions after it. Neither backend indexes
`(conversationId, sequence)` uniquely, so the duplicate is stored, and
`/auditstore/verify` grades a duplicate exactly like a deletion — `BROKEN`.

> **Known limitation — operational requirement:** a multi-replica deployment needs
> **conversation affinity** (route every turn of one conversation to the same node) for chain
> integrity. Without it, duplicate sequences are produced and `/auditstore/verify` grades the
> affected conversations `BROKEN`. HMAC verification of individual entries still holds — only
> the chain-continuity check is affected.

**Deferred fix, and why it is deferred.** Removing that requirement needs storage-level
atomic allocation — PostgreSQL `UPDATE … RETURNING` on a per-conversation counter row,
MongoDB `findOneAndUpdate` with `$inc` — plus a unique `(conversationId, sequence)`
constraint and a retry on collision. It is tracked as follow-up work rather than shipped here
because it moves a store round trip from once per conversation to once per *entry*, on the
pipeline thread, and it is a schema change on both backends.

The unique constraint on its own would make things worse, not better: without the allocator
that makes collisions impossible, a rejected insert **silently drops an audit record**,
whereas the duplicate it prevents at least surfaces as a detectable `BROKEN` verdict.

**Detection in the meantime — `eddi_audit_sequence_collisions_total`.** After each flush the
ledger re-reads `MAX(sequence)` for the conversations it just wrote. If the store already
holds a position this node has not handed out yet, another replica is allocating for the same
conversation: the ledger logs a WARN naming the conversation, increments
`eddi_audit_sequence_collisions_total`, and continues its own chain past the foreign rows. An
operator running without affinity therefore sees the problem in metrics instead of
discovering it at verify time.

- Any non-zero value means "multi-replica without conversation affinity" — fix the routing.
- It is a **partial** detector: two nodes that hand out exactly the same range leave a stored
  maximum consistent with both counters, and only `/auditstore/verify` sees that duplicate.
- Cost is one indexed `MAX(sequence)` read per conversation per flush, on the ledger's writer
  thread — never on the pipeline thread.

## Secret Redaction

All string values in audit entries pass through the `SecretRedactionFilter` before storage. The following patterns are redacted:

- OpenAI API keys (`sk-...`)
- Anthropic API keys (`sk-ant-...`)
- Bearer tokens (JWTs and opaque tokens)
- Generic API key patterns (`apikey=...`, `token=...`, etc.)

A `${vault:...}` reference is deliberately **not** redacted: it is a pointer to a secret, not a secret, and the key name it carries is ordinary configuration. Leaving it legible keeps a `<REDACTED>` marker meaning what it is designed to mean — that a value embedded a secret *literal*. A resolved secret does not look like a reference and is caught by the rules above, and `${vault:key}SECRET-TAIL` is redacted as a whole rather than treated as a bare reference.

Redaction is applied recursively to nested maps and lists.

## Failure Handling

If a database write fails, entries are **re-queued** for the next flush cycle. After 3 consecutive failures, the batch is dropped from the queue and written to a **dead-letter sink** — NATS JetStream (subject `eddi.deadletter.audit`) when a connection is available, otherwise the JSONL file at `eddi.audit.dead-letter-path`.

**Queue overflow has two outcomes, and only one of them is recoverable.** Both increment `eddi_audit_entries_dropped_total`, so the counter on its own does not say which happened:

| Overflow path | What happens at `eddi.audit.max-queue-size` | Recoverable? |
| ------------- | ------------------------------------------- | ------------ |
| **Re-queue** — a failed batch coming back from the flush retry, or entries the retry budget never reached | Written to the dead-letter sink instead of growing the heap | **Yes** — replay from the sink |
| **Submit** — a fresh entry arriving from the pipeline while the queue is already full | Refused outright: counted and logged at WARN, **not** dead-lettered | **No** — the entry is gone |

The asymmetry is deliberate. A re-queued entry has already consumed its chain position, so discarding it without a record would leave a gap `/auditstore/verify` grades `BROKEN` with nothing to attribute it to — the sink is what makes that gap explicable. A refused submission has not been sequenced yet (`submit()` reserves the queue slot *before* taking a chain position, exactly so a refusal burns no number), so it leaves the chain intact and verify reports nothing at all.

> **A dropped submission is unrecoverable and invisible to chain verification.** `eddi_audit_entries_dropped_total` rising while `/auditstore/verify` still says `INTACT` is the only signal that audit evidence was lost. Alert on the counter; do not treat a clean verify as proof of completeness.

> **The dead-letter record is the whole entry, and that makes the sink a personal-data location.** It carries the `userId`, the verbatim input and output, the LLM detail and tool calls, plus the entry's own timestamp, sequence, HMAC and agent signature — anything less is not replayable, and without the `sequence` an operator cannot prove which chain positions the ledger itself abandoned, so every self-inflicted gap reads as `BROKEN` rather than `INCOMPLETE`. Secret redaction has already been applied, but user content has not.
>
> The GDPR erasure cascade redacts the ledger and pseudonymizes the database logs; it does **not** touch `eddi.audit.dead-letter-path` or the `eddi.deadletter.audit` subject. Give the sink the same access controls, encryption at rest and retention handling as the ledger, and include it in your Art. 17 procedure — see [The audit dead-letter sink holds personal data](gdpr-compliance.md#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it).
>
> The image creates the default directory (`/opt/eddi/data`) and hands it to the runtime user, and the ledger reports at startup if the configured location is not writable — the sink's unavailability should be discovered before the incident that needs it, not from an error line nested inside the error line reporting the drop.

## Storage

### MongoDB (default)

- Collection: `audit_ledger`
- Indexes: `conversationId`, `(agentId, agentVersion)`, `timestamp` (descending), `userId`, `(conversationId asc, timestamp desc)`, `(conversationId asc, sequence desc)`, `(taskId asc, timestamp desc)` — the last one backs the administrative-action listing
- Operations: `insertOne` and `insertMany`, plus the two GDPR erasure mutations — `pseudonymizeByUserId` (an `updateMany` that overwrites `userId`) and `redactEntry` (an `updateOne` per row, conditional on its stored `hmac`, that rewrites `userId`, `input`, `output`, `llmDetail`, `toolCalls`, `hmac` and `agentSignature` — see [GDPR erasure](#gdpr-erasure-redaction-not-deletion)). Nothing else mutates a stored entry, and nothing ever deletes one. Pseudonymisation alone is HMAC-preserving for v3+ rows (the signature covers the identity *token*, which is the same for an identifier and its pseudonym)

#### Building the MongoDB indexes ahead of a deploy

`userId` backs the GDPR export and erasure scans, the two `conversationId` compound indexes serve the per-conversation read's filter and sort together (which is what stops large conversations hitting MongoDB's in-memory sort limit) and back `maxSequence`, and `(taskId, timestamp)` — the newest — backs the administrative-action listing.

`AuditStore`'s constructor issues all seven `createIndex` calls synchronously, so on an existing multi-million-document `audit_ledger` the thread that first builds the bean blocks until the new ones are built. The collection stays readable and writable while they build — MongoDB 4.2+ takes the exclusive lock only briefly at the start and end — but the first request that touches the ledger after a deploy waits it out.

To take that wait out of the deploy, build them first:

```javascript
db.audit_ledger.createIndex({ userId: 1 });
db.audit_ledger.createIndex({ conversationId: 1, timestamp: -1 });
db.audit_ledger.createIndex({ conversationId: 1, sequence: -1 });
db.audit_ledger.createIndex({ taskId: 1, timestamp: -1 });
```

`createIndex` is idempotent for an identical key pattern, so the startup calls then find the indexes already present and return immediately.

### PostgreSQL

- Table: `audit_ledger` (auto-created on first use)
- Hybrid storage: indexed columns (conversation_id, agent_id, agent_version, timestamp) + JSONB for variable data
- Selected at runtime with `eddi.datastore.type=postgres` (default `mongodb`), resolved by `DataStoreProducers.auditStore(...)` — both backends ship in the same image
- Same insert-only contract as MongoDB, with the same two erasure mutations (`UPDATE … WHERE user_id = ?`, and `UPDATE … WHERE id = ? AND hmac IS NOT DISTINCT FROM ?` for a redaction)

#### Upgrading an existing PostgreSQL ledger

`idx_audit_user` on `audit_ledger (user_id)` backs the GDPR export and erasure scans — without it they are sequential scans over the largest never-pruned table in the system — and `idx_audit_task` on `(task_id, created_at DESC)` backs the administrative-action listing.

Both are built **in the background with `CREATE INDEX CONCURRENTLY`**, started once the table exists (on the first audit read or write after the deploy). Audit inserts and reads carry on while they build; until they exist, export, erasure and the listing are slower, not broken. Each build holds a PostgreSQL advisory lock, so replicas starting together do not build the same index twice. An index an interrupted build left `INVALID` is dropped and rebuilt, and a build that ends `INVALID` is retried (after 30 s, 2 min and 10 min); if it still fails, a WARN names the indexes and the next restart tries again. The database role needs `CREATE` on the table, which it already has to create it.

Earlier releases built `idx_audit_user` with a plain `CREATE INDEX` on the audit-writer thread, which blocked audit inserts for the length of the build. To check the result, or to build them yourself ahead of a deploy (the startup build then finds them and does nothing):

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_user ON audit_ledger (user_id);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_task ON audit_ledger (task_id, created_at DESC);
SELECT indexrelid::regclass, indisvalid FROM pg_index
 WHERE indexrelid IN ('idx_audit_user'::regclass, 'idx_audit_task'::regclass);
```

## Architecture

```
LifecycleManager                 ConversationService
  |                                |
  | buildAuditEntry()              | setAuditCollector()
  | (per task completion)          | (enriches with environment)
  |                                |
  v                                v
IAuditEntryCollector ---------> AuditLedgerService
                                   |
                                   | 1. scrubSecrets()
                                   | 2. computeHmac()
                                   | 3. queue.offer()
                                   |
                                   v  (every N seconds)
                                IAuditStore.appendBatch()
                                   |
                          +--------+--------+
                          |                 |
                     AuditStore     PostgresAuditStore
                     (MongoDB)        (PostgreSQL)
```
