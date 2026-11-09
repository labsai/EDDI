## 🐛 fix(audit): agent signatures on audit entries are versioned and verified (2026-11-07)

**Repo:** EDDI (`fix/audit-agent-signature`, on top of `feat/agent-signing-key-provisioning`)

### What changed and why

The audit ledger wrote an agent signature (`agentSignature`) that nothing could use. It always signed with the agent's unversioned key, which key provisioning never writes, so a provisioned agent's entries were never signed. It ignored the agent's own signing setting, signed the entry id when the deployment had no HMAC key (which says nothing about the content), stored no key version, and `/auditstore/verify` never checked the signature.

- **Signing** moved to a new `AuditAgentSigner`. The agent version that produced the entry signs when it has `security.signInterAgentMessages` on and declares a key, with its newest key, and only when the entry carries an HMAC. The signed bytes are `eddi-audit-entry:` followed by the HMAC, and the stored value is `v<keyVersion>:<Base64 signature>`. A key that cannot be used leaves the entry unsigned and logs a WARN naming it.
- **Verification**: `/auditstore/verify` checks the agent signature of every entry whose HMAC verified, against the public key the entry's own agent version declares. A new status, `AGENT_SIGNATURE_INVALID`, counts towards `invalid` and raises `tamperingSuspected`. An entry whose agent version no longer exists (the agent was deleted permanently) reports `AGENT_KEY_UNAVAILABLE`, which is not counted as tampering (see the 2026-11-08 entry); a soft-deleted agent's entries keep verifying, because the version is read including deleted ones. If the agent store cannot be read, the sweep fails.
- **GDPR erasure** still decides whether to re-sign a row from its HMAC alone, so deleting an agent cannot leave redacted rows unverifiable.
- `AgentSigningService.sign` and `signIfKeyPresent` take the key version.
- Manager: an `AGENT_SIGNATURE_INVALID` entry is marked "Signature does not match", like an `INVALID` one. The operator prompt says how audit entries are signed (revision bump).

### Design decisions

- The key version lives in the `agentSignature` value, not in a new column. The two are only meaningful together, and so they cannot be stored, redacted or dead-lettered apart. No schema change was needed on either backend.
- The trust anchor is the entry's own agent version, which is immutable, rather than the agent's current configuration. Editing the agent later cannot make an old entry verify against a different key.
- Signing follows `signInterAgentMessages`, the one setting whose save checks the key can sign.
- A signature removed from a row cannot be covered by the HMAC, which is computed first; the 2026-11-08 entry reports it as `AGENT_SIGNATURE_MISSING` where the agent version would have signed. See the [audit ledger page](../audit-ledger.md#agent-signatures).

**Files:** [`AuditAgentSigner.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditAgentSigner.java), [`AuditLedgerService.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditLedgerService.java), [`AuditVerificationStatus.java`](../../src/main/java/ai/labs/eddi/engine/audit/AuditVerificationStatus.java), [`RestAuditStore.java`](../../src/main/java/ai/labs/eddi/engine/audit/rest/RestAuditStore.java), [`AgentSigningService.java`](../../src/main/java/ai/labs/eddi/configs/agents/AgentSigningService.java), [`audit-ledger.md`](../audit-ledger.md), [`cryptography.md`](../cryptography.md), [`configuration-reference.md`](../configuration-reference.md).

```decision-log
| 2026-11-07 | The key version of an audit agent signature is stored inside `agentSignature` (`v<n>:<sig>`), not as its own column | Making audit agent signatures verifiable needed the key version persisted | A `keyVersion` field on `AuditEntry` with a PostgreSQL column and Mongo field: a schema change on the append-only ledger for a value that is meaningless apart from the signature |
```
