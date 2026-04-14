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
- **Processor → Controller**: If you use EDDI as a managed service,
  ensure your hosting provider notifies you immediately upon discovering
  a breach. If you self-host, you are both controller and processor.

### CCPA

- **Affected consumers**: Notification required for certain categories
  of personal information
- **California Attorney General**: If breach affects 500+ residents

### HIPAA (§164.408)

- **HHS (Secretary)**: Within **60 days** of discovery
- **Affected individuals**: Without unreasonable delay, no later than
  **60 days** after discovery
- **Media**: If breach affects ≥ 500 residents of a state, notify
  prominent media outlets in that state
- **Small breaches** (< 500 individuals): May be reported annually to HHS
  in a batch submission

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

## 7. Emergency Access Procedure (HIPAA §164.312(a)(2)(ii))

For healthcare deployments, maintain a documented "break glass" procedure:

1. **Emergency admin account**: Create a dedicated Keycloak account with
   `eddi-admin` role, stored in a sealed envelope or hardware security
   module (HSM)
2. **Activation**: Require two-person authorization to unseal the
   emergency credentials
3. **Audit**: All emergency access is logged in the immutable audit ledger
   — EDDI records every API call, tool invocation, and data access
4. **Deactivation**: Rotate emergency credentials immediately after each
   use via Keycloak admin console
5. **Documentation**: Log the reason for emergency access, duration, and
   actions taken

## See Also

- [hipaa-compliance.md](hipaa-compliance.md) — HIPAA deployment guide
- [gdpr-compliance.md](gdpr-compliance.md) — GDPR/CCPA compliance
- [security.md](security.md) — Security architecture
- [audit-ledger.md](audit-ledger.md) — Immutable audit trail
