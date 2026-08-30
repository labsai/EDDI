# CapabilityMatch Condition — Usage Guide

> The `capabilityMatch` behavior rule condition enables **config-driven agent discovery**. An orchestrating agent can dynamically find other agents that declare a specific skill, without hardcoding agent IDs. This is the foundation of EDDI's A2A (Agent-to-Agent) soft routing.

## How It Works

```
┌───────────────────────────┐
│  Agent A (Orchestrator)   │
│  behavior.json:           │
│    condition:              │
│      capabilityMatch       │
│      skill: "translation"  │
│                            │
│  If SUCCESS → action:      │
│    "delegate_to_translator"│
└──────────┬────────────────┘
           │ queries registry
           ▼
┌───────────────────────────┐
│  CapabilityRegistryService │
│                            │
│  Index:                    │
│  "translation" →           │
│    Agent B (confidence:high)│
│    Agent C (confidence:med) │
└──────────┬────────────────┘
           │ matched agent IDs
           ▼
┌───────────────────────────┐
│  Conversation Memory:      │
│  capabilityMatch.results = │
│  ["agent-b-id", "agent-c"] │
└───────────────────────────┘
```

1. **Agent B and C** declare capabilities in their `AgentConfiguration`:
   ```json
   { "capabilities": [{ "skill": "translation", "confidence": "high" }] }
   ```
2. **Agent A** (the orchestrator) uses `capabilityMatch` in its behavior rules
3. When the condition evaluates, it queries the `CapabilityRegistryService`
4. Matching agent IDs are stored in memory as `capabilityMatch.results`
5. Downstream tasks (group orchestration, httpCalls, LLM tools) can consume the results

---

## Configuration

### behavior.json

```json
{
  "name": "Route to specialist",
  "actions": ["delegate_to_specialist"],
  "conditions": [
    {
      "type": "capabilityMatch",
      "configs": {
        "skill": "language-translation",
        "strategy": "highest_confidence",
        "minResults": "1"
      }
    }
  ]
}
```

### Config Keys

| Key | Required | Default | Description |
|---|---|---|---|
| `skill` | Yes | — | Skill name to search for (case-insensitive) |
| `strategy` | No | `highest_confidence` | Selection strategy: `highest_confidence`, `round_robin`, `random`, or `all` |
| `minResults` | No | `1` | Minimum number of matching agents for SUCCESS |

### Selection Strategies

| Strategy | Behavior |
|---|---|
| `highest_confidence` | Sort matches by confidence (high → medium → low) |
| `round_robin` | Deterministic rotation — a per-skill counter advances one position on each query (for load distribution) |
| `random` | Shuffle matches randomly |
| `all` | Return all matches in natural order |

---

## Template Variables

> **Known limitation — template expressions do not currently resolve here.**
> `CapabilityMatchCondition.resolveTemplate` hands a config value to the templating
> engine only when the value contains the double-brace marker `{{`, so a single-brace
> Qute expression such as `{properties.requiredSkill}` never reaches the engine at all —
> and a double-brace one that does reach it is left literal, because Qute does not
> resolve `{{ … }}` (pinned by `PlaceholderSyntaxContractTest`). Either way the
> unresolved string is used as the skill name, matches nothing, and the condition
> silently returns FAIL. Give `skill` and `strategy` literal values until this is fixed.

The intent is that config values are **Qute template expressions**, resolved against the conversation memory at evaluation time, enabling dynamic routing:

```json
{
  "type": "capabilityMatch",
  "configs": {
    "skill": "{properties.requiredSkill}",
    "strategy": "{context.routingStrategy}",
    "minResults": "1"
  }
}
```

The `skill` and `strategy` values would be resolved using `IMemoryItemConverter.convert(memory)` — the same data map available to system prompts and httpCalls templates.

---

## Agent Capability Declaration

Agents declare capabilities in their `AgentConfiguration`:

```json
{
  "name": "Translation Agent",
  "capabilities": [
    {
      "skill": "language-translation",
      "confidence": "high",
      "attributes": {
        "languages": "en,de,fr,es",
        "domain": "legal"
      }
    },
    {
      "skill": "summarization",
      "confidence": "medium",
      "attributes": {}
    }
  ]
}
```

### Capability Fields

| Field | Required | Default | Description |
|---|---|---|---|
| `skill` | Yes | — | Unique skill identifier (lowercased for indexing) |
| `confidence` | No | `medium` | Self-declared confidence level: `high`, `medium`, `low` |
| `attributes` | No | `{}` | Key-value metadata for fine-grained filtering |

---

## Consuming Results

When `capabilityMatch` evaluates to SUCCESS, the matching agent IDs are stored in conversation memory:

```
Memory key: capabilityMatch.results
Value: ["agent-b-id", "agent-c-id"]
```

### Example 1: Action Delegation

The simplest pattern — match a skill, then fire an action that another task (e.g., LLM, httpCalls) reacts to:

```json
{
  "name": "Delegate to translator",
  "actions": ["call_translation_agent"],
  "conditions": [
    {
      "type": "capabilityMatch",
      "configs": {
        "skill": "language-translation",
        "strategy": "highest_confidence",
        "minResults": "1"
      }
    }
  ]
}
```

The LLM task or httpCalls task listens for the `call_translation_agent` action and can access the discovered agents via memory.

### Example 2: Dynamic Group Composition

Use the discovered agents to dynamically compose a group conversation:

**behavior.json:**
```json
{
  "name": "Assemble expert panel",
  "actions": ["create_expert_group"],
  "conditions": [
    {
      "type": "capabilityMatch",
      "configs": {
        "skill": "legal-analysis",
        "strategy": "all",
        "minResults": "2"
      }
    }
  ]
}
```

**System prompt (LLM task triggered by `create_expert_group`):**
```text
Legal analysis experts have been identified for this request.

Use the createGroupConversation tool to assemble them into a discussion panel.
```

> The matched agent IDs are written with `storeData` into the current step's data
> store under the key `capabilityMatch.results`, so Java tasks and tools can read
> them. They are **not** reachable from a template: `{memory.current.*}` resolves
> against the step's `conversationOutput`, which only the `addConversationOutput*`
> methods populate — and the key itself contains a dot, so it would not be
> addressable as `.capabilityMatch.results` even if it were there.

### Example 3: Template-Based Routing with Properties

Use PropertySetter to capture the user's intent, then route dynamically — subject to the
limitation in [Template Variables](#template-variables): the `{properties.requiredSkill}`
below is not resolved by `capabilityMatch` today, so this example does not yet work.

**property.json (PropertySetterTask):**
```json
{
  "actions": ["user_request_specialist"],
  "setOnActions": [{
    "actions": ["user_request_specialist"],
    "setProperties": [{
      "name": "requiredSkill",
      "valueString": "{memory.current.intent}",
      "scope": "conversation"
    }]
  }]
}
```

**behavior.json:**
```json
{
  "name": "Find specialist for user request",
  "actions": ["specialist_found"],
  "conditions": [
    {
      "type": "capabilityMatch",
      "configs": {
        "skill": "{properties.requiredSkill}",
        "strategy": "highest_confidence",
        "minResults": "1"
      }
    }
  ]
}
```

---

## Attribute Filtering

The `CapabilityRegistryService` also supports fine-grained attribute matching via the `findBySkillAndAttributes` API. This is available to in-process Java callers only — no REST endpoint, MCP tool or behavior rule config currently exposes attribute filtering (`/capabilities`, the A2A `/.well-known/capabilities` endpoint and the `findAgentsByCapability` tool all call the skill-only overload). Example:

```java
// Find translation agents that support German
var matches = registry.findBySkillAndAttributes(
    "language-translation",
    Map.of("languages", "de"),
    "highest_confidence"
);
```

Comma-separated attribute values are matched with `contains` — `"en,de,fr"` matches `"de"`.

---

## Metrics

The `CapabilityRegistryService` exposes metrics at `/q/metrics`:

| Metric | Description |
|---|---|
| `eddi.capability.query.count` | Total number of capability queries |
| `eddi.capability.query.time` | Query execution time distribution |

---

## ContentTypeMatcher — Attachment Routing

A companion condition for routing based on **attachment MIME types**:

```json
{
  "name": "Process image attachments",
  "actions": ["analyze_image"],
  "conditions": [
    {
      "type": "contentTypeMatcher",
      "configs": {
        "mimeType": "image/*",
        "minCount": "1"
      }
    }
  ]
}
```

| Config | Default | Description |
|---|---|---|
| `mimeType` | — | MIME type pattern (supports `*/*`, `image/*`, `application/pdf`) |
| `minCount` | `1` | Minimum number of matching attachments |

This condition reads from the `attachments` memory key populated by the attachment pipeline (see `docs/attachments-guide.md`).
