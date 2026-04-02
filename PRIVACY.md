# EDDI Privacy & Data Processing

> **EDDI is a data processor.** Organizations deploying EDDI act as the
> data controller and are responsible for obtaining user consent, maintaining
> data processing agreements (DPAs), and ensuring lawful basis for processing.

## What Data EDDI Stores

| Data Category | Storage | Contains PII? | Retention |
|---|---|---|---|
| **Conversation Memory** | MongoDB / PostgreSQL | Yes (userId, chat content) | 365 days default (configurable) |
| **User Memory** | MongoDB / PostgreSQL | Yes (userId, structured facts) | Until explicitly deleted |
| **Audit Ledger** | MongoDB / PostgreSQL | Yes (userId) | Indefinite (EU AI Act) |
| **Database Logs** | MongoDB / PostgreSQL | Yes (userId) | Configurable |
| **Managed Conversations** | MongoDB / PostgreSQL | Yes (userId, intent) | Until explicitly deleted |

## Data Subject Rights

### Right to Erasure (Art. 17)

EDDI provides a unified erasure endpoint:

```
DELETE /admin/gdpr/{userId}
```

This cascades across all stores:
1. **User memories** — permanently deleted
2. **Conversation snapshots** — permanently deleted
3. **Managed conversation mappings** — permanently deleted
4. **Database logs** — userId pseudonymized (SHA-256 hash)
5. **Audit ledger** — userId pseudonymized (SHA-256 hash)

**Why pseudonymize instead of delete?** The audit ledger and logs are retained
under GDPR Art. 17(3)(e) — compliance with EU AI Act Articles 17/19 which
require immutable decision traceability for AI systems. User identifiers are
replaced with irreversible hashes, making re-identification impossible without
the original identifier.

### Right of Access / Portability (Art. 15/20)

```
GET /admin/gdpr/{userId}/export
```

Returns all user data as JSON: memories, conversations (with chat history),
and managed conversation mappings.

### MCP Tools

For AI-orchestrated compliance workflows:
- `delete_user_data` — full cascade erasure (requires `confirmation="CONFIRM"`)
- `export_user_data` — complete user data bundle

## Security Measures

- **Encryption at rest**: AES-256-GCM via Secrets Vault for API keys and credentials
- **Immutable audit trail**: HMAC-signed ledger entries for tamper detection
- **Secret redaction**: `SecretRedactionFilter` scrubs API keys, tokens, and vault
  references from audit entries before persistence
- **RBAC**: All GDPR endpoints require `eddi-admin` role
- **Input validation**: URL validation, regex injection prevention, path traversal
  protection

## Third-Party Data Transfers

EDDI sends conversation content to configured LLM providers during
AI processing. The specific provider is selected by the deployer via the
Manager UI or configuration files.

**Deployer responsibilities:**
- Select LLM providers that meet your data residency requirements
- Establish DPAs with your chosen LLM providers
- Inform users that their data is processed by third-party AI providers
- Consider self-hosted alternatives (Ollama, jlama) for sensitive deployments

EDDI supports self-hosted LLM providers (Ollama, jlama) that keep all data
on-premise with zero external transfers.

## Consent

EDDI does **not** manage user consent. As a data processor, consent is the
controller's responsibility. Deployers should:

1. Obtain appropriate consent before enabling conversational AI
2. Inform users about data processing via their privacy policy
3. Provide clear opt-out mechanisms in their application
4. Consider disabling `enableMemoryTools` unless users are explicitly informed
   that their interactions are remembered across sessions

## Data Retention

| Category | Default | Configuration |
|---|---|---|
| Ended conversations | 365 days | `eddi.conversations.deleteEndedConversationsOnceOlderThanDays` |
| Idle conversations | 90 days | `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` |
| User memories | No auto-delete | Use GDPR API or MCP tools |
| Audit ledger | No auto-delete | Retained for EU AI Act compliance |

Set retention to `-1` to disable automatic cleanup.

## Contact

For privacy-related inquiries about the EDDI platform, contact the
project maintainers at [github.com/labsai/EDDI](https://github.com/labsai/EDDI).
