## Revoke-key API description no longer says peers accept the revoked key (2026-11-18)

**Repo:** EDDI, branch `fix/revoke-key-openapi-description`.

The `@Operation` description of `revokeAgentSigningKey` (`POST /agentstore/agents/{id}/signing-keys/{keyVersion}/revoke`) still said peer verification does not refuse a revoked key yet, so a message signed with it could still verify. That has not been true since revocation became enforced: `GroupSigningGuard` withholds entries signed with a revoked key (`Verdict.REVOKED`), and audit verification reports `AGENT_KEY_REVOKED`. The description now says so, and notes that a node that already loaded the key can keep signing audit entries with it for up to 60 seconds (`AgentSigningService.KEY_CACHE_TTL_NANOS`).

- `src/main/java/ai/labs/eddi/configs/agents/IRestAgentStore.java`: description text only, no behaviour change. No checked-in OpenAPI snapshot carries the description, so nothing was regenerated.
