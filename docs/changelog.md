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
| [October 2026](changelog/2026-10.md) | 66 | 317 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1217 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## ✨ feat(operator): the operator can look up the running EDDI version and git commit (2026-10-13)

**Repo:** EDDI (`feat/operator-build-version`)

### What

An operator should always know which EDDI build it runs on, including after the
deployment is upgraded. The Platform Operator's system prompt is written at activation and
its tools are generated from the OpenAPI spec, so nothing it was given can say what is
running *now*. It now has a live lookup:

- `BuildInfo` (`engine/runtime`) is the single source: version from
  `systemRuntime.projectVersion`, commit from `eddi.build.commit` (env `EDDI_BUILD_COMMIT`).
  Only a hex string is accepted as a commit; anything else reports `unknown`, because the
  value reaches an LLM through the tool.
- `GET /administration/version` (`IRestBuildInfo`, `eddi-admin`) returns
  `{version, commit, commitShort}`.
- `get_eddi_version` MCP tool (`McpVersionTools`, `eddi-admin`), added to the `McpToolFilter`
  whitelist and `McpToolFilterCoverageTest`.
- An `EDDI <version> (commit <sha>)` startup log line. (A Micrometer info gauge was left out:
  `MetricsDashboardCoverageTest` requires every meter on the generated dashboard and in
  `docs/metrics.md`, which is more than this change warrants.)
- Dockerfile: `ARG EDDI_COMMIT` -> `ENV EDDI_BUILD_COMMIT` and the
  `org.opencontainers.image.revision` label. `ci.yml` passes `--build-arg EDDI_COMMIT=${{ github.sha }}`.
- Manager: `GET /administration/version` added to the operator's `READ_ENDPOINTS`, and one line
  in its system prompt telling it to call the tool instead of answering from memory.

### Design decisions

- **Commit via Docker build arg, not a Maven git plugin.** The jar stays free of per-commit
  stamps (see `docs/build-reproducibility.md`) and no new build dependency is introduced.
  The cost: a jar run outside the image reports `commit: unknown` unless `EDDI_BUILD_COMMIT` is set.
- **Live endpoint rather than text in the operator's prompt.** An upgrade replaces the image;
  it does not rewrite the stored operator agent, so anything baked in at activation goes stale.
- Raising the Manager's backend floor: operator activation now requires an EDDI that serves
  `/administration/version`. Manager and backend ship in one jar, so this only matters for a
  Manager pointed at an older backend.

### Not done

- Manager vitest was not run locally (no `node_modules` in this checkout); CI runs it. Backend:
  `BuildInfoTest`, `McpToolFilterCoverageTest`, `ImportStyleTest` pass.

### Also in this change

- `ui/manager/src/test/mocks/openapi-operations.json` refreshed from the `OpenAPI Snapshot` job's
  artifact, and `operator-revision.json` bumped to revision 9 (the operator's prompt and allow-list
  changed, which is what makes an operator update reach existing deployments).

---

## Chat UI: recover from an unreadable `done`, cap the token-refresh timer (2026-10-13)

**Repo:** EDDI · **Branch:** `fix/chat-ui-review-followups`

Two CodeRabbit findings posted on #948 after its last push, and still open when it merged.

**What changed:**
- **An unreadable `done` payload** (`ui/chat/src/components/ChatWidget.tsx`). `parseDoneSnapshot` returns null for an empty or malformed body, for example when a proxy truncates the frame. The widget then applied it as an empty snapshot. A turn that had streamed no tokens ended with no reply and no quick replies, and nothing said so.
  - The `done` handling moved into `applyDoneSnapshot`.
  - A null payload now only marks the turn (`TurnContext.doneUnreadable`) and ends the read loop.
  - The re-read that already follows every stream (for undo/redo) asks for the current step, which carries the same two fields as `done`: `conversationState` and `conversationOutputs`. On an unreadable `done` that re-read is applied as the turn's outcome.
  - If the re-read fails too, the turn ends with the "response may be incomplete" notice. The draft is not handed back.
- **The token-refresh timer** (`ui/chat/src/api/embed-auth.ts`). Browsers store a `setTimeout` delay as a signed 32-bit value. A JWT whose `exp` was more than about 24.8 days away overflowed it, so the timer fired at once. The refresh request then went out immediately. A host that answered with the same token got the same instant timer back, and the two looped over `postMessage`. The delay is now capped at 2,147,483,647 ms. When a capped timer fires, it schedules again for the remainder instead of asking for a token.

**Design decisions:**
- CodeRabbit suggested throwing on a null `done`, so the stream-failure path handles it. That path treats the message as never consumed: with no tokens it withdraws the bubble and puts the text back in the composer as "could not be sent". A `done` means the server finished the turn, so a resend would run it twice. The re-read recovers the real outcome instead. A test pins this: the throwing variant fails both new tests.
- `parseSseFrame` stays generic. The `done` schema is still interpreted only in `sse-events.ts` and the widget.

**Tests:** `embed-auth.test.ts` adds a fake-timers test with an `exp` 60 days out. `ChatWidget.review-fixes.test.tsx` covers the re-read recovery and the re-read failure. Each test fails with its fix reverted.

---

## 🐛 fix(monitoring): one panel id per panel in the full metrics dashboard (2026-10-13)

**Repo:** EDDI (`fix/metrics-dashboard-panel-id-clash`)

### What changed and why

`main`'s Build & Test went red after #1000 and #951 merged in the same batch: each
added a panel with id 182 to the full metrics dashboard ("Idempotent turn
duplicates by outcome" and "Files refused by upload source"). Grafana keys panel
state, links and repeats on the id, so two panels with one id behave as one.
`MetricsDashboardCoverageTest.panelIdsAreUnique` caught it, and
`DeploymentManifestsTest.splitDashboardPartsAddUpToTheSource` failed with it
because the Kubernetes halves were no longer the source's panels.

The idempotent-turns panel is now id 201, the next free id. The Kubernetes
halves are regenerated with `scripts/sync-monitoring-assets.py`. Nothing
referenced panel 182 by id.

**Files:** [`eddi-full-metrics-dashboard.json`](monitoring/eddi-full-metrics-dashboard.json),
`k8s/overlays/monitoring/dashboards/eddi-full-metrics-dashboard-{1,2}.json`.

---

## feat(llm): every provider parameter, model-aware, with a Manager settings panel (2026-10-13)

**Repo:** EDDI (`feat/llm-provider-parameter-coverage`; backend, Manager, docs, Platform Operator)

### What changed and why

An audit of all 19 LLM integrations against the langchain4j builder APIs showed that EDDI read only a handful of parameters per provider (typically `temperature`, `maxTokens`, `timeout`) and silently dropped everything else: no `topP`, `seed`, `stopSequences`, no reasoning controls (`reasoningEffort`, Anthropic `thinkingType`/`effort`, Gemini `thinkingLevel`), no prompt caching, no Gemini safety settings, no custom headers or body fields. The task `parameters` map is string-only and nothing could reach a builder it did not name. Worse, newer models reject parameters older ones accept (OpenAI reasoning models reject `temperature`, newer Claude models reject non-default sampling settings and manual thinking budgets), so exposing parameters without knowing the model would only have made it easier to break an agent.

- **langchain4j 1.20.2 to 1.22.0 / 1.22.0-beta32.** The OCI GenAI community module caught up with the beta line, so its separate `langchain4j-community-oci.version` property and the `BuildQualityGatesTest` lagging-artifact exception are gone (the guard demands exactly that). 1.22.0 adds OpenAI `promptCacheKey` and Anthropic `cacheAutomatically`/`cacheTtl`; no builder API the code used changed.
- **Every builder reads the parameters its API accepts.** OpenAI/Azure OpenAI, Anthropic, Gemini, Vertex Gemini, Mistral, Ollama, Bedrock, Hugging Face, Oracle GenAI and, through the OpenAI builder, the eight OpenAI-compatible providers. Shared keys keep one name across providers (`topP`, `seed`, `stopSequences`, `reasoningEffort`, `customHeaders`, `customParameters`...). Each builder parses its settings once and applies them to the synchronous and the streaming client alike. The full list is the generated [`docs/llm-parameters.md`](llm-parameters.md).
- **Value parsing is lenient and never logs values.** New `ModelParameterValues` helpers (`stringList`: JSON array or comma list, `objectMap`/`stringMap`/`intMap` for JSON objects, `enumValue`, `booleanOrNull`): a malformed value falls back to the provider default with a warning naming only the key, because a header or body value can be a credential.
- **Model-aware parameter catalog** (`src/main/resources/llm/model-parameters.json`, `ModelParameterCatalog`). Per provider, every parameter with type, range, group and description, plus per-model-version rules (`rejected` or `ignored`, with `when`/`unless` conditions on other parameters). When a model is built, `ChatModelRegistry` removes the parameters the configured model is documented to reject and logs one WARN naming parameter, model and reason, instead of every turn failing with an HTTP 400. `ignored` parameters are kept and warned about. `eddi.llm.parameter-catalog.drop-rejected=false` only warns. Unknown providers and models fail open. A rule exists only where an official vendor source states the behaviour; conflicting or missing sources produce no rule.
- **OpenAI-compatible vendor fields.** xAI, DeepSeek, Moonshot, Qwen, Z.ai, MiniMax, OpenRouter and Groq get their own fields as catalog entries with a `wire` path (Qwen `enableThinking`/`thinkingBudget`, Z.ai `doSample`/`thinkingType`, OpenRouter provider routing and app attribution headers, Groq `reasoningFormat`...), translated into the request body (dotted path means nested object) by `OpenAiCompatibleLanguageModelBuilder`. The user's own `customParameters` still win.
- **A rejected `maxTokens` keeps its cap.** On OpenAI/Azure reasoning models (o-series, GPT-5 and later) `maxTokens` is rejected; instead of silently losing the output and cost limit, the catalog rule carries `migrateTo: maxCompletionTokens` and the value is sent as `maxCompletionTokens` (unless that is set). The truncation re-ask no longer counts a cap the model rejects (it would have sent `max_tokens` and been answered with a 400); for those models, which are capped through `maxCompletionTokens`, there is no base cap to raise and the re-ask is skipped, as for any task without a configured cap.
- **Hugging Face now uses the langchain4j default router** (`router.huggingface.co`) when `baseUrl` is unset, instead of the previous hard-coded inference host, and `modelId` is finally set on the client (langchain4j 1.22 requires it and builds `<baseUrl>models/<modelId>`). `baseUrl` is the service root without `models/`. Existing Hugging Face tasks therefore change endpoint.
- **Fixes found on the way.** Vertex had no `maxOutputTokens` at all; Hugging Face never set `modelId`, which langchain4j 1.22 requires; Bedrock only built its request parameters when `temperature` or `maxTokens` was set; Azure `endpoint` and Vertex `apiEndpoint` were not checked against the cloud metadata service. `FormatRetryRunner` now also reads `maxCompletionTokens` when sizing the truncation re-ask.
- **Manager: a Model settings panel** in the LLM task editor and in cascade steps, driven by a generated TypeScript mirror of the catalog (`npm run gen:llm-params`, drift-tested). Typed controls grouped as sampling, reasoning, output, tools, caching, safety, connection and advanced; filter box; configured count per group; reset to provider default. As the model or a related setting changes it shows, live, which settings the model will reject (control locked, one-click Remove) or ignore. The previous free-form grid remains as "Other parameters" for anything outside the catalog. 51 to 63 new i18n keys in all 11 locales.
- **Cascade steps and the judge model wrote `parameters.model` for every provider**, a key only Ollama reads; the OpenAI, Anthropic and other builders ignored it and ran the task's model. The step card and the judge editor now read and write the provider's own model key (legacy `model` values stay readable and are moved on first edit).
- **Two `main` CI breakages fixed so this PR can go green**: the split Grafana dashboard copies under `k8s/overlays/monitoring/` were stale, and `docs/monitoring/eddi-full-metrics-dashboard.json` carried panel id 182 twice (two merged PRs each added one); 'Idempotent turn duplicates by outcome' is now id 201 and the copies are regenerated.
- **Docs.** New generated [`docs/llm-parameters.md`](llm-parameters.md) (`scripts/gen-llm-parameters-doc.py`; `LlmParametersDocumentationTest` fails when a provider or parameter is missing), and a section in [`docs/langchain.md`](langchain.md).
- **Platform Operator.** The prompt now points admins' "why does my topP/temperature do nothing" questions at the `llm-parameters` docs page and explains that rejected parameters are dropped with a WARN; `operator-revision.json` bumped to 9 so existing deployments are told to upgrade their operator.

### Design decisions

- **Catalog as data, not code**: the Manager needs the same rules to warn before a request is ever made, and a data file can be mirrored, documented and vector-tested in both languages. `ModelParameterCatalogVectorsTest` and the Manager's `llm-parameter-catalog.test.ts` run the same 89 vectors (`src/test/resources/llm/parameter-rule-vectors.json`) so the two evaluators cannot drift.
- **Drop rejected, warn on ignored**: sending a parameter that is certain to produce a 4xx fails every turn of an agent; dropping it with a loud WARN keeps the agent answering. The switch exists because a vendor can change the rule before the catalog does.
- **Fail open**: no rule is written from an unverified or contradictory source, and an unknown model is never restricted.
- **`baseModel` on Azure**: the Azure `model` field is a deployment name that says nothing about the model, so gating needs the underlying id from the designer; it is never sent.
- **`includeThoughts` is not offered for Gemini**: thought parts interact with the thought-signature handling documented in `GeminiLanguageModelBuilder`; `thinkingLevel`/`thinkingBudget` steer effort without asking for the thoughts back.
- **`logprobs` is not offered**: EDDI never reads them, and the streaming OpenAI client has no setter.

### Upgrade notes

- **Keys that used to be ignored are now sent.** Before this change an unrecognised key such as `topK`, `seed`, `frequencyPenalty`, `presencePenalty` or `stopSequences` on Gemini, OpenAI, Azure OpenAI or Mistral was only warned about and never reached the provider. It now does. A config that carried such a key out of habit therefore starts to send it, and `eddi.llm.parameter-catalog.drop-rejected` only protects where the catalog has a documented rule for the model. The one known unverified case is Gemini `frequencyPenalty`/`presencePenalty`: some Gemini models are reported to reject them, no official source names which, so there is no rule and the parameter descriptions say so. Remove the key if the provider answers with an error.
- The startup log warns once per model build about every parameter that is dropped or has no effect; grep for `was not sent to` and `has no effect on`.

### Known gaps and follow-ups

- Mistral `reasoning_effort`, `service_tier`, `top_k`, `min_tokens` and `prompt_mode` cannot be set (no langchain4j setter, no `customParameters`); Ollama `think` is true/false only; Jlama `threadCount` is process-wide in the library and not exposed.
- No rules for Vertex Gemini and for several OpenAI/Qwen/xAI cases whose official sources were silent or contradictory (listed in the research notes of this change); they apply to every model until a source settles them.
- The panel's chrome is translated in all 11 locales, but the catalog's own prose (parameter labels, descriptions and rule notes, about 250 parameters) is English data in every locale. Translating it needs a localized label map and a review process; not part of this change.
- The Manager evaluates the model rules against the model value as typed, so a `${vars...}` reference is not resolved there (the panel says so); the server gates on the resolved id.
- The catalog JSON is hand-maintained; a scheduled check against vendor docs is not part of this change.

---

## NATS cluster stack brought up to main (2026-10-13)

**Repo:** EDDI · **Branch:** `feat/nats-cluster` (PR #956, with #957, #958 and #970 merged into it)

`origin/main` merged into the cluster stack after #937, #942–#954, #990, #993, #996,
#999, #1001, #1008, #969, #936, #967, #1000, #951 and #1002 had landed on it.

### How the cluster code now sits on main's APIs

- **Lost lease → `abandonTurn` (#949).** The lease-lost hook in `ConversationStepRunner`
  raises the cancel flag through `abandonTurn`, the same seam `ITurnAbandonment` and the
  watchdog use, aimed at the turn's own memory rather than looked up by conversation id
  (a late callback must never hit a newer turn). The lease block now runs inside the
  guarded `try` around main's `InFlightRegistration`.
- **Lost lease vs `settleCancelledTurn` (#949).** Both the completion and the failure
  callback ask `discardLeaseLostTurn` first. A lease-lost turn therefore never gets the
  `EXECUTION_INTERRUPTED` CAS that a `/cancel` gets — that state would overwrite the one
  the new holder committed — and is still dead-lettered (`lease-lost`) and answered 409.
  Before, a lease-lost turn that *failed* (rather than completed) took the generic error
  path.
- **Lease-loss reporting on main's single turn path.** `ConversationService` lost its two
  copies of the turn code to `admitTurn` (#949); the lease check is now
  `TurnListener.leaseLost` — skipped/409 for a plain turn, an error event for a stream —
  and runs before `recordTurnOutcome` (#1000), so a turn that is not stored never records
  an idempotency key or a turn error.
- **Erasure redaction (#952).** `pseudonymiseIfErased(entry, queued)` keeps the
  `erasedElsewhere` check: a late entry of a user erased on another node is pseudonymised
  *and* redacted, as on the erasing node. Its drain counters are kept only for users erased
  on this node (nothing would report or clear the others).
- **A2A (#953).** `CachedA2ATaskStore` asks `CacheFactory` for `a2aTaskMapping` and
  `a2aTaskMapping:context`, which cluster mode backs with the `A2A_TASKS` and
  `A2A_CONTEXTS` buckets; a new test round-trips an `A2ATaskRecord` between two nodes.
  `A2A_STATES` is no longer used and is gone from the bucket map and the docs.
- **Undeploy (#990).** `IAgentFactory.undeployAgent` no longer throws checked exceptions;
  the cluster's local undeploy catches `RuntimeException` only.
- **Helm chart 2.7.0** (main is at 2.6.0, from #951). `eddi.updateStrategy` keeps the per-messaging-type
  default and accepts main's object shape; the chart still refuses `RollingUpdate` with
  in-memory messaging, which #938 had documented as an opt-in (see
  [Kubernetes](kubernetes.md#upgrades-replace-the-pod-no-surge)).
- **Operator revision 9** (main is at 8, from #1002): the merged prompt carries both the cluster
  questions and main's admin-action / redaction text.
- **Turn deadline (#996).** [Clustering](clustering.md#sizing) now says that a deadline
  counts from arrival, so time spent waiting for the lease is spent from it.

### Open for review

- `TurnIdempotencyService` (#1000) keeps in-flight keys per node. A duplicate of a running
  turn that reaches another node waits for the lease rather than getting a 409, and the
  persisted-key check happens at admission only, so that duplicate can run the turn a
  second time.

---

## fix(cluster): answer the code-scanning findings on the NATS cluster PR (2026-10-13)

**Repo:** EDDI · **Branch:** `feat/nats-cluster` (PR #956)

### What changed and why

- **Log injection (CodeQL).** `NatsClusterRpc.call` (operation and node id),
  `GroupConversationService` (the lease-less group discussion warning) and
  `AgentDeploymentManagement.awaitClusterDeployment` (agent id, twice) now pass the
  caller-supplied value through `LogSanitizer.sanitize`, like the rest of the codebase.
- **Unused lambda parameters.** The two `whenComplete` callbacks in `RestClusterAdmin`
  that only close the SSE sink use unnamed parameters (`(_, _)`). The `StartupEvent`
  parameter of `AgentDeploymentManagement.subscribeToCluster` stays: a CDI observer
  method needs it, and Java does not allow an unnamed method parameter.
- **Cluster demo scripts.** `scripts/cluster-demo/demo.py` and `mock_llm.py` open their
  files in `with` blocks, drop three unused locals (keeping the `start_conv` call whose
  side effect the undeploy scenario needs), comment their deliberate `except: pass`
  blocks (the log export reports a failed save on stderr), and no longer mix value
  returns with fall-through returns. `mock_llm.py` gains
  a `main()` that holds the request log open for the life of the server.

- `RestClusterAdmin` closes the activity stream after its last `busy` or `expired` event through one `closeAfter` helper. A failed send is logged at debug and the sink is still closed, so the callback no longer declares parameters it ignores.

Behaviour is unchanged apart from two diagnostics: a failed final SSE event is logged at debug, and the cluster demo prints a failed node-log export to stderr.

---

## 🐛 fix(monitoring): split the Kubernetes copy of the Full Metrics Reference (2026-10-13)

**Repo:** EDDI (`fix/observability`)

Merging `main` grew the Full Metrics Reference past what one ConfigMap can carry
through a client-side `kubectl apply`: minified and escaped it reached 247,445 bytes
against this PR's own 245,000-byte budget (the last-applied annotation is capped at
262,144). [`sync-monitoring-assets.py`](../scripts/sync-monitoring-assets.py) now
writes it to the monitoring component as two dashboards, split at the top-level row
boundary that best balances their size (131 KB and 119 KB escaped). Part 1 keeps the
uid `eddi-metrics-all`, so existing links and bookmarks still land; part 2 is
`eddi-metrics-all-2`; each links to the other. `docs/monitoring/` keeps the single
dashboard, which Compose and a manual import use.

`DeploymentManifestsTest` checks that the parts' panels, concatenated, are the
source's panels and that nothing else differs beyond title, uid and links (verified
by dropping a panel from part 2: the test fails). Panel ids 174-177 were also each
held by two panels, from parallel PRs taking the next free id; the later holders now
have ids above the maximum.

---

## A2A streaming uses the conversation-scoped `sayStreaming` (2026-10-13)

**Repo:** EDDI · **Branch:** `fix/a2a-streaming-after-990`

**What changed:**
- **The break:** main stopped compiling after two PRs that were green on their own were merged in parallel.
  - #953 (A2A 1.0) called `IConversationService.sayStreaming(Environment, agentId, conversationId, …)`.
  - #990 removed that environment-qualified overload, which had no other callers.
- **The fix:** `A2ATaskHandler` now calls `sayStreaming(conversationId, …)`, which reads the agent and environment from the stored conversation record. `A2ATaskHandlerTest` stubs that overload.

**Behaviour:** unchanged. An A2A conversation is always started as `startConversation(Environment.production, agentId, …)`, so the record holds exactly the agent and environment the old call passed explicitly. The non-streaming path still uses the environment-qualified `say`, which #990 kept.

---

## 🐛 fix(a2a): stream A2A turns through the agent-qualified `sayStreaming` again (2026-10-12)

**Repo:** EDDI (`fix/main-build-after-concurrent-merges`)

### What changed and why

#949 removed the environment-qualified `sayStreaming(Environment, agentId,
conversationId, …)` from `IConversationService` (no caller outside
`ConversationService` at the time) while #953 (A2A 1.0 / 0.3) added one in
`A2ATaskHandler`. Merged in parallel, `main` stopped compiling. #1008 made it
compile by moving the A2A call onto the conversation-id overload — which runs the
caller-facing checks, `requireConversationAccess` (the conversation-ownership
guard) among them.

A2A is driven by a peer agent, not by the signed-in owner of a stored
conversation, and A2A's non-streaming path still calls the qualified `say()` for
exactly that reason. After #1008 the two paths disagreed: a streaming turn ran an
ownership check the non-streaming turn skips. This restores the qualified
overload on the interface (public on the implementation) and points A2A
`message/stream` back at it, so both A2A paths go through the same entry point —
the behaviour #953 was written and reviewed against.

`A2ATaskHandlerTest`'s streaming tests stub the qualified overload again, so they
fail if the call drifts back to the conversation-id one.

**Files:** [`IConversationService.java`](../src/main/java/ai/labs/eddi/engine/api/IConversationService.java),
[`ConversationService.java`](../src/main/java/ai/labs/eddi/engine/internal/ConversationService.java),
[`A2ATaskHandler.java`](../src/main/java/ai/labs/eddi/engine/a2a/A2ATaskHandler.java),
[`A2ATaskHandlerTest.java`](../src/test/java/ai/labs/eddi/engine/a2a/A2ATaskHandlerTest.java)

---

## 🐛 fix(manager): the RAG editor invented vault keys and saved empty values (2026-10-12)

**Repo:** EDDI (`fix/rag-editor-defaults`)

### What changed and why

The knowledge-base editor's provider and store parameters misled users and broke configs in three ways:

1. **Invented vault references were saved as real values.** Switching the embedding provider or vector store pre-filled every parameter whose *example* was a `${vault:…}` placeholder with that placeholder. A knowledge base then referenced `${vault:openai-key}`, `${eddivault:gemini-key}`, `${vault:mongo-uri}` and so on, keys nobody had created; the setup wizard names its vault entries uniquely, so they never matched a real one. The Gemini example even used the legacy `eddivault` prefix.
2. **Empty fields were saved as `""`, which defeats the backend's defaults.** The same switch, and the "+ key" chips, created every other parameter with an empty string. `EmbeddingModelFactory` and `EmbeddingStoreFactory` default a parameter only when its key is *absent* (`params.getOrDefault`), so `{"model": ""}` reached the provider as an empty model name and failed at ingest, while the field looked defaulted.
3. **Placeholders mixed real defaults with invented samples.** `nomic-embed-text` (the real default) and `embeddings` (a sample) looked identical. pgvector's `table` showed `embeddings`, but the backend defaults to one table per knowledge base (`eddi_kb_<id>`); a user who copied the hint into several knowledge bases made them share one table. `user` showed `${vault:pg-user}` where the default is `eddi`.

The editor now:

- **never pre-fills anything** on a provider or store switch, and restores only what that provider had earlier in the session;
- **lists every parameter the backend knows**, each with what an empty field means: `Default: …` (copied from the factories), `e.g. …` (a sample), or `Required`. Credential fields say "pick a vault secret" rather than suggesting a key name;
- **never saves an empty value**: clearing a field removes the key. Unknown parameters (from an import, a hand edit, or "Add Parameter") stay on screen, in place and focused, until they have a value;
- **flags `""` values saved by the old editor** ("the default is not used") with a one-click "Use default";
- treats `connectionString` as a credential field (Atlas);
- follows the storage layout from #945 (knowledge bases isolated by id): the default table, collection or index shows as `eddi_kbid_<id>` for a per-id knowledge base (and for a new one, which the server stamps with that layout), and as the name-derived `eddi_kb_<name>` for a 6.5 knowledge base. OpenAI's now-honoured `baseUrl` shows its real default, `https://api.openai.com/v1`.

This is the first PR of [`planning/rag-embedding-ux-plan.md`](../planning/rag-embedding-ux-plan.md) (P3, P4, P10, P11). The backend still uses `""` when it is sent. Normalising blank to absent server-side, and publishing the parameter catalogue so these hints stop being hand-copied, is WS1a in that plan.

**Files:** [`rag-editor.tsx`](../ui/manager/src/components/editors/rag-editor.tsx), [`rag-editor.test.tsx`](../ui/manager/src/components/editors/__tests__/rag-editor.test.tsx), 14 new `ragEditor.*` keys in all 11 locales, [`planning/rag-embedding-ux-plan.md`](../planning/rag-embedding-ux-plan.md) (new).

---

## 🐛 fix(rag): in-memory knowledge bases no longer silently lose their data (2026-10-12)

**Repo:** EDDI (`fix/rag-inmemory-store-eviction`)

### What was wrong

`EmbeddingStoreFactory` kept every vector store in one Caffeine cache: 50 entries, a 30-minute idle
TTL, and an `invalidateAll()` on any vault-secret or global-variable change. For an external store
(pgvector, Qdrant, …) eviction drops a client and costs a reconnect. For `storeType: "in-memory"`
the cached object *is* the data, so a knowledge base with no query for half an hour, a 51st
knowledge base, or a credential rotation anywhere in the deployment emptied it, and retrieval
returned no context rather than an error. A scheduled ingestion run did not refill it either: the
pipeline skips a page whose content hash is unchanged, and the hashes outlived the vectors. Only
"Purge state + Run now" recovered.

### What changed

- **Data and clients are kept apart.** In-memory stores live in their own registry in
  `EmbeddingStoreFactory`, with no TTL, no size eviction and no reaction to secret or variable
  changes. The registry is keyed exactly as knowledge-base isolation (#945) addresses every other
  store: the RAG configuration's id, plus the name for a knowledge base still in the 6.5.0 name
  layout (`inMemoryKey`). Two knowledge bases with one name never share an in-memory store, and
  deleting one cannot empty another. External stores stay in the evicting client cache, unchanged.
- **An entry is dropped only when the knowledge base goes, or its vectors stop being valid:** the
  last version is deleted (`RestRagStore.deleteRag`, same "no current version left" rule as the
  schedules); an update moves it to another key (a rename in the name layout, a switch to
  `storeNamespace: "id"`), to another `storeType`, or changes its embedding signature
  (`RestRagStore.updateRag`); or a write arrives under a different embedding signature
  (`getOrCreateForIngestion`, the safety net for imports and other write paths). Retrieval and
  removals (`getOrCreate`) never replace a store; `/ingest`, pipeline runs and scheduled runs write
  through `getOrCreateForIngestion`.
- **Embedding signature** (`embeddingSignature`): store type, provider, the model as
  `EmbeddingParameters.modelName` resolves it (`model` and its alias `modelName` are one setting),
  Azure `deploymentName`, the endpoint (`baseUrl`, or `endpoint`) and the vector size
  (`dimensions`/`outputDimensionality`). The endpoint counts because an OpenAI-compatible server
  decides for itself what a model name means; keys, timeouts, retries and task types do not.
- **Store epoch.** Every in-memory store gets an epoch at creation. Ingestion records the store
  fingerprint (`store-epoch=<uuid>`) per source in a new `rag_ingestion_sources` collection/table
  (`IIngestionStateStore.storeFingerprint` / `recordStoreFingerprint`, removed by `purgeSource`).
  At the start of a run, `IngestionPipeline.reconcileStoreFingerprint` compares it with the store
  the run writes to; on a mismatch it calls `invalidateContent` and records the new fingerprint, so
  every document is re-embedded and no conditional request is sent. After a restart a scheduled
  run therefore refills the store by itself. A preview reports the re-embedding and writes nothing.
- **Deployment-wide cap.** `eddi.rag.in-memory.max-segments` (default 50,000; `0` disables)
  bounds the segments all in-memory stores hold together, enforced in
  `CappedInMemoryEmbeddingStore` under one lock. A write past it throws
  `InMemoryStoreFullException` with an actionable message; an ingestion run stops and reports
  `FAILED` with that message. Nothing is evicted.
- **Replaced and dropped stores are retired.** A run takes its store at the start, so a store
  replaced (embedding signature changed) or dropped (delete, move) mid-run used to keep receiving
  that run's writes: an orphan nothing reads, outside the cap, which counts only registered stores.
  The factory now retires such a store; a later write throws `InMemoryStoreRetiredException` under
  the cap's lock, and the run stops `FAILED` saying the store was replaced or removed. The next run
  re-embeds into the current store, because its epoch no longer matches. (Raised in review.)
  A drop retires the store inside the registry's own atomic removal (`computeIfPresent`), so no
  write lands between "the cap stops counting it" and "it refuses writes"; holding the write lock
  around `remove()` instead would invert the lock order of the replacement path and could deadlock.
- **A run is bound to the store it checked.** The run takes its write store once, at the start,
  and compares that instance's fingerprint (`EmbeddingStoreFactory.fingerprintOf`). A later lookup
  could have returned a newer, empty store after a mid-run drop, with every document passing as
  "unchanged". A run whose bound store was retired by the end reports `FAILED` even if it wrote
  nothing.
- **The embedding signature compares resolved values.** `${vars:…}` references are resolved as
  `EmbeddingModelFactory` resolves them, so a global variable that switches the model replaces the
  in-memory store on the next write instead of mixing two models' vectors.

### Design decisions

- **A new version with the same store key and embedding signature keeps the data.** Versions are
  not part of the registry key: nothing else a version can change (chunking, retrieval settings,
  sources) invalidates stored vectors. A rotated embedding API key is not a new model.
- **Only writes replace a store whose embedding configuration differs.** An agent still pinned to
  an older knowledge base version keeps reading what is there; replacing on a read would let two
  live versions wipe each other's store in turn and re-embed on every run.
- **The fingerprint is per source, in its own row,** rather than a new field on every document row:
  it needs no change to `recordIngested` or `DocumentState`, and invalidating via the existing
  `invalidateContent` also clears the stored validators, so a 304 cannot skip a page the new store
  lacks. It is an opaque, named string so that a follow-up can append an embedding-model
  fingerprint (provider + model + dimension) to the same value.
- **Legacy state** (no fingerprint recorded) is "unknown → re-embed" only when the store has a
  fingerprint, i.e. only for in-memory stores. External stores report none and behave exactly as
  before.
- **The cap fails instead of evicting**, because eviction is the bug being fixed.

### What's next

- A durable built-in store, so the default `storeType` keeps its data across restarts.
- An embedding-model fingerprint in the same value, so changing the model of an external store
  also re-embeds instead of mixing vectors.

**Files:** [`EmbeddingStoreFactory.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingStoreFactory.java),
[`CappedInMemoryEmbeddingStore.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/CappedInMemoryEmbeddingStore.java),
[`InMemoryStoreFullException.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/InMemoryStoreFullException.java),
[`IngestionPipeline.java`](../src/main/java/ai/labs/eddi/modules/ingestion/IngestionPipeline.java),
[`IIngestionStateStore.java`](../src/main/java/ai/labs/eddi/modules/ingestion/IIngestionStateStore.java),
[`MongoIngestionStateStore.java`](../src/main/java/ai/labs/eddi/modules/ingestion/mongo/MongoIngestionStateStore.java),
[`PostgresIngestionStateStore.java`](../src/main/java/ai/labs/eddi/datastore/postgres/PostgresIngestionStateStore.java),
[`RestRagStore.java`](../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RagIngestionService.java`](../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`rag.md`](rag.md#vector-stores), [`configuration-reference.md`](configuration-reference.md#knowledge-bases-rag),
Platform Operator prompt (revision bump).

---

## Navigation guard: one app-level blocker wins over per-page blockers (2026-10-12)

**Repo/branch:** EDDI, `fix/manager-navigation-guard` (merge of `main`)

**What changed**
- #939 (on `main`) and this branch both added in-app unsaved-changes protection. `main` called `useBlocker` from every page through `useUnsavedChangesGuard` and rendered an `UnsavedChangesPrompt` per page; this branch registers dirty state in a store and holds navigation with ONE blocker in `NavigationGuardDialog`.
- The merge keeps the single blocker. `connection-detail`, `workflow-detail`, `config-editor-layout` and `agent-editor-sheet` now just call `useUnsavedChangesGuard(isDirty)`, use the module-level `allowNextNavigation()` for deliberate navigations, and no longer render a prompt. `UnsavedChangesPrompt` and `main`'s per-page navigation test were removed.
- `main`'s role-aware navigation (`useMayOpenScreen`) is kept in the rebuilt sidebar and command palette; both PRs' stable-id section collapse state collapsed into the route-registry ids.

**Design decision:** a router consults only the most recently registered blocker, so a per-page blocker lets any clean guard mounted later silently unprotect a dirty page. Regression tests in `use-unsaved-changes-guard.router.test.tsx` cover a clean sibling mounting after the dirty page and the browser Back button; they fail if each hook registers its own `useBlocker`.

---

## 🐛 fix(hitl): deliver a paused turn only after the pause is persisted (2026-10-07)

**Repo:** EDDI (`fix/hitl-pause-persist-before-response`)

### What changed and why

A turn that ended `AWAITING_HUMAN` told its client "paused" **before** the pause was written. The completion callback fires from inside the pipeline callable and answered the sync `say` response / the streaming `done` event; the paused memory was only stored afterwards by `ConversationStepRunner.runGuardedConversationStep`'s runtime `onComplete` (`populateHitlTimeoutBookmark`, `storeConversationMemoryIfState`, cancel re-check, pause counter, timeout schedule). A client that reacted to the answer by reading `GET /agents/{id}/approval-status` (which reads the **stored** snapshot) could see `state != AWAITING_HUMAN`, `pauseId: ""` and `pauseDetails: null` — the Manager rendered an approvable card with no details, and the chat widget could treat the pause as already resolved. The same ordering applied to a `resume` that re-pauses, and to every consumer of `say` (MCP chat tools, the OpenAI-compatible `/v1` API, group member turns, the quarkus-eddi SDK).

The paused outcome is now delivered from the persistence step:

- `ConversationService.completeTurn` (new) decides when a turn may be answered. A turn whose snapshot is `AWAITING_HUMAN` hands its completion to a one-shot `DeferredDelivery` (new) instead of calling the handler. Every other turn is answered immediately, exactly as before, so ordinary latency is unchanged.
- `ConversationStepRunner.runGuardedConversationStep` flushes the deferral from a `finally` after the commit outcome is known, with the state the store holds: `AWAITING_HUMAN` only when the pause was durably committed and not cancel-raced; otherwise the stored state (lost compare-and-set, cancel conversion to `EXECUTION_INTERRUPTED`, store failure, stale-pause discard). The state cache is updated from the same value, so a lost commit no longer caches a phantom pause. `onFailure` and the task's own `finally` flush a still-pending delivery, so the stream always terminates exactly once.
- `ConversationHitlService.resumeConversation` gets the same treatment for a resume that re-pauses (`resumeDelivery` / `committedResumeState`), flushed from `resumeFinished.onComplete`/`onFailure` and the guarded resume's `finally`.

### Design decisions

- **Narrow: pause turns only.** An ordinary turn's snapshot already matches what the store will hold, and the coordinator serializes the next turn behind this one (a stale-snapshot guard rebuilds a queued turn over the committed document), so holding every response until persistence would add a store round-trip to every turn for no consistency gain. Only a pause is a promise the client acts on immediately.
- The delivery runs inside the same coordinator turn on the same thread, after the commit, so turn ordering and the `processingTurn` release point are preserved; handlers that only complete a future (group member turns, MCP) cannot deadlock on it.
- `DeferredDelivery.deliver` never throws: it runs from `finally` blocks, where an escaping exception would be reported through the Future and flip a healthy conversation to `ERROR`.

### Files

`ConversationService`, `ConversationStepRunner`, `ConversationHitlService`, new `DeferredDelivery`, new `PausedTurnDeliveryOrderTest`, `docs/hitl.md` (ordering guarantee).

### Platform Operator

No change: no endpoint, config field or default changed; only the order in which an already-documented response is sent.

---

## 🐛 fix(operator): approval card could be approved before its details existed; readable tool arguments; decision summary (2026-10-07)

**Repo:** EDDI (`fix/operator-approval-card`), `ui/manager`

### Root cause

The chat stream's `done` event (snapshot `conversationState: "AWAITING_HUMAN"` plus `hitlPausedAt`) is emitted from the completion callback inside `conversation.say()`. The paused memory is only persisted afterwards, in `ConversationStepRunner.runGuardedConversationStep` (`storeConversationMemoryIfState`). `GET /agents/{id}/approval-status` reads the stored snapshot, so the first read after `done` can return `state` other than `AWAITING_HUMAN`, `pauseId: ""`, `pausedAt: ""` and `pauseDetails: null`. `useApprovalStatus` treated that as loaded data and never polled again (global `staleTime` 30 s, no `refetchInterval`), so `ApprovalBanner` rendered the generic RULE layout with Approve enabled until a window-focus refetch happened to fix it. In that window an admin could approve a tool call they had never seen.

### What changed and why

- `describesPause(status, expectedPausedAt)` in [`hitl-pause-binding.ts`](../ui/manager/src/lib/hitl-pause-binding.ts) is the single rule for "this response describes this pause": state `AWAITING_HUMAN` and a matching identity (`Date.parse(hitlPausedAt) === Number(pauseId)`; a blank `pauseId` never matches; a backend without `pauseId` falls back to `pausedAt`).
- `useApprovalStatus` ([`use-hitl.ts`](../ui/manager/src/hooks/use-hitl.ts)) takes `{ verifyPause: true }`. While the response does not describe the pause it re-polls at 500 ms, 1 s, then 2 s, for up to 10 s; withholds the non-matching response from `data`; and returns `detailsPending`, `detailsError` (read failed, or the window ran out) and `retry` (restarts the window and refetches). The operator page uses it. Without the option the hook behaves as before.
- [`approval-banner.tsx`](../ui/manager/src/components/hitl/approval-banner.tsx): new optional `expectToolCall` prop, and a TOOL_CALL detail with zero calls, both keep Approve disabled (Reject stays available) and show `approval-details-missing`. The operator chat passes `expectToolCall`.
- Approving on the operator surface takes at most two steps. Under `requireExplicitPerCall`, a pause with exactly one gated call hides the per-call buttons and the progress chip, and Approve plus the confirm dialog sends an explicit per-call `APPROVED` (with `amendedArguments` if amended). Two or more calls keep the required per-call verdicts, and once all are set the button (now "Submit decisions") submits directly, because the per-call review is the confirmation. Reject still confirms. The blocked, pending, error, missing-details and `expectToolCall` guards disable the button in every variant. The approvals inbox and conversation-detail also pass `requireExplicitPerCall`, so they get the same flow.
- Tool-call arguments are shown through [`hitl-format-args.ts`](../ui/manager/src/lib/hitl-format-args.ts): JSON-in-a-JSON-string (the `setupAgent` `requestBody`) is expanded and pretty-printed, with a Show raw / Show formatted toggle. Display only: the amend flow and the submitted decision still use the original `call.arguments`. A level is reformatted only when its parse is lossless (`minify(text) === JSON.stringify(JSON.parse(text))`), so big integers, `1.50`, `1e3`, duplicate keys and escape spellings are never altered; those levels, and truncated or non-JSON arguments, are shown verbatim.
- [`operator-chat.tsx`](../ui/manager/src/components/operator/operator-chat.tsx): once a decision is submitted the card is replaced by a compact summary (`approval-decision-summary`, per-call outcome, amended flag, note) while the resumed turn runs. The banner stays mounted but hidden so a failed resume brings it back with the per-call choices intact; it and the summary are keyed by the pause, so a re-pause renders fresh.

### Design decisions

- Fail closed: a response that cannot be matched to the pause is "not described". The cost is a Retry click; the opposite error approves a call nobody saw. The timeout becomes the existing `pauseDetailsError` state, never an endless spinner.
- `verifyPause` is opt-in. The approvals inbox and conversation-detail read the paused state and the status from the same store, so they do not have the stream-before-persist race, and a decision from them is still bound to `pauseId` by the pause-binding layer. The compact drawer surface has no Approve control (it only links to the full page), so it was left alone.
- The "request summary" for the operator's own tools was not added: the backend's resolved request preview plus the escalation flags already cover it, and `src/lib/operator/**` is under mutation testing. No operator revision bump: the prompt, allow-list and gate are untouched.
- The formatted view must never misrepresent the call: a lossy parse falls back to the raw text, the safe direction, even when that is a false "lossy".
- The permanent transcript decision entry was not changed.

### Tests

`hitl-pause-binding.test.ts` (`describesPause`), `hitl-format-args.test.ts`, `approval-card-pause-race.test.tsx` (not-yet-stored first read, different pause id, 10 s timeout and Retry, backoff, legacy backend), `approval-banner.test.tsx` (zero calls, `expectToolCall`, argument formatting and toggle, decision payload uses the original arguments), `operator.test.tsx` (page-level race), `operator-chat.test.tsx` (summary after approve and reject, failed resume restores the card, re-pause renders fresh).

---

## 🐛 fix(manager,chat): every approval surface behaves the same (2026-10-07)

**Repo:** EDDI (`fix/operator-approval-card`), `ui/manager` and `ui/chat`

### What changed and why

Phase 1 fixed the operator's approval card; this applies the same rules everywhere a pause is shown or decided, so no surface can drift.

- **One deciding experience.** `ApprovalCard` ([`approval-card.tsx`](../ui/manager/src/components/hitl/approval-card.tsx)) wraps `ApprovalBanner` and owns the lifecycle: pending, details, at most two steps, a `DecisionSummary` receipt while the resumed work runs, the card hidden but mounted so a failed resume brings it back with its choices. It is used by the operator chat, conversation-detail, the approvals inbox rows and the group transcript. `summariseDecision` ([`hitl-decision.ts`](../ui/manager/src/lib/hitl-decision.ts)) covers tool calls, group tasks and group phases.
- **Verify the pause everywhere.** conversation-detail and the inbox now use `useApprovalStatus(..., { verifyPause: true })`, so a not-yet-stored or different pause is never approvable. conversation-detail also re-reads for up to 10 s when opened from a pause link (`?awaiting=1`).
- **Read-only surfaces share `PauseNotice`** ([`pause-notice.tsx`](../ui/manager/src/components/hitl/pause-notice.tsx)): the Manager chat panel and the Workforce thread name the gated tools, link to that conversation's own review page, and clear when the pause is decided elsewhere (`onResolved`, 5 s watch). The chat store now remembers `pausedAt` to tell this pause from another.
- **Group transcript:** Approve is held while only the stream says `AWAITING_APPROVAL` and the persisted conversation has not caught up (no phase or task data yet). A discussion waiting on a human is re-read every 5 s. The Workforce board's review link opens the specific conversation.
- **ui/chat:** `useHitlPolling` took the first approval-status read of a pause that was not stored yet (READY) as "resolved", refreshed to the old snapshot and dropped the card. A READY read before the pause has been seen stored is now distrusted for 10 s (500 ms backing off to 2 s). `PausedCard` stays read-only, and shows tool names only, never arguments.

### Design decisions

- Client-side polling is defence in depth. The root cause (the stream's `done` is emitted before the pause is persisted) is being fixed on the backend separately; these windows then rarely trigger.
- For ui/chat the pause identity is not available to the client (no `pauseId` in its approval-status type, no `hitlPausedAt` tracked), so the signal is "has this pause ever been seen stored". A pause decided before the first read looks identical to a not-yet-stored one, so the doubt ends after 10 s.
- Every deciding 1:1 surface uses `requireExplicitPerCall`, so the single-call and multi-call flows are identical on all of them. Group cards have no tool calls and use Approve plus confirm.

### Tests

`approval-card.test.tsx`, `pause-notice.test.tsx`, `conversation-detail-pause-race.test.tsx`, additions to `approvals.test.tsx`, `discussion-transcript.test.tsx`, `use-groups.test.tsx`, and `useHitlPolling.test.ts` in `ui/chat`. Each safety fix was mutation-checked.

### Follow-up: parity gaps closed (2026-10-07)

- **Receipt scope:** `ApprovalCard` only shows a receipt for the submission that started the current resolution. A failed resume spends it, and a cancel clears it, so a later cancel never reads "You approved".
- **Group approve is bound:** the group page's approve goes through `currentGroupPauseOf` + `bindDecisionToPause` like the inbox. A changed pause refuses, refreshes and does not approve. Group test fixtures use a fixed pause start.
- **Group card keeps its choices:** the card stays mounted (hidden behind its receipt) while the stream takes over, and returns with the per-task toggles if the resume fails.
- **One rule for read-only notices:** the Manager chat panel shows the same review link under `/workforce` (one SPA, so a plain router link), because `PauseNotice` has no per-surface hiding.
- **ui/chat:** `PausedCard` renders at once from the stream's own news ("Waiting for approval", Cancel available, still read-only) and is enriched when approval-status arrives.
- **conversation-detail:** a successful resume re-reads the conversation immediately (explicit refetch plus the hook's invalidation); a test now pins that the receipt hands over to the settled state promptly.

---

## ✨ feat(operator): batched approvals and a REST cascade — a knowledge-base binding takes two approvals, not five (2026-10-07)

**Repo:** EDDI (`feat/operator-batched-changes`)

### What changed and why

The Platform Operator performed a multi-step change one gated call per turn, so the admin approved every step separately: connecting a knowledge base to an agent was five approvals (write the LLM config, write the workflow, repoint the workflow, repoint the agent, deploy). Approval binds to the exact resolved request ("request pinning"), so a step that depends on an earlier step's result cannot be pre-approved; steps that do not depend on each other can share one pause. Two changes use that:

1. **Parallel tool calls, planned first.** The operator's prompt now has a delimited block (`buildBatchingSection`) that tells it to set out the whole plan — every step, which go out together, how many approvals to expect — before the first gated call and to wait for agreement (a plan is a proposal, so it composes with "check / look / verify is read-only"), then to issue independent writes as parallel tool calls in one turn. Nothing in the engine needed to change for this to work: the tool-approval gate classifies every tool call a model emits in one message together and pauses the gated ones as a single batch (`ToolLoopRunner`, `toolApprovalGate.classify` over the whole `toolExecutionRequests()` list); no provider disables parallel calls. See [`hitl.md`](hitl.md#batching--several-calls-one-pause).
2. **`POST /agentstore/agents/{id}/cascade?version=N`** — the REST form of the MCP tool `apply_agent_changes`, so the operator (whose tools are generated from the REST spec) can use it. Body `{ resourceMappings:[{oldUri,newUri}], redeploy, environment, compatible }`; it swaps references through the agent's workflows and into the agent, saving every changed workflow and the agent **once**, optionally redeploying, and answers with the new agent version, the workflow versions written and the deployment outcome. The logic moved out of `McpAdminTools` into a shared `AgentCascadeService` that both surfaces call — no duplicated implementation. A mapping can now also name a workflow itself, so a workflow patched in the same batch is picked up instead of being overwritten by a rewrite of the version the agent still points at.

Result for the knowledge-base binding: batch 1 = LLM config write + workflow write (parallel), batch 2 = one cascade carrying both new versions plus the redeploy — **two approvals instead of five**. With the credential-safe JSON-Patch endpoints (#1009) batch 1 is the LLM-config PATCH plus the workflow write, which stays a `PUT /workflowstore/workflows/{id}` (the workflow store has no PATCH, and a workflow holds no credential references); the shape is unchanged.

### Behaviour changes worth knowing

- **Stricter than before, on both surfaces.** A mapping now must have a `?version=` on both URIs, name the same kind of resource (authority and store path), and its `oldUri` must be a reference the agent really holds. Anything else is refused whole (`400` over REST, an error result over MCP) and **nothing is written** — previously an unmatched pair was silently ignored and the call reported `cascaded`. A `version` that is not the agent's current one is a `409`, detected before any workflow is written (it used to surface only at the final agent update, after the workflows had already been saved).
- **Role.** `eddi-admin` or `eddi-editor`, the class-level gate every agent-store write already has and the same set the writes it composes require; the MCP tool stays `eddi-admin`.
- **Failures are explicit.** If the agent save (or a later workflow write) fails, the error says the agent was NOT changed and names the workflow versions already written (harmless, unreferenced); a store update that reports no new version is an error, never a guessed `version + 1`. A workflow referenced twice by one agent is written once. The agent's workflow reference may only move to another version of the same workflow (as `updateResourceUri` allows), never to a different workflow.
- **Redeploy** goes through the normal deploy path, so quota, EDIT access and the vault-grant check apply identically; a refused or failed deploy is reported in `deployment`, the documents being already written.

### Security decision

Added to the operator's `WRITE_ENDPOINTS`, gated like every write by `http.post:*`. It is not the agent-document PUT that `planning/operator-write-scope-plan.md` excludes: the body is URI pairs and two flags, the backend rewrites only `workflowStep.config.uri` and the agent's workflow list, and the agent is the stored document written back with that list replaced — so `hitlConfig` and every other field are carried forward unchanged. `AgentCascadeServiceTest` pins this by serializing an agent that carries a gate before and after and asserting every field except `workflows` is equal. Aimed at the operator's own agent it is the same self-ungating hinge as `updateResourceUri`; `self-guard.ts` blocks it (the id is a path segment), with a test. The reasoning, and the residual (a `newUri` naming a same-type resource the caller never created is not ownership-checked), is recorded in [the plan](../planning/operator-write-scope-plan.md) in its correction style.

### Manager

- `tool-scopes.ts`: `POST /agentstore/agents/{id}/cascade` in `WRITE_ENDPOINTS`; `grantsAgentCascade` so the prompt names the tool only when it was generated.
- `system-prompt.ts`: plan-first, batching and cascade guidance (no `{…}` in it — the body is a Qute template).
- `cascade-preview.ts` + `cascade-summary.tsx`, shown by `RequestPreview`: each mapping as `llm <id> v1 → v2`, plus "Will redeploy to <environment>" and what `compatible` does to running conversations. The raw body stays below it; an unreadable or truncated body shows no summary rather than a partial one. New strings are translated in all eleven locales.
- `operator-revision.json` bumped (prompt and allow-list changed): existing operators get the upgrade banner.
- `ui/manager/AGENTS.md` (Platform Operator) and `docs/deployment-management-of-agents.md` ("Landing resource changes in one pass"), `docs/mcp-server.md`, `docs/hitl.md` updated.

### Tests

`AgentCascadeServiceTest` (mappings applied, each document saved once, workflow-then-step mapping keeps the rag step, unknown `oldUri` → nothing written, cross-type / versionless / smuggled-query URIs refused, stale version → conflict before any read, `hitlConfig` and all other agent fields unchanged, redeploy outcomes), `RestAgentStoreCascadeTest` (400/404/409 mapping, EDIT access first, role), the MCP suites adjusted for the stricter validation, and Manager tests for the prompt plan shape, `WRITE_ENDPOINTS`, `self-guard`, and the approval card.

**Files:** [`AgentCascadeService.java`](../src/main/java/ai/labs/eddi/configs/agents/AgentCascadeService.java), [`IRestAgentStore.java`](../src/main/java/ai/labs/eddi/configs/agents/IRestAgentStore.java), [`RestAgentStore.java`](../src/main/java/ai/labs/eddi/configs/agents/rest/RestAgentStore.java), [`McpAdminTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java), [`tool-scopes.ts`](../ui/manager/src/lib/operator/tool-scopes.ts), [`system-prompt.ts`](../ui/manager/src/lib/operator/system-prompt.ts), [`cascade-preview.ts`](../ui/manager/src/lib/operator/cascade-preview.ts)

---

## feat(configs): PATCH (RFC 6902) on the LLM, HTTP-call and MCP-call stores (2026-10-07)

**Repo:** EDDI (`feat/operator-credential-safe-edits`)

### What changed and why

The Platform Operator could not edit an LLM configuration that has an API key — nearly all of them. It read the document (`"apiKey": "${vault:anthropic-api-key}"`), added a field, and sent the whole thing back with `PUT`; the call was refused because the body carried a vault reference its own configuration did not write (`ConfigReferenceGuard`). The refusal is correct and is unchanged. It also protects against something worse: had the guard allowed the echo, `ApiCallExecutor.buildRequest` resolves `${vault:…}` in the rendered body (`resolveSecrets`, immediately after the guard) and would have sent — and the store would have kept in a new, exportable version — the **plaintext** key.

The way through is to not send what does not change:

- `PATCH /llmstore/llms/{id}?version=N`, `PATCH /apicallstore/apicalls/{id}?version=N` and `PATCH /mcpcallsstore/mcpcalls/{id}?version=N`, with `Content-Type: application/json-patch+json`, apply an RFC 6902 patch to version `N`, validate the result exactly as `PUT` does and store it as a new version (returned in `Location`). `409` when `N` is not the current version, `400` for a failed `test` operation, a path that does not resolve, an unknown operation or a patched document the model rejects.
- A patch never mentions a credential it is not changing, so the stored reference is carried through untouched, and a patch that tries to *introduce* one is refused by `ConfigReferenceGuard` like any other call.
- `McpApiToolBuilder` only mapped `application/json` request bodies, so a PATCH declared as `application/json-patch+json` produced a tool with no body at all. It now maps `application/json-patch+json` too (plain JSON still wins when both are declared) and describes the parameter as an RFC 6902 patch.

### Design decisions

- **One implementation, three stores.** `RestVersionInfo.patch` (access check, version check, read, store write, `Location` — everything `update` does) plus `ConfigJsonPatcher` (parse, apply, strict re-parse). Each store contributes a three-line method and its own pre-write validation, the same function its `PUT` runs.
- **No new dependency.** `jakarta.json` (Parsson) is already on the classpath through `quarkus-rest`.
- **Own path/media type, no collision.** `output` and `dictionary` already have a `PATCH /{id}` with `application/json` and a different body (`PatchInstruction` SET/DELETE); neither holds credential references, so they are untouched. The three stores here had no PATCH, so the same path with `application/json-patch+json` cannot clash. OpenAPI allows one PATCH operation per path, so a store that already has the other PATCH would need a sub-path.
- **`PUT` stays.** It still works for documents without references and is what the Manager editors use.
- **Server-side approval guard.** `SelfUngatingGuard` (the check of an approved call before it executes, on the Slack, MCP and REST approval paths too) refused an llmstore write carrying `toolApprovals` as a key; a patch names the field in a path, which that test missed. It now also refuses a patch whose `path`/`from` names the field, whose `value` carries it, or that uses an operation other than add/replace/remove/test. The REST endpoint itself stays unrestricted: it cannot tell the operator from an admin with the same role.
- **Bounded.** A patch is limited to 256 KiB and 200 operations.

### Files

[`ConfigJsonPatcher.java`](../src/main/java/ai/labs/eddi/configs/patch/ConfigJsonPatcher.java), [`RestVersionInfo.java`](../src/main/java/ai/labs/eddi/configs/rest/RestVersionInfo.java), [`IRestLlmStore.java`](../src/main/java/ai/labs/eddi/configs/llm/IRestLlmStore.java), [`IRestApiCallsStore.java`](../src/main/java/ai/labs/eddi/configs/apicalls/IRestApiCallsStore.java), [`IRestMcpCallsStore.java`](../src/main/java/ai/labs/eddi/configs/mcpcalls/IRestMcpCallsStore.java), [`McpApiToolBuilder.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java), [`secrets-vault.md`](secrets-vault.md#editing-a-configuration-that-holds-a-reference-patch-not-echo)

---

## feat(operator): edit configs that hold vault references with PATCH (2026-10-07)

**Repo:** EDDI (`feat/operator-credential-safe-edits`)

### What changed and why

The operator's `llmstore` write grant was unusable for any LLM configuration with an API key: it read the document (`apiKey: ${vault:…}`), sent the whole thing back with `PUT`, and the backend refused the echoed reference. It then had to ask the admin to make the edit by hand. The backend now has `PATCH` (RFC 6902) on `llmstore`, `apicallstore` and `mcpcallsstore` (see the backend entry of this date), and the operator uses it:

- **Allow-list.** `PATCH /llmstore/llms/{id}`, `PATCH /apicallstore/apicalls/{id}` and `PATCH /mcpcallsstore/mcpcalls/{id}` are in `WRITE_ENDPOINTS` (`PATCHABLE_EXTENSION_STORES`). `PUT` stays granted: it still works for documents with no reference.
- **Gate guard.** `gate-guard.ts` reads patch operations. An operation whose `path` or `from` names `toolApprovals`, or whose `value` carries it at any depth (which covers replacing a whole task, `/tasks` or the root), is refused as `carries-gate`. `copy` and `move` are refused outright (`unsupported-patch-op`) — they can carry a stored gate override, or a stored credential reference, somewhere the body does not name. A body that is not a well-formed patch, is truncated, or is unpinned is refused like a `PUT` body. The `llmstore` grant is valid only together with this guard, as before.
- **Approval card.** A patch is listed operation by operation (op, path, value), with the document, the version it applies to and the version it will store; the raw body sits behind a toggle.
- **Prompt.** One delimited section tells the operator to edit with PATCH, never to send back a document it read, which operations exist, that copy/move, a written `${vault:…}` reference and `toolApprovals` are refused, and that a 409 means re-read. It is derived from the granted endpoints.
- **Revision.** `operator-revision.json` is bumped one past main's value (7 when this was written).
- **Snapshot.** `openapi-operations.json` gains the three operations.

### Design decisions

- **Server-side too.** `SelfUngatingGuard` now judges patches the same way `gate-guard.ts` does (see the backend entry), so the rule holds on the Slack, MCP and REST approval paths, not only in the Manager. The REST endpoint itself stays unrestricted (it cannot tell the operator from an admin).
- **`copy`/`move` are not reasoned about, they are refused.** Neither is needed to edit a configuration.
- **`ragstore` is not patchable** because it is not writable; it takes the same three-part change when it is.


### Files

[`gate-guard.ts`](../ui/manager/src/lib/operator/gate-guard.ts), [`json-patch.ts`](../ui/manager/src/lib/operator/json-patch.ts), [`tool-scopes.ts`](../ui/manager/src/lib/operator/tool-scopes.ts), [`system-prompt.ts`](../ui/manager/src/lib/operator/system-prompt.ts), [`request-preview.tsx`](../ui/manager/src/components/operator/request-preview.tsx), [`blocked-calls.ts`](../ui/manager/src/lib/operator/blocked-calls.ts), [`operator-revision.json`](../ui/manager/src/lib/operator/operator-revision.json), [`operator-write-scope-plan.md`](../planning/operator-write-scope-plan.md)

---

## 🐛 fix(operator): generated tools could not send text/plain bodies — repointing an agent always failed (2026-10-07)

**Repo:** EDDI (`fix/operator-tool-generation`)

### What happened

The Platform Operator called `updateResourceInAgent` (`PUT /agentstore/agents/{id}/updateResourceUri`) to repoint an agent at a new workflow version. The admin approved; the backend answered `400 resourceURI must carry a version`. The operator then reported that its tool "has no field for that URI, only the agent id, the version and a compatible flag". It was right: the endpoint consumes a `text/plain` body (one bare URI), and `McpApiToolBuilder` mapped only `application/json` request bodies. Any other media type was dropped without a trace, so the tool was generated with no way to send its body. Activation's `findMissingEndpoints` only checks that the path exists in the spec, so it passed.

A second observation from the same transcript: asked to "check" a document, the operator issued two unrequested workflow writes without describing them first (three approvals in one turn) and afterwards admitted it.

### What changed

- **`text/plain` bodies are supported** ([`McpApiToolBuilder`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java)). The body media type is now a small dispatch (`mapRequestBody`): JSON as before, `text/plain` as one declared `requestBody` parameter, sent verbatim with `Content-Type: text/plain`. The parameter description is built from the spec's body description, format and example. An operation offering both is sent JSON. The value is substituted as data into the authored `{requestBody}` placeholder, so `{…}` / `${…}` inside it is never rendered as a template, and a credential reference typed into it is refused by `ConfigReferenceGuard` like one in a JSON body. Request pinning needed no change: `IApiCallExecutor#resolve` and `execute` share `buildRequest`, so the preview and the fingerprint cover the text body.
- **Nothing is dropped silently any more.** An operation that *requires* an input the generator cannot send — a body of another type (`application/zip`, `multipart/form-data`, form-urlencoded) or a required header/cookie parameter — is no longer generated as a tool that can only fail. It is skipped and reported: `ApiBuildResult.unusableEndpoints`, a `WARN` log line, `resources.unusableEndpoints` in the `setup-api` response and `unusableEndpoints` in the API-discovery response. A spec where nothing is usable is rejected with those names. An optional body of an unsupported type is still generated without it. Both `setup-api` and the MCP `create_api_agent` tool call `AgentSetupService.createApiAgent`, which is the only caller of the generator on the provisioning path, so one fix covers both.
- **The OpenAPI spec now describes the two `updateResourceUri` bodies** ([`IRestAgentStore`](../src/main/java/ai/labs/eddi/configs/agents/IRestAgentStore.java), [`IRestWorkflowStore`](../src/main/java/ai/labs/eddi/configs/workflows/IRestWorkflowStore.java)): description, `format: uri`, example, and the mandatory `?version=`.
- **Manager: activation refuses an unusable allow-list** ([`operator.ts`](../ui/manager/src/lib/api/operator.ts), [`use-operator.ts`](../ui/manager/src/hooks/use-operator.ts)). `findUnusableEndpoints` runs the same test against the fetched spec before anything is created, and activation also rolls back on the backend's `unusableEndpoints` report, naming the endpoints either way. A backend that predates the report is covered by the pre-flight.
- **Prompt** ([`system-prompt.ts`](../ui/manager/src/lib/operator/system-prompt.ts)): a request to check/look/verify/read is read-only — report, then propose any change in plain words (what, which document, from which version to which) and wait for agreement before the gated call; never chain an unrequested write onto a read request; when a write fails, say why before trying another. Rule 2 (describe, then call in the same turn) is amended to apply when the change was asked for. The repoint section now says the URI is the bare plain-text `requestBody` and must carry `?version=`, and that a `409` means a newer version was saved and the document must be re-read, never re-sent with a bumped version. Operator revision bumped.

### The failed `updateWorkflow`

The transcript was not available, so the cause is inferred from the code, not reproduced. The generated `updateWorkflow` tool is `PUT /workflowstore/workflows/{id}?version=N` with the whole document as one JSON string. A write there fails in three ways the operator can hit: **409** (`RestVersionInfo.update` → `ResourceModifiedException`: `N` is no longer the latest version — the admin had just "added it", i.e. saved a newer one, so a PUT against the version the operator had read loses); the **pre-send JSON guard** (`HttpCallToolsProvider.rejectUnparseableBody`: a raw newline or unescaped quote in a long document, refused with "re-send"); and `ConfigReferenceGuard` (a `${vault:…}`/`${vars:…}` reference inside the body — not normally present in a workflow document). The first fits "first attempt failed, second succeeded after a re-read" best. Template-escaping is not the cause: a substituted body is never re-evaluated as a template (pinned by `GeneratedTextBodyExecutionTest`). Nothing systematic to fix in the generator; the operator now has the 409 rule above.

**Files:** [`McpApiToolBuilder.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java), [`AgentSetupService.java`](../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`RestApiCallsStore.java`](../src/main/java/ai/labs/eddi/configs/apicalls/rest/RestApiCallsStore.java), [`McpApiToolBuilderTextBodyTest.java`](../src/test/java/ai/labs/eddi/engine/mcp/McpApiToolBuilderTextBodyTest.java), [`GeneratedTextBodyExecutionTest.java`](../src/test/java/ai/labs/eddi/modules/apicalls/impl/GeneratedTextBodyExecutionTest.java), [`mcp-server.md`](mcp-server.md), [`architecture.md`](architecture.md), [`operator-revision.json`](../ui/manager/src/lib/operator/operator-revision.json)

---

## 🐛 fix(operator): a vault key goes in by name, and a call that cannot succeed is never put up for approval (2026-10-07)

**Repo:** EDDI (`fix/operator-vault-key-name`)

### What happened

An admin asked the Platform Operator for an agent on "the anthropic key available". As the admin reported it, the operator copied `${vault:anthropic-api-key}` out of its own LLM config into the `setupAgent` body and was refused (the report says before the approval step; that stage was not reproduced, and from the code a gated call takes the same path every time); told the admin that typing the reference themselves would make it acceptable (false); proposed the call, which the admin approved, and only then was it refused; and finally sent the admin to build the agent by hand. The refusal after an approval is confirmed from the code and is what this entry fixes.

### Why

`ConfigReferenceGuard` refuses any `${vault|eddivault|connection|caller:…}` in a rendered request value that the configuration did not itself write. That is the right control and is unchanged — it is about where a reference came from, not who typed it, so no amount of retyping can satisfy it. Two things around it were wrong:

- **The operator was taught to write references.** Its prompt said to pass `${vault:key-name}` in `apiKey`. The supported path was already there: `vaultKeyName` takes a bare name, leaves `apiKey` empty, requires the entry to exist, and still goes through the deploy-time grant check (`createApiAgent` already carries the same field).
- **The approval was spent before the refusal.** Gate time resolves each gated http call to pin it, which runs the same request build as execution, guard included. But pinning is never allowed to fail a pause, so the guard's refusal was swallowed as "approved unpinned" and the approver was shown a card for a call that was refused the moment they approved it.

### What changed

- **Gate-time refusal.** `ConfigReferenceGuard` now throws its own `ConfigReferenceRefusedException` (still an `IllegalArgumentException`). `ApiCallExecutor.resolve` lets it through unwrapped for a call whose resolution is what execution builds, and `ToolLoopRunner` drops such a gated call from the pending batch before the pause, returning the guard's message to the model as that call's tool result (trace `credential_reference_refused`). Other gated and ungated calls in the batch are unaffected. Any other resolve failure, and a call that can diverge between resolve and execute (pre-request property instructions, batching, retries), still pauses and is approved unpinned.
- **Clearer message.** The refusal now says retrying with the same text cannot succeed whoever typed it, and what to do instead, generically: send the credential's name in a field the endpoint documents (for example `vaultKeyName`), or edit with a partial update that does not repeat it. The guard still knows no endpoint.
- **Operator prompt** (`system-prompt.ts`, revision bumped): a "Credentials in tool-call bodies" section — never put a `${vault:…}`/`${connection:…}`/`${caller:…}` reference in any tool-call body whoever typed it; use `vaultKeyName` with a bare name and an empty `apiKey`; it cannot read the vault so it never claims a key exists; a new agent is not on a restricted key's grant, so its first deploy is refused with `VAULT_GRANT_MISSING` and an administrator grants it in the Manager's grant dialog (the vault-grant UX), while the operator never sets `grantReferencedSecrets`; on a refusal over a reference it explains the rule and, where the endpoint has a key-name field or the store a partial update, retries that way rather than sending the admin to do it by hand. The sentences that taught the opposite in the setup, cheatsheet and "making changes" sections are amended, not duplicated.
- **No approval-card flag.** A body carrying a credential reference can no longer reach the card for any call the operator's generated tools produce (they are always pinnable), so an `escalation-flags.ts` warning would be dead on that surface and would cost translations in every locale.

### Docs

[`secrets-vault.md`](secrets-vault.md#where-vault-references-work) (the provenance rule is about origin, not author; the `vaultKeyName` path and its grant consequence) and [`hitl.md`](hitl.md#request-pinning--approval-binds-to-a-request-not-a-tool-name) (gate-time refusal).

**Files:** [`ConfigReferenceGuard.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceGuard.java), [`ConfigReferenceRefusedException.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceRefusedException.java), [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java), [`ToolLoopRunner.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`system-prompt.ts`](../ui/manager/src/lib/operator/system-prompt.ts), [`operator-revision.json`](../ui/manager/src/lib/operator/operator-revision.json)

---

## ✨ feat(vault): new agents that use a restricted secret — say why a deploy is refused, and grant in one step (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-ux`) · plan: [`planning/vault-grant-ux-plan.md`](../planning/vault-grant-ux-plan.md)

### Why

A vault secret's grant (`allowedAgents`) lists agent ids, and an id exists only after the agent is created. So with `eddi.vault.grant-enforcement=enforce` (the default) **every new agent that uses a restricted secret fails its first deploy**, through every route: the Manager wizard, the setup API, MCP, an import, the Platform Operator's activation. The check is right and stays. What was wrong is how it met the user: the reason went to the server log only, the API answered `{"status":"ERROR"}`, and a retry (the setup API, the operator) created another agent that failed the same way.

### What changed (backend)

- **The refusal reason is kept and returned.** `VaultGrantGate.check()` returns a structured `GrantCheck` (`mayDeploy` is a thin wrapper, unchanged in behaviour). `AgentFactory` stores a `DeploymentFailure` (`VAULT_GRANT_MISSING` with the secret names and the exact grant call, or `DEPLOYMENT_FAILED` with a fixed message that points to the server log, since a cause's message can carry a credential or an internal path) next to the ERROR status; the next successful deploy clears it. The waited deploy returns it as `failure` (and `error`), `deploymentstatus?format=detailed` returns it to a caller with EDIT, and the log line names each secret, why a new agent hits this, and the fix.
- **An unchecked exception while building an agent no longer leaves it stuck IN_PROGRESS** until a restart; it ends in ERROR with the cause.
- **Preflight:** `GET /administration/{env}/deploy/{agentId}/preflight?version=` (EDIT) lists the ungranted secrets before deploying; grant ids only for admins, counts for editors.
- **Append one agent to a grant:** `POST /secretstore/secrets/{tenant}/{key}/grant/agents/{agentId}` (`eddi-admin`, `dryRun`). Idempotent, never touches a `*` grant, compare-and-set with retry so concurrent appends both land, audit-ledger entry per change (`VaultGrantService`).
- **Setup API:** `grantReferencedSecrets` (admin only, 403 otherwise) grants the new agent between create and deploy; `SetupResult` gains `deploymentFailure` and `grantedSecrets`. Without the flag a refused setup now names the secret and tells the caller to grant and redeploy *this* agent instead of running the setup again.
- **MCP:** `deploy_agent` returns the failure code, secret names and fix with a note that a human administrator applies it. No MCP tool can grant, and the flag is not on any MCP tool (asserted by `McpDeployGrantFailureTest`).

**Files:** [`VaultGrantGate.java`](../src/main/java/ai/labs/eddi/secrets/VaultGrantGate.java), [`VaultGrantService.java`](../src/main/java/ai/labs/eddi/secrets/VaultGrantService.java), [`DeploymentFailure.java`](../src/main/java/ai/labs/eddi/engine/model/DeploymentFailure.java), [`DeploymentPreflight.java`](../src/main/java/ai/labs/eddi/engine/model/DeploymentPreflight.java), [`AgentFactory.java`](../src/main/java/ai/labs/eddi/engine/runtime/internal/AgentFactory.java), [`RestAgentAdministration.java`](../src/main/java/ai/labs/eddi/engine/internal/RestAgentAdministration.java), [`RestSecretStore.java`](../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java), [`AgentSetupService.java`](../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java), [`McpAdminTools.java`](../src/main/java/ai/labs/eddi/engine/mcp/McpAdminTools.java). Docs: [`secrets-vault.md`](secrets-vault.md#new-agents-always-need-adding), [`agent-sync-guide.md`](agent-sync-guide.md).

### Design decisions

- **The waited deploy still answers 200, not 409.** Every other failed waited deploy answers 200 with the outcome in the body, all in-process callers and quarkus-eddi read that body, and `failure` carries everything a 409 would. See the plan, §5.1.
- **Granting stays an explicit administrator action** on every path (a dialog, an admin-only flag, an admin-only endpoint); enforcement and its default are unchanged.

---

## ✨ feat(manager): grant-then-deploy dialog, restricted-key badge, and an operator that never leaks an agent (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-ux`), `ui/manager`

### What changed and why

- **One shared grant step wherever the Manager deploys** (`useDeployWithGrants` + `GrantRequiredDialog`): save-and-deploy, the agent card, agent detail, the resource page's deploy action and the agent wizard. It runs the preflight, then (for an administrator) a dry-run append, the append, and a waited deploy. A deploy that still comes back with `VAULT_GRANT_MISSING` reopens the same dialog, once. Non-administrators get the explanation and a copyable request for an admin. A failed deploy now shows its reason, with a **Fix** button for a missing grant. The agent wizard creates with `deploy: false` and settles grants before the first deploy, so a new agent never shows a failed first deploy.
- **Secret picker:** a lock badge "Restricted to N agents" on a restricted key, and a "not granted yet" note when the editor knows its owning agent (`useOwningAgent`, set by the resource page and Agent Studio).
- **Platform Operator:**
  - activation no longer leaks the created agent when its deploy fails. The old `assertProvisioned` threw before the rollback `try`.
  - a restricted model key is granted inside activation, on the same agent, and a retry reuses it.
  - operator agents the Manager did not register are listed with Adopt, Fix and Remove. The marker is `[eddi-platform-operator]` on the descriptor.
  - the preflight endpoint is on the operator's read allow-list. The append-grant endpoint is deliberately not, because no LLM may widen a grant. The prompt explains `failure`. The operator revision moved from 2 to 3.
- `ui/manager/src/test/mocks/openapi-operations.json` is refreshed from this branch's backend (6.6.0, the two new operations).

- `useDeployWithGrants` refreshes the agent queries once a deploy has an outcome (deployed, refused or cancelled), not on the way out of a deploy request that threw: nothing changed on the backend, and that refetch landed in the after-test toast drain on slow CI runners (`resource-detail-save-not-live.test.tsx` failed there about every other run).
- The shared "saved — not live yet" toast (`lib/save-not-live-toast.ts`, added on main meanwhile) deploys through the grant flow too, so the studio, workflow and agent-section saves ask about a restricted key before deploying.

### Still open

- `pages/group-wizard.tsx` and `pages/workforce/workforce-wizard.tsx` still create member agents with `deploy: true`. Their refused deploys now carry the reason, but they do not yet go through the grant dialog.
- Re-enabling a paused operator (`reactivateOperator`) deploys without the grant dialog. If that deploy is refused, the operator screen's banner shows the reason and a Fix button.

**Files:** [`use-deploy-with-grants.ts`](../ui/manager/src/hooks/use-deploy-with-grants.ts), [`grant-required-dialog.tsx`](../ui/manager/src/components/secrets/grant-required-dialog.tsx), [`secret-key-picker.tsx`](../ui/manager/src/components/shared/secret-key-picker.tsx), [`use-operator.ts`](../ui/manager/src/hooks/use-operator.ts), [`operator-discovery.ts`](../ui/manager/src/lib/api/operator-discovery.ts), [`unregistered-operators.tsx`](../ui/manager/src/components/operator/unregistered-operators.tsx). Docs: [`architecture.md`](architecture.md#a-restricted-model-key-and-operators-the-manager-did-not-register).

---

## ✨ feat(manager): group and Workforce wizards and operator re-enable go through the vault grant flow, one question per key (2026-10-06)

**Repo:** EDDI (`feat/vault-grant-wizards`, stacked on `feat/vault-grant-ux`), `ui/manager`

### Why

Under `eddi.vault.grant-enforcement=enforce` an agent that uses a restricted vault key is refused at deploy until it is on the key's grant, and a brand-new agent never is. The agent wizard already created with `deploy: false` and deployed through the grant dialog; three other Manager paths still created or deployed in one step and so hit a failed first deploy: the group wizard (members and moderator), the Workforce wizard (advisors), and re-enabling a deactivated Platform Operator (`reactivateOperator` called a plain `deployAgent`).

### What changed

- **Batch entry point** `runDeployManyWithGrants` / `useDeployWithGrants().deployMany` in [`use-deploy-with-grants.ts`](../ui/manager/src/hooks/use-deploy-with-grants.ts): preflights every target in parallel, merges the grant issues by `(tenantId, keyName)` (the raw reference when it is not a plain vault reference), opens **one** dialog listing each key once with every agent that needs it, then deploys each target with the waited deploy. The tail (deploy, one second chance on an unexpected `VAULT_GRANT_MISSING`, outcome mapping) is now shared by the single and the batch flow (`deployAndSettle`). Returns a per-target outcome; a transport error on one target is that target's `failed`, not a throw that hides the others.
- **Dialog** ([`grant-required-dialog.tsx`](../ui/manager/src/components/secrets/grant-required-dialog.tsx)): `GrantRequest.agents` / `GrantIssue.agentIds` make it show several agents per key (own title, key line, question and non-admin text). One confirmation appends every listed agent to every key it needs; the copy-for-an-admin request lists all the calls. A one-agent request renders exactly as before.
- **Group wizard:** members and moderator are created with `deploy: false`, then deployed together through the batch flow before the group is saved. The per-card "create agent" buttons deploy through the single flow. A member that is created but not live keeps a `pendingDeploy` marker, so the next Create deploys it instead of treating it as done.
- **Workforce wizard:** advisors are created with `deploy: false` and deployed as one batch before the group is saved; advisors a previous attempt created but could not deploy are remembered (`pendingDeployRef`) and deployed on Try Again.
- **`reactivateOperator`** goes through `runDeployWithGrants` like activation: a cancelled grant or a failed deploy is an error and the operator stays off; the config is written only after a READY deploy.
- New `resolveSetupVersion` (agent-setup.ts) and `describeUndeployed` (deploy-outcome.ts); 8 new i18n keys in all 11 locales.

### Design decisions

- **Cancel deploys nothing, for the whole batch** (including members that needed no grant) and the wizard does not save the group around members that are not live. It says the agents were created, that Create again retries the grant and deploy, and that they can be deployed from their agent pages. The wizards stop rather than report success.
- **One confirmation, an append per (key, agent).** The backend's atomic append (compare-and-set) is per agent; replacing a key's whole list with one PUT from here would drop an agent an administrator added a moment ago. "Allow every agent" is still a single PUT per key. Dry runs run first, as for one agent.
- **Targets are deployed one after another**, not in parallel: a second-chance dialog would replace the first, and a waited deploy is not something to fan out.
- **Platform Operator prompt and allow-list unchanged** (no endpoint or behavior the operator describes changed), so `operator-revision.json` is not bumped.

**Tests:** `use-deploy-many-with-grants.test.tsx` (merge per key, one dialog for N agents, an append per agent only after confirm, cancel deploys nothing, second chance per agent, failed target isolated), `group-wizard-grants.test.tsx`, `workforce-wizard-grants.test.tsx` (created undeployed, no deploy before the grant, cancel then retry), and reactivation cases in `use-operator-grants.test.tsx` / `operator.test.ts`. Each was mutation-checked by reverting the behavior it guards.

---

## 🐛 fix(manager): agent detail saves on Save, not on every edit — with review, discard and Save & leave (2026-10-06)

**Repo:** EDDI (`fix/manager-agent-detail-explicit-save`)

### What changed and why

**Agent detail no longer auto-saves.** Every toggle, select and (after a 600 ms
debounce) every keystroke in the agent detail page's ten config sections —
workflows, A2A, security, capabilities, user memory, memory policy, session
management, conversation review, HITL, channels — PUT the whole agent document
on its own. Each PUT creates a new agent version, so one sitting of edits made a
dozen versions, there was no way to back out of a change, and the page behaved
unlike every other editor in the Manager. It now works like the workflow and
resource editors:

- Sections edit one page-level **draft**; nothing reaches the backend until
  **Save**, which writes it as one version and follows it ("Saved as version N").
  **Discard** (confirmed) restores the stored version. Ctrl/Cmd+S saves.
- **Seeing what changed:** an *Unsaved changes* badge in the header, a
  **Modified** pill on every section whose fields differ from the stored
  version, a floating bar at the bottom of the (long) page with the count,
  Discard and Save, and **Review changes** — a diff of the draft against the
  stored version (`ResourceDiffViewer`).
- While there are edits, the version picker and Deploy/Undeploy are disabled
  (deploying acts on the *saved* version; the tooltip says so).
- A **409** keeps the edits, moves the page onto the newer version and re-applies
  only the blocks the user changed (`applyChangedFields`), so another client's
  change survives the next Save.
- The debounce timers and the per-agent save queue that existed only to make
  auto-save survivable are gone (`use-agent-section-save.ts` removed).
- Merged with #967: Save shows its "Saved as vN — not live yet" toast with the
  Deploy action (once per Save instead of once per edit); removing a workflow
  no longer opens a confirmation, since it only changes the draft and Discard
  restores it; #967's rename-in-place, live-version header, workflow names and
  accessibility changes are kept.

**Leaving with unsaved edits.** The app holds in-app navigation with one router
blocker and one prompt (`NavigationGuardDialog`, #968); a page only calls
`useUnsavedChangesGuard`. This adds an optional `onSave` to that hook: when every
dirty page can save on demand, the prompt also offers **Save & leave** and lets the
navigation through only once the save landed. Agent detail and the workflow
editor pass it. (This branch first carried its own app-wide guard, written while
the app was still on `<BrowserRouter>`, then a per-page prompt after #939; both
were dropped as #939 and #968 landed.) While a placeholder document from the
previous version is on screen (#939's `configPending`), the draft takes no edits.

**Also:** the "compat. gen 1" badge left the monospace id line, where it
inherited the code font and duplicated the version chip; it now sits beside the
version picker at the same height as "Generation 1", with the explanation in
its tooltip. The secret access ("Who may use …") dialog's body had no padding
against the dialog border; it now has the `p-5` every other dialog body has.

**Dependency:** `source-map-js` 1.2.1 → 1.2.2 in `ui/manager/.ds-sync/package-lock.json`
(CVE-2026-93749, HIGH — `Trivy Filesystem Scan`). Edited by hand rather than via
`npm install`, which on Windows prunes the Tailwind wasm dependencies from the lock.

### Design decisions

- One draft of the whole document rather than per-section drafts: one Save, one
  version, one diff, and the same shape as the workflow editor.
- Save & leave lives in the shared guard and prompt, not on the agent page, so
  any editor whose save resolves to success can opt in with one argument.

**Files:** [`agent-detail.tsx`](../ui/manager/src/pages/agent-detail.tsx),
[`agent-config-sections.tsx`](../ui/manager/src/components/editors/agent-config-sections.tsx),
[`agent-draft.ts`](../ui/manager/src/lib/agent-draft.ts),
[`unsaved-changes-dialog.tsx`](../ui/manager/src/components/ui/unsaved-changes-dialog.tsx),
[`use-unsaved-changes-guard.ts`](../ui/manager/src/hooks/use-unsaved-changes-guard.ts),
[`navigation-guard-dialog.tsx`](../ui/manager/src/components/shared/navigation-guard-dialog.tsx),
[`edit-grant-dialog.tsx`](../ui/manager/src/components/secrets/edit-grant-dialog.tsx),
[`compatibility-generation-badge.tsx`](../ui/manager/src/components/agents/compatibility-generation-badge.tsx)

---

## feat(llm): recovery observability, resume-path parity and the resilience guide (2026-10-06)

**Repo:** EDDI (`feat/llm-resilience-observability-docs`, the capstone of the LLM turn-resilience series, on top of `feat/llm-circuit-breaker` and `feat/turn-idempotency-structured-errors`)

### What changed and why

R11, R12 and R13 of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../planning/llm-turn-resilience-plan.md)), and the plan's status block. The seven items before it each added a recovery; this one makes them readable and keeps the HITL resume path from being the one place they do not apply.

**R11 - observability**

- **Resolved model names.** `audit:model_name`, the cascade trace's `model` and the circuit-breaker key recorded the literal `${vars:gemini-model}` (the registry resolves global variables late, after the task has recorded its name). `LlmTask.resolveModelName(params, type, resolver)` and the cascade's `resolveVariables` now resolve `${vars:...}` through `GlobalVariableResolver` for everything that records or keys on the model, including the new `eddi.llm.failure` tag. An unresolvable reference (unknown variable, resolver error) keeps the configured text instead of failing the turn over a label. The raw template is not kept in a separate field (not trivial: the audit has one name slot).
- **One structured line per recovery.** New `LlmRecoveryLog` (`modules/llm/impl`) writes `LLM recovery conversationId=... agentId=... class=... action=... outcome=... attempt=... durationMs=...` at INFO, once per repair, same-model re-ask, cascade escalation, skipped (open-circuit) model and served fallback. The call site that performs the action emits it; the failures that triggered it keep their own WARN/ERROR lines. The ad-hoc INFO lines it replaces: the `FormatRetryRunner` "Re-asking the same model" line (now written from `ReaskListener.onReaskDone`, where outcome and duration are known), the cascade's WARN for a skipped circuit and its INFO "escalating: confidence" line (kept at DEBUG, it carries the scores). Values are stripped of whitespace and control characters.
- **Metrics gap-fill** (only what was missing): `eddi.llm.recovery` gains `action=repair` (a reply that parsed only after fence stripping or extraction), `action=circuit_skip`, and `action=escalate` for every escalation reason (`timeout`, `error`, `retryable_error`, `low_confidence`, besides the existing `invalid_output`); `eddi.llm.failure{class,model}` is now also counted for a failed single-model task (a cascade still counts per step, and its overall failure is not counted twice; the circuit's own refusal and control flow such as a HITL pause are not model failures). `eddi.llm.output` and `eddi.llm.turn.deadline.exceeded` already existed. The tag keys of `eddi.llm.recovery` stay `action, outcome, trigger` so Prometheus sees one consistent meter.

**R12 - resume parity.** `LlmTask.executeResume` now applies, to the resumed final answer: the `onError: fallback` guard (the approved tools already ran exactly once through the journal, so only the answer is replaced), `responseValidation` actions (it applied none before), and the R5 same-model re-ask of an empty, invalid-JSON or schema-mismatch reply. The re-ask is one model call over the resumed transcript through `reaskFinalAnswer`; `ToolLoopResumer` records that transcript on a new `ExecutionResult.resumeTranscript` (the loop's final messages without the answer), so no tool can run twice. A task that may re-ask is buffered on resume, as on a live turn. **Not applied on resume, and why:** the circuit breaker (the resume pins the model whose loop paused and settles no ticket), the cascade (the escalation decision was made before the pause), truncation and context-too-long re-asks (the loop reports no finish reason; shrinking after tools ran would replay them), and a turn deadline (a resume starts a turn without one). A resume that recorded no transcript cannot re-ask and goes to `fallbackAction`. Parsing and shape validation (R3, R4) were already shared.

**R13 - docs.** New [`docs/llm-resilience.md`](llm-resilience.md): failure taxonomy, recovery order, the full configuration surface with defaults (verified against the config classes), a complete `langchain.json` recipe for a JSON agent behind a 60-second caller, how to read step keys, trace, log line and metrics, the resume table and the known limits. [`langchain.md`](langchain.md), [`model-cascade.md`](model-cascade.md) (new "Cascade as failover") and [`metrics.md`](metrics.md) link to it and no longer say the resume path has no recovery. `docs/SUMMARY.md` lists it.

**Platform Operator.** An admin will ask "why did my agent answer 'Sorry...'" and "why is model X skipped". The operator prompt's docs map now names `llm-resilience`, and its how-to-work section says which step keys to read (`llm:fallback`, `llm:error`, `llm:output:outcome|reason`, a trace status `circuit_open`) and that the log carries one `LLM recovery` line per recovery. No allow-list change (it can already read conversations, logs and the audit trail). `operator-revision.json` 2 to 3.

**Plan status.** The status block of the plan now marks R1 to R16 implemented with the branch and PR of each, where the implementation differs from the plan text, and the known gaps below.

### Review fixes on the stacked PR

- **Fallback turns in the model's history.** The step-wide `llm:fallback` flag dropped the whole output of a step, including another LLM task's good answer in the same step. Now only output items marked `fallback: true` are dropped (`ConversationOutputExtractor.withoutFallbackItems`, both history paths); a flagged step with no marked item (a `postResponse`-rendered fallback) is still dropped whole, since nothing says which text it wrote.
- **Configured `prompt` after a fallback turn** replaced the whole merged user message and lost the fallback turn's unanswered question; it now replaces only the current input's contents.
- **Re-ask budget** now spends from the turn deadline (plain, cascade, tool-mode and resume re-asks): a re-ask no longer starts with less than `minAttemptMs` left. A cascade whose time left is already inside the reserve gives step 0 the remaining turn time instead of a 1 ms timeout.
- **Interrupt on the deadline-bounded retry path** is restored on the thread and surfaces as `LifecycleInterruptedException` instead of being classified `UNKNOWN` (which let a cancelled step pay a second provider call).
- Keyed streaming requests now forward `llm_retry`; dashboard panels in the cascade row no longer overlap and the circuit-transition series is a `rate`.
- Not changed: the Manager editor's selectors still omit `retry` (types only, as documented): adding it is UI work with three i18n gates and is a follow-up.

### Design decisions

- One helper rather than six `LOGGER.infof` calls, so the line has one shape and tests can assert "exactly one line per recovery" through an observer hook (`LlmRecoveryLog.observer`, null in production).
- The retry line is written by a `ReaskListener` default method (`onReaskDone`), not by `FormatRetryRunner`, because only the caller knows the conversation and agent ids.
- `ExecutionResult` grew a fifth component rather than reusing `toolExchange`: the exchange is "tools only" by contract, the resume transcript is "everything the model saw".
- `circuit_skip` is a fifth `action` value next to the plan's four: it is a recovery (the engine avoided a call) and an operator counting escalations should not have to subtract it.

### Known gaps and follow-ups

- The streaming path is still not bounded by the turn deadline and uses the plain backoff.
- R15's `onInvalidJson: "retry"` with `maxRetries: 0` warning is not implemented, and its `onError` check still reads the field reflectively.
- The R14 WireMock cascade IT was not written.
- No dashboard panel dedicated to `action=repair` / `circuit_skip`; the full-metrics dashboard has pre-existing duplicate panel ids (158/159, 168 to 172) on `main` that this change leaves alone. Ids 174 to 181 (this series) are unique.
- Manager UI has types only for the new task fields.

---

## fix+feat(migration): carry 5.x LLM defaults over, unencoded URL hosts, deploy-time compatibility check (2026-10-06)

**Repo:** EDDI (`feat/v5-compat-deploy-checks`)

### What changed and why

Findings from a production 5.x to 6.x migration: valid 5.x configuration that 6.x accepts, deploys
READY, and then handles differently on the first real turn.

- **JSON mode (#7).** A migrated LLM task with `parameters.responseFormat: "json"` and no
  `convertToObject` gets `convertToObject: "true"`; `responseFormat` stays. Without it the model
  wrapped its JSON in a Markdown code fence.
- **Tools default (#8).** A migrated LLM task without `enableHttpCallTools` / `enableMcpCallTools`
  gets both written as `false`, which is what 5.x did (6.x defaults them to `true`, turning a chat task
  into a tool-calling one, which on Gemini also silently drops JSON mode). One transform,
  `LegacyDocumentMigrations.llm()`, runs in the V6 collection sweep (`llms` and `llms.history`, a
  database 5.x wrote) and in the import of a ZIP whose workflow still references the 5.x LLM
  authority. Tasks authored in 6.x are never touched. Idempotent.
- **Templated URL host (#9).** `https://{properties.apiHost}` was rendered as `api%2Eexample%2Ecom`
  because every substituted value was percent-encoded as a path segment. The authority is now
  rendered separately and keeps letters, digits, `.`, `-`, `_`, `:`; `/ @ ? #`, spaces and `%` are
  still encoded, so a value cannot add a path, userinfo or query to the host. The resolved URL is
  unchanged for the SSRF checks (private/loopback refused with
  `eddi.security.ssrf-protection.enabled`, cloud metadata always).
- **Deploy-time compatibility check (#6, #11).** `AgentCompatibilityLint` runs when an agent is
  deployed, reads its LLM, API-call, property-setter and output configs, and reports: a template in an
  LLM `apiKey` (task, cascade steps, judge model; vault/vars/connection references are fine),
  `responseFormat: json` without `convertToObject`, `convertToObject` with tools on a provider that
  cannot combine them (from `JsonResponseFormatPolicy`), strings that do not parse with the restricted
  runtime Qute engine, and left-over `[[${...}]]` Thymeleaf. Warnings only: one WARN line per
  finding, and a new additive `warnings` list on each entry of the deployment-status listing
  (`AgentDeploymentStatus`, `IAgent.getDeploymentWarnings()`). It never changes the status or blocks a
  deployment, and a failing lint is swallowed.
- `docs/upgrading-from-5x.md` section 6.1: every behaviour change with before/after config.

### Design decisions

- The 5.x origin of an LLM document is known, not guessed: the V6 sweep runs once over a database 5.x
  wrote, and an import counts as 5.x only when the workflow names its LLM step by the 5.x authority.
  A v6 document with an absent flag keeps meaning "on".
- The lint does not report a templated URL host (#9, now supported) or `fromObjectPath` naming
  `responseObjectName` (#5, handled in the engine).
- `LlmTask.TEMPLATE_SKIP_PARAMS` and `CONFIG_REF_MENTION` became public so the lint shares the
  engine's own definition of "credential" and "reference".
- A templated host is a trust decision: whoever controls the substituted value chooses where the
  call goes. Documented; the metadata address stays refused regardless.

### Follow-ups

- `GET /administration/preflight` for an unmigrated 5.x database (out of scope here).
- Showing `warnings` in the Manager's deployment view.
- The Postgres backend has no 5.x data, so the sweep has no Postgres twin; only ZIP import applies.

**Files:** [`LegacyDocumentMigrations.java`](../src/main/java/ai/labs/eddi/configs/migration/LegacyDocumentMigrations.java),
[`V6RenameMigration.java`](../src/main/java/ai/labs/eddi/configs/migration/V6RenameMigration.java),
[`RestImportService.java`](../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`AgentCompatibilityLint.java`](../src/main/java/ai/labs/eddi/engine/compat/AgentCompatibilityLint.java),
[`CompatibilityRules.java`](../src/main/java/ai/labs/eddi/engine/compat/CompatibilityRules.java),
[`upgrading-from-5x.md`](upgrading-from-5x.md)

---

## fromObjectPath now keeps an object or list, so an LLM response object can be stored as a property (2026-10-06)

- Repo: EDDI, branch `fix/fromobjectpath-llm-response-object`.
- **Symptom (reported from a production 5.x deployment moved to 6.x).** An LLM task with `responseObjectName: "aiOutput"`, `convertToObject: "true"` and a `postResponse` property instruction `{ name: "aiOutputObject", fromObjectPath: "aiOutput", convertToObject: true }` left `{properties.aiOutputObject.htmlResponseText}` empty.
- **Root cause.** The path itself resolved fine: `PathNavigator` reads the template data, which the LLM task fills with the converted response (a Map) under `responseObjectName`. The loss was in `PrePostUtils.executePropertyInstructions`: only a `String` was carried forward, and every other value reached through `fromObjectPath` fell into an `else` branch that replaced it with `""`. A Map or List therefore became an empty-string property. The same applied to httpcalls and MCP calls, which share the helper, but their responses are usually strings that `convertToObject` parses, so it went unnoticed. Workaround that worked: `valueString: "{json:serialize(aiOutput)}"` with `convertToObject: true`.
- **Fix.** An object or list found through `fromObjectPath` is now stored as it is (`PrePostUtils`). Strings (including `convertToObject` parsing), numbers and booleans behave as before, a path that resolves to nothing still stores an empty string, and `scope: "secret"` still refuses a non-string value. Tests: `PrePostUtilsFromObjectPathObjectTest`. Docs: `docs/langchain.md`, `docs/httpcalls.md`.
- **Follow-up (not done).** A number or boolean read through `fromObjectPath` is still stored as `""`; changing it is a separate compatibility decision.

---

## Idle sweep no longer loads every open conversation; bulk "end inactive conversations" (2026-10-06)

**Repo:** EDDI | **Branch:** `fix/idle-conversation-sweep-oom`

**Problem.** `AgentDeploymentManagement.endOldConversationsWithOldAgents` (the daily idle sweep, first run five minutes after boot) called `loadActiveConversationMemorySnapshot`, which deserializes every open conversation of an agent version in full. A production 5.x deployment with tens of thousands of open conversations of several hundred KB each ran out of heap shortly after boot. `GET /conversationstore/conversations/active/{agentId}` had the same shape, and so did `POST /administration/{env}/undeploy/{agentId}?endAllActiveConversations=true`, which lists through it.

**What changed.**
- `IConversationMemoryStore` gets `loadOpenConversationActivity` (keyset-paged projection: id, state, agent, version, last-interaction time), `loadConversationActivity` (the same for one id) and `endIdleConversations` (the bulk end), implemented for MongoDB and PostgreSQL. The last-interaction time is computed by the database as the newest `conversationSteps[].workflows[].lifecycleTasks[].timestamp`, the same quantity the sweep always aged conversations by. MongoDB converts each timestamp with `$convert`, because the persistence mapper stores dates as ISO-8601 strings while older documents can hold BSON dates or epoch milliseconds; PostgreSQL reads numeric epoch millis and ISO strings.
- The idle sweep reads batches of 200 projections, re-checks one conversation's projection (not a full load) right before ending it, and keeps every existing rule: `AWAITING_HUMAN` is spared, the agent descriptor is the fallback age, "cannot prove it is idle" never ends a conversation, state compare-and-set, the `LocalDate` arithmetic. It now also records the end reason `idle` (new `IConversationService.END_REASON_IDLE`), best-effort.
- `RestConversationStore.getActiveConversations` is built from the projection, so `/active/{agentId}` no longer loads a conversation in full (contract unchanged). Undeploy-with-end lists straight from the store projection (`RestAgentAdministration.openConversationsOf`), skipping the `/active` listing's per-conversation descriptor read for a last-interaction time it does not use.
- New `POST /conversationstore/conversations/end-inactive?inactiveForDays=N[&agentId=...][&dryRun=true]`, `eddi-admin` only (like the bulk delete; there is no REST role hierarchy): ends every `READY` conversation idle for longer than N days with end reason `idle`, returns `{"count": n, "dryRun": bool}`, validates `inactiveForDays >= 1`, and writes an audit-ledger entry. MongoDB: one `updateMany` with a server-side `$expr`; PostgreSQL: batched `UPDATE` (500 rows per statement and transaction) so a 22 GB backlog does not become one transaction.
- `undeployThisAndAllPreviousAgentVersions` no longer aborts on the first failing or refused version: every version is attempted, then a `500` naming the failed versions (or a `409` for refusals) is returned.

**Design decisions.**
- *Descriptors are not touched by the bulk end.* Retention deletes ended conversations by the descriptor's `lastModifiedOn`; rewriting descriptors would be one write per conversation (defeating the single operation) and would restart the retention clock at "now". The existing sweep never touched descriptors either, so retention keeps counting from the last interaction for both. The listing re-derives state from the conversation, so it shows `ENDED` immediately. Consequence, documented: a conversation idle for longer than the retention is deleted by the next retention run.
- *The bulk end only touches `READY`.* `AWAITING_HUMAN` needs the HITL-aware end (timeout schedule, bookmark, oversight audit), `IN_PROGRESS` is running, and a conversation with no timestamp cannot be proven idle. Conversation-state cache entries (30 s TTL) are not invalidated, as for the sweep.
- *The sweep stays per-conversation* (projection + compare-and-set) rather than calling the bulk method, because it must keep the descriptor age fallback and the `AWAITING_HUMAN` accounting; both use the same server-side last-interaction computation.
- *Undeploy schedules:* `disableSchedulesForAgent` is already gated on the call leaving no deployed version of the agent in the environment (main already had this, with tests); nothing to change.
- *Platform Operator:* the new endpoint is a destructive deployment-wide bulk mutation, so it is deliberately not added to the operator allow-list; no operator revision bump.

**Operators.** Anyone who disabled the sweep with `maximumLifeTimeOfIdleConversationsInDays=-1` because of the out-of-memory failure can re-enable it. See [Upgrading from EDDI 5](upgrading-from-5x.md#ending-a-backlog-of-idle-conversations-in-one-call).

**Follow-ups.** The listing behind `/active/{agentId}` still reads one descriptor per conversation (slow on very large backlogs, memory-safe). The idle sweep's check-then-act window between its re-check and the compare-and-set remains (a revision-conditional narrow update would close it).

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
