# Agent-to-Agent (A2A) Protocol

> **Status:** Available since EDDI v6.0.0; conformant to A2A 1.0 in the release after 6.5  
> **Spec:** [A2A Protocol](https://a2a-protocol.org/latest/specification/) — EDDI is pinned to **A2A 1.0** (specification release [v1.0.1](https://github.com/a2aproject/A2A/releases)) and still serves **0.3** peers and the **pre-0.2** `tasks/send` call EDDI spoke until 6.5

EDDI implements the Agent-to-Agent (A2A) protocol for distributed peer-to-peer agent communication. Agents can **expose** their capabilities via Agent Cards, and **consume** remote A2A agents as tools.

Verified against the official [`a2a-sdk`](https://pypi.org/project/a2a-sdk/) Python client (1.2.1): its 1.0 JSON-RPC transport and its 0.3 compatibility transport both resolve an EDDI Agent Card and send, stream, get and cancel tasks.

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
| `GET` | `/.well-known/agent-card.json` | Default Agent Card (first A2A-enabled agent) — the A2A 0.3+/1.0 well-known path | Yes |
| `GET` | `/.well-known/agent.json` | The same card at the pre-0.3 path, kept for older peers | Yes |
| `GET` | `/a2a/agents/{agentId}/.well-known/agent-card.json` | Per-agent Agent Card where an SDK client given `/a2a/agents/{agentId}` as its base URL looks for it | Yes |
| `GET` | `/a2a/agents/{agentId}/agent.json` | Per-agent Agent Card (EDDI's original path) | Yes |
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
  the card carries an `authentication` block naming the token endpoint for the
  JSON-RPC call that follows — see [The advertised token
  endpoint](#the-advertised-token-endpoint).
- **`GET /a2a/agents` requires authentication.** It enumerates every A2A-enabled
  agent — name, description, skills, URL — which no part of the protocol needs,
  and which is strictly more than the skill-name list gated behind
  `eddi.a2a.capabilities.public`.
- **The JSON-RPC endpoint requires authentication** and an EDDI role
  (`eddi-user` or above). Sending a message runs a conversation on your LLM
  budget.
- **Capability discovery follows its flag.** `eddi.a2a.capabilities.public` is the
  only *authentication* gate — no token is ever required or checked. It is not the
  only gate: `eddi.a2a.enabled` still has to be on, and with either off the two
  endpoints answer 404 to everyone. With both on they are anonymous, which is what
  "public" means there.

### The advertised token endpoint

`authentication.credentials` has to name a token endpoint the **peer** can
reach, which is not always the one EDDI uses: the Helm chart points
`quarkus.oidc.auth-server-url` at the in-cluster Keycloak Service and the auth
compose profile at `http://keycloak:8080`. Publishing either to an outside peer
dead-ends its discovery.

**Set `eddi.a2a.public-token-endpoint`** and that value is advertised verbatim.
It is the *endpoint*, not the issuer, because the path is the provider-specific
part — an issuer-shaped setting cannot express Okta's
`https://example.okta.com/oauth2/default/v1/token`.

Left empty, EDDI derives `<issuer>/protocol/openid-connect/token`, taking
`<issuer>` from:

1. **`eddi.keycloak.public.url`**, grafted onto the realm path from
   `quarkus.oidc.auth-server-url`. Both shipped authenticated deployments already
   set it — the Helm chart *requires* it, since the Manager SPA cannot start a
   login without it — so they advertise a reachable endpoint with no new
   configuration. Only the origin is taken from it; the realm path stays whatever
   EDDI is configured against, so the two cannot drift apart.
2. **`quarkus.oidc.auth-server-url`** unchanged — correct whenever EDDI and its
   peers reach the IdP by the same name, which is the externally hosted IdP case.

> **That derivation assumes Keycloak.** `/protocol/openid-connect/token` is
> Keycloak's path, and it is what every shipped authenticated deployment runs. On
> any other identity provider the derived value will be wrong, so set
> `eddi.a2a.public-token-endpoint` there. Resolving the endpoint through OIDC
> discovery (`<issuer>/.well-known/openid-configuration`) would remove the
> assumption rather than document it, and is the right follow-up — it is not done
> here because it turns rendering an anonymous card into an outbound HTTP call,
> which needs `SafeHttpClient`, a cache and a failure policy of its own.

> **Implementation note.** `@PermitAll` on the JAX-RS method is only half of
> this. Quarkus evaluates the `quarkus.http.auth.permission.*` path policies
> *before* declarative RBAC, so an endpoint that is not also named in a `permit`
> entry in `application.properties` is claimed by the `/*` catch-all and answers
> 401 regardless of its annotation. The two halves are kept in step by
> `A2aEndpointPermissionsTest`; the status codes above are asserted against a
> real Keycloak in `ui/manager/e2e/auth/a2a-discovery.spec.ts`.

### JSON-RPC Methods

One endpoint, `POST /a2a/agents/{agentId}`, serves three dialects. The method
name decides the dialect, and the answer is written in the dialect it was asked
in — so an A2A 1.0 client, which parses results strictly, never sees a 0.3
field, and the reverse.

| Operation | A2A 1.0 (pinned) | A2A 0.3 | Pre-0.2 (deprecated) |
|---|---|---|---|
| Send a message, wait for the turn | `SendMessage` | `message/send` | `tasks/send` |
| Send and stream the turn (SSE) | `SendStreamingMessage` | `message/stream` | `tasks/sendSubscribe` |
| Read a task | `GetTask` | `tasks/get` | `tasks/get` |
| Cancel a task | `CancelTask` | `tasks/cancel` | `tasks/cancel` |
| List / resubscribe | `ListTasks`, `SubscribeToTask` → `-32004` UnsupportedOperation | `tasks/list`, `tasks/resubscribe` → `-32004` | — |
| Push notifications | → `-32003` PushNotificationNotSupported | → `-32003` | — |
| Extended Agent Card | `GetExtendedAgentCard` → `-32007` | `agent/getAuthenticatedExtendedCard` → `-32007` | — |

A 1.0 client sends `A2A-Version: 1.0`; an absent header is read as 0.3, as the
specification says, and a version that is neither 0.x nor 1.x is refused with
`-32009` VersionNotSupported.

**What differs between the dialects**

| | 1.0 | 0.3 | Pre-0.2 |
|---|---|---|---|
| Task state | `TASK_STATE_COMPLETED` | `completed` | `completed` |
| Status | object: `state`, `message`, `timestamp` | same | same (a bare enum up to 6.5) |
| Role | `ROLE_USER` / `ROLE_AGENT` | `user` / `agent` | `user` / `agent` |
| Text part | `{"text": "…"}` | `{"kind": "text", "text": "…"}` | `{"kind": "text", "type": "text", "text": "…"}` |
| Send result | `{"task": {…}}` | the task, `"kind": "task"` | the task |
| Return without waiting | `configuration.returnImmediately: true` | `configuration.blocking: false` | — |

A message may carry several text parts (joined with newlines) and data parts
(read as JSON). A message made only of file parts is refused with `-32005`
ContentTypeNotSupported.

### Tasks, contexts and conversations

A **context is a conversation**, and a **task is one turn** in it. A message
that names no `contextId` opens a new conversation, and its id is the
`contextId` the peer gets back; a later message with that `contextId` continues
the same conversation. Task ids the server issues are
`<conversationId>_<random>`, and every turn carries its task id in the input
context (`{context.a2aTaskId}`, with `{context.a2aContextId}` next to it).

A message that names a `taskId` continues that task — in its conversation,
under its id. A task in a terminal state takes no further messages (`-32004`);
send a new message in the same context instead.

A pre-0.2 `tasks/send` keeps its old contract: the task id is the one the peer
chose (`params.id`), and no context is invented when the peer named none. If a
peer sends again under the same id while an earlier turn is still running, the
id belongs to the newer send: the earlier turn's outcome answers its own caller
but is not recorded over the newer task.

### Task states

A task's state is how **its own turn** ended — never what the conversation
last said.

| The turn… | Task state |
|---|---|
| finished normally (conversation `READY` or `ENDED`) | `completed`, the turn's output as the `response` artifact |
| failed (conversation `ERROR`) | `failed`, status message "The agent failed while processing the message." — no artifact, no internal detail |
| paused for a human approval (HITL, `AWAITING_HUMAN`) | `input-required`, the agent's pause announcement as the status message |
| was dropped because the conversation was paused (or became so while the message was queued) | `input-required` — the message was **not** processed; send it again once the approval is resolved |
| was dropped because the conversation was busy with another turn | `rejected` — the message was not processed |
| was cancelled (`CancelTask`, or a HITL approval cancelled) | `canceled` |
| is still running when a blocking send stops waiting (`eddi.a2a.task-timeout-seconds`) | `working` — poll `GetTask` |

A turn's output is returned only when it carries the task's own id, so a turn
that was dropped can no longer hand back the previous turn's answer (6.5 did).
`GetTask` on an `input-required` task reads the conversation again: once a
reviewer has approved and the resumed turn finished, the task reads
`completed` with the answer.

`CancelTask` stops the task's turn — a running turn at its next task boundary,
a HITL pause by cancelling the pending approval — **without ending the
conversation**, so the context stays usable. Cancelling a task that already
reached a terminal state answers `-32002` TaskNotCancelable. (Up to 6.5, a
cancel ended the whole conversation.)

A conversation runs one turn at a time, and cancelling stops the turn it is
running. So a task **queued behind another task's turn** in the same context is
answered `-32002` too — cancelling it then would stop its sibling instead. Once
the turn ahead has settled, the task can be cancelled. This queue is known per
node; a cancel that reaches a node which did not submit the turn cannot see it.

### Where tasks are remembered

Tasks and context bindings are kept behind `IA2ATaskStore`. The default
implementation keeps them in two bounded, node-local caches
(`a2aTaskMapping`, `a2aTaskMapping:context`). A task EDDI issued is also found
**without** that store — after a restart, or on another node — because its id
names its conversation and its turn is tagged with it: `GetTask` then re-derives
the task from the conversation store, and a `contextId` EDDI issued resolves the
same way. Both are scoped to the calling peer: a conversation is only used when
the peer owns it. What does not survive a restart on a single node is a
peer-chosen id: a legacy `tasks/send` id, or a `contextId` the peer made up. A
clustered deployment supplies a shared `IA2ATaskStore`.

### Streaming

`SendStreamingMessage` / `message/stream` answer with `text/event-stream`. Each
event is a JSON-RPC response carrying the request's `id`:

1. the task, `working`;
2. one `artifactUpdate` (0.3: `artifact-update`) per streamed token, appended to
   the `response` artifact;
3. the settled answer as a final `artifactUpdate` with `append: false`,
   `lastChunk: true` — it replaces the streamed preview, because the turn's
   output (after templating and any non-LLM output) is the result;
4. a `statusUpdate` (0.3: `status-update` with `final: true`) with the task's
   final state.

A turn that outlives the stream ends it on its last known state; poll `GetTask`
for the rest. While no event is due, the stream writes an SSE comment
(`: keepalive`) every 15 seconds so a proxy does not drop an idle connection;
SSE clients ignore comment lines.

### The in-flight bound

At most `eddi.a2a.max-concurrent-requests` (default `64`) A2A turns run at once,
across all peers and agents. A send or stream beyond that is refused **before
any conversation is started** with HTTP `503`, `Retry-After: 1` and a JSON-RPC
error body (`-32603`, "Server busy: too many concurrent A2A requests. Retry
shortly."). `GetTask` and `CancelTask` are never refused.

A slot is held for the turn, not the HTTP request: a `returnImmediately` send, a
stream and a blocking send that stopped waiting keep it until the turn settles.
Every slot is released at the latest `task-timeout + 30 s` after it was taken,
so a turn that never reports back cannot leak one; a normal release cancels that
timer. A cancelled turn keeps its
slot until it actually stops (an in-flight LLM call is not interrupted).

Metrics: `eddi_a2a_requests_total{method,dialect,outcome}` (`outcome=busy` counts
refusals), `eddi_a2a_tasks_total{state}` and the gauge `eddi_a2a_in_flight` — see
[Metrics](metrics.md).

### Compatibility and deprecations

- **`tasks/send` is deprecated** and still served. Its result is now the 0.3
  task shape — `status` is an object, every part carries `kind` as well as the
  old `type` — so a client that read `status` as a string must read
  `status.state`. EDDI's own client up to 6.5 only reads the artifacts and is
  unaffected.
- **`/.well-known/agent.json` and `{agent}/agent.json` are kept** next to the
  current `agent-card.json` paths.
- **The Agent Card** carries both the 1.0 fields (`supportedInterfaces`,
  `securitySchemes`/`securityRequirements` in the 1.0 shape) and the 0.3
  connection fields (`url`, `protocolVersion: "0.3.0"`, `preferredTransport`),
  plus EDDI's old `authentication` block. `version` is the EDDI version;
  `capabilities.streaming` is now `true`.
- **Removed:** the card's `stateTransitionHistory` capability (EDDI never
  provided a transition history).

### Example: Send a Message (A2A 1.0)

```json
{
  "jsonrpc": "2.0",
  "method": "SendMessage",
  "id": "req-1",
  "params": {
    "message": {
      "messageId": "msg-1",
      "role": "ROLE_USER",
      "parts": [{ "text": "Track order #12345" }]
    }
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "result": {
    "task": {
      "id": "6ac0e0cf04e6a965aec9c8ed_3b94b74432f042cf",
      "contextId": "6ac0e0cf04e6a965aec9c8ed",
      "status": { "state": "TASK_STATE_COMPLETED", "timestamp": "2026-10-03T11:02:39.519Z" },
      "artifacts": [{ "artifactId": "response", "name": "response", "parts": [{ "text": "Order #12345 shipped yesterday." }] }]
    }
  }
}
```

The same call in the 0.3 dialect is `"method": "message/send"` with
`"role": "user"` and `"parts": [{"kind": "text", "text": "…"}]`; the deprecated
form is `"method": "tasks/send"` with `"params": {"id": "task-1", "message": {…}}`.

### Configuration Properties

| Property | Default | Description |
|---|---|---|
| `eddi.a2a.enabled` | `true` | Master toggle for all A2A endpoints |
| `eddi.a2a.base-url` | `http://localhost:7070` | Base URL used in Agent Card URLs |
| `eddi.a2a.public-token-endpoint` | *(derived)* | The token endpoint advertised in Agent Cards — see [The advertised token endpoint](#the-advertised-token-endpoint) |
| `eddi.a2a.capabilities.public` | `false` | Whether `/.well-known/capabilities` and `/.well-known/capabilities/skills` are served at all — see [Who can call them](#who-can-call-them) |
| `eddi.a2a.max-concurrent-requests` | `64` | How many A2A turns may run at once — see [The in-flight bound](#the-in-flight-bound) |
| `eddi.a2a.task-timeout-seconds` | `systemRuntime.agentTimeoutInSeconds` | How long a blocking send waits for its turn before answering `working` |

While `eddi.a2a.enabled=false`, the card and capability endpoints answer `404`,
`GET /a2a/agents` answers `200` with an empty list, and the JSON-RPC endpoint
answers HTTP `200` with a JSON-RPC "method not found" error (`-32601`, "A2A is
disabled") — not a `404`, because a JSON-RPC client reads errors from the body.

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

1. **Discovery:** `A2AToolProviderManager` fetches the Agent Card from `{url}/agent.json`, then `{url}/.well-known/agent-card.json`, then `{url}/.well-known/agent.json` — the first that answers wins. A `url` ending in `.json` is fetched as the card itself.
2. **Dialect:** read off the card. A JSON-RPC interface at protocol version `1.x` in `supportedInterfaces` → A2A 1.0 (`SendMessage`, header `A2A-Version: 1.0`); a `0.x` `protocolVersion` → 0.3 (`message/send`); neither — a stock EDDI up to 6.5, or any pre-0.2 peer — → the legacy `tasks/send`.
3. **Endpoint:** the configured `url`. A card is authored by the peer and is not allowed to redirect the call (or change what a HITL approval fingerprint pins); only when `url` names the card document itself is the card's interface URL used — and then only if it is on the same origin (scheme, host, port) as the card, because the configured credential is sent to that endpoint. A card naming another origin is refused. The URL is validated like any other target.
4. **Mapping:** Each skill becomes a `ToolSpecification` with a `message` parameter
5. **Execution:** When the LLM calls the tool, one send is made in the peer's dialect. The result is read in any of the three shapes. A task that did not complete reaches the model as such — `A2A agent task failed: …`, `… needs more input: …`, `… is still working …` — never as an answer.
6. **Caching:** Agent Cards are cached for 5 minutes to avoid redundant fetches

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
          │  GET agent card           │  POST SendMessage
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
| `engine/a2a/A2AModels.java` | Protocol records (Agent Card, JSON-RPC, Task), dialect-neutral |
| `engine/a2a/A2AWireFormat.java` | Reads requests and writes results in each dialect (1.0, 0.3, legacy) |
| `engine/a2a/AgentCardService.java` | Generates Agent Cards from agent configs |
| `engine/a2a/A2ATaskHandler.java` | Runs tasks as conversation turns; maps conversation state to task state |
| `engine/a2a/IA2ATaskStore.java`, `CachedA2ATaskStore.java` | Where tasks and contexts are remembered (default: node-local cache) |
| `engine/a2a/A2AInFlightLimiter.java` | The in-flight bound |
| `engine/a2a/RestA2AEndpoint.java` | JAX-RS endpoints |
| `modules/llm/impl/A2AToolProviderManager.java` | Client-side discovery and tool execution |
| `modules/llm/model/LlmConfiguration.java` | `A2AAgentConfig` configuration model |
| `configs/agents/model/AgentConfiguration.java` | `a2aEnabled`, `a2aSkills`, `description` |
