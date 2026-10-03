## ✨ feat(a2a): A2A 1.0 conformance — honest task states, an in-flight bound, restart-safe tasks (2026-10-03)

**Repo:** EDDI (`feat/a2a-spec-conformance`)

### What changed and why

The A2A surface spoke a pre-0.2 dialect no current SDK can parse — a bare-enum
`status`, `type` instead of `kind`, and only `tasks/send` — so the official
`a2a-sdk` client could neither resolve an EDDI Agent Card nor send to it. It
also reported every turn as `completed`: a failing LLM, a HITL pause and a turn
dropped behind a pause all came back `completed`, the last one with the
*previous* turn's answer as its result. There was no bound on concurrent tasks,
and tasks lived only in a node-local 1,000-entry cache.

- **Pinned to A2A 1.0** (specification release v1.0.1). One endpoint serves
  three dialects, chosen by method name and answered in kind:
  `SendMessage` / `SendStreamingMessage` / `GetTask` / `CancelTask` (1.0),
  `message/send` / `message/stream` / `tasks/get` / `tasks/cancel` (0.3), and
  the deprecated `tasks/send`. Unimplemented spec methods get their spec error
  codes (`-32003`, `-32004`, `-32007`); an unsupported `A2A-Version` gets
  `-32009`. Rendering lives in the new `A2AWireFormat`; the task model is
  dialect-neutral.
- **Agent Card** at `/.well-known/agent-card.json` and
  `/a2a/agents/{id}/.well-known/agent-card.json` (the old paths stay), readable
  by 1.0 and 0.3 clients alike: `supportedInterfaces` for both versions,
  1.0-shaped `securitySchemes`, the 0.3 connection fields, the real EDDI
  version, `streaming: true`.
- **Task states come from the task's own turn**: ERROR → `failed`, a HITL
  pause → `input-required`, a turn dropped behind a pause → `input-required`,
  dropped while busy → `rejected`, cancelled → `canceled`, still running when a
  blocking send stops waiting → `working`. Each turn carries its task id in the
  input context and an output is returned only if it carries that id, so a
  stale previous answer can no longer be returned.
- **A task queued behind a sibling's turn is not cancelable** (`-32002`)
  until that turn settles: cancelling the conversation would have stopped the
  sibling's turn instead. The in-flight bound's lease timer is cancelled on a
  normal release rather than left queued for task timeout + 30 s.
- **`CancelTask` cancels the turn**, not the conversation (it used to end the
  whole context), and a terminal task answers `TaskNotCancelable`.
- **In-flight bound** `eddi.a2a.max-concurrent-requests` (default 64): the
  excess is refused with HTTP 503 + `Retry-After` before any conversation
  starts. Meters `eddi_a2a_requests_total`, `eddi_a2a_tasks_total`,
  `eddi_a2a_in_flight`, on the full-metrics dashboard.
- **Tasks behind `IA2ATaskStore`** (default `CachedA2ATaskStore`, the same
  node-local caches). Server-issued task ids are `<conversationId>_<random>`
  and server-issued context ids are the conversation id, so `GetTask` and
  context continuation re-derive from the conversation store after a restart
  or on another node — peer-scoped by conversation ownership.
- **Outbound client** (`A2AToolProviderManager`) reads the dialect off the
  peer's card — `SendMessage` + `A2A-Version: 1.0`, `message/send`, or
  `tasks/send` for a stock EDDI up to 6.5 — tries the well-known card paths,
  reads results in all three shapes, and reports a failed, rejected,
  input-required or still-working task to the model as such instead of as an
  answer. The configured URL stays the endpoint; a card cannot redirect it (when the configured URL is the card document itself, the card's endpoint must be on the card's origin, since the credential is sent there).

Verified live with the official `a2a-sdk` 1.2.1 Python client (1.0 transport
and its 0.3 compatibility transport) against a jar of this branch, and compared
with a stock `labsai/eddi:6.5.0`.

### Compatibility

`tasks/send` results are now the 0.3 task shape: `status` is an object
(`status.state`), parts carry `kind` next to `type`. EDDI's own client up to
6.5 reads only the artifacts and keeps working against this server; this
branch's client keeps working against a 6.5 server.

### Docs

[`a2a-protocol.md`](../a2a-protocol.md) (methods per dialect, state mapping,
task store, streaming, the bound, deprecations; the disabled endpoint answers
200 + a JSON-RPC error, which the page now says),
[`configuration-reference.md`](../configuration-reference.md),
[`metrics.md`](../metrics.md), [`security.md`](../security.md).

The Platform Operator holds no A2A endpoint and cannot change A2A settings, so
its prompt and allow-list are unchanged.

**Files:** [`A2AModels.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2AModels.java),
[`A2AWireFormat.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2AWireFormat.java),
[`A2ATaskHandler.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskHandler.java),
[`IA2ATaskStore.java`](../../src/main/java/ai/labs/eddi/engine/a2a/IA2ATaskStore.java),
[`CachedA2ATaskStore.java`](../../src/main/java/ai/labs/eddi/engine/a2a/CachedA2ATaskStore.java),
[`A2AInFlightLimiter.java`](../../src/main/java/ai/labs/eddi/engine/a2a/A2AInFlightLimiter.java),
[`RestA2AEndpoint.java`](../../src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java),
[`AgentCardService.java`](../../src/main/java/ai/labs/eddi/engine/a2a/AgentCardService.java),
[`A2AToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java)

### What's next

- A shared `IA2ATaskStore` for clustered deployments (owned by the NATS
  cluster work); peer-chosen ids (legacy task ids, made-up context ids) are
  the only ones that do not survive a restart today.
- `ListTasks`, `SubscribeToTask` and push notifications are answered with
  their spec errors, not implemented.
- The outbound client does not poll a task that came back `working`.

```decision-log
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
```
