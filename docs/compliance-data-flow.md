# Compliance Data Flow Diagram

> **Audience**: Compliance auditors, DPOs, and deployers performing risk
> assessments. This is the **canonical data inventory** for EDDI: how data
> flows through it, where it is stored, what leaves the deployment, and what an
> erasure does to each store. The [GDPR](gdpr-compliance.md),
> [HIPAA](hipaa-compliance.md) and [incident response](incident-response.md)
> guides link here instead of repeating it.

---

## System Data Flow

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                                EDDI Platform                                │
│                                                                             │
│  ┌──────────┐    ┌────────────────┐    ┌──────────────────────────────────┐ │
│  │ Keycloak │───▶│  REST API /    │───▶│     Conversation Pipeline        │ │
│  │  (OIDC)  │    │  SSE / MCP     │    │                                  │ │
│  │          │    │                │    │  Input → Parser → Behavior Rules │ │
│  │ JWT auth │    │  TLS required  │    │  → LLM Task → Output Generation │ │
│  └──────────┘    └────────────────┘    └──────────┬───────────────────────┘ │
│                                                   │                         │
│                    ┌──────────────────────────────┼──────────────────┐      │
│                    │              │               │                  │      │
│              ┌─────▼─────┐ ┌─────▼────┐  ┌──────▼──────┐  ┌───────▼────┐ │
│              │ Conversa- │ │  User    │  │   Audit     │  │  Secrets   │ │
│              │ tion      │ │ Memory   │  │   Ledger    │  │  Vault     │ │
│              │ Memory    │ │ Store    │  │             │  │            │ │
│              │           │ │          │  │  HMAC-signed│  │ AES-256-GCM│ │
│              │ PII: Yes  │ │ PII: Yes │  │  (when a key│  │ Envelope   │ │
│              │ Encrypted:│ │ Encrypted│  │  is set)    │  │ encryption │ │
│              │ TDE*      │ │ TDE*     │  │  PII: Yes** │  │            │ │
│              │           │ │          │  │  TDE*       │  │ PII: No    │ │
│              └─────┬─────┘ └─────┬───┘  └──────┬──────┘  └────────────┘ │
│                    │             │              │                         │
│                    └─────────────┼──────────────┘                         │
│                                 │                                         │
│                          ┌──────▼──────┐                                  │
│                          │  MongoDB /  │                                  │
│                          │ PostgreSQL  │                                  │
│                          │             │                                  │
│                          │ TDE* = DB-  │                                  │
│                          │ level       │                                  │
│                          │ encryption  │                                  │
│                          └─────────────┘                                  │
│                                                                           │
│   ** Audit rows are kept on GDPR erasure; their content is redacted and   │
│      the userId pseudonymized (see "What an erasure does" below)          │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │
                               │ Configured transport (https:// or http://
                               │ base URL) — conversation content LEAVES
                               │ the deployment whenever an LLM call runs
                               ▼
                    ┌──────────────────────┐
                    │    LLM Provider      │
                    │  (per agent config)  │
                    │                      │
                    │  See "What reaches   │
                    │  the LLM provider"   │
                    └──────────────────────┘
```

---

## What reaches the LLM provider

**Conversation content leaves your deployment.** Every LLM call sends data to
the provider configured on the agent — unless that provider is self-hosted
(Ollama, jlama, or an OpenAI-compatible server you run), it is a third party
and a processor (GDPR) or sub-Business Associate (HIPAA) that needs a contract.
EDDI does not store what it sends beyond its own conversation memory and audit
ledger, but the provider's retention is governed by the provider's terms.

**Transport security is the configured endpoint's.** The hosted providers' SDK
defaults are `https://`, but a `baseUrl` an agent configures (Ollama, an
OpenAI-compatible server, a proxy) is used as given — EDDI blocks cloud-metadata
addresses there, not plain HTTP. A remote `http://` base URL sends the prompt,
the history and tool results unencrypted. Use `https://` for any endpoint
reached over a network you do not control.

**Always sent, on every LLM call:**

- the agent's system prompt, **after templating**
- the current user message, and any attachment passed to a multimodal model
- the conversation history window of **this** conversation (rolling summaries
  included, when the agent uses them)
- the names, descriptions and argument schemas of the tools the agent may call

**Sent when the agent's configuration makes it so:**

| Source | When | What it can contain |
|---|---|---|
| Prompt templates | the system prompt or a tool body references it | `{properties.*}` — the user's persistent memories are loaded into the properties at conversation start, including `global` memories **other agents** wrote about the same user and `group` memories of the conversation's group; `{userInfo.userId}`; `{conversationInfo.*}`, `{context.*}`, `{snippets.*}`, `{vars.*}` |
| Memory tools (`enableMemoryTools`) | the model calls `recallMemories` / `searchMemory` | this agent's own memories, plus every `global` memory any agent wrote about the user and the group's `group` memories |
| RAG | a knowledge base is attached | chunks retrieved from the ingested documents |
| HTTP calls, MCP and A2A tools | the model calls the tool | the tool's response, whatever the external service returns |
| Group discussions | the agent is a group member | other members' contributions and the group context |
| Background LLM jobs | configured on the agent or group | conversation summarization sends conversation history; [Dream](user-memory.md#dream-consolidation) consolidation sends the user's memories; cascade judges and group facilitators/summarizers send the turn they assess |

**Not sent by EDDI on its own:** a user identifier or account metadata (EDDI
sets no `user` field on provider requests — an identifier reaches the provider
only if a template or a tool result puts it into the text), other users'
conversations, vault secrets (unless a template resolves one into prompt text)
and API keys other than the provider's own authentication.

"No data from other agents reaches the LLM" is therefore **not** a property of
EDDI: `global` user memories are shared across agents by design, and a
`longTerm` property of an agent **without a `userMemoryConfig`** is stored as
`global` — so by default it is loaded into every other agent's conversations
with that user. To keep an agent's memories private, give it a
`userMemoryConfig` (its `defaultVisibility` is `self`) and leave `global` out of
`guardrails.allowedVisibilities` (the default) — and check what the agents you
deploy alongside it write as `global`.

Provider data residency: [PRIVACY.md](../PRIVACY.md); provider BAAs:
[HIPAA guide](hipaa-compliance.md#llm-provider-baa-requirements).

---

## Data Store Inventory

| Data Store | Contains PII | Encryption | Retention | On GDPR erasure | Regulatory Notes |
|---|---|---|---|---|---|
| **Conversation Memory** | ✅ userId, chat content | TDE (deployer) | Ended conversations: 365 days default (`eddi.conversations.deleteEndedConversationsOnceOlderThanDays`) | ✅ Deleted | Primary PII store |
| **User Memory** | ✅ userId, structured facts | TDE (deployer) | Kept until deleted; `eddi.usermemories.deleteOlderThanDays` (default `-1`, off) | ✅ Deleted | Cross-conversation state |
| **Managed Conversations** | ✅ userId, intent mappings | TDE (deployer) | Until deleted | ✅ Deleted | Routing metadata |
| **Attachments** | ✅ user-uploaded images, PDFs, audio | TDE (deployer) | With owning conversation | ✅ Deleted | GridFS or PostgreSQL blobs; potential PHI |
| **Conversation Checkpoints** | ✅ copy of conversation properties | TDE (deployer) | With owning conversation | ✅ Deleted | Same PII as the conversation |
| **Group Conversations** | ✅ multi-agent transcripts | TDE (deployer) | Until deleted | ✅ Deleted | Group discussion content |
| **Shared Artifacts** | ✅ user/agent-authored content | TDE (deployer) | Until deleted | ✅ Deleted | Owned by the creating user |
| **HITL Tool Journal** | ✅ tool name, capped tool result, approver identity | TDE (deployer) | With owning conversation | ✅ Deleted | Human-approval audit trail |
| **Schedules** | ✅ userId, trigger payloads | TDE (deployer) | Until deleted | ✅ Deleted | Owned by the creating user |
| **Audit Ledger** | ✅ userId, prompts, responses, LLM detail, tool calls; admin actions name their actor | TDE (deployer) + HMAC (when a key is set) | Indefinite — no application-level expiry | ⚠️ Rows kept; content **redacted**, userId **pseudonymized** (`eddi.audit.erasure-mode`) | EU AI Act Arts. 12/19 record-keeping |
| **Audit dead-letter sink** | ✅ whole audit entries | Deployer | Never expired by EDDI | ❌ Not reached | Only written when the ledger cannot persist; see [GDPR guide](gdpr-compliance.md#the-audit-dead-letter-sink-holds-personal-data-and-erasure-does-not-reach-it) |
| **Database Logs** | ✅ userId; WARN/ERROR message text | TDE (deployer) | `eddi.logs.db-retention-days` (default `-1`: kept until deleted) | ⚠️ userId pseudonymized; message text kept | Operational data |
| **Secrets Vault** | ❌ API keys only (plus `scope: "secret"` property values, deleted on erasure) | AES-256-GCM (application-level) | Until rotated/deleted | ✅ Auto-vaulted user secrets deleted | Credentials only |

---

## PII Lifecycle

```
User Input (may contain PII)
    │
    ├──▶ Stored in Conversation Memory (MongoDB/PostgreSQL)
    │        └─ Ended conversations deleted after the retention period (default 365 days)
    │        └─ Or: GDPR erasure (immediate)
    │
    ├──▶ Extracted to User Memory (property setter longTerm, memory tools)
    │        └─ Retention: until deleted (optional age-based sweep)
    │        └─ Or: GDPR erasure (immediate)
    │
    ├──▶ Sent to the LLM provider (whenever an LLM call runs — see above)
    │        └─ Not stored by EDDI beyond its own memory and audit ledger
    │        └─ Provider retention: per the provider's terms
    │
    ├──▶ Recorded in the Audit Ledger (userId + task input/output/LLM detail/tool calls)
    │        └─ Retention: indefinite (EU AI Act record-keeping)
    │        └─ GDPR erasure: rows kept, content redacted, userId pseudonymized
    │        └─ HMAC integrity hash (when a key is configured) detects tampering
    │
    ├──▶ Mentioned in Database Logs (WARN/ERROR entries for the turn)
    │        └─ Retention: eddi.logs.db-retention-days (off by default)
    │        └─ GDPR erasure: userId pseudonymized
    │
    └──▶ Secret-scoped values → Secrets Vault
             └─ Vault reference replaces plaintext in memory
             └─ Raw input scrubbed from conversation step
```

---

## Encryption Summary

| Layer | Mechanism | Managed By | Covers |
|---|---|---|---|
| **In Transit** | TLS 1.2+ | Deployer (reverse proxy or direct) | All HTTP/SSE/MCP traffic |
| **At Rest (credentials)** | AES-256-GCM envelope encryption | EDDI Secrets Vault | API keys, tokens, passwords |
| **At Rest (data)** | Transparent Data Encryption (TDE) | Deployer (database config) | Conversations, memories, audit, logs |
| **Audit Integrity** | HMAC-SHA256 | EDDI — **only when** `EDDI_VAULT_MASTER_KEY` or `EDDI_AUDIT_HMAC_KEY` is set; `eddi.compliance.audit-signing-required=true` makes a missing key a startup failure | Tamper detection on audit entries |

---

## What an erasure does

`DELETE /admin/gdpr/{userId}` deletes the user's data from every store above
marked "Deleted" — the step-by-step list, the response fields and how to read
`complete` are in the [GDPR guide](gdpr-compliance.md#1-right-to-erasure-gdpr-art-17--ccpa-1798105).
Two stores are kept rather than deleted:

- **Audit ledger** — every row stays, so the record of what ran and when, and
  the per-conversation chain, survive. The row's prompt, response, LLM detail
  and tool calls are replaced by a redaction marker and its userId by a keyed
  pseudonym; a row that verified before is re-signed so it still verifies. See
  [audit-ledger.md](audit-ledger.md#gdpr-erasure-redaction-not-deletion).
- **Database logs** — the userId is replaced by `gdpr-erased:<sha256(userId)>`.
  The log message text is not rewritten; bound it with
  `eddi.logs.db-retention-days`.

> **This is pseudonymisation, not anonymisation, and the distinction is legal as
> well as technical.** The database-log pseudonym is a deterministic, unsalted
> digest: anyone holding a list of candidate user IDs can hash each one and match
> it against the stored value. The audit ledger's v5 pseudonym is keyed
> (`gdpr-erased:k1:<HMAC(key, userId)>`) and cannot be recomputed without the
> ledger's key — but whoever holds the key can.
>
> Under GDPR Art. 4(5) pseudonymised data remains **personal data** and stays in
> scope. Do not treat these records as anonymised, and do not disclose them on
> that basis.

---

## See Also

- [PRIVACY.md](../PRIVACY.md) — Data processing overview
- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA deployment guide
- [eu-ai-act-compliance.md](eu-ai-act-compliance.md) — EU AI Act compliance
- [gdpr-compliance.md](gdpr-compliance.md) — GDPR/CCPA operations
- [secrets-vault.md](secrets-vault.md) — Encryption architecture
- [audit-ledger.md](audit-ledger.md) — Audit trail details
