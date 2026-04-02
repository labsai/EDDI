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
- **PII-safe logging**: GDPR operations log SHA-256 pseudonyms, never raw user IDs

## Third-Party Data Transfers (GDPR Art. 44-49)

EDDI sends conversation content to configured LLM providers during AI
processing. **Every conversation turn constitutes a data transfer to the
selected provider.** The specific provider is configured per agent by the
deployer.

### Supported LLM Providers and Data Locations

| Provider | Data Location | Self-Hosted? | DPA Available |
|---|---|---|---|
| **Ollama** | Your infrastructure | ✅ Yes | N/A (local) |
| **jlama** | Your infrastructure | ✅ Yes | N/A (local) |
| **Anthropic** | US/EU (varies) | ❌ No | [anthropic.com/policies](https://www.anthropic.com/policies) |
| **OpenAI** | US | ❌ No | [openai.com/policies](https://openai.com/policies) |
| **Google Gemini** | US/EU (varies) | ❌ No | [cloud.google.com/terms](https://cloud.google.com/terms) |
| **Mistral** | EU (France) | ❌ No | [mistral.ai/terms](https://mistral.ai/terms/) |
| **Azure OpenAI** | Your Azure region | Partially | Via Azure Enterprise Agreement |
| **AWS Bedrock** | Your AWS region | Partially | Via AWS Enterprise Agreement |
| **Oracle GenAI** | Your OCI region | Partially | Via Oracle Cloud Agreement |
| **Hugging Face** | Varies by model host | Partially | [huggingface.co/terms](https://huggingface.co/terms-of-service) |

### Deployer Responsibilities for LLM Data Transfers

As the data controller, you **must**:

1. **Select providers**: Choose LLM providers that meet your data residency
   and compliance requirements
2. **Establish DPAs**: Sign Data Processing Agreements with each cloud LLM
   provider you configure in EDDI
3. **Document transfers**: Record all LLM providers in your Article 30
   processing register
4. **Inform users**: Disclose in your privacy policy that conversations are
   processed by third-party AI providers
5. **Assess adequacy**: For non-EU providers, ensure adequate safeguards
   (Standard Contractual Clauses, adequacy decisions, or binding corporate
   rules) per GDPR Art. 46
6. **Consider self-hosting**: For maximum data sovereignty, use Ollama or
   jlama with self-hosted models — zero external data transfers

### What Data is Sent to LLM Providers

| Data Type | Sent? | Notes |
|---|---|---|
| User messages | ✅ Yes | The current turn's input |
| Conversation history | ✅ Yes | Recent conversation context (windowed) |
| System prompt | ✅ Yes | Agent instructions (configured by deployer) |
| User ID | ❌ No | Not included in LLM requests |
| API keys | ❌ No | Only the provider's own key for authentication |

EDDI does **not** send user IDs, metadata, or data from other conversations
to LLM providers. Only the conversation context relevant to the current agent
interaction is transmitted.

## Consent (GDPR Art. 6/7)

EDDI does **not** manage user consent. As a data processor, consent is the
controller's responsibility.

### Legal Basis

EDDI's data processing activities and their typical legal bases:

| Processing Activity | Suggested Legal Basis | Notes |
|---|---|---|
| Conversation processing | Art. 6(1)(a) Consent or Art. 6(1)(b) Contract | Controller determines |
| Persistent user memory | Art. 6(1)(a) Consent | Users should be informed |
| Audit ledger retention | Art. 6(1)(c) Legal obligation | EU AI Act Art. 17/19 |
| System logging | Art. 6(1)(f) Legitimate interest | Operational necessity |

### Controller Obligations

Deployers should:

1. Obtain appropriate consent before enabling conversational AI
2. Inform users about data processing via their privacy policy
3. Provide clear opt-out mechanisms in their application
4. Consider disabling `enableMemoryTools` unless users are explicitly informed
   that their interactions are remembered across sessions
5. Document the legal basis for each processing activity in your Article 30
   register

## CCPA Compliance

### Do Not Sell (§1798.120)

EDDI does **not sell personal information** and has no mechanism to do so.
EDDI is middleware infrastructure — it processes data on behalf of the
deployer (controller) and does not share, sell, or monetize user data with
any third party for commercial purposes.

If your deployment scenario involves sharing user data with third parties
(e.g., analytics providers), this is the controller's responsibility to
manage and disclose.

### Right to Know (§1798.100)

The GDPR export endpoint (`GET /admin/gdpr/{userId}/export`) satisfies the
CCPA "right to know" requirement by providing all personal information
collected about a consumer in a structured, machine-readable format.

### Right to Delete (§1798.105)

The GDPR erasure endpoint (`DELETE /admin/gdpr/{userId}`) satisfies the
CCPA "right to delete" requirement.

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
