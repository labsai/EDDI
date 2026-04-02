# GDPR Compliance Guide

This guide helps EDDI operators handle data subject requests and meet
GDPR/CCPA requirements.

## Handling Data Subject Requests

### 1. Right to Erasure (Art. 17)

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
  "completedAt": "2024-01-15T10:30:00Z"
}
```

**Via MCP:** Use the `delete_user_data` tool with `confirmation="CONFIRM"`.

### 2. Right of Access (Art. 15) / Data Portability (Art. 20)

```bash
curl https://your-eddi-instance/admin/gdpr/{userId}/export \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -o user-data.json
```

**Via MCP:** Use the `export_user_data` tool.

### 3. Timeline

- **GDPR**: Respond within 30 days (extendable to 90 days for complex requests)
- **CCPA**: Respond within 45 days (extendable to 90 days)
- EDDI's erasure operation completes in seconds

## Retention Configuration

```properties
# Auto-delete ended conversations after N days (default: 365, -1 to disable)
eddi.conversations.deleteEndedConversationsOnceOlderThanDays=365

# Close idle conversations after N days (default: 90)
eddi.conversations.maximumLifeTimeOfIdleConversationsInDays=90

# Dev environment uses 90 days
%dev.eddi.conversations.deleteEndedConversationsOnceOlderThanDays=90
```

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
- [ ] **Consent**: Obtain consent before enabling conversational AI
- [ ] **DPAs**: Establish DPAs with your chosen LLM providers
- [ ] **User Notice**: Inform users that conversations are processed by AI
- [ ] **Retention**: Review default 365-day retention — adjust if needed
- [ ] **Memory Disclosure**: If using `enableMemoryTools`, inform users their
      interactions are remembered across sessions
- [ ] **Provider Selection**: Choose LLM providers that meet your data
      residency requirements
- [ ] **Erasure Process**: Document your internal process for handling DSARs
      using the GDPR API
- [ ] **Breach Response**: Prepare a breach notification plan (see
      [incident-response.md](incident-response.md))

## LLM Provider Considerations

EDDI sends conversation content to the LLM provider configured per agent.
You choose the provider via the Manager UI:

| Provider | Data Location | Self-Hosted? |
|---|---|---|
| Ollama | Your infrastructure | ✅ Yes |
| jlama | Your infrastructure | ✅ Yes |
| Anthropic | US/EU (varies) | ❌ No |
| OpenAI | US | ❌ No |
| Google Gemini | US/EU (varies) | ❌ No |
| Azure OpenAI | Your Azure region | Partially |
| AWS Bedrock | Your AWS region | Partially |
| Oracle GenAI | Your OCI region | Partially |

For maximum data sovereignty, use Ollama or jlama with self-hosted models.
