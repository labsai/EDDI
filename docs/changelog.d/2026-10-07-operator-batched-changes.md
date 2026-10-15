## ✨ feat(operator): batched approvals and a REST cascade — a knowledge-base binding takes two approvals, not five (2026-10-07)

**Repo:** EDDI (`feat/operator-batched-changes`)

### What changed and why

The Platform Operator performed a multi-step change one gated call per turn, so the admin approved every step separately: connecting a knowledge base to an agent was five approvals (write the LLM config, write the workflow, repoint the workflow, repoint the agent, deploy). Approval binds to the exact resolved request ("request pinning"), so a step that depends on an earlier step's result cannot be pre-approved; steps that do not depend on each other can share one pause. Two changes use that:

1. **Parallel tool calls, planned first.** The operator's prompt now has a delimited block (`buildBatchingSection`) that tells it to set out the whole plan — every step, which go out together, how many approvals to expect — before the first gated call and to wait for agreement (a plan is a proposal, so it composes with "check / look / verify is read-only"), then to issue independent writes as parallel tool calls in one turn. Nothing in the engine needed to change for this to work: the tool-approval gate classifies every tool call a model emits in one message together and pauses the gated ones as a single batch (`ToolLoopRunner`, `toolApprovalGate.classify` over the whole `toolExecutionRequests()` list); no provider disables parallel calls. See [`hitl.md`](../hitl.md#batching--several-calls-one-pause).
2. **`POST /agentstore/agents/{id}/cascade?version=N`** — the REST form of the MCP tool `apply_agent_changes`, so the operator (whose tools are generated from the REST spec) can use it. Body `{ resourceMappings:[{oldUri,newUri}], redeploy, environment, compatible }`; it swaps references through the agent's workflows and into the agent, saving every changed workflow and the agent **once**, optionally redeploying, and answers with the new agent version, the workflow versions written and the deployment outcome. The logic moved out of `McpAdminTools` into a shared `AgentCascadeService` that both surfaces call — no duplicated implementation. A mapping can now also name a workflow itself, so a workflow patched in the same batch is picked up instead of being overwritten by a rewrite of the version the agent still points at.

Result for the knowledge-base binding: batch 1 = LLM config write + workflow write (parallel), batch 2 = one cascade carrying both new versions plus the redeploy — **two approvals instead of five**. With the credential-safe JSON-Patch endpoints (#1009) batch 1 is the LLM-config PATCH plus the workflow write, which stays a `PUT /workflowstore/workflows/{id}` (the workflow store has no PATCH, and a workflow holds no credential references); the shape is unchanged.

### Behaviour changes worth knowing

- **Stricter than before, on both surfaces.** A mapping now must have a `?version=` on both URIs, name the same kind of resource (authority and store path), and its `oldUri` must be a reference the agent really holds. Anything else is refused whole (`400` over REST, an error result over MCP) and **nothing is written** — previously an unmatched pair was silently ignored and the call reported `cascaded`. A `version` that is not the agent's current one is a `409`, detected before any workflow is written (it used to surface only at the final agent update, after the workflows had already been saved).
- **Role.** `eddi-admin` or `eddi-editor`, the class-level gate every agent-store write already has and the same set the writes it composes require; the MCP tool stays `eddi-admin`.
- **Failures are explicit.** If the agent save (or a later workflow write) fails, the error says the agent was NOT changed and names the workflow versions already written (harmless, unreferenced); a store update that reports no new version is an error, never a guessed `version + 1`. A workflow referenced twice by one agent is written once. The agent's workflow reference may only move to another version of the same workflow (as `updateResourceUri` allows), never to a different workflow.
- **Redeploy** goes through the normal deploy path, so quota, EDIT access and the vault-grant check apply identically; a refused or failed deploy is reported in `deployment`, the documents being already written.

### Security decision

Added to the operator's `WRITE_ENDPOINTS`, gated like every write by `http.post:*`. It is not the agent-document PUT that `planning/operator-write-scope-plan.md` excludes: the body is URI pairs and two flags, the backend rewrites only `workflowStep.config.uri` and the agent's workflow list, and the agent is the stored document written back with that list replaced — so `hitlConfig` and every other field are carried forward unchanged. `AgentCascadeServiceTest` pins this by serializing an agent that carries a gate before and after and asserting every field except `workflows` is equal. Aimed at the operator's own agent it is the same self-ungating hinge as `updateResourceUri`; `self-guard.ts` blocks it (the id is a path segment), with a test. The reasoning, and the residual (a `newUri` naming a same-type resource the caller never created is not ownership-checked), is recorded in [the plan](../../planning/operator-write-scope-plan.md) in its correction style.

### Manager

- `tool-scopes.ts`: `POST /agentstore/agents/{id}/cascade` in `WRITE_ENDPOINTS`; `grantsAgentCascade` so the prompt names the tool only when it was generated.
- `system-prompt.ts`: plan-first, batching and cascade guidance (no `{…}` in it — the body is a Qute template).
- `cascade-preview.ts` + `cascade-summary.tsx`, shown by `RequestPreview`: each mapping as `llm <id> v1 → v2`, plus "Will redeploy to <environment>" and what `compatible` does to running conversations. The raw body stays below it; an unreadable or truncated body shows no summary rather than a partial one. New strings are translated in all eleven locales.
- `operator-revision.json` bumped (prompt and allow-list changed): existing operators get the upgrade banner.
- `ui/manager/AGENTS.md` (Platform Operator) and `docs/deployment-management-of-agents.md` ("Landing resource changes in one pass"), `docs/mcp-server.md`, `docs/hitl.md` updated.

### Tests

`AgentCascadeServiceTest` (mappings applied, each document saved once, workflow-then-step mapping keeps the rag step, unknown `oldUri` → nothing written, cross-type / versionless / smuggled-query URIs refused, stale version → conflict before any read, `hitlConfig` and all other agent fields unchanged, redeploy outcomes), `RestAgentStoreCascadeTest` (400/404/409 mapping, EDIT access first, role), the MCP suites adjusted for the stricter validation, and Manager tests for the prompt plan shape, `WRITE_ENDPOINTS`, `self-guard`, and the approval card.

**Files:** [`AgentCascadeService.java`](../../src/main/java/ai/labs/eddi/configs/agents/AgentCascadeService.java), [`IRestAgentStore.java`](../../src/main/java/ai/labs/eddi/configs/agents/IRestAgentStore.java), [`RestAgentStore.java`](../../src/main/java/ai/labs/eddi/configs/agents/rest/RestAgentStore.java), [`McpAdminTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java), [`tool-scopes.ts`](../../ui/manager/src/lib/operator/tool-scopes.ts), [`system-prompt.ts`](../../ui/manager/src/lib/operator/system-prompt.ts), [`cascade-preview.ts`](../../ui/manager/src/lib/operator/cascade-preview.ts)

```decision-log
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
```
