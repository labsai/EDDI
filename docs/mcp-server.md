# MCP Server — EDDI as MCP Tool Provider

EDDI exposes its agent conversation and administration capabilities via the **Model Context Protocol (MCP)**, enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed agents and manage the platform programmatically.

## Transport

EDDI uses **Streamable HTTP** transport, served by the Quarkus MCP Server extension (`quarkus-mcp-server-http`).

| Endpoint                    | Description                           |
| --------------------------- | ------------------------------------- |
| `http://localhost:7070/mcp` | MCP server endpoint (default + admin) |

## Available Tools (39)

### Conversation Tools (11)

| Tool                    | Description                                                                                                                 |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `list_agents`           | List all deployed agents with status, version, and name                                                                     |
| `list_agent_configs`    | List all agent configurations (including undeployed)                                                                        |
| `create_conversation`   | Start a new conversation with a deployed agent                                                                              |
| `talk_to_agent`         | Send a message and get the agent's response                                                                                 |
| `chat_with_agent`       | Create a conversation and send a message in one call                                                                        |
| `read_conversation`     | Read conversation history, memory, and quick replies                                                                        |
| `read_conversation_log` | Read conversation log as formatted text                                                                                     |
| `list_conversations`    | List all conversations for a specific agent                                                                                 |
| `get_agent`             | Get an agent's full configuration (packages, name, description)                                                              |
| `discover_agents`       | Discover deployed agents enriched with intent mappings from agent triggers. Best way to find agents by purpose              |
| `chat_managed`          | Send a message using intent-based managed conversations (one conversation per intent+userId, auto-creates on first message) |

### Admin Tools (13)

| Tool                    | Description                                                                     |
| ----------------------- | ------------------------------------------------------------------------------- |
| `deploy_agent`          | Deploy an agent version to an environment                                        |
| `undeploy_agent`        | Undeploy an agent from an environment                                            |
| `get_deployment_status` | Get deployment status of a specific agent version                               |
| `list_workflows`        | List all packages (pipeline configurations)                                     |
| `create_agent`          | Create a new agent                                                              |
| `delete_agent`          | Delete an agent (with optional cascade)                                          |
| `update_agent`          | Update an agent's name/description and optionally redeploy                       |
| `read_workflow`         | Read a package's full pipeline configuration                                    |
| `read_resource`         | Read any resource config by type (behavior, langchain, httpcalls, output, etc.) |
| `list_agent_triggers`   | List all agent triggers (intent→agent mappings) for managed conversations       |
| `create_agent_trigger`  | Create an agent trigger mapping an intent to one or more agent deployments       |
| `update_agent_trigger`  | Update an existing agent trigger                                                |
| `delete_agent_trigger`  | Delete an agent trigger for a given intent                                       |

### Resource CRUD Tools (5)

| Tool                   | Description                                                                         |
| ---------------------- | ----------------------------------------------------------------------------------- |
| `update_resource`      | Update any resource config by type and ID. Returns the new version URI              |
| `create_resource`      | Create a new resource. Returns the new resource ID and URI                          |
| `delete_resource`      | Delete a resource (soft-delete by default, `permanent=true` for hard delete)        |
| `apply_agent_changes`  | Batch-cascade URI changes through package → agent in ONE pass, optionally redeploy  |
| `list_agent_resources` | Walk agent → packages → extensions to get a complete resource inventory in one call |

### Diagnostic Tools (2)

| Tool               | Description                                                                                |
| ------------------ | ------------------------------------------------------------------------------------------ |
| `read_agent_logs`  | Read server-side pipeline logs (errors, LLM timeouts) filtered by agent/conversation/level |
| `read_audit_trail` | Read per-task audit entries with LLM details, timing, cost, and tool calls                 |

### Setup Tools (2)

| Tool               | Description                                                                                                                                                                                                                                         |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `setup_agent`      | Create a fully working agent in one call: creates behavior rules, LangChain config, optional output/greeting, package, agent, and deploys. Supports built-in tools, quick replies, and sentiment analysis. Default: `anthropic`/`claude-sonnet-4-6` |
| `create_api_agent` | Create an agent from an OpenAPI 3.0/3.1 spec. Parses the spec, generates HttpCalls configs (grouped by API tag), creates the full pipeline, and deploys. Supports endpoint filtering, base URL override, and auth header propagation                 |

### Schedule Management Tools (6)

| Tool                    | Description                                                                                                                                                                                         |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `create_schedule`       | Create a new scheduled agent trigger (cron job or heartbeat). For CRON: provide `cronExpression`. For HEARTBEAT: provide `heartbeatIntervalSeconds`. Heartbeats default to persistent conversations |
| `list_schedules`        | List all scheduled agent triggers with name, type, cron/interval, status, next fire time, and fire count. Optionally filter by agentId                                                              |
| `read_schedule`         | Read a schedule's full configuration including recent fire history (last 10 executions)                                                                                                             |
| `delete_schedule`       | Delete a scheduled agent trigger                                                                                                                                                                    |
| `fire_schedule_now`     | Manually trigger a schedule fire immediately. Useful for testing or one-off executions                                                                                                              |
| `retry_failed_schedule` | Re-queue a dead-lettered schedule for another fire attempt after fixing the cause of failure                                                                                                        |

## MCP Resources

EDDI also exposes its documentation as MCP **resources**, allowing AI agents to browse and read the docs programmatically.

| Resource             | Description                                               |
| -------------------- | --------------------------------------------------------- |
| `eddi://docs/index`  | List all available documentation pages                    |
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
1. list_agents → see deployed agents
2. create_conversation(agentId: "my-agent") → get conversationId
3. talk_to_agent(agentId: "my-agent", conversationId: "...", message: "Hello!") → get response
4. read_conversation_log(conversationId: "...") → see full history
```

### Discovering Agents by Purpose

```
1. discover_agents() → enriched list with intents per agent
2. list_agent_triggers() → see all intent→agent mappings
```

### Intent-Based Managed Chat

```
1. create_agent_trigger(config: {"intent":"support","agentDeployments":[{"agentId":"agent-123"}]})
2. chat_managed(intent: "support", userId: "user1", message: "Hello!") → auto-creates conversation
3. chat_managed(intent: "support", userId: "user1", message: "I need help") → reuses same conversation
```

### Inspecting Agent Configuration

```
1. list_agent_resources(agentId: "my-agent") → complete resource inventory in one call
2. read_resource(resourceType: "langchain", resourceId: "lc-456") → see LLM config details
```

### Modifying Resources + Cascade

```
1. read_resource("langchain", "lc-456") → get current config
2. update_resource("langchain", "lc-456", version: 1, config: {...}) → new version 2
3. apply_agent_changes(agentId, agentVersion, [{oldUri: "...?version=1", newUri: "...?version=2"}], redeploy: true)
```

### Debugging an Agent

```
1. read_agent_logs(agentId: "my-agent") → see pipeline errors, LLM timeouts
2. read_audit_trail(conversationId: "conv-123") → per-task execution details, LLM tokens, cost
```

### Scheduling a Cron Job

```
1. create_schedule(agentId: "my-agent", triggerType: "CRON", cron: "0 9 * * MON-FRI",
     message: "Daily morning check-in", name: "Weekday Morning Check")
   → { scheduleId: "sched-1", description: "At 09:00 on every weekday", nextFire: "..." }
2. list_schedules() → see all scheduled triggers with status
3. fire_schedule_now(scheduleId: "sched-1") → test immediately
4. read_schedule(scheduleId: "sched-1") → see full config + fire logs
```

### Setting Up a Heartbeat

```
1. create_schedule(agentId: "my-agent", triggerType: "HEARTBEAT", heartbeatIntervalSeconds: 300,
     name: "Health Heartbeat")
   → { scheduleId: "hb-1", description: "Every 5 minutes", conversationStrategy: "persistent" }
   # Heartbeats default to: persistent conversation, "heartbeat" message, drift-proof scheduling
2. read_schedule(scheduleId: "hb-1") → check next fire time and conversation ID
3. retry_failed_schedule(scheduleId: "hb-1") → requeue if dead-lettered
```

---

## Tool Reference — Agent Discovery & Managed Conversations

EDDI provides **two tiers** of conversation management:

| Tier          | Tools                                   | Conversations                       | Use Case                                 |
| ------------- | --------------------------------------- | ----------------------------------- | ---------------------------------------- |
| **Low-level** | `create_conversation` + `talk_to_agent` | Multiple per user, manually managed | Custom apps, multi-conversation UIs      |
| **Managed**   | `chat_managed`                          | One per intent+userId, auto-created | Single-window chat, intent-based routing |

The managed tier relies on **agent triggers** — mappings from an _intent_ string to one or more agent deployments. Use the discovery and trigger tools below to configure and interact with this system.

### `discover_agents`

Discover deployed agents with their capabilities. Returns an enriched list of deployed agents, cross-referenced with intent mappings from agent triggers. This is the **best way to find agents by purpose**.

**Parameters:**

| Parameter     | Type   | Required | Default        | Description                                              |
| ------------- | ------ | -------- | -------------- | -------------------------------------------------------- |
| `filter`      | string | No       | `""`           | Filter agents by name (case-insensitive substring match) |
| `environment` | string | No       | `"production"` | Environment: `production`, `production`, or `test`       |

**Response:**

```json
{
  "count": 80,
  "agents": [
    {
      "agentId": "692f7fe8...",
      "name": "Bob Marley 2",
      "description": "gemini powered Agent",
      "version": 1,
      "status": "READY",
      "environment": "production",
      "intents": ["bob-marley-2-692f7fe8d6c14292d2b7f70c"]
    },
    {
      "agentId": "64513b3c...",
      "name": "Agent Father",
      "description": "Agent to create Connector Agents...",
      "version": 110,
      "status": "READY",
      "environment": "production"
    }
  ]
}
```

> **Note:** The `intents` array only appears for agents that have agent triggers configured. Agents without triggers are still returned — they can be interacted with via `chat_with_agent` (low-level tier) but not via `chat_managed`.

---

### `chat_managed`

Send a message to an agent using **intent-based managed conversations**. Unlike `chat_with_agent` (which requires a agentId and creates multiple conversations), this tool uses an _intent_ to find the right agent and maintains **exactly one conversation per intent+userId** — like a single chat window.

The conversation is auto-created on first message and reused on subsequent calls. Requires an agent trigger to be configured for the intent (see `list_agent_triggers` / `create_agent_trigger`).

**Parameters:**

| Parameter     | Type   | Required | Description                                                              |
| ------------- | ------ | -------- | ------------------------------------------------------------------------ |
| `intent`      | string | **Yes**  | Intent that maps to an agent trigger. E.g. `"customer_support"`           |
| `userId`      | string | **Yes**  | User ID for conversation management (one conversation per intent+userId) |
| `message`     | string | **Yes**  | The user message to send                                                 |
| `environment` | string | No       | Environment: `production` (default), `production`, or `test`             |

**Response:**

```json
{
  "environment": "production",
  "conversationId": "69bc8b93...",
  "agentId": "692f7fe8...",
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
- Returns an error if no agent trigger is configured for the given intent

---

### `list_agent_triggers`

List all agent triggers (intent→agent mappings). Returns all configured intents with their agent deployments. Agent triggers enable intent-based conversation management via `chat_managed`.

**Parameters:** None.

**Response:**

```json
{
  "count": 48,
  "triggers": [
    {
      "intent": "customer_support",
      "agentDeployments": [
        {
          "environment": "production",
          "agentId": "6544db9b...",
          "initialContext": {}
        }
      ]
    }
  ]
}
```

> **Tip:** Each trigger can map to **multiple agent deployments** — useful for A/B testing or environment-specific routing.

---

### `create_agent_trigger`

Create an agent trigger that maps an intent to one or more agents. Once created, the intent can be used with `chat_managed` to talk to the agent.

**Parameters:**

| Parameter | Type          | Required | Description                                   |
| --------- | ------------- | -------- | --------------------------------------------- |
| `config`  | string (JSON) | **Yes**  | Full trigger configuration (see schema below) |

**Config schema:**

```json
{
  "intent": "customer_support",
  "agentDeployments": [
    {
      "agentId": "64513b3c...",
      "environment": "production",
      "initialContext": {
        "language": { "type": "string", "value": "en" }
      }
    }
  ]
}
```

| Field                               | Type   | Required | Description                                                                        |
| ----------------------------------- | ------ | -------- | ---------------------------------------------------------------------------------- |
| `intent`                            | string | **Yes**  | Unique intent identifier. Convention: `slug-agentId` (e.g. `support-agent-abc123`) |
| `agentDeployments`                  | array  | **Yes**  | List of agent deployments this intent routes to                                    |
| `agentDeployments[].agentId`        | string | **Yes**  | The agent ID to route messages to                                                  |
| `agentDeployments[].environment`    | string | No       | Deployment environment (default: `production`)                                     |
| `agentDeployments[].initialContext` | object | No       | Key-value pairs injected into the conversation context on creation                 |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "created" }
```

---

### `update_agent_trigger`

Update an existing agent trigger. Changes the agent deployments for a given intent (e.g., to point to a new agent version, add A/B routing, or change the initial context).

**Parameters:**

| Parameter | Type          | Required | Description                                                                |
| --------- | ------------- | -------- | -------------------------------------------------------------------------- |
| `intent`  | string        | **Yes**  | The intent to update                                                       |
| `config`  | string (JSON) | **Yes**  | Full updated trigger configuration (same schema as `create_agent_trigger`) |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "updated" }
```

---

### `delete_agent_trigger`

Delete an agent trigger for a given intent. After deletion, `chat_managed` calls with this intent will return an error. Existing conversations are **not** deleted — they become orphaned but can still be read.

**Parameters:**

| Parameter | Type   | Required | Description          |
| --------- | ------ | -------- | -------------------- |
| `intent`  | string | **Yes**  | The intent to delete |

**Response:**

```json
{ "intent": "customer_support", "status": 200, "action": "deleted" }
```

---

### End-to-End Example: Setting Up Managed Chat

```
# 1. Create an agent (using setup_agent or the Manager UI)
setup_agent(name: "Support Agent", systemPrompt: "You are a helpful support agent...", ...)
→ { agentId: "abc123", version: 1, status: "deployed" }

# 2. Create a trigger mapping an intent to this agent
create_agent_trigger(config: {
  "intent": "customer_support",
  "agentDeployments": [{ "agentId": "abc123", "environment": "production" }]
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
discover_agents(filter: "Support") → shows the agent with its intent
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

**Why?** EDDI's langchain4j integration registers internal agent tools (calculator, datetime, websearch, etc.) that are meant ONLY for agent pipeline execution — not for external MCP clients. The filter ensures only the 33 intended tools are visible.

To add a new MCP tool: add it to the `MCP_TOOLS` set in `McpToolFilter.java`.

## Authentication & Authorization

- The MCP endpoint inherits EDDI's existing OIDC/Keycloak authentication
- When auth is enabled (`quarkus.oidc.tenant-enabled=true`), MCP clients must provide valid tokens
- Admin tools (deploy, undeploy, delete) should be production to authorized users via `@RolesAllowed`
- **Future**: Per-agent MCP access control via agent configuration for multi-tenant SaaS

### Recommended Role Mapping

| Role        | Tools                                                                                                                                                                                                            |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `mcp-user`  | `list_agents`, `discover_agents`, `create_conversation`, `talk_to_agent`, `chat_with_agent`, `chat_managed`, `read_conversation*`, `list_agent_triggers`, `read_agent_logs`, `read_audit_trail`                  |
| `mcp-admin` | All user tools + `deploy_agent`, `undeploy_agent`, `create_agent`, `delete_agent`, `update_agent`, `setup_agent`, `create_api_agent`, resource CRUD, `apply_agent_changes`, `list_agent_resources`, trigger CRUD |

## Sentiment Monitoring

Agents created with `enableSentimentAnalysis=true` (via `setup_agent` or `create_api_agent`) include sentiment data in every LLM response. The sentiment object includes: `score` (-1.0 to +1.0), `trend`, `emotions`, `intent`, `urgency`, `confidence`, and `topicTags`.

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

## MCP Client — Agents as MCP Consumers

In addition to acting as an MCP server, EDDI agents can also **consume external MCP servers** as tool providers. This enables agents to call tools exposed by other MCP-compatible services during conversations.

### Configuration

Add `mcpServers` to a LangChain task configuration:

```json
{
  "tasks": [
    {
      "type": "anthropic",
      "mcpServers": [
        {
          "url": "http://localhost:7070/mcp",
          "name": "eddi-docs",
          "apiKey": "${vault:mcp-api-key}",
          "timeoutMs": 30000
        },
        {
          "url": "https://tools.example.com/mcp",
          "name": "external-tools"
        }
      ]
    }
  ]
}
```

| Field       | Type   | Required | Default            | Description                                                                        |
| ----------- | ------ | -------- | ------------------ | ---------------------------------------------------------------------------------- |
| `url`       | string | **Yes**  | —                  | MCP server URL (Streamable HTTP transport)                                         |
| `name`      | string | No       | URL                | Human-readable name for logging                                                    |
| `transport` | string | No       | `"streamableHttp"` | Transport type (only `streamableHttp` supported)                                   |
| `apiKey`    | string | No       | —                  | API key, sent as `Authorization: Bearer <key>`. Supports `${vault:key}` references |
| `timeoutMs` | long   | No       | `30000`            | Connection and request timeout in milliseconds                                     |

### Using `setup_agent` with MCP Servers

```
setup_agent(
  name: "My Agent",
  systemPrompt: "You are helpful",
  mcpServers: "http://localhost:7070/mcp, https://tools.example.com/mcp",
  ...
)
```

The `mcpServers` parameter accepts a comma-separated list of URLs.

### Architecture

```
┌──────────────┐     ┌──────────────────────┐
│  User sends  │────▶│    LangchainTask      │
│   message    │     │  (EDDI pipeline)      │
└──────────────┘     └──────────┬────────────┘
                                │
                    ┌───────────┼───────────┐
                    ▼           ▼           ▼
           ┌──────────┐ ┌──────────┐ ┌──────────┐
           │ Built-in │ │  Custom  │ │   MCP    │
           │  Tools   │ │  Tools   │ │  Tools   │
           │(calc,dt) │ │(HttpCall)│ │(external)│
           └──────────┘ └──────────┘ └────┬─────┘
                                          │
                              ┌───────────┼───────────┐
                              ▼                       ▼
                    ┌──────────────┐       ┌──────────────┐
                    │ MCP Server 1 │       │ MCP Server 2 │
                    │ (EDDI docs)  │       │ (3rd party)  │
                    └──────────────┘       └──────────────┘
```

### Key Behaviors

- **Graceful degradation**: Failed MCP connections log warnings but never kill the pipeline
- **Connection caching**: `McpToolProviderManager` reuses connections across conversation turns
- **Budget/rate-limiting**: MCP tools are subject to the same `ToolExecutionService` controls as built-in tools
- **Vault references**: API keys support `${vault:key}` syntax via `SecretResolver`
- **Clean shutdown**: All MCP clients are closed on application shutdown via `@PreDestroy`
