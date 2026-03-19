# MCP Server — EDDI as MCP Tool Provider

EDDI exposes its bot conversation and administration capabilities via the **Model Context Protocol (MCP)**, enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed bots and manage the platform programmatically.

## Transport

EDDI uses **Streamable HTTP** transport, served by the Quarkus MCP Server extension (`quarkus-mcp-server-http`).

| Endpoint | Description |
|----------|-------------|
| `http://localhost:7070/mcp` | MCP server endpoint (default + admin) |

## Available Tools (33)

### Conversation Tools (11)

| Tool | Description |
|------|-------------|
| `list_bots` | List all deployed bots with status, version, and name |
| `list_bot_configs` | List all bot configurations (including undeployed) |
| `create_conversation` | Start a new conversation with a deployed bot |
| `talk_to_bot` | Send a message and get the bot's response |
| `chat_with_bot` | Create a conversation and send a message in one call |
| `read_conversation` | Read conversation history, memory, and quick replies |
| `read_conversation_log` | Read conversation log as formatted text |
| `list_conversations` | List all conversations for a specific bot |
| `get_bot` | Get a bot's full configuration (packages, name, description) |
| `discover_bots` | Discover deployed bots enriched with intent mappings from bot triggers. Best way to find bots by purpose |
| `chat_managed` | Send a message using intent-based managed conversations (one conversation per intent+userId, auto-creates on first message) |

### Admin Tools (13)

| Tool | Description |
|------|-------------|
| `deploy_bot` | Deploy a bot version to an environment |
| `undeploy_bot` | Undeploy a bot from an environment |
| `get_deployment_status` | Get deployment status of a specific bot version |
| `list_packages` | List all packages (pipeline configurations) |
| `create_bot` | Create a new bot |
| `delete_bot` | Delete a bot (with optional cascade) |
| `update_bot` | Update a bot's name/description and optionally redeploy |
| `read_package` | Read a package's full pipeline configuration |
| `read_resource` | Read any resource config by type (behavior, langchain, httpcalls, output, etc.) |
| `list_bot_triggers` | List all bot triggers (intent→bot mappings) for managed conversations |
| `create_bot_trigger` | Create a bot trigger mapping an intent to one or more bot deployments |
| `update_bot_trigger` | Update an existing bot trigger |
| `delete_bot_trigger` | Delete a bot trigger for a given intent |

### Resource CRUD Tools (5)

| Tool | Description |
|------|-------------|
| `update_resource` | Update any resource config by type and ID. Returns the new version URI |
| `create_resource` | Create a new resource. Returns the new resource ID and URI |
| `delete_resource` | Delete a resource (soft-delete by default, `permanent=true` for hard delete) |
| `apply_bot_changes` | Batch-cascade URI changes through package → bot in ONE pass, optionally redeploy |
| `list_bot_resources` | Walk bot → packages → extensions to get a complete resource inventory in one call |

### Diagnostic Tools (2)

| Tool | Description |
|------|-------------|
| `read_bot_logs` | Read server-side pipeline logs (errors, LLM timeouts) filtered by bot/conversation/level |
| `read_audit_trail` | Read per-task audit entries with LLM details, timing, cost, and tool calls |

### Setup Tools (2)

| Tool | Description |
|------|-------------|
| `setup_bot` | Create a fully working bot in one call: creates behavior rules, LangChain config, optional output/greeting, package, bot, and deploys. Supports built-in tools, quick replies, and sentiment analysis. Default: `anthropic`/`claude-sonnet-4-6` |
| `create_api_bot` | Create a bot from an OpenAPI 3.0/3.1 spec. Parses the spec, generates HttpCalls configs (grouped by API tag), creates the full pipeline, and deploys. Supports endpoint filtering, base URL override, and auth header propagation |

## MCP Resources

EDDI also exposes its documentation as MCP **resources**, allowing AI agents to browse and read the docs programmatically.

| Resource | Description |
|----------|-------------|
| `eddi://docs/index` | List all available documentation pages |
| `eddi://docs/{name}` | Read a specific doc (e.g., `eddi://docs/getting-started`) |

Configure the docs path with: `eddi.docs.path` (default: `docs/`, in Docker: `/deployments/docs`).

## Quick Start

### Claude Desktop Configuration

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "eddi": {
      "url": "http://localhost:7070/mcp"
    }
  }
}
```

### Example Workflow

```
1. list_bots → see deployed bots
2. create_conversation(botId: "my-bot") → get conversationId
3. talk_to_bot(botId: "my-bot", conversationId: "...", message: "Hello!") → get response
4. read_conversation_log(conversationId: "...") → see full history
```

### Discovering Bots by Purpose

```
1. discover_bots() → enriched list with intents per bot
2. list_bot_triggers() → see all intent→bot mappings
```

### Intent-Based Managed Chat

```
1. create_bot_trigger(config: {"intent":"support","botDeployments":[{"botId":"bot-123"}]})
2. chat_managed(intent: "support", userId: "user1", message: "Hello!") → auto-creates conversation
3. chat_managed(intent: "support", userId: "user1", message: "I need help") → reuses same conversation
```

### Inspecting Bot Configuration

```
1. list_bot_resources(botId: "my-bot") → complete resource inventory in one call
2. read_resource(resourceType: "langchain", resourceId: "lc-456") → see LLM config details
```

### Modifying Resources + Cascade

```
1. read_resource("langchain", "lc-456") → get current config
2. update_resource("langchain", "lc-456", version: 1, config: {...}) → new version 2
3. apply_bot_changes(botId, botVersion, [{oldUri: "...?version=1", newUri: "...?version=2"}], redeploy: true)
```

### Debugging a Bot

```
1. read_bot_logs(botId: "my-bot") → see pipeline errors, LLM timeouts
2. read_audit_trail(conversationId: "conv-123") → per-task execution details, LLM tokens, cost
```

---

## Tool Reference — Bot Discovery & Managed Conversations

EDDI provides **two tiers** of conversation management:

| Tier | Tools | Conversations | Use Case |
|------|-------|---------------|----------|
| **Low-level** | `create_conversation` + `talk_to_bot` | Multiple per user, manually managed | Custom apps, multi-conversation UIs |
| **Managed** | `chat_managed` | One per intent+userId, auto-created | Single-window chat, intent-based routing |

The managed tier relies on **bot triggers** — mappings from an _intent_ string to one or more bot deployments. Use the discovery and trigger tools below to configure and interact with this system.

### `discover_bots`

Discover deployed bots with their capabilities. Returns an enriched list of deployed bots, cross-referenced with intent mappings from bot triggers. This is the **best way to find bots by purpose**.

**Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `filter` | string | No | `""` | Filter bots by name (case-insensitive substring match) |
| `environment` | string | No | `"unrestricted"` | Environment: `unrestricted`, `restricted`, or `test` |

**Response:**

```json
{
  "count": 80,
  "bots": [
    {
      "botId": "692f7fe8...",
      "name": "Bob Marley 2",
      "description": "gemini powered Bot",
      "version": 1,
      "status": "READY",
      "environment": "unrestricted",
      "intents": ["bob-marley-2-692f7fe8d6c14292d2b7f70c"]
    },
    {
      "botId": "64513b3c...",
      "name": "Bot Father",
      "description": "Bot to create Connector Bots...",
      "version": 110,
      "status": "READY",
      "environment": "unrestricted"
    }
  ]
}
```

> **Note:** The `intents` array only appears for bots that have bot triggers configured. Bots without triggers are still returned — they can be interacted with via `chat_with_bot` (low-level tier) but not via `chat_managed`.

---

### `chat_managed`

Send a message to a bot using **intent-based managed conversations**. Unlike `chat_with_bot` (which requires a botId and creates multiple conversations), this tool uses an _intent_ to find the right bot and maintains **exactly one conversation per intent+userId** — like a single chat window.

The conversation is auto-created on first message and reused on subsequent calls. Requires a bot trigger to be configured for the intent (see `list_bot_triggers` / `create_bot_trigger`).

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `intent` | string | **Yes** | Intent that maps to a bot trigger. E.g. `"customer_support"` |
| `userId` | string | **Yes** | User ID for conversation management (one conversation per intent+userId) |
| `message` | string | **Yes** | The user message to send |
| `environment` | string | No | Environment: `unrestricted` (default), `restricted`, or `test` |

**Response:**

```json
{
  "environment": "unrestricted",
  "conversationId": "69bc8b93...",
  "botId": "692f7fe8...",
  "userId": "user-123",
  "intent": "bob-marley-2-692f7fe8...",
  "actions": ["send_message", "unknown"],
  "conversationState": "READY",
  "response": {
    "conversationOutputs": [{
      "output": [{ "type": "text", "text": "Hello there! ..." }]
    }],
    "conversationSteps": [...]
  }
}
```

**Behavior:**
- **First call** with a new intent+userId: creates a new conversation and sends the message
- **Subsequent calls** with the same intent+userId: reuses the existing conversation (like continuing in the same chat window)
- Returns an error if no bot trigger is configured for the given intent

---

### `list_bot_triggers`

List all bot triggers (intent→bot mappings). Returns all configured intents with their bot deployments. Bot triggers enable intent-based conversation management via `chat_managed`.

**Parameters:** None.

**Response:**

```json
{
  "count": 48,
  "triggers": [
    {
      "intent": "customer_support",
      "botDeployments": [{
        "environment": "unrestricted",
        "botId": "6544db9b...",
        "initialContext": {}
      }]
    }
  ]
}
```

> **Tip:** Each trigger can map to **multiple bot deployments** — useful for A/B testing or environment-specific routing.

---

### `create_bot_trigger`

Create a bot trigger that maps an intent to one or more bots. Once created, the intent can be used with `chat_managed` to talk to the bot.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `config` | string (JSON) | **Yes** | Full trigger configuration (see schema below) |

**Config schema:**

```json
{
  "intent": "customer_support",
  "botDeployments": [
    {
      "botId": "64513b3c...",
      "environment": "unrestricted",
      "initialContext": {
        "language": { "type": "string", "value": "en" }
      }
    }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `intent` | string | **Yes** | Unique intent identifier. Convention: `slug-botId` (e.g. `support-bot-abc123`) |
| `botDeployments` | array | **Yes** | List of bot deployments this intent routes to |
| `botDeployments[].botId` | string | **Yes** | The bot ID to route messages to |
| `botDeployments[].environment` | string | No | Deployment environment (default: `unrestricted`) |
| `botDeployments[].initialContext` | object | No | Key-value pairs injected into the conversation context on creation |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "created" }
```

---

### `update_bot_trigger`

Update an existing bot trigger. Changes the bot deployments for a given intent (e.g., to point to a new bot version, add A/B routing, or change the initial context).

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `intent` | string | **Yes** | The intent to update |
| `config` | string (JSON) | **Yes** | Full updated trigger configuration (same schema as `create_bot_trigger`) |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "updated" }
```

---

### `delete_bot_trigger`

Delete a bot trigger for a given intent. After deletion, `chat_managed` calls with this intent will return an error. Existing conversations are **not** deleted — they become orphaned but can still be read.

**Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `intent` | string | **Yes** | The intent to delete |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "deleted" }
```

---

### End-to-End Example: Setting Up Managed Chat

```
# 1. Create a bot (using setup_bot or the Manager UI)
setup_bot(name: "Support Agent", systemPrompt: "You are a helpful support agent...", ...)
→ { botId: "abc123", version: 1, status: "deployed" }

# 2. Create a trigger mapping an intent to this bot
create_bot_trigger(config: {
  "intent": "customer_support",
  "botDeployments": [{ "botId": "abc123", "environment": "unrestricted" }]
})

# 3. Chat using the intent — conversation auto-created
chat_managed(intent: "customer_support", userId: "user-1", message: "I need help with billing")
→ { conversationId: "conv-789", response: { output: "I'd be happy to help..." } }

# 4. Continue the same conversation (same conversationId reused)
chat_managed(intent: "customer_support", userId: "user-1", message: "Can you check order #1234?")
→ { conversationId: "conv-789", response: { output: "Let me look that up..." } }

# 5. Different user gets their own conversation
chat_managed(intent: "customer_support", userId: "user-2", message: "Hello")
→ { conversationId: "conv-999", response: { output: "Welcome! How can I help?" } }

# 6. Discover what's available
discover_bots(filter: "Support") → shows the bot with its intent
```

## Configuration

In `application.properties`:

```properties
# MCP Server — Streamable HTTP at /mcp
quarkus.mcp-server.http.root-path=/mcp

# Documentation path for MCP resources (default: docs/)
eddi.docs.path=docs
```

## Tool Filtering

EDDI uses a **whitelist-based `ToolFilter`** (`McpToolFilter.java`) to control which tools are exposed via MCP.

**Why?** EDDI's langchain4j integration registers internal bot tools (calculator, datetime, websearch, etc.) that are meant ONLY for bot pipeline execution — not for external MCP clients. The filter ensures only the 33 intended tools are visible.

To add a new MCP tool: add it to the `MCP_TOOLS` set in `McpToolFilter.java`.

## Authentication & Authorization

- The MCP endpoint inherits EDDI's existing OIDC/Keycloak authentication
- When auth is enabled (`quarkus.oidc.tenant-enabled=true`), MCP clients must provide valid tokens
- Admin tools (deploy, undeploy, delete) should be restricted to authorized users via `@RolesAllowed`
- **Future**: Per-bot MCP access control via bot configuration for multi-tenant SaaS

### Recommended Role Mapping

| Role | Tools |
|------|-------|
| `mcp-user` | `list_bots`, `discover_bots`, `create_conversation`, `talk_to_bot`, `chat_with_bot`, `chat_managed`, `read_conversation*`, `list_bot_triggers`, `read_bot_logs`, `read_audit_trail` |
| `mcp-admin` | All user tools + `deploy_bot`, `undeploy_bot`, `create_bot`, `delete_bot`, `update_bot`, `setup_bot`, `create_api_bot`, resource CRUD, `apply_bot_changes`, `list_bot_resources`, trigger CRUD |

## Sentiment Monitoring

Bots created with `enableSentimentAnalysis=true` (via `setup_bot` or `create_api_bot`) include sentiment data in every LLM response. The sentiment object includes: `score` (-1.0 to +1.0), `trend`, `emotions`, `intent`, `urgency`, `confidence`, and `topicTags`.

This data is stored in conversation memory and can be:
- Read via `read_conversation` (in the conversation snapshot)
- Aggregated for monitoring dashboards (Manager UI log panel)
- Used for alerting (e.g., negative sentiment spike triggers notification)

## Architecture

```
┌──────────────┐     ┌──────────────────────┐
│  MCP Client  │────▶│ quarkus-mcp-server   │
│ (Claude,IDE) │◀────│ Streamable HTTP /mcp │
└──────────────┘     └──────────┬───────────┘
                                │
                  ┌─────────────┼─────────────┐
                  ▼             ▼              ▼
         ┌────────────┐ ┌────────────┐ ┌────────────┐
         │ McpConv.   │ │ McpAdmin   │ │ McpSetup   │
         │   Tools    │ │   Tools    │ │   Tools    │
         └─────┬──────┘ └─────┬──────┘ └─────┬──────┘
               │              │               │
         ┌─────▼──────┐ ┌─────▼──────┐ ┌─────▼──────┐
         │ REST API   │ │ REST API   │ │ REST API   │
         │ endpoints  │ │ endpoints  │ │ + OpenAPI  │
         └────────────┘ └────────────┘ └────────────┘

         ┌──────────────────────────────────────────┐
         │         McpDocResources                   │
         │   @Resource / @ResourceTemplate           │
         │   eddi://docs/{name}  (filesystem I/O)    │
         └──────────────────────────────────────────┘
```
