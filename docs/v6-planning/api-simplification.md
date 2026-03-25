# v6 API Endpoint Simplification

> **Status**: Planned  
> **Date**: 2026-03-25  
> **Scope**: Breaking REST API + MCP tool changes for v6

Simplify all conversation-scoped REST and MCP endpoints by removing redundant `environment` and `agentId` parameters. The conversation record already stores both — requiring callers to supply them is unnecessary friction.

---

## Motivation

`SimpleConversationMemorySnapshot` stores `agentId`, `agentVersion`, `environment`, and `conversationId`. Yet every conversation-scoped endpoint forces the caller to re-supply `environment` + `agentId`:

```
# Current (v5/early v6)
POST /agents/{environment}/{agentId}/{conversationId}

# Proposed (v6 final)
POST /agents/{conversationId}
```

The `ConversationService.say()` method already loads the conversation from DB first and validates `agentId` via `AgentMismatchException` — the external params are redundant.

---

## Design Decisions

- **`environment`**: Optional query param on `startConversation` (defaults to `production`). Dropped from all other endpoints.
- **`getConversationState`**: Drop `environment` param entirely — impl only null-checks it, doesn't use it for filtering.
- **Breaking changes are acceptable** for v6 API. Database/export backwards compatibility is maintained separately via migrations.

---

## Phase 1: Service Layer — Conversation-Only Overloads

### [MODIFY] `IConversationService.java`

Add overloads that resolve `agentId` + `environment` from the stored conversation record:

- `readConversation(conversationId, returnDetailed, returnCurrentStepOnly, returningFields)`
- `say(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, rerunOnly, responseHandler)`
- `sayStreaming(conversationId, returnDetailed, returnCurrentStepOnly, returningFields, inputData, streamingHandler)`
- `getConversationState(conversationId)` — drop `environment`
- `isUndoAvailable(conversationId)` / `undo(conversationId)` / `isRedoAvailable(conversationId)` / `redo(conversationId)`

### [MODIFY] `ConversationService.java`

Implement overloads by:
1. Loading conversation via `conversationMemoryStore.loadConversationMemorySnapshot(conversationId)`
2. Extracting `agentId` and `environment` from the snapshot
3. Delegating to existing full-param methods (no `AgentMismatchException` needed)

```java
@Override
public void say(String conversationId, Boolean returnDetailed, ...) {
    var snapshot = conversationMemoryStore.loadConversationMemorySnapshot(conversationId);
    say(snapshot.getEnvironment(), snapshot.getAgentId(), conversationId, ...);
}
```

---

## Phase 2: REST Layer — Simplified Paths

### [MODIFY] `IRestAgentEngine.java`

| Old Path | New Path | Notes |
|----------|---------|-------|
| `POST /{env}/{agentId}` | `POST /{agentId}?environment=production` | Start conversation — env as optional query param |
| `POST /{env}/{agentId}/{convId}` (say) | `POST /{conversationId}` | Talk — only need convId |
| `GET /{env}/{agentId}/{convId}` | `GET /{conversationId}` | Read conversation |
| `POST /{env}/{agentId}/{convId}/rerun` | `POST /{conversationId}/rerun` | Rerun |
| `GET /{convId}/log` | ✅ Already correct | No change |
| `POST /{convId}/endConversation` | ✅ Already correct | No change |
| `GET/POST /{env}/{agentId}/undo/{convId}` | `GET/POST /{conversationId}/undo` | Undo |
| `GET/POST /{env}/{agentId}/redo/{convId}` | `GET/POST /{conversationId}/redo` | Redo |
| `GET /{env}/conversationstatus/{convId}` | `GET /{conversationId}/status` | State |

### [MODIFY] `RestAgentEngine.java`

Update to use conversation-only service methods. Remove `checkNotNull` for environment/agentId on conversation-scoped methods.

### [MODIFY] `IRestAgentEngineStreaming.java` + `RestAgentEngineStreaming.java`

```diff
-@Path("/{environment}/{agentId}/{conversationId}/stream")
+@Path("/{conversationId}/stream")
```

---

## Phase 3: MCP Tools — Remove Redundant Parameters

### [MODIFY] `McpConversationTools.java`

| Tool | Drop | Keep |
|------|------|------|
| `talk_to_agent` | `agentId`, `environment` | `conversationId`, `message` |
| `read_conversation` | `agentId`, `environment` | `conversationId` + options |
| `chat_with_agent` | `environment` | `agentId` (needed for create), `conversationId`, `message` |
| `create_conversation` | `environment` | `agentId` |
| `list_conversations` | — | No change |

---

## Phase 4: Merge Managed Agents

### [MODIFY] `IRestAgentManagement.java`

```diff
-@Path("/managedagents")
+@Path("/agents/managed")
```

### [MODIFY] `RestAgentManagement.java`

Update to use conversation-only service methods.

---

## Phase 5: OpenAPI Tag Cleanup & MCP Resource Naming

### OpenAPI Tag Renumbering

Fix duplicate/conflicting tag numbers:

| Current | Fix |
|---------|-----|
| `09. Agent Setup` (conflicts with 09. Talk) | → `07. Agent Setup` |
| `09. Log Administration` | → `11. Log Administration` |
| `10. Coordinator Admin` (conflicts with 10. Backup) | → `12. Coordinator Admin` |
| `11. Schedules` (conflicts with 11. Orphan) | → `13. Schedules` |
| `11. Orphan Admin` | → `14. Orphan Admin` |

### MCP Resource Type Naming (`McpAdminTools.java`)

- `langchain` → `llm` (matches v6 rename)
- `behavior` → `rules` (matches store name)

---

## Verification

1. **Unit tests**: Update `RestAgentEngineTest`, `ConversationServiceTest`, `McpConversationToolsTest`
2. **New tests**: Conversation-only overloads resolve agent from memory correctly
3. **Swagger UI**: Verify simplified paths, no tag conflicts, clean sidebar
4. **Manual flow**: `POST /agents/{agentId}` → `POST /agents/{convId}` → `GET /agents/{convId}`
