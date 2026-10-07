# Vault-grant UX for new agents, and operator activation visibility

> Handoff plan, copied here on branch `feat/vault-grant-ux` as it asked. The
> **Decisions** section at the end records what the implementation chose where
> the plan left a choice open, and the contract the Manager and backend share.

## 1. The problem, observed on a real deployment

A vault secret can be restricted to a list of agents (`SecretMetadata.allowedAgents`). With
`eddi.vault.grant-enforcement=enforce` (the default) `VaultGrantGate.mayDeploy` refuses to deploy an
agent that references a secret it is not granted. That check is right, and it must stay. The way it
meets the user is not:

1. **Chicken and egg.** A grant names an agent id, and the id only exists after the agent is created.
   So every *new* agent that uses a restricted secret fails its first deploy, by construction. That
   holds for every route: the Manager's agent wizard, the setup API (`POST /administration/setup…`,
   `AgentSetupService`), MCP `create_agent`/`setup_agent`, a ZIP import, and the Platform Operator
   activation.
2. **The reason is hidden.** The refusal is written to the server log only. The API answers only
   `{"status":"ERROR"}` (`GET /administration/{env}/deploymentstatus/{id}`, also with
   `format=detailed`). The Manager shows "Deployment failed". The operator screen shows "The operator
   agent was created but failed to deploy (status: ERROR)". An operator without access to the server
   logs cannot find out why.
3. **The operator activation leaks an agent and hides it.** In `ui/manager/src/hooks/use-operator.ts`
   `assertProvisioned(result)` throws on a failed deploy **before** the `try` whose `catch` calls
   `removeSupersededAgent`. So the created operator agent is left behind, and the operator config
   variable (`platform.operator`) is never written. The operator screen therefore shows "off". A
   retry creates a **second** agent with a new id, which is again not granted, so it fails the same
   way.
4. **The operator is invisible unless the Manager activated it.** The Manager decides "is there an
   operator" only from the `platform.operator` global variable. An operator agent that exists for any
   other reason (setup API or MCP, imported, left behind by a failed activation, variable deleted)
   never shows up, can't be managed and can't be cleaned up from the UI.

## 2. Goals and non-goals

**Goals**
- REST and MCP: a deploy refused for an ungranted secret says so, in the response, with the secret
  names and the exact fix, not only in the log.
- Manager: before it happens, show that a secret is restricted. When saving or deploying an agent that
  is not granted, offer to add this agent to the grant (admin only), then deploy. This applies to
  creation flows too: create, grant, deploy, as one guided step.
- Operator: activation never leaks an agent. A grant problem is resolved inside the activation
  dialog. The operator screen finds and shows an operator agent that exists without the Manager's
  config, and offers to adopt or remove it.

**Non-goals (deliberately)**
- Do **not** weaken enforcement, change the default mode, or auto-grant silently. Granting stays an
  explicit admin action. Every path either asks a human or requires an explicit opt-in flag from an
  admin caller.
- Do **not** let an LLM widen grants. No new MCP tool that grants. The MCP `deploy_agent` error
  explains the fix, and a human applies it.
- Never propose `["*"]` automatically. The UI may *offer* it as a separate, clearly worded choice.

## 3. Design (summary of the handoff)

- **4.1 Backend:** `VaultGrantGate.check()` returning a structured result (`mayDeploy` stays a thin
  wrapper); `AgentFactory` remembers *why* a deployment failed; the deploy response and the detailed
  deployment status return it; a preflight endpoint; an opt-in `grantReferencedSecrets` on the setup
  API for admins (audited); MCP `deploy_agent` / `setup_agent` / `create_api_agent` explain the fix but
  can never grant; an atomic, idempotent append-one-agent grant endpoint.
- **4.2 Manager:** restricted-key badge in `SecretKeyPicker`; a shared `useDeployWithGrants` +
  `GrantRequiredDialog` wherever the Manager deploys (save-and-deploy, card, detail, wizard, import);
  creation flows preflight right after create; deploy errors show the reason with a "Fix" button.
- **4.3 Operator:** no leaked agent on a failed activation; a restricted `credentialKey` is resolved
  inside activation on the *same* agent; operator agents the Manager did not register are found and
  can be adopted or removed.

## 4. Acceptance criteria

1. Creating a new agent in the Manager that uses a restricted key ends deployed, after one confirm by
   an admin. No "Deployment failed" step appears in between.
2. The same via REST without the flag returns the new agent id and a body that names the secret and
   the fix. With `grantReferencedSecrets:true` (admin), it ends deployed, with an audit entry.
3. `deploymentstatus?format=detailed` for a refused agent shows `failure.code = VAULT_GRANT_MISSING`
   and the secret names.
4. Activating the operator with a restricted credential key works first time, and never leaves a
   second agent behind on retry. A failed activation leaves either no agent or exactly one, shown on
   the operator screen with a fix.
5. An operator agent created outside the Manager, or whose `platform.operator` variable is missing,
   shows on the operator screen and can be adopted or removed.
6. Enforcement is unchanged: no path grants without an admin's explicit action, and no LLM-reachable
   tool can widen a grant.

## 5. Decisions

### 5.1 The deploy response stays `200`, not `409`

The handoff preferred `409 Conflict` for a grant refusal on `POST …/deploy/{id}?waitForCompletion=true`
and allowed keeping `200` if 409 proved too disruptive. It stays **200** with `status: "ERROR"` and a
`failure` object, because:

- every other failed waited deploy (build failure, timeout) already answers 200 with `status`/`error`
  in the body, and all four in-process callers (`AgentSetupService.deployAndWait`, MCP `deploy_agent`,
  `update_agent`, `apply_agent_changes`) and the Manager branch on that body. A grant refusal answering
  differently from every other failure would make each caller handle two shapes for one outcome;
- `quarkus-eddi`'s `EddiClient` is an external consumer of this endpoint and reads the 200 body;
- the information the 409 would carry is all in `failure`, so nothing is lost.

`error` is also filled with `failure.message` when the deploy itself raised nothing, so callers that
only read `error` (MCP `update_agent`, `apply_agent_changes`) now show the reason too.

### 5.2 Shared contract

`DeploymentFailure` (only non-null fields are serialised):

```json
{
  "code": "VAULT_GRANT_MISSING",
  "message": "Agent '<id>' v1 uses vault secret(s) it is not granted: default/gemini-api-key. …",
  "secrets": [{"tenantId": "default", "keyName": "gemini-api-key", "reference": "${vault:gemini-api-key}"}],
  "fix": {
    "addAgentId": "<id>",
    "endpoints": ["POST /secretstore/secrets/default/gemini-api-key/grant/agents/<id>"],
    "dryRunFirst": true
  }
}
```

`code` is `VAULT_GRANT_MISSING` or `DEPLOYMENT_FAILED` (any other cause; `message` then carries the
exception's message). A reference the checker reports that is not a plain vault reference (an
unreadable `${connection:…}`, a reference assembled from global variables) is listed in `secrets` with
only `reference` set and has no fix endpoint.

| Endpoint | Shape |
|---|---|
| `POST /administration/{env}/deploy/{id}?version=&waitForCompletion=true` | 200 `{status, agentId, version, environment, error?, failure?}` |
| `GET /administration/{env}/deploymentstatus/{id}?version=&format=detailed` | `{status, failure?}`; `failure` only for a caller with EDIT on the agent |
| `GET /administration/{env}/deploy/{id}/preflight?version=` (EDIT) | `{agentId, version, enforcement: "ENFORCE"\|"WARN"\|"OFF", checked, ready, grantIssues: [{tenantId, keyName, reference, grantsAllAgents, allowedAgentCount, allowedAgents?}]}` |
| `POST /secretstore/secrets/{tenant}/{key}/grant/agents/{agentId}?dryRun=` (`eddi-admin`) | the `PUT …/grant` response shape plus `changed` |

- **Preflight visibility:** `allowedAgents` (the ids) is returned only to an `eddi-admin`; an editor
  gets `allowedAgentCount` and `grantsAllAgents`. The ids are not secret, but they map which other
  agents hold a credential, and only an admin can act on them (granting is admin-only).
- **`ready`** is `false` only in `enforce` mode with at least one issue. In `warn` mode the issues are
  still listed (`ready: true`) so the UI can explain the warning. In `off` mode the check does not run
  (`checked: false`, `ready: true`). A check that cannot run reports `checked: false, ready: true` —
  the gate also lets such a deploy through, so the preflight must not predict a refusal it would not
  make.
- **Append endpoint:** idempotent; a secret that already grants every agent (`*`) is never touched
  (`changed: false`). Atomic through the existing compare-and-set write
  (`ISecretProvider.updateGrant(…, expectedAllowedAgents)`), retried on a conflict, so two admins
  appending at once both land. Every real change writes an audit-ledger entry
  (`taskType: "vault"`, `taskId: "vault.grant.append"`, actor, secret, agent id, origin).
- **Setup flag:** `grantReferencedSecrets` on `SetupAgentRequest` and `CreateApiAgentRequest`; 403 for
  a non-admin caller; never exposed on MCP. `SetupResult` gains `deploymentFailure` and
  `grantedSecrets`.

### 5.3 Operator activation: `deploy:false`, grant, deploy

Of the two options in the handoff, activation provisions with `deploy:false`, resolves any grant issue
on the agent it just created (preflight → grant dialog → append), then deploys and waits. The setup API
needs no special case for the operator, and the grant decision is a visible admin step in the dialog
instead of a flag. A retry after a refused grant reuses the agent the failed attempt kept.

### 5.4 Operator marker

`provisionOperator` stamps the new agent's **descriptor description** with the
token `[eddi-platform-operator]` (`OPERATOR_DESCRIPTOR_MARKER` in
`ui/manager/src/lib/api/operator-marker.ts`; the full description is "Platform
Operator — managed from the Manager's Operator screen. [eddi-platform-operator]").
A descriptor field because `AgentConfiguration` has nowhere free-form to put one,
and because the descriptor is what the agent listing already returns — so
recognition costs no read beyond the listing. Adopting an agent stamps it too.
The stamp is best-effort: a failed PATCH never fails an activation.

Recognition (`findUnregisteredOperators` in `lib/api/operator-discovery.ts`),
cheapest first:

1. ONE descriptor listing, `filter=perator` (the backend matches name OR
   description, case-sensitively — this catches both "Operator" in a name and the
   marker), limit 100.
2. A descriptor carrying the marker is an operator.
3. Otherwise only a name matching `/operator/i` makes it a candidate (at most 10
   are read), and it counts only when its stored gate passes `gateLooksInstalled`
   AND one of its httpcalls toolsets has a `targetServerUrl` equal to this
   instance's address (the config's `apiBaseUrl`, the backend's `self-url`, or
   the browser's origin; `localhost`/`127.0.0.1` treated alike). That is what a
   pre-marker operator looks like and what an ordinary agent named "operator" is
   not.

The agent `platform.operator` points at is excluded. Each listed agent gets its
detailed deployment status (so a refused grant shows its reason) and the actions
Adopt (only when the registered operator is missing or off), Fix grant and deploy
(when not READY), and Remove. The inverse — `platform.operator` pointing at an
agent that no longer exists, or one switched on and not deployed — is a banner on
the operator screen (`RegisteredOperatorHealth`) with Clear configuration or
Fix grant and deploy / Deploy again.

Activation cancel behaviour: a refused or cancelled grant **keeps** the created
agent (not deployed, not registered, marked) and throws
`OperatorGrantPendingError` naming it; the page passes it back as `reuseAgent` on
the next attempt, which reuses it when its provisioning fingerprint (name, key,
model, prompt, scope, addresses) is unchanged and removes it first otherwise. Any
other deploy failure removes the agent. So a failed activation leaves no agent or
exactly one, and that one is listed on the operator screen with Fix and Remove.
