# Compliance Data Flow Diagram

> **Audience**: Compliance auditors, DPOs, and deployers performing risk
> assessments. This document provides a single-page overview of how data
> flows through EDDI, where it's stored, and where encryption is applied.

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
│              │ PII: Yes  │ │ PII: Yes │  │  Write-once │  │ Envelope   │ │
│              │ Encrypted:│ │ Encrypted│  │  PII: Yes** │  │ encryption │ │
│              │ TDE*      │ │ TDE*     │  │  Encrypted: │  │            │ │
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
│              ** Audit userId is pseudonymized on GDPR erasure             │
└──────────────────────────────┬────────────────────────────────────────────┘
                               │
                               │ HTTPS (conversation content)
                               │ Only when LLM Task executes
                               ▼
                    ┌──────────────────────┐
                    │    LLM Provider      │
                    │                      │
                    │  Receives:           │
                    │  • User message      │
                    │  • Chat history      │
                    │  • System prompt     │
                    │                      │
                    │  Does NOT receive:   │
                    │  • User IDs          │
                    │  • API keys          │
                    │  • Other sessions    │
                    └──────────────────────┘
```

---

## Data Store Inventory

| Data Store | Contains PII | Encryption | Retention | Deletable | Regulatory Notes |
|---|---|---|---|---|---|
| **Conversation Memory** | ✅ userId, chat content | TDE (deployer) | 365 days default (configurable) | ✅ GDPR cascade | Primary PII store |
| **User Memory** | ✅ userId, structured facts | TDE (deployer) | Until deleted | ✅ GDPR cascade | Cross-conversation state |
| **Managed Conversations** | ✅ userId, intent mappings | TDE (deployer) | Until deleted | ✅ GDPR cascade | Routing metadata |
| **Attachments** | ✅ user-uploaded images, PDFs, audio | TDE (deployer) | With owning conversation | ✅ GDPR cascade | GridFS or PostgreSQL blobs; potential PHI |
| **Conversation Checkpoints** | ✅ copy of conversation properties | TDE (deployer) | With owning conversation | ✅ GDPR cascade | Same PII as the conversation |
| **Group Conversations** | ✅ multi-agent transcripts | TDE (deployer) | Until deleted | ✅ GDPR cascade | Group discussion content |
| **Shared Artifacts** | ✅ user/agent-authored content | TDE (deployer) | Until deleted | ✅ GDPR cascade | Owned by the creating user |
| **HITL Tool Journal** | ✅ tool name, capped tool result, approver identity | TDE (deployer) | With owning conversation | ✅ GDPR cascade | Human-approval audit trail |
| **Schedules** | ✅ userId, trigger payloads | TDE (deployer) | Until deleted | ✅ GDPR cascade | Owned by the creating user |
| **Audit Ledger** | ✅ userId (pseudonymized on erasure) | TDE (deployer) + HMAC | Indefinite | ❌ Pseudonymized only | EU AI Act Art. 17/19 |
| **Database Logs** | ✅ userId (pseudonymized on erasure) | TDE (deployer) | Configurable | ❌ Pseudonymized only | Operational data |
| **Secrets Vault** | ❌ API keys only | AES-256-GCM (application-level) | Until rotated/deleted | ✅ Via REST API | Credentials only |

---

## PII Lifecycle

```
User Input (may contain PII)
    │
    ├──▶ Stored in Conversation Memory (MongoDB/PostgreSQL)
    │        └─ Retention: configurable (default 365 days)
    │        └─ Auto-deleted after retention period
    │        └─ Or: GDPR cascade delete (immediate)
    │
    ├──▶ Extracted to User Memory (if PropertySetter configured)
    │        └─ Retention: until deleted
    │        └─ Or: GDPR cascade delete (immediate)
    │
    ├──▶ Sent to LLM Provider (if LLM task triggers)
    │        └─ Transient: not stored by EDDI after response
    │        └─ Provider retention: per provider's data policy
    │
    ├──▶ Logged in Audit Ledger (userId + task data)
    │        └─ Retention: indefinite (EU AI Act)
    │        └─ userId pseudonymized on GDPR erasure (SHA-256)
    │        └─ HMAC integrity hash prevents tampering
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
| **Audit Integrity** | HMAC-SHA256 | EDDI (derived from vault master key) | Tamper detection on audit entries |

---

## GDPR Erasure Cascade

When `DELETE /admin/gdpr/{userId}` is called:

```
 1. User Memories ─────────────── PERMANENTLY DELETED
 2. Attachments (binary blobs) ── PERMANENTLY DELETED
 3. HITL Tool Journal ─────────── PERMANENTLY DELETED
 4. Conversation Descriptors ──── PERMANENTLY DELETED
 5. Conversation Checkpoints ──── PERMANENTLY DELETED
 6. Conversation Snapshots ────── PERMANENTLY DELETED
 7. Managed Conversation Maps ─── PERMANENTLY DELETED (cache invalidated)
 8. Group Conversations ───────── PERMANENTLY DELETED
 9. Shared Artifacts ──────────── PERMANENTLY DELETED
10. Schedules ────────────────── PERMANENTLY DELETED
11. Database Logs ────────────── userId → SHA-256 PSEUDONYMIZED
12. Audit Ledger ─────────────── userId → SHA-256 PSEUDONYMIZED
    Audit Ledger Event ───────── GDPR_ERASURE entry written (immutable)
```

Steps 2–5 run before the conversation snapshots are deleted because they
reference conversation IDs that the bulk delete removes.

Steps 11–12 retain operational and compliance data with the `userId` replaced by
`AuditHmac.pseudonymFor(userId)` — a prefix plus the hex SHA-256 of the identifier.

> **This is pseudonymisation, not anonymisation, and the distinction is legal as
> well as technical.** The digest is deterministic and unsalted, so anyone holding
> a list of candidate user IDs can hash each one and match it against the stored
> value. It defeats casual browsing of the audit trail; it does not defeat an
> adversary who can enumerate or guess identifiers.
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
