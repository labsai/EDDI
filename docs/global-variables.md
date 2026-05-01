# Global Variables

EDDI includes a built-in global variable store for managing deployment-wide configuration values like default LLM model names, API base URLs, temperature settings, and feature flags. Unlike secrets, global variables are **not encrypted** and are **fully visible** in the UI and logs.

## Architecture

```
┌─────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│  Configuration   │────>│ GlobalVariable   │────>│  GlobalVariable  │
│  (JSON configs)  │     │ Resolver         │     │  Store           │
│  ${vars:..}   │     │ (regex + cache)  │     │  (MongoDB/PG)    │
└─────────────────┘     └──────────────────┘     └──────────────────┘
        │
        ▼
┌─────────────────┐
│  Template Layer  │
│  {{vars.<key>}}  │
│  (Jinja2/Qute)   │
└─────────────────┘
```

### Core Components

| Component | Package | Purpose |
|-----------|---------|---------|
| `GlobalVariable` | `configs.variables.model` | Record: `key`, `value`, `description`, `exportable` |
| `IGlobalVariableStore` | `configs.variables` | Persistence interface (non-versioned, flat key-value) |
| `GlobalVariableStore` | `configs.variables.mongo` | MongoDB implementation (`globalvariables` collection) |
| `PostgresGlobalVariableStore` | `datastore.postgres` | PostgreSQL implementation (`global_variables` table) |
| `GlobalVariableResolver` | `configs.variables` | Resolves `${vars:...}` references with Caffeine cache |
| `IRestGlobalVariableStore` | `configs.variables.rest` | JAX-RS REST interface |
| `RestGlobalVariableStore` | `configs.variables.rest` | REST implementation with key validation and cache invalidation |

## Two Access Syntaxes

Global variables are available through two complementary syntaxes:

### 1. Template Syntax: `{{vars.<key>}}`

Available in LLM task system prompts and other template contexts. Resolved by the Jinja2/Qute template engine at template processing time.

```
You are an AI assistant powered by {{vars.default-model}}.
Always respond at temperature {{vars.default-temperature}}.
```

### 2. Late-Binding Syntax: `${vars:<key>}`

Available **everywhere** — in LLM task parameters, HTTP call configurations, MCP/A2A settings, embedding configs, Slack configs, and even the `type` field that selects the LLM provider. Resolved by `GlobalVariableResolver` at runtime, after template processing but before vault secret resolution.

```json
{
  "type": "${vars:default-provider}",
  "parameters": {
    "model": "${vars:default-model}",
    "apiKey": "${vault:openai-api-key}",
    "baseUrl": "${vars:api-base-url}"
  }
}
```

### When to Use Which

| Syntax | Where It Works | When to Use |
|--------|---------------|-------------|
| `{{vars.<key>}}` | System prompts, template-processed strings | Dynamic prompt content that changes per-deployment |
| `${vars:<key>}` | Everywhere (params, URLs, headers, type) | Operational config that affects infrastructure |

### Resolution Order

EDDI resolves configuration values in a strict three-step order:

```
1. Jinja2/Qute templates   →  {{vars.x}}, {{snippets.x}}, {{properties.x}}, etc.
2. Global variables         →  ${vars:x}   ← this feature
3. Vault secrets           →  ${vault:x}
```

This ordering is important: vault secrets can contain global variable references, and global variables can be composed with template expressions.

> **⚠️ Nesting is not supported.** `${vars:${vault:x}}` and `${vault:${vars:x}}` will NOT work. Each resolution layer operates independently on the fully-resolved output of the previous layer.

## Where References Work

`${vars:...}` references are resolved in these pipeline callsites:

| Configuration Type | Fields Resolved |
|-------------------|-----------------|
| **LLM Task** (`langchain.json`) | `type` (provider selection), all template parameters |
| **Chat Model Registry** | All model parameters before model creation |
| **HTTP Calls** (`httpcalls.json`) | URL, request body, headers, query parameters |
| **MCP Tool Providers** | API keys, server URLs |
| **A2A Tool Providers** | API keys, agent URLs |
| **Embedding Model Factory** | All embedding model parameters |
| **Embedding Store Factory** | All embedding store parameters |
| **Slack Channel Router** | Channel configuration values (bot tokens, etc.) |

## REST API

All endpoints require the `eddi-admin` or `eddi-editor` role.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/variablestore/variables` | List all global variables |
| `GET` | `/variablestore/variables/{key}` | Get a single variable by key |
| `PUT` | `/variablestore/variables/{key}` | Create or update a variable |
| `DELETE` | `/variablestore/variables/{key}` | Delete a variable |

### Key Validation

Variable keys must match the pattern `[a-zA-Z0-9_.\-]+`:

| Valid ✅ | Invalid ❌ |
|---------|-----------|
| `default-model` | `has space` |
| `api.base-url` | `key=value` |
| `feature_flag_v2` | `key/path` |
| `openai.temperature` | `special!char` |

### Response Examples

**`GET /variablestore/variables`**:

```json
[
  {
    "key": "default-model",
    "value": "gpt-4.1",
    "description": "The default LLM model used by all agents",
    "exportable": true
  },
  {
    "key": "api.base-url",
    "value": "https://api.openai.com",
    "description": "OpenAI API base URL",
    "exportable": false
  }
]
```

**`PUT /variablestore/variables/default-model`** — request body:

```json
{
  "key": "default-model",
  "value": "gpt-4.1-mini",
  "description": "The default LLM model used by all agents",
  "exportable": true
}
```

**`GET /variablestore/variables/missing-key`** — returns `404 Not Found`.

## Caching

Global variables are cached in a Caffeine cache with a **2-minute TTL** (configurable). This means:

- Variables load once from the database, then serve from cache
- After creating/updating/deleting a variable via the REST API, changes take effect **immediately** (the REST endpoint invalidates the cache)
- If the database is updated directly (outside the REST API), changes appear within 2 minutes

### Configuration

```properties
# Cache TTL for global variables (default: 2 minutes)
eddi.variables.cache-ttl-minutes=2
```

### Downstream Cache Invalidation

When global variables change, downstream caches that were built with old variable values need to be evicted. The `GlobalVariableResolver` supports an **invalidation listener** pattern:

- **ChatModelRegistry** registers a listener that clears all cached model instances when variables change
- This ensures that if you change `${vars:default-model}` from `gpt-4.1` to `gpt-4.1-mini`, all agents pick up the new model on their next request

## Use Cases

### Fleet-Wide Model Switching

Set a default model for all agents:

```bash
# Set the variable
curl -X PUT http://localhost:7070/variablestore/variables/default-model \
  -H "Content-Type: application/json" \
  -d '{"key": "default-model", "value": "gpt-4.1", "description": "Fleet default"}'
```

Reference it in all agents' `langchain.json`:
```json
{
  "type": "${vars:default-provider}",
  "parameters": {
    "model": "${vars:default-model}"
  }
}
```

Switch all agents at once:
```bash
curl -X PUT http://localhost:7070/variablestore/variables/default-model \
  -H "Content-Type: application/json" \
  -d '{"key": "default-model", "value": "gpt-4.1-mini"}'
```

### Environment-Specific API Endpoints

```json
{
  "key": "api-gateway-url",
  "value": "https://staging.api.example.com",
  "description": "API gateway URL (changes between staging/production)",
  "exportable": false
}
```

### Feature Flags

```json
{
  "key": "enable-rag",
  "value": "true",
  "description": "Toggle RAG context injection"
}
```

### System Prompt Injection

```
You are an AI assistant for {{vars.company-name}}.
Your default language is {{vars.default-language}}.
Current API version: {{vars.api-version}}.
```

## Comparison: Global Variables vs Secrets vs Properties vs Snippets

| Aspect | Global Variables | Secrets Vault | Properties | Snippets |
|--------|-----------------|---------------|------------|----------|
| **Purpose** | Operational config | Sensitive credentials | Per-user/conversation state | Reusable prompt text |
| **Scope** | Deployment-wide | Deployment-wide | Per-user or per-conversation | Deployment-wide |
| **Encryption** | None | AES-256-GCM | None | None |
| **Visibility** | Fully visible | Write-only | Fully visible | Fully visible |
| **Template syntax** | `{{vars.<key>}}` | — | `{{properties.<key>}}` | `{{snippets.<name>}}` |
| **Late-binding** | `${vars:<key>}` | `${vault:<key>}` | — | — |
| **Versioned** | No | No | No | Yes |
| **REST path** | `/variablestore/variables` | `/secretstore/secrets` | via PropertySetter | `/snippetstore/snippets` |
| **Caching** | 2 min | 5 min | No cache | 5 min |
| **Export** | Configurable (`exportable`) | Always scrubbed | Per-user | Included |

### Decision Guide

- **Need to store an API key?** → Use the **Secrets Vault** (`${vault:...}`)
- **Need to change the LLM model for all agents?** → Use a **Global Variable** (`${vars:...}`)
- **Need to remember a user's name across conversations?** → Use **Properties** with `scope: longTerm`
- **Need reusable system prompt instructions?** → Use **Prompt Snippets** (`{{snippets.<name>}}`)

## Testing

### Backend Tests

| Test Class | Tests | Coverage |
|-----------|-------|---------|
| `GlobalVariableTest` | 7 | Model record, defaults, JSON serialization, equality |
| `GlobalVariableResolverTest` | 14 | Resolution, caching, invalidation, edge cases, patterns |
| `RestGlobalVariableStoreTest` | 11 | CRUD, validation, cache invalidation, key patterns |
| `GlobalVariableStoreTest` | 10 | MongoDB adapter CRUD with mocked MongoCollection |
| `PostgresGlobalVariableStoreTest` | 15 | PostgreSQL adapter CRUD with mocked JDBC, error paths |
| `GlobalVariableCrudIT` | 8 | Full CRUD lifecycle against MongoDB (Testcontainers) |
| `PostgresGlobalVariableCrudIT` | 8 | Full CRUD lifecycle against PostgreSQL (Testcontainers) |

**Aggregate coverage across all 5 production classes: 99% instruction / 93% branch.**

### Integration Tests

The integration tests verify the complete REST API lifecycle:

1. List variables (initially empty)
2. Create a variable via PUT
3. Read the variable by key
4. Update the variable value
5. Verify the variable appears in the list
6. Verify 404 for non-existent keys
7. Verify validation rejects invalid key patterns
8. Delete the variable and verify removal

Tests run against both MongoDB and PostgreSQL via Quarkus DevServices (Testcontainers).
