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

| Method | Path | Description | Anonymous? |
|---|---|---|---|
| `GET` | `/.well-known/agent.json` | Default Agent Card (first A2A-enabled agent) | Yes |
| `GET` | `/a2a/agents/{agentId}/agent.json` | Per-agent Agent Card | Yes |
| `GET` | `/a2a/agents` | List all A2A-enabled agents | **No** |
| `POST` | `/a2a/agents/{agentId}` | JSON-RPC 2.0 endpoint | **No** |
| `GET` | `/.well-known/capabilities?skill=…` | Capability discovery | Only with `eddi.a2a.capabilities.public=true` |
| `GET` | `/.well-known/capabilities/skills` | Registered skill names | Only with `eddi.a2a.capabilities.public=true` |

### Who can call them

On a deployment with `quarkus.oidc.tenant-enabled=false` — the shipped default —
no endpoint requires a token, so the column above says nothing there. It is still
not a promise that every row returns data: `eddi.a2a.enabled` and
`eddi.a2a.capabilities.public` are independent switches, and an endpoint whose
switch is off answers 404 whether or not a token was sent. With authentication
on, the column is the contract:

- **Agent Cards are anonymous by design.** A peer is handed a URL and fetches
  `{url}/agent.json` before it holds any credential for your deployment; EDDI's
  own client does exactly that, with `apiKey` optional. Reading a card needs the
  agent id, so it discloses one agent, not the roster. When authentication is on,
  the card carries an `authentication` block for the JSON-RPC call that follows.

  > **Caveat.** `authentication.credentials` is derived from
  > `quarkus.oidc.auth-server-url`, which is the URL **EDDI** uses to reach the
  > IdP. Point that at a publicly resolvable issuer and the value is usable as
  > published. The shapes that bundle Keycloak do not: the Helm chart sets it to
  > the in-cluster service and `docker-compose.integration-keycloak.yml` to the
  > compose hostname, so the advertised token endpoint does not resolve for an
  > outside peer, which must be told its token endpoint out of band. Nothing
  > leaks — an internal hostname is not a credential — but do not rely on the
  > card alone for token discovery there.
- **`GET /a2a/agents` requires authentication.** It enumerates every A2A-enabled
  agent — name, description, skills, URL — which no part of the protocol needs,
  and which is strictly more than the skill-name list gated behind
  `eddi.a2a.capabilities.public`.
- **The JSON-RPC endpoint requires authentication.** `tasks/send` runs a
  conversation on your LLM budget.
- **Capability discovery follows its flag.** `eddi.a2a.capabilities.public` is the
  only *authentication* gate — no token is ever required or checked. It is not the
  only gate: `eddi.a2a.enabled` still has to be on, and with either off the two
  endpoints answer 404 to everyone. With both on they are anonymous, which is what
  "public" means there.

> **Implementation note.** `@PermitAll` on the JAX-RS method is only half of
> this. Quarkus evaluates the `quarkus.http.auth.permission.*` path policies
> *before* declarative RBAC, so an endpoint that is not also named in a `permit`
> entry in `application.properties` is claimed by the `/*` catch-all and answers
> 401 regardless of its annotation. The two halves are kept in step by
> `A2aEndpointPermissionsTest`; the status codes above are asserted against a
> real Keycloak in `ui/manager/e2e/auth/a2a-discovery.spec.ts`.

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
| `eddi.a2a.capabilities.public` | `false` | Whether `/.well-known/capabilities` and `/.well-known/capabilities/skills` are served at all — see [Who can call them](#who-can-call-them) |

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
