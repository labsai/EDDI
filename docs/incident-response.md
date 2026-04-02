# Data Breach Incident Response Plan

This runbook outlines the steps for detecting, assessing, and responding
to a data breach involving the EDDI platform.

## 1. Detection

### Indicators

- Unexpected audit ledger HMAC validation failures (tamper detection)
- Unusual API access patterns in `/admin/` endpoints
- Failed authentication spikes in Keycloak logs
- Anomalous conversation volume or data export requests
- Alerts from infrastructure monitoring (Grafana/Prometheus)

### Monitoring

EDDI exposes metrics at `/q/metrics` (Prometheus format):
- `eddi.conversations.active` — active conversation count
- `eddi.tool.execution.count` — tool execution volume
- `eddi.audit.entries.count` — audit ledger write rate

## 2. Assessment (First 4 Hours)

### Scope Determination

1. **Identify affected data**: Which stores were compromised?
   - Conversation content (chat history)
   - User memories (persistent facts)
   - API keys/credentials (vault)
   - Audit trail integrity

2. **Identify affected users**: Query the GDPR export endpoint to
   enumerate affected user data:
   ```bash
   GET /admin/gdpr/{userId}/export
   ```

3. **Determine attack vector**: Check database logs for unauthorized access:
   ```bash
   GET /admin/logs?level=ERROR&limit=100
   ```

### Risk Classification

| Risk Level | Criteria | Response |
|---|---|---|
| **High** | PII exposure, credentials leaked | Full breach protocol |
| **Medium** | System data exposed, no PII | Containment + review |
| **Low** | Failed attempt, no data access | Log + monitor |

## 3. Containment (First 24 Hours)

1. **Rotate compromised credentials**:
   - Rotate all LLM API keys in the Secrets Vault
   - Invalidate affected Keycloak sessions
   - Update any exposed database credentials

2. **Isolate affected systems**:
   - Undeploy compromised agents
   - Revoke affected user tokens

3. **Preserve evidence**:
   - Export audit trail for affected conversations
   - Snapshot database logs
   - Do NOT delete audit entries (immutable by design)

## 4. Notification

### GDPR (Art. 33-34)

- **Supervisory authority**: Within **72 hours** of becoming aware
- **Data subjects**: Without undue delay if high risk to rights/freedoms
- **Processor → Controller**: EDDI operators (processors) must notify
  their data controllers immediately

### CCPA

- **Affected consumers**: Notification required for certain categories
  of personal information
- **California Attorney General**: If breach affects 500+ residents

### Template

```
Subject: Data Breach Notification

Date of discovery: [DATE]
Nature of breach: [DESCRIPTION]
Categories of data: [conversation content / user memories / credentials]
Approximate number of affected users: [COUNT]
Measures taken: [CONTAINMENT STEPS]
Contact: [DPO / PRIVACY CONTACT]
```

## 5. Recovery

1. Deploy patched version of EDDI with vulnerability remediated
2. Re-validate audit ledger integrity (HMAC chain verification)
3. Conduct post-incident review
4. Update this runbook with lessons learned

## 6. Prevention

- Enable Keycloak authentication in production
- Use RBAC (`eddi-admin`, `eddi-viewer`) for all administrative operations
- Review audit trail regularly for anomalies
- Keep EDDI updated to the latest version
- Use self-hosted LLM providers for sensitive deployments
- Enable TLS for all EDDI endpoints
- Regularly rotate API keys and vault credentials
