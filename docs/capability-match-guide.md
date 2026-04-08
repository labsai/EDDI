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
| `strategy` | No | `highest_confidence` | Selection strategy: `highest_confidence`, `round_robin`, or `all` |
| `minResults` | No | `1` | Minimum number of matching agents for SUCCESS |

### Selection Strategies

| Strategy | Behavior |
|---|---|
| `highest_confidence` | Sort matches by confidence (high → medium → low) |
| `round_robin` | Randomize order (for load distribution) |
| `all` | Return all matches in natural order |

---

## Template Variables

Config values support **Jinja2 template expressions**, resolved against the conversation memory at evaluation time. This enables dynamic routing:

```json
{
  "type": "capabilityMatch",
  "configs": {
    "skill": "{{properties.requiredSkill.valueString}}",
    "strategy": "{{context.routingStrategy}}",
    "minResults": "1"
  }
}
```

The `skill` and `strategy` values are resolved using `IMemoryItemConverter.convert(memory)` — the same data map available to system prompts and httpCalls templates.

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
```
The following agents have been identified as legal analysis experts:
{{memory.current.capabilityMatch.results}}

Use the createGroupConversation tool to assemble them into a discussion panel.
```

### Example 3: Template-Based Routing with Properties

Use PropertySetter to capture the user's intent, then route dynamically:

**property.json (PropertySetterTask):**
```json
{
  "actions": ["user_request_specialist"],
  "setOnActions": [{
    "actions": ["user_request_specialist"],
    "setProperties": [{
      "name": "requiredSkill",
      "valueString": "{{memory.current.intent}}",
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
        "skill": "{{properties.requiredSkill.valueString}}",
        "strategy": "highest_confidence",
        "minResults": "1"
      }
    }
  ]
}
```

---

## Attribute Filtering

The `CapabilityRegistryService` also supports fine-grained attribute matching via the `findBySkillAndAttributes` API. This is available programmatically (e.g., from MCP tools or REST) but not yet exposed as a behavior rule config. Example:

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
