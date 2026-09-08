# Platform Operator — three follow-ups (implementation plan)

**Audience:** a coding agent picking this up cold. Everything needed is in this document; you should
not need to reconstruct history from chat logs or PR threads.

**Status:** all three are unimplemented. They are independent — do them in any order, or in parallel
in separate worktrees.

| # | Task | Repo | Rough size |
|---|---|---|---|
| A | Operator can converse with other agents / groups (test-drive) | EDDI-Manager | ~half a day |
| B | Operator conversation survives navigation + restart; history tab | EDDI-Manager | ~1 day |
| C | A tool call whose body is invalid JSON fails at the API, not before | EDDI | ~2 hours |

---

## 0. Background you need first

### 0.1 What the Platform Operator is

An **EDDI agent that administers its own EDDI deployment**, provisioned by the Manager UI. The admin
chats with it at `/manage/operator` (full page) or through a header drawer on any page.

It is not special-cased anywhere in the engine. It is an ordinary agent created through
`POST /administration/agents/setup-api`, which takes an **OpenAPI spec** plus an `endpoints` filter
and generates one httpcall tool per allow-listed endpoint. The spec it is given is *this
deployment's own* OpenAPI document, so its tools are literally EDDI's REST API.

Consequences worth internalising before you change anything:

- **To give the operator a new capability, you add an endpoint to the allow-list.** You do not write
  a tool. The tool is generated from the spec.
- The allow-list lives in the **Manager**, not the backend: `src/lib/operator/tool-scopes.ts`.
- It is applied **at provisioning time only**. It is *not* a runtime security boundary — that file
  says so in its own comment, and you must not start treating it as one.
- Changing an operator's capabilities means **re-provisioning** (a fresh `setup-api` call creating a
  new agent), never an in-place edit. This is deliberate; see §0.4.

### 0.2 Repos, paths, branches

| | EDDI (backend, Java/Quarkus) | EDDI-Manager (frontend, React/TS) |
|---|---|---|
| Root | `C:\dev\git\EDDI` | `C:\dev\git\EDDI-Manager` |
| Remote | `github.com/labsai/EDDI` | `github.com/labsai/EDDI-Manager` |
| Base branch | `chore/remove-agent-father` while PR #672 is open, else `main` | `main` |

**Do not run Maven in `C:\dev\git\EDDI`** if a `quarkus:dev` instance is running there — it will fight
the build. Use a git worktree (there are several under `C:\dev\git\EDDI-*`).

Key files:

**EDDI**
- `src/main/java/ai/labs/eddi/engine/api/IRestAgentEngine.java` — runtime conversation REST
  (`/agents/…`: start, say, read, status)
- `src/main/java/ai/labs/eddi/engine/api/IRestGroupConversation.java` — group conversations
  (`/groups/{groupId}/conversations`)
- `src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java` — OpenAPI → httpcall tool
  generation, including the request-body template
- `src/main/java/ai/labs/eddi/modules/llm/impl/HttpCallToolsProvider.java` — builds the executor for
  each generated tool; `templateDataFor` merges the model's arguments over conversation memory
- `src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java` — actually performs the
  call; owns the result map the LLM receives
- `src/main/java/ai/labs/eddi/engine/hitl/tools/ToolApprovalPatterns.java` — approval pattern syntax
- `src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java` — pause/resume, the
  pending-approval placeholder message
- `planning/operator-write-scope-plan.md` — **binding constraints**, see §0.5

**EDDI-Manager**
- `src/lib/operator/tool-scopes.ts` — `READ_ENDPOINTS`, `WRITE_ENDPOINTS`, `buildToolApprovals`,
  `endpointsForScope`, `buildEndpointFilter`
- `src/lib/api/operator.ts` — `provisionOperator`, `verifyGateInstalled`, and the exempt-safety
  constants (`GATED_WRITE_PATTERNS`, `WRITE_EXEMPT_PREFIXES`)
- `src/lib/operator/system-prompt.ts` — the operator's system prompt, assembled from the granted
  endpoint set
- `src/hooks/use-operator-chat.ts` — the chat store (zustand), conversation id persistence, pause
  resolution
- `src/pages/operator.tsx` — the operator page
- `src/lib/api/conversations.ts` — `getConversationDescriptors`, `getSimpleConversationLog`,
  `extractOutputParts`
- `src/hooks/use-chat.ts` — the *other* chat surface; has snapshot→messages logic worth reusing
- `src/pages/conversations.tsx` — an existing list-of-conversations page to mirror

### 0.3 The approval gate (HITL) — the part you must not break

Every operator tool call is classified before it runs. `buildToolApprovals()` in `tool-scopes.ts`
installs exactly this on the provisioned agent:

```ts
{
  requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
  exempt: ["http.get:*"],
  timeoutPolicy: "WAIT_INDEFINITELY",
}
```

Pattern addresses are `http.<method>:<normalised path>` — e.g. `http.post:/agents/{agentId}/start`.
The path comes from `HttpCallToolsProvider.normalizeEndpointPath`. `*` compiles to `.*`
(`ToolApprovalPatterns.compile`), so patterns are globs, not regexes.

When a call matches `requireApproval` and not `exempt`, the turn **pauses**: state becomes
`AWAITING_HUMAN`, a `PendingToolCallBatch` is persisted, and the UI shows an approval card. A turn
may pause up to `maxPausesPerTurn` times (default 3).

**Reading this correctly: every non-GET the operator makes needs a human click.** That is the entire
safety model. Nothing in these three tasks may weaken it without the explicit decision recorded in
§A.3.

### 0.4 Gate verification, and why it will fight you

At activation the Manager reads the provisioned agent back and asserts the gate is really installed
(`verifyGateInstalled` in `src/lib/api/operator.ts`). It **rejects** any `exempt` entry that could
swallow a write. The relevant constants:

```ts
const GATED_WRITE_PATTERNS  = ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"];
const GATED_WRITE_PREFIXES  = ["http.post:", "http.put:", "http.patch:", "http.delete:"];
const WRITE_EXEMPT_PREFIXES = [...GATED_WRITE_PREFIXES, "http.*:"];
```

Any exempt pattern beginning with one of `WRITE_EXEMPT_PREFIXES` fails verification — **including a
narrow one** like `http.post:/agents/*/start`. The reasoning is written out in the file: the blanket
gate pattern already matches every call of that method, so prefix membership alone proves overlap
and no glob-intersection logic is needed.

This is load-bearing for task A. Read §A.3 before you touch it.

Activation also runs a **live write probe** afterwards (`src/lib/operator/write-canary.ts`) which
provokes one real gated write and checks it pauses. A proven failure tears the operator down.

### 0.5 Constraints that are already decided — do not relitigate

`planning/operator-write-scope-plan.md` §5 ("What I would NOT do") binds these. The ones that touch
this work:

- **Never grant `POST /agents/{id}/resume`.** The operator approving its own pauses is a complete
  escape from the gate. Also excluded: `PATCH /agents/{id}/state`, `/cancel`.
- Also permanently excluded: `PUT /variablestore/variables/…` (the operator's own config), all
  `/secretstore` writes, `/backup/import*`, `channelstore` writes, `/ragstore/.../ingest`,
  `usermemorystore` writes, `AgentTriggerStore` writes, `POST /administration/quotas`,
  `DELETE /administration/orphans`.
- **No "approve all" button.** `requireExplicitPerCall` exists so that "I clicked Approve" means "I
  looked at every call".
- **Never upgrade an operator in place** — scope changes re-provision.

### 0.6 Conventions

- **EDDI:** every commit that changes behaviour must add an entry at the **top** of
  `docs/changelog.md` **in the same commit** (AGENTS.md §2 rule 8). Newest first.
- **Never add a `Co-Authored-By` trailer** to commits or PR bodies.
- Branch names: `fix/…`, `feat/…`, `chore/…`. Never push a `claude/*` branch name.
- Comments explain *why*, not *what*. Match the density of the surrounding file — these files are
  heavily commented on purpose, and a bare change in a file full of rationale reads as unfinished.

### 0.7 Verification commands, and their traps

**EDDI**
```bash
./mvnw -o test -Dtest='SomeTest,OtherTest' -DfailIfNoTests=false
```
- `mvnw` **auto-formats tracked `.java` files in place** on every build. Expect unrelated
  whitespace diffs; do not commit them.
- `-Dtest=Class#method` silently runs **0 tests** (exit 0, looks like a pass) when the method is in
  an `@Nested` class. Filter by whole class.
- The full local suite is **red at baseline** (~8 failures / ~288 errors) — all environmental. Get a
  baseline before blaming your change.
- Integration tests (`*IT`) need Docker/testcontainers and **cannot run locally**; they run in CI.
- Type-signature refactors need `./mvnw clean` — incremental builds reuse stale `.class` files and
  hide breaks in callers you did not edit.

**EDDI-Manager**
```bash
npm run typecheck          # tsc -b — `npx tsc --noEmit` checks NOTHING (solution-style tsconfig)
npx eslint src --max-warnings 0
npm run i18n:check         # key parity across 11 locales
npx vitest run src/lib/operator          # a subset
npx vitest run                            # full suite (~3 min, ~5250 tests)
```
- 11 locales must stay in key parity. Plural keys need each locale's own CLDR suffix set — mirror an
  existing plural key (`agents.count_*`) rather than guessing; Arabic has six forms.
- Tailwind v4 uses cascade layers: utilities beat `@layer components`, so component overrides must
  be **unlayered** to win.
- A pre-commit hook runs eslint + typecheck on staged files.

---

## A. Operator can converse with other agents and groups

### A.1 The ask

> "Why can't the operator chat with an agent or a group of agents? Would make a whole lot of sense
> (e.g. for testing if the agent works)."

Concretely: after the operator builds or edits an agent, the admin should be able to say *"now send
it a test message and show me what it says"* — without leaving the operator chat.

### A.2 Do NOT use `ConverseWithAgentTool`

There is a built-in tool `converse_with_agent`
(`src/main/java/ai/labs/eddi/modules/llm/tools/ConverseWithAgentTool.java`). It is the wrong route:

1. It is wired through `DynamicAgentToolsProvider`, gated on `enableBuiltInTools` +
   `builtInToolsWhitelist` on the LLM task. The operator is provisioned via `setup-api`, whose
   request (`createApiAgent` in `src/lib/api/agent-setup.ts`) sends **neither**.
2. It additionally requires `dynamicAgents.enabled` **and** `allowDelegation` on the created agent
   (`ConverseWithAgentTool` lines ~127).
3. `src/lib/operator/escalation-flags.ts:84-91` deliberately **flags `dynamicAgents.*` to the
   approver as an escalation**. Turning it on for the operator itself contradicts a control the
   operator is supposed to enforce on others.

So: adding a built-in tool means widening two orthogonal mechanisms and undermining an existing
warning. Use the operator's own architecture instead.

### A.3 The route: allow-list the runtime conversation endpoints

These already exist and are already in the OpenAPI spec the operator is built from:

| Endpoint | Interface | Purpose |
|---|---|---|
| `POST /agents/{agentId}/start` | `IRestAgentEngine` | start a conversation; returns `Location` with the conversation id |
| `POST /agents/{conversationId}` | `IRestAgentEngine` | say something (`text/plain` and `application/json` variants) |
| `GET /agents/{conversationId}` | `IRestAgentEngine` | read the conversation snapshot back |
| `GET /agents/{conversationId}/status` | `IRestAgentEngine` | lifecycle state |
| `POST /groups/{groupId}/conversations` | `IRestGroupConversation` | start a group discussion |
| `GET /groups/{groupId}/conversations/{groupConversationId}` | `IRestGroupConversation` | read it back |

**Excluded, deliberately** (§0.5): `/resume`, `/state`, `/cancel`, `/undo`, `/redo`,
`/endConversation`, `DELETE …/conversations/{id}`.

Add the GETs to `READ_ENDPOINTS` in `tool-scopes.ts`. The POSTs are the decision below.

### A.4 THE decision you must make first

The POSTs match `requireApproval: ["http.post:*"]`, so **every test message pauses for human
approval**.

**Option A1 — accept it (recommended default).** Zero change to the security model. The admin clicks
Approve per message. For a 2–3 message smoke test this is tolerable, and each card honestly says
"send message X to agent Y". Ship this first; it is strictly additive and cannot regress the gate.

**Option A2 — exempt conversation POSTs.** Better UX, but **it does not work today**: any exempt
beginning `http.post:` is rejected by `verifyGateInstalled` (§0.4), so activation would fail its own
verification and roll back. Implementing A2 therefore requires editing the exempt-safety guard in
`src/lib/api/operator.ts` to permit a **narrow, hard-coded allow-list** of conversation patterns
while still rejecting everything else. That is a real security-boundary change and needs:
- an explicit allow-list constant (not a relaxed prefix rule),
- tests proving every *other* `http.post:` exempt is still rejected,
- a note in `planning/operator-write-scope-plan.md` recording the decision,
- a human sign-off. **Do not do A2 on your own initiative.**

The argument *for* A2, if someone asks: talking to an agent changes no configuration, and the target
agent's own approval gate remains in force for anything that agent then does. The argument against:
it consumes tokens, creates conversation records, and is the first hole ever punched in
`http.post:*`.

### A.5 Implementation (Option A1)

1. **`src/lib/operator/tool-scopes.ts`**
   - Add the six endpoints above to `READ_ENDPOINTS` (all of them — `READ_ENDPOINTS` is the
     *granted* set, not "only GETs"; the gate is what distinguishes them). Group them under a
     `// Test-drive: talk to an agent or group` comment explaining that the POSTs are gated and that
     `/resume`, `/state` and `/cancel` are excluded on purpose, with a pointer to
     `planning/operator-write-scope-plan.md` §5.
   - Confirm `buildEndpointFilter` picks them up automatically (it maps over the resolved set).

2. **`src/lib/operator/system-prompt.ts`** — add a short section to `BODY_ROLE` (or a new
   conditional section) telling the operator:
   - it can test-drive an agent: start a conversation, send a message, read the reply back;
   - each message needs approval, so batch the test into as few messages as possible and say what it
     is about to send;
   - **if the target conversation comes back `AWAITING_HUMAN`, the agent under test paused on its own
     approval gate** — report that as the finding (it is usually the *desired* result when testing a
     gated agent), do not poll and do not attempt to resume it;
   - it cannot approve on another agent's behalf.

3. **Tests** (`src/lib/operator/__tests__/tool-scopes.test.ts`, `system-prompt.test.ts`)
   - the six endpoints are present in `endpointsForScope("read_only")` and `("read_write")`;
   - `/resume`, `/state`, `/cancel` are **absent** from both — assert this explicitly, it is the
     regression that matters;
   - `buildToolApprovals()` still returns exempt `["http.get:*"]` only (proves A1 did not silently
     become A2);
   - the prompt mentions the `AWAITING_HUMAN` behaviour.

4. **Changelog** — Manager has no changelog file; the PR body carries the rationale.

### A.6 Risks

- **Prompt/endpoint drift.** `system-prompt.ts` derives everything from the granted endpoint set for
  a reason: a prompt that claims a capability the agent lacks is worse than silence. If you gate the
  new prompt text, gate it on the actual endpoints being present, not on scope.
- **Existing operators are unaffected until re-activated** — the endpoint filter and the prompt body
  are both baked in at provisioning. Say so in the PR body.
- Spec drift: `setup-api` matches endpoints against the fetched OpenAPI document. If a path string
  here does not match the spec exactly, the tool is silently not generated. Verify against a real
  `/openapi.json` from a running instance.

---

## B. Operator conversation persistence + history tab

### B.1 The ask

> "The fact that an operator conversation gets unreachable once navigating away or restarting is
> bad. We should show the latest conversation, and also a history tab (like in the other parts of
> the app)."

### B.2 What exists today

- `src/hooks/use-operator-chat.ts` is a **module-level zustand store** shared by the full page and
  the drawer. Messages live only in memory.
- The conversation id is persisted to **`sessionStorage`** under `eddi.operator.conversationId`
  (`CONVERSATION_STORAGE_KEY`, ~line 133). The comment records the intent: *"an operator
  investigation belongs to the tab"*. So today: navigating within the tab keeps the id; a reload
  keeps the id but **not the transcript**; a browser restart loses both.
- `getSimpleConversationLog(conversationId, returnDetailed, returnCurrentStepOnly)` in
  `src/lib/api/conversations.ts` returns a snapshot with `conversationSteps` /
  `conversationOutputs`.
- `getConversationDescriptors(...)` lists conversations; check its signature for an agent filter.
- `src/hooks/use-chat.ts` already rebuilds a message list from a snapshot — **read it before writing
  a new mapper**; `extractInput`, `extractOutputParts` and friends in `conversations.ts` are the
  shared primitives.
- `src/pages/conversations.tsx` and `conversation-detail.tsx` are the existing list/detail patterns.

### B.3 Implementation

1. **Re-hydrate on mount.** In `use-operator-chat.ts`, add a `hydrate(conversationId)` action:
   read the conversation with `returnCurrentStepOnly: false`, map every step to
   `{role: "user"} / {role: "agent"}` messages, and replace `messages`. Call it from the operator
   page/drawer when the store is empty but a stored id exists.
   - **Guard against races:** `reset()` or a send may happen mid-fetch. Follow the existing pattern
     in `resolveApproval` — capture an `AbortController`, and drop the result if the store moved on.
   - If the conversation 404s (purged), clear the stored id and start clean rather than surfacing an
     error.

2. **Survive restart.** Two options; pick one and say why in the PR:
   - move the key to `localStorage` (simple, reverses the documented tab-scoped intent), or
   - on mount with no stored id, look up the newest conversation for the operator's `agentId` via
     `getConversationDescriptors` (no storage semantics changed; one extra request).
   The second is more in keeping with the existing comment.

3. **History tab** on `/manage/operator`: a tab bar (Chat | History). History lists the operator
   agent's conversations — timestamp, first user message, state badge (reuse the conversations-page
   components). Selecting one sets `conversationId`, hydrates, and switches to Chat.
   - A conversation in `AWAITING_HUMAN` should be visibly marked; selecting it must restore
     `isPaused` and the approval card. `useApprovalStatus(conversationId, isPaused)` already drives
     that surface — set `isPaused` from the hydrated snapshot's `conversationState`.

4. **Known and acceptable:** the client-side decision entries (`kind: "decision" | "notice"` on
   `ChatMessage`) do **not** survive hydration — they are this tab's record, not backend state. The
   backend's own pending-approval messages carry the story in a re-read transcript, so a hydrated
   view is coherent without them. Do not try to reconstruct them.

5. **Tests** (`src/hooks/__tests__/use-operator-chat.test.tsx`, plus a page test)
   - hydrate rebuilds user/agent messages in order from a snapshot;
   - hydrating a paused conversation sets `isPaused` and the pause reason;
   - a 404 clears the stored id and leaves an empty, usable chat;
   - a `reset()` during hydration discards the in-flight result;
   - the history list renders and selecting an entry loads it.

### B.4 Risks

- The store is **shared between the page and the drawer**. Hydrating from two mount points at once
  must not double-append. Make `hydrate` idempotent and no-op when a hydration is already in flight.
- `returnCurrentStepOnly` defaults differ per call site; be explicit.
- Long transcripts: cap what you render, or the first paint of a 200-turn conversation janks.

---

## C. A tool call whose body is invalid JSON should fail before the API call

### C.1 The symptom

An operator `setupAgent` call, already approved by a human, returned:

```
400 {"objectName":"Class","attributeName":"systemPrompt","line":1,"column":593}
```

The body failed to **bind** at parse time — column 593, inside the `systemPrompt` string value.

### C.2 Root cause — read this before "fixing" the escaping

`McpApiToolBuilder.buildBodyTemplate` (around line 376) generates the request-body template as a
**single variable**, `{requestBody}` (`WHOLE_BODY_VARIABLE = "requestBody"`). The model writes the
entire JSON body itself. The file documents why per-property templates were rejected:

> Values are substituted into the JSON unescaped: the templating engine runs in TEXT mode and
> escapes nothing, so a model-supplied value containing a quote can break the body or add fields the
> schema never declared. With the whole body in one variable there is no substitution boundary to
> cross.

So **EDDI is not mangling anything** — there is nothing between the model's string and the wire.
A bind failure at column 593 means the model emitted a `requestBody` that is not valid JSON:
overwhelmingly likely a raw newline (or unescaped quote) inside a long multi-line `systemPrompt`,
where `\n` should have been written.

Do **not** "fix" this by escaping values in the template or decomposing the body — both are
explicitly rejected designs with reasons recorded in that file.

Note also: this used to be invisible. `ApiCallExecutor` returned an empty map on non-2xx, so the
model received `{}` and could not tell failure from success. That is already fixed — the result map
now carries `httpCode` and a truncated error `body`. So the model *can* now see and retry this. C is
about failing faster and more clearly, not about restoring visibility.

### C.3 Implementation

In `HttpCallToolsProvider` (the executor lambda, ~line 182), before calling
`apiCallExecutor.execute(...)`:

- if the resolved `requestBody` argument is present **and** the call's content type is
  `application/json`, attempt `jsonSerialization.deserialize(requestBody, Object.class)`;
- on failure, **do not send the request**. Return a tool result in the same shape the executor's
  catch already uses, with a message naming the parse position, e.g.
  `{"error":"requestBody is not valid JSON: <message>. Re-send with the body correctly escaped (\\n for newlines inside strings)."}`;
- log at warn with the tool name; **never log the body** (it may contain resolved secrets — see
  `RequestRedactor`, which exists precisely because bodies reach conversation memory).

Where to read the argument: `templateDataFor(memory, toolRequest)` already deserialises
`toolRequest.arguments()` into a map and merges it. Read `requestBody` from that map rather than
re-parsing the arguments string.

Edge cases to honour:
- a tool with **no** body (GET) — skip entirely;
- a body template that is not exactly `{requestBody}` (a spec with no schema uses the same variable;
  older configs may differ) — only validate when the value is actually present;
- a non-JSON content type — skip;
- the value legitimately being a JSON array or scalar — `deserialize(..., Object.class)` accepts all
  of those, which is correct.

### C.4 Tests

`src/test/java/ai/labs/eddi/modules/llm/impl/` (mirror the style of the existing
`ApiCallExecutor*Test` classes — Mockito, no Quarkus context):

- an invalid-JSON `requestBody` returns the error result and `ApiCallExecutor.execute` is **never
  called** (`verify(executor, never())…`) — this is the assertion that matters;
- a valid body passes straight through unchanged;
- a GET/no-body call is unaffected;
- the error message does not contain the offending body.

**Mutation-check it:** revert the guard, confirm the first test fails. Error-path tests in this
codebase have a history of passing for the wrong reason.

### C.5 Changelog

Required, same commit, top of `docs/changelog.md` — see §0.6. Lead with the symptom (an approved
call rejected at bind time), then the root cause (the model writes the whole body; EDDI adds no
escaping *by design*), then what changed.

---

## D. Suggested order

1. **C** — smallest, and it stops another human approval being spent on a request that cannot
   succeed.
2. **A (Option A1)** — highest user-visible value; purely additive.
3. **B** — largest, fully self-contained.

Each is a separate PR. EDDI PRs target `chore/remove-agent-father` while #672 is open (note that a
PR based on anything other than `main` does **not** trigger `ci.yml`, and CodeRabbit skips it — the
umbrella PR is where the stack actually gets built and reviewed). Manager PRs target `main` and get
full CI plus review.
