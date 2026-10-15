# REST API Reference — Conversations

This page is the curated reference for the endpoints a client uses to **hold a
conversation** with a deployed agent: start it, send messages (blocking or
streamed), read it back, undo and redo, handle human-in-the-loop pauses, and end
it. Every request and response below was captured from a running EDDI build.

The complete, generated API description — every store, every admin endpoint —
is served by each instance at **`/openapi`** and is browsable at
**`/q/swagger-ui`**. It always matches the build you are talking to, so use it
for anything this page does not cover.

**Contents:** [Conventions](#conventions) · [Endpoint overview](#endpoint-overview) ·
[Starting](#starting-a-conversation) · [Sending a message](#sending-a-message) ·
[Streaming (SSE)](#streaming-a-turn-server-sent-events) ·
[Reading](#reading-a-conversation) · [Undo, redo, rerun](#undo-redo-and-rerun) ·
[Ending and cancelling](#ending-and-cancelling) ·
[Human-in-the-loop](#human-in-the-loop-approvals) ·
[Admin state reset](#resetting-a-stuck-conversation) ·
[Conversation store](#the-conversation-store) · [Errors](#errors)

## Conventions

- **Base URL.** The examples use `EDDI=http://localhost:7070`.
- **Authentication.** With OIDC enabled (`QUARKUS_OIDC_TENANT_ENABLED=true`),
  send `Authorization: Bearer <access token>`. The conversation endpoints accept
  the roles `eddi-admin`, `eddi-editor` and `eddi-user`; the approval endpoints
  also accept `eddi-approver`. There is no role hierarchy — a role is accepted
  only where it is listed. A non-admin can only touch conversations they own (the
  `userId` the conversation was started for). See [Security](security.md).
- **Two ids.** A conversation is *started* on the agent
  (`/agents/{agentId}/start`); every later call addresses the **conversation**
  (`/agents/{conversationId}/…`). The same `/agents/` prefix is used for both.
- **Ids come back in `Location`.** A create answers `201` with an empty body and
  an `eddi://` URI in the `Location` header; the id is its last path segment.
- **Conversation states:** `READY`, `IN_PROGRESS`, `AWAITING_HUMAN`, `ENDED`,
  `ERROR`, `EXECUTION_INTERRUPTED`.

## Endpoint overview

| Method & path | Purpose | Success |
| --- | --- | --- |
| `POST /agents/{agentId}/start` | Start a conversation (optionally with a context map) | `201` + `Location` |
| `POST /agents/{conversationId}` | Send a message (text or JSON), wait for the reply | `200` snapshot |
| `POST /agents/{conversationId}/stream` | Send a message, receive the turn as Server-Sent Events | `200` `text/event-stream` |
| `GET /agents/{conversationId}` | Read the conversation | `200` snapshot |
| `GET /agents/{conversationId}/status` | Read the conversation state | `200` `"READY"` |
| `GET /agents/{conversationId}/log` | Conversation history as chat messages | `200` |
| `GET` / `POST /agents/{conversationId}/undo` | Is undo available? / undo the last turn | `200` |
| `GET` / `POST /agents/{conversationId}/redo` | Is redo available? / redo | `200` |
| `POST /agents/{conversationId}/rerun` | Re-execute the last turn | `200` snapshot |
| `POST /agents/{conversationId}/endConversation` | End the conversation | `200` |
| `POST /agents/{conversationId}/cancel` | Cancel an executing or paused turn | `200` |
| `POST /agents/{conversationId}/resume` | Submit a human decision for a paused turn | `200` |
| `GET /agents/{conversationId}/approval-status` | Pause details of a conversation | `200` |
| `GET /agents/pending-approvals` | Conversations awaiting a human decision | `200` list |
| `PATCH /agents/{conversationId}/state` | Admin: reset a stuck conversation to `READY` | `200` |
| `GET /conversationstore/conversations` | List conversations (paged, filterable) | `200` list |
| `GET /conversationstore/conversations/simple/{conversationId}` | Read a conversation (same shape as `GET /agents/{id}`) | `200` |
| `GET /conversationstore/conversations/{conversationId}` | Read the raw stored conversation memory | `200` |
| `DELETE /conversationstore/conversations/{conversationId}` | Delete a conversation | `204` |
| `GET /conversationstore/conversations/active/{agentId}` | Open conversations of an agent (admin, editor) | `200` list |
| `POST /conversationstore/conversations/end` | End a list of conversations (admin, editor) | `200` |

The agent must be **deployed** to the environment you start in — see
[Deployment management](deployment-management-of-agents.md).

## Starting a conversation

```bash
curl -i -X POST "$EDDI/agents/<AGENT_ID>/start?environment=production&userId=demo-user"
```

```text
HTTP/1.1 201 Created
Location: eddi://ai.labs.conversation/conversationstore/conversations/6abf78ad892bd7bd6ee7b901
```

| Query parameter | Default | Meaning |
| --- | --- | --- |
| `environment` | `production` | The deployment environment: `production` or `test` |
| `userId` | — | The user the conversation belongs to. With OIDC on, the authenticated identity is used for ownership |

To start **with a context map**, send it as the JSON body — the body is the map
itself, keyed by context name; there is no `input` field on this call:

```bash
curl -i -X POST "$EDDI/agents/<AGENT_ID>/start?environment=production&userId=demo-user" \
  -H "Content-Type: application/json" \
  -d '{ "userName": { "type": "string", "value": "Ada" } }'
```

The start turn runs the agent's workflows once, with the action
`CONVERSATION_START`; whatever it outputs (a greeting, say) is in the
conversation when you first read it. A context value is visible to templates
only during the turn it was sent with, so a value needed later must be sent
again or copied into a property — see [Passing context](passing-context-information.md).

`404 Agent is not deployed or not ready` means no version of the agent is
deployed and `READY` in that environment.

## Sending a message

The same path takes plain text or JSON.

```bash
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>" \
  -H "Content-Type: text/plain" --data 'hello'
```

```bash
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>" \
  -H "Content-Type: application/json" \
  -d '{ "input": "hello",
        "context": { "userName": { "type": "string", "value": "Ada" } } }'
```

The JSON body (`InputData`) has two fields: `input` (the message) and `context`
(a map of name → `{ "type": …, "value": … }`, where `type` is `string`,
`expressions`, `object` or `array`). Context keys the engine reserves for itself
are removed from client-supplied context. Input longer than
`eddi.conversations.max-input-chars` (default 200,000 characters) is refused
with `413`.

**Response** (`200`, abridged — `conversationSteps` is cut short):

```json
{
  "conversationId": "6abf78ad892bd7bd6ee7b901",
  "agentId": "6abf74a9892bd7bd6ee7b8f5",
  "agentVersion": 1,
  "userId": "demo-user",
  "environment": "production",
  "conversationState": "READY",
  "undoAvailable": true,
  "redoAvailable": false,
  "conversationOutputs": [
    {
      "input": "hello",
      "actions": ["welcome_action"],
      "output": [ { "type": "text", "text": "Hello Ada! How can I help you today?", "delay": 0 } ]
    }
  ],
  "conversationProperties": {},
  "conversationSteps": [
    { "conversationStep": [
        { "key": "input:initial", "value": "hello", "timestamp": 1790933170850, "originWorkflowId": "6abf74a8892bd7bd6ee7b8f4" },
        { "key": "actions", "value": ["welcome_action"], "timestamp": 1790933170853, "originWorkflowId": "6abf74a8892bd7bd6ee7b8f4" }
    ] }
  ]
}
```

`conversationOutputs[].output` is what to show the user; `quickReplies`, when
the turn produced any, sits next to it. Query parameters, shared by this call,
`/stream`, `/rerun` and `GET /agents/{conversationId}`:

| Parameter | Default | Effect |
| --- | --- | --- |
| `returnDetailed` | `false` | `true` returns every memory entry of the step. By default only the client-facing keys are returned: `input:initial`, `actions*`, `output*`, `quickReplies*` |
| `returnCurrentStepOnly` | `true` | `false` returns all turns since the start |
| `returningFields` | — | Repeatable; restricts the snapshot to the named top-level fields |

Turns of one conversation are processed strictly in order. A message sent to a
conversation that is `AWAITING_HUMAN` is refused with `409`, to an `ENDED` one
with `410 Conversation has ended`.

## Streaming a turn (Server-Sent Events)

`POST /agents/{conversationId}/stream` takes the same JSON body as a JSON
message and answers with `Content-Type: text/event-stream`. The turn runs exactly
as it would on the blocking endpoint; the stream reports its progress and ends
with a `done` event carrying the turn's state and outputs.

```bash
curl -s -N -X POST "$EDDI/agents/<CONVERSATION_ID>/stream" \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{ "input": "tell me something" }'
```

A captured turn of an agent whose workflow is parser → rules → LLM → output →
templating (the model's text is shortened):

```text
event:task_start
data: {"taskId":"eddi://ai.labs.parser","taskType":"expressions","index":0}

event:task_complete
data: {"taskId":"eddi://ai.labs.parser","taskType":"expressions","durationMs":7}

event:task_start
data: {"taskId":"eddi://ai.labs.behavior","taskType":"behavior_rules","index":1}

event:task_complete
data: {"taskId":"eddi://ai.labs.behavior","taskType":"behavior_rules","durationMs":0,"actions":["send_to_ai"]}

event:task_start
data: {"taskId":"eddi://ai.labs.llm","taskType":"langchain","index":2}

event:token
data: Here is something

event:token
data:  interesting.

event:task_complete
data: {"taskId":"eddi://ai.labs.llm","taskType":"langchain","durationMs":466,"actions":["send_to_ai"]}

event:task_start
data: {"taskId":"eddi://ai.labs.output","taskType":"output","index":3}

event:task_complete
data: {"taskId":"eddi://ai.labs.output","taskType":"output","durationMs":1,"actions":["send_to_ai"]}

event:task_start
data: {"taskId":"eddi://ai.labs.templating","taskType":"output","index":4}

event:task_complete
data: {"taskId":"eddi://ai.labs.templating","taskType":"output","durationMs":0,"actions":["send_to_ai"]}

event:done
data: {"conversationState":"READY","conversationOutputs":[{"input":"tell me something","actions":["send_to_ai"],"output":[{"type":"text","text":"Here is something interesting.","delay":0}]}]}
```

### Event contract

| Event | `data` | When |
| --- | --- | --- |
| `task_start` | `{"taskId","taskType","index"}` | A lifecycle task begins. `taskId` is the task's registered id (`eddi://ai.labs.behavior` for a step configured as `ai.labs.rules`, `eddi://ai.labs.httpcalls` for `ai.labs.apicalls`); `index` is its position in the turn |
| `task_complete` | `{"taskId","taskType","durationMs"}` plus, when present, `actions` (the actions in memory after the task), `toolTrace` (the LLM task's tool calls, arguments redacted) and `confidence` | A task finished |
| `task_failed` | `{"taskId","taskType","durationMs","errorType","error"}` | A task threw. By default that stops the turn: the conversation goes to `ERROR` and the stream still ends with `done`, whose snapshot has `"conversationState":"ERROR"` and a `taskErrors` entry. An agent whose [memory policy](memory-policy.md) continues on failure runs the next task instead |
| `token` | **Raw text**, not JSON | A chunk of model output, as the model streams it. Concatenate the chunks |
| `tool_call` | `{"tool":"<name>"}` | Sent right before a tool runs — the name only. There is no "tool finished" event |
| `cascade_step_start` | `{"stepIndex","modelType","modelName","totalSteps"}` | A [model cascade](model-cascade.md) step starts |
| `cascade_escalation` | `{"fromStep","toStep","confidence","threshold","reason","durationMs"}` | The cascade escalates to the next model |
| `done` | A reduced snapshot: `conversationState`, `conversationOutputs`, and `hitlPausedAt` when the turn paused | **Terminal.** The turn completed. `GET /agents/{conversationId}` returns the full snapshot (properties, steps, undo and redo flags) |
| `error` | `{"message", …}` | **Terminal.** The turn could not run or failed |

Rules for clients:

- **Exactly one terminal event** — `done` or `error` — ends every stream. Treat
  the `done` snapshot as authoritative for what to display; tokens are a preview.
  A turn in which a task failed still ends with `done`; check its
  `conversationState` (`ERROR`) and `taskErrors`. Captured with an LLM endpoint
  that was down:

  ```text
  event:task_failed
  data: {"taskId":"eddi://ai.labs.llm","taskType":"langchain","durationMs":1196,"errorType":"transport","error":"Streaming chat failed: java.net.ConnectException"}

  event:done
  data: {"conversationState":"ERROR","conversationOutputs":[{"input":"hello","actions":["send_to_ai"],"taskErrors":[{"type":"errorDigest","taskId":"ai.labs.llm","taskType":"langchain","text":"Task 'eddi://ai.labs.llm' failed: Streaming chat failed:"}]}]}
  ```

  An `ERROR` conversation accepts the next message; an admin can also reset it
  (see [below](#resetting-a-stuck-conversation)).
- **Strip one space after `data:`.** Every `data:` line carries one delimiter
  space; a token's own leading space follows it (`data:  interesting.` is the
  token `" interesting."`). A payload containing line breaks arrives as several
  `data:` lines, each padded the same way — join them with `\n`.
- **`token` only appears when the model streams.** A turn that runs no LLM task,
  or whose output comes from an output set, has no `token` events; the reply is in
  `done`.
- **An `error` before anything ran carries a `code`.** When the turn is refused
  up front — the cases the blocking endpoint answers with a status — the event is
  `{"message":"…","code":"…"}` with `code` one of `awaiting_approval`,
  `conversation_not_found`, `input_too_large`, `conversation_ended`,
  `agent_not_ready`, `agent_mismatch`, `quota_exceeded`,
  `quota_accounting_unavailable`, `processing_restricted` or
  `restriction_status_unavailable`. Branch on `code`, show `message`. Captured:

  ```text
  event:error
  data: {"message":"Conversation has ended","code":"conversation_ended"}
  ```

  Any other failure is `{"message":"Internal server error","correlationId":"…"}`;
  the correlation id is in the server log next to the real cause.
- **Ownership and input size are checked before the stream opens,** so those
  failures are plain `403` / `413` responses, not SSE events.
- **Disconnecting cancels the turn** by default
  (`eddi.streaming.cancel-on-client-disconnect=true`). Set it to `false` to let
  the turn finish and persist when the client goes away; the reply is then there
  when the conversation is read again.

The Chat UI (`ui/chat`) and the Manager are reference consumers of this
contract.

## Reading a conversation

```bash
curl -s "$EDDI/agents/<CONVERSATION_ID>?returnCurrentStepOnly=false"
curl -s "$EDDI/agents/<CONVERSATION_ID>/status"           # → "READY"
```

`GET /agents/{conversationId}` returns the same snapshot shape as a message
reply, with the same query parameters. With `returnCurrentStepOnly=false` the
first `conversationOutputs` entry is the start turn (`{"actions":["CONVERSATION_START"]}`).

`GET /agents/{conversationId}/log` returns the history as chat messages — the
view an LLM gets:

```bash
curl -s "$EDDI/agents/<CONVERSATION_ID>/log?logSize=4"
```

```json
[
  { "role": "user", "content": [ { "type": "text", "value": "hello" } ] },
  { "role": "assistant", "content": [ { "type": "text", "value": "Hello ! How can I help you today?" } ] }
]
```

| Parameter | Default | Effect |
| --- | --- | --- |
| `outputType` | `json` | `text` returns one line per message instead |
| `logSize` | `-1` | Number of entries; `-1` for all |

## Undo, redo and rerun

```bash
curl -s "$EDDI/agents/<CONVERSATION_ID>/undo"              # → true   (text/plain)
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>/undo"      # → 200
curl -s "$EDDI/agents/<CONVERSATION_ID>/redo"              # → true
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>/redo"      # → 200; 409 when there is nothing to redo
```

Undo removes the last turn from the conversation and keeps it for redo; sending a
new message discards the redo stack. The snapshot's `undoAvailable` and
`redoAvailable` flags say the same as the two `GET`s.

`POST /agents/{conversationId}/rerun` executes the last turn again with its
original input (for example after fixing the agent's configuration) and returns
the new snapshot. It accepts the snapshot query parameters plus `language`.

## Ending and cancelling

```bash
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>/endConversation"    # → 200
curl -s "$EDDI/agents/<CONVERSATION_ID>/status"                     # → "ENDED"
```

An agent can also end the conversation itself with the action
`CONVERSATION_END`. Afterwards every message is refused with
`410 Conversation has ended`.

`POST /agents/{conversationId}/cancel` stops a turn that is executing or paused
for approval. On an idle conversation it answers:

```text
HTTP/1.1 409 Conflict
Nothing to cancel: conversation is neither awaiting approval nor executing. Use endConversation to close it.
```

## Human-in-the-loop approvals

When an agent's [HITL gate](hitl.md) pauses a turn, the conversation goes to
`AWAITING_HUMAN` until someone decides.

```bash
curl -s "$EDDI/agents/pending-approvals?limit=50"
curl -s "$EDDI/agents/<CONVERSATION_ID>/approval-status"
curl -s -X POST "$EDDI/agents/<CONVERSATION_ID>/resume" \
  -H "Content-Type: application/json" \
  -d '{ "verdict": "APPROVED", "pauseId": "<pauseId from approval-status>", "note": "checked" }'
```

`approval-status` of a conversation that is not paused:

```json
{"conversationId":"6abf78ad892bd7bd6ee7b901","state":"READY","pausedAt":"","pauseId":"","pauseReason":"","timeoutPolicy":"","approvalTimeout":""}
```

The resume body is a decision: `verdict` (`APPROVED` or `REJECTED`, required),
optional `note`, optional `pauseId` (send it — a decision for a pause that has
since been replaced is refused with `409` instead of approving the wrong thing),
and `toolDecisions` for per-tool-call gates. `?detail=full` on
`approval-status` adds the full memory snapshot. `pending-approvals` takes
`limit` (default 200, at most 1000).

| Response | Meaning |
| --- | --- |
| `400 Request body must include a 'verdict' field (APPROVED or REJECTED)` | No verdict in the body |
| `409 Conversation is not in a resumable state (current state: READY) — …` | Nothing is paused (already resumed, cancelled, timed out, or the agent is not deployed) |
| `409` naming `pauseId no longer current` | The pause changed after the decision was made |

## Resetting a stuck conversation

`PATCH /agents/{conversationId}/state?state=READY` (admin only) moves a
conversation in `ERROR` or `EXECUTION_INTERRUPTED` back to `READY`. `READY` is
the only accepted target:

| Situation | Response |
| --- | --- |
| Conversation in `ERROR` or `EXECUTION_INTERRUPTED` | `200 {"message":"State reset from ERROR to READY"}` |
| Conversation already `READY` | `200 {"message":"Conversation already in READY state"}` |
| Any other `state` value | `400 {"error":"Only READY is supported as target state"}` |
| Conversation `IN_PROGRESS` | `409 {"error":"Cannot reset while conversation is IN_PROGRESS"}` |
| Conversation `ENDED` | `409 {"error":"Cannot reset an ENDED conversation"}` |

## The conversation store

`/conversationstore/conversations` is the history view: listing, reading the
stored document, deleting.

```bash
curl -s "$EDDI/conversationstore/conversations?agentId=<AGENT_ID>&limit=20&index=0"
```

```json
[
  {
    "resource": "eddi://ai.labs.conversation/conversationstore/conversations/6abf78ad892bd7bd6ee7b901",
    "createdOn": 1790933165895,
    "lastModifiedOn": 1790933188985,
    "deleted": false,
    "agentName": "",
    "userId": "demo-user",
    "agentResource": "eddi://ai.labs.agent/agentstore/agents/6abf74a9892bd7bd6ee7b8f5?version=1",
    "viewState": "UNSEEN",
    "conversationStepSize": 5,
    "environment": "production",
    "conversationState": "READY"
  }
]
```

| Parameter | Meaning |
| --- | --- |
| `index`, `limit` | Page number and page size (default `0`, `20`). Page until a page comes back empty. `index × limit` above 10,000 is refused with `400` — narrow the listing with a filter instead |
| `agentId`, `agentVersion` | Only this agent (version) |
| `conversationState` | Only conversations in this state |
| `conversationId` | One conversation |
| `viewState` | `UNSEEN`, `SEEN`, … |
| `filter` | Free-text filter |

Results are newest first. A non-admin sees only their own conversations.

| Call | Notes |
| --- | --- |
| `GET …/simple/{conversationId}` | Same snapshot as `GET /agents/{conversationId}`, with the same query parameters |
| `GET …/{conversationId}` | The stored conversation memory document, every step and key |
| `DELETE …/{conversationId}` | `204`. `?deletePermanently=true` removes the document instead of marking it deleted |
| `DELETE …/?deleteOlderThanDays=N` | Admin only: permanently delete every **ended** conversation older than `N` days (`N ≥ 1`) |
| `GET …/active/{agentId}` | Admin or editor: open (not `ENDED`) conversations of an agent, `[{"conversationId","agentId","agentVersion","conversationState","lastInteraction"}]` |
| `POST …/end` | Admin or editor: end the listed conversations. The body is a JSON array of objects of which only `conversationId` is read, so the `active` list can be sent back as it is; an array of plain id strings is refused with `400`. `?endReason=agent-version-retired` is the one accepted reason. The response lists the `ended`, `skipped` and `failed` ids |

Every per-conversation call checks ownership, so a caller can neither read nor
delete a conversation they do not own.

## Errors

| Status | Body (examples) | Cause |
| --- | --- | --- |
| `400` | `Request body must include a 'verdict' field …` | Malformed request |
| `401` | — | OIDC on and no or invalid token |
| `403` | — | Wrong role, not the conversation's owner, or processing restricted for this user ([GDPR](gdpr-compliance.md)) |
| `404` | `Agent is not deployed or not ready` | No `READY` deployment of the agent in that environment |
| `404` | `No conversation found! (conversationId=…)`; `Conversation not found.` from cancel, resume and approval-status | Unknown conversation id |
| `409` | `Nothing to cancel: …` | The conversation's state does not allow the call — awaiting approval, nothing to redo, nothing to cancel |
| `410` | `Conversation has ended` | Message to an ended conversation |
| `413` | — | Input longer than `eddi.conversations.max-input-chars` |
| `429` | — | A [tenant quota](tenant-quotas.md) is exhausted |
| `503` | — | Quota accounting or the GDPR restriction status could not be read; retry |

For symptoms and fixes beyond the status code, see
[Troubleshooting](troubleshooting.md).

## Related

- [Conversations](conversations.md) — how conversation state, memory and windowing work
- [Human-in-the-Loop](hitl.md) — pause configuration, timeouts, per-tool-call gates
- [Group Conversations](group-conversations.md) — the `/groups/{groupId}/conversations` API
- [OpenAI-compatible API](open-webui-integration.md) — `/v1/chat/completions` on top of the same engine
- [MCP Server](mcp-server.md) — the same operations as MCP tools
