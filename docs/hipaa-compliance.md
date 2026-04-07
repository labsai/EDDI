# HIPAA Compliance Guide

> **HIPAA applies when EDDI processes Protected Health Information (PHI)** — for
> example, when used by healthcare organizations, telehealth platforms, or
> health-related chatbots. Under HIPAA, EDDI acts as infrastructure used by a
> **Business Associate**.

This guide helps deployers configure EDDI for HIPAA-compliant operation. For
general data processing documentation, see [PRIVACY.md](../PRIVACY.md). For
GDPR/CCPA operations, see [gdpr-compliance.md](gdpr-compliance.md).

---

## HIPAA Readiness Overview

| HIPAA Safeguard | EDDI Feature | Status |
|---|---|---|
| **Access Control** (§164.312(a)) | Keycloak OIDC + RBAC roles (`admin`, `editor`, `viewer`) | ✅ Built-in |
| **Audit Controls** (§164.312(b)) | HMAC-signed immutable audit ledger | ✅ Built-in |
| **Integrity Controls** (§164.312(c)) | HMAC tamper detection on all audit entries | ✅ Built-in |
| **Person Authentication** (§164.312(d)) | Keycloak with JWT/OIDC, MFA-capable | ✅ Built-in |
| **Transmission Security** (§164.312(e)) | TLS — deployer configures | ⚠️ Deployer responsibility |
| **Encryption at Rest** (§164.312(a)(2)(iv)) | Database-level TDE — deployer configures | ⚠️ Deployer responsibility |
| **Data Disposal** (§164.310(d)(2)(i)) | GDPR cascade delete (`DELETE /admin/gdpr/{userId}`) | ✅ Built-in |
| **Incident Response** (§164.308(a)(6)) | Documented runbook ([incident-response.md](incident-response.md)) | ✅ Built-in |
| **Secret Management** | AES-256-GCM Secrets Vault with envelope encryption | ✅ Built-in |

---

## PHI Data Flow

```
End User (patient / healthcare worker)
    │
    │  HTTPS (TLS required)
    │
    ▼
┌─────────────────────────────────────────┐
│              EDDI Backend               │
│                                         │
│  ┌───────────┐    ┌──────────────────┐  │
│  │ Keycloak  │    │  Audit Ledger    │  │
│  │ (AuthN)   │    │  (HMAC-signed,   │  │
│  └───────────┘    │   write-once)    │  │
│                   └──────────────────┘  │
│  ┌───────────────────────────────────┐  │
│  │  Conversation Pipeline            │  │
│  │  Input → Parse → Rules → LLM →   │  │
│  │  Output                           │  │
│  └───────────────────────────────────┘  │
│         │                    │          │
│    ┌────▼────┐         ┌────▼────┐     │
│    │MongoDB/ │         │ Secrets │     │
│    │Postgres │         │ Vault   │     │
│    │(TDE req)│         │(AES-256)│     │
│    └─────────┘         └─────────┘     │
└──────────────┬──────────────────────────┘
               │
               │  HTTPS (BAA required with provider)
               ▼
       ┌───────────────┐
       │  LLM Provider  │
       │  (see matrix)  │
       └───────────────┘
```

**What is sent to LLM providers:**
- Current user message (may contain PHI)
- Recent conversation history (windowed — may contain PHI)
- Agent system prompt (configured by deployer, should NOT contain PHI)

**What is NOT sent:**
- User IDs or account metadata
- Data from other conversations or agents
- API keys (except the provider's own authentication key)

---

## Encryption at Rest

HIPAA requires all ePHI to be encrypted at rest. EDDI stores conversation
data and user memories in MongoDB or PostgreSQL. **The deployer must enable
database-level encryption.**

EDDI will log a startup warning if encryption has not been acknowledged:

```
COMPLIANCE: Database encryption status unknown
```

### MongoDB

Use [WiredTiger Encryption at Rest](https://www.mongodb.com/docs/manual/core/security-encryption-at-rest/)
(MongoDB Enterprise) or encrypt the underlying volume.

### PostgreSQL

Options (choose one):
- **Cloud-managed encryption**: AWS RDS encryption, Azure Database encryption,
  GCP Cloud SQL encryption — all encrypt at the storage layer
- **Full-disk encryption**: LUKS (Linux), BitLocker (Windows)
- **Tablespace encryption**: Available in PostgreSQL 16+ with third-party
  extensions

### Acknowledging Encryption

Once database encryption is configured, suppress the startup warning:

```properties
eddi.compliance.database-encryption-acknowledged=true
```

---

## Encryption in Transit

HIPAA requires encryption of ePHI during transmission. Configure TLS using
one of these approaches:

### Option 1: TLS at Reverse Proxy (Recommended)

Terminate TLS at nginx, Traefik, Caddy, or your cloud load balancer. EDDI
communicates with the proxy over localhost.

### Option 2: TLS Directly in EDDI

```properties
quarkus.http.ssl.certificate.file=/path/to/cert.pem
quarkus.http.ssl.certificate.key-file=/path/to/key.pem
quarkus.http.ssl-port=8443
```

---

## LLM Provider BAA Requirements

When conversation content containing PHI is sent to a cloud LLM provider,
that provider becomes a **sub-Business Associate**. You must have a BAA in
place.

| Provider | BAA Available? | Notes |
|---|---|---|
| **Ollama** (self-hosted) | N/A | No external transfer — recommended for HIPAA |
| **jlama** (self-hosted) | N/A | No external transfer — recommended for HIPAA |
| **Azure OpenAI** | ✅ Yes | Via Azure Enterprise Agreement; data stays in your region |
| **AWS Bedrock** | ✅ Yes | Via AWS BAA; HIPAA-eligible service |
| **Google Vertex AI** | ✅ Yes | Via Google Cloud BAA |
| **OpenAI API** | ✅ Yes | OpenAI offers BAAs for API customers (not ChatGPT) |
| **Anthropic** | ⚠️ Contact | Contact sales for BAA availability |
| **Mistral** | ⚠️ Contact | Contact sales for BAA availability |
| **Hugging Face** | ❌ Varies | Depends on model hosting — evaluate per deployment |

> **Recommendation:** For maximum HIPAA safety, use **Ollama or jlama** with
> self-hosted models. This eliminates external PHI transfer entirely.

---

## Authentication & Session Management

### Enable Keycloak

HIPAA requires person authentication (§164.312(d)). Enable Keycloak:

```bash
docker run -e QUARKUS_OIDC_TENANT_ENABLED=true \
           -e QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/eddi \
           labsai/eddi:latest
```

### Session Timeout

HIPAA requires automatic logoff after inactivity (§164.312(a)(2)(iii)).
Configure Keycloak session timeouts:

| Setting | Recommended Value | Keycloak Path |
|---|---|---|
| SSO Session Idle | 15 minutes | Realm Settings → Sessions |
| SSO Session Max | 8 hours | Realm Settings → Sessions |
| Client Session Idle | 15 minutes | Client → Advanced Settings |
| Access Token Lifespan | 5 minutes | Realm Settings → Tokens |

---

## Minimum Necessary Standard

HIPAA (§164.502(b)) requires that only the minimum necessary PHI is used for
each operation. EDDI provides several mechanisms:

1. **Conversation History Windowing**: `ConversationHistoryBuilder` limits
   the number of past turns sent to the LLM (configurable per agent)
2. **Context Selection Rules** (planned): Conditional context loading based
   on current action — sends only task-relevant data to the LLM
3. **Self-hosted models**: Eliminates external PHI exposure entirely

---

## Emergency Access Procedure

HIPAA requires emergency access procedures (§164.312(a)(2)(ii)). Document a
"break glass" process for your deployment:

1. **Emergency admin account**: Create a dedicated Keycloak account with
   `eddi-admin` role, stored in a sealed envelope or hardware security module
2. **Activation**: Two-person authorization to unseal the emergency credentials
3. **Audit**: All emergency access is logged in the immutable audit ledger
4. **Deactivation**: Rotate emergency credentials after each use

---

## Breach Notification

HIPAA breach notification timelines differ from GDPR:

| Regulation | Notify Authority | Notify Individuals |
|---|---|---|
| **HIPAA** | HHS within **60 days** | Without unreasonable delay, no later than **60 days** |
| **GDPR** | Supervisory authority within **72 hours** | Without undue delay if high risk |

For small breaches (< 500 individuals), HIPAA allows annual batch notification
to HHS. For large breaches (≥ 500), immediate notification plus media notice
in affected states.

See [incident-response.md](incident-response.md) for the full response
runbook.

---

## Deployer Checklist

As the HIPAA-covered entity or business associate deploying EDDI:

- [ ] **BAA**: Execute a Business Associate Agreement with any managed EDDI
      hosting provider
- [ ] **LLM Provider BAAs**: Execute BAAs with each cloud LLM provider used
      in your agents (or use self-hosted Ollama/jlama)
- [ ] **TLS**: Enable TLS for all EDDI endpoints (direct or via reverse proxy)
- [ ] **Database Encryption**: Enable encryption at rest on MongoDB/PostgreSQL
- [ ] **Vault Master Key**: Set `EDDI_VAULT_MASTER_KEY` (enables AES-256-GCM
      encryption for API keys and HMAC audit signing)
- [ ] **Keycloak**: Enable authentication
      (`QUARKUS_OIDC_TENANT_ENABLED=true`)
- [ ] **Session Timeouts**: Configure 15-minute idle timeout in Keycloak
- [ ] **RBAC**: Assign minimum necessary roles to each operator
- [ ] **Data Retention**: Review `eddi.conversations.deleteEndedConversationsOnceOlderThanDays`
      — reduce from 365 to minimum necessary
- [ ] **User Memory Purge**: Configure `eddi.usermemory.auto-purge-days` if
      PHI is stored in user memories
- [ ] **Emergency Access**: Document emergency access procedure with
      two-person authorization
- [ ] **Risk Assessment**: Complete HIPAA Security Risk Assessment for your
      deployment
- [ ] **Workforce Training**: Train all operators on PHI handling procedures
- [ ] **Breach Response**: Prepare breach notification plan per
      [incident-response.md](incident-response.md)

---

## See Also

- [PRIVACY.md](../PRIVACY.md) — Data processing overview
- [gdpr-compliance.md](gdpr-compliance.md) — GDPR/CCPA operations
- [security.md](security.md) — Security architecture
- [secrets-vault.md](secrets-vault.md) — Encryption and key management
- [audit-ledger.md](audit-ledger.md) — Immutable decision trail
- [incident-response.md](incident-response.md) — Breach response runbook
