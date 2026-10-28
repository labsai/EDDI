# Evaluated Generation — checks and repair for every step that produces something

> **Status: proposal, not approved.** Written 2026-10-15 against `main` at `d49e8552b`, revised 2026-10-24
> against `5028af0a3` after output guardrails (#1064), the `classifier` guardrail (#1112), the per-conversation
> cost ceiling and enforced tenant budget (#1058) and the agent regression harness (#1065) landed. E1 (E1a + E1b)
> is in review as #1121. Every
> "exists today" claim names the file or doc it was checked in; re-check them before starting an item, because
> this area is moving fast.

## Prerequisite reading

- [`docs/project-philosophy.md`](../docs/project-philosophy.md) — the nine pillars this plan is measured against
- [`docs/langchain.md`](../docs/langchain.md), section "Input and Output Guardrails" — the loop E1 extends
- [`docs/llm-resilience.md`](../docs/llm-resilience.md) — the recovery order guardrails run after
- [`planning/llm-turn-resilience-plan.md`](llm-turn-resilience-plan.md) — R1–R16 and their known gaps
- [`planning/multimodal-attachments-completion-plan.md`](multimodal-attachments-completion-plan.md) — the attachment work E6 extends
- [`docs/model-cascade.md`](../docs/model-cascade.md), [`docs/hitl.md`](../docs/hitl.md),
  [`docs/group-conversations.md`](../docs/group-conversations.md), [`docs/clustering.md`](../docs/clustering.md),
  [`docs/agent-testing.md`](../docs/agent-testing.md)

---

## 1. Why

### 1.1 The pattern

Every serious generative workload has the same loop: **make → judge → repair**. Something produces an output;
something checks it; when the check fails, the producer tries again with the failure in hand, or the problem is
handed upward. The work is to make that loop cheap, bounded, auditable and configurable, because it is where
quality actually comes from: an output that ships on the first sample is a matter of luck; an output that ships
after a check is a matter of engineering.

### 1.2 The motivating case

This plan started as an analysis of whether EDDI could host a production AI video-generation pipeline: a
TypeScript service that turns a topic into a narrated, captioned training video. One job makes roughly 120–200
model calls over 11–25 minutes:

1. A writer produces a JSON storyboard; a judge reviews it; the best of two drafts is revised once.
2. Narration is recorded per scene. Each line is transcribed back and compared with its script, and a judge
   checks by ear that every line sounds like the same speaker.
3. Keyframes: four candidates per scene, a judge selects one and inspects it, the rejected one is re-picked
   from the paid alternates, and a re-render gets the judge's fix hint.
4. A human (or an autonomous mode) approves before the expensive phase.
5. Video clips are generated per scene. A multimodal judge watches each clip. A repair ladder escalates from
   *fix the clip* to *redraw keyframe and clip* to *rewrite the shot plan and re-film*, keeping the best attempt
   and rolling back the rest.
6. ffmpeg assembles the cut; a judge watches the whole video and sends flagged scenes back through step 5.

Its own design notes say that roughly a third of its spend is repair, and that this is the quality system
working. That service is the example used throughout this plan, but **nothing in this plan is
video-specific**: each item is justified by at least three unrelated agent types (§1.3).

### 1.3 The same loop elsewhere

| Use case | Producer | Best check | Repair | Latency budget |
| --- | --- | --- | --- | --- |
| Invoice or contract extraction → JSON | LLM | deterministic: schema, arithmetic, required fields | re-ask with the exact failure | seconds |
| Text-to-SQL, API argument building | LLM | a tool: dry-run, `EXPLAIN`, a validator endpoint | re-ask with the tool's error | seconds |
| Grounded support answer (RAG) | LLM | LLM judge: is every claim backed by the retrieved passages? | re-ask, or escalate the model | 1–3 s, must stay cheap |
| Compliance and safety on output | LLM | a classifier: PII, toxicity, off-topic | redact, re-ask, or refuse | sub-second |
| Marketing copy, documentation, translation | LLM or a group | LLM judge (brand voice, meaning fidelity) and a human | re-ask with the note | minutes to hours |
| Multi-agent deliverables | a group task | moderator VERIFY | rework within the run | minutes |
| Images, speech, video | a media model | multimodal LLM judge plus deterministic checks | re-ask, re-pick, redo an earlier step | seconds to many minutes |

Four conclusions shape the design:

1. **The most valuable checks are deterministic.** Schema, arithmetic and a tool's error message are cheap,
   reliable and auditable. An LLM judge is one check type among several, and should judge defects, not taste.
2. **A human is just another check.** EDDI already treats a group-task rejection with a note as "redo it"
   (`onTaskRejection: RETRY`); a single agent does not (§2.1).
3. **The loop runs at four time scales**: one model call, one pipeline step, one group, one long job. One
   vocabulary can serve all four; one mechanism cannot.
4. **Media generation is a producer type, not a separate feature.** It plugs into the same loop.

---

## 2. What exists on `main` today

### 2.1 Loops already in the engine

EDDI runs this loop in seven places:

| Where | What it checks | How it repairs | Source |
| --- | --- | --- | --- |
| **Output guardrails** (#1064) | built-in types (`pii-redaction`, `regex-filter`, `max-length`, `json-object`) and `classifier`, an author-configured safety model (#1112) that answers one label | `reprompt`: the same model is asked again with a corrective message, `maxRetries` 0–5, the turn's `minAttemptMs`, the circuit breaker; then **block**. Also `redact`, `block`, `warn`. `failMode` closed/open per guardrail | [`GuardrailService.java`](../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java), [`ClassifierGuardrail.java`](../src/main/java/ai/labs/eddi/modules/llm/guardrails/impl/ClassifierGuardrail.java) |
| Format recovery (R5) | `EMPTY`, `TRUNCATION`, `CONTENT_FILTER`, `INVALID_JSON`, `SCHEMA_MISMATCH`, `CONTEXT_TOO_LONG` | same-model re-ask, budgeted by `maxRetries`, `maxRetryCostUsd`, `minAttemptMs`, gated by the circuit breaker | [`FormatRetryRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java) |
| Model cascade | a confidence score, plus `invalid_output` | escalate to the next model; `returnBestAcrossSteps` keeps the best | [`CascadingModelExecutor.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java) |
| Group TASK_FORCE VERIFY | the moderator | **none within the run**: a failed task becomes `FAILED`, which is terminal; an unparseable verdict auto-verifies every task | `SharedTaskList.verifyTask`, `TaskForceEngine` |
| Group task approval (HITL, `TASK` granularity) | a human | `onTaskRejection: RETRY` re-queues the task with the reviewer's note | [`docs/hitl.md`](../docs/hitl.md) |
| Single-agent HITL | a human | **none**: on `REJECTED` the remaining pipeline tasks are skipped and the note is shown | [`docs/hitl.md`](../docs/hitl.md) |
| Team cadences | the moderator, across runs | an unverified backlog task returns to `PENDING` with the feedback appended | [`docs/group-conversations.md`](../docs/group-conversations.md) |

Output guardrails run on the answer **after** `responseValidation`, in every execution mode (plain, streaming,
tool loop, cascade, HITL resume). They are the natural home for checks on meaning: the earlier version of this
plan proposed adding them to `responseValidation`, before guardrails shipped with their own reprompt loop.

### 2.2 Other foundations on `main`

| Capability | Source |
| --- | --- |
| Native `responseSchema` (OpenAI, Azure OpenAI, Mistral, Gemini; non-strict) plus EDDI's own validation | [`docs/langchain.md`](../docs/langchain.md), #998 |
| A fixed recovery order: local repair → transport retry → same-model re-ask → escalate → fallback | [`docs/llm-resilience.md`](../docs/llm-resilience.md) |
| `thinkingLevel`, `mediaResolution` and every other provider parameter, model-aware | [`docs/llm-parameters.md`](../docs/llm-parameters.md), #1017 |
| Agent-level `turnDeadlineMs`; the per-request header is capped at 10 minutes | #996 |
| `Idempotency-Key` on turns, cross-node since #1089; structured `error` object | #1000, #1089 |
| Agent-level `maxCostPerConversationUsd` (sum of every step's `audit:cost`, checked before each model call and between tool-loop rounds); the tenant `maxMonthlyCostUsd` is enforced | [`docs/langchain.md`](../docs/langchain.md), [`docs/tenant-quotas.md`](../docs/tenant-quotas.md), #1058 |
| Agent regression harness: golden conversations, tool mocks, `maxCostPerRun`; an LLM-as-judge expectation is a planned follow-up | [`docs/agent-testing.md`](../docs/agent-testing.md), #1065 |
| NATS cluster mode: conversation leases with fencing, a shared dead-letter stream with replay, cluster-wide cost totals | [`docs/clustering.md`](../docs/clustering.md) |
| A HITL pause is committed before the caller hears of it; approval timeouts are one-shot schedules, at-least-once, bound to a `pauseId`; a resume can return the resumed turn's reply (#1116) | [`docs/hitl.md`](../docs/hitl.md) |
| Signed inbound webhooks start an agent turn or a group discussion | [`docs/webhook-triggers.md`](../docs/webhook-triggers.md), #1059 |
| All outbound HTTP goes through `OutboundHttp` (`SafeHttpClient` was retired) | `engine/httpclient/outbound/OutboundHttp.java` |

### 2.3 Still missing (checked in code on `5028af0a3`)

| Gap | Evidence |
| --- | --- |
| A model-backed check can only say safe or unsafe: `classifier` returns one label, so a reprompt can only say *that* the answer failed, not *what* to fix | `ClassifierGuardrail.classify`, `GuardrailVerdict.repromptHint` is a constant or null |
| A spent reprompt always **blocks**: right for safety, wrong for quality checks, where the best attempt with a warning beats a block message | `GuardrailService.runOutput` |
| No deterministic check over the parsed reply (arithmetic, cross-field rules) | built-in guardrail types |
| No model-backed check can see the turn's images or audio; no `video/*` forwarding at all | `ClassifierGuardrail` sends text; [`AttachmentForwarder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarder.java) |
| `maxRetryCostUsd` is priced from per-1M prices and ignores the conversation's spend; the call that crosses a ceiling completes; nothing reserves cost for calls in flight | [`docs/langchain.md`](../docs/langchain.md) |
| A generated file cannot become an attachment; attachments are written in exactly one place | [`Conversation.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java) |
| Attachments capped at 20 MB per file, 100 MB per conversation | `eddi.attachments.*` in `application.properties` |
| No image, speech, video or music generation anywhere | no matching type in `src/main/java` |
| `batchRequests` only works with `fireAndForget`, so per-item results are discarded | [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java) |
| Polling an external job inside a turn: each backoff is capped at 30 s and sleeps on the conversation thread | `ApiCallExecutor.MAX_BACKOFF_MILLIS` |
| No way for an agent to start its own next turn | the reserved actions are `PAUSE_CONVERSATION`, `STOP_CONVERSATION`, `CONVERSATION_START`, `CONVERSATION_END` |
| Two same-model re-ask loops (`FormatRetryRunner`, `GuardrailService`) with separate retry counts | the two classes |

---

## 3. Design principles

Each principle is a pillar of [`project-philosophy.md`](../docs/project-philosophy.md) applied to this problem.

1. **Configuration is the loop's policy; Java is its machinery** (Pillar 1). Which checks run, how many attempts
   a step gets, what a failure costs and what happens when everything fails are agent-designer settings with
   defaults that change nothing for an existing agent.
2. **Extend the loops that shipped; do not build another one** (Pillars 2, 3; AGENTS.md "unification over
   duplication"). A check on meaning is a new guardrail type, not a new runner.
3. **The pipeline stays a straight line** (Pillar 3, Golden Rule 3). Loops are bounded and live *inside* a step.
   A repair that must reach an earlier step leaves as an **action** for behaviour rules and runs later.
   `LifecycleManager` gains no jump or loop.
4. **No unmetered attempt** (Pillar 2). Every attempt, judge call and media call is priced, counted against the
   conversation's spend, and checked *before* it is made.
5. **Judge output is untrusted data** (Pillar 4). It never becomes template source and never reaches a model's
   user role as free text (§4.4). This is the invariant `GuardrailService.correctiveMessage` and
   `FormatRetryRunner` already hold.
6. **A failed attempt never enters memory the model will see** (Pillar 8). Attempts live in the audit record and
   the trace; only the delivered answer is stored as output.
7. **Every attempt is evidence** (Pillar 6). The audit record says which check failed, with which issue codes,
   what was tried next, and what it cost.
8. **Heavy compute and domain logic live outside** (Pillar 1's escape hatch). ffmpeg, music, and
   domain-specific deterministic code run in services behind MCP or HTTP.
9. **Off by default, cheap by default.** No item changes the behaviour, latency or cost of an agent that does
   not opt in.

---

## 4. The model

### 4.1 Vocabulary

- **Producer**: whatever makes the output — a model call, a tool or HTTP call, a media generation, an agent
  turn, a group task.
- **Check**: one test of an output. For model answers that is an **output guardrail**; elsewhere the same
  verdict shape is reused (E2, E5).
- **Verdict**: `GuardrailVerdict` — `ALLOW`, `REWRITE` or `VIOLATION`, a reason, an optional reprompt hint, a
  cost. E1 adds the issue codes a judge returned.
- **Repair**: the guardrail actions that exist — `block`, `redact`, `reprompt`, `warn` — plus, for `reprompt`,
  what happens when its retries are spent (E1) and `emitAction` for repairs that reach an earlier step (§4.3).

### 4.2 Where the loop lives

| Level | Mechanism | Item |
| --- | --- | --- |
| L1: one model call | output guardrails inside `LlmTask` | E1 |
| L2: one tool, HTTP or media step | the same verdict shape on mcpcalls, httpcalls and media steps | E5, E7 |
| L3: a group | VERIFY and human approval produce verdicts; TASK_FORCE reworks within the run | E2 |
| L4: a long job | repairs leave as actions; the agent continues itself in the next turn | E8 |

### 4.3 Repairs that reach an earlier step

A check can only re-ask the producer it is attached to. When the fix lies upstream (the clip is wrong because
the keyframe is wrong), the step ends with an action such as `REDO_KEYFRAME`, which behaviour rules route like any
other — in the same turn if the workflow places the upstream step later, or in the next turn via continuation
(E8). Tasks never call tasks.

### 4.4 Feedback without injection

`GuardrailService.correctiveMessage` builds a reprompt's user message from the author's `repromptMessage`, else
the guardrail's own hint, else a generic sentence, with `{reason}` replaced literally — "never the user's or the
model's text". A judge's free-text critique is model output derived from untrusted content (retrieved documents,
tool results, user input). Putting it into the reprompt would open an injection path through the judge into the
producer.

**So judge feedback is a closed vocabulary.** The guardrail declares its issue codes and the author-written fix
text for each. The judge may only answer with those codes; an undeclared code means the judge could not decide
(`failMode` applies). The reprompt hint is the author's fix text for the codes returned, and the audit reason
lists the codes. The invariant holds, and every word the producer is told was written by the agent designer.

---

## 5. Work items

Each item is one PR (or a short series), useful on its own, and none is video-specific. Sizes are rough:
S ≈ days, M ≈ one to two weeks, L ≈ several weeks.

Every item's definition of done includes, besides unit tests (plain JUnit 5 + Mockito, AGENTS.md §4.6):

- the feature's page under `docs/` (for E1: the guardrails section of [`docs/langchain.md`](../docs/langchain.md));
- **the Platform Operator** (AGENTS.md §4.6.6): prompt and/or allow-list for every config field an admin will
  ask about, and a bump of `operator-revision.json`;
- the Manager: an editor control, not just a TypeScript type (Pillar 7);
- metrics on the existing meters (`eddi.guardrail.triggered`, `eddi.llm.recovery`) where they fit, a new meter
  only where they do not;
- a changelog fragment under `docs/changelog.d/`.

### E1 — The `judge` guardrail and quality-check exhaustion (M) — **the next PR**

**Goal.** A well-formed but wrong answer is caught and repaired with feedback that says what to fix, and a
quality check that cannot be satisfied delivers its best attempt instead of a block message.

**E1a — `judge`, an output guardrail that answers with issue codes.**

```json
"outputGuardrails": [ {
  "type": "judge",
  "action": "reprompt",
  "maxRetries": 1,
  "onExhausted": "deliver-best",
  "parameters": {
    "provider": "gemini",
    "modelName": "gemini-3.7-flash",
    "apiKey": "${vault:gemini-key}",
    "thinkingLevel": "low",
    "promptTemplate": "Sources:\n{context}\n\nQuestion:\n{input}\n\nAnswer:\n{output}\n\nCheck the answer against the sources.",
    "includeRetrievedContext": "true",
    "issues": "{\"UNSUPPORTED_CLAIM\": \"Remove every claim the sources do not state.\", \"MISSING_CITATION\": \"Cite the source of each fact you state.\"}",
    "timeoutMs": "5000",
    "inputPricePer1M": "0.15",
    "outputPricePer1M": "0.60"
  }
} ]
```

- **Shares the classifier's machinery** rather than copying it: the model is built through `ChatModelRegistry`
  with the same provider-parameter pass-through, vault and `${vars}` resolution, timeout (capped, and bounded by
  the turn deadline), virtual-thread executor, pricing, `promptTemplate` rendering with the screened text as
  *data* only (`{input}`, `{output}`, `{context}`), `includeRetrievedContext` and `failMode`. Extract that into a
  shared component used by both types; do not fork `ClassifierGuardrail`.
- **The answer format is EDDI's, not the author's.** EDDI appends a fixed instruction naming the allowed codes and
  the JSON shape `{"issues": ["CODE", ...]}` (empty list = pass), and asks for native JSON (with the codes as an
  enum where the provider takes a schema). Parsing tolerates a code fence or text around the object. Any answer
  that is not that shape, or names an undeclared code, is **could not decide** (`judge_unparseable`), as is an
  unreachable or slow judge (`judge_unavailable`). Safe is a positive finding: only a parsed empty list passes.
- **Verdict**: issues → `VIOLATION` with reason `judge_failed:<CODE>[,<CODE>…]` (codes are author-declared, so
  they are safe in audit records and metrics) and a reprompt hint built from the author's fix texts for those
  codes. `repromptMessage` still overrides it, as for every type.
- **Validation at deploy time**: `provider`, `promptTemplate` and `issues` required; `issues` a JSON object of 1–20
  codes matching `[A-Z][A-Z0-9_]{0,39}`, each with non-blank fix text of bounded length.
- **Phases**: output only. `screensHistory()` is false, like the classifier.
- **Metrics**: `eddi.guardrail.judge.duration{provider, outcome}` (`pass`, `fail`, `unparseable`, `unavailable`,
  `timeout`), mirroring the classifier timer; the existing `eddi.guardrail.triggered` counts the action.

**E1b — `onExhausted` on `reprompt`: `block` (default, today's behaviour) or `deliver-best`.**

- When the retries of a `deliver-best` guardrail are spent, the answer delivered is the attempt it rated best
  (fewest issues; for a binary type, the last attempt), recorded as a `warn` outcome with reason
  `<reason>:delivered_best` and the attempt count.
- **The delivered attempt is still screened by every other guardrail**: it goes through the whole list once more
  with the exhausted guardrail skipped and no further reprompts (a `reprompt` finding then blocks). A quality
  check giving up must never let an answer past a PII redaction or a safety classifier.
- Deploy-time warning when `deliver-best` is set on a `classifier` (a safety check that delivers what it flagged
  defeats its purpose); refused on any action other than `reprompt`.
- Applies in every execution mode output guardrails run in; tests cover plain, tool loop and cascade.

**Not in E1**: the `expression` type (E1c, below), images or audio for the judge (after E6), best-of-N sampling,
judging tool calls inside the agent loop (only the final answer is screened, as today).

**E1c — `expression`, a deterministic output guardrail (S, follows E1).** A check over the parsed JSON answer
(`sum(lines, 'amount') == total`, cross-field rules) with a declared issue code and fix text, so it reprompts
exactly like `judge`. Needs a decision on the evaluator (§9): a small sandboxed parser in the style of
`CalculatorTool`'s `SafeMathParser`, or the existing bounded `ExpressionProvider` grammar. No script engine.

**Acceptance**: a grounded-answer agent with a `judge` reprompts once with the author's fix text for the flagged
code and serves the corrected answer; with `deliver-best` and a judge that never passes, the best attempt is
delivered, still PII-redacted, with a `warn` audit record; an agent without the new type or field behaves
byte-identically to today.

### E2 — Humans and groups produce verdicts (S–M)

- **Single-agent HITL, "revise with my note".** A resume verdict that asks the model to revise its final answer
  with the reviewer's note (the note is human-written, so it may go in the user role; it is still never template
  source), reusing the resume path's re-ask of the final answer. #1116 added an opt-in reply on resume, not this.
- **TASK_FORCE `maxReworks`.** A task that fails VERIFY within a run returns to `ASSIGNED` with the verifier's
  feedback, up to `maxReworks` times (default 0, today's behaviour), then `FAILED`. Mirrors
  `onTaskRejection: RETRY` for human rejections and the cross-run cadence loop.
- **VERIFY can run checks.** Deterministic checks first and for free; the moderator's verdict is one more check.
  An unparseable moderator verdict keeps today's fail-open auto-verify, now counted and warned.
- **`convergence.mode: "VERDICT"`**: a repeating phase stops when the verdict passes, alongside today's
  similarity-based convergence.
- **Agent testing**: the harness's planned LLM-as-judge expectation should reuse the `judge` machinery from E1
  rather than add a third model-call path ([`docs/agent-testing.md`](../docs/agent-testing.md)).

### E3 — superseded

The earlier version of this plan proposed folding the planned output guardrails into `responseValidation`. They
shipped as their own mechanism (#1064) with `block`/`redact`/`reprompt`/`warn` and the `classifier` type (#1112),
and E1 now builds on them. What remains of the old E3 is a smaller unification: `FormatRetryRunner` and
`GuardrailService` each count their own re-asks. A turn can therefore re-ask for format and then again for a
guardrail. Share one per-task re-ask budget when either is next touched; it is not worth a PR of its own.

### E4 — Spend checks that see every attempt (S)

The per-conversation ceiling (#1058) already sums every step's `audit:cost` and is checked before each model call
and between tool-loop rounds. What is left:

- `maxRetryCostUsd` consults the conversation's spend, not only its own per-1M pricing;
- guardrail reprompts and judge calls are checked against the ceiling *before* they start, like the primary call;
- **reservations** for parallel attempts (needed by `forEach` in E5 and media in E7): a call in flight reserves
  its expected price so concurrent calls cannot all pass the same check;
- media costs (E7) report into the same `audit:cost`.

### E5 — Checks on tool and HTTP steps, and `forEach` (M)

- A check block on mcpcalls and httpcalls steps (deterministic pipeline mode) producing the same verdict shape.
  Repair: retry (same call, optionally with a corrective argument from an author-written template), `warn`,
  fallback, `emitAction`. A `tool` check type calls an httpcall or MCP tool and maps its result to a verdict
  (SQL dry-run, a validator endpoint, an image-QA service).
- **Billable calls never retry on timeout** by default: a timed-out generation may have completed and been billed.
- **`forEach`** on a step: iterate a property array, run the step per item with `maxConcurrency` (virtual
  threads, Pillar 5), collect each item's result and verdict into an array property. Replaces the
  fire-and-forget-only limitation of `batchRequests`, which keeps working as today.
- **Never abandon paid work**: a failing item does not cancel its siblings.

### E6 — Media groundwork (L) — extends the multimodal-attachments plan

Folded into [`multimodal-attachments-completion-plan.md`](multimodal-attachments-completion-plan.md), not a
parallel store:

- **Asset store on object storage** (S3-compatible) beside GridFS/Postgres, for files past the attachment caps.
  Memory holds `asset://` references only.
- **A step can attach what it produced**, so a later step's model (and a `judge`) sees it. This is the second
  writer of `MemoryKeys.ATTACHMENTS`, so it needs the same ownership, quota and MIME checks as upload (#1084 added
  those for group attachments).
- **`video/*` forwarding** to models that accept it, with a provider file upload for large files.
- **Media output types**: image, audio and video output items the Chat UI, Slack and the OpenAI-compatible `/v1`
  adapter can render.
- **Provenance**: every generated asset carries machine-readable "AI-generated" metadata (model, time, agent,
  conversation); the EU AI Act's transparency rules for synthetic media include machine-readable marking.
- **Lifecycle**: deleted with the conversation and by GDPR erasure; quotas per conversation and per user.

### E7 — Speech and image providers (M)

- One provider interface per modality (image, speech; video in E8), each with a pricing hook that reads the real
  unit (images, characters, seconds) from the response and reports into `audit:cost`.
- A deterministic pipeline step and an LLM tool over one implementation, both through `ToolExecutionService`
  (rate limiting, caching rules, cost tracking — Pillar 2: no unmetered path). Outbound calls through
  `OutboundHttp`, credentials only as vault references.
- First providers: the OpenAI-style `/images/generations` and `/audio/speech` shapes, which also cover the new
  `openai-compatible` self-hosted servers (#1062), and Gemini (image through `generateContent` with reference
  images as input; Gemini speech). Check the resolved langchain4j jars before assuming what they provide
  (AGENTS.md §2 rule 7).
- Media steps take checks like any other producer, including a `judge` over the generated image or audio, and
  `candidates` for best-of-N where the designer accepts the cost.

### E8 — Long jobs: the conversation as the job log (M–L)

A job of 150 model calls over 20 minutes is many turns, never one: the per-request deadline header is capped at
10 minutes, and the turn is EDDI's unit of tracing, persistence and recovery. Instead of a workflow engine:

- **Self-continuation**: a reserved action (for example `CONTINUE_CONVERSATION`) queues the next turn of the same
  conversation, bounded by `maxContinuations` (default 0), the turn deadline per turn and the conversation's cost
  ceiling across all of them. Each continuation carries an automatic idempotency key, so a re-delivered
  continuation does not run twice. Behaviour rules route on a phase property, so the job is a state machine
  written in config, and every phase is an ordinary traced turn.
- **Park until an external operation finishes**: the HITL pause machinery with a machine as the resolver. A poll
  schedule bound to the `pauseId` (at-least-once, safe to fire twice, like the HITL timeout) checks the operation
  and resumes the turn at the next task; a signed inbound webhook (#1059) is the natural way to resolve it
  sooner. The conversation thread is never held.
- **What the cluster already gives this**: leases and fencing, dead letters with replay, cluster-wide cost totals.
- **Then one async video provider**, behind the E7 interface.
- **Open**: a parked operation inside a group member's turn; nested pauses are not supported today.

### E9 — DAG with fingerprinted steps (L) — the roadmap's Phase 9

Design Phase 9 around content fingerprints: a step's fingerprint hashes its config and its inputs (including
asset bytes). An unchanged fingerprint reuses the stored result; a changed one recomputes that step and
everything downstream. One mechanism then gives resume after a crash, "regenerate scene 3", and upstream repair:
a **completed** result is never bought again.

Fingerprints alone do not stop a step being paid for twice while it runs: two nodes can both see the fingerprint
missing and both start the billed call, and a provider can bill a request whose result EDDI never committed (a
crash between the response and the write). "At most one paid execution per fingerprint" needs three more pieces,
each a design item of E9, not a given:

- **An atomic cross-node claim** on the fingerprint before the billed call (the conversation lease and fencing
  token are the model, [`docs/clustering.md`](../docs/clustering.md)); a node that loses the claim waits for the
  result instead of calling.
- **A durable result commit** under that claim, so a completed result survives the node that produced it.
- **Provider idempotency or reconciliation**: an idempotency key derived from the fingerprint where the provider
  accepts one, otherwise a lookup of the provider's own operation (by its id, recorded before waiting) on
  recovery — and, where neither exists, an honest statement that a crash mid-call can cost a second call.

### E10 — Composition service (sibling project, not core)

An MCP server that takes a declarative timeline (clips, audio tracks, captions, overlays) and returns an
`asset://` reference. ffmpeg, fonts and right-to-left text shaping live there. EDDI never runs a process.

---

## 6. Worked examples

### 6.1 Grounded support answer (E1)

A `judge` with `includeRetrievedContext`, codes `UNSUPPORTED_CLAIM` and `MISSING_CITATION`, `reprompt` with
`maxRetries: 1` and `deliver-best`, after a `pii-redaction` guardrail. A first answer with an invented claim is
re-asked once with the author's sentence; if the second still fails, it is delivered with a `warn`, still
redacted, and the audit ledger shows both verdicts and the judge's cost.

### 6.2 Invoice extraction (E1c)

`convertToObject` with a JSON-Schema `responseSchema` and `onSchemaMismatch: "retry"` for the shape, then an
`expression` guardrail `LINE_SUM` for the arithmetic, `reprompt` with `onExhausted: "block"`, so a wrong total
is never delivered.

### 6.3 The video pipeline (E1–E10)

| Stage | In EDDI | Outside EDDI |
| --- | --- | --- |
| Storyboard and review | LLM step with `responseSchema` and a `judge`, or a PEER_REVIEW group | — |
| Narration | speech step with `forEach` over scenes, a `tool` check | transcript matching and timing fit: domain logic behind MCP |
| Keyframes | image step, `forEach`, `candidates: 4`, a `judge` over the images | — |
| Cost gate | `PAUSE_CONVERSATION` with the estimate | — |
| Clips | video step, parked operation, a `judge` over the clip, `REDO_KEYFRAME` action | — |
| Assembly | an MCP call | the composition service (E10) |
| Final review and repairs | a `judge` over the cut emits per-scene actions; continuation turns repair | — |

Roughly 70% becomes configuration; the rest stays in services by design. The pipeline's quality lives largely in
its prompts, which move over as prompt snippets. Its practice of probing every prompt change cheaply before
spending now has an EDDI equivalent: an agent test suite ([`docs/agent-testing.md`](../docs/agent-testing.md))
with tool mocks for the paid media steps and a `maxCostPerRun`. Gemini's `propertyOrdering` is not in EDDI's
native-schema subset, so field order can only be requested in the prompt.

---

## 7. Rejected alternatives

| Alternative | Why not |
| --- | --- |
| Checks on meaning in `responseValidation` (this plan's first version) | Output guardrails shipped with their own reprompt loop, run after `responseValidation` in every mode, and already carry a model-backed type; a second home would split the feature |
| Extending `classifier` with an issues mode | Its contract is "safe is a positive label", with `labelPattern` and label matching; a judge answers a structured list. Share the call machinery, keep two types |
| Free-text judge feedback, even opt-in | Breaks the corrective-message invariant (§4.4) |
| Loops or jumps in `LifecycleManager` | Breaks the straight-line pipeline (Pillar 3, Golden Rule 3) |
| A dedicated refine `ILifecycleTask` | A separate task cannot see or re-ask another task's model call |
| langchain4j's agentic loop agents | Rejected in [`langchain4j-recommendations.md`](langchain4j-recommendations.md): EDDI's governance lives in its own loop bodies |
| Groups for the inner per-item loop | Every step would be an LLM agent turn where a function call belongs; the member timeout and text-only artifacts do not fit media |
| A general workflow engine (Temporal-style) | Contradicts "the engine, not the application"; conversations plus leases, dead letters and parked pauses already give durable steps |
| ffmpeg or any process execution in core | Pillar 4 |
| Best-of-N on video by default | Multiplies the dominant cost for the least reliable judgement |

---

## 8. Risks and traps

- **Cost amplification**: attempts × checks. Mitigation: every attempt priced and pre-checked (E4); deploy-time
  warning for a `judge` without prices.
- **Latency**: a judge adds a model call to every answer it screens, and any `reprompt` forces buffering instead
  of streaming. Keep `timeoutMs` short; document the trade.
- **A silently broken judge**: `failMode: "open"` means a dead judge switches the check off. The `unavailable` and
  `unparseable` outcomes must be on the dashboard.
- **Self-judging**: a model judging its own output is biased. Deploy-time note when the judge's model equals the
  task's.
- **Gaming the judge and oscillation**: every attempt is re-screened from the first guardrail (already so), and
  `deliver-best` keeps the best rather than the last.
- **A quality check overriding a safety check**: the reason E1b re-screens the delivered attempt.
- **Memory pollution**: rejected attempts stay out of conversation output, history, rolling summaries and the
  recall tool.
- **Two writers of attachments** (E6): the new writer needs the upload path's checks, or it becomes a way around them.
- **Provider churn**: video APIs change fast. One video provider in core at most; the rest via MCP.

---

## 9. Open decisions

1. **`expression` evaluator** (E1c): a new sandboxed parser, or the bounded `ExpressionProvider` grammar?
2. **The re-ask budget**: should `FormatRetryRunner` and `GuardrailService` share one per-task count (E3 residue)?
3. **Core media scope**: speech and image now, video only after E8? Recommended: yes.
4. **Self-continuation (E8)**: acceptable as a reserved action, with `maxContinuations` default 0?
5. **Single-agent "revise"** (E2): a new verdict value, or a `hitlConfig` policy on `REJECTED`?

Settled since the first version: output guardrails exist and E1 builds on them; the per-conversation cost ceiling
exists; judge feedback is closed-vocabulary only.

---

## 10. Order

| # | Item | Size | Depends on |
| --- | --- | --- | --- |
| 1 | E1 — `judge` guardrail and `onExhausted: deliver-best` | M | #1064, #1112 (shipped) |
| 2 | E1c — `expression` guardrail | S | E1; decision 9.1 |
| 3 | E4 — spend checks that see every attempt, reservations | S | #1058 (shipped) |
| 4 | E2 — humans and groups produce verdicts | S–M | E1 |
| 5 | E5 — checks on tool and HTTP steps; `forEach` | M | E1, E4 |
| 6 | E6 — media groundwork | L | multimodal-attachments plan |
| 7 | E7 — speech and image providers | M | E4, E6 |
| 8 | E8 — continuation and parked operations; then async video | M–L | — (video part: E6, E7) |
| 9 | E9 — DAG with fingerprinted steps | L | E5 |
| 10 | E10 — composition service | sibling project | E6 |

E1 is the highest-value next step: a new guardrail type and one new field inside a loop that already runs in
every execution mode, serving grounding, extraction, content quality and media alike.
