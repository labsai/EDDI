# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [November 2026](changelog/2026-11.md) | 45 | 193 KB |
| [October 2026](changelog/2026-10.md) | 267 | 1235 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1216 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🧪 test: raise branch coverage back over the merged 80% gate (2026-11-05)

**Repo:** EDDI (`test/raise-branch-coverage`)

### Why

After the last day's feature merges (cost accounting, team handoff, the SERVICE judge, HITL
edit-on-approve and approval-card context, setup-api, argument schemas, the configs/llm/secrets
fixes), the `Integration Tests` job on `main` failed the JaCoCo `merged-check`:
branches covered ratio 0.79 against a minimum of 0.80. The threshold stays where it is; this
branch adds tests.

### What changed

Test code only — no production source is touched. Plain JUnit 5 + Mockito, no `@QuarkusTest`.
Every test asserts behaviour (refusal messages, routing decisions, masking, audit units), not
just execution. Classes covered, grouped by the PR that introduced or changed them:

- **Argument schemas (#1268):** `ToolArgumentSchemaValidator` (keyword reduction, nullable type
  lists, the violation cap, violation wording), `OpenApiArgumentSchemas` (no direct test before),
  `McpApiToolBuilder` helpers (unsendable inputs, header-answering operations, server variables).
- **Team handoff (#1223):** `TeamHandoffToolsProvider` — the standalone filed ledger in step data,
  artifact references, unusable targets, participant rules.
- **Cost accounting (#1222):** `MeteredTokenUsage`, `PricingLint` (cascade, judge, summary and
  tool-summarizer pricing), `UnpricedSightings`.
- **HITL (#1228, #1230):** `HitlDecisionRecords`, `EditMetrics`, `ApprovalCategories`,
  `GroupApprovalContextRenderer`, `HitlNotificationContent`.
- **Cluster rate limits:** `ToolRateLimiter` in NATS mode — shared buckets across replicas, the
  global layer giving the conversation its token back, degraded local-share and reject.
- **Elsewhere:** `ArtifactSchemaComplexity`, `EmailAgentToolsInspector`, `EmailTargetRouting`,
  `EmailReplyThread`, `TurnBudgetValidator`, `TurnObservation`, `ResourceSharingService.describe`,
  `SpaceTenants`, `JsonBodyErrors`, `AlertPayloads`, `DeliveryTarget`, `OutboundSettings.fromConfig`,
  `AttachmentClassifier`, `WebhookEventParser`, `PropertyAccessValueResolver`, `IngestionSource`
  validation, `EmbeddingParameters`, `ChatCompletionRequest`.

### Measured

Unit suite only (integration tests need Docker): branch ratio **0.7793 → 0.7915**, +867
branches. The merged unit + IT ratio could not be measured locally; CI's `Integration Tests` job
is the check.

### Noticed, not changed

`JsonBodyErrors` renders an unknown field's JSON path including the field itself
(`Unknown field 'x' at x.`): it calls `StrictConfigurationParser.jsonPath(e)`, which renders the
whole path, where the private overload documents "one short of it for an unknown field". The new
test asserts only the prefix of the message, so it does not pin either form.

---

## ✨ feat(llm): lazy loading for HTTP, MCP and A2A tools (`lazySources`) (2026-11-04)

**Repo:** EDDI (`feat/llm-lazy-external-tools`)

### What changed and why

An operator agent whose tools are a 60–70 operation API sent every tool description on every model call: `toolLoadingStrategy: LAZY` only ever hid the built-in tools, because `AgentOrchestrator.buildToolSetup` built `discover_tools` from the built-ins and merged the http/mcp/a2a sources afterwards, in full.

- **`lazySources`** (`LlmConfiguration.Task`): any of `builtin` (default, the old behaviour), `http`, `mcp`, `a2a`. **`lazyRetention`**: `turn` (default) or `conversation`. Both are defaults, not switches: a stored config is unchanged.
- **`LazyToolCatalog`** (new, stateless): with an external source listed, the `discover_tools` name is reserved in assembly phase 1 (so no remote tool can take it) and completed after phase 2 into a *grouped* meta-tool — its description lists the groups with a one-line summary each; it takes `group` and/or `keywords`; results are ranked and capped by `maxToolsInContext`. The lazily-managed specs travel in `ToolSetup.builtInSpecs`, the component LAZY always activated from, so the live loop, resume and refusal share one list. An empty catalog removes the meta-tool again.
- **Groups**: `ToolGroup` on the tool SPI (`ToolContribution.toolGroups`, carried through `ToolSourceRegistry`). An http group is one apicalls configuration (resource id), summarised by the new optional **`ApiCallsConfiguration.description`**, which `McpApiToolBuilder` now fills from the OpenAPI tag (`"<tag>: <first line of the tag description>"`). An MCP group is one server (`name`, else host — never the URL).
- **Safety parity**: a call to a lazily-managed tool that is not loaded is answered `NOT_EXECUTED` ("call discover_tools first") *before* the approval gate — never executed, never shown to an approver (trace `tool_not_activated`, metric `eddi.llm.lazy_tools.refused`). Loaded tools go through the unchanged per-call pipeline. This also applies to the existing built-in-only LAZY mode, where a hidden built-in called directly used to run. Resume executes approved calls by name regardless of what is loaded.
- **Retention**: the loaded names are stored on the conversation step (`llm:lazyTools:activated:<taskId>`, not a client-visible key) when `discover_tools` activates, and re-applied at the start of every loop run (cascade steps, resume) and, with `conversation`, from the latest earlier step (capped at `maxToolsInContext`). Metric `eddi.llm.lazy_tools.activated`.

### Measured

Synthetic 66-tool HTTP agent, six apicalls groups of eleven, OpenAPI-generated shape (`LazyExternalToolsTest`, GPT-4o tokenizer, tool specs only): eager 66 specs / 40,626 chars / ~9,438 tokens per request; lazy first request 1 spec / 1,024 chars / ~258 tokens; after loading one group 12 specs / 7,795 chars / ~1,831 tokens. See [Lazy tool loading](langchain.md#lazy-tool-loading).

### Docs

`docs/langchain.md` (table rows + new "Lazy tool loading" section), `docs/httpcalls.md` (`description`). The Platform Operator's prompt and allow-list do not cover tool loading, so they are unchanged.

---

## 🐛 fix(cluster): a finished turn could take back the lease it had just handed on (2026-11-04)

**Repo:** EDDI (`fix/lease-handoff-test-flake`)

### What changed and why

`ClusterConversationCoordinatorTest.leaseIsHandedToTheNextQueuedTurn` failed intermittently in CI
(`one acquire for the whole queue ==> expected: <1> but was: <2>`). It was not the test: the local
lease handoff added in #1240 had a real race.

`releaseOrHandOver` parks the lease in `handoffs`, calls `submitNext` (whose `execute` takes it for the
next queued turn), and in its `finally` removes "its" parking with `handoffs.remove(conversationId, lease)`.
But a handed-over lease is the *same* `LeaseHandle` object for every turn in the chain. Once `submitNext`
has handed it on, turn N+1 can run to its end and park that same lease for turn N+2 before turn N's thread
reaches its `finally` — which then matched N+1's parking, released the lease, and left turn N+2 to acquire
a fresh one. In production that is the extra NATS round trips and the window for another node to take the
conversation that the handoff exists to avoid.

Each parking is now its own `Handoff` object (a private class with identity `equals`, deliberately not a
record), and the `finally` removes only the parking this call made.

Reproduced before the fix with the test repeated 1000 times: 13 failures. After it: 0 failures in 3000.

**Files:** [`ClusterConversationCoordinator.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/ClusterConversationCoordinator.java)

---

## ✨ feat(llm): structured answers through a terminal reply tool, `answerMode: "tool"` (2026-11-04)

**Repo:** EDDI (`feat/llm-answer-mode-tool`)

### What changed and why

A tool-using agent on Gemini, Anthropic or Bedrock cannot use native JSON mode (the providers reject it
next to `tools`), so its JSON answer was only held to the schema by the prompt block — and in
production 1.6–5.7% of such answers were replaced by the fallback. Function-call arguments, by
contrast, are enforced by every provider. The new opt-in task field `answerMode: "tool"` adds one
synthetic tool, `reply` (`answerToolName`), whose parameters are the `responseSchema`; the model
answers by calling it.

- **Loop** (`ToolLoopRunner`): reply calls are taken out of each model response before the call caps,
  the approval gate, quotas, rate limits, cache and cost tracking see it. A reply ends the loop with no
  further model call; its arguments become the answer text and run through the unchanged
  `convertToObject` path (parse, `responseSchema` / `nonBlankFields`, `onSchemaMismatch`,
  `postResponse`). Reply plus other calls: the others run first; if one pauses for approval the reply
  is discarded (not in the frozen transcript). Duplicate replies: the first wins, WARN once per
  conversation. Traced as step type `answer` without arguments; never a `tool_call`/`tool_result`
  event (those are emitted inside `executeToolCall`, which the reply never reaches — true for the
  current event code and for `feat/resume-stream-tool-events`).
- **Forced call** (`answerToolChoice`, default `required`): `ToolChoice.REQUIRED` on the request for
  `openai`, `azure-openai`, `anthropic`, `gemini`, `mistral` (verified in the langchain4j bindings);
  every other provider gets `auto`, logged at INFO at deploy. `maxToolIterations` still bounds the
  loop; a loop that spends it without a reply ends with the budget text, which goes to `onInvalidJson`.
- **Plain-text answer** goes through the existing invalid-JSON path (local repair → `onInvalidJson`);
  no parallel path.
- **Assembly** (`AgentOrchestrator.buildToolSetup`): the reply tool is added last, with no executor;
  a runtime tool of the same name is dropped with a WARN. The HITL resume, LAZY loading, cascade steps,
  streaming and the format re-ask (`reaskFinalAnswer`) all offer it.
- **Validation** (`AnswerToolConfigValidator`, called from `LlmStore` and at deploy): tool mode needs
  `convertToObject: "true"` and a convertible object-root `responseSchema`; the name must not collide
  with an engine tool or a tool the task names in its whitelist, rate limits, pricing, timeouts, cache
  scopes or quotas.
- **Prompt**: in tool mode the `RESPONSE FORMAT` block tells the model to call the tool instead of
  repeating the schema; no response format is sent on those requests.
- **Metric** `eddi.llm.answer_tool{outcome}`: `reply`, `text`, `duplicate`, `discarded_by_pause`,
  `no_reply`, `collision_dropped`.

### Design decisions

- The reply arguments are returned as the loop's answer *string*, so every downstream policy is the
  existing one — "tool" can never lose an answer "text" would have kept.
- The reply call is removed from the transcript rather than answered with a synthetic result, so a
  pause's frozen transcript and a cascade's carried exchange never hold an unanswered call.
- The "only tool" INFO is logged at first assembly per task, not at configure: httpcall/MCP tools come
  from the workflow, which `configure` cannot see.

**Files:** [`AnswerTool.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/AnswerTool.java),
[`AnswerToolConfigValidator.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/AnswerToolConfigValidator.java),
[`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`AgentOrchestrator.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java),
[`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`LlmConfiguration.java`](../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java),
[`LlmStore.java`](../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java),
[`langchain.md`](langchain.md#answering-through-a-reply-tool-answermode-tool),
[`llm-resilience.md`](llm-resilience.md).

**Review follow-ups.** A `modelCascade` on a `tool`-mode task with no other tool ran every step on the
legacy text path, because the cascade's tool-loop gate was `task.isAgentMode()` alone, so the reply tool
was never offered. `CascadingModelExecutor` now also enters the tool loop when `answerMode` is `tool`.
`enableInAgentMode` still gates a task with other tools, because `LlmTask` skips the cascade for those
before this point. A blank `answerToolName` is now saved as the default `reply`, which is how the runtime
already read it; before, it was refused with a 400. `langchain.md` now warns that a tool-mode turn adds the
reply JSON to the output unless `addToOutput` is `"false"`, and tells authors to set that when a
`postResponse` template renders the answer.

---

## ✨ feat(httpcalls): validate LLM tool arguments against an argument schema before the approval gate (2026-11-04)

**Repo:** EDDI (`feat/httpcall-argument-schema`)

### What changed and why

Generated OpenAPI tools filled a whole-body template from string parameters, so a malformed call
(missing required field, wrong type, misspelled field, value outside an enum) reached the human
approval card and then failed at the API — or, when the API ignores unknown fields, silently did
nothing after a human had approved it.

- **`ApiCall.argumentSchema`** (new, optional) — JSON Schema of the tool arguments. `null` keeps
  string parameters and today's rendering byte for byte. **`ApiCall.unknownFields`**
  (`reject` | `drop` | `allow`, default `reject` when a schema is set; save-time validated).
- **`McpApiToolBuilder`** generates the schema per operation (new `OpenApiArgumentSchemas`): scalar
  path/query parameters typed, the body schema with every component `$ref` resolved (`allOf` of
  objects merged, recursion and `anyOf`/`oneOf` left untyped, `readOnly` fields omitted).
- **`HttpCallToolsProvider`** types the tool parameters from the schema (via the new
  `ResponseSchemaConverter.toElement`; a parameter with no tool form, including a property-less object
  Gemini would reject, stays a string). With a schema, arguments are normalised (a JSON string for an
  object/array parameter is decoded; `drop` removes undeclared fields) and every non-string value is
  rendered as compact JSON — never a Java `toString` — and substituted as data, as before.
- **`ToolLoopRunner.refuseInvalidArguments`** runs before `ToolApprovalGate.classify`: every executor
  implementing the new SPI `ToolArgumentValidator` is asked; a violating call gets a `NOT_EXECUTED`
  envelope with one line per violation (`$.requestBody.emails: required`,
  `$.requestBody.role: must be one of manager, curator`, `… unknown field (declared fields: …)`), a
  `tool_error` trace step with `error: "argument_schema"`, and `eddi.tool.arguments.refused`. It is
  never paused or executed. The httpcall executor re-checks before sending, which covers resumed calls.
- **`ToolArgumentSchemaValidator`** (`modules/llm/guardrails`) — pure function, uses the
  networknt validator already on the classpath on an effective schema reduced to a safe subset
  (no `$ref` loading, no `pattern`); built so it can become a tool-call guardrail type later.

Docs: [`httpcalls.md`](httpcalls.md#argument-schema-for-llm-tools),
[`langchain.md`](langchain.md#argument-schema-refusals).

**Files:** [`ApiCall.java`](../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCall.java),
[`ToolArgumentSchemaValidator.java`](../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolArgumentSchemaValidator.java),
[`ToolArgumentValidator.java`](../src/main/java/ai/labs/eddi/modules/llm/tools/spi/ToolArgumentValidator.java),
[`OpenApiArgumentSchemas.java`](../src/main/java/ai/labs/eddi/engine/mcp/OpenApiArgumentSchemas.java),
`McpApiToolBuilder.java`, `HttpCallToolsProvider.java`, `ToolLoopRunner.java`,
`ResponseSchemaConverter.java`, `ApiCallsConfigurationValidator.java`.

### Design decisions

- Rendering moved to the tool-argument boundary (`HttpCallToolsProvider.templateDataFor`, shared by
  the gate-time resolver and the executor) rather than `ApiCallExecutor`: the executor keeps receiving
  strings, so the fingerprint pinned at gate time and the request built at execution agree.
- `drop` is applied when the request is built, not by rewriting the model's call: the transcript and
  the approval card keep the call as the model wrote it.
- Top-level parameters stay all-required as before (an omitted query value would render `?name=`);
  requiredness inside the body follows the spec.

---

## ✨ feat(llm): rescue near-miss JSON replies without a second model call (2026-11-04)

**Repo:** EDDI (`feat/llm-json-near-miss-rescue`)

### What changed and why

A tool-using agent on Gemini, Anthropic or Bedrock gets no native JSON mode, and in production about 2-6% of the turns of such agents were replaced by the fallback answer although the model had written a good one: plain HTML/text with no JSON wrapper (most often non-Latin scripts), double-escaped JSON, a misspelled field name, or `{}`. `onInvalidJson: "retry"` helps but costs a second model call. Three opt-in local repairs now cover the first three shapes, in the shared parse path (`ModelOutputParser`), so the live turn, the HITL resume, the format-retry runner's detection, the circuit breaker's view and every cascade step see the same reply:

- **`responseValidation.onInvalidJson: "wrap"`** with required `wrapField`, optional `wrapDefaults` and `wrapMaxChars` (default 20,000): a non-JSON, non-blank reply becomes `{ "<wrapField>": "<text>" }` (plus the defaults) and is validated like any parsed reply. Text starting with `{` or `[` is broken JSON and is never wrapped (`fallbackAction` applies). New parse outcome `wrapped` (`llm:output:outcome:*`, `eddi.llm.output{outcome=wrapped}`), `eddi.llm.recovery{action=wrap}` and a `LlmRecoveryLog` line.
- **Double-escape repair** (`{\"a\": \"b\"}`): one-level unescape, on whenever `onInvalidJson` is `wrap` or `retry`; accepted only as a JSON object and never tried on a reply that already parsed.
- **Task `fieldAliases`** (`{"htmlResponseStr": "htmlResponseText"}`): renames a top-level key when the target is absent (the target wins when both exist).
- `{}` stays covered by `nonBlankFields` plus `onSchemaMismatch: "retry"`; documented as the recommended pair.

Save-time validation in `LlmStore`: `wrap` without `wrapField` is a 400 naming what to set; `wrapField`, alias names and `wrapDefaults` keys must be plain top-level names. A config without the new fields behaves exactly as before.

### Design decisions

- The options are resolved once into `RescueOptions.of(task)` and passed through the existing `parse` / `Policy.from` / `classifyJsonReply` / `convertResponseToObject` overloads (the old signatures delegate with `RescueOptions.NONE`), so a later caller that routes a plain-text answer through the invalid-JSON handling (the `answerMode: "tool"` work) gets the rescue for free.
- Wrapping and the double-escape repair follow `responseValidation.enabled` like every other policy; `fieldAliases` is independent of it, like `nonBlankFields`.
- Plan deviation: `wrap` and `retry` are alternatives for the single `onInvalidJson` action. A reply `wrap` cannot rescue (broken JSON, over the cap) is handled by `fallbackAction` (as after spent retries), not by a second policy.
- Platform Operator: no change needed (it does not teach per-task response-validation fields).

**Files:** [`ModelOutputParser.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java), [`RescueOptions.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/RescueOptions.java), [`FormatRetryRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LlmConfiguration.java`](../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`LlmStore.java`](../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java), [`langchain.md`](langchain.md), [`llm-resilience.md`](llm-resilience.md)

---

## ✨ feat(setup-api): per-turn auth from secret context, generate-only, and re-sync (2026-11-04)

**Repo:** EDDI (`feat/setup-api-context-auth-sync`)

### What changed and why

An integrator could not build a per-user-token operator agent with EDDI's own OpenAPI converter, nor update one from a regenerated spec. Three additions, usable independently:

1. **`${context:<key>}` in httpcall headers and in `apiAuth`.** Resolved per request from the turn's context entry of that key — only when the client marked it `"secret": true`, only in a header, only as a lone reference after an optional scheme, only when written in the configured header itself, and only towards the origin of the httpcalls configuration's `targetServerUrl`. A missing value, an empty one, or the `<secret context>` placeholder a resumed turn holds when the client did not re-supply it fails the call — never `Bearer ` with nothing. The plaintext joins the executor's redaction set (request record, approval preview, error bodies, logs) and disables redirects. Read from the step datum (`context:<key>`), which holds the live value during the turn and after a HITL resume re-injects it (`Conversation.injectResumeSecretContext`). A Qute pass-through resolver for the `context` namespace keeps the reference intact through templating. Metric `eddi.httpcalls.context.reference{outcome}`.
2. **`"dryRun": true` on `POST /administration/agents/setup-api`.** Runs the whole setup code path with every write skipped (`ResourceWriter`): no store, vault, descriptor, grant or deploy. Returns `200` with the apicalls configurations, tool names, LLM configuration, workflow and agent (gate included). A plaintext `apiKey`/`apiAuth` is shown as a placeholder. Hidden from the OpenAPI schema so the Platform Operator's generated `setup-api` tool cannot carry it.
3. **`POST /administration/agents/{agentId}/setup-api/sync`.** Regenerates only the apicalls configurations stamped with the new `ApiCallsConfiguration.openApiOrigin` marker (now set by every setup), rewrites the LLM task's `tools` list and the generated API summary in its prompt, keeps everything else, and saves the agent as a compatible version (`updateAgent(..., compatible=true)`), then deploys unless `deploy: false`. Reports added/changed/removed tools; refuses with 400 and nothing written when a removed tool is still named by `toolQuotas`, a `toolApprovals` exemption (agent or task) or `toolResultGuardrails.exemptTools`, listing each. The request's `requireApproval` patterns are unioned into the stored gate; nothing is removed and request exemptions are ignored. An agent without marked configurations (created before this change) is refused with how to add the marker. Supports `dryRun`.

### Findings

- The existing `Bearer {context.userToken}` Qute form does **not** leak into persisted memory: an `Authorization` header is name-redacted in the request record, and the turn-end scrub (`Conversation.scrubSecretContextValues`) removes the value (8+ characters) from every step datum, property, pending tool-call batch and audit entry. It is weaker in-turn: it renders anywhere (path, query, body), towards any host, follows redirects, sends `Bearer ` when missing, and an API error body echoing the token reaches the model's tool result only pattern-redacted. Unchanged; the reference is documented as the recommended form.

- Review follow-up: a dry run that names an existing vault key (`vaultKeyName`) together with a plaintext `apiKey` can't tell reuse from creation without vault metadata, so it reports the destination-host rule a new key would fail in `resources.warnings` instead of refusing the request; the live setup checks hosts only when it creates the key.

### Not done

- `toolDisplay` reference checks: the field is not on `main` yet (`feat/resume-stream-tool-events`); sync preserves it (the LLM task is copied whole) but cannot check it for dangling references.

**Files:** [`SecretContextReferences.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/SecretContextReferences.java), [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`AgentSetupService.java`](../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`ApiAgentSyncService.java`](../src/main/java/ai/labs/eddi/engine/setup/ApiAgentSyncService.java), [`ApiCallsConfiguration.java`](../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCallsConfiguration.java); docs [`httpcalls.md`](httpcalls.md), [`architecture.md`](architecture.md), [`rest-api-reference.md`](rest-api-reference.md), [`secrets-vault.md`](secrets-vault.md).

---

## 🛡️ fix(groups, llm): fenced peer text in group prompts, head-and-tail tool truncation, identifiers kept in summaries, retry jitter (2026-11-04)

**Repo:** EDDI (`fix/tier1-prompt-hygiene-runtime`)

Four small changes that came out of comparing EDDI with OpenClaw's group-chat and agent runtime.
Each one is local to one or two files.

### 1. Peer text in group prompts is fenced, cleaned and bounded

The preset discussion templates quoted other participants as `— {entry.speaker}: "{entry.content}"`.
A peer is not necessarily trustworthy (a dynamic or recruited agent, a human member, or a model
talked into something), and its text could close the quotation and carry on as if it were the
template giving instructions. The text could also be any size.

- **New `PeerContentGuard`** ([`PeerContentGuard.java`](../src/main/java/ai/labs/eddi/engine/internal/groups/PeerContentGuard.java)),
  applied once in `GroupContextBuilder.buildPhaseInput` to the template data of every phase type,
  custom `inputTemplate`s included. It breaks any ` ``` ` inside quoted text with a zero-width space,
  reduces a speaker name to one line of at most 120 characters, cuts one entry at 12,000 characters
  with a note, and drops the oldest entries of a list once it passes 96,000 characters, leaving one
  `System` note in their place. The newest entry is always kept. The stored transcript is unchanged.
- **The 14 presets that quote peers** ([`DiscussionStylePresets.java`](../src/main/java/ai/labs/eddi/configs/groups/model/DiscussionStylePresets.java))
  now put that text between ` ``` ` fences, including `targetResponse` and `originalResponse`, and
  open with `QUOTED_CONTENT_NOTICE`, which tells the member that fenced text is quoted material to
  weigh and not instructions to follow.
- The participant names spliced into the instructions themselves (`targetName` in "You are reviewing
  {targetName}'s…", the speaker's own `displayName`) get the same one-line treatment, and the list
  budget counts every rendered string of an entry (names and phase name too), not only its content.
- The Manager's verbatim copy of the arbitration prompt (`NEGOTIATION_ARBITRATION_TEMPLATE` in
  [`hitl-config.ts`](../ui/manager/src/lib/hitl-config.ts)) follows the new preset; a Vitest
  compares the two.
- The user's own `question` is not treated as peer text.
- **Not covered yet:** the task-force templates (`TEMPLATE_EXECUTE`, `TEMPLATE_VERIFY`), which
  `TaskForceEngine` renders from its own data. They quote task results the same unfenced way.

### 2. Tool results over the limit keep their start and their end

`ToolResponseTruncator` cut an over-limit result after its first `maxChars` characters, which drops
exactly what a model most needs from a failed command or API call: the exit code, the error and the
totals at the end.

- **New strategy `headTail`**, the default when `toolResponseLimits.truncationStrategy` is unset. It
  keeps 70% of the limit from the start and 30% from the end, with a `[TRUNCATED: …]` note in place
  of the middle. The kept content is still exactly the limit, and neither cut splits a surrogate pair.
- `truncate` (start only) is unchanged when set explicitly, and is still what an unknown strategy
  name and the `paginate`/`summarize` fallbacks use.
- `toolResponseLimits` had no documentation, so it now has its own section in
  [`langchain.md`](langchain.md#tool-response-limits).

### 3. Summaries keep identifiers exactly as written

The rolling conversation summary's default prompt ([`ConversationSummarizer.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java))
gained a seventh rule: copy every opaque identifier (IDs, UUIDs, hashes, order and ticket numbers,
URLs, hostnames, IPs, ports, file names and paths) exactly as written. The group context-window
summary (`GroupContextBuilder.WINDOW_SUMMARY_INSTRUCTIONS`) got the same line. A custom
`summarizationPrompt` replaces the whole default, so it gets none of this; the docs now say so.

### 4. Retry backoff has jitter

`RetryConfiguration` slept exactly `backoffDelayMs × multiplier^n`, so every conversation that hit
the same provider outage retried in lockstep and arrived in one burst again.

- **New field `jitterRatio`**, default `0.2`: each configured backoff is shortened by a random share
  of up to that fraction (a 1000 ms backoff sleeps 800–1000 ms). Applied in both `executeWithRetry`
  and the public `backoff(...)` used by the streaming path.
- **Downwards only**, so a jittered sleep never exceeds `maxBackoffDelayMs`, the 60-second total
  backoff budget or the turn deadline. A provider-requested wait (`honorRetryAfter`) is never
  shortened. `0` turns it off; values outside `[0, 1]` are clamped and `NaN` counts as `0`.
- The Manager's `retry` type gained the optional field ([`types.ts`](../ui/manager/src/components/editors/llm/types.ts)).

### Behaviour changes operators may notice

- A tool result over its limit now shows its last 30% instead of more of its start. Set
  `"truncationStrategy": "truncate"` to keep the old output.
- Retry sleeps are up to 20% shorter and no longer identical across conversations.
- Group member prompts show peer text in fences, with a one-line notice at the top.

### Tests

New `PeerContentGuardTest`, including a real Qute render in which a peer's attempt to close the
fence stays inside it, and a check that every peer-quoting preset carries the notice. Also new
head-and-tail cases in `ToolResponseTruncatorExtendedTest`, jitter cases in
`RetryConfigurationTest`, and an identifier-rule case in `ConversationSummarizerTest`. Existing
retry tests that assert exact sleep values or exact budget arithmetic
(`RetryConfigurationTest`, `RetryConfigurationDeadlineTest`, `StreamingLegacyChatExecutorRetryTest`,
`ToolExchangeIdsAndProvenanceTest`) now set `jitterRatio` to `0`: jitter only shortens a sleep, so
with it on, one more retry fits inside the same budget.

---

## 🔒 fix(engine): agent-findings sweep — security, runtime, HITL, GDPR and cluster (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-engine-security-runtime`)

### What changed and why

Verified the 55 CodeRabbit agent findings filed against `engine/{security,runtime,hitl,setup,tenancy,schedule,triggermanagement,cluster,gdpr}`
against current `main`: 15 fixed here, 25 already fixed by earlier PRs (#1187, #1197, #1198), 4 accepted risks
(documented behaviour or operator-only configuration), 11 left for a decision (contract or cross-area changes, one already on
the unmerged #1234). Plus the CodeRabbit outside-diff finding on #1208.

- **NATS TLS** — a `tls://` / `wss://` URL turned TLS on inside jnats with `SSLContext.getDefault()`, skipping the host-name
  check that `eddi.nats.tls.enabled` installs; the verifying context is now built for those URLs too. The
  `createSocket(Socket, InputStream, boolean)` overload of `HostnameVerifyingSslContext` now returns a verifying socket like
  every other overload. Review follow-up: without an EDDI trust or key store the JVM default context is the base (a
  `javax.net.ssl.keyStore` client certificate still goes out for mTLS), and with host-name verification on the node dials
  the host name (`HappyEyeballs`) — jnats' default resolution dialled the resolved IP, so the check compared the
  certificate with the address, not the name in `eddi.nats.url`.
- **HITL** — the batch timeout policy no longer treats a rule shadowed by an earlier rule with the same match as speaking for
  a call (a policy-less task rule could let another call's `AUTO_APPROVE` decide the whole batch). The group pending-approval
  retention sweep cancels only the pause it found expired (`cancelPausedDiscussion` with the pause id), not a discussion an
  approval resumed in between. The self-conversation check sees JSON-escaped ids. The self-ungating guard refuses a patch
  whose earlier add/remove shifts a gated task's index, and an MCP `update_resource` that replaces a gated LLM document.
- **Sharing** — revoking the canonical subject `describe()` returned no longer re-encodes it (a `%`/`|` in a name made the
  revoke match nothing and report success); the share dialog's grant list follows the same structural disclosure rule as
  descriptor responses when workspaces are not enforced.
- **GDPR** — a failed dead-letter, journal, descriptor or checkpoint delete keeps the conversation snapshots for the retry,
  as a failed attachment or vault sweep already did.
- **Runtime** — the secret-context scrub leaves spend and token-usage records alone (a secret equal to the turn's cost
  erased its metering); a later workflow no longer puts back input a `scope: secret` instruction scrubbed; Dream keeps global
  and reader-less group memories apart; a 3-segment DELETE marks a descriptor deleted only when it is that descriptor's own
  resource (a `/spacestore/variables/{key}` delete could hide another owner's configuration).
- **Unauthenticated guard** — the side-effecting managed-conversation GET is recognised under a `quarkus.http.root-path`.

### Decisions left open

Conversation review after a compatible update, sharing CAS, setup-api `apiAuth` allowedHosts doc/code mismatch, checkpoint
writes racing an erasure, journal tombstone retention, tool-pause narration vs output guardrails, team-cadence mutation by a
former member, cascade ids of non-viewable nodes, team-share notifications to former members — listed in the batch report.

**Files:** [`NatsConnectionManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java),
`HostnameVerifyingSslContext.java` (since replaced by `ServerNameTrustManager`),
[`ConversationHitlService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationHitlService.java),
[`HitlCrashRecoveryObserver.java`](../src/main/java/ai/labs/eddi/engine/hitl/HitlCrashRecoveryObserver.java),
[`ApprovedCallScope.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/ApprovedCallScope.java),
[`SelfUngatingGuard.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/SelfUngatingGuard.java),
[`RestResourceSharing.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/rest/RestResourceSharing.java),
[`ResourceSharingService.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceSharingService.java),
[`ResourceAccessGuard.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/ResourceAccessGuard.java),
[`UnauthenticatedRequestGuard.java`](../src/main/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuard.java),
[`GdprComplianceService.java`](../src/main/java/ai/labs/eddi/engine/gdpr/GdprComplianceService.java),
[`Conversation.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/Conversation.java),
[`DreamService.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/DreamService.java),
[`DocumentDescriptorFilter.java`](../src/main/java/ai/labs/eddi/engine/runtime/rest/interceptors/DocumentDescriptorFilter.java),
[`gdpr-compliance.md`](gdpr-compliance.md)

---

## 🐛 fix(integrations): close six channel findings from the agent-findings review (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-integrations`)

### What changed and why

A batch of 23 scanner findings against `integrations/{channels,email,slack,webhook}` was checked against the
current code. Most had been closed since the scans (#1126, #1187, #1175) or are documented limitations; six
were real and are fixed here:

- **Slack bot token, composite references.** The `allowedHosts` check on a Slack `botToken` parsed only the
  first `${vault:…}` in the value, while the resolver expands all of them. Every reference is now checked
  (`ChannelTargetRouter.botTokenMayGoToSlack`, which the legacy connector path shares).
- **Slack approval card without a known pause.** A card built before its pause could be read was recorded with
  no pause identity and matched any pause that began before the record was written — so a delayed card could
  approve a later pause it does not show. Such a card is now posted without buttons and not recorded, and a
  record without a pause epoch matches nothing (`ISlackApprovalRecordStore.matchesPause`, `notifyApprovers`,
  the group listener). The card says why it has no buttons; for a conversation, its own user writing in the
  thread again posts a card with buttons, and a group pause is decided through REST/MCP ([hitl.md](hitl.md)).
- **Slack pause notice escaping.** The in-thread pause notice appended the pause reason as raw mrkdwn; it is
  escaped like the approval card's reason.
- **Email, duplicate Message-ID.** A mail from another sender carrying the Message-ID of a mail waiting for its
  retry took over that mail's ledger row and the conversation it names. It now waits, and is settled as a
  duplicate once the real mail has run (`EmailInboundProcessor`).
- **Email, group reply retries.** A cached group reply was retried to its stored recipient without the recipient
  rules being applied again, so turning `honorReplyTo` off did not stop a pending reply to a `Reply-To`. The
  retry now decides again, as every other reply kind does ([email-integration.md](email-integration.md)
  already promised this), and renders the mail again under the row's Message-ID, so a changed `address` is
  its `From` as well as the envelope sender.
- **Legacy Slack connector `groupId`.** A `channels[].config.groupId` on an agent became a standing route into
  that group once deployed, with no check that the deployer may use it. `deployAgent` now requires USE on it,
  the rule the channel API applies to a group target ([slack-integration.md](slack-integration.md)). The
  check fails closed: when the agent's configuration cannot be read, the deploy is refused with a retryable 503
  instead of going ahead unchecked (it used to proceed on a store outage).

Verdicts: 6 fixed, 7 already fixed, 1 false positive, 4 accepted risk, 5 need a decision (Slack approval records
keyed by the mutable integration name; editor-declared Slack `teamId` as the identity namespace; space
membership for channel credential references; IDNA deviation characters in the notify recipient policy;
discovery records during a partial email GDPR erasure).

**Files:** [`ChannelTargetRouter.java`](../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`SlackEventHandler.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackGroupDiscussionListener.java`](../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java),
[`ISlackApprovalRecordStore.java`](../src/main/java/ai/labs/eddi/integrations/slack/hitl/ISlackApprovalRecordStore.java),
[`EmailInboundProcessor.java`](../src/main/java/ai/labs/eddi/integrations/email/EmailInboundProcessor.java),
[`EmailReplySender.java`](../src/main/java/ai/labs/eddi/integrations/email/reply/EmailReplySender.java),
[`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java)

---

## 🛡️ fix(modules): CodeRabbit agent findings for ingestion, nlp, templating and apicalls (2026-11-03)

**Repo:** EDDI (`fix/agent-findings-modules-misc`)

The 43 findings of the CodeRabbit agent-findings export that touch `modules/{ingestion,apicalls,nlp,properties,rag,rules,templating}`
were each traced through the current code. Most were already closed by #1173, #1175, #1186 and #1199 since the scans ran; six
needed a change here, and seven are left for a decision.

### What changed and why

- **Quadratic regex in the `str:fmt` size estimate** (`BoundedFormat`). `%` followed by a long run of `0` could be read as a flag
  or as the start of the width, and the regex tried every split of the run, so the estimate that exists to protect the render
  took time quadratic in the format's length before any limit applied. The width now cannot start with `0`, which is how
  `String.format` reads it too.
- **HTML list items were not charged to the conversion budget** (`HtmlToMarkdownConverter`). A list item is written by
  `appendList` without passing through `convertElement`, so a page of empty `<li>` elements escaped the element allowance.
  Each item is now counted and the list stops when the allowance is spent.
- **Run ownership is checked fail-closed before vectors are published** (`IngestionPipeline`). A state store that could not
  answer used to count as "still owned", so a worker that had been revoked while its embedding call was in flight could publish
  text of a source that had just been removed. The check made before a write now refuses when it cannot be verified; the
  document is not recorded and the next run takes it. The periodic progress check keeps working through a store outage.
- **The crawl frontier is bounded in characters** (`WebCrawler`). The count cap (100,000) did not bound size: a short relative
  link inherits its whole base URL, and both the canonical id and the fetch URL are retained, so one page could pin about 1.6 GB.
  Link discovery now stops admitting URLs after 64 million characters of URL text.
- **PDF pre-filter copied one dictionary per `stream` keyword** (`PdfStreamBudget`). A file that is mostly the word `stream`
  copied up to 64 KB for every occurrence. A later keyword in an object whose dictionary was already read is skipped.
- **Merged-terms correction shares one time budget across its retries** (`MergedTermsCorrection`). Every prefix and suffix lookup
  had its own regex deadline and counted expiry as a miss, so a word cost up to 200 deadlines. The word is now abandoned when
  one `RegEx.MATCH_BUDGET_NANOS` has passed.

### Findings left for a decision

Not changed, because a fix changes a contract or needs a design: a credential-use check for standalone RAG ingestion, a
durable retry for removed-source vector cleanup, the `tenantId` conversation property choosing the auto-vault tenant,
filtering `capabilityMatch` by the caller's USE access, a per-render allocation budget for Qute loops, structural escaping of
values substituted into quick-reply expressions, and provenance tracking so a normalized secret input scrubs its original form.

### Verdicts

43 findings: 6 fixed, 26 already fixed, 1 false positive, 3 accepted risk, 7 left for a decision.

---

## ✨ feat(llm): agent operations tools — team scorecard, budget proposal, follow-up scheduling (2026-11-03)

**Repo:** EDDI (`feat/agent-ops-tools`, branched from the local `feat/operator-mcp-integration`, which merges the still-open PRs #1222–#1231)

### What changed and why

A standing team could not read its own spend or approval numbers, propose a budget move, or book its own check-back,
so a person pasted metrics and moved money by hand. Three built-in tools now do that for an agent, each **off unless
named in `builtInToolsWhitelist`** (an omitted whitelist does not admit them), see [Agent Operations Tools](agent-ops-tools.md):

- `read_team_scorecard` (slug `team_scorecard`) — read-only: month spend and budget left, tasks done/failed per UTC
  week, approval outcomes with reason codes, retro lessons, for the agent's own group, its `oversight.teams` and
  `agentOps.scorecard.groupIds`. Each team is read only if the conversation's user may VIEW it (`ResourceAccessGuard`,
  the REST rule), checked per call and failing closed; no `userId` parameter.
- `propose_team_budget_raise` / `propose_team_budget_transfer` (slug `propose_team_budget`) — assembled only when the
  task's effective `toolApprovals` requires approval for both tools, so the existing approval card (Manager, Slack,
  e-mail, MCP) is the human gate. The body re-checks that the approving caller is an `eddi-admin` and refuses
  otherwise (owner/`eddi-approver`, timeout auto-approval and autonomy exemptions apply nothing). Applies through
  `TeamBudgetService`; per-turn, per-day (counted from the adjustment history) and amount caps; idempotent.
- `schedule_followup` / `list_followups` / `cancel_followup` (slug `schedule_followup`) — one-shot schedules for the
  agent's own conversation or a fresh one; horizon ≤ 90 days, per-agent pending and per-turn caps, a mandatory
  `maxCostPerFire`, never admin-powered, an agent sees and cancels only its own.

New `agentOps` block on an LLM task (limits only, validated at save time). `AgentOpsToolsProvider` is a CDI bean
wired into `AgentOrchestrator`. `RetroEngine.RETRO_SOURCE` became public. Manager: an **Agent Operations Tools**
section in the LLM editor, strings in all 11 locales. Platform Operator: the team-budget guidance now covers the
tools (revision bump).

### Design decisions

- Gate-based approval rather than a new pending-proposal store: reuses the approval card, Slack channel, audit and
  crash recovery, at the cost of the approver having to be an admin for the change to apply.
- The budget tool refuses to exist without a gate instead of trusting the designer to add one.

### Tests

Plain JUnit 5 + Mockito for the three tools, the provider and the validator; Vitest for the Manager helpers.

### Review follow-ups (PR #1259)

- `schedule_followup` reserves its per-turn slot only when a booking is about to be written and releases it on a
  pending-cap refusal or store failure, so a malformed call no longer locks the model out for the turn.
- `pending()` pages through all of the agent's schedules (bounded at 40 pages of 500) before filtering, so a
  check-back past the first page still counts, lists and cancels.
- The scorecard's approval window now starts at midnight UTC on the Monday of the oldest throughput week shown.
- Manager: unticking the last whitelisted tool switches built-ins off instead of leaving an empty whitelist that
  means "all built-in tools"; the limit blocks use the logical `ms-5` for RTL.
- Docs: e-mail is a notification, not an approval channel; the task-level `toolApprovals` shape is shown; a group
  member's gated call is auto-rejected, so budget proposals belong to a standalone oversight agent; `maxPending` is
  per agent and booking user. The Platform Operator prompt says the same about group turns and `maxPending`
  (revision 55). Inline Mockito FQNs in two tests replaced by static imports.

---

## 💰 feat(cost): per-run tool quotas and an opt-in hard tenant ceiling (2026-11-03)

**Repo:** EDDI (`feat/cost-hardening`, branched from the local `feat/operator-mcp-integration` because the price resolver
and catalog PRs were still open)

### What changed and why

Two gaps in cost control, found while planning to run EDDI on EDDI inside a fixed monthly budget.

**1. `toolQuotas` `window: "RUN"`.** Tool quotas only had fixed UTC windows (`PT1M`..`P31D`), so "20 searches per scout
per run" had to be approximated with a daily cap. A rule may now say `"window": "RUN"`: the counter belongs to one run
and the next run starts at zero. The run is a scheduled fire (new context key `scheduleFireId`, set by
`ScheduleFireExecutor` on the startup turn and on every fire turn, so a persistent conversation counts per fire), else a
group conversation (`groupConversationId`, already set on every member turn), else the conversation. Mechanics:
`ToolQuotaRules.Resolved.perRun()`, the run id hashed into the counter name by `ToolQuotaService.keyFor` with window
start `0` (no schema change in either store), rows expire 31 days after the first call. Validated at save time
(`ToolQuotaConfigValidator`): `RUN` is case-insensitive, a pool cannot mix `RUN` with a time window. The refusal names
the per-run limit and no reset time. `docs/langchain.md` gets a "Per-run caps" section.

**2. `eddi.tenant.quota.cost-reservation.enabled` (default `false`).** `maxMonthlyCostUsd` was only charged after each
settled turn, so the crossing turn finished and overshot, and concurrent turns all read the same remaining budget. With
the mode on, an LLM task's model phase and each priced tool call *reserve their maximum cost* against the tenant counter
before running, and are refused when it does not fit.

- `ITenantQuotaStore.tryReserveCost` / `releaseCost`: one atomic conditional write per backend (MongoDB: `$expr` filter
  plus pipeline update on `tenant_usage`; PostgreSQL: one `UPDATE ... WHERE cost + held + amount <= limit`, two new
  columns added with `ADD COLUMN IF NOT EXISTS`; in-memory under the tenant lock). Unreleased holds (dead node) are
  dropped by an epoch (`reservedSince`) older than `eddi.tenant.quota.cost-reservation.ttl` (`PT10M`); a release names
  its epoch, so a late release cannot cancel somebody else's hold.
- `TenantQuotaService.reserveCost` / `holdForCall` / `shrinkHolds` / `releaseHolds`; fails closed (a store that cannot
  answer refuses, counted on `eddi_tenant_quota_unavailable_total{type="cost"}`). Metrics
  `eddi_tenant_cost_reservation_total{outcome}` and `eddi_tenant_cost_reservation_usd_total{direction}`.
- `ModelCallBound` sizes the model phase: prompt (three characters per token) x input price + output-token cap x output
  price, over `1 + maxToolIterations` rounds for an agent-mode task (later rounds carry the tool-context cap) and every
  step of a cascade. Unpriced models reserve $0, like metering.
- Holds live on the turn (`IConversationMemory.getTenantCostHolds`), are shrunk to the actual cost when each LLM task
  finishes (`LlmSpendGate.settleHolds`) and are released in `TurnCostMeter.meterSettledStep` *after* the charge, so spend
  is always counted as spent before it stops being counted as held.
- Wired into `LlmTask` (before the model phase, and when a paused tool loop resumes) and `ToolLoopRunner` (priced tool calls, price from the new
  `ToolCostTracker.priceOf`).

**3. Manager UI.** The LLM task editor has a new "Tool quotas" section (`task-tool-quotas.tsx`): subject template,
per-tool rules with limit, window (per run, hour, day, 7 days, 31 days, or a stored custom duration kept as-is) and pool,
plus the note that a per-run cap is not an abuse limit. Nothing is written until a rule is added. The Quotas page shows the
budget running turns hold (`reservedUsd`, new on `UsageSnapshot` and filled by all three stores) as a second segment on
the monthly-cost bar, and explains the opt-in startup property next to the cost limit. 16 new i18n keys in all 11 locales.
A Grafana panel (`Tenant cost reservations (hard ceiling)`) charts the new meters.

### Design decisions

- A deployment property, not a per-tenant field: it needs no change to the tenant quota record, REST shape or its two
  stores' document layout, and a cluster agrees on it by construction.
- A reservation is held until the turn is charged, not until the call ends: the actual cost only reaches the counter at
  settle, so releasing on call end would leave a window where the spend is counted nowhere.
- The small paid side calls (re-ask, judge, guardrail classifier, summaries, Dream) are **not** reserved; they keep the
  read-only gate and are charged at settle. The mode is a hard ceiling for the calls that carry the cost, not bit-exact
  (documented in `docs/tenant-quotas.md`).
- The `RUN` id comes from runtime context, so it bounds an autonomous run, not a hostile end user (documented); fixed
  windows remain the tool for per-user fair use.
- Platform Operator: system prompt teaches both (revision 54, fingerprint `00f0946c7e085db5`; 55 after the review follow-ups); the allow-list is
  unchanged (the property is startup configuration, the `RUN` rule is part of the agent document).

### Not done / next

- Anthropic Batch API support was explicitly out of scope.
- Store ITs (`TenantQuotaStoreParityTest`, Mongo and PostgreSQL) are written; they need Docker.
- Not reserved: embeddings (unpriced anyway), the side calls above and the HITL REVISE model call, which keep the
  read-only gate. A resumed tool loop does reserve (`ModelCallBound.resumeMaxUsd`).

### Review follow-ups (2026-11-05)

- Hold expiry is by the **newest** hold, not the shared epoch: a busy tenant's overlapping turns kept the held total
  above zero past the TTL and the next reservation dropped holds of calls still running. New `reservedLastAt` /
  `reserved_last_at` (added by `ALTER ... IF NOT EXISTS`) in all three stores; a dead node's hold on a continuously busy
  tenant now waits for a quiet TTL (fails closed). Parity test with a stepping clock. Docs say the snapshot's
  `reservedUsd` can include such a hold, and that a model hold is an estimate.
- `window: "RUN"` on a scheduled fire is keyed by `scheduleId@lastFired` (`ScheduleFireExecutor.runIdOf`), so the retries
  of one due fire share one run instead of each getting a fresh counter.
- The resumed LLM task settles its holds when it finishes (as `executeTask` does).
- `maxToolContextTokens <= 0` (guard off) sizes a hold with the default tool-context cap instead of zero.
- Manager: no subject-only `toolQuotas` (subject disabled until a rule exists; removing the last rule drops it); a rename
  onto another rule's name is held as a draft and flagged instead of silently dropping that rule; the budget status
  subtracts holds from what is left; the cost-ceiling note and the operator prompt no longer promise an un-overshootable
  budget. 2 new i18n keys and one amended, in all 11 locales. Grafana: the USD series has its own unit and axis.
- Platform Operator: revision 55 (fingerprint `a5a444f4385d0702`) for the prompt change.

---

## 📝 docs: fix the findings of the 2026-11-03 documentation audit (2026-11-03)

**Repo:** EDDI (`docs/audit-fixes-2026-11-03`)

### What changed and why

A 13-scope audit compared the docs, Javadoc and inline comments with the code. Every finding was re-checked against the code before it was fixed; the audit was wrong about a few, which were skipped.

**Code**
- Counterweight preset snippets are now `counterweight_cautious` / `counterweight_strict`. The hyphenated names could never be created (`PromptSnippetStore.NAME_PATTERN` is `[a-z0-9_]+`). The hyphenated names are still read as a fallback.
- `OrdinalNumbersDictionary` carried the id `punctuation` (copy-paste); it is now `ordinal_number`.
- Everything else under `src/` is Javadoc or comment text only.

**Docs**
- Tenant quotas: only the default tenant is seeded, other tenants are unmetered until an admin sets a quota, and quotas are off by default.
- `rotate-kek` "TLS required" is not enforced by EDDI; the docs now say so. Default demo credentials are no longer listed in `security.md`.
- Compliance pages (`PRIVACY.md`, BAA template, audit ledger, data flow) no longer claim an immutable, always-signed ledger, a complete export, or unsalted pseudonyms (the DB-log pseudonym is `k1:`/`s1:`). EU AI Act citations corrected (Art. 50, 73). DLQ subject is `eddi.<prefix>.dlq.audit`.
- `AGENTS.md` §5 and the config-authoring pages: the templating step is only needed for output texts and quick replies, not for LLM prompts. `returnDetailed` withholds audit, trace and error keys.
- The vars/vault resolution order in `global-variables.md`, the HITL crash-recovery description, the MCP tool count (96) and the discussion-style count were corrected.
- `SECURITY.md` (root and Manager): only the latest release is supported; older versions get no backported fixes.
- `ui/manager/SECURITY.md`: the react-router advisory acceptance is restated for the data router the app uses.

**Deferred** (editorial or structural): splitting the very large pages (`langchain.md`, `hitl.md`, `configuration-reference.md`, `metrics.md`, `rag.md`, `security.md`, `secrets-vault.md`), the duplicated Manager community files, a 6.6 to 6.7 upgrade page, I-code and Phase labels in comments. Open code gaps: the 10,000-row audit export cap is not signalled in the bundle, and some export categories and block failures are not reported in `failedCategories`/`omittedCategories`.

---

## 🔒 fix(cluster): NATS TLS, failing loudly, replication, manifests and alerts (2026-11-03)

**Repo:** EDDI (`fix/cluster-ops-security`) — workstream D of the cluster production-readiness review (`planning/cluster-production-readiness-review.md`, findings C1, C2, C3, C5, C7–C15 and the scheduler starvation of D6).

### What changed and why

- **C1 — TLS host names.** EDDI's hostname-verifying SSL context is now built for every `tls://` or `wss://` server, not only with `eddi.nats.tls.enabled=true`. The chart's external path never set that flag, so jnats fell back to the JVM default context, which accepts any CA-signed certificate for any host: a man in the middle received the shared NATS password. `opentls://` (trust anything) fails the boot. A real-server test (`NatsConnectionSecurityIT`) also found that the previous hostname verification (endpoint identification on the socket) never worked with a host name: jnats resolves the name and dials — and hands TLS — the bare IP, so every certificate issued for the host was refused (and jnats' `Unresolved` mode does not connect at all). Verification now happens in a trust manager ([`ServerNameTrustManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/ServerNameTrustManager.java)): the chain must be trusted AND the certificate must name one of the hosts in `eddi.nats.url` (DNS SAN, one leading wildcard label, or an IP SAN for an IP). `HostnameVerifyingSslContext` is gone. [`NatsConnectionManager.java`](../src/main/java/ai/labs/eddi/engine/cluster/NatsConnectionManager.java), [`ClusterConfig.java`](../src/main/java/ai/labs/eddi/engine/cluster/ClusterConfig.java)
- **C1, reconciled with main (#1249).** Main fixed the same hole with `HostnameVerifyingSslContext` plus jnats `HappyEyeballs` resolution. The merge keeps one check, `ServerNameTrustManager`: it compares the certificate with the configured names whatever address is dialled, so it also covers servers jnats discovers from INFO by IP. From main it keeps `HappyEyeballs` (the handshake carries the name as SNI), the real-handshake `NatsTlsHandshakeTest`, and mutual TLS with the JVM's `javax.net.ssl.keyStore` certificate when no EDDI key store is configured (now loaded explicitly into the EDDI context; a new handshake test covers it).
- **C2 — fail loudly.** A node that never connected since boot, or whose connection fails for a reason retrying cannot heal (authentication, TLS, client options), now reports not-ready (`eddi.cluster.readiness.require-first-connect`, `eddi.cluster.readiness.fail-on-misconfiguration`, both default `true`) instead of serving every turn unfenced while green. Every NATS error is classified and counted in `eddi_cluster_nats_errors_total{cause}` (all causes registered at 0); misconfigurations log at ERROR, the rest at WARN, rate-limited; `eddi_cluster_nats_misconfigured` is a gauge. Trust stores may be PEM (a cert-manager `ca.crt`), and the chart mounts the NATS CA — the in-chart TLS Secret's, or `nats.externalCaSecretName` — into EDDI as its trust store; the in-chart URL is `tls://` with `nats.tls.enabled`. [`ClusterHealthCheck.java`](../src/main/java/ai/labs/eddi/engine/cluster/ClusterHealthCheck.java)
- **C5 — dead connections.** `eddi.nats.ping-interval` (5 s) and `eddi.nats.max-pings-out` (2): a server lost without a TCP reset is noticed in about 10–15 s instead of about 4 minutes.
- **C3 — replication.** The chart renders `EDDI_NATS_REPLICAS` for an external NATS too (`nats.externalReplicas`, default 3); EDDI warns at connect when R1 meets three or more servers, or the replica count exceeds the servers it knows.
- **C7 — rollouts.** Readiness still flips at SIGTERM, but turns are admitted for the readiness grace (3 s) before the gate closes; the chart (`eddi.preStopSleepSeconds`, 5) and the Kustomize `nats` component add a `preStop` sleep, counted in the termination-grace check. [`GracefulShutdownService.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/GracefulShutdownService.java)
- **C8 — Kustomize NATS.** The `nats` component now authenticates clients and routes (credentials from a `nats-auth` Secret the operator creates — **existing users must create it before applying**), restricts EDDI's user, sets `max_file_store` with headroom, requires one NATS pod per node, spreads zones, sets `GOMEMLIMIT` and `EDDI_CLUSTER_DEGRADED_TURNS=reject`. TLS remains the Helm chart's.
- **C9 — storage.** `<prefix>_ARCHIVES` gets `eddi.cluster.archives.max-bytes` (1 GiB; an existing bucket is capped on first use); the in-chart `max_file_store` is 80 % of the volume (`nats.storage.maxFileStore` overrides). [`ClusterArchiveStore.java`](../src/main/java/ai/labs/eddi/engine/cluster/ClusterArchiveStore.java)
- **C10 — NATS StatefulSet.** `nats:2.11.17-alpine` pinned by digest on every delivery path (chart, Kustomize, compose; `ThirdPartyImagePinsTest` enforces it), required anti-affinity (`nats.antiAffinity`) plus zone spread, `nats.goMemLimit`, an optional `prometheus-nats-exporter` sidecar with a ServiceMonitor (`nats.metrics.enabled`), and a second user for password rotation without downtime (`nats.auth.secondaryUsername`/`secondaryPassword`).
- **C11 / C4 (chart side) — isolation and least privilege.** The in-chart and Kustomize NATS deny EDDI's user `$JS.API.STREAM.DELETE.>`, `$JS.API.SERVER.>`, `$JS.API.META.>` and `$JS.API.ACCOUNT.PURGE.>` (checked against a real NATS 2.11.17: KV, streams, purge and object store still work; a stream delete is refused). `eddi.nats.inbox-prefix` (default jnats' `_INBOX`) and the opt-in `nats.isolation.enabled` put a deployment in its own NATS account with its own inbox prefix — opt-in because it moves the buckets into a new account.
- **C12 — alerts.** `eddi-cluster-alerts.yml` gains NATS misconfiguration, NATS errors by cause, member loss, lease takeovers, an event-outbox backlog and, from the NATS exporter, JetStream storage, a full dead-letter stream and slow consumers; it has promtool tests (`eddi-cluster-alerts.test.yml`, run in CI) and the chart's PrometheusRule includes it in cluster mode.
- **C13 — ingress.** With `ingress.className: nginx` the chart disables buffering and sets a 300 s read timeout (SSE), and in cluster mode adds an `<release>-mcp` Ingress hashing `/mcp` on the client address (`ingress.nginxDefaults`, `ingress.readTimeoutSeconds`).
- **C14 — HPA.** `autoscaling.targetMemoryUtilizationPercentage` defaults to `null`: a JVM keeps its heap, so the memory target ratcheted the HPA to `maxReplicas`.
- **C15 — configuration.** Boot refuses non-positive durations, two authentication methods at once and a malformed inbox prefix; warnings for an acquire timeout below the lease TTL or at or above the agent timeout, plaintext credentials and `verify-hostname=false`.
- **D6 — scheduler.** The cluster scheduler's two platform threads now only keep time; every job runs on a virtual thread and never overlaps itself ([`OffloadingScheduler.java`](../src/main/java/ai/labs/eddi/engine/cluster/OffloadingScheduler.java)), so jobs blocked on NATS or the database can no longer delay presence past its 30 s TTL.
- **Docs:** [Clustering](clustering.md) (degraded mode, failure modes, permissions, TLS, alerts), [Kubernetes](kubernetes.md), [Configuration reference](configuration-reference.md), [Metrics](metrics.md), the monitoring guide; the Platform Operator explains a not-ready node (revision bump). Helm chart **2.14.0**.

### Design decisions

- Readiness DOWN for a misconfiguration takes every node with the same bad credential out of the load balancer. That is the intended trade: loud instead of silently unfenced. Both switches can be turned off.
- The code default of `degraded.turns` stays `local`; the chart exposes `eddi.cluster.degradedTurns` and the docs and the Kustomize component recommend `reject`.
- Account isolation and a custom inbox prefix are opt-in: switching an existing release would strand its dead letters and archives in the old account.

### Review follow-ups

- Only a certificate or handshake-policy failure counts as a TLS misconfiguration: a handshake
  the peer cut off or a reset connection (a NATS restart) is `io` and no longer turns every pod
  not-ready.
- A failed cap of an existing archive bucket is retried on the next use instead of being given up.
- `helm upgrade --reuse-values` from an older chart no longer fails on the missing
  `nats.externalReplicas` (defaults to 3) or `nats.isolation` / `nats.metrics` maps.
- `EddiClusterMemberLost` compares with the member count that held for 10 minutes, so a rolling
  update's surge pod (3 → 4 → 3) no longer fires it; a promtool case covers that.
- Docs: `require-first-connect` must be off as well to serve from boot; the Kustomize NATS needs
  EDDI switched to `tls://` too, not only a server `tls {}` block; an `eddi.existingSecret`
  release must update that Secret and restart EDDI before the old NATS user is removed. The
  Platform Operator no longer rules out a NATS outage for a node that never connected.
- The NATS image pin test checks the whole reference, not a valid prefix of it. The CI chart
  checks use low-entropy fixture passwords (secret scanning flagged the earlier placeholders).

### What's next

- Per-node NKey/JWT users need an external NATS with account management; the chart stays with password users.
- Signed RPC/events (C4) and the receiving-side last-node guard are workstreams C and B.


- Operator prompt (revision 55): a `config` NATS failure is diagnosed from the reported error detail (credential, trust store or client certificate), and with `eddi.cluster.readiness.require-nats=true` a node that connected and then became degraded is also not ready.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-11-03 | NATS auth/TLS failures and "never connected since boot" fail readiness by default | Review C2: such nodes served every turn unfenced while green | Capping unfenced time in the coordinator (owned by another workstream); keeping readiness UP |
| 2026-11-03 | Cluster scheduler jobs run on virtual threads behind a timer-only pool | Review D6: two blocked jobs starved presence past its TTL | A dedicated presence thread only (presence is owned by another workstream) |
| 2026-11-03 | Transient (autoDeploy=false) deployments are recorded in a NATS KV bucket with tombstones, not in the deployment collection | B1: they existed only as events | A database status + epoch: survives a full cluster restart (against autoDeploy=false semantics) and an unknown status value breaks older nodes mid-upgrade |
| 2026-11-03 | Cluster messages are signed with an HMAC key kept outside NATS; unsigned accepted unless eddi.cluster.signing.required=true | C4: one NATS credential controlled every node | Per-message asymmetric signatures (key distribution, cost); refusing unsigned by default (breaks rolling upgrades and existing installs) |
| 2026-11-03 | No margin when an audit position counter is re-seeded | B5 recommended one | A margin is a chain gap that verification grades BROKEN; re-seeds are counted instead |
| 2026-11-02 | Analytics read a projection (`approval_decisions`) fed by the decision event and rebuildable from the ledger | GDPR redaction empties ledger output; cancellation rows lack agents; no index on `output.*`; overflow drops | Aggregating the ledger directly; maintained `$inc` counters (not idempotent under retry/backfill) |
| 2026-11-02 | Summaries aggregate in Java over a bounded, indexed query (50k rows, `truncated` flag) | One implementation for MongoDB 6 and PostgreSQL, percentiles from fixed buckets | `$bucket` / `GROUP BY` twins per backend |
| 2026-11-02 | Re-gating is a runtime overlay with synthetic WAIT rules; promotion is a human-applied config change | Nothing ungates without a human; a suspension must never auto-execute and must not rewrite configuration | Rewriting `exempt` on demotion; an AUTO_APPROVE demotion inside ConversationHitlService |
| 2026-11-02 | No MCP write tools and no bulk sample verdict | An MCP client is often a model; bulk OK is the rubber-stamping the queue prevents | Mirroring every REST action as an MCP tool |
| 2026-11-02 | Humans execute task-force tasks after the agents' waves, one task per pause | A person inside a wave future would hold every agent's wave budget hostage; one pause per task keeps a single pendingHumanInput and one content-to-task mapping | Humans inside waves; batching several tasks into one pause |
| 2026-11-02 | targetEachPeer phases and nested groups containing humans stay refused | The resume bookmark has no (speaker, target) coordinate, and nested pauses would need parent-follows-child pause propagation across two documents | Extending ResumePoint for peer targets in this PR |
| 2026-11-02 | VERIFY rework skips HUMAN-assigned tasks | A rework would pause from inside VERIFY, and resuming VERIFY replays the whole verification pass | Reworking human tasks with a VERIFY-phase pause |
| 2026-11-02 | A HUMAN_DECIDES tie-break pauses through the I6 AWAITING_HUMAN_INPUT path, not the approval path | Approval endpoints are binary and carry no content, and any eddi-approver could then set the outcome of a vote; the I6 path already has the decider-or-admin guard, timeouts, crash recovery, CAS and the inbox | A PHASE approval with the choice in the note; letting approvers break ties |
| 2026-11-02 | GroupConversation schema v5 for PendingHumanInput.detail and RESUME_KIND_TIE_BREAK | Both are resume-consumed: an older pod resolving a tie-break would re-run the vote's last repeat | Riding v4 because the fields are additive |
| 2026-11-02 | A tie-break audit entry carries no decisionOutcome | It is a human turn, not an approval; the approval-analytics contract skips null outcomes, and new outcome values would widen that contract | DECIDED / TIMED_OUT outcome values |
| 2026-11-02 | A team's monthly spend is charged from the root discussion's cost-ledger delta; the root group pays and a nested child charges nothing | The ledger already holds member, nested and group-system spend and records by replacement, so a delta cannot double-count; the per-call spend feed has no root and cannot express the in-run ceiling | Per-call `ISpendSink` events (attribute nested children to the child group, tenant-accepted charges only); reading the tenant counter (stays 0 with quotas disabled) |
| 2026-11-02 | Gates fail closed on an unreadable team budget, metering never fails work | A budget nobody can read is not a budget with room; failing open would drop every cap during an outage | Fail-open at the gate |
| 2026-11-02 | The ceiling a running discussion obeys is expressed in its own `totalCost` and excludes its boundary | Lets the existing ceiling checks enforce it unchanged; equality exhausts, like the tenant | A parallel check in every phase engine |
| 2026-11-02 | Tool-call arguments are screened before the approval gate, and again block-only after approval; `disclosure` is a guardrail type, not a transform; result rules deferred | A call certain to be refused must not reach a human, and an approver must see what will run; approval binds to a request, so nothing may be rewritten after it | Screening after the gate (approvers see unredacted text, approvals spent on refused calls); rewriting approved calls (sends text nobody approved); a new `GuardrailAction` for labels (unknown actions block) |
| 2026-11-04 | Tool-argument schema check runs before the approval gate via a `ToolArgumentValidator` executor marker | Malformed generated-tool calls reached human approval, then failed or silently no-op'd at the API | A new map threaded through `ToolContribution`/`ToolSetup` (more plumbing for the same result); a hand-written validator (networknt already ships) |
| 2026-11-04 | setup-api sync identifies generated apicalls by an `openApiOrigin` marker set at setup; unmarked agents are refused with how to add it | Sync must not regenerate hand-written httpcalls | Heuristic adoption by targetServerUrl match (could overwrite hand-written configs) |
| 2026-11-04 | `dryRun` on setup-api is hidden from the OpenAPI schema | No addition may become callable by the Platform Operator, whose setup-api tool is generated from that schema | A sibling endpoint (the brief asked for the body field) |
| 2026-11-04 | Retry jitter only shortens the backoff (`[base × (1 − r), base]`), never lengthens it | Adding ±20% jitter as OpenClaw does | Symmetric jitter, which could push a sleep past `maxBackoffDelayMs`, the total backoff budget or the turn deadline |
| 2026-11-04 | Peer-content caps (12,000 per entry, 96,000 per list) are engine constants, not group config | Bounding peer text in group prompts | A per-group setting: it would have to be threaded through every `buildPhaseInput` caller for a safety bound nobody has needed to tune; the I9 context window is the tuning knob |
| 2026-11-04 | `headTail` became the default only for an *unset* strategy; explicit `truncate` and the fallbacks keep start-only | Making head-and-tail truncation the default | Changing `truncate` itself, which would have silently changed configs that asked for it by name |
| 2026-11-03 | Vault references in a SERVICE judge's parameters need a secret granted to all agents ("*") and are re-checked at run time | A group has no agent identity, so the deploy-time VaultGrantChecker cannot apply; an editor could otherwise aim any vault key at an endpoint of their choosing | A group-level grant model; restricting parameters to ${vars:...} |
| 2026-11-03 | `approvalContext` is rendered once at pause commit and persisted as `hitlApprovalContext`; every surface serves that text, re-redacted | The Slack card is posted once, so the Manager, REST, MCP and a later audit must show the same text the approver saw; a read-time render would drift (snippets, artifacts, republished config) and would need the whole multi-MB conversation memory on every poll | Rendering at read time on each surface; rendering only for Slack |
| 2026-11-03 | Showing the context in a Slack channel needs the integration's own opt-in `hitlApprovalContext=true`, on top of the designer's `approvalContext` | The channel audience (everyone who can read it) is wider than the approver role the Manager, REST and MCP surfaces are gated to | Designer opt-in only; always on |
| 2026-11-03 | Slack values are `plain_text` sections; a card that carries context is posted with link and media unfurling off | The text is conversation data a model may have written: plain text cannot form a mention, a disguised link or bold spoofing, and a model-chosen URL must not make Slack fetch it | Escaping mrkdwn (`SlackMrkdwn.escape` leaves `@here` and bold untouched) |
| 2026-11-03 | A render failure becomes an item with an `error`; it never throws into the pause commit | A broken template must not strand a pause or hide the approval from the person who has to decide | Failing the pause; dropping the block silently |
| 2026-11-03 | A lease is trusted for ttl − 2×heartbeat after the last acknowledged renewal, counted from its send time; past that isLost() is true and callbacks fire | A holder cut off from NATS ran on past the TTL (A1) | Keeping the lease until a conflict is seen (the old behaviour); counting from the reply's arrival (the server's TTL starts earlier) |
| 2026-11-03 | Lease values carry a per-process lease id; conflicts are re-read and adopted when the id matches | Applied-but-timed-out writes read as takeovers (A2) | Matching on node+boot only — a second lease of the same process under the same key could be mistaken for this one |
| 2026-11-03 | Release notifications are addressed to the nodes that said they wait; no broadcast for plain releases | Every node received every release (D7) | Keeping the broadcast during the upgrade — old waiters poll instead, a hint is never needed for exclusion |
| 2026-11-02 | A deployment price catalog completes config prices; EDDI ships reference prices that are never active until an admin adopts them | Cache, thinking and unpriced spend were invisible to budgets; `TokenPricing` said "deliberately no built-in provider price table" | Active shipped defaults (stale silently, upgrades change charges); per-tenant catalogs (EDDI meters one tenant) |
| 2026-11-02 | Unknown cache prices are charged at the input price | Anthropic/Bedrock cache tokens were $0 | Charging them $0 until priced (under-counts a hard budget) |
| 2026-11-02 | The SERVICE judge calls SummarizationService.summarizeWithUsage, not GuardrailModelClient | GuardrailModelClient is package-private and bound to an LLM task's GuardrailContext/PaidCallGate, which a group does not have; the group summarizers already price and book through SummarizationService + TokenPricing + GroupCostLedger | Reusing the guardrail client; a new model client |
| 2026-11-02 | The judge's parameters accept vault/vars references only for credential keys, and refuse ${connection:...} | A group config must not become a plaintext key store, and ChatModelRegistry rejects connection references for model parameters at call time | Accepting literals with a warning; resolving connections for the judge |
| 2026-11-02 | One `hitl.approval` entry per decision event with a `units[]` list (answer, tool call, phase, task) and `schema: 2`; legacy fields kept | Analytics needs per-unit outcomes, categories and edit sizes; existing readers need the old fields | One entry per unit (breaks the one-entry-per-decision shape, multiplies ledger sequence numbers); a separate store written alongside the ledger |
| 2026-11-02 | An approved edit of a rule-pause answer replaces only the draft's own output item and data keys; nothing re-runs; a guardrail-refused edit re-pauses on the unchanged draft | Re-running later tasks would re-trigger the pause rule; a refused edit must not consume the human's pause | Truncating and re-running the rest of the turn (as REVISE does); screening before the CAS (model calls on the request thread and inside Slack's 3-second window) |
| 2026-11-02 | A group SYNTHESIS edit is appended as an unsigned reviewer entry (`hitl:<decidedBy>`, `errorReason: reviewer-edit`), reusing the SYNTHESIS type | Signed transcript entries must never be rewritten; the existing "last synthesis" extraction then needs no change and older pods deserialize it | A new transcript entry type; rewriting the signed entry in place |
| 2026-11-02 | Slack edits of tool arguments are merged server-side against the raw arguments; redacted values cannot be edited from Slack | Slack only ever sees redacted arguments; a value left as shown must keep its raw secret, and a marker must never become an argument | Sending raw arguments to Slack; refusing every edit of a call that has any redacted value |
| 2026-11-02 | `HitlDecisionRecorded` is fired with `fireAsync` through `Arc.container()` from the plain audit writer | Nothing a consumer does may run on the decision's critical path; the HITL collaborators are plain classes built in their facades' constructors | A synchronous event; new CDI constructor parameters on the facades |
| 2026-11-02 | Cross-team handoff requires two-sided consent (source `targets` and target `accept.from`), enforced in `TeamHandoffService`, not by prompt | A one-sided design lets an author with EDIT on any agent write into any team's backlog | Source-only allow-list; wildcard accept rules (explicit ids keep consent auditable) |
| 2026-11-02 | Handoff provenance is a side map on the workspace (`handoffs.origins`), not a `TaskItem` component | The cadence writeback rebuilds tasks with the shorter constructors and already drops `createdByAgentId`; a component would also touch ~30 call sites | Adding `origin` to the `TaskItem` record |
| 2026-11-02 | The default accept mode is REVIEW (an intake inbox no cadence can pull) | The handed-over text crosses a trust boundary and will be read by the receiving team's models | AUTO by default |
| 2026-11-02 | The approval source for the tool is a new `team`, not `builtin` | `exempt: ["builtin:*"]` would silently un-gate cross-team writes | Reusing `builtin` |
| 2026-11-02 | `toolQuotas` reserve inside `ToolExecutionService.executeToolWrapped` (after cache, before execution), release on failure, keep on timeout | A quota that counts cache hits or refused calls, or that cannot give a failed call back, charges users for nothing; a timed-out call may still have completed its side effect | Reserving in `ToolLoopRunner` around the supplier (cannot see the cache; the refusal would be cached and charged); counting at result time (overshoots under concurrency) |
| 2026-11-01 | Late agent-test charges adjust the stored terminal record (compare-and-swap on `heartbeatAt`) | A timed-out run would otherwise wait up to the turn timeout plus the write-back wait before it could report | Holding finalisation for abandoned turns |
| 2026-11-01 | Dead-letter forwarding is fenced by a shared erasure tombstone, checked before and after publishing | The snapshot outlives the shared purge, so "does the conversation exist" cannot see an erasure under way, and one check still races the purge | Relying on the existence check alone |
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
| 2026-10-27 | The prompt and response are removed from the stored step once the ledger accepted the turn's entries; tool calls, usage, cost, model name and guardrail records stay | The ledger is the system of record and holds the full text; the copy was a fifth of the document. Agent tests, cost metering and history replay read the small keys back from the stored step | A config switch (no consumer needs the text in memory); a hash/reference in place of the text (the ledger is addressable by conversation id and step index already); stripping `audit:tool_calls` too (agent tests read it from the stored step) |
| 2026-10-27 | Current-step and approval-status reads load a server-side projection (last step/output + computed totals) instead of the whole conversation document | Whole-document reads ran at ~100 KB/s on a bandwidth-limited database link; 0.5–3 MB documents made these reads take 11–29 s | Caching the snapshot (stale across nodes, does not help the first read); a `$slice` find projection without totals (undoAvailable, output/step pairing and conversationCost need the full lengths and every step's spend) |
| 2026-10-26 | Due resubmission rows of a deleted integration are left to ledger retention, not refused by the sweeper | Once the query is scoped to one integration they block nobody; a sweeper pass over all RECEIVED rows would again read one global batch | Refusing them in the inbound sweep; deleting an integration's ledger rows with it (the rows are GDPR-reachable history) |
| 2026-10-25 | A conversation-mode reply's recipient is decided in one function, from the ledger row cross-checked with the mail's step in conversation memory and the conversation's identity, on every attempt | Authentication is a fact of the inbound mail; the processor forces it on, and the reply path checks it again from two independent records, so a reply to an unauthenticated or anonymous sender cannot be produced even for a turn that should not have run | An authenticated flag on the ledger row (a new column on both stores for a fact the step already holds); trusting the processor's check alone |
| 2026-10-25 | Replies are rebuilt from the conversation on retry; group replies, whose discussion keeps no copy of the mail, are retried only by the node that holds them | The ledger stores no body or subject (plan §12); a group conversation has no field for the answered mail's subject | Storing the subject in the ledger; an extra copy of the mail in the group conversation |
| 2026-10-25 | A mail whose conversation was started but whose turn never ran is found again in the mailbox by Message-ID and run once | The raw mail is not kept, and the mailbox still has it (flagged or moved); the hand-back is a conditional transition and the turn carries the mail's ledger key | Keeping the rendered input (a body in storage); marking it lost (a mail silently not processed) |
| 2026-10-25 | The email suppression list stores a SHA-256 of the address per integration, and GDPR erasure deletes the entry | Enough to answer "suppressed?" and to clear it for someone who knows the address; conversation mode only answers mail the person sends, so an erased person is written to again only if they write first | Plain addresses (a list of correspondents); keeping a hash after erasure (plan §23 Q4 — keeps the identifier the request asked to remove) |
| 2026-10-25 | Suppressions are cleared through two channel REST endpoints, not an MCP tool or an operator grant | An administrator needs a way to undo a bounce without database access; the operator holds no channel endpoints by design | Store-only clearing (database access); an MCP tool (one more admin surface to secure) |
| 2026-10-25 | Email trigger mode never runs a mail twice once a side effect may have happened; a claim that did nothing is given back and the mail, still unmarked in the mailbox, runs again | The CLAIMED state separates "nothing left EDDI" from the point of no return; only a crash while starting the conversation is ambiguous, and that one is REFUSED with an owner notice | Refusing every stranded claim (lost mail that had run nothing); re-running submitted turns (double side effects); a sweep that opens its own IMAP session (two nodes on one mailbox) |
| 2026-10-25 | The thread mapping key of a correlated email conversation includes the sender's address | Message-IDs travel in every forward; under identityMode integration all senders share a user id, so a thread-only key lets anyone join any thread | An explicit sender field checked after lookup (one more stored fact and a check that can be forgotten); correlating by user id only (unsafe in integration mode) |
| 2026-10-25 | Unauthenticated email senders get a one-off email-anon id with no user-memory store and no memory tool, enforced in ConversationService and ContextualToolsProvider | Their From is a claim anyone can make; memory written under it would be read by the next forger | A shared anonymous id (one sender's injection persists for all); relying on the random id never recurring (still writes orphaned memories) |
| 2026-10-25 | Mail-server outages back off inside the poller and are COMPLETED fires with the error in the fire log | The schedule retry ladder dead-letters after a few minutes, which would silently stop a mailbox after a short outage | FAILED fires (dead-letter); SKIPPED fires (no error recorded) |
| 2026-10-25 | Polling state lives in a new IEmailMailboxStateStore (Mongo + Postgres), not the ledger or the cluster KV | The cursor is one value per mailbox that must outlive every ledger row's 30-day retention; the KV buckets are node-local and volatile in single-node mode | A cursor row in the ledger (expires, mixes per-message and per-mailbox facts); NATS KV (lost on restart) |
| 2026-10-25 | Save-time warnings travel as stable codes in an X-Eddi-Warnings response header | The channel save responses carry no body to extend, and codes let the Manager translate them in 11 locales | A response body (changes the create/update contract); log-only warnings (invisible to the author) |
| 2026-10-25 | Mail in the folder before an integration's first poll is never processed | Connecting an integration to a full mailbox must not make an agent work through years of mail | Processing everything unread (cost and side effects nobody expected); a startFrom key (more surface, deferred until asked for) |
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
| 2026-10-24 | Self-continuation is delivered as a one-shot schedule on the cluster-aware schedule machinery, not through the conversation coordinator's queue | E8 needs a continuation that survives a node crash, runs once under the conversation lease, and lands in dead letters when lost | Coordinator queue (in memory, lost on a crash); a custom scheduler (AGENTS.md forbids it) |
| 2026-10-24 | `maxContinuations` counts per conversation (steps carrying the engine-written `continuation:number`), not per chain | A counter the author or a property setter can reset is no bound; a job that needs more starts a new conversation | A conversation property as counter (writable by config); per-chain counts reset by user input |
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
| 2026-10-24 | Hidden-content pass in two modes: conservative for pages (comments, 1px, transparent, explicitly same-coloured text), strict for mail | Pages hide accordions, tabs and "read more" content with display:none/hidden/opacity until script reveals it — content RAG wants; mail runs no script, so anything hidden there is only for a machine | One strict rule set for every caller (dropped FAQ answers and tab panels from crawled pages); opt-in only for mail (leaves RAG without the white-on-white and zero-size defences) |
| 2026-10-24 | Style-block hiding rules over-approximate instead of being capped | A rule cap is a bypass: a display:none rule placed after the cap would leave the hidden text in | Truncating the style text or the rule count; exact selector matching per rule (one document search per rule, quadratic) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
| 2026-10-24 | Single-agent revise is `hitlConfig.onRejection: REVISE`, RULE pauses only, the frozen request kept as step data | Plan decision 9.5; a rejected tool call already reaches the model with the note | A new verdict value on the approval API; rebuilding the request from memory at resume (loses retrieved context and tool results) |
| 2026-10-24 | `maxReworks` runs the rework inside the VERIFY phase | The phase list stays linear and resume bookmarks untouched | Jumping the phase loop back to EXECUTE |
| 2026-10-24 | A guardrail-refused message is withheld from every later model-facing history by a step flag written at block time (`llm:input_withheld:<taskId>`), not by re-screening | The classifier does not re-screen history, so a message it blocked reached the model on the next turn | Making the classifier screen history (cost multiplies by the window); storing the redacted text in memory (size, and a classifier redaction is the block message anyway) |
| 2026-10-24 | `postResponse` runs after an input block, like every other fallback | An input-blocked `convertToObject` + `addToOutput: false` task answered nothing | Correcting the docs instead — that left the silent empty answer |
| 2026-10-24 | Webhook dedup refuses (503) per integration (own budget, fair share from half capacity, hard stop at 90%) rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
| 2026-10-15 | LLM input/output guardrails use an EDDI-native `ContentGuardrail` SPI, not `dev.langchain4j.guardrail` | WS4/R0 implementation against langchain4j 1.22.0 | lc4j `InputGuardrail`/`OutputGuardrail` + `EddiChatExecutor` (mandatory executor = unbudgeted model-call path; sealed results; mandatory builder fields) |
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in, `eddi.chat.url-token.enabled`, default off; served to the widget as a script global before the bundle | Security review M6: a token in the URL is login CSRF and is logged by the first request | Keeping it on and documenting the risk; a fetched JSON config, which would race the `?token=` read |
| 2026-10-15 | All three CSP filters use negative Vert.x orders so they run before the `/q` sub-router mount | Security review L11: at order 20 the Swagger filter never ran, and Quarkus passes the order to Vert.x unchanged, ascending | Moving Swagger UI off the sub-router, or a Java `Filters` observer — both heavier than three numbers |
| 2026-10-15 | `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false`; the advertised MCP resource scheme follows the request | Security review L3: a plain-http instance with auth on advertised an https identifier nothing served | Keeping `true` (wrong on every plain-http instance); deriving it from `quarkus.http.proxy.*` (no config signal separates plain http from a TLS proxy without forwarded headers) |
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
| 2026-10-15 | Tool cost ceilings enforced by default (`eddi.tools.budget.enforce-by-default=true`); `enforceBudget: false` is the named opt-out | Security review M8: every stored ceiling was advisory because the opt-in key was undeclared, and the $0.00 built-in pricing that justified the opt-in was fixed releases ago | Keep the opt-in and only document it |
| 2026-10-15 | Remote MCP/A2A tool parameter schemas are governed (text redaction, per-tool and per-server byte caps; descriptions stripped before a tool is dropped) | Security review M7: schemas are prompt content authored by the remote party, and a partial schema would make the model call the tool wrongly | Govern descriptions only; truncate the serialised schema mid-document |
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
| 2026-10-13 | Model-version rules live in a data catalog consulted when a model is built, rejected parameters are dropped with a WARN (switchable) | Exposing 30 more parameters per provider without knowing which model accepts them would make agents easier to break; dropping a certain-4xx parameter keeps the agent answering | Fail the build of the model; only document the rules |
| 2026-10-13 | No catalog rule without an official source; unknown models fail open | A wrong rule silently removes a working setting, a missing rule only costs a visible vendor error | Infer rules from model-name prefixes |
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
| 2026-10-07 | Operations whose required input the generator cannot send are skipped and reported, not generated half-working | An approved `updateResourceUri` call could only ever fail, and the model could not say why | Generating them and warning (the tool still exists for the model to call); failing the whole setup (one unusable endpoint would block every other tool) |
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
| 2026-10-13 | The Kubernetes copy of the Full Metrics Reference ships as two linked dashboards | Keeps plain client-side `kubectl apply -k` working; the source dashboard is unchanged | Requiring `kubectl apply --server-side` (changes every operator's apply command); raising the test's budget (removes the headroom that caught this) |
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together. Superseded on 2026-10-12: the post-release PR moved the chart to 6.5.0, so the examples were bumped with it when main was merged | Writing 6.5.0 now (fails the build until the chart moves) |
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
| 2026-10-02 | HITL timeouts whose decision cannot be applied yet are re-armed at a fixed interval and never dead-lettered | A COMPLETED-and-disabled one-shot silently turned a finite policy into wait-forever | Retrying on the normal backoff ladder (dead-letters after ~21 min) |
| 2026-10-02 | Schedule fires re-check USE against a creator access snapshot (teams + admin flag) taken at creation | principalMayUse(id, principal) refuses team shares; server has no team membership without a token | Re-checking the bare principal; skipping the check |
| 2026-10-02 | Scheduler lease is raised at startup to outlast fire-timeout + grace instead of failing startup | A misconfigured scheduler should not take the platform down | Failing startup; heartbeat-renewed leases (more store surface) |
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
| 2026-09-29 | Merge with main: a captured conversation property is kept at turn end; a secret input shorter than 4 characters is removed as a whole token, in values only | The wizard pattern hands a captured key to a later turn; a short PIN echoed into a reply must not persist, but digits inside other numbers and field names must | Main's property scrub; no search below 4 characters; renaming map keys in token mode |
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
| 2026-09-29 | PR #831 keeps #859's `ClientContextGuard` and #860's converse ownership check, drops its own `ReservedContextKeys` and started-conversation set, and applies the delegation USE check to continuations | Two implementations of one boundary drift apart; the USE check on continuations closes the one case the started set covered that ownership does not | Keeping the started set alongside the ownership check (two continuation rules for one tool); stripping in `ConversationService` as well (a second boundary for the same keys) |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
| 2026-09-29 | KEK rotation keeps main's rollback and adds re-runnability only for what a rollback cannot undo | Both branches fixed "a half-way rotation strands DEKs"; one mechanism, main's, with the pending salt and either-KEK verify for the unrecoverable cases | Forward-only re-run (this branch's original); rollback without the pending salt (loses DEKs when the salt write fails) |
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
| 2026-09-27 | A client-flagged secret input is searched for from 4 characters, not 8 and not 1 | A PIN or short password is what a password field carries; below 4 the replace-everywhere search shreds the turn's reply and map keys for a guessable value. Same floor in the turn-end scrub, read-time masking and audit buffer | The 8-character context-value floor; every nonempty form |
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | A client-flagged secret input is scrubbed when its turn ends, and the turn output's `input` is the masked display copy on the wire | The parser overwrote the placeholder with the normalized plaintext, so exposing `input` alone would have leaked a copy | Exposing `input` without the engine fix; scrubbing properties the designer captured the input into (breaks the wizard pattern) |
| 2026-09-26 | Older stored secret turns are masked on read, not migrated | Every secret turn carries `context.secretInput`; masking on read closes the exposure for history at no migration cost | A data migration; leaving history exposed |
| 2026-09-26 | Chat UI math is `$$…$$` only | Single-dollar math turned dollar amounts into formulas | remark-math defaults |
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
| 2026-09-26 | `RestAgentStore.updateAgent` keeps the stored `dynamicOrigin`; duplicate and new-agent ZIP import drop it; create still accepts it | The marker authorizes permanent deletion, so only the engine may write it; setup writes it through the loopback REST create | Stripping on create (breaks `AgentSetupService`); a separate engine-only store field (larger change, same outcome) |
| 2026-09-26 | Reserved context keys are read with exact-key lookups (`getData`, new `getExactDataPerStep`) **and** `ClientContextGuard` also drops keys that start with a reserved name | `getLatestData`/`getAllLatestData` match by prefix, so `groupIdSuffix` and friends passed the exact-name guard and were read as the reserved key (PR #831, CWE-863) | Only the exact lookups (the next prefix read reopens it); only the prefix drop (leaves every reader relying on a distant boundary); changing `getLatestData` to exact matching (many callers rely on the prefix, e.g. `httpCalls:*`, `output:*`) |
| 2026-09-26 | `teardown_agent` and end-of-discussion cleanup require a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
| 2026-09-21 | Walk `docs/` rather than list it in `ComposeStackTest`, and assert the sweep's shape | A guard that reads only the top level would have failed over a nested page that *does* document a stack; a false failure is how a guard gets deleted rather than fixed | Listing the top level only (the reviewed version), naming the nested directories explicitly (a third place to forget when one is added) |
| 2026-09-29 | Each Slack approval card carries a random card id in its buttons, recorded with the card; a click must present the id recorded for the current pause | Subject + pause matching let an old card of the same subject approve a newer pause | Storing the posted message `ts` on the record after `chat.postMessage` (a post-then-update window, and a failed update leaves a live card unusable) |
| 2026-09-28 | The Slack workspace comes only from a declared `platformConfig.teamId`, for owned channels too; without one the identity is team-less | A signing secret authenticates the integration, not the workspace in the payload | Trusting an owned channel's payload team_id; scoping team-less ids by integration name (names are mutable) |
| 2026-09-28 | Adoption of raw-id `/v1` chat mappings is opt-in (`adopt-legacy-header-mappings`, default false) | A raw mapping may have been written for an OIDC principal under `http-policy=authenticated`; its origin is unrecorded | Adopting by default (reopens the cross-namespace reach); heuristics on the id's shape |
| 2026-09-28 | `/v1` refuses an OIDC principal carrying the reserved `openwebui:` prefix | Such a principal equals a header-derived identity | Namespacing OIDC principals too |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
| 2026-09-27 | Slack webhook rejects an event whose claimed workspace disagrees with the signing integration's declared teamId; a teamId-less unowned (DM) event is bound team-less, not to the payload team | Payload team_id was attacker-controllable in a validly-signed DM event → cross-tenant memory read (review residual #1) | Trusting the payload team when the integration declares none |
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id mappings are re-keyed only | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id mappings are re-keyed only (opt-in since 2026-09-28) | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Strip `SafeHttpClient.SENSITIVE_HEADERS` on every cross-origin redirect hop in the shared httpcalls client (`HttpClientModule`), and additionally disable redirect-following for a request whose header carries a resolved (reference) credential | Vert.x's redirect handler strips none of these headers cross-origin, so a literal credential written in the config leaked too; the hop-level strip covers every credential source, and the per-request disable is the stronger measure for resolved secrets | Disable redirects only for reference credentials (the first cut: missed literal credentials); strip only the three RFC headers (Vert.x does not even do that) |
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-11-04 | `leaseIsHandedToTheNextQueuedTurn` flaky: a queued turn acquired a second lease | `releaseOrHandOver`'s cleanup matched the lease, which every turn of a handoff chain parks again, so a finished turn removed and released the next turn's parking | Park a per-call `Handoff` token compared by identity | fix/lease-handoff-test-flake |
| 2026-11-03 | Hostname-verified NATS TLS refused every certificate issued for a host name | jnats dials and hands TLS the resolved IP, so endpoint identification compared the certificate with an IP | `ServerNameTrustManager` checks the certificate against the configured host names; `NatsConnectionSecurityIT` covers it | fix/cluster-ops-security |
| 2026-11-02 | Anthropic/Bedrock cached prompt tokens and Gemini/Vertex thinking tokens were not priced; `audit:token_usage.inputTokens` now includes cache tokens, so costs and counts rise for cached or thinking workloads | Provider usage conventions differ and only input/output were read | Normalization in the chat-model decorator; escape hatch `eddi.llm.pricing.provider-normalization=false` | feat/cost-accounting |
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating | The parameter is now ignored unless `eddi.chat.url-token.enabled=true` (M6); the widget says so in the browser console | Set the property, or move to the `postMessage` hand-off | a136e09 |
| 2026-10-15 | A deployment behind a TLS-terminating proxy with neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` advertises an `http` MCP resource identifier | `force-https-scheme` now defaults to `false` (L3) | Set one of those two, or `force-https-scheme=true` | a136e09 |
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
| 2026-09-27 | Updating either of two pre-existing duplicate-named channel integrations of the same type now fails validation until one is renamed | New global per-type name uniqueness check on create/update (Finding C) — benign migration: rename one integration | Rename one of the clashing integrations, then update | fix/security-channel-identity |
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
