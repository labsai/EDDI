## 🔒 fix(security): engine-reserved context keys are no longer accepted from clients (2026-09-26)

**Repo:** EDDI (`fix/security-context-keys`)

### What changed and why

Several conversation context keys are written by EDDI itself when it drives a
conversation on its own behalf — group member turns and delegated sub-agent
conversations — and the engine then trusts them to decide what a turn may do:
which group's shared memories are visible, which dynamic-agent policy governs the
turn, which agents count as created by the conversation, and how deep a
delegation chain already is. Those same keys were accepted verbatim in the
context of any client request, so a caller could assert them and be believed.

- **New `ClientContextGuard`** removes the reserved keys (`groupId`,
  `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`,
  `dynamicCreatedAgentIds`, `delegationDepth`) at every point where context
  crosses from a client into the engine: conversation start and turns over REST
  (plain and streaming, which also covers managed conversations) and the MCP
  managed-conversation path's trigger context. Internal callers reach
  `IConversationService` directly and keep setting them. The request still
  succeeds; only the reserved entries are dropped.
- **Group policy carries over.** A conversation governed by a group's
  dynamic-agent policy now keeps the most recent policy on a turn that carries no
  group context (such as one its owner sends into the member conversation
  directly), instead of falling back to the permissive standalone default.
- **`groupId` properties no longer select a memory scope.** The `usermemory`
  tool's group scope used to fall back to a `groupId` conversation property;
  properties are client- and input-settable, so only the orchestrator-injected
  context value counts now.

### Behaviour changes operators should know

- `{context.<reserved key>}` renders empty for a client-started turn. The
  Manager's Platform Operator drawer sends the viewed group as `groupId`, so its
  "(group …)" hint in the operator prompt is now blank; moving that hint to a
  non-reserved key is a follow-up for the Manager.
- A deployment whose callers are all trusted can re-permit specific keys with
  `eddi.conversation.client-context.permitted-reserved-keys` (default: none; a
  startup warning is logged when set).

**Files:**
[`ClientContextGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/ClientContextGuard.java),
[`RestAgentEngine.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java),
[`RestAgentEngineStreaming.java`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java),
[`McpConversationTools.java`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java),
[`DynamicAgentToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/DynamicAgentToolsProvider.java),
[`ContextualToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ContextualToolsProvider.java),
[`passing-context-information.md`](../passing-context-information.md#reserved-context-keys),
[`user-memory.md`](../user-memory.md#group-memory)

**Tests:** `ClientContextGuardTest`, `RestAgentEngineClientContextTest`,
`McpConversationToolsReservedContextTest`, `DynamicAgentGroupPolicyCarryOverTest`,
and `ContextualToolsProviderGroupIdTest` (property case inverted). Each was
mutation-checked against a reverted fix.

```decision-log
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
```
