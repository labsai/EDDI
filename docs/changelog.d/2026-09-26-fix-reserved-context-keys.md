## 🔒 fix(engine): client context can no longer pose as the group orchestrator; teardown, delegation and recruitment bound to what the caller owns (2026-09-26)

**Repo:** EDDI (`fix/reserved-context-keys`)

### What changed and why

A conversation's context map is shared by two writers: the client (`InputData.context`, the start body, a trigger's `initialContext`) and the engine's own orchestrators, which use the same map to hand a group member or delegated callee its policy. `Conversation.createContextData` stored every key verbatim and the readers could not tell who wrote it. Review findings C3a/C3b/C3c/C6/M-T2/M-A1:

- **C3a — client unlocks the dynamic-agent tools.** Sending `context.dynamicAgentConfig` satisfied `DynamicAgentToolsProvider.hasGroupPolicy`, so any agent with `enableBuiltInTools` and no whitelist got `create_sub_agent`, `converse_with_agent`, recruit and teardown, with caps the client chose. **Fixed:** new [`ReservedContextKeys`](../../src/main/java/ai/labs/eddi/engine/model/ReservedContextKeys.java) (`groupId`, `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`, `dynamicCreatedAgentIds`, `delegationDepth`). Every entry point that accepts outside context strips them: the `conversationId` overloads of `ConversationService.say`/`sayStreaming` (REST, streaming, MCP, Slack, `/v1`), `RestAgentEngine.startConversationWithContext` (REST start and the `RestAgentManagement` trigger path) and `McpConversationTools`' trigger start. The engine-internal overloads (`startConversation`, agent-id `say`) stay trusting — their callers are `MemberTurnExecutor`, `GroupLifecycleOps` and the delegation tool. Dropped with a WARN, not rejected, so a client echoing old context keeps working.
- **C3b — teardown of any agent.** `context.dynamicCreatedAgentIds = <victim>` seeded the created list and `teardown_agent(victim, delete=true)` called `deleteAllPermanently`. **Fixed twice:** the key is reserved (above), and a creator marker is now required. New `AgentConfiguration.dynamicOrigin` (`createdByAgentId`, `createdInConversationId`, `createdInGroupConversationId`, `createdForUserId`) is stamped on v1 by a new internal overload `AgentSetupService.setupAgent(request, origin)` — not a `SetupAgentRequest` field, which is a REST/MCP body. [`TeardownAgentTool`](../../src/main/java/ai/labs/eddi/modules/llm/tools/TeardownAgentTool.java) refuses (for undeploy as well as delete) unless the agent's current config carries a marker naming this conversation or its discussion; unreadable config fails closed.
- **C3c — another team's group memories.** A client `groupId` loaded that group's group-visible memories at init (`Conversation.extractGroupIds`). **Fixed** by the reserved list. Also removed the conversation-*property* fallback in `ContextualToolsProvider.resolveGroupIds`: properties are client-settable (`properties*` context of type `expressions` → `PropertySetterTask`), so the fallback was the same hole one step removed.
- **C6 — `converse_with_agent` drives any conversation.** The model-supplied `conversationId` went to the agent-id `say` overload, which has no ownership check. **Fixed:** the tool continues only conversations it started. The started ids are server-written step data (`MemoryKeys.DYNAMIC_DELEGATED_CONVERSATION_IDS`), seeded and written back each turn by `DynamicAgentToolsProvider`, so multi-turn delegation across turns still works; any other id is refused before the delegation budget is spent.
- **M-T2 — retain lasts one turn.** `sharedRetainedIds` started empty every turn, so on turn 2 `teardown_agent` deleted an agent the model had asked to keep. **Fixed:** seeded from the *latest* recorded retained set (not the union — an `unretain_agent` must not be resurrected), narrowed to agents still tracked as created.
- **M-A1 — recruitment unchecked.** `recruit_agent` pulled any deployed agent into a discussion by id. **Fixed:** new `ResourceAccessGuard.principalMayUse(resourceId, principal)` — a request-free USE check for a named principal — wired from `AgentOrchestrator` into `RecruitAgentTool`, asked for the discussion owner. Conservative by necessity: the member turn has no token, so only the owner's own resources, direct grants and published agents count; team shares are refused. Workspaces off → admitted, as everywhere.

### Design decisions

- Strip at the boundary rather than in `Conversation`: `Conversation` cannot tell an orchestrator's map from a client's; the boundary can. A source-scan test (`ReservedContextKeysTest.everyOrchestratorWrittenKeyIsReserved`) fails if the group orchestrator starts writing a context key that is not on the list.
- Belt and braces for teardown: the context fix closes today's hole; the marker keeps a future leak of any list from reaching an agent a person built.
- Compatibility: `dynamicOrigin` is additive (strict-boundary round-trip test passes). Agents created by `create_sub_agent` **before** this change carry no marker and can no longer be torn down by the tool — end-of-discussion cleanup still removes ephemeral ones. Clients that sent reserved keys lose only the ability to set them. A standalone-agent `groupId` *property* no longer scopes group memory.

### Not changed / follow-ups

- Conversations persisted before this fix may still hold client-written `context:groupId`/`context:delegationDepth` on earlier steps, which the all-steps fallbacks read. Not migrated.
- Schedule-fired turns build their own context (no client keys) and were left alone.

### Tests

`ReservedContextKeysTest` (new), `ConverseWithAgentToolOwnershipTest` (new), `DynamicAgentCrossTurnStateTest` (new), additions to `ConversationServiceTest`, `RestAgentEngineTest`, `DynamicAgentToolsTest` (teardown origin, create stamps origin), `RecruitAgentToolTest`, `ResourceAccessGuardTest`, `ContextualToolsProviderGroupIdTest`; existing converse/teardown/setup tests updated to the new constructors and overload.

```decision-log
| 2026-09-26 | Strip engine-reserved context keys at the external entry points and keep the internal `startConversation`/agent-id `say` trusting | Clients could set `dynamicAgentConfig`/`dynamicCreatedAgentIds`/`groupId` and act as the group orchestrator (C3a–c) | A trusted side channel on `InputData` (touches every internal caller and the stored step format); filtering inside `Conversation` (cannot tell who wrote the map) |
| 2026-09-26 | `teardown_agent` requires a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
```
