# OpenAI-Compatible API Adapter (Open WebUI Integration) — Implementation Plan

> **Status: IMPLEMENTED** on `feat/openai-api-adapter`. Kept as the design record — it holds the research and the reasoning behind each decision. User-facing documentation is [`docs/open-webui-integration.md`](../docs/open-webui-integration.md).
> **Target:** EDDI backend (Java 25 / Quarkus 3.37.x), package `ai.labs.eddi.integrations.openai`.

## Deltas discovered during implementation

Four things differed from the plan below. They are recorded here rather than edited in, so the plan still reads as what was decided in advance.

1. **Phase 2 was unnecessary.** `ConversationOutputExtractor.extractResponse()` already exists in `engine/memory` (landed upstream) and handles strictly more output formats than the Slack-local copy this plan proposed extracting — including a plain-String `output`, an `output` map with a `text` key, the `reply` key, and a blank check. The adapter uses it directly. `SlackHitlSupport.extractSlackResponseText` still duplicates it; deduplicating that is tracked separately.
2. **`@Blocking` is `io.smallrye.common.annotation.Blocking`**, not `org.jboss.resteasy.reactive.Blocking`, in this Quarkus version. `StreamingOutput` worked as expected — the `RoutingContext` fallback was not needed.
3. **The resolved userId reaches the resource via `@Context ContainerRequestContext`**, which the auth filter populates with a request property. Cleaner than the `ResteasyContext` lookup first attempted.
4. **Slugging needed Unicode folding.** `[^a-z0-9]+` alone turned `Übersicht` into `bersicht`, mangling every non-ASCII agent name. NFD normalisation plus combining-mark stripping was added; caught by its own test.

---

## Table of Contents

1. [Goal](#1-goal)
2. [Corrections to the previous draft](#2-corrections-to-the-previous-draft)
3. [What is supported](#3-what-is-supported)
4. [How it works end-to-end](#4-how-it-works-end-to-end)
5. [Architecture](#5-architecture)
6. [Component specifications](#6-component-specifications)
7. [Wire protocol reference](#7-wire-protocol-reference)
8. [Configuration reference](#8-configuration-reference)
9. [Security model](#9-security-model)
10. [Error contract](#10-error-contract)
11. [Open WebUI setup guide](#11-open-webui-setup-guide)
12. [Non-goals, known gaps, v2](#12-non-goals-known-gaps-v2)
13. [File manifest](#13-file-manifest)
14. [Implementation order](#14-implementation-order)
15. [Test plan](#15-test-plan)
16. [Pillar validation](#16-pillar-validation)

---

## 1. Goal

[Open WebUI](https://github.com/open-webui/open-webui) — and every other OpenAI-protocol client (Python `openai` SDK, LangChain, LiteLLM, Continue, Cursor, …) — talks to a backend exposing `GET /v1/models` and `POST /v1/chat/completions`. EDDI has no such surface.

Build a **thin protocol adapter** that presents deployed EDDI agents as OpenAI "models" and translates the stateless OpenAI request/response protocol onto EDDI's stateful conversation lifecycle. No EDDI core changes. The adapter is a channel integration, structurally parallel to `integrations/slack`.

**The central tension:** OpenAI's API is stateless (client resends full `messages[]` every turn, server keeps nothing). EDDI is stateful (conversations accumulate memory, properties, behavior-rule position, HITL bookmarks). The adapter's whole job is bridging that, correctly and without leaking one user's conversation into another's.

---

## 2. Corrections to the previous draft

The prior plan's EDDI-side audit was accurate. Its Open WebUI–side research was not. Every item below was verified against source before writing this plan; each is a **behavioural** correction, not a stylistic one.

| # | Prior claim | Reality | Consequence |
|---|---|---|---|
| **C1** | Route sync vs. streaming via two `@Produces` methods; "all real-world OpenAI clients send `Accept: text/event-stream`". | **False for both named clients.** `openai-python` hardcodes `"Accept": "application/json"` in default headers and never varies it by `stream`. Open WebUI sets **no** `Accept` header at all (`get_headers_and_cookies()` sets only `Content-Type`); it detects streaming from the *response* `Content-Type`. | Content negotiation cannot work. **→ one method, dispatch on the `stream` field in the body.** See [§6.1](#61-restopenaiadapter). |
| **C2** | "Open WebUI PR #27174 already maps `chat_id` → the standard OpenAI `user` field." | [#27174](https://github.com/open-webui/open-webui/pull/27174) is an **issue**, **closed as not planned**. Never implemented. In `routers/openai.py` on `main`, `payload['user']` is set **only for pipeline models**, and its value is an object `{name,id,email,role}` — not a chat id. | "Mode 2" never activates; every chat window collides. **→ key on `X-OpenWebUI-Chat-Id`** ([PR #15813](https://github.com/open-webui/open-webui/pull/15813), gated by `ENABLE_FORWARD_USER_INFO_HEADERS` — which the prior plan's own compose file already set). `user` becomes a secondary fallback, and must be parsed defensively because it is sometimes an object. |
| **C3** | Adapter `@Inject`s `IRestAgentAdministration` for `/v1/models`. | That interface is `@RolesAllowed({"eddi-admin","eddi-editor"})` at type level ([IRestAgentAdministration.java:28](../src/main/java/ai/labs/eddi/engine/api/IRestAgentAdministration.java#L28)). Quarkus enforces `@RolesAllowed` via a CDI interceptor, so a direct bean-to-bean call **is** intercepted → 403 for every ordinary user. Same for `IRestAgentStore` ([:27](../src/main/java/ai/labs/eddi/configs/agents/IRestAgentStore.java#L27)). | **→ inject the underlying stores/services, never the `IRest*` facades.** Those facades *are* the authorization boundary; reaching around them from a new public surface defeats them. |
| **C4** | "Authentication: OIDC first, fall back to static key ✅" | No mechanism was designed. [application.properties:262-264](../src/main/resources/application.properties#L262) applies `policy=authenticated` to `/,/*`, so `/v1/*` is captured (Slack needed an explicit permit rule at [:258-260](../src/main/resources/application.properties#L258); none was proposed here). With OIDC on, `Authorization: Bearer sk-no-key` is rejected by the OIDC mechanism **before** JAX-RS, so an in-resource key check is unreachable. | The demo compose and all curl examples only work with auth disabled. **→ explicit two-mode design + startup guard.** See [§9](#9-security-model). |
| **C5** | Mode 1 keys on `{agentId}` + `default-user`, framed as "same as Slack". | Not the same. Slack keys on the **real Slack user id** ([SlackEventHandler.java:312,709](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java#L312)). With a shared `default-user`, every anonymous caller resolves to one `(intent, userId)` row → **one shared conversation, shared memory, shared long-term user memory across all users.** | That is cross-user data leakage, not a limitation. **→ default `enabled=false`; refuse to serve without a resolvable identity unless anonymity is explicitly opted into.** |
| **C6** | HITL not mentioned. | `sayStreaming` throws `ConversationAwaitingApprovalException` ([IConversationService.java:120-125](../src/main/java/ai/labs/eddi/engine/api/IConversationService.java#L120)); `onSkipped` fires for **both** `AWAITING_HUMAN` and `IN_PROGRESS` ([:181-188](../src/main/java/ai/labs/eddi/engine/api/IConversationService.java#L181)). The prior plan mapped `onSkipped` to a single "conversation busy" message. | Any agent using `PAUSE_CONVERSATION` or `hitlConfig.toolApprovals` becomes permanently unusable with a misleading error. **→ mirror Slack's sentinel-snapshot discrimination** ([SlackEventHandler.java:766-773](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java#L766)). See [§6.3](#63-openaiconversationbridge). |
| **C7** | New-chat heuristic: `userMessageCount == 1 && existingConversationHasTurns` → `endConversation`. | Self-contradictory with the plan's own recommended Open WebUI Filter, which **strips history to the last user message** — making *every* turn look like `userMessageCount == 1`, destroying and recreating the conversation on every message. Also misfires on regenerate and edit-and-resend. `endConversation` is unrecoverable. | **→ delete the heuristic entirely.** With `X-OpenWebUI-Chat-Id`, a new chat *is* a new key. |
| **C8** | `UtilityAgentProvisioner` auto-creates + deploys an `eddi-utility` agent on first request. | Unauthenticated caller triggers config writes, clones another agent's credential reference, and consumes `maxAgentsPerTenant` quota ([IRestAgentAdministration.java:33-36](../src/main/java/ai/labs/eddi/engine/api/IRestAgentAdministration.java#L33)). Blocked by C3 anyway. In tension with Pillar 1 (engine authoring agent config at runtime). | **→ cut the component.** Replaced by the `:stateless` model suffix ([§3](#3-what-is-supported)) plus documentation. Zero writes, zero roles, and a genuinely more useful feature. |
| **C9** | `ChatCompletionResponse.usage` listed as a field. | Never sourced. EDDI does not surface per-request token counts to this layer. | **→ omit `usage` in v1** and say so. Emitting zeros is worse than omitting — Open WebUI would render "0 tokens" as fact. |
| **C10** | Model `id` = "descriptor name (slugified)". | Descriptor names are not unique; slugification is lossy. Resolution chain `id → name → slug` is non-deterministic with two agents named "Support". | **→ `<slug>-<agentId last 6 hex>`**: readable *and* unique. See [§6.2](#62-agentmodelresolver). |
| **C11** | Multimodal: `mimeType = url.substring(5, url.indexOf(';'))`; `"image/*"` for remote URLs. | Throws `StringIndexOutOfBoundsException` on `data:image/png,…` (no `;base64`). `"image/*"` passes `AttachmentForwarder`'s `mime.startsWith("image/")` gate but is then handed to `ImageContent.from(base64, mimeType)` ([AttachmentForwarder.java:226](../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java#L226)) where a concrete type is required. | **→ hardened parser + extension-derived concrete MIME.** See [§6.4](#64-openaimessagemapper). |

**What the prior plan got right and is carried forward unchanged:** the `attachment_N` multimodal mapping (verified exactly correct against `AttachmentContextExtractor`'s documented contract); the honest tool-calling assessment (echoing `tool_calls` would cause double-execution); the accurate description of Open WebUI's internal RAG; the Inlet-Filter-over-upstream-PR recommendation; and the confirmed EDDI API signatures (`sayStreaming(conversationId,…)`, `ConversationResult`, the `(intent,userId)` unique index).

---

## 3. What is supported

### 3.1 Endpoints

| Endpoint | Support |
|---|---|
| `GET /v1/models` | ✅ Lists deployed agents as models |
| `POST /v1/chat/completions` (`stream:false`) | ✅ Single JSON response |
| `POST /v1/chat/completions` (`stream:true`) | ✅ SSE `chat.completion.chunk` stream |
| `GET /v1/models/{id}` | ✅ Single model lookup (cheap; some clients probe it) |
| `/v1/embeddings`, `/v1/completions`, `/v1/audio/*`, `/v1/images/*` | ❌ 404 with a proper OpenAI error body |

### 3.2 Model naming

Each deployed agent is exposed under **two** ids:

| Model id | Semantics |
|---|---|
| `customer-support-a3f9c1` | **Stateful (default).** Maps to a persistent EDDI conversation keyed by chat + user. Memory, properties, behavior-rule position and HITL all persist across turns. |
| `customer-support-a3f9c1:stateless` | **Stateless.** Starts a conversation, sends one message, ends it, returns. Classic OpenAI semantics. Use for title/tag generation and for clients that genuinely want no memory. |

Format: `<slugified-descriptor-name>-<last 6 hex chars of agentId>`. Readable in the Open WebUI dropdown, collision-free, stable across restarts.

The resolver additionally accepts, for convenience: the bare 24-char `agentId`; the exact descriptor name; and the bare slug — the last two **only when unambiguous** (otherwise 400 listing candidates).

### 3.3 Request features honoured

| Field | Behaviour |
|---|---|
| `model` | Resolved to an agent (see above). Required. |
| `messages[]` | **Only the last `role:"user"` message is sent to EDDI.** Prior turns are ignored — EDDI has its own history. |
| `messages[]` with `role:"system"` | Last system message injected as context key `openai_system_message`. **Never overrides the agent's own system prompt** (Pillar 1). Agent designers opt in via `{context.openai_system_message}`. This is also where Open WebUI's RAG chunks arrive. |
| `messages[].content` as `String` | Plain text. |
| `messages[].content` as array (`text` + `image_url`) | Text extracted; images mapped to EDDI `attachment_N` context → full `AttachmentForwarder` pipeline (vision capability gating, byte caps, `ImageContent`). Both `data:` URIs and remote URLs. |
| `stream` | Selects JSON vs SSE. **This is the only dispatch signal.** |
| `user` | Fallback chat key when `X-OpenWebUI-Chat-Id` is absent. Parsed defensively (string *or* object). |
| `stream_options`, `temperature`, `max_tokens`, `top_p`, `tools`, `tool_choice`, `metadata`, `files`, … | **Accepted and ignored.** Must never 400. Model parameters live in the agent's `langchain.json` (Pillar 1). Jackson is already `FAIL_ON_UNKNOWN_PROPERTIES=false` ([SerializationCustomizer.java:69](../src/main/java/ai/labs/eddi/datastore/serialization/SerializationCustomizer.java#L69)) — verify the adapter's mapper inherits it. |

### 3.4 Headers honoured

| Header | Purpose |
|---|---|
| `Authorization: Bearer <key>` | Static API key (mode A) or OIDC token (mode B). See [§9](#9-security-model). |
| `X-OpenWebUI-Chat-Id` | **Primary chat/session key** → per-chat conversation isolation. |
| `X-OpenWebUI-User-Id` | User identity → EDDI `userId` (memory, GDPR, audit). Trusted only when `trust-user-headers=true`. |
| `X-OpenWebUI-User-Email`, `-Name`, `-Role` | Ignored in v1; reserved. |

Response always carries `X-EDDI-Conversation-Id` so operators (and HITL approvers) can correlate an OpenAI request with an EDDI conversation.

### 3.5 What flows through EDDI unchanged

Because messages traverse the normal `LifecycleManager` pipeline via `IConversationService`, all of this works with no adapter involvement: behavior rules, property setters, HTTP calls, MCP/A2A tools, built-in tools, RAG (`VectorStoreRetrievalTask`), multi-model cascading, group conversations, conversation windowing, persistent user memory, tool cost tracking, rate limiting, the audit ledger, and GDPR Art. 18 restriction checks.

### 3.6 HITL

Fully supported, surfaced as chat text rather than as errors:

- Turn pauses (`PAUSE_CONVERSATION`) or tool-call gates → the adapter returns a normal `200` assistant message containing any output-so-far plus a pause notice and the conversation id. A red error toast would lose the user's message; a chat message does not.
- Further messages while paused → a "still awaiting approval" assistant message, not an error.
- Approval happens out-of-band (`POST /agents/{conversationId}/resume`, Slack card, or Manager UI). The next Open WebUI message continues normally.

---

## 4. How it works end-to-end

### 4.1 First message in a new Open WebUI chat

```
Open WebUI                          EDDI adapter                         EDDI core
    │
    │ POST /v1/chat/completions
    │   Authorization: Bearer <api-key>
    │   X-OpenWebUI-Chat-Id: 4f2b…
    │   X-OpenWebUI-User-Id: u_812
    │   {"model":"support-a3f9c1","messages":[…],"stream":true}
    ├──────────────────────────────────►│
    │                                   │ 1. OpenAiAuthFilter → key ok, principal=u_812
    │                                   │ 2. resolve "support-a3f9c1" → agentId 66…a3f9c1
    │                                   │ 3. intent = "channel:openai:66…a3f9c1:4f2b…"
    │                                   │    userId = "u_812"
    │                                   │ 4. IUserConversationStore.readUserConversation → null
    │                                   ├─ startConversation(production, agentId, u_812,
    │                                   │     {channelIntent, openai_system_message}) ─────►│
    │                                   │◄──────────────── ConversationResult(convId) ──────┤
    │                                   │ 5. createUserConversation(intent,u_812,…,convId)
    │                                   │ 6. extract last user msg (+ attachments)
    │                                   ├─ sayStreaming(convId, …, handler) ───────────────►│
    │◄─ data: {…"delta":{"role":"assistant"}…}                                              │
    │◄─ data: {…"delta":{"content":"Hel"}…}   ◄── onToken("Hel")                            │
    │◄─ data: {…"delta":{"content":"lo"}…}    ◄── onToken("lo")                             │
    │◄─ data: {…"delta":{},"finish_reason":"stop"} ◄── onComplete(snapshot)                 │
    │◄─ data: [DONE]                                                                        │
```

### 4.2 Second message in the same chat

Steps 1–3 identical. Step 4 now **finds** the mapping → reuses `convId`. No conversation is created, no history replayed. EDDI's own memory supplies the context; the `messages[]` the client resent are discarded. This is the entire point of the design.

### 4.3 A different Open WebUI chat, same user, same agent

`X-OpenWebUI-Chat-Id` differs → different `intent` → different row in `IUserConversationStore` → **a separate EDDI conversation**. The two chats cannot see each other's memory. This is the isolation guarantee that C2/C5 restore.

### 4.4 Agent pauses for human approval

```
onComplete(snapshot) with snapshot.conversationState == AWAITING_HUMAN
  → emit output-so-far (if any) as content deltas
  → emit "\n\n⏸️ Awaiting human approval. Conversation: 66…c1d2"
  → finish_reason "stop", [DONE], HTTP 200
```
The user sees a normal assistant turn. A reviewer approves via the existing resume surfaces. The next message resumes.

### 4.5 A message arrives while the previous turn is still running

`ConversationCoordinator` drops it → `onSkipped(snapshot)` with a non-`AWAITING_HUMAN` state → sync path returns `429` (clients back off and retry), stream path emits a "busy" content chunk (headers are already sent; 429 is impossible).

### 4.6 Title generation (`:stateless`)

Open WebUI's task model is pointed at `support-a3f9c1:stateless`. The adapter starts a conversation, sends the title prompt, reads the reply, calls `endConversation`, and returns — the user's real conversation is untouched. Documented caveat: quality depends on the agent's own system prompt; pointing Open WebUI's task model at a *separate* connection (Ollama, etc.) gives better titles and is the recommended production setup.

---

## 5. Architecture

```
┌─────────────────┐   OpenAI protocol   ┌──────────────────────────────────────────────┐
│   Open WebUI    │  GET  /v1/models     │  ai.labs.eddi.integrations.openai            │
│   openai SDK    │  POST /v1/chat/…     │                                              │
│   LiteLLM       ├─────────────────────►│  OpenAiAuthFilter        (@Provider, /v1)    │
│   LangChain     │                      │  RestOpenAiAdapter       (@Path("/v1"))      │
│                 │◄─────────────────────┤  AgentModelResolver      (Caffeine 30s)      │
└─────────────────┘  JSON  /  SSE        │  OpenAiConversationBridge (the bridge)       │
                                         │  OpenAiSseWriter         (chunk framing)     │
                                         │  OpenAiMessageMapper     (msg → InputData)   │
                                         │  OpenAiCompatConfig      (@ConfigMapping)    │
                                         │  OpenAiStartupGuard      (@Observes Startup) │
                                         │  model/  (DTOs)                              │
                                         └──────────────────┬───────────────────────────┘
                                                            │ CDI — no IRest* facades
                                                            ▼
                                         ┌──────────────────────────────────────────────┐
                                         │  EDDI core (unchanged except one extraction) │
                                         │  IConversationService                        │
                                         │  IUserConversationStore                      │
                                         │  IAgentStore / IDeploymentStore              │
                                         │  ConversationOutputText  ← extracted shared  │
                                         └──────────────────────────────────────────────┘
```

**The one core change:** `SlackHitlSupport.extractSlackResponseText` ([SlackHitlSupport.java:386-443](../src/main/java/ai/labs/eddi/integrations/slack/SlackHitlSupport.java#L386)) is channel-agnostic logic living in a Slack class. Extract it to `ai.labs.eddi.engine.memory.ConversationOutputText.extract(snapshot)` and leave `SlackHitlSupport.extractSlackResponseText` as a one-line delegate so Slack tests are untouched. Do **not** duplicate it.

---

## 6. Component specifications

### 6.1 `RestOpenAiAdapter`

`@ApplicationScoped @Path("/v1")`.

> **C1 is the single most important correction in this plan.** There is exactly **one** `POST /chat/completions` method. Dispatch is on `request.stream()`, never on `Accept`.

```java
@POST
@Path("/chat/completions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces({MediaType.APPLICATION_JSON, MediaType.SERVER_SENT_EVENTS})
@Blocking
public Response chatCompletions(ChatCompletionRequest request,
                                @Context HttpHeaders headers) {
    // Boolean.TRUE.equals(request.stream()) — `stream` is nullable in the wire format.
    if (Boolean.TRUE.equals(request.stream())) {
        StreamingOutput body = out -> bridge.streamCompletion(request, headers, out);
        return Response.ok(body)
                .type(MediaType.SERVER_SENT_EVENTS)          // explicit — overrides @Produces order
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")           // defeats nginx proxy buffering
                .build();
    }
    ChatCompletionResponse dto = bridge.completion(request, headers);
    return Response.ok(dto).type(MediaType.APPLICATION_JSON).build();
}
```

**Implementation notes**

- `@Blocking` is required: the bridge blocks on `CompletableFuture.get(timeout)` exactly as Slack's `sendAndWait` does ([SlackEventHandler.java:748-778](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java#L748)). Consequence: one worker thread per in-flight completion. Bound it with `eddi.openai-compat.max-concurrent-requests` (semaphore; reject with `429` when exhausted).
- **Verify at implementation time:** that `quarkus-rest` in the pinned Quarkus version resolves `StreamingOutput`. It ships a `StreamingOutputMessageBodyWriter`, so it should. If it does not, the fallback is `@Context RoutingContext` and writing to `ctx.response()` directly — same framing, same headers, one extra Vert.x import. Do not fall back to two-method content negotiation.
- `X-EDDI-Conversation-Id` is set on the sync response. On the stream path headers are flushed before the conversation id is known, so it is instead surfaced in the pause/error message text.

**`GET /v1/models` / `GET /v1/models/{id}`** — delegate to `AgentModelResolver.listModels()` / `.resolve(id)`. Never inject `IRestAgentAdministration` (C3).

**Unsupported `/v1/*` paths** — add a catch-all returning `404` with an OpenAI-shaped error, so probing clients get a parseable body instead of an HTML error page.

### 6.2 `AgentModelResolver`

`@ApplicationScoped`. Caffeine cache, 30s TTL, refreshed on miss.

**Data source:** the deployment store / agent descriptor store directly — the same data `getDeploymentStatuses(Environment)` returns as `List<AgentDeploymentStatus>` (fields: `agentId`, `agentVersion`, `status`, `descriptor`), but reached **without** the role-gated REST facade (C3). Filter to `status == READY`.

```java
public record ResolvedModel(String agentId, Environment environment,
                            String displayName, String modelId, boolean stateless) {}

List<ModelObject> listModels();                  // both variants per ready agent
ResolvedModel resolve(String modelId) throws UnknownModelException, AmbiguousModelException;
```

**Model id construction**

```java
String slug = displayName.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
if (slug.isBlank()) slug = "agent";
String modelId = slug + "-" + agentId.substring(agentId.length() - 6);
```

**Resolution order** — first match wins; `:stateless` suffix stripped first and recorded on the result:

1. exact `modelId` (canonical)
2. exact `agentId`
3. exact descriptor name, case-insensitive — **only if exactly one match**, else `AmbiguousModelException`
4. bare slug — **only if exactly one match**, else `AmbiguousModelException`

Environment is `eddi.openai-compat.environment` (default `production`), not hardcoded (C: prior plan pinned `Environment.production`).

### 6.3 `OpenAiConversationBridge`

`@ApplicationScoped`. The core of the adapter.

**Injects:** `IConversationService`, `IUserConversationStore`, `AgentModelResolver`, `OpenAiMessageMapper`, `OpenAiCompatConfig`, `MeterRegistry`.

#### Session key resolution

```java
/** Chat/session key — decides WHICH EDDI conversation. */
String resolveChatKey(HttpHeaders headers, ChatCompletionRequest req) {
    String h = headers.getHeaderString("X-OpenWebUI-Chat-Id");     // C2: the real mechanism
    if (h != null && !h.isBlank()) return h;
    String u = req.userAsString();                                 // C2: defensive — may be an object
    if (u != null && !u.isBlank()) return u;
    return null;                                                   // → shared per-user conversation
}

/** EDDI userId — decides WHOSE memory/GDPR/audit scope. Distinct concern. */
String resolveUserId(HttpHeaders headers, SecurityIdentity identity) { … }  // see §9
```

**Intent key** — mirrors Slack's `"channel:slack:…"` convention:

```java
String intent = "channel:openai:" + agentId + ":" + (chatKey != null ? chatKey : "default");
```

`(intent, userId)` is uniquely indexed in both Mongo and Postgres, so this is the natural primary key.

#### `getOrCreateConversation` — models `SlackEventHandler.getOrCreateConversation` ([:705-730](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java#L705))

```
1. existing = userConversationStore.readUserConversation(intent, userId)   // null if absent
2. if existing != null:
     state = conversationService.getConversationState(existing.getConversationId())
     if state == ENDED or conversation missing:
         userConversationStore.deleteUserConversation(intent, userId)
         existing = null
     else:
         return existing.getConversationId()
3. result = conversationService.startConversation(env, agentId, userId, contextMap)
4. try   { userConversationStore.createUserConversation(new UserConversation(...)) }
   catch (ResourceAlreadyExistsException e) {          // concurrent first-messages
       // lost the race — adopt the winner's conversation, end our orphan
       re-read, endConversation(result.conversationId()), return winner's id
   }
5. return result.conversationId()
```

`contextMap` carries `channelIntent` (string, `= intent`) and, when present, `openai_system_message` (string).

**There is no new-chat heuristic (C7).** A new Open WebUI chat produces a new `X-OpenWebUI-Chat-Id` and therefore a new intent. Nothing is ever destroyed on a guess.

#### `completion(request, headers)` — the sync path

```
1. resolved = modelResolver.resolve(request.model())
2. userId   = resolveUserId(...)          // 401 if unresolvable and anonymity not allowed
3. inputData = messageMapper.toInputData(request)   // may throw NoUserMessageException → 400
4. if resolved.stateless():
       convId = startConversation(...)     // no store mapping, no intent
   else:
       convId = getOrCreateConversation(...)
5. snapshot = sendAndWait(convId, inputData)        // CompletableFuture, timeout from config
6. if resolved.stateless(): endConversation(convId)  // always, in a finally block
7. text = renderAssistantText(snapshot)
8. return ChatCompletionResponse(id="chatcmpl-" + uuid, model=request.model(),
                                 choices=[Choice(0, ChatMessage("assistant", text), "stop")])
```

**`sendAndWait` — HITL discrimination (C6).** Copy the sentinel pattern verbatim from Slack ([:748-778](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java#L748)):

```java
@Override public void onSkipped(SimpleConversationMemorySnapshot s) {
    boolean stillAwaiting = s != null && s.getConversationState() == ConversationState.AWAITING_HUMAN;
    future.complete(stillAwaiting ? SKIPPED_STILL_AWAITING : SKIPPED_NOT_ACTIVE);
}
```

`renderAssistantText(snapshot)` then branches:

| Snapshot | Rendered as | HTTP |
|---|---|---|
| normal `onComplete` | `ConversationOutputText.extract(snapshot)` | 200 |
| `onComplete`, state `AWAITING_HUMAN` | output-so-far + `"\n\n⏸️ Awaiting human approval. Conversation: <id>"` | 200 |
| `SKIPPED_STILL_AWAITING` | `"⏸️ Still awaiting approval — a reviewer must decide before I can continue."` | 200 |
| `SKIPPED_NOT_ACTIVE` | — | **429** `rate_limit_exceeded` |

**Stale-mapping self-heal:** catch `ConversationEndedException` around the `say`, delete the mapping, create a fresh conversation, **retry exactly once**. A once-only retry cannot loop.

#### `streamCompletion(request, headers, OutputStream out)` — the stream path

Same 1–4. Then `sayStreaming` with a `StreamingResponseHandler` driving `OpenAiSseWriter`. Terminate the stream in a `finally` regardless of outcome — a hung SSE stream is worse than a truncated one.

**Token/no-token reconciliation.** Not every agent streams tokens: `LlmTask` does, but a purely rule-based agent produces text only via `OutputTask` at `onComplete`. Track `boolean anyTokenEmitted`:

- tokens were emitted → on complete, emit **only** the finish chunk. Do **not** also emit the snapshot text; that would duplicate the whole reply.
- no tokens → emit `ConversationOutputText.extract(snapshot)` as one content delta, then finish.

`onTaskStart` / `onTaskComplete` / `onTaskFailed` / `onCascade*` are suppressed — not part of the OpenAI protocol. (v2: expose them behind a config flag as informational deltas.)

**Metrics** — `eddi.openai.requests` (tags `mode=sync|stream`, `outcome=ok|error|busy|paused`), `eddi.openai.conversations.created`, `eddi.openai.request.duration`, `eddi.openai.models.listed`.

### 6.4 `OpenAiMessageMapper`

`@ApplicationScoped`, pure functions, no I/O. The most test-dense component — unit-test it hard.

```java
InputData toInputData(ChatCompletionRequest req) throws NoUserMessageException;
```

**Algorithm**

1. Last `role:"system"` message → `openai_system_message` context (string). Ignore any earlier ones.
2. Last `role:"user"` message → the input. If absent → `NoUserMessageException` → 400.
3. If its `content` is a `String` → `new InputData(text, contexts)`.
4. If it is an array → concatenate all `type:"text"` parts with `"\n"`; map each `type:"image_url"` part to `attachment_<n>`.

**Attachment mapping** — matches `AttachmentContextExtractor`'s documented contract exactly (`Context(object, Map{mimeType, data|url, fileName})`):

```java
// data: URI  →  data:<mime>[;base64],<payload>
if (url.startsWith("data:")) {
    int comma = url.indexOf(',');
    if (comma < 0) { skip with a warning; }                 // C11: malformed, don't throw
    String meta = url.substring(5, comma);                   // "image/png;base64" or "image/png"
    int semi = meta.indexOf(';');
    String mime = (semi >= 0 ? meta.substring(0, semi) : meta).trim();
    if (mime.isBlank()) mime = "image/jpeg";
    attach.put("mimeType", mime);
    attach.put("data", url.substring(comma + 1));            // base64 payload
} else {
    attach.put("mimeType", mimeFromExtension(url));          // C11: concrete, never "image/*"
    attach.put("url", url);
}
attach.put("fileName", "openai-attachment-" + idx);
contexts.put(AttachmentContextExtractor.ATTACHMENT_PREFIX + idx++,
             new Context(Context.ContextType.object, attach));
```

`mimeFromExtension` maps `.png/.jpg/.jpeg/.gif/.webp` → the concrete type, defaulting to `image/jpeg`. This matters because `ImageContent.from(base64, mimeType)` receives it verbatim ([AttachmentForwarder.java:226](../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java#L226)) and providers reject wildcards. Open WebUI sends `data:` URIs in practice, so the URL branch is a compatibility path for other clients.

Cap attachments at `AttachmentContextExtractor.DEFAULT_MAX_ATTACHMENTS_PER_TURN`; drop the excess with a warning rather than failing the request.

### 6.5 `OpenAiSseWriter`

Small, stateless framing helper over an `OutputStream`. Not a CDI bean.

```java
void role(String id, String model);                    // opening delta {"role":"assistant"}
void content(String id, String model, String text);    // delta {"content": …}
void finish(String id, String model, String reason);   // delta {} + finish_reason
void done();                                           // "data: [DONE]\n\n"
```

Every frame is `data: <compact-json>\n\n`, UTF-8, **flushed immediately** — an unflushed buffer makes streaming look broken. Use the injected Quarkus `ObjectMapper` for escaping; never hand-build JSON with `String.format` (the existing `RestAgentEngineStreaming` does in places, and it is a latent escaping bug — do not copy that part).

### 6.6 `OpenAiAuthFilter`

`@Provider @PreMatching` `ContainerRequestFilter`, scoped to `/v1` paths only. See [§9](#9-security-model).

### 6.7 `OpenAiCompatConfig` / `OpenAiStartupGuard`

`@ConfigMapping(prefix = "eddi.openai-compat")` interface for all knobs in [§8](#8-configuration-reference).

`OpenAiStartupGuard` observes `StartupEvent` and **fails startup** when the adapter is exposed without protection — mirroring the existing `AuthStartupGuard` philosophy:

```
if (enabled && "permit".equals(httpPolicy) && apiKey.isBlank()
        && ConfigProvider.getConfig().getValue("authorization.enabled", boolean.class)) {
    throw new IllegalStateException(
        "eddi.openai-compat is enabled with an unauthenticated /v1 surface and no api-key. "
      + "Set eddi.openai-compat.api-key, or set eddi.openai-compat.http-policy=authenticated, "
      + "or set eddi.openai-compat.enabled=false.");
}
```

---

## 7. Wire protocol reference

### `GET /v1/models`

```json
{"object":"list","data":[
  {"id":"customer-support-a3f9c1","object":"model","created":1753500000,"owned_by":"eddi"},
  {"id":"customer-support-a3f9c1:stateless","object":"model","created":1753500000,"owned_by":"eddi"}
]}
```

### `POST /v1/chat/completions` — `stream:false`

```json
{
  "id":"chatcmpl-eddi-7f3a…","object":"chat.completion","created":1753500123,
  "model":"customer-support-a3f9c1",
  "choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}]
}
```
`usage` is **omitted** in v1 (C9). Response header `X-EDDI-Conversation-Id: 66…c1d2`.

### `POST /v1/chat/completions` — `stream:true`

```
data: {"id":"chatcmpl-eddi-7f3a…","object":"chat.completion.chunk","created":1753500123,"model":"customer-support-a3f9c1","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}

data: {"id":"chatcmpl-eddi-7f3a…","object":"chat.completion.chunk","created":1753500123,"model":"customer-support-a3f9c1","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

data: {"id":"chatcmpl-eddi-7f3a…","object":"chat.completion.chunk","created":1753500123,"model":"customer-support-a3f9c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

data: [DONE]
```

`id`, `created` and `model` are **identical across every chunk of one response** — some clients key on this.

### DTOs (`integrations/openai/model/`) — Java records

| Record | Shape |
|---|---|
| `ChatCompletionRequest` | `String model, List<ChatMessage> messages, Boolean stream, JsonNode user` — plus `userAsString()`: returns the text if `user` is textual, `user.get("id").asText()` if it is an object (C2), else `null`. |
| `ChatMessage` | `String role, JsonNode content` — plus `isPlainText()`, `textContent()`, `contentParts()`. `JsonNode` sidesteps a custom polymorphic deserializer entirely; do not write one. |
| `ContentPart` | `String type, String text, ImageUrl imageUrl` (`@JsonProperty("image_url")`) |
| `ImageUrl` | `String url, String detail` |
| `ChatCompletionResponse` | `String id, String object, long created, String model, List<Choice> choices` |
| `ChatCompletionChunk` | `String id, String object, long created, String model, List<ChunkChoice> choices` |
| `Choice` | `int index, ChatMessage message, String finishReason` (`@JsonProperty("finish_reason")`) |
| `ChunkChoice` | `int index, Delta delta, String finishReason` |
| `Delta` | `String role, String content` — both `@JsonInclude(NON_NULL)` |
| `ModelsResponse` / `ModelObject` | as shown above |
| `OpenAiError` / `OpenAiErrorResponse` | `{"error":{message,type,param,code}}` |

Annotate response records `@JsonInclude(JsonInclude.Include.NON_NULL)` so `usage` and empty deltas serialize cleanly.

---

## 8. Configuration reference

```properties
# ═══ OpenAI-Compatible API Adapter ═══
# Disabled by default (C5): enabling exposes a new conversation surface.
eddi.openai-compat.enabled=false

# Shared secret clients send as `Authorization: Bearer <key>`. REQUIRED when
# http-policy=permit and authorization.enabled=true (enforced by OpenAiStartupGuard).
eddi.openai-compat.api-key=

# permit        → adapter enforces the static api-key itself (Open WebUI default)
# authenticated → Quarkus OIDC validates the bearer token; api-key ignored
eddi.openai-compat.http-policy=permit

# Trust X-OpenWebUI-User-Id as the EDDI userId. Only safe when the caller already
# proved possession of the api-key — i.e. Open WebUI is a trusted proxy. If the key
# leaks, a holder can impersonate any user. See §9.
eddi.openai-compat.trust-user-headers=true

# Serve callers with no resolvable identity. When true, ALL such callers share one
# conversation per (agent, chat). Leave false in any multi-user deployment (C5).
eddi.openai-compat.allow-anonymous=false
eddi.openai-compat.default-user=openai-anonymous

eddi.openai-compat.environment=production
eddi.openai-compat.request-timeout-seconds=120
eddi.openai-compat.max-concurrent-requests=64
eddi.openai-compat.model-cache-seconds=30
eddi.openai-compat.expose-stateless-variants=true

# ═══ HTTP auth policy for /v1 (REQUIRED — /,/* would otherwise capture it) ═══
quarkus.http.auth.permission.openai-compat.paths=/v1/*
quarkus.http.auth.permission.openai-compat.policy=${eddi.openai-compat.http-policy:permit}
quarkus.http.auth.permission.openai-compat.methods=GET,POST,OPTIONS
```

CORS already permits `http://localhost:3000` ([application.properties:152](../src/main/resources/application.properties#L152)); a non-default Open WebUI origin must be added there.

---

## 9. Security model

### Two modes, chosen by `http-policy`

**Mode A — `permit` (default; the Open WebUI path).** Quarkus lets `/v1/*` through unauthenticated; `OpenAiAuthFilter` enforces the shared `api-key` with a **constant-time** comparison (`MessageDigest.isEqual` on UTF-8 bytes — not `String.equals`). Identity comes from `X-OpenWebUI-User-Id` when `trust-user-headers=true`.

**Mode B — `authenticated` (the enterprise path).** Quarkus OIDC validates the bearer token before JAX-RS. The filter reads `SecurityIdentity.getPrincipal().getName()` as the userId and ignores both `api-key` and the user headers. Note that Open WebUI cannot currently mint per-user OIDC tokens for upstream connections, so mode B suits SDK/LangChain/LiteLLM clients rather than Open WebUI.

### Identity resolution order

```
1. Mode B → SecurityIdentity principal
2. Mode A + trust-user-headers → X-OpenWebUI-User-Id
3. allow-anonymous=true        → default-user
4. otherwise                   → 401 invalid_api_key
```

### Explicit properties

- **No role-gated facade is ever injected** (C3). The adapter reaches the stores and `IConversationService` directly, so it cannot be used to escalate into admin operations. This is the reason `UtilityAgentProvisioner` was cut (C8) — it needed exactly the writes this rule forbids.
- **The adapter performs no config writes.** It creates conversations and mappings only.
- **Header trust is a deliberate, documented delegation**, gated behind a flag and behind key possession. Turning `trust-user-headers=false` collapses all mode-A callers onto `default-user`, which is why `allow-anonymous` must then be explicitly set.
- **`enabled=false` by default**, and startup fails on the unauthenticated-and-keyless combination.
- The pipeline's own guarantees — GDPR Art. 18 restriction, audit ledger, quota, rate limiting — are untouched because every message goes through `IConversationService`.

**Threat note to include in the docs:** the shared `api-key` is a bearer credential for the whole surface. Rotate it as you would any shared secret, and prefer mode B where per-user tokens are available.

---

## 10. Error contract

Body shape for every non-2xx:

```json
{"error":{"message":"…","type":"invalid_request_error","param":null,"code":"model_not_found"}}
```

| Condition | Status | `type` / `code` |
|---|---|---|
| Missing/invalid API key | 401 | `invalid_request_error` / `invalid_api_key` |
| Unknown model | 404 | `invalid_request_error` / `model_not_found` |
| Ambiguous model name | 400 | `invalid_request_error` / `ambiguous_model` (message lists candidates) |
| No `user` message in `messages[]` | 400 | `invalid_request_error` / `no_user_message` |
| `AgentNotReadyException` | 503 | `server_error` / `agent_not_ready` |
| Conversation busy (`SKIPPED_NOT_ACTIVE`) | 429 | `rate_limit_exceeded` |
| Concurrency semaphore exhausted | 429 | `rate_limit_exceeded` |
| Timeout waiting for the turn | 504 | `server_error` / `timeout` |
| Unexpected exception | 500 | `server_error` |
| Unsupported `/v1/*` endpoint | 404 | `invalid_request_error` / `unknown_endpoint` |

**Asymmetry to implement deliberately:** once SSE headers are flushed the status is fixed at 200, so stream-path failures are emitted as a content delta plus `finish_reason:"stop"` plus `[DONE]`. Prefix such text with `⚠️` so it is visibly distinct from agent output. Document this — it is a protocol constraint, not a shortcut.

HITL states are **not** errors (C6): they return 200 with assistant text ([§4.4](#44-agent-pauses-for-human-approval)).

---

## 11. Open WebUI setup guide

Ships as `docs/open-webui-integration.md`.

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
      # REQUIRED — without this, X-OpenWebUI-Chat-Id and -User-Id are not sent,
      # so all of a user's chats collapse into one EDDI conversation.
      ENABLE_FORWARD_USER_INFO_HEADERS: "true"
```

### Must configure

| Setting | Value | Why |
|---|---|---|
| `ENABLE_FORWARD_USER_INFO_HEADERS` | `true` | Load-bearing. Supplies `X-OpenWebUI-Chat-Id` (per-chat isolation) and `X-OpenWebUI-User-Id` (per-user memory). |
| Title / Tag generation model | a `…:stateless` model, **or** a separate connection | Otherwise utility prompts are injected into the user's real conversation. A separate connection (Ollama, etc.) gives better titles; `:stateless` guarantees no corruption if you only have EDDI. |
| System Prompt (per model) | *leave empty* | The agent owns its prompt. A non-empty one arrives as `openai_system_message` context and is ignored unless the agent references it. |
| Tools / Functions (per model) | *assign none* | EDDI executes tools internally; the adapter never returns `tool_calls` (see below). |

### Optional Inlet Filter (bandwidth only)

With `X-OpenWebUI-Chat-Id` doing the session work, this Filter is **purely a bandwidth optimisation** — everything works without it. (Contrast the prior draft, where it was load-bearing.)

```python
class Filter:
    class Valves(BaseModel):
        EDDI_MODEL_SUFFIX_LEN: int = 7   # "-a3f9c1"

    def inlet(self, body: dict, __metadata__: dict = {}) -> dict:
        messages = body.get("messages", [])
        system = [m for m in messages if m["role"] == "system"]
        user   = [m for m in messages if m["role"] == "user"]
        body["messages"] = system + ([user[-1]] if user else [])
        return body
```

Safe here only because the adapter has no message-count heuristic (C7). Under the prior plan this Filter would have destroyed the conversation on every turn.

### Behaviour to expect

| Behaviour | Effect |
|---|---|
| **Regenerate** | Sends the message to EDDI **again** — a real new turn. Not idempotent: behavior rules advance, tools re-run. This is inherent to a stateful backend. |
| **Edit-and-resend** | Same — a new turn, not a rewrite of history. |
| **Deleting a chat in Open WebUI** | Does **not** end the EDDI conversation. It is abandoned and eventually reaped by normal conversation lifecycle policy. |
| **Documents dropped into chat** | Handled entirely by Open WebUI's own RAG (upload → chunk → embed → inject into system message). Arrives as `openai_system_message`. For production RAG use EDDI's own pipeline. |
| **Images pasted into chat** | Fully supported end-to-end via `attachment_N` → `AttachmentForwarder` → `ImageContent`, with vision-capability gating. No configuration needed. |
| **Token counts** | Not displayed — `usage` is omitted in v1 (C9). |
| **Model parameters** (temperature etc.) | Accepted and ignored; the agent's `langchain.json` governs. |

### Tool calling

EDDI executes tools **inside** the lifecycle pipeline. Returning `tool_calls` in the OpenAI response would make Open WebUI attempt to execute them locally, where EDDI's HTTP/MCP/memory tools do not exist — double-execution and guaranteed failure. So the response contains only final text, exactly as ChatGPT presents its internal tools. Tool activity remains fully visible in EDDI's audit ledger and `toolTrace`.

---

## 12. Non-goals, known gaps, v2

**Non-goals (v1):** `/v1/embeddings` (model-selection ambiguity, little value here); `tool_calls` passthrough (see above); Open WebUI Pipelines; function-calling from the client; multi-choice `n>1`; `logprobs`.

**Known gaps, stated honestly in the docs:**

1. `usage` / token counts omitted (C9).
2. Non-text output items — quick replies, `inputField` — are dropped; only text reaches OpenAI clients. Rule-based agents that depend on quick replies degrade to plain text.
3. Regenerate is a real new turn, not a replay.
4. Stream-path errors must return 200 (protocol constraint, [§10](#10-error-contract)).
5. `@Blocking` costs one worker thread per in-flight completion; bounded by `max-concurrent-requests`.
6. Mode B is impractical with current Open WebUI, which cannot mint per-user upstream OIDC tokens.

**v2 candidates:** source `usage` from `ObservableChatModel` / `ToolCostTracker`; optional tool-progress deltas (`🔧 Calling weather API…`) behind a flag; render quick replies as a numbered text list; expose HITL approve/reject through an Open WebUI Filter; `/v1/embeddings` once model selection is config-driven.

---

## 13. File manifest

### New — `src/main/java/ai/labs/eddi/integrations/openai/`

| File | Role |
|---|---|
| `RestOpenAiAdapter.java` | `@Path("/v1")` — models + chat completions ([§6.1](#61-restopenaiadapter)) |
| `OpenAiConversationBridge.java` | stateful↔stateless bridge ([§6.3](#63-openaiconversationbridge)) |
| `AgentModelResolver.java` | model id ⇄ agent, Caffeine ([§6.2](#62-agentmodelresolver)) |
| `OpenAiMessageMapper.java` | `messages[]` → `InputData` + attachments ([§6.4](#64-openaimessagemapper)) |
| `OpenAiSseWriter.java` | chunk framing ([§6.5](#65-openaissewriter)) |
| `OpenAiAuthFilter.java` | key/OIDC + identity resolution ([§9](#9-security-model)) |
| `OpenAiCompatConfig.java` | `@ConfigMapping` |
| `OpenAiStartupGuard.java` | fail-fast on unprotected exposure |
| `OpenAiExceptionMapper.java` | domain exceptions → OpenAI error bodies ([§10](#10-error-contract)) |

### New — `…/integrations/openai/model/`

`ChatCompletionRequest`, `ChatCompletionResponse`, `ChatCompletionChunk`, `ChatMessage`, `ContentPart`, `ImageUrl`, `Choice`, `ChunkChoice`, `Delta`, `ModelsResponse`, `ModelObject`, `OpenAiError`, `OpenAiErrorResponse` — all records ([§7](#7-wire-protocol-reference)).

### Modified

| File | Change |
|---|---|
| `engine/memory/ConversationOutputText.java` | **NEW** — extracted from `SlackHitlSupport.extractSlackResponseText` ([§5](#5-architecture)) |
| `integrations/slack/SlackHitlSupport.java` | `extractSlackResponseText` becomes a one-line delegate |
| `resources/application.properties` | `eddi.openai-compat.*` + the `/v1/*` permission entry ([§8](#8-configuration-reference)) |
| `docs/open-webui-integration.md` | **NEW** — [§11](#11-open-webui-setup-guide) |
| `docs/changelog.md` | entry in the same commit as the work (AGENTS.md §2 rule 8) |
| `AGENTS.md` §3 roadmap | add the completed row |

---

## 14. Implementation order

Each phase compiles and is independently committable. Run `.\mvnw.cmd compile` before every commit (AGENTS.md §2 rule 6).

| Phase | Deliverable | Commit |
|---|---|---|
| **1** | DTO records + `OpenAiCompatConfig` + config keys + permission entry. No behaviour. | `feat(openai): OpenAI-compatible protocol DTOs and configuration` |
| **2** | Extract `ConversationOutputText`; make `SlackHitlSupport` delegate. Slack tests must stay green. | `refactor(memory): extract channel-agnostic conversation output text extraction` |
| **3** | `AgentModelResolver` + `GET /v1/models` + `/v1/models/{id}` + `OpenAiExceptionMapper`. **Reachable milestone:** Open WebUI populates its model dropdown. | `feat(openai): expose deployed agents via GET /v1/models` |
| **4** | `OpenAiAuthFilter` + `OpenAiStartupGuard`. | `feat(openai): api-key and OIDC authentication for the /v1 surface` |
| **5** | `OpenAiMessageMapper` (text + multimodal). Heavily unit-tested; no wiring yet. | `feat(openai): map OpenAI messages and image content to EDDI InputData` |
| **6** | `OpenAiConversationBridge` sync path + `stream:false` endpoint, incl. HITL discrimination and stale-mapping self-heal. **Milestone:** non-streaming chat works end-to-end. | `feat(openai): stateful chat completions bridge` |
| **7** | `OpenAiSseWriter` + streaming path + token reconciliation. **Milestone:** Open WebUI streams. | `feat(openai): SSE streaming for chat completions` |
| **8** | `:stateless` variants. | `feat(openai): stateless model variants for utility requests` |
| **9** | Metrics, docs, changelog, roadmap. | `docs(openai): Open WebUI integration guide` |

Branch from `origin/main` per AGENTS.md §2 rule 3, with a `feat/…` name (not `claude/…`). **Ask before pushing.**

---

## 15. Test plan

### Unit tests (runnable locally — `.\mvnw.cmd test -Dtest=…`)

> Baseline first: `mvnw test` is red out of the box in this environment (~8 failures / 288 errors, all environmental). Compare against baseline before attributing a failure to this work.

| Class | Must cover |
|---|---|
| `AgentModelResolverTest` | id construction; all four resolution paths; `:stateless` stripping; **ambiguous name → `AmbiguousModelException`**; only `READY` agents listed; cache TTL. |
| `OpenAiMessageMapperTest` | plain-string content; array content; **`data:image/png;base64,…`**; **`data:image/png,…` — must not throw (C11)**; malformed `data:` with no comma; remote URL → concrete MIME, never `image/*`; multiple images → `attachment_0..n`; attachment cap; system message → context; **no user message → `NoUserMessageException`**; multiple system messages → last wins. |
| `OpenAiConversationBridgeTest` | new mapping created; existing reused; **different `X-OpenWebUI-Chat-Id` → different intent (the C2/C5 isolation guarantee)**; absent chat-id → `:default`; ENDED mapping self-heals; `createUserConversation` race → adopt winner; `SKIPPED_STILL_AWAITING` vs `SKIPPED_NOT_ACTIVE` (C6); `AWAITING_HUMAN` on complete → 200 + notice; `ConversationEndedException` → retry **exactly once**; stateless path always calls `endConversation` (incl. on exception). |
| `OpenAiSseWriterTest` | chunk shape byte-for-byte; stable `id`/`created`/`model` across chunks; `[DONE]` terminator; JSON escaping of quotes/newlines/unicode; flush per frame. |
| `OpenAiSseTranslatorTest` | tokens streamed → snapshot text **not** duplicated; no tokens → snapshot text emitted once; `onError` → `⚠️` delta + finish + `[DONE]`; task/cascade events suppressed. |
| `OpenAiAuthFilterTest` | valid/invalid/missing key; constant-time compare; identity precedence order; `allow-anonymous=false` → 401; `trust-user-headers=false` ignores the header. |
| `OpenAiStartupGuardTest` | the fail-fast combination throws; each mitigating setting individually permits startup. |

**Mutation-check the behavioural tests:** revert the fix, confirm the test fails. Error-path tests in this codebase have a history of passing for the wrong reason. Use **hex** ids (≥18 chars) in fixtures — non-hex ids yield `getId() == null` — and avoid `anyString()` in `verify(never())` assertions, which silently skips `null` and makes the assertion vacuous.

### CI-only

`RestOpenAiAdapterTest` (`@QuarkusTest`) binds an HTTP socket and **cannot run locally** in this environment. Write it, rely on CI. Cover: `GET /v1/models` shape; `stream:false` → `application/json`; **`stream:true` → `text/event-stream`** ← the C1 regression guard; unknown model → 404 with an OpenAI error body; unknown `/v1/*` → 404 JSON; unknown request fields (`stream_options`, `tools`, `metadata`) do not 400.

### Manual verification

```bash
curl -H "Authorization: Bearer sk-eddi-change-me" http://localhost:7070/v1/models
```

```bash
curl -X POST http://localhost:7070/v1/chat/completions -H "Authorization: Bearer sk-eddi-change-me" -H "Content-Type: application/json" -H "X-OpenWebUI-Chat-Id: chat-1" -H "X-OpenWebUI-User-Id: alice" -d '{"model":"my-agent-a3f9c1","messages":[{"role":"user","content":"Hello"}]}'
```

**The critical manual check — the C1 regression.** The Python SDK sends `Accept: application/json` even when `stream=True`; if this prints tokens progressively, body-based dispatch is working:

```bash
python -c "
from openai import OpenAI
c = OpenAI(base_url='http://localhost:7070/v1', api_key='sk-eddi-change-me')
print([m.id for m in c.models.list().data])
for chunk in c.chat.completions.create(model='my-agent-a3f9c1', messages=[{'role':'user','content':'Hi'}], stream=True):
    print(chunk.choices[0].delta.content or '', end='', flush=True)
"
```

**The C2/C5 isolation check** — send two messages with different `X-OpenWebUI-Chat-Id` values and confirm two distinct `X-EDDI-Conversation-Id` responses and two rows in the user-conversation store.

---

## 16. Pillar validation

Honest assessment against [`docs/project-philosophy.md`](../docs/project-philosophy.md). The prior draft marked all nine ✅ without examining `UtilityAgentProvisioner`.

| Pillar | Verdict | Reasoning |
|---|---|---|
| 1. Config Is Logic | ✅ | Adapter is protocol translation only. It creates **no** agent configuration — the decisive reason `UtilityAgentProvisioner` was cut (C8). Client-supplied system prompts and model parameters are exposed as context, never applied over the agent's own config. |
| 2. Deterministic Governance | ✅ | Every message traverses the full pipeline via `IConversationService`; rate limiting, HITL, cost tracking and behavior rules are unmodified. |
| 3. Engine, Not Application | ✅ | A new entry point alongside Slack. No business logic; delegates throughout. |
| 4. Security as Architecture | ✅ *by design, ⚠️ by dependency* | Default-off, fail-fast startup guard, constant-time key compare, no role-gated facades (C3), explicit `/v1` permission entry (C4). **The residual risk is named, not hidden:** mode A trusts `X-OpenWebUI-User-Id` behind a shared key, so a leaked key permits impersonation. Flagged, flag-gated, documented. |
| 5. Enterprise Concurrency | ✅ | Delegates to `ConversationCoordinator`; no new concurrency primitives. `@Blocking` thread cost is bounded by an explicit semaphore. |
| 6. Transparent Observability | ✅ | Pipeline audit trail intact; `channelIntent` context tags the origin; four Micrometer meters; `X-EDDI-Conversation-Id` correlates OpenAI requests with EDDI conversations. |
| 7. Progressive Disclosure | ✅ | Deploy an agent → it appears in Open WebUI. Every knob has a working default; only `enabled` and `api-key` are mandatory. |
| 8. Persistent Memory | ✅ | The entire point of stateful mode. Per-chat isolation (C2) is what makes memory *correct* rather than merely present. |
| 9. Agent Portability | ✅ | Agents are adapter-unaware. `openai_system_message` is an optional context key, not a requirement. |
