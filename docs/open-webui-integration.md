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
11. [Recipe: EDDI as a thin LLM gateway](#11-recipe-eddi-as-a-thin-llm-gateway)

---

## 1. Quick start

### Runnable demo (one command)

```bash
docker compose -f docker-compose.openwebui.yml up --build
```

Brings up MongoDB, EDDI, Open WebUI on <http://localhost:3000>, and a one-shot seeder that creates and deploys a small rule-based demo agent so the model dropdown is not empty. Pick `eddi-demo-agent-…` and start typing.

Two things worth knowing about it:

- **EDDI is built from the working tree**, not pulled from Docker Hub — the adapter is not in any published image yet, so `labsai/eddi:latest` would start fine and then 404 on `/v1`. The build happens inside the container, so no local JDK or Maven is needed. The first build takes a few minutes; later ones are cached.
- **The demo agent has no LLM.** It needs no provider credentials and its replies are deterministic — but it also *cannot answer questions about anything*, including an uploaded PDF. To get an agent that actually thinks, set `EDDI_DEMO_LLM_API_KEY` and a second LLM-backed agent is deployed alongside it:

  ```bash
  EDDI_DEMO_LLM_API_KEY=sk-... docker compose -f docker-compose.openwebui.yml up --build
  ```

  `EDDI_DEMO_LLM_TYPE` (default `openai`) and `EDDI_DEMO_LLM_MODEL` (default `gpt-4o-mini`) select the provider and model — see [`langchain.md`](langchain.md) for the supported types. Its system prompt references `{context.openai_system_message}`, so with `RAG_SYSTEM_CONTEXT=true` it can answer about files you upload.

  **The key goes into EDDI's Secrets Vault, not into the agent config.** The seeder stores it via `PUT /secretstore/secrets/default/demo-llm-api-key` and the config holds `${vault:demo-llm-api-key}`. That is the point of the vault: an agent config gets exported, diffed, rendered in the Manager UI and logged, and a literal key would travel with all of it. Verified on a clean run — the plaintext key appears in no MongoDB collection, and `GET /secretstore/secrets/default` returns metadata and a checksum, never the value.
- **The rule-based agent** runs a short three-turn flow — it asks your name, remembers it, and refers back to it — because that is the thing this adapter exists to bridge: conversation state surviving a stateless HTTP protocol. Open a second chat and it asks again, which is the per-chat isolation.
- **Open WebUI's auxiliary generation is turned off** in the demo (titles, tags, follow-ups, search queries). Left on, those extra LLM calls go to the selected model, so utility prompts land in your real conversation and advance its behaviour rules — and the unparseable replies surface as bogus follow-up suggestions. In a real deployment point them at a separate connection or an `…:stateless` model instead of disabling them (§5).

It is **not a production configuration** — `WEBUI_AUTH=false` and `allow-anonymous=true` are set so the UI is usable immediately. Both are called out inline in the compose file, and §4 has the real security model.

#### Running it more than once

The seeder is idempotent **per agent**, not "has anything been deployed". Each run creates only what is missing, so the normal second run — you now have a provider key and want the LLM agent too — just works:

```bash
EDDI_DEMO_LLM_API_KEY=sk-... docker compose -f docker-compose.openwebui.yml up -d
```

It reports which agents it skipped, adds the LLM one, and re-stores the key in the vault (so changing the key here rotates it rather than leaving the old one). `--build` is only needed the first time or after changing EDDI's source.

#### If a port is already taken

`EDDI_PORT` and `OPEN_WEBUI_PORT` remap the **host** side; the containers keep talking to each other on `7070`/`8080` regardless:

```bash
EDDI_PORT=7071 OPEN_WEBUI_PORT=3001 docker compose -f docker-compose.openwebui.yml up -d
```

Without this, Docker fails with `Bind for 0.0.0.0:7070 failed: port is already allocated` — most often because another EDDI (or the main `docker-compose.yml`) is already running.

#### Shutting it down

```bash
docker compose -f docker-compose.openwebui.yml down
```

That keeps the two named volumes, so the seeded agents, the vault entry and your chat history survive and the next start needs no re-seeding. Add `-v` to discard them — that is the one irreversible step.

### Production-shaped compose

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

Verify from the shell, with `EDDI_API_KEY` set to the same value as in the compose file:

```bash
curl -H "Authorization: Bearer $EDDI_API_KEY" http://localhost:7070/v1/models
```

```bash
curl -X POST http://localhost:7070/v1/chat/completions -H "Authorization: Bearer $EDDI_API_KEY" -H "Content-Type: application/json" -H "X-OpenWebUI-Chat-Id: chat-1" -H "X-OpenWebUI-User-Id: alice" -d '{"model":"my-agent-a3f9c1","messages":[{"role":"user","content":"Hello"}]}'
```

And from Python:

```python
import os
from openai import OpenAI

client = OpenAI(base_url="http://localhost:7070/v1", api_key=os.environ["EDDI_API_KEY"])
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
| `customer-support-a3f9c1:stateless` | **Stateless.** Starts a conversation, sends one message, ends it. No conversation memory (see below). |

The format is `<slugified agent name>-<last 6 characters of the agent id>`. The suffix is there because agent names are not unique — two agents both called "Support" would otherwise be indistinguishable. Accented characters are folded (`Übersicht` → `ubersicht`) rather than dropped.

The adapter also accepts the bare agent id, the exact agent name, or the bare slug — the last two only when they match exactly one agent. An ambiguous name returns `400` listing the candidates rather than picking one.

### Stateless requests

Both routes select the same behaviour — a throwaway conversation, ended as soon as the turn completes, with no entry in the managed-conversation store:

```jsonc
// 1. the model suffix — the only route a UI like Open WebUI can express,
//    since a model name is all it lets you pick per request
{"model": "customer-support-a3f9c1:stateless", "messages": [...]}

// 2. the `stateless` body field — an EDDI extension, for callers that can
//    say what they mean
{"model": "customer-support-a3f9c1", "messages": [...], "stateless": true}
```

```python
client.chat.completions.create(
    model="customer-support-a3f9c1",
    messages=[{"role": "user", "content": "Classify this ticket"}],
    extra_body={"stateless": True},
)
```

The suffix follows the ecosystem convention for behavioural model variants — OpenRouter's `:nitro`/`:floor`/`:free`, Ollama's `llama3:8b` — and has the advantage of appearing in `GET /v1/models`, so the capability is discoverable. The body field is the honest parameter for programmatic callers. **They are OR-ed**: `model: "x:stateless"` together with `stateless: false` is self-contradictory, and of the two readings, running stateless merely loses continuity while running stateful would persist a conversation the caller may not have wanted.

Setting `expose-stateless-variants=false` disables **both** routes, so the switch cannot be circumvented by moving the request from the model id into the body.

Two things stateless does **not** mean:

- **Not "no memory at all".** User-scoped long-term memory is keyed by `userId`, not by conversation, so an agent with user memory enabled still loads that user's entries at init and still persists `longTerm` properties at teardown. A thin LLM-only agent has neither, so nothing happens.
- **Not "client-managed history".** The adapter sends only the last user message in *both* modes. A client that resends its own history each turn gets no context from the earlier entries. Stateless is genuinely single-turn.

It is also only sensible for LLM-only agents: a rule-based agent under `:stateless` restarts at `CONVERSATION_START` on every request, which is correct but useless for a wizard.

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
| `eddi.openai-compat.expose-stateless-variants` | `true` | Enable stateless requests — lists the `:stateless` ids and accepts the `stateless` body field. Disabling blocks both. |

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
| `RAG_SYSTEM_CONTEXT` | `true` | **Set this.** Default `false` puts Open WebUI's retrieved chunks and instruction template into the *user* message, so your agent reads that instead of what the user typed. See below. |
| Built-in tools capability (per model) | *off* | Also set this. It cannot be invoked — EDDI never returns `tool_calls` — but leaving it on prepends an `<attached_files>` block to the user's message. No env var; it is a toggle in the model editor. See below. |

### Title generation

Open WebUI generates chat titles and tags by sending an extra completion request to a configured model. Pointed at a normal EDDI model, that prompt becomes a real turn in the user's conversation.

Two ways to avoid it, in order of preference:

1. **Point the task model at a separate connection** (Ollama, a small hosted model). Best titles, no EDDI involvement.
2. **Point it at `<agent>:stateless`.** The adapter starts a throwaway conversation, answers, and ends it. Nothing touches the user's real conversation. Title quality depends on the agent's own system prompt.

### RAG context: set `RAG_SYSTEM_CONTEXT=true`

**This one silently breaks rule-based agents.** When you drop a file into a chat, Open WebUI retrieves the relevant chunks and wraps them — together with its own multi-paragraph instruction template — around your message. By default it puts all of that in the **user message**, not the system message:

```python
# open_webui/env.py
RAG_SYSTEM_CONTEXT = os.getenv('RAG_SYSTEM_CONTEXT', 'False').lower() == 'true'

# open_webui/utils/middleware.py
if RAG_SYSTEM_CONTEXT:
    return add_or_update_system_message(...)   # system message
else:
    return add_or_update_user_message(...)     # <- the default
```

So instead of `what is this pdf about?`, your agent receives `{memory.current.input}` containing several thousand tokens of `### Task: ...`, `<context><source id="1">...</source></context>` and `<attached_files>` markup, with the real question buried at the end. Input matchers stop matching, property setters capture the whole blob, and quick replies never fire.

```yaml
- "RAG_SYSTEM_CONTEXT=true"
```

With it set, the context lands in the system message, the adapter maps it to the `openai_system_message` context entry, and the agent decides whether to use it — `{context.openai_system_message}` — or ignore it. The user's actual text stays the user's actual text.

If you are using EDDI's own RAG pipeline instead, this still matters: without it, Open WebUI's retrieval output pollutes the input regardless.

### Turn off the `builtin_tools` capability on EDDI models

Open WebUI's built-in tools (the Files capability that lets a model list and search chat attachments) are **dead weight against an EDDI model** — the adapter never returns `tool_calls`, so they can never be invoked. They are not harmless, though: enabling them prepends an `<attached_files>` block to the user's message.

```python
# open_webui/utils/middleware.py
use_builtin_tools = (...) or (
    bool(metadata.get('session_id'))
    and metadata.get('params', {}).get('function_calling') != 'legacy'
    and (model.get('info', {}).get('meta', {}).get('capabilities') or {}).get('builtin_tools', True)
)
...
if use_builtin_tools:
    form_data['messages'] = await add_file_context(...)   # prepends <attached_files>
```

So with a file attached, an agent that expected `what is this pdf about?` receives:

```
<attached_files>
<file type="file" id="e097e541-…" content_type="application/pdf" name="Report.pdf"/>
</attached_files>

what is this pdf about?
```

There is **no environment variable** for this — it is a per-model toggle. Open **Workspace → Models → edit the EDDI model → Capabilities** and turn the built-in tools capability off. (Setting the model's `function_calling` parameter to `legacy` has the same effect, as the condition above shows.)

**What you give up, precisely.** Less than it sounds. Open WebUI's built-in tools let the model *search* chat attachments on demand; turning them off does not stop it reading uploaded files. With `RAG_SYSTEM_CONTEXT=true` the retrieved chunks still arrive in the system message and an agent that references `{context.openai_system_message}` answers from them. You lose the on-demand search variant, not the capability.

**Why they cannot work today.** OpenAI tool calling is a multi-turn round trip — the assistant returns `finish_reason: "tool_calls"`, the client executes and sends back a `role: "tool"` message, the assistant continues from the result. This adapter is single-turn: it sends only the last user message and returns final text, and a `role: "tool"` message is dropped. That rule is what keeps EDDI's own memory from being doubled by the client's replayed history (§2), so client-side tools are the one place it would need a deliberate exception.

Supporting them means accepting `tools[]` from the request, plumbing them into `LlmTask` alongside EDDI's own, returning `tool_calls` **only** for client-side tools — EDDI's own MCP, HTTP-call and built-in tools must keep executing server-side, or you get the double-execution this adapter exists to avoid — and reading `role: "tool"` messages back. A real feature, tracked for v2; see [§10](#10-known-gaps).

This is not specific to EDDI. Open WebUI's built-in tools go dark against any OpenAI-compatible backend that does not implement tool calling.

Note this is separate from `RAG_SYSTEM_CONTEXT`: that one controls where the *retrieved chunks* go, this one controls the *file announcement*. Both must be dealt with for an attachment-carrying turn to reach your agent clean.

### Streaming: watch `AIOHTTP_CLIENT_STREAM_IDLE_TIMEOUT`

Open WebUI can end a streamed reply when the upstream sends nothing for that long. EDDI can legitimately be silent for a while before its first token — conversation start, behaviour rules, HTTP calls, an MCP tool chain — and a **rule-based agent emits nothing at all until the turn completes**, since its text comes from the output task rather than token-by-token.

If you set this, size it against your slowest agent, not against a typical LLM's time-to-first-token. Leaving it unset keeps the previous behaviour, where only the overall `AIOHTTP_CLIENT_TIMEOUT` applies.

### Sending `stateless` from the Open WebUI UI

The `stateless` body field (§2) is not an OpenAI-standard parameter, so Open WebUI would normally drop it. Newer versions can forward it: set **`passthrough_params`** in the connection's Advanced settings to a comma-separated list of parameter names — or `*` for every non-standard parameter — and it reaches EDDI verbatim.

That makes the body field usable from the UI, not just from `extra_body` in code. It is the cleaner alternative to picking a `...:stateless` model where your Open WebUI version supports it.

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
| `/v1/embeddings`, `/v1/completions`, `/v1/audio/*`, `/v1/images/*` | ❌ Plain `404` (see note) |

> **Note:** unimplemented `/v1` paths match no JAX-RS resource, so they return Quarkus' standard `404` rather than an OpenAI error envelope. Every path the adapter *does* serve uses the envelope. Adding a catch-all route would risk shadowing the real endpoints for a purely cosmetic gain, so it is deliberately not done.

### Request fields

| Field | Handling |
|---|---|
| `model` | Resolved to an agent. Required. |
| `messages[]` | Only the last `role:"user"` entry is sent. |
| `role:"system"` | Last one becomes `openai_system_message` context — never overrides the agent's prompt. |
| `content` as string | Plain text. |
| `content` as array | Text extracted; `image_url`, `file` and `input_audio` parts mapped to attachments. |
| `stream` | Selects JSON vs SSE. The only dispatch signal. |
| `stateless` | **EDDI extension.** Run this turn in a throwaway conversation — same as the `:stateless` model suffix. See §2. |
| `user` | Fallback chat key when `X-OpenWebUI-Chat-Id` is absent. Accepts both the string form and Open WebUI's object form. |
| `temperature`, `max_tokens`, `top_p`, `stream_options`, `tools`, `tool_choice`, `metadata`, `files`, … | **Accepted and ignored.** Model parameters belong to the agent's `langchain.json`. |

### Attachments — images, documents, audio

Three OpenAI content-part types are mapped to EDDI `attachment_N` context entries, which then flow through the normal attachment pipeline — capability gating per provider and model, per-turn and byte caps, PDF text extraction, and SSRF-guarded fetching for remote URLs.

| Content part | Wire shape | Downstream |
|---|---|---|
| `image_url` | `{"url":"data:image/png;base64,…"}` or a remote URL | `ImageContent` when the model has vision; otherwise a note explaining why it was dropped |
| `file` | `{"filename":"report.pdf","file_data":"data:application/pdf;base64,…"}` | `PdfFileContent` when the model has native document support; otherwise text-extracted and inlined |
| `input_audio` | `{"data":"<raw base64>","format":"wav"\|"mp3"}` | `AudioContent` when the model supports audio |

Notes worth knowing:

- **`input_audio.data` is raw base64 with no `data:` prefix**, unlike every other binary payload in the protocol. `format` carries the type separately, and `mp3` maps to `audio/mpeg` (not `audio/mp3`, which is not a real media type).
- **`file.file_id` is not supported.** It references a file uploaded through the OpenAI Files API, which EDDI does not implement. Such parts are skipped with a warning — send `file_data` inline instead.
- **The declared `filename` wins over a generic data-URI type.** A `data:application/octet-stream` payload named `contract.pdf` is still treated as a PDF, because clients that base64 a file without sniffing it send the generic type.
- **The per-turn cap (`eddi.attachments.max-per-turn`, default 5) counts all three types together.** Excess attachments are dropped with a warning rather than failing the turn.
- Beyond PDFs, any type EDDI's text extractor handles (`.txt`, `.md`, `.csv`, `.json`, `.xml`, `.html`) is inlined as text.

> **Open WebUI specifically** sends images as `image_url` and does *not* send documents as `file` parts — it runs its own RAG over uploads and injects the retrieved text into the prompt. **Where it injects matters a great deal to an EDDI agent — see [§5, RAG context](#rag-context-set-rag_system_contexttrue).** The `file` path is for the OpenAI SDK and other clients that send PDFs inline.

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
| **Documents dropped into chat** | Handled entirely by Open WebUI's own RAG (upload → chunk → embed → inject into the prompt). **By default it injects into the *user* message, not the system message** — set `RAG_SYSTEM_CONTEXT=true` (§5) or your agent receives the whole `<source>` blob as its input. For production document RAG, use EDDI's own pipeline. |
| **Token counts** | Reported as `usage` for agents that call a model, summed across cascade steps and tool round-trips. Rule-based agents spend no tokens, so the field is omitted rather than zero-filled. On streams it needs `stream_options.include_usage` (§7.1). |
| **Two requests in one chat at once** | The second is dropped by `ConversationCoordinator` and returns `429`; clients retry. |

### 7.1 Token usage

`usage` is built from the turn's `audit:token_usage` entry, which `LlmTask` accumulates across **every** model call the turn made — so a turn that escalated through three cascade steps and two tool round-trips reports the sum, not the last leg.

```json
"usage": {"prompt_tokens": 1204, "completion_tokens": 88, "total_tokens": 1292}
```

Two things follow from where the number comes from:

- **A rule-based agent reports no `usage` at all.** It called no model, so there is nothing to count. The field is omitted rather than zero-filled, because `0 tokens` reads in a client as a measurement rather than as an absence.
- **On the streaming path it is opt-in.** Per the OpenAI specification, usage is emitted only when the client sends `stream_options: {"include_usage": true}`, as a trailing frame with an empty `choices` array, after the `finish_reason` frame and before `[DONE]`. Clients that did not ask for it never see it — an unrequested empty-choices frame is a protocol deviation some clients reject.

### 7.2 Structured outputs

An EDDI turn can carry eight output types; the OpenAI protocol carries one string. Rather than drop everything but text, the adapter renders the rest as Markdown and appends it:

| EDDI output item | Rendered as |
|---|---|
| `text` | the text itself, unchanged |
| `quickReply` | `_Suggested replies:_ ` + each value in backticks |
| `image` | `![alt](uri)` |
| `applicationLink` | `[label](path)` |
| `button` | `**[label]**` — the label only; `onPress` is a client-side instruction with no meaning here |
| `inputField` | `**Label:**`, plus a warning for `password` that this channel cannot mask input |
| `agentFace` | dropped — an avatar has no text equivalent |
| `other` | dropped |

A turn combining a text item with quick replies arrives as one assistant message:

```
Which LLM provider should this agent use?

_Suggested replies:_ `Anthropic` · `OpenAI` · `Gemini` · `Ollama (local)`
```

Blocks are joined with a blank line, so each renders as its own paragraph. Values are de-duplicated, blank ones skipped, and original order preserved.

This rendering is local to the adapter. The shared `ConversationOutputExtractor` is unchanged — its other callers feed agent-to-agent prompts, where interaction affordances would be noise.

#### Why quick replies show the value, and why typing it works

A quick reply has two halves: a `value` the user sees (`Anthropic`) and an `expressions` token the behaviour rules match (`select_anthropic`). Rendering the *value* is not merely cosmetic — it is the half that round-trips.

`InputParserTask.prepareTemporaryDictionaries()` looks at the **previous** turn's output and, for every quick reply it finds, registers a temporary dictionary entry mapping the `value` to its `expressions` (`DictionaryUtilities.convertQuickReplies` — `addWord` for single words, `addPhrase` when the value contains a space). So when the user reads `` `Anthropic` `` and types `Anthropic`, the parser resolves it to `select_anthropic` and the matching rule fires, with no dictionary configured on the agent. This is why a quick-reply-driven agent works over `/v1` even when its parser step declares `"dictionaries": []`.

Two consequences worth knowing:

- **Rendering the `expressions` token instead would break this.** `select_anthropic` is not in any dictionary; only the value is registered. Showing the internal token would also leak implementation detail at the user.
- **The window is exactly one turn.** The temporary dictionary is rebuilt from the immediately preceding output, so quick replies are answerable on the *next* message only. If the user types something unrelated first, the options are no longer matchable by name and the agent falls back to whatever its `*` rules do.

Numbering (`1.`, `2.`) is deliberately avoided for the same reason: nothing registers `2` as an answer, so a numbered list would invite input the parser cannot resolve.

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
| `stateless:true` while stateless requests are disabled | 400 | `invalid_request_field` |
| No `user` message | 400 | `no_user_message` |
| Agent not deployed | 503 | `agent_not_ready` |
| Conversation busy / concurrency cap | 429 | — |
| Turn timed out | 504 | `timeout` |
| Adapter disabled | 404 | `unknown_endpoint` |

> **On streams:** once SSE headers are flushed the status is fixed at `200`. Failures during a stream therefore arrive as a content delta prefixed with ⚠️, followed by `finish_reason: "stop"` and `[DONE]`. This is a protocol constraint, not a shortcut — a stream that simply stopped would look like a hang. The concurrency cap applies the same way: a stream that cannot get a slot delivers a ⚠️ busy notice in-band instead of a `429`, because the `200` was already committed when the body started.

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

1. **Agent groups are not exposed as models.** Only individual agents appear in `/v1/models`; EDDI's multi-agent group discussions are unreachable over this API. Nothing blocks it — groups are listable, `discuss()` returns a `synthesizedAnswer`, `continueDiscussion()` gives multi-turn, and `GroupDiscussionEventListener` gives streaming — but it needs a second bridge with its own conversation mapping, streaming path and approval surface, so it belongs in its own change rather than bolted onto this one.
2. **Structured outputs are flattened to Markdown, not interactive.** Quick replies, buttons and input fields are rendered as text (§7.2), so the user reads and retypes them rather than clicking. `agentFace` and `other` items are dropped entirely.
3. **Regenerate is a real new turn**, not an idempotent replay.
4. **Stream-path errors must return 200** (see §8).
5. **One worker thread per in-flight completion**, bounded by `max-concurrent-requests`.
6. **`http-policy=authenticated` is impractical with Open WebUI**, which cannot mint per-user upstream OIDC tokens.
7. **`tool_calls` are never returned.** EDDI runs tools inside its pipeline; echoing them would make Open WebUI try to execute EDDI's HTTP/MCP/memory tools locally, where they do not exist. Tool activity remains visible in the audit ledger and `toolTrace`.
8. **Client-side tools cannot be used** — Open WebUI's built-in Files/knowledge tools, or any `tools[]` the client sends. Uploaded files are still readable through Open WebUI's RAG (§5); what is missing is the model invoking a client tool mid-answer. Doing this properly needs the multi-turn `tool_calls` → `role: "tool"` round trip, which the single-turn message mapping deliberately does not support, plus a way to distinguish client tools from EDDI's own so only the former are echoed. The clearest v2 candidate in this list.

---

## 11. Recipe: EDDI as a thin LLM gateway

If what you want from EDDI is **vaulted API keys, an audit trail, tenant quotas and cost tracking** — not agent logic — you do not need a passthrough proxy. A minimal LLM-only agent behind this adapter already provides all of it, and behaves like a plain model call.

> [!IMPORTANT]
> **Read the limits first.** This is genuinely useful for single-turn workloads (classification, extraction, summarisation, drafting). It is **not** a drop-in replacement for an OpenAI-compatible gateway such as LiteLLM or Portkey:
> - **Single-turn only.** The adapter sends just the last user message, so a client that manages its own history gets no context from earlier turns (§2).
> - **One agent per model.** A caller cannot send `model: "gpt-4o"` and have it routed; each model you want exposed is one agent config.
> - **No caching, fallbacks, load balancing or virtual per-user keys.** Those are what a dedicated gateway is for.
>
> If you need any of the above, use a real gateway. If you want key custody and an audit trail on single-turn calls you are already making, this is a few config files.

### What you get

| | |
|---|---|
| **API keys** | Stored via EDDI's Secrets Vault, never in the client |
| **Audit** | Every call recorded in the audit ledger with user attribution |
| **Quotas / cost** | Tenant quotas and per-conversation cost tracking apply |
| **Access control** | The adapter's shared key or OIDC, plus per-user identity |
| **Model swap** | Change provider or model in config; callers keep the same model id |

### The four config files

Assembled from the standard shapes — see [`langchain.md`](langchain.md) for the full LLM parameter reference and [`architecture.md`](architecture.md) for the workflow model. Use valid hex ids (≥18 chars) throughout.

**1. `…0002.behavior.json`** — fire the LLM on every user turn:

```json
{
  "expressionsAsActions": true,
  "behaviorGroups": [{
    "behaviorRules": [{
      "name": "Always answer",
      "actions": ["send_message"],
      "conditions": [{
        "type": "inputmatcher",
        "configs": { "expressions": "*", "occurrence": "currentStep" }
      }]
    }]
  }]
}
```

> A single unconditional `inputmatcher` deliberately departs from the guidance in [`AGENTS.md` §5.3](../AGENTS.md) that every rule carry an `actionmatcher` on `lastStep`. That rule exists to stop wizard-style agents firing out of order; here, firing on every turn *is* the intent.

**2. `…0003.langchain.json`** — the model:

```json
{
  "tasks": [{
    "actions": ["send_message"],
    "id": "gateway",
    "type": "openai",
    "description": "Thin LLM gateway",
    "parameters": {
      "apiKey": "${vault:openai-key}",
      "modelName": "gpt-4o",
      "systemMessage": "",
      "logSizeLimit": "-1",
      "addToOutput": "true"
    }
  }]
}
```

Set `logSizeLimit: "0"` if you want each turn to carry no conversation history at all — belt and braces alongside `:stateless`.

**3. `…0001.workflow.json`** — three steps, no output task (the LLM's `addToOutput` supplies the response):

```json
{
  "workflowSteps": [
    { "type": "eddi://ai.labs.parser",    "extensions": { "dictionaries": [], "corrections": [] }, "config": {} },
    { "type": "eddi://ai.labs.behavior",  "extensions": {}, "config": { "uri": "eddi://ai.labs.rules/rulestore/rulesets/…0002?version=1" } },
    { "type": "eddi://ai.labs.llm",       "extensions": {}, "config": { "uri": "eddi://ai.labs.llm/llmstore/llms/…0003?version=1" } }
  ]
}
```

**4. `…0000.agent.json`**:

```json
{ "workflows": ["eddi://ai.labs.workflow/workflowstore/workflows/…0001?version=1"], "channels": [] }
```

Name the agent descriptor something client-facing — `gpt-4o-gateway` — since it becomes the model id (`gpt-4o-gateway-<last6>`).

### Calling it

```python
import os
from openai import OpenAI

client = OpenAI(base_url="https://eddi.example.com/v1", api_key=os.environ["EDDI_API_KEY"],
                default_headers={"X-OpenWebUI-User-Id": "svc-billing"})

client.chat.completions.create(
    model="gpt-4o-gateway-a3f9c1",
    messages=[{"role": "user", "content": "Classify: 'my card was declined'"}],
    extra_body={"stateless": True},      # throwaway conversation per call
)
```

`stateless` matters here: without it every call from that identity accumulates into one conversation, since a service caller sends no `X-OpenWebUI-Chat-Id`.

Add one agent per model you want to expose. Each appears in `GET /v1/models` automatically.

> **Not verified end-to-end by the adapter's test suite.** The config shapes above are taken from working examples in this repository, but the recipe as a whole is a pattern — deploy it to a test environment before relying on it.

---

## See also

- [`architecture.md`](architecture.md) — the conversation pipeline
- [`hitl.md`](hitl.md) — human-in-the-loop approval gates
- [`../planning/openai-api-adapter-plan.md`](../planning/openai-api-adapter-plan.md) — design rationale and the research behind it
