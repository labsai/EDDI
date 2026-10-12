## ✨ feat(vault): new agents that use a restricted secret — say why a deploy is refused, and grant in one step (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-ux`) · plan: [`planning/vault-grant-ux-plan.md`](../../planning/vault-grant-ux-plan.md)

### Why

A vault secret's grant (`allowedAgents`) lists agent ids, and an id exists only after the agent is created. So with `eddi.vault.grant-enforcement=enforce` (the default) **every new agent that uses a restricted secret fails its first deploy**, through every route: the Manager wizard, the setup API, MCP, an import, the Platform Operator's activation. The check is right and stays. What was wrong is how it met the user: the reason went to the server log only, the API answered `{"status":"ERROR"}`, and a retry (the setup API, the operator) created another agent that failed the same way.

### What changed (backend)

- **The refusal reason is kept and returned.** `VaultGrantGate.check()` returns a structured `GrantCheck` (`mayDeploy` is a thin wrapper, unchanged in behaviour). `AgentFactory` stores a `DeploymentFailure` (`VAULT_GRANT_MISSING` with the secret names and the exact grant call, or `DEPLOYMENT_FAILED` with the cause) next to the ERROR status; the next successful deploy clears it. The waited deploy returns it as `failure` (and `error`), `deploymentstatus?format=detailed` returns it to a caller with EDIT, and the log line names each secret, why a new agent hits this, and the fix.
- **An unchecked exception while building an agent no longer leaves it stuck IN_PROGRESS** until a restart; it ends in ERROR with the cause.
- **Preflight:** `GET /administration/{env}/deploy/{agentId}/preflight?version=` (EDIT) lists the ungranted secrets before deploying; grant ids only for admins, counts for editors.
- **Append one agent to a grant:** `POST /secretstore/secrets/{tenant}/{key}/grant/agents/{agentId}` (`eddi-admin`, `dryRun`). Idempotent, never touches a `*` grant, compare-and-set with retry so concurrent appends both land, audit-ledger entry per change (`VaultGrantService`).
- **Setup API:** `grantReferencedSecrets` (admin only, 403 otherwise) grants the new agent between create and deploy; `SetupResult` gains `deploymentFailure` and `grantedSecrets`. Without the flag a refused setup now names the secret and tells the caller to grant and redeploy *this* agent instead of running the setup again.
- **MCP:** `deploy_agent` returns the failure code, secret names and fix with a note that a human administrator applies it. No MCP tool can grant, and the flag is not on any MCP tool (asserted by `McpDeployGrantFailureTest`).

**Files:** [`VaultGrantGate.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantGate.java), [`VaultGrantService.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantService.java), [`DeploymentFailure.java`](../../src/main/java/ai/labs/eddi/engine/model/DeploymentFailure.java), [`DeploymentPreflight.java`](../../src/main/java/ai/labs/eddi/engine/model/DeploymentPreflight.java), [`AgentFactory.java`](../../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentFactory.java), [`RestAgentAdministration.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java), [`RestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java), [`AgentSetupService.java`](../../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`McpAdminTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java). Docs: [`secrets-vault.md`](../secrets-vault.md#new-agents-always-need-adding), [`agent-sync-guide.md`](../agent-sync-guide.md).

### Design decisions

- **The waited deploy still answers 200, not 409.** Every other failed waited deploy answers 200 with the outcome in the body, all in-process callers and quarkus-eddi read that body, and `failure` carries everything a 409 would. See the plan, §5.1.
- **Granting stays an explicit administrator action** on every path (a dialog, an admin-only flag, an admin-only endpoint); enforcement and its default are unchanged.

```decision-log
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
```

## ✨ feat(manager): grant-then-deploy dialog, restricted-key badge, and an operator that never leaks an agent (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-ux`), `ui/manager`

### What changed and why

- **One shared grant step wherever the Manager deploys** (`useDeployWithGrants` + `GrantRequiredDialog`): save-and-deploy, the agent card, agent detail, the resource page's deploy action and the agent wizard. It runs the preflight, then (for an administrator) a dry-run append, the append, and a waited deploy. A deploy that still comes back with `VAULT_GRANT_MISSING` reopens the same dialog, once. Non-administrators get the explanation and a copyable request for an admin. A failed deploy now shows its reason, with a **Fix** button for a missing grant. The agent wizard creates with `deploy: false` and settles grants before the first deploy, so a new agent never shows a failed first deploy.
- **Secret picker:** a lock badge "Restricted to N agents" on a restricted key, and a "not granted yet" note when the editor knows its owning agent (`useOwningAgent`, set by the resource page and Agent Studio).
- **Platform Operator:**
  - activation no longer leaks the created agent when its deploy fails. The old `assertProvisioned` threw before the rollback `try`.
  - a restricted model key is granted inside activation, on the same agent, and a retry reuses it.
  - operator agents the Manager did not register are listed with Adopt, Fix and Remove. The marker is `[eddi-platform-operator]` on the descriptor.
  - the preflight endpoint is on the operator's read allow-list. The append-grant endpoint is deliberately not, because no LLM may widen a grant. The prompt explains `failure`. The operator revision moved from 2 to 3.
- `ui/manager/src/test/mocks/openapi-operations.json` is refreshed from this branch's backend (6.6.0, the two new operations).

### Still open

- `pages/group-wizard.tsx` and `pages/workforce/workforce-wizard.tsx` still create member agents with `deploy: true`. Their refused deploys now carry the reason, but they do not yet go through the grant dialog.
- Re-enabling a paused operator (`reactivateOperator`) deploys without the grant dialog. If that deploy is refused, the operator screen's banner shows the reason and a Fix button.

**Files:** [`use-deploy-with-grants.ts`](../../ui/manager/src/hooks/use-deploy-with-grants.ts), [`grant-required-dialog.tsx`](../../ui/manager/src/components/secrets/grant-required-dialog.tsx), [`secret-key-picker.tsx`](../../ui/manager/src/components/shared/secret-key-picker.tsx), [`use-operator.ts`](../../ui/manager/src/hooks/use-operator.ts), [`operator-discovery.ts`](../../ui/manager/src/lib/api/operator-discovery.ts), [`unregistered-operators.tsx`](../../ui/manager/src/components/operator/unregistered-operators.tsx). Docs: [`architecture.md`](../architecture.md#a-restricted-model-key-and-operators-the-manager-did-not-register).
