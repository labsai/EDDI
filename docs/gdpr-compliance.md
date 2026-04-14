# GDPR Compliance Guide

This guide helps EDDI operators handle data subject requests and meet
GDPR/CCPA requirements. For an overview of data processing, see
[PRIVACY.md](../PRIVACY.md).

## Handling Data Subject Requests

### 1. Right to Erasure (GDPR Art. 17 / CCPA §1798.105)

When you receive an erasure request:

```bash
# Via REST API
curl -X DELETE https://your-eddi-instance/admin/gdpr/{userId} \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

The response includes per-store counts:
```json
{
  "userId": "user-123",
  "memoriesDeleted": 15,
  "conversationsDeleted": 8,
  "conversationMappingsDeleted": 3,
  "logsPseudonymized": 42,
  "auditEntriesPseudonymized": 156,
  "completedAt": "2026-04-02T15:30:00Z"
}
```

**Via MCP:** Use the `delete_user_data` tool with `confirmation="CONFIRM"`.

**What happens:**
1. User memories — **permanently deleted**
2. Conversation snapshots — **permanently deleted**
3. Managed conversation mappings — **permanently deleted**
4. Database logs — userId **pseudonymized** (SHA-256 hash)
5. Audit ledger — userId **pseudonymized** (SHA-256 hash)

### 2. Right of Access (GDPR Art. 15) / Data Portability (Art. 20) / Right to Know (CCPA §1798.100)

```bash
curl https://your-eddi-instance/admin/gdpr/{userId}/export \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -o user-data.json
```

The export includes all user data in a structured, machine-readable JSON format:
- All persistent user memories
- All conversation transcripts (with full chat history)
- All managed conversation mappings (intent→conversation bindings)
- All audit processing records (capped at 10,000 entries)

**Via MCP:** Use the `export_user_data` tool.

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
- Restriction status is stored as a user memory entry
- All restriction/unrestriction events are logged in the audit ledger

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

# Close idle conversations after N days (default: 90)
eddi.conversations.maximumLifeTimeOfIdleConversationsInDays=90

# User memories — delete entries older than N days (default: -1, disabled)
eddi.usermemories.deleteOlderThanDays=-1

# Audit ledger — delete entries older than N days (default: -1, disabled)
# WARNING: EU AI Act requires indefinite audit retention. Only enable if
# your jurisdiction explicitly requires time-limited retention.
eddi.audit.retentionDays=-1
```

**Per-category retention** allows different retention periods for:
- **Conversations** — 365 days (default)
- **User memories** — disabled by default (configure per-deployment)
- **Audit entries** — disabled by default (retained for EU AI Act compliance)

**Data minimization (Art. 5(1)(e)):** Review the default retention periods
and reduce them to the minimum necessary for your use case.

## Audit Ledger Legal Basis

The EDDI audit ledger is retained indefinitely under two legal bases:

1. **GDPR Art. 17(3)(e)** — Compliance with a legal obligation
2. **EU AI Act Articles 17/19** — Immutable decision traceability for AI systems

Upon erasure requests, userId fields in audit entries are pseudonymized
(replaced with a SHA-256 hash). The audit data structure, timestamps, and
decision records remain intact for regulatory compliance.

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
- [ ] **Retention**: Review default 365-day retention — adjust if needed
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

EDDI sends conversation content to the LLM provider configured per agent.
You choose the provider via the Manager UI or configuration files.

| Provider | Data Location | Self-Hosted? |
|---|---|---|
| Ollama | Your infrastructure | ✅ Yes |
| jlama | Your infrastructure | ✅ Yes |
| Anthropic | US/EU (varies) | ❌ No |
| OpenAI | US | ❌ No |
| Google Gemini | US/EU (varies) | ❌ No |
| Mistral | EU (France) | ❌ No |
| Azure OpenAI | Your Azure region | Partially |
| AWS Bedrock | Your AWS region | Partially |
| Oracle GenAI | Your OCI region | Partially |
| Hugging Face | Varies by model host | Partially |

**For maximum data sovereignty**, use Ollama or jlama with self-hosted models.

**What is sent to LLM providers:**
- The current user message
- Recent conversation history (windowed)
- Agent system prompt

**What is NOT sent:**
- User IDs or account metadata
- Data from other conversations or agents
- API keys (except the provider's own authentication key)

## CCPA-Specific Requirements

### Do Not Sell (§1798.120)

EDDI does **not sell personal information** and has no mechanism to do so.
Document this in your CCPA privacy notice.

### Right to Know (§1798.100)

Use the export endpoint (`GET /admin/gdpr/{userId}/export`) to fulfill
"right to know" requests. The response includes all categories of personal
information collected.

### Right to Delete (§1798.105)

Use the erasure endpoint (`DELETE /admin/gdpr/{userId}`) to fulfill
"right to delete" requests. The cascade covers all data stores.

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
