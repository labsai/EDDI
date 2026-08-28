# Audit Ledger

> **Status:** Available since v6.0.0
> **EU AI Act:** Articles 17/19 — Immutable Decision Traceability

The Audit Ledger provides a **write-once, append-only** trail of every lifecycle task execution. It captures what data each task read, what it produced, LLM-specific details (compiled prompts, model responses, token usage), tool calls, actions, costs, and timing — signed with HMAC-SHA256 for tamper detection.

## Overview

Every time a conversation turn is processed, each lifecycle task (parser, behavior rules, HTTP calls, LangChain, output, etc.) generates an audit entry. These entries are:

1. **Scrubbed** — secrets are redacted (API keys, bearer tokens); vault references are left legible
2. **Signed** — HMAC-SHA256 computed over all fields for tamper detection
3. **Batched** — queued in-memory and flushed to the database every few seconds
4. **Immutable** — stored in a write-once collection with no update or delete operations

## Configuration

| Property                            | Default | Description                                                 |
| ----------------------------------- | ------- | ----------------------------------------------------------- |
| `eddi.audit.enabled`                | `true`  | Enable/disable the audit ledger                             |
| `eddi.audit.flush-interval-seconds` | `3`     | How often to flush queued entries to the database           |
| `eddi.audit.max-queue-size`         | `100000` | Bound on the in-memory queue. Entries arriving past the bound are **dropped** and counted on `eddi_audit_entries_dropped_total` — only the flush-retry path dead-letters |
| `eddi.audit.dead-letter-path`       | `/opt/eddi/data/eddi-audit-deadletter.jsonl` | File-based dead-letter log, used when NATS is unavailable |
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

When the vault master key is configured, each audit entry is signed with HMAC-SHA256:

1. A **signing key** is derived from the vault master key using PBKDF2 with a distinct salt (`eddi-audit-hmac-v1`, 600K iterations). This makes the audit signing key cryptographically independent from the vault's KEK.
2. A **canonical string** is built from all entry fields (excluding the HMAC itself), with map keys sorted alphabetically for deterministic output. Nested maps and lists are canonicalized recursively.
3. The HMAC is computed and stored as `v4:<64 hex chars>`. The v4 canonical form signs the user identifier as an identity token rather than verbatim (so a GDPR pseudonymisation does not invalidate the signature it had), includes the per-conversation `sequence`, and signs the timestamp as epoch milliseconds truncated to milliseconds.

To verify entries have not been tampered with, use the verification endpoints above rather than recomputing digests by hand — verification has to pick the canonicalizer from the entry's own version tag.

### Canonical form versioning

The stored value carries the version of the canonical form it was computed over, and verification picks the canonicalizer from that tag:

| Stored value      | Canonical form | Written by                                  |
| ----------------- | -------------- | ------------------------------------------- |
| `v4:<hex>`        | v4             | current                                     |
| `v3:<hex>`        | v3             | before the timestamp was signed as epoch-millis |
| `v2:<hex>`        | v2             | before the identity token and `sequence`    |
| `<hex>` (no tag)  | v1             | before delimiter escaping                   |

**v1** joined keys and values with `=`, `,`, `{}`, `[]` and `|` without escaping them, so the map-to-string mapping was not injective: `{"a": "x", "b": "y"}` and `{"a": "x,b=y"}` canonicalize to the same bytes and therefore share one valid HMAC — a tampered entry could verify as intact. That became reachable once `toolCalls` started carrying tool-trace `arguments`/`result` strings, which the model and the user write.

**v2** escapes every delimiter inside keys and scalars and type-tags every value (`s:` scalar, `m` map, `l` list, `n` null), so a string can never render like a nested structure.

**v3** changes two fields: the user identifier is signed through an identity token rather than verbatim, so a GDPR pseudonymisation no longer invalidates the signature it had, and the per-conversation `sequence` joins the signed payload, so an entry cannot be renumbered and a deletion leaves a gap verification can see.

**v4** signs the timestamp as epoch milliseconds instead of `Instant.toString()`. No backend stores the precision v3 signed — PostgreSQL's `TIMESTAMPTZ` keeps microseconds, MongoDB's `Date` keeps milliseconds — so an entry read back never carried the value that had been signed and no v3 digest recomputed cleanly. Milliseconds is the coarser floor of the two, so a v4 signature round-trips through either backend without loss.

Verification never falls back from v2 to v1 — that would hand the collision straight back — and pre-existing untagged rows keep verifying under v1, so an upgrade does not turn the historical ledger into a wall of "tampered".

## Secret Redaction

All string values in audit entries pass through the `SecretRedactionFilter` before storage. The following patterns are redacted:

- OpenAI API keys (`sk-...`)
- Anthropic API keys (`sk-ant-...`)
- Bearer tokens (JWTs and opaque tokens)
- Generic API key patterns (`apikey=...`, `token=...`, etc.)

A `${vault:...}` reference is deliberately **not** redacted: it is a pointer to a secret, not a secret, and the key name it carries is ordinary configuration. Leaving it legible keeps a `<REDACTED>` marker meaning what it is designed to mean — that a value embedded a secret *literal*. A resolved secret does not look like a reference and is caught by the rules above, and `${vault:key}SECRET-TAIL` is redacted as a whole rather than treated as a bare reference.

Redaction is applied recursively to nested maps and lists.

## Failure Handling

If a database write fails, entries are **re-queued** for the next flush cycle. After 3 consecutive failures, the batch is dropped from the queue and written to a **dead-letter sink** — NATS JetStream (subject `eddi.deadletter.audit`) when a connection is available, otherwise the JSONL file at `eddi.audit.dead-letter-path`. The re-queue path respects the bound set by `eddi.audit.max-queue-size`: entries that no longer fit go to the same sink instead of growing the heap. This prevents unbounded memory growth while keeping the missing entries recoverable rather than lost.

## Storage

### MongoDB (default)

- Collection: `audit_ledger`
- Indexes: `conversationId`, `(agentId, agentVersion)`, `timestamp` (descending)
- Operations: `insertOne`, `insertMany` only — no update or delete

### PostgreSQL

- Table: `audit_ledger` (auto-created on first use)
- Hybrid storage: indexed columns (conversation_id, agent_id, agent_version, timestamp) + JSONB for variable data
- Selected at runtime with `eddi.datastore.type=postgres` (default `mongodb`), resolved by `DataStoreProducers.auditStore(...)` — both backends ship in the same image
- Same insert-only contract as MongoDB

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
