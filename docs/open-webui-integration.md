# Open WebUI Integration (OpenAI-Compatible API)

EDDI can present its deployed agents as **OpenAI models**. Any client that speaks the OpenAI Chat Completions protocol — [Open WebUI](https://github.com/open-webui/open-webui), the Python `openai` SDK, LangChain, LiteLLM, Continue, Cursor — can drive an EDDI conversation without knowing anything about EDDI.

The adapter lives at `/v1` and is **disabled by default**.

---

## Contents

1. [Quick start](#1-quick-start)
2. [How it works](#2-how-it-works)
3. [Configuration](#3-configuration)
4. [Security](#4-security)
5. [Open WebUI settings that matter](#5-open-webui-settings-that-matter)
6. [What is supported](#6-what-is-supported)
7. [Behaviour to expect](#7-behaviour-to-expect)
8. [Errors](#8-errors)
9. [Troubleshooting](#9-troubleshooting)
10. [Known gaps](#10-known-gaps)

---

## 1. Quick start

```yaml
services:
  eddi:
    image: labsai/eddi:latest
    ports: ["7070:7070"]
    environment:
      EDDI_OPENAI_COMPAT_ENABLED: "true"
      EDDI_OPENAI_COMPAT_API_KEY: "sk-eddi-change-me"
    depends_on: [mongodb]

  mongodb:
    image: mongo:7

  open-webui:
    image: ghcr.io/open-webui/open-webui:main
    ports: ["3000:8080"]
    environment:
      OPENAI_API_BASE_URL: http://eddi:7070/v1
      OPENAI_API_KEY: "sk-eddi-change-me"
      ENABLE_OLLAMA_API: "false"
      # REQUIRED — see §5. Without it, every chat window a user opens against
      # one agent shares a single EDDI conversation, and its memory.
      ENABLE_FORWARD_USER_INFO_HEADERS: "true"
```

Deploy an agent in EDDI, open <http://localhost:3000>, and it appears in the model dropdown.

Verify from the shell:

```bash
curl -H "Authorization: Bearer sk-eddi-change-me" http://localhost:7070/v1/models
```

```bash
curl -X POST http://localhost:7070/v1/chat/completions -H "Authorization: Bearer sk-eddi-change-me" -H "Content-Type: application/json" -H "X-OpenWebUI-Chat-Id: chat-1" -H "X-OpenWebUI-User-Id: alice" -d '{"model":"my-agent-a3f9c1","messages":[{"role":"user","content":"Hello"}]}'
```

And from Python:

```python
from openai import OpenAI

client = OpenAI(base_url="http://localhost:7070/v1", api_key="sk-eddi-change-me")
print([m.id for m in client.models.list().data])

for chunk in client.chat.completions.create(
        model="my-agent-a3f9c1",
        messages=[{"role": "user", "content": "Hi"}],
        stream=True):
    print(chunk.choices[0].delta.content or "", end="", flush=True)
```

---

## 2. How it works

### The problem

The OpenAI API is **stateless**: the client resends the full `messages[]` array every turn and the server keeps nothing. EDDI is **stateful**: a conversation accumulates memory, properties, behavior-rule position and HITL bookmarks. The adapter bridges the two.

### Model names

Each deployed agent is exposed under two ids:

| Model id | Behaviour |
|---|---|
| `customer-support-a3f9c1` | **Stateful (default).** Maps to a persistent EDDI conversation. Memory, properties, rule position and HITL survive across turns. |
| `customer-support-a3f9c1:stateless` | **Stateless.** Starts a conversation, sends one message, ends it. Classic one-shot OpenAI semantics. |

The format is `<slugified agent name>-<last 6 characters of the agent id>`. The suffix is there because agent names are not unique — two agents both called "Support" would otherwise be indistinguishable. Accented characters are folded (`Übersicht` → `ubersicht`) rather than dropped.

The adapter also accepts the bare agent id, the exact agent name, or the bare slug — the last two only when they match exactly one agent. An ambiguous name returns `400` listing the candidates rather than picking one.

### Session mapping

```
intent = channel:openai:<agentId>:<chatKey>
userId = the authenticated caller
```

`chatKey` comes from the `X-OpenWebUI-Chat-Id` header, falling back to the OpenAI `user` field. `(intent, userId)` is the primary key in EDDI's managed-conversation store, so:

- **the same chat** → the same EDDI conversation, turn after turn;
- **a different chat window** → a different conversation, with no shared memory;
- **a different user** → a different conversation.

Only the **last user message** is sent to the agent. The rest of `messages[]` is discarded: EDDI has its own memory, so replaying the client's history would double every turn.

### A turn, end to end

```
Open WebUI                       EDDI adapter                        EDDI core
    │ POST /v1/chat/completions
    │   Authorization: Bearer …
    │   X-OpenWebUI-Chat-Id: 4f2b…
    │   X-OpenWebUI-User-Id: u_812
    ├───────────────────────────────►│
    │                                │ authenticate, resolve model → agentId
    │                                │ intent = channel:openai:<agentId>:4f2b…
    │                                │ look up (intent, userId)
    │                                ├─ start or reuse conversation ─────────►│
    │                                ├─ say / sayStreaming ──────────────────►│
    │◄─ data: {"delta":{"role":"assistant"}}                                  │
    │◄─ data: {"delta":{"content":"Hel"}}     ◄── onToken                     │
    │◄─ data: {"delta":{},"finish_reason":"stop"}                             │
    │◄─ data: [DONE]                                                          │
```

Because every message goes through `IConversationService`, the whole pipeline applies unchanged: behavior rules, property setters, HTTP calls, MCP/A2A tools, RAG, multi-model cascading, conversation windowing, persistent user memory, tool cost tracking, rate limiting, the audit ledger and GDPR Art. 18 restriction.

---

## 3. Configuration

| Property | Default | Purpose |
|---|---|---|
| `eddi.openai-compat.enabled` | `false` | Master switch. |
| `eddi.openai-compat.api-key` | *(empty)* | Shared bearer secret. Required when `http-policy=permit` and authorization is on. |
| `eddi.openai-compat.http-policy` | `permit` | `permit` → the adapter authenticates; `authenticated` → Quarkus OIDC does. |
| `eddi.openai-compat.trust-user-headers` | `true` | Believe `X-OpenWebUI-User-Id` as the EDDI userId. |
| `eddi.openai-compat.allow-anonymous` | `false` | Serve callers with no resolvable identity. See the warning in §4. |
| `eddi.openai-compat.default-user` | `openai-anonymous` | The identity used when anonymity is allowed. |
| `eddi.openai-compat.environment` | `production` | Which deployment environment's agents are exposed. |
| `eddi.openai-compat.request-timeout-seconds` | `120` | Per-turn wait before returning `504`. |
| `eddi.openai-compat.max-concurrent-requests` | `64` | In-flight completions; excess gets `429`. |
| `eddi.openai-compat.model-cache-seconds` | `30` | Model catalogue TTL. |
| `eddi.openai-compat.expose-stateless-variants` | `true` | Also list the `:stateless` ids. |

Every property has an environment-variable form: `eddi.openai-compat.api-key` → `EDDI_OPENAI_COMPAT_API_KEY`.

> **Note on CORS.** Open WebUI calls EDDI from its *backend*, so CORS does not apply to it. If you build a browser client that calls `/v1` directly, add its origin to `quarkus.http.cors.origins` and add `x-openwebui-chat-id`/`x-openwebui-user-id` to `quarkus.http.cors.headers`.

---

## 4. Security

### Two modes

**`http-policy=permit` (default).** Quarkus lets `/v1/*` through and the adapter enforces the shared API key itself, with a constant-time comparison. This mode exists because Open WebUI sends an opaque `sk-…` secret, which Quarkus OIDC would reject as a malformed JWT before any application code ran.

**`http-policy=authenticated`.** Quarkus OIDC validates a real bearer token first; the adapter reads the resulting principal as the EDDI userId and ignores both the API key and the user headers. Suited to SDK/LangChain/LiteLLM clients — Open WebUI cannot currently mint per-user OIDC tokens for upstream connections.

### Identity resolution

```
1. OIDC principal                       (http-policy=authenticated)
2. X-OpenWebUI-User-Id                  (when trust-user-headers=true)
3. default-user                         (only when allow-anonymous=true)
4. otherwise → 401
```

> [!IMPORTANT]
> **`trust-user-headers` is a deliberate delegation.** The header is believed only because the caller already proved possession of the API key — i.e. Open WebUI is a trusted proxy that authenticated its own users. **A leaked API key therefore allows impersonating any user.** Rotate it as you would any shared secret, and prefer `http-policy=authenticated` where per-user tokens are available.

> [!WARNING]
> **`allow-anonymous=true` merges users.** Every caller without a resolvable identity collapses onto `default-user`, which means they share one conversation — and one memory — per agent and chat. Intended for single-user deployments only. EDDI logs a warning at startup when it is on.

### Startup guard

EDDI **refuses to start** when the adapter is enabled, `http-policy=permit`, no API key is set, and `authorization.enabled=true`. That combination would expose conversation creation to anyone who can reach the port, and nothing about the running system would look wrong afterwards. Fix it by setting an API key, switching to `authenticated`, or disabling the adapter.

### What the adapter does not do

- It never injects EDDI's role-gated REST facades (`IRestAgentAdministration`, `IRestAgentStore`, …), so it cannot be used to reach admin operations.
- It performs **no configuration writes**. It creates conversations and conversation mappings, nothing else.

---

## 5. Open WebUI settings that matter

| Setting | Value | Why |
|---|---|---|
| `ENABLE_FORWARD_USER_INFO_HEADERS` | `true` | **Load-bearing.** Supplies `X-OpenWebUI-Chat-Id` (per-chat isolation) and `X-OpenWebUI-User-Id` (per-user memory). Without it, all of a user's chats against one agent collapse into one conversation. |
| Title / Tag generation model | a `…:stateless` model, or a separate connection | Otherwise Open WebUI's "write a title for this chat" prompt is injected into the user's real conversation. |
| System Prompt (per model) | *leave empty* | The agent owns its prompt. A value here arrives as `openai_system_message` context and is ignored unless the agent references it. |
| Tools / Functions (per model) | *assign none* | EDDI executes tools inside its own pipeline; the adapter never returns `tool_calls`. |

### Title generation

Open WebUI generates chat titles and tags by sending an extra completion request to a configured model. Pointed at a normal EDDI model, that prompt becomes a real turn in the user's conversation.

Two ways to avoid it, in order of preference:

1. **Point the task model at a separate connection** (Ollama, a small hosted model). Best titles, no EDDI involvement.
2. **Point it at `<agent>:stateless`.** The adapter starts a throwaway conversation, answers, and ends it. Nothing touches the user's real conversation. Title quality depends on the agent's own system prompt.

### Optional: an Inlet Filter to save bandwidth

Purely an optimisation — everything works without it. Open WebUI resends the whole history each turn and EDDI discards all but the last user message:

```python
class Filter:
    def inlet(self, body: dict, __metadata__: dict = {}) -> dict:
        messages = body.get("messages", [])
        system = [m for m in messages if m["role"] == "system"]
        user = [m for m in messages if m["role"] == "user"]
        body["messages"] = system + ([user[-1]] if user else [])
        return body
```

This is safe because the adapter has no message-count heuristic — truncating history cannot make it think a new chat started.

---

## 6. What is supported

### Endpoints

| Endpoint | Status |
|---|---|
| `GET /v1/models` | ✅ |
| `GET /v1/models/{id}` | ✅ |
| `POST /v1/chat/completions` (`stream:false`) | ✅ JSON |
| `POST /v1/chat/completions` (`stream:true`) | ✅ SSE |
| `/v1/embeddings`, `/v1/completions`, `/v1/audio/*`, `/v1/images/*` | ❌ `404` with an OpenAI error body |

### Request fields

| Field | Handling |
|---|---|
| `model` | Resolved to an agent. Required. |
| `messages[]` | Only the last `role:"user"` entry is sent. |
| `role:"system"` | Last one becomes `openai_system_message` context — never overrides the agent's prompt. |
| `content` as string | Plain text. |
| `content` as array | Text extracted; `image_url` parts mapped to attachments. |
| `stream` | Selects JSON vs SSE. The only dispatch signal. |
| `user` | Fallback chat key when `X-OpenWebUI-Chat-Id` is absent. Accepts both the string form and Open WebUI's object form. |
| `temperature`, `max_tokens`, `top_p`, `stream_options`, `tools`, `tool_choice`, `metadata`, `files`, … | **Accepted and ignored.** Model parameters belong to the agent's `langchain.json`. |

### Images

Images pasted or dropped into Open WebUI arrive as `image_url` content parts and are mapped to EDDI's `attachment_N` context entries. From there they flow through the normal attachment pipeline: vision-capability gating per provider and model, byte caps, and SSRF-guarded fetching for remote URLs. Both `data:` URIs and remote URLs work. No configuration needed.

### HITL

Fully supported, surfaced as chat text rather than as errors:

- A turn that pauses (`PAUSE_CONVERSATION`, or a `hitlConfig.toolApprovals` tool gate) returns `200` with any output so far, a pause notice, and the conversation id.
- Further messages while paused return "still awaiting approval".
- Approve or reject out of band — `POST /agents/{conversationId}/resume`, the Slack approval card, or the Manager UI. The next message continues normally.

The conversation id is also on every response as `X-EDDI-Conversation-Id`.

---

## 7. Behaviour to expect

| Behaviour | Effect |
|---|---|
| **Regenerate** | Sends the message to EDDI **again** — a genuine new turn. Behavior rules advance and tools re-run. Inherent to a stateful backend. |
| **Edit and resend** | Same: a new turn, not a rewrite of history. |
| **Deleting a chat in Open WebUI** | Does not end the EDDI conversation. It is abandoned and reaped by normal conversation lifecycle policy. |
| **Documents dropped into chat** | Handled entirely by Open WebUI's own RAG (upload → chunk → embed → inject into the system message). Arrives as `openai_system_message`. For production document RAG, use EDDI's own pipeline. |
| **Token counts** | Not shown — `usage` is omitted (see §10). |
| **Two requests in one chat at once** | The second is dropped by `ConversationCoordinator` and returns `429`; clients retry. |

---

## 8. Errors

Every failure uses the OpenAI envelope:

```json
{"error": {"message": "…", "type": "invalid_request_error", "param": null, "code": "model_not_found"}}
```

| Condition | Status | `code` |
|---|---|---|
| Missing or wrong API key | 401 | `invalid_api_key` |
| Unresolvable caller identity | 401 | `invalid_api_key` |
| Unknown model | 404 | `model_not_found` |
| Ambiguous model name | 400 | `ambiguous_model` |
| No `user` message | 400 | `no_user_message` |
| Agent not deployed | 503 | `agent_not_ready` |
| Conversation busy / concurrency cap | 429 | — |
| Turn timed out | 504 | `timeout` |
| Adapter disabled, or unknown `/v1/*` path | 404 | `unknown_endpoint` |

> **On streams:** once SSE headers are flushed the status is fixed at `200`. Failures during a stream therefore arrive as a content delta prefixed with ⚠️, followed by `finish_reason: "stop"` and `[DONE]`. This is a protocol constraint, not a shortcut — a stream that simply stopped would look like a hang.

---

## 9. Troubleshooting

**No models in the dropdown.** Check `eddi.openai-compat.enabled=true`, that at least one agent is deployed to the configured environment with status `READY`, and that the API key matches. `curl -H "Authorization: Bearer <key>" http://eddi:7070/v1/models` shows the raw answer.

**All my chats share one conversation.** `ENABLE_FORWARD_USER_INFO_HEADERS` is not set on Open WebUI, so no `X-OpenWebUI-Chat-Id` reaches EDDI and every chat falls into the `:default` slot.

**Every user sees everyone else's memory.** `allow-anonymous=true` with `trust-user-headers=false`, or with the header absent. Set `trust-user-headers=true` and enable header forwarding.

**EDDI won't start.** Read the startup message: the guard fires when the adapter is enabled, unauthenticated, and authorization is on. It names the three ways out.

**Chat titles appear in the conversation.** Point Open WebUI's title/tag model at a `:stateless` variant or a separate connection (§5).

**Streaming looks like a hang behind a proxy.** The adapter sets `X-Accel-Buffering: no`, but some proxies need explicit configuration to stop buffering `text/event-stream`.

**401 with a valid-looking token under OIDC.** In `http-policy=authenticated` mode the token must be a real OIDC token for your realm; an `sk-…` string is not.

---

## 10. Known gaps

1. **No `usage` / token counts.** EDDI does not surface per-request token counts to this layer. Emitting zeros would render as a factual "0 tokens", so the field is omitted instead.
2. **Non-text outputs are dropped.** Quick replies and `inputField` items do not reach OpenAI clients — only text. Rule-based agents that lean on quick replies degrade to plain prose.
3. **Regenerate is a real new turn**, not an idempotent replay.
4. **Stream-path errors must return 200** (see §8).
5. **One worker thread per in-flight completion**, bounded by `max-concurrent-requests`.
6. **`http-policy=authenticated` is impractical with Open WebUI**, which cannot mint per-user upstream OIDC tokens.
7. **`tool_calls` are never returned.** EDDI runs tools inside its pipeline; echoing them would make Open WebUI try to execute EDDI's HTTP/MCP/memory tools locally, where they do not exist. Tool activity remains visible in the audit ledger and `toolTrace`.

---

## See also

- [`architecture.md`](architecture.md) — the conversation pipeline
- [`hitl.md`](hitl.md) — human-in-the-loop approval gates
- [`../planning/openai-api-adapter-plan.md`](../planning/openai-api-adapter-plan.md) — design rationale and the research behind it
