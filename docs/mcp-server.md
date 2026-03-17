# MCP Server — EDDI as MCP Tool Provider

EDDI exposes its bot conversation and administration capabilities via the **Model Context Protocol (MCP)**, enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed bots and manage the platform programmatically.

## Transport

EDDI uses **Streamable HTTP** transport, served by the Quarkus MCP Server extension (`quarkus-mcp-server-http`).

| Endpoint | Description |
|----------|-------------|
| `http://localhost:7070/mcp` | MCP server endpoint (default + admin) |

## Available Tools

### Conversation Tools

| Tool | Description |
|------|-------------|
| `listBots` | List all deployed bots with status, version, and name |
| `listBotConfigs` | List all bot configurations (including undeployed) |
| `createConversation` | Start a new conversation with a deployed bot |
| `talkToBot` | Send a message and get the bot's response |
| `readConversation` | Read conversation history and memory |
| `readConversationLog` | Read conversation log as formatted text |

### Admin Tools

| Tool | Description |
|------|-------------|
| `deployBot` | Deploy a bot version to an environment |
| `undeployBot` | Undeploy a bot from an environment |
| `getDeploymentStatus` | Get deployment status of a specific bot version |
| `listPackages` | List all packages (pipeline configurations) |
| `createBot` | Create a new bot |
| `deleteBot` | Delete a bot (with optional cascade) |

### Setup Tools

| Tool | Description |
|------|-------------|
| `setup_bot` | Create a fully working bot in one call: creates behavior rules, LangChain config, optional output/greeting, package, bot, and deploys. Supports built-in tools (calculator, datetime, websearch). Default: `anthropic`/`claude-sonnet-4-6` |
| `create_api_bot` | Create a bot from an OpenAPI 3.0/3.1 spec. Parses the spec, generates HttpCalls configs (grouped by API tag), creates behavior/langchain/package/bot, and deploys. The LLM gets API endpoint context injected into its system prompt. Supports endpoint filtering, base URL override, and auth header propagation |


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
1. listBots → see deployed bots
2. createConversation(botId: "my-bot") → get conversationId
3. talkToBot(botId: "my-bot", conversationId: "...", message: "Hello!") → get response
4. readConversationLog(conversationId: "...") → see full history
```

## Configuration

In `application.properties`:

```properties
# MCP Server — Streamable HTTP at /mcp
quarkus.mcp-server.http.root-path=/mcp
```

## Security Considerations

- The MCP endpoint inherits EDDI's existing OIDC/Keycloak authentication
- When auth is enabled (`quarkus.oidc.tenant-enabled=true`), MCP clients must provide valid tokens
- Admin tools (deploy, undeploy, delete) should be restricted to authorized users
- Future: per-bot MCP access control via bot configuration

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
         │ IConv.     │ │ IRestBot   │ │ OpenAPI    │
         │ Service    │ │ Admin/Store│ │ Parser     │
         └────────────┘ └────────────┘ └────────────┘
```

