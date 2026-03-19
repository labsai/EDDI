# MCP Server — EDDI as MCP Tool Provider

EDDI exposes its bot conversation and administration capabilities via the **Model Context Protocol (MCP)**, enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed bots and manage the platform programmatically.

## Transport

EDDI uses **Streamable HTTP** transport, served by the Quarkus MCP Server extension (`quarkus-mcp-server-http`).

| Endpoint | Description |
|----------|-------------|
| `http://localhost:7070/mcp` | MCP server endpoint (default + admin) |

## Available Tools (27)

### Conversation Tools (9)

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

### Admin Tools (9)

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

**Why?** EDDI's langchain4j integration registers internal bot tools (calculator, datetime, websearch, etc.) that are meant ONLY for bot pipeline execution — not for external MCP clients. The filter ensures only the 27 intended tools are visible.

To add a new MCP tool: add it to the `MCP_TOOLS` set in `McpToolFilter.java`.

## Authentication & Authorization

- The MCP endpoint inherits EDDI's existing OIDC/Keycloak authentication
- When auth is enabled (`quarkus.oidc.tenant-enabled=true`), MCP clients must provide valid tokens
- Admin tools (deploy, undeploy, delete) should be restricted to authorized users via `@RolesAllowed`
- **Future**: Per-bot MCP access control via bot configuration for multi-tenant SaaS

### Recommended Role Mapping

| Role | Tools |
|------|-------|
| `mcp-user` | `list_bots`, `create_conversation`, `talk_to_bot`, `chat_with_bot`, `read_conversation*`, `read_bot_logs`, `read_audit_trail` |
| `mcp-admin` | All user tools + `deploy_bot`, `undeploy_bot`, `create_bot`, `delete_bot`, `update_bot`, `setup_bot`, `create_api_bot`, resource CRUD, `apply_bot_changes`, `list_bot_resources` |

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
