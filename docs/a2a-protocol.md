# Agent-to-Agent (A2A) Protocol

> **Status:** Available since EDDI v6.0.0  
> **Spec:** [Google A2A Protocol](https://github.com/google/A2A)

EDDI implements the Agent-to-Agent (A2A) protocol for distributed peer-to-peer agent communication. Agents can **expose** their capabilities via Agent Cards, and **consume** remote A2A agents as tools.

---

## Server — Exposing Agents via A2A

### Enable A2A for an Agent

Add A2A fields to your agent configuration:

```json
{
  "a2aEnabled": true,
  "description": "Customer support agent specializing in order tracking",
  "a2aSkills": ["order-tracking", "refund-processing"],
  "workflows": ["eddi://ai.labs.workflow/workflowstore/workflows/..."]
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `a2aEnabled` | boolean | `false` | Opt-in flag for A2A discovery |
| `description` | string | `"EDDI conversational AI agent"` | Human-readable description for the Agent Card |
| `a2aSkills` | string[] | `["chat"]` | Skills advertised in the Agent Card |

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/.well-known/agent.json` | Default Agent Card (first A2A-enabled agent) |
| `GET` | `/a2a/agents/{agentId}/agent.json` | Per-agent Agent Card |
| `GET` | `/a2a/agents` | List all A2A-enabled agents |
| `POST` | `/a2a/agents/{agentId}` | JSON-RPC 2.0 endpoint |

### JSON-RPC Methods

| Method | Description |
|---|---|
| `tasks/send` | Send a message and get a synchronous response |
| `tasks/get` | Retrieve task status by task ID |
| `tasks/cancel` | Cancel (end) a task's conversation |

### Example: Send a Message

```json
{
  "jsonrpc": "2.0",
  "method": "tasks/send",
  "id": "req-1",
  "params": {
    "id": "task-1",
    "message": {
      "role": "user",
      "parts": [{ "type": "text", "text": "Track order #12345" }]
    }
  }
}
```

### Configuration Properties

| Property | Default | Description |
|---|---|---|
| `eddi.a2a.enabled` | `true` | Master toggle for all A2A endpoints |
| `eddi.a2a.base-url` | `http://localhost:7070` | Base URL used in Agent Card URLs |

---

## Client — Consuming Remote A2A Agents as Tools

Configure remote A2A agents in your LLM task configuration. They are discovered and merged into the tool-calling loop alongside built-in, MCP, and httpcall tools.

### LLM Task Configuration

```json
{
  "systemMessage": "You are an orchestrator agent...",
  "a2aAgents": [
    {
      "url": "https://remote-eddi.example.com/a2a/agents/support-agent",
      "name": "support-agent",
      "apiKey": "${vault:remote-agent-key}",
      "timeoutMs": 30000,
      "skillsFilter": ["order-tracking"]
    }
  ]
}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `url` | string | *required* | Base URL of the remote A2A agent |
| `name` | string | from Agent Card | Display name (used in tool naming) |
| `apiKey` | string | — | **Must be a vault reference** (`${vault:...}`) to prevent secret leakage |
| `timeoutMs` | long | `30000` | Timeout for A2A operations |
| `skillsFilter` | string[] | all skills | Only expose specific skills (by id or name) |

> **⚠️ Security:** Always use vault references (`${vault:my-key}`) for API keys. Raw keys trigger a runtime warning and risk leakage in configuration exports. See [Secrets Vault](secrets-vault.md).

### How It Works

1. **Discovery:** `A2AToolProviderManager` fetches the Agent Card from `{url}/agent.json`
2. **Mapping:** Each skill becomes a `ToolSpecification` with a `message` parameter
3. **Execution:** When the LLM calls the tool, a JSON-RPC `tasks/send` request is sent
4. **Caching:** Agent Cards are cached for 5 minutes to avoid redundant fetches

### Tool Naming

Tools are named `{agentName}_{skillId}`, sanitized to `[a-z0-9_]`. Example: `support_agent_order_tracking`.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  EDDI Instance A (Server)                               │
│                                                         │
│  AgentConfiguration ─→ AgentCardService ─→ Agent Card   │
│  RestA2AEndpoint ←── JSON-RPC ←── A2ATaskHandler        │
│                                    ↓                    │
│                            ConversationService.say()    │
└─────────────────────────────────────────────────────────┘
          ▲                           │
          │  GET /agent.json          │  POST tasks/send
          │  (discovery)              │  (execution)
          │                           ▼
┌─────────────────────────────────────────────────────────┐
│  EDDI Instance B (Client)                               │
│                                                         │
│  LlmConfiguration.Task.a2aAgents[]                      │
│            ↓                                            │
│  A2AToolProviderManager ─→ ToolSpecification            │
│            ↓                                            │
│  AgentOrchestrator (merged with MCP + httpcall tools)   │
└─────────────────────────────────────────────────────────┘
```

## Key Files

| File | Purpose |
|---|---|
| `engine/a2a/A2AModels.java` | Protocol records (Agent Card, JSON-RPC, Task) |
| `engine/a2a/AgentCardService.java` | Generates Agent Cards from agent configs |
| `engine/a2a/A2ATaskHandler.java` | Bridges JSON-RPC to ConversationService |
| `engine/a2a/RestA2AEndpoint.java` | JAX-RS endpoints |
| `modules/llm/impl/A2AToolProviderManager.java` | Client-side discovery and tool execution |
| `modules/llm/model/LlmConfiguration.java` | `A2AAgentConfig` configuration model |
| `configs/agents/model/AgentConfiguration.java` | `a2aEnabled`, `a2aSkills`, `description` |
