## ✨ feat(setup-api): per-turn auth from secret context, generate-only, and re-sync (2026-11-04)

**Repo:** EDDI (`feat/setup-api-context-auth-sync`)

### What changed and why

An integrator could not build a per-user-token operator agent with EDDI's own OpenAPI converter, nor update one from a regenerated spec. Three additions, usable independently:

1. **`${context:<key>}` in httpcall headers and in `apiAuth`.** Resolved per request from the turn's context entry of that key — only when the client marked it `"secret": true`, only in a header, only as a lone reference after an optional scheme, only when written in the configured header itself, and only towards the origin of the httpcalls configuration's `targetServerUrl`. A missing value, an empty one, or the `<secret context>` placeholder a resumed turn holds when the client did not re-supply it fails the call — never `Bearer ` with nothing. The plaintext joins the executor's redaction set (request record, approval preview, error bodies, logs) and disables redirects. Read from the step datum (`context:<key>`), which holds the live value during the turn and after a HITL resume re-injects it (`Conversation.injectResumeSecretContext`). A Qute pass-through resolver for the `context` namespace keeps the reference intact through templating. Metric `eddi.httpcalls.context.reference{outcome}`.
2. **`"dryRun": true` on `POST /administration/agents/setup-api`.** Runs the whole setup code path with every write skipped (`ResourceWriter`): no store, vault, descriptor, grant or deploy. Returns `200` with the apicalls configurations, tool names, LLM configuration, workflow and agent (gate included). A plaintext `apiKey`/`apiAuth` is shown as a placeholder. Hidden from the OpenAPI schema so the Platform Operator's generated `setup-api` tool cannot carry it.
3. **`POST /administration/agents/{agentId}/setup-api/sync`.** Regenerates only the apicalls configurations stamped with the new `ApiCallsConfiguration.openApiOrigin` marker (now set by every setup), rewrites the LLM task's `tools` list and the generated API summary in its prompt, keeps everything else, and saves the agent as a compatible version (`updateAgent(..., compatible=true)`), then deploys unless `deploy: false`. Reports added/changed/removed tools; refuses with 400 and nothing written when a removed tool is still named by `toolQuotas`, a `toolApprovals` exemption (agent or task) or `toolResultGuardrails.exemptTools`, listing each. The request's `requireApproval` patterns are unioned into the stored gate; nothing is removed and request exemptions are ignored. An agent without marked configurations (created before this change) is refused with how to add the marker. Supports `dryRun`.

### Findings

- The existing `Bearer {context.userToken}` Qute form does **not** leak into persisted memory: an `Authorization` header is name-redacted in the request record, and the turn-end scrub (`Conversation.scrubSecretContextValues`) removes the value (8+ characters) from every step datum, property, pending tool-call batch and audit entry. It is weaker in-turn: it renders anywhere (path, query, body), towards any host, follows redirects, sends `Bearer ` when missing, and an API error body echoing the token reaches the model's tool result only pattern-redacted. Unchanged; the reference is documented as the recommended form.

### Not done

- `toolDisplay` reference checks: the field is not on `main` yet (`feat/resume-stream-tool-events`); sync preserves it (the LLM task is copied whole) but cannot check it for dangling references.

**Files:** [`SecretContextReferences.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/SecretContextReferences.java), [`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`AgentSetupService.java`](../../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`ApiAgentSyncService.java`](../../src/main/java/ai/labs/eddi/engine/setup/ApiAgentSyncService.java), [`ApiCallsConfiguration.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCallsConfiguration.java); docs [`httpcalls.md`](../httpcalls.md), [`architecture.md`](../architecture.md), [`rest-api-reference.md`](../rest-api-reference.md), [`secrets-vault.md`](../secrets-vault.md).

```decision-log
| 2026-11-04 | setup-api sync identifies generated apicalls by an `openApiOrigin` marker set at setup; unmarked agents are refused with how to add it | Sync must not regenerate hand-written httpcalls | Heuristic adoption by targetServerUrl match (could overwrite hand-written configs) |
| 2026-11-04 | `dryRun` on setup-api is hidden from the OpenAPI schema | No addition may become callable by the Platform Operator, whose setup-api tool is generated from that schema | A sibling endpoint (the brief asked for the body field) |
```
