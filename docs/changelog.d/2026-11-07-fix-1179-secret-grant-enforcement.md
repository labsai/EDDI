## 🔒 fix(secrets): enforce agent grants where a secret is resolved (#1179) (2026-11-07)

**Repo:** EDDI (`fix/1179-secret-grant-enforcement`)

### What changed and why

A secret's agent grant (`allowedAgents`) was checked only when an agent was deployed. On a cluster the grant-impact scan that guarded secret creation missed two deployments: one that passed the deploy gate on another node after the scan had read the shared records, and an `autoDeploy=false` deployment on another node, which has no record. Both could resolve a secret their grant left out, from the vault or from the resolver's cache. An agent deployed before its grant was narrowed also kept resolving until it was redeployed.

The grant is now also checked **at every resolution**, for the agent the value is resolved for, so it holds on every node, whatever any scan saw.

- **`SecretAccess`** (new) names on whose behalf a reference is resolved. `SecretResolver.resolveValue` and `resolveSecrets` no longer have an overload without one, so every call site had to decide, and the compiler keeps it that way. There are three kinds. **agent**: the grant must list the agent or be open. **no agent**: the grant must be open to every agent, the rule `ServiceJudgeVaultGrants` already applied to group judges. **outside agent grants**: deployment infrastructure the grant never governed, so the grant is not read. An agent access without an id fails closed.
- **Cache.** The resolver caches the grant next to the plaintext, both keyed on the secret, and checks the grant for the asking access *before* the cache on every resolution. A value cached for agent A is never handed to agent B. Every grant write now evicts both caches cluster-wide: `PUT …/grant` deliberately did not before, and the append endpoint (`VaultGrantService`) never had to.
- **Downstream caches.** `SecretResolver.requireGranted` checks the grant without resolving. It runs before each cache that holds a client built from a secret: `ChatModelRegistry` (sync and streaming), `EmbeddingModelFactory`, `EmbeddingStoreFactory`, MCP tool assembly (`discoverTools`, `resourceBridgeTools`), A2A discovery and `ConnectionResolver` (before a cached OAuth token is handed out). The old `VaultGrantChecker` Javadoc gave the `ChatModelRegistry` cache as the reason resolution-time checks were impossible. That is now covered.
- **Fail closed.** A grant that cannot be read is refused at resolution (the deploy gate still lets unreadable metadata through). A missing secret stays unresolved, as before. `eddi.vault.grant-enforcement` governs both checks: `enforce` refuses, `warn` logs once per secret and asker, `off` reads nothing. The new counter `eddi.vault.grant.refusals` counts refusals. The new `ConnectionException.Reason.SECRET_NOT_GRANTED` maps to 403.
- **The impact scan is advisory.** `VaultGrantImpactAnalyzer.refusalToCreate` is now `warningOnCreate`. A secret create no longer answers 409. It succeeds and carries a `warning` field (an `X-Eddi-Warnings` header on the space endpoint) naming the deployed agents the new grant leaves out. `GrantImpact.complete()` is documented as "every source was read", not "every deployment was seen". The grant endpoint's warning no longer says those agents "keep running".

### Call-site decisions

| Kind | Call sites |
| --- | --- |
| agent | `LlmTask` (all six model builds), `CascadingModelExecutor` (steps and judge; a judge refused its key now fails the turn instead of falling back to the heuristic), `GuardrailModelClient`, `ToolResponseTruncator` via `ToolLoopRunner`, `ConversationSummarizer`, `DreamService`, `ApiCallExecutor` (body, path, headers, query, `${connection:}` headers), `McpToolsProvider`, `McpCallsTask`, `A2AToolsProvider`, `RagContextProvider` |
| no agent | knowledge-base ingestion (`RagIngestionService`, `IngestionPipeline`), `KnowledgeBaseInspector`, the editor's MCP discovery probe (`RestMcpCallsStore`), `ServiceConvergenceJudge`, `LessonConsolidationService`, group window and stance summaries |
| outside agent grants | `OutboundHttp` proxy credentials, `SystemMailer`, `AlertSenders`, `ChannelTargetRouter`, `RestChannelIntegrationStore`, the email-channel transport, the OAuth token endpoint (`OAuthTokenService`, `RestConnectionAuthorization`), and the shared MCP and A2A sessions *after* the agent check at tool assembly |

**Behaviour change:** ingesting into a knowledge base whose embedding or store key is granted to specific agents only is now refused. Grant such a key to `["*"]` and restrict it with `allowedHosts`. Channel integrations and alert rules, which editors also author, stay outside agent grants for now. Moving them to the "no agent" rule would cut off existing channels whose tokens are restricted, so it needs save-time validation first (follow-up).

**Manager:** the grant dialog's warning and two doc comments said narrowed agents "keep running". They now say the agents lose access at once, in all 11 locales. **Platform Operator:** the prompt now explains runtime grant refusals. Revision bumped.

**Files:** [`SecretAccess.java`](../../src/main/java/ai/labs/eddi/secrets/SecretAccess.java), [`SecretResolver.java`](../../src/main/java/ai/labs/eddi/secrets/SecretResolver.java), [`VaultGrantImpactAnalyzer.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantImpactAnalyzer.java), [`ConnectionResolver.java`](../../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java), [`ChatModelRegistry.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java), [`docs/secrets-vault.md`](../secrets-vault.md), plus the call sites above. Tests: [`SecretResolverGrantTest.java`](../../src/test/java/ai/labs/eddi/secrets/SecretResolverGrantTest.java) and the per-category tests.

```decision-log
| 2026-11-07 | Enforce secret agent grants at resolution time, checked before every cache (resolver, model, store, MCP/A2A, connection token) | #1179: the deploy-time grant-impact scan cannot see every deployment of a cluster | A lock shared between deploy and secret creation: it would not cover autoDeploy=false deployments or grants narrowed after deployment |
```
