# Persistent User Memory

Persistent User Memory enables EDDI agents to remember facts, preferences, and context about individual users **across conversations**. Unlike conversation-scoped properties that are lost when a conversation ends, persistent memories survive indefinitely and are automatically loaded into every new conversation with the same user.

## Overview

| Feature | Description |
|---|---|
| **Scope** | Per-user, per-agent (or globally shared) |
| **Storage** | MongoDB (`usermemories` collection) or PostgreSQL (`usermemories` table) |
| **LLM Integration** | 4 built-in tools for autonomous memory management |
| **Visibility** | `self`, `group`, `global` scoping |
| **Guardrails** | Configurable key/value limits, write-rate limits, capacity caps |
| **GDPR** | Full right-to-erasure support via REST API and MCP tools |
| **Maintenance** | Background "Dream" consolidation (stale pruning, contradiction detection) |

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Conversation Pipeline              │
│                                                      │
│  LLM ──→ UserMemoryTool ──→ IUserMemoryStore        │
│            ↑                       ↑                 │
│            │                       │                 │
│      AgentOrchestrator     MongoUserMemoryStore      │
│      (per-invocation)      PostgresUserMemoryStore   │
│                                                      │
│  REST API ───────────────────→ IUserMemoryStore      │
│  MCP Tools ──────────────────→ IUserMemoryStore      │
│  DreamService (background) ─→ IUserMemoryStore      │
└─────────────────────────────────────────────────────┘
```

## Agent Configuration

Enable persistent memory in your agent's configuration:

```json
{
  "name": "My Agent",
  "userMemoryConfig": {
    "maxEntriesPerUser": 500,
    "maxRecallEntries": 50,
    "recallOrder": "most_recent",
    "onCapReached": "evict_oldest",
    "guardrails": {
      "maxKeyLength": 100,
      "maxValueLength": 1000,
      "maxWritesPerTurn": 10,
      "allowedCategories": ["preference", "fact", "context"]
    },
    "dreamConfig": {
      "enabled": true,
      "pruneStaleAfterDays": 90,
      "detectContradictions": true,
      "summarizeInteractions": false,
      "maxCostPerRun": 0.50
    }
  },
  "builtInToolsWhitelist": ["usermemory"]
}
```

### Configuration Reference

| Field | Type | Default | Description |
|---|---|---|---|
| `maxEntriesPerUser` | `int` | `500` | Maximum memory entries per user |
| `maxRecallEntries` | `int` | `50` | Maximum entries returned by recall |
| `recallOrder` | `String` | `"most_recent"` | `"most_recent"` (by updatedAt) or `"most_accessed"` (by accessCount) |
| `onCapReached` | `String` | `"evict_oldest"` | `"reject"` (block new writes) or `"evict_oldest"` (push out of recall window) |

### Guardrails

| Field | Type | Default | Description |
|---|---|---|---|
| `maxKeyLength` | `int` | `100` | Maximum characters for memory keys |
| `maxValueLength` | `int` | `1000` | Maximum characters for memory values |
| `maxWritesPerTurn` | `int` | `10` | Write-rate limit per conversation turn |
| `allowedCategories` | `List<String>` | `["preference","fact","context"]` | Allowed memory categories |

### Dream Configuration

| Field | Type | Default | Description |
|---|---|---|---|
| `enabled` | `boolean` | `false` | Enable background consolidation |
| `pruneStaleAfterDays` | `int` | `90` | Remove entries not accessed in N days. Set to 0 to disable. |
| `detectContradictions` | `boolean` | `true` | Flag entries with same key but different values |
| `summarizeInteractions` | `boolean` | `false` | V2 feature: LLM-driven fact compression |
| `maxCostPerRun` | `double` | `0.50` | Maximum dollar cost per dream cycle |

## LLM Tools

When `usermemory` is in the agent's `builtInToolsWhitelist`, the LLM gets access to four tools:

### `rememberFact`

Store a fact about the user.

```
Parameters:
  key       - Short key name (e.g. "favorite_color", "dietary_restriction")
  value     - The value to remember
  category  - One of: "preference", "fact", "context"
  visibility - One of: "self", "group", "global" (default: "self")

Returns: "✅ Remembered: favorite_color = blue [preference, self]"
```

### `recallMemories`

Retrieve all memories visible to this agent for the current user.

```
Parameters: none

Returns:
  • name = Alice [fact, self]
  • favorite_color = blue [preference, self]
  • language = English [preference, global]
```

### `searchMemory`

Search for memories by keyword across keys and values.

```
Parameters:
  query - Search text (e.g. "color")

Returns: matching entries formatted as bullet list
```

### `forgetFact`

Delete a specific memory by key.

```
Parameters:
  key - The key name to forget (e.g. "favorite_color")

Returns: "✅ Forgotten: favorite_color"
```

## Visibility Scopes

| Scope | Description | Upsert Key |
|---|---|---|
| `self` | Only the agent that stored it can see it | `(userId, key, sourceAgentId)` |
| `group` | All agents in the same group conversation can see it | `(userId, key, sourceAgentId)` |
| `global` | All agents for this user can see it | `(userId, key)` |

### Group Memory

When agents participate in a [Group Conversation](group-conversations.md), the `groupId` is automatically injected into the conversation context. Memories stored with `group` visibility are visible to all agents in that group.

## REST API

Base path: `/usermemorystore/memories`

| Method | Path | Description |
|---|---|---|
| `GET` | `/{userId}` | Get all memories for a user |
| `GET` | `/{userId}/visible?agentId=&groupId=&order=&limit=` | Get memories visible to a specific agent |
| `GET` | `/{userId}/search?q=` | Search memories by keyword |
| `GET` | `/{userId}/category/{category}` | Get memories filtered by category |
| `GET` | `/{userId}/key/{key}` | Get a specific memory by key |
| `PUT` | `/` | Upsert a memory entry (JSON body) |
| `DELETE` | `/entry/{entryId}` | Delete a specific memory |
| `DELETE` | `/{userId}` | Delete ALL memories for a user (GDPR) |
| `GET` | `/{userId}/count` | Count memory entries |

### Example: Upsert a memory

```bash
curl -X PUT http://localhost:7070/usermemorystore/memories \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "key": "preferred_language",
    "value": "German",
    "category": "preference",
    "visibility": "global",
    "sourceAgentId": "agent-456"
  }'
```

### Example: Get visible memories

```bash
curl "http://localhost:7070/usermemorystore/memories/user-123/visible?agentId=agent-456&order=most_recent&limit=20"
```

## MCP Tools

8 MCP tools are available for external integration and administration:

| Tool | Role | Description |
|---|---|---|
| `list_user_memories` | `eddi-viewer` | List all entries for a user |
| `get_visible_memories` | `eddi-viewer` | Get entries visible to a specific agent |
| `search_user_memories` | `eddi-viewer` | Search by keyword |
| `get_memory_by_key` | `eddi-viewer` | Look up by key name |
| `count_user_memories` | `eddi-viewer` | Count entries |
| `upsert_user_memory` | `eddi-admin` | Insert or update an entry |
| `delete_user_memory` | `eddi-admin` | Delete a specific entry |
| `delete_all_user_memories` | `eddi-admin` | GDPR delete-all (requires `CONFIRM`) |

### GDPR Compliance

The `delete_all_user_memories` MCP tool and `DELETE /{userId}` REST endpoint permanently remove **all** memory entries and legacy properties for a user. The MCP tool requires an explicit `confirmation="CONFIRM"` parameter as a safety gate.

## Dream Consolidation

The Dream service performs background maintenance on user memories:

1. **Stale Pruning** — Removes entries not accessed in `pruneStaleAfterDays` days. This is a deterministic operation with zero LLM cost.

2. **Contradiction Detection** — Identifies entries with the same key but different values (e.g., `language=English` from Agent A vs `language=German` from Agent B). V1 uses key-based matching; future versions will use LLM-driven semantic analysis.

3. **Interaction Summarization** — (V2, not yet active) Compresses multiple related facts into consolidated summaries using the LLM.

### Metrics

The Dream service exposes Micrometer metrics:

| Metric | Type | Description |
|---|---|---|
| `dream.users.processed` | Counter | Users processed across all dream cycles |
| `dream.entries.pruned` | Counter | Total entries pruned |
| `dream.contradictions.found` | Counter | Contradictions detected |
| `dream.duration` | Timer | Duration of dream cycles |

## Migration from Legacy Properties

Persistent User Memory replaces the legacy `IPropertiesStore` interface (now `@Deprecated`). Key differences:

| Aspect | Legacy Properties | User Memory |
|---|---|---|
| **Storage** | `properties` collection (flat map) | `usermemories` collection (structured entries) |
| **Scoping** | Per-user only | Per-user, per-agent, per-group |
| **LLM access** | Via template variables only | Direct LLM tool access |
| **Querying** | Key lookup only | Key, category, search, visibility filtering |
| **Administration** | No REST API | Full CRUD REST API + MCP tools |

Legacy property operations (`readProperties`, `mergeProperties`, `deleteProperties`) continue to work through `IUserMemoryStore` — the unified store delegates legacy methods to the existing `properties` collection.

## Data Model

Each memory entry contains:

```json
{
  "id": "ObjectId",
  "userId": "user-123",
  "key": "preferred_language",
  "value": "German",
  "category": "preference",
  "visibility": "global",
  "sourceAgentId": "agent-456",
  "groupIds": ["group-1"],
  "sourceConversationId": "conv-789",
  "conflicted": false,
  "accessCount": 12,
  "createdAt": "2026-01-15T10:30:00Z",
  "updatedAt": "2026-03-29T14:22:00Z"
}
```
