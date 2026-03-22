# Audit Ledger

> **Status:** Available since v6.0.0
> **EU AI Act:** Articles 17/19 — Immutable Decision Traceability

The Audit Ledger provides a **write-once, append-only** trail of every lifecycle task execution. It captures what data each task read, what it produced, LLM-specific details (compiled prompts, model responses, token usage), tool calls, actions, costs, and timing — signed with HMAC-SHA256 for tamper detection.

## Overview

Every time a conversation turn is processed, each lifecycle task (parser, behavior rules, HTTP calls, LangChain, output, etc.) generates an audit entry. These entries are:

1. **Scrubbed** — secrets are redacted (API keys, bearer tokens, vault references)
2. **Signed** — HMAC-SHA256 computed over all fields for tamper detection
3. **Batched** — queued in-memory and flushed to the database every few seconds
4. **Immutable** — stored in a write-once collection with no update or delete operations

## Configuration

| Property                            | Default | Description                                                 |
| ----------------------------------- | ------- | ----------------------------------------------------------- |
| `eddi.audit.enabled`                | `true`  | Enable/disable the audit ledger                             |
| `eddi.audit.flush-interval-seconds` | `3`     | How often to flush queued entries to the database           |
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
| `environment`    | String  | Deployment environment (e.g., `unrestricted`)              |
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

Returns audit entries for a agent. The `agentVersion` parameter is optional.

### Get Entry Count

```
GET /auditstore/{conversationId}/count
```

Returns the total number of audit entries for a conversation.

## HMAC Integrity

When the vault master key is configured, each audit entry is signed with HMAC-SHA256:

1. A **signing key** is derived from the vault master key using PBKDF2 with a distinct salt (`eddi-audit-hmac-v1`, 600K iterations). This makes the audit signing key cryptographically independent from the vault's KEK.
2. A **canonical string** is built from all entry fields (excluding the HMAC itself), with map keys sorted alphabetically for deterministic output.
3. The HMAC is computed and stored as a hex-encoded string.

To verify an entry has not been tampered with, recompute the HMAC and compare it to the stored value.

## Secret Redaction

All string values in audit entries pass through the `SecretRedactionFilter` before storage. The following patterns are redacted:

- OpenAI API keys (`sk-...`)
- Anthropic API keys (`sk-ant-...`)
- Bearer tokens (JWTs and opaque tokens)
- Generic API key patterns (`apikey=...`, `token=...`, etc.)
- Vault references (`${eddivault:...}`)

Redaction is applied recursively to nested maps and lists.

## Failure Handling

If a database write fails, entries are **re-queued** for the next flush cycle. After 3 consecutive failures, entries are dropped and an error is logged. This prevents unbounded memory growth while maximizing data retention.

## Storage

### MongoDB (default)

- Collection: `audit_ledger`
- Indexes: `conversationId`, `(agentId, agentVersion)`, `timestamp` (descending)
- Operations: `insertOne`, `insertMany` only — no update or delete

### PostgreSQL

- Table: `audit_ledger` (auto-created on first use)
- Hybrid storage: indexed columns (conversation_id, agent_id, agent_version, timestamp) + JSONB for variable data
- Activated with `@IfBuildProfile("postgres")`
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
                     (MongoDB)        (future)
```
