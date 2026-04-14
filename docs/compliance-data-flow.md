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
1. User Memories ──────────────── PERMANENTLY DELETED
2. Conversation Snapshots ─────── PERMANENTLY DELETED
3. Managed Conversation Maps ──── PERMANENTLY DELETED
4. Database Logs ──────────────── userId → SHA-256 PSEUDONYMIZED
5. Audit Ledger ───────────────── userId → SHA-256 PSEUDONYMIZED
6. Audit Ledger Event ─────────── GDPR_ERASURE entry written (immutable)
```

Steps 4–5 retain operational and compliance data but make re-identification
impossible without the original userId.

---

## See Also

- [PRIVACY.md](../PRIVACY.md) — Data processing overview
- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA deployment guide
- [eu-ai-act-compliance.md](eu-ai-act-compliance.md) — EU AI Act compliance
- [gdpr-compliance.md](gdpr-compliance.md) — GDPR/CCPA operations
- [secrets-vault.md](secrets-vault.md) — Encryption architecture
- [audit-ledger.md](audit-ledger.md) — Audit trail details
