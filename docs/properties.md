# Properties

**Version: 6.0.0**

## Overview

**Properties** are EDDI's primary mechanism for storing and retrieving state within and across conversations. They are key-value pairs that can be set by agent configuration, extracted from API responses, or written by LLM tools — and they're accessible in every template (system prompts, HTTP call bodies, output templates, property instructions).

Properties are the glue that connects the pipeline's processing steps with persistent user state. Understanding how they work is essential for building stateful agents.

## Key Concepts

### What Properties Are

A property has:

- **Name** (key): The identifier used to access the property (e.g., `preferred_language`, `company_name`)
- **Value**: Can be a `String`, `Integer`, `Float`, `Map`, `List`, or `Boolean`
- **Scope**: How long the property lives
- **Visibility** (v6): Who can see the property

### Property vs Context vs Memory

| Mechanism | Source | Lifetime | Access Pattern |
|---|---|---|---|
| **Properties** | Agent-set (via PropertySetter, LLM tools) | Configurable (step → longTerm) | `{properties.key.valueString}` |
| **Context** | Your application (passed per request) | Per request | `{context.key}` |
| **Memory** | Pipeline (each task writes data) | Per step (current turn's data) | `{memory.current.key}` |

Use **properties** when the agent needs to remember something. Use **context** when your application injects something. Use **memory** when you need data from the current or previous pipeline step.

---

## Scopes

Properties support four scopes that control their lifetime:

| Scope | Lifetime | Persistence | Use Case |
|---|---|---|---|
| `step` | Current conversation turn only | Not persisted | Temporary data needed only for this response |
| `conversation` | Entire conversation session | Persisted in conversation memory | User preferences within a session, extracted entities |
| `longTerm` | Across conversations | Persisted in user property store | User profile data, preferences that should survive between sessions |
| `secret` | Across conversations (encrypted) | Persisted via SecretsVault | API keys, tokens, sensitive credentials |

### Choosing the Right Scope

```
Is this data only needed for the current response?
  → step

Will the user need this data later in the same conversation?
  → conversation

Should this data persist when the user starts a new conversation?
  → longTerm

Is this sensitive data (API keys, tokens)?
  → secret
```

---

## Visibility (v6)

Properties also have a **visibility** dimension that controls which agents can see them:

| Visibility | Who sees it | Use Case |
|---|---|---|
| `self` (default) | Only the owning agent | Agent-specific preferences, internal state |
| `group` | All agents in the same group conversation | Shared context in multi-agent orchestration |
| `global` | All agents for this user | Cross-agent user preferences (e.g., language, timezone) |

Visibility is orthogonal to scope — a property can be `longTerm` + `self` (persists across sessions, visible only to the owning agent) or `longTerm` + `global` (persists and visible to all agents).

---

## Setting Properties

### Via PropertySetter Configuration (JSON)

The PropertySetter task (`ai.labs.property`) sets properties based on triggered actions:

```json
{
  "setOnActions": ["greet_user"],
  "propertyInstructions": [
    {
      "name": "greeted",
      "valueString": "true",
      "scope": "conversation"
    },
    {
      "name": "preferred_language",
      "valueString": "{context.language}",
      "scope": "longTerm",
      "visibility": "global"
    }
  ]
}
```

When the `greet_user` action fires:
1. `greeted` is set to `"true"` for this conversation session
2. `preferred_language` is set from the input context and persisted across all conversations and agents

### Via Pre/Post Request Instructions

Properties can be set before or after any lifecycle task (HTTP calls, LLM calls):

```json
{
  "preRequest": {
    "propertyInstructions": [
      {
        "name": "requestTimestamp",
        "valueString": "{uuidUtils:generateUUID()}",
        "scope": "step"
      }
    ]
  },
  "postResponse": {
    "propertyInstructions": [
      {
        "name": "lastApiResponse",
        "fromObjectPath": "httpCalls.weatherApi",
        "scope": "conversation"
      }
    ]
  }
}
```

### Via LLM Tools (Agent-Driven)

When `enableUserMemory` is enabled in the agent configuration, the LLM can set properties using built-in memory tools:

```
Agent: "I'll remember that you prefer dark mode."
→ Tool call: rememberFact(key="ui_preference", value="dark_mode", category="preference", visibility="self")
```

See [Persistent Memory Architecture](planning/persistent-memory-architecture.md) for full details on the memory tool system.

---

## Accessing Properties in Templates

Properties are available in **all** templates via the `properties` namespace:

### In Output Templates

```
Hello {properties.userName.valueString}! Your preferred language is {properties.preferred_language.valueString}.
```

### In System Prompts (LLM)

```
You are a helpful assistant. The user's name is {properties.userName.valueString}.
They prefer {properties.preferred_language.valueString} responses.
```

### In HTTP Call Bodies

```json
{
  "userId": "{properties.userId.valueString}",
  "language": "{properties.preferred_language.valueString}"
}
```

### Property Value Accessors

| Accessor | Type | Example |
|---|---|---|
| `.valueString` | String value | `{properties.name.valueString}` |
| `.valueInt` | Integer value | `{properties.age.valueInt}` |
| `.valueFloat` | Float value | `{properties.score.valueFloat}` |
| `.valueObject` | Object/Map | `{properties.profile.valueObject.email}` |
| `.valueList` | List | `{#for item in properties.tags.valueList}...{/for}` |
| `.valueBoolean` | Boolean | `{#if properties.isPremium.valueBoolean}...{/if}` |

---

## Property Lifecycle

Properties are managed by `Conversation.java` at session boundaries — NOT by pipeline tasks.

### 1. Conversation Init

```
Conversation.init()
  └─→ loadLongTermProperties()
      └─→ IPropertiesHandler.loadProperties(userId)
      └─→ Properties loaded into conversationProperties with scope=longTerm
      └─→ Available as {properties.key} in all templates
```

### 2. Pipeline Execution

```
LifecycleManager runs pipeline
  └─→ PropertySetterTask sets properties based on actions
      └─→ scope=step (cleared after this turn)
      └─→ scope=conversation (lives for the session)
      └─→ scope=longTerm (persisted across conversations)
      └─→ scope=secret (auto-vaulted via SecretsVault)
```

### 3. Conversation Teardown

```
Conversation.postConversationLifecycleTasks()
  └─→ storePropertiesPermanently()
      └─→ All longTerm properties saved via IPropertiesHandler
      └─→ Secret properties scrubbed from memory and vaulted
```

> **Key insight**: Persistent state is a **session concern** handled at init/teardown — NOT in the pipeline. This means properties "just work" without any task ordering dependencies.

---

## Secret Properties

Properties with `scope=secret` are automatically handled by the SecretsVault:

1. During pipeline execution, the secret value is available in memory normally
2. At teardown, the value is encrypted and stored in SecretsVault
3. The in-memory property value is scrubbed (replaced with a vault reference)
4. On next conversation init, the value is loaded from the vault and decrypted

```json
{
  "name": "api_token",
  "valueString": "sk-abc123...",
  "scope": "secret"
}
```

See [Secrets Vault](secrets-vault.md) for full documentation.

---

## Best Practices

### 1. Use Appropriate Scopes

```json
// ❌ Don't persist temporary data
{"name": "tempResult", "scope": "longTerm"}

// ✅ Use step scope for temporary data
{"name": "tempResult", "scope": "step"}
```

### 2. Use fromObjectPath for Extraction

Instead of storing entire API responses, extract only what you need:

```json
{
  "name": "temperature",
  "fromObjectPath": "httpCalls.weatherApi.current.temperature",
  "scope": "conversation"
}
```

### 3. Use Visibility for Multi-Agent Scenarios

```json
// Agent-specific memory
{"name": "internal_state", "scope": "longTerm", "visibility": "self"}

// Shared within group conversation
{"name": "group_context", "scope": "longTerm", "visibility": "group"}

// Cross-agent user preference
{"name": "language", "scope": "longTerm", "visibility": "global"}
```

### 4. Naming Conventions

- Use `snake_case` for property names
- Use descriptive, specific names (`user_preferred_timezone` not `tz`)
- Prefix agent-specific properties with the agent's domain (`support_ticket_id`, `onboarding_step`)

---

## Related Documentation

- [Conversation Memory](conversation-memory.md) - How memory flows through the pipeline
- [Secrets Vault](secrets-vault.md) - Encrypted property storage
- [Passing Context Information](passing-context-information.md) - External data injection
- [Output Templating](output-templating.md) - Using properties in templates
- [LLM Integration](langchain.md) - Using properties in LLM prompts
- [HTTP Calls](httpcalls.md) - Using properties in API requests
