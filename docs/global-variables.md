# Global Variables

EDDI includes a built-in global variable store for managing configuration values like default LLM model names, API base URLs, temperature settings, and feature flags. Variables are **scoped per tenant** — single-tenant deployments use `"default"` implicitly, while multi-tenant deployments can maintain separate variable sets per tenant. Unlike secrets, global variables are **not encrypted** and are **fully visible** in the UI and logs.

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
| `GlobalVariable` | `configs.variables.model` | Record: `tenantId`, `key`, `value`, `description`, `exportable` |
| `IGlobalVariableStore` | `configs.variables` | Persistence interface (non-versioned, tenant-scoped key-value) |
| `GlobalVariableStore` | `configs.variables.mongo` | MongoDB implementation (`globalvariables` collection, composite `_id: tenantId/key`) |
| `PostgresGlobalVariableStore` | `datastore.postgres` | PostgreSQL implementation (`global_variables` table, PK: `(tenant_id, key)`) |
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

### 2. Late-Binding Syntax: `${vars:<key>}` / `${vars:tenantId/<key>}`

Available **everywhere** — in LLM task parameters, HTTP call configurations, MCP/A2A settings, embedding configs, Slack configs, and even the `type` field that selects the LLM provider. Resolved by `GlobalVariableResolver` at runtime, after template processing but before vault secret resolution.

Supports two forms, mirroring the vault's pattern:

| Form | Syntax | Behavior |
|------|--------|----------|
| **Short form** | `${vars:key}` | Uses the context tenant (defaults to `"default"`) |
| **Full form** | `${vars:tenantId/key}` | Uses the explicit tenant |

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

Multi-tenant example:
```json
{
  "parameters": {
    "model": "${vars:tenant-a/default-model}"
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
| `GET` | `/variablestore/variables/{tenantId}` | List all variables for a tenant |
| `GET` | `/variablestore/variables/{tenantId}/{key}` | Get a single variable by tenant and key |
| `PUT` | `/variablestore/variables/{tenantId}/{key}` | Create or update a variable |
| `DELETE` | `/variablestore/variables/{tenantId}/{key}` | Delete a variable |

> **Single-tenant shortcut:** Use `default` as the tenantId for single-tenant deployments.

### ID Validation

Both `tenantId` and `key` must match the pattern `[a-zA-Z0-9_.\-]+`:

| Valid ✅ | Invalid ❌ |
|---------|-----------|
| `default` | `has space` |
| `tenant-a` | `key=value` |
| `api.base-url` | `key/path` |
| `feature_flag_v2` | `special!char` |

### Response Examples

**`GET /variablestore/variables/default`**:

```json
[
  {
    "tenantId": "default",
    "key": "default-model",
    "value": "gpt-4.1",
    "description": "The default LLM model used by all agents",
    "exportable": true
  },
  {
    "tenantId": "default",
    "key": "api.base-url",
    "value": "https://api.openai.com",
    "description": "OpenAI API base URL",
    "exportable": false
  }
]
```

**`PUT /variablestore/variables/default/default-model`** — request body:

```json
{
  "key": "default-model",
  "value": "gpt-4.1-mini",
  "description": "The default LLM model used by all agents",
  "exportable": true
}
```

**`GET /variablestore/variables/default/missing-key`** — returns `404 Not Found`.

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
# Set the variable (default tenant)
curl -X PUT http://localhost:7070/variablestore/variables/default/default-model \
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
curl -X PUT http://localhost:7070/variablestore/variables/default/default-model \
  -H "Content-Type: application/json" \
  -d '{"key": "default-model", "value": "gpt-4.1-mini"}'
```

### Multi-Tenant Model Switching

Set a different default model per tenant:

```bash
# Tenant A uses GPT
curl -X PUT http://localhost:7070/variablestore/variables/tenant-a/default-model \
  -H "Content-Type: application/json" \
  -d '{"key": "default-model", "value": "gpt-4.1"}'

# Tenant B uses Claude
curl -X PUT http://localhost:7070/variablestore/variables/tenant-b/default-model \
  -H "Content-Type: application/json" \
  -d '{"key": "default-model", "value": "claude-sonnet-4-20250514"}'
```

Reference in agent config:
```json
{
  "parameters": {
    "model": "${vars:default-model}"
  }
}
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
| **Scope** | Per-tenant (all agents) | Per-tenant | Per-user or per-conversation | Deployment-wide |
| **Encryption** | None | AES-256-GCM | None | None |
| **Visibility** | Fully visible | Write-only | Fully visible | Fully visible |
| **Template syntax** | `{{vars.<key>}}` | — | `{{properties.<key>}}` | `{{snippets.<name>}}` |
| **Late-binding** | `${vars:<key>}` or `${vars:tenantId/<key>}` | `${vault:<key>}` or `${vault:tenantId/<key>}` | — | — |
| **Versioned** | No | No | No | Yes |
| **REST path** | `/variablestore/variables/{tenantId}` | `/secretstore/secrets/{tenantId}` | via PropertySetter | `/snippetstore/snippets` |
| **Caching** | 2 min (per-tenant) | 5 min | No cache | 5 min |
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
| `GlobalVariableTest` | 9 | Model record, tenant defaults, JSON serialization, equality |
| `GlobalVariableResolverTest` | 16 | Short/full form resolution, tenant caching, invalidation, edge cases |
| `RestGlobalVariableStoreTest` | 11 | CRUD, tenant+key validation, cache invalidation, patterns |
| `GlobalVariableStoreTest` | 9 | MongoDB adapter CRUD with mocked MongoCollection, tenant scoping |
| `PostgresGlobalVariableStoreTest` | 14 | PostgreSQL adapter CRUD with mocked JDBC, tenant isolation, error paths |
| `GlobalVariableCrudIT` | 8 | Full CRUD lifecycle against MongoDB (Testcontainers) |
| `PostgresGlobalVariableCrudIT` | 8 | Full CRUD lifecycle against PostgreSQL (Testcontainers) |

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
