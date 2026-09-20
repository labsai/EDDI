# Inference Stack Fit — Nine Proposed Technologies, Assessed

> **Status: assessment, not approved work.** Nothing here is scheduled. This document
> evaluates an externally proposed list of nine inference-layer technologies against what
> EDDI 6.4.0 actually is, and maps each one onto work already triaged in
> [`langchain4j-recommendations.md`](langchain4j-recommendations.md). Three items are
> recommended, four are recommended against, and two are reframed. Read §1 and §3; the
> rest is evidence.

**EDDI 6.4.0** · langchain4j 1.20.0 / 1.20.0-beta30 · Quarkus 3.39.3 · Java 25 · 2026-09-20

Confidence tags, matching [`langchain4j-recommendations.md`](langchain4j-recommendations.md):
`[src]` verified from EDDI source or a resolved jar/classfile · `[docs]` from upstream docs
or repository metadata · `[inf]` inferred from verified facts · `[unv]` unverified.

## Prerequisite reading

1. [`langchain4j-recommendations.md`](langchain4j-recommendations.md) — **read this first.**
   It already triages most of what the proposal raises, with effort sizes and priorities.
2. [`guardrails-architecture.md`](guardrails-architecture.md) — the older guardrails design.
   §6 below explains where it and the document above disagree, and which one wins.
3. [`../docs/project-philosophy.md`](../docs/project-philosophy.md) — the nine pillars.
4. [`../AGENTS.md`](../AGENTS.md) §4.2 "Not Everything Is a Lifecycle Task", and §2 rule 7
   (verify against `pom.xml`, never against grep counts).

---

## 1. Bottom line

**The proposal is largely a rediscovery of work EDDI has already triaged, at the wrong
priorities, plus four items EDDI already rejected or deferred for stated reasons.**

Of the nine technologies, exactly **one** is genuinely zero-effort and useful today
(OpenAI-compatible serving: vLLM, llama-server). One is a ten-line fix with a
precondition (TEI embeddings). One is a real and unclaimed gap (W3C trace propagation).
The rest either duplicate existing triage entries, rest on a false premise about EDDI's
stack, or would commit the project to shipping and endorsing third-party model weights.

Three corrections matter more than the ranking:

**Jlama is not "already supported" — it is advertised and broken.** `jlama-core 0.8.4`
resolves and runs, but with no `--add-modules jdk.incubator.vector` in the image it
silently selects the pure-scalar `NaiveTensorOperations` backend. Details in §4.1. This is
a live docs-versus-shipped defect, not an opportunity.

**The proposal's headline framing — Jlama as the air-gapped path — is refuted by EDDI's
own prior analysis.** `JlamaEmbeddingModel`'s constructor downloads weights from Hugging
Face at runtime ([`langchain4j-recommendations.md`](langchain4j-recommendations.md) I1,
`[src]`), so it is not air-gapped. The genuine offline path is in-process ONNX *embeddings*
(I1, ~35 MB without weights, measured), which the proposal does not mention.

**Inference is the wrong axis of investment.** Every serving stack worth supporting already
speaks the OpenAI wire protocol, which EDDI already speaks. Meanwhile the default
single-model path emits no latency, token or cost metric; there is no guardrail on user
input or model output; and two planning documents give contradictory guardrail designs.
Those are governance gaps, which is where EDDI's differentiation actually lives.

---

## 2. What was verified, and how

Every claim tagged `[src]` below was checked against this working tree or a resolved
artifact, not against documentation. The method matters, because the proposal's three
largest errors are all of the kind AGENTS.md §2 rule 7 warns about — inferring current
behaviour from a name that appears in the codebase.

| Claim source | Method |
|---|---|
| Provider `baseUrl` support | Read [`OpenAILanguageModelBuilder`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java) — both `build` and `buildStreaming` |
| Embedding `baseUrl` absence | Read `buildOpenAi` in [`EmbeddingModelFactory`](../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java) |
| Jlama backend selection | Unpacked `jlama-core-0.8.4-sources.jar` from the local `.m2`; read `TensorOperationsProvider` and `MachineSpec` |
| Jlama classfile level | `od` on `PanamaTensorOperations.class` — major 64 (Java 20), minor 0 (**not** preview-compiled) |
| Jlama release cadence | GitHub releases Atom feed (the HTML page omits years) |
| Artifact availability | `repo1.maven.org` `maven-metadata.xml` for each artifact |
| langchain4j `JsonSchema` API | `javap` against the resolved `langchain4j-core-1.20.0.jar` and `langchain4j-open-ai-1.20.0.jar` |
| OTel default state | Read [`application.properties`](../src/main/resources/application.properties) |
| LLM metrics coverage | Grepped every `meterRegistry` call site under `modules/llm/` |

A second reviewer (an independent model, prompted adversarially) contributed §4.4's
egress finding, §5.1's diagnosis that the observability decorator is inert by default, and
the challenge to the reranker framing. Its claim that EDDI has "not a single Micrometer
metric for LLM latency, tokens or errors" was **wrong** and is corrected in §5.1; its
claim that OTel being off makes `gen_ai.*` work pointless was an overstatement and is
qualified there too. Both corrections came from re-reading the source.

---

## 3. Verdict table

| # | Technology | Proposal's claim | Verified verdict | Existing triage entry |
|---|---|---|---|---|
| 1 | **vLLM / llama-server** | Zero effort | ✅ **Correct.** Docs page only | — |
| 2 | **HF TEI (embeddings)** | Zero effort, via RAG `baseUrl` | ⚠️ **Wrong — no such field.** ~10 lines, gated on I5 | I5 |
| 3 | **Jlama** | Zero effort, already supported | ❌ **Advertised and broken.** Now fixed — see the §4.1 decision | I1 (refutes the air-gap premise) |
| 4 | **OpenShift AI / KServe** | Configuration only | ✅ **Correct** — but it is a deployment doc, not a feature | — |
| 5 | **TrustyAI guardrails gateway** | Feeds the audit ledger | ❌ **Cannot.** Transparent proxy yields no verdict. Detector API instead | R0 |
| 6 | **OTel GenAI semconv + traceparent** | Low effort | ⚠️ **Right target, deprecated attribute names, wrong effort.** Needs an LLM span first | R5 + D11 |
| 7 | **In-process ONNX reranking** | Low effort | ❌ **Already deferred**, on better-stated grounds | R3b (deferred, P4) |
| 8 | **Infinispan vector store** | Low effort | ❌ **Wrong artifact, confused rationale.** Reject | — |
| 9 | **GBNF / Outlines / XGrammar** | Medium effort | ⚠️ **Wrong lever.** `response_format: json_schema` instead | I4 |

---

## 4. Item-by-item

### 4.1 Jlama — advertised, shipped, and running on the scalar path

This is the most consequential finding, because EDDI makes the claim publicly.

**What is true.** [`JlamaLanguageModelBuilder`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/JlamaLanguageModelBuilder.java)
exists and `LlmModule` registers `"jlama"`. `[src]` The surface is wide: `jlama` is offered
by [`AgentSetupService`](../src/main/java/ai/labs/eddi/engine/setup/AgentSetupService.java)
(the setup API and the Manager's agent wizard), by
[`McpSetupTools`](../src/main/java/ai/labs/eddi/engine/mcp/McpSetupTools.java) (so an agent
can choose it), and by [`CreateSubAgentTool`](../src/main/java/ai/labs/eddi/modules/llm/tools/CreateSubAgentTool.java).
`isLocalLlmProvider` treats it as needing no API key. [`../docs/langchain.md`](../docs/langchain.md)
names it ten times and counts it toward "12 providers".

**What is broken.** Jlama picks its tensor backend at first use, in
`TensorOperationsProvider.pickFastestImplementation()`: `[src]`

1. It tries `NativeSimdTensorOperations`, which needs `com.github.tjake:jlama-native` on
   the classpath. EDDI does not have that artifact — `pom.xml` pulls only `jlama-core`
   transitively via `langchain4j-jlama`. It logs
   *"Native operations not available"* and moves on. `[src]`
2. It falls back to `MachineSpec.VECTOR_TYPE`, which reads
   `FloatVector.SPECIES_PREFERRED` inside a `catch (Throwable)`. Without
   `--add-modules jdk.incubator.vector`, that throws, the catch logs
   *"Java SIMD Vector API *not* available. Add --add-modules=jdk.incubator.vector"*,
   and the type stays `NONE`. `[src]`
3. `NONE` selects `NaiveTensorOperations` — scalar Java matrix arithmetic. `[src]`

[`src/main/docker/Dockerfile`](../src/main/docker/Dockerfile)'s `JAVA_OPTS_APPEND` sets
neither flag, and neither does `mise.toml` or `pom.xml`. `[src]` So every containerised
EDDI running a Jlama agent is doing scalar inference. It does not crash — it is simply far
too slow to be usable interactively. `[inf]`

**One thing the proposal and I both nearly got wrong.** `--enable-preview` is *not*
required. `jlama-core` 0.8.4's classfiles are major version 64 with minor version 0 — plain
Java 20 bytecode, no preview marker. `[src]` Only `--add-modules jdk.incubator.vector`
(and, for the native path, the `jlama-native` artifact plus `--enable-native-access`) is
needed. Asserting `--enable-preview` would have been wrong, and on Java 25 it would also
have been actively harmful.

**Why this is still not an investment opportunity.**

- `jlama-core` is pinned at **0.8.4, released 2025-01-01** — no release in the last twelve
  months, and ten of ten releases authored by a single maintainer. `[docs]`
- The README states Java 20+ and "Java 21 preview features"; it does not mention Java 24
  or 25. EDDI is on Java 25. `[docs]`
- `langchain4j-jlama` is on the beta line, not GA. `[src]`
- The air-gap argument does not survive: `JlamaEmbeddingModel`'s constructor downloads
  weights from Hugging Face. `[src]` via I1. Where those weights land on an OpenShift
  restricted-SCC pod with a read-only root filesystem and a random UID is unanswered. `[unv]`
- `jdk.incubator.vector` is still an incubator module and will keep churning until
  Valhalla lands; each JDK bump is a fresh compatibility question. `[docs]`

**Recommendation.** Pick one, this quarter, and close the gap either way:

- **(a) Support it honestly** — add `--add-modules jdk.incubator.vector` to the Dockerfile
  and `mise.toml`, add `com.github.tjake:jlama-native` for the fast path, and add a smoke
  test that actually runs a tiny model so the claim cannot silently regress. Accept the
  maintenance risk explicitly.
- **(b) Demote it** — mark `jlama` experimental/unsupported in
  [`../docs/langchain.md`](../docs/langchain.md), drop it from the provider count, and
  remove it from the setup/wizard/MCP/sub-agent chooser lists so nobody selects it by
  accident.

**(b) was recommended** on the grounds that Ollama and llama-server give the same
local-inference story with an active upstream, no incubator-API exposure and no GraalVM
native-image question. Either way, the present state — advertised everywhere, working
nowhere — is the one option that must not persist.

> **Decision (2026-09-20): (a), with one amendment.** Jlama is fixed rather than demoted.
> Two claims in this section did not survive implementation and are corrected below the
> decision note: the `user.home` writability argument, and the `threadCount` one.
> `com.github.tjake:jlama-native` is deliberately **not** added: the Vector API path is
> pure Java and gets most of the benefit, while the native artifact would put
> platform-specific, glibc-sensitive shared objects into a digest-pinned UBI image, add
> Trivy scan surface and require `--enable-native-access`. The flag now ships in the
> image, both Maven forks and `mise run dev`, guarded by `JlamaRuntimeFlagsTest` and
> `JlamaRuntimeSupportTest`. The "smoke test that runs a tiny model" is **not** included —
> it would download gigabytes of weights from Hugging Face on every CI run. What replaced
> it is stronger per unit of CI time: an assertion that the Vector API is genuinely
> resolvable in the test JVM, which is the single thing that was silently false.
>
> The same pass also exposed `modelCachePath`, `threadCount`, `quantizeModelAtRuntime`,
> `workingDirectory` and `workingQuantizedType`, which Jlama accepts and EDDI was dropping.

> **Two corrections from implementation.** Both were plausible, and both were wrong.
>
> **`user.home` is writable in this image.** Verified as UID 185 and as an arbitrary
> OpenShift-style UID: the base image sets `HOME=/home/default`, the JDK falls back to it
> when the passwd lookup fails, and `mkdir -p $HOME/.jlama/models` succeeds. `user.home`
> only degrades to `/` when `HOME` is unset. The real reason to set `modelCachePath` is
> that the default lands on the pod's **ephemeral writable layer** — multi-gigabyte
> weights re-downloaded on every restart, and an air-gapped deployment that cannot start
> at all. `[src]`
>
> **`threadCount` does not oversubscribe on a CPU limit.** Jlama defaults to
> `max(2, availableProcessors() / 2)`, and `availableProcessors()` *is* container-aware
> for a CPU limit. The genuine hazard is narrower: cgroup **shares** have been ignored
> since JDK 19, so a pod with a CPU *request* and no *limit* sees the whole node. `[src]`
>
> A third correction is recorded against §4.6 rather than here: an earlier revision of
> the fix probed whether `jdk.incubator.vector` was resolved, which is strictly weaker
> than what Jlama asks. `MachineSpec` accepts only a 512- or 256-bit species (128 on
> ARM), so a 128-bit x86 species — a hypervisor masking AVX2, `-XX:UseAVX=0` — resolves
> the module and still selects the scalar backend, with the warning never firing. The
> shipped probe reads `MachineSpec.VECTOR_TYPE` directly. `[src]`

> **Correction to an earlier draft of this document.** It claimed `docs/langchain.md`'s
> Jlama example advertised a `timeout` parameter the builder ignores. That was wrong.
> `timeout` is deliberately in `ModelParameterValues.PIPELINE_KEYS`, whose javadoc spells
> out why: jlama and oracle-genai have no provider-level timeout, so `ObservableChatModel`
> applies it as a wall-clock bound around `chat(...)` instead. It is honoured, it is not
> reported as unrecognised, and adding it to `recognisedParameters()` would be the wrong
> fix. `JlamaTests#timeoutIsNotABuilderParameter` now pins that, because both available
> "fixes" are tempting and both are wrong. `[src]`

### 4.2 vLLM and llama-server — the one unambiguous win

Confirmed exactly as claimed. `OpenAILanguageModelBuilder` declares `baseUrl` in
`recognisedParameters()` and applies it in both `build()` and `buildStreaming()`. `[src]`
`docs/langchain.md` already documents the pattern for DeepSeek and Cohere. No code change.

**Work item: a documentation page, nothing more.** Worked configs for vLLM, llama-server
and the TrustyAI gateway, with the caveats that matter: the capability matrix in
[`JsonResponseFormatPolicy`](../src/main/java/ai/labs/eddi/modules/llm/capability/JsonResponseFormatPolicy.java)
keys on provider *type*, so every OpenAI-compatible endpoint silently inherits OpenAI's
row — see §5.3.

### 4.3 TEI — the claimed mechanism does not exist

TEI does expose an OpenAI-compatible `/v1/embeddings`, and it is Apache 2.0 (it was under
the source-available HFOIL from October 2023 until 8 April 2024; that is resolved). `[docs]`
It also exposes `/rerank`. `[docs]` Both facts are useful.

But the RAG side has no `baseUrl` at all. `EmbeddingModelFactory.buildOpenAi` sets exactly
`modelName` and `apiKey`. `[src]` `RagConfiguration`'s javadoc does not list one either,
and `SUPPORTED_EMBEDDING_PROVIDERS` is a closed set validated at the create/update
boundary. `[src]` This is a genuine asymmetry with the chat side and worth fixing on its
own merits — it unlocks TEI, vLLM-served embeddings and every OpenAI-compatible embedder
at once.

**Precondition, not optional.** Do not ship this without I5 (`EmbeddingInputType`).
Ingestion and retrieval share one cached model instance, and the BGE/E5/nomic families TEI
is usually deployed with depend on distinct query-versus-document prefixes. Adding TEI
without input-type handling ships silent recall degradation. I5 also fixes a live defect
for existing Gemini RAG users. `[src]` via I5.

### 4.4 OpenShift AI, and the provider egress question

KServe-served models behind an internal service DNS name are reachable today through
`baseUrl`. Correct, and a deployment-documentation item.

The proposal frames "`baseUrl` is not SSRF-validated" as convenient. It is worth being
precise about what that means. `baseUrl` is agent configuration, and langchain4j sends the
configured `apiKey` as an `Authorization` header to whatever host it names. So an agent
config can direct a provider call — prompt, RAG context and credential — at an arbitrary
internal address. `[inf]` This is not the SSRF class `SafeHttpClient` was built for (that
guards tool fetches of user-supplied URLs), and provider endpoints are admin-authored, not
user-supplied. But it is unbounded.

**The fix is not to reuse Connections.** `ConnectionConfiguration` already has a required,
canonical-origin-validated `baseUrlAllowlist`, and
[`ConnectionParameterGuard`](../src/main/java/ai/labs/eddi/connections/ConnectionParameterGuard.java)
*deliberately* refuses `${connection:…}` in model, embedding and vector-store parameter
maps — with a documented rationale about header-versus-bare-credential form and
unresolved-parameter cache keys. `[src]` Overriding that would undo a considered decision.

**Proposed instead:** an optional **deployment-level** allow-list for provider endpoints —
operator-owned in `application.properties`, empty meaning unrestricted so nothing breaks —
checked in `ChatModelRegistry` and `EmbeddingModelFactory`. Small, and it makes the
multi-tenant story defensible. Filed as a new item, not covered by existing triage.

### 4.5 TrustyAI — right project, wrong integration shape

The guardrails gateway is a sidecar that emulates the chat-completions API, so `baseUrl`
reaches it. `[docs]` But it is *transparent*: EDDI receives a modified completion, not a
pass/block verdict. The proposal's central claim — that it "immediately triggers EDDI's
HMAC-SHA256 audit ledger" — is not achievable through the gateway. `[inf]`

Two further problems. The gateway sits on `/v1/chat/completions`, so it never sees tool
results, HITL pauses or group-conversation context — precisely the surfaces EDDI governs.
And anything it logs lands in a second audit system that an EU AI Act auditor must then
reconcile against EDDI's ledger.

The integration shape that fits is the standalone **detector API**, which is just "an HTTP
classifier endpoint returning a score" — which is what a guardrail type should call
generically. That belongs in R0, not in a separate TrustyAI feature. Keep OpenShift AI as
a deployment document.

### 4.6 Observability — right target, deprecated names, understated effort

Confirmed: [`LifecycleManager`](../src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java)
emits `eddi.pipeline.task` with `eddi.*` attributes only; there is no `gen_ai.*` anywhere
and no `traceparent` / `TextMapPropagator` anywhere in `src/main/java`. `[src]`

Three corrections:

**The proposal's attribute names are deprecated.** `gen_ai.system` was renamed to
`gen_ai.provider.name` in semantic-conventions v1.37.0, and `gen_ai.usage.prompt_tokens`
to `gen_ai.usage.input_tokens`. `[docs]`

**The whole `gen_ai.*` namespace is still Development status.** It moved out of the core
semantic-conventions repository into `semantic-conventions-genai` in v1.42.0 (12 June
2026) specifically so it could iterate below the core stability bar; nothing in it is
marked Stable. `[docs]` Adopt the names, pin the schema version, and expect at least one
more rename.

**There is no LLM span to attach them to.** This is the real effort. See §5.1 — it is
R5 + D11, already P1 in the existing triage, and it must land first.

**Trace propagation is medium, not low.** `langchain4j-mcp` uses its own HTTP transport
(EDDI ships `langchain4j-http-client-jdk`); Quarkus OTel auto-instruments Vert.x and the
REST client, not `java.net.http`. `[inf]` Each of MCP, A2A and `SafeHttpClient` needs its
own injection point. This is the one genuinely new and unclaimed item on the list.

### 4.7 Reranking — EDDI already deferred this, with better reasons

Confirmed that no rerank stage exists: [`RagContextProvider`](../src/main/java/ai/labs/eddi/modules/llm/impl/RagContextProvider.java)
builds one `EmbeddingStoreContentRetriever` with `maxResults` and `minScore` and stops. `[src]`
`dev.langchain4j:langchain4j-onnx-scoring` does exist at `1.20.0-beta30`, aligning exactly
with EDDI's `${langchain4j-beta.version}`. `[docs]`

But this is **R3b, already deferred to P4**, and the existing reasons are sharper than
either the proposal's or my own first pass. My "the ONNX file is 500 MB–1 GB" objection is
the weakest of them: weights need not be in the image at all (§8). The real blockers are a
synchronous per-query inference on the conversation hot path in a subsystem with no
`MeterRegistry` references; redefining `maxResults` as first-stage candidates, which
silently changes every stored agent; and a `rerank.minScore` on a different scale from
cosine `minScore`.

**And the cheaper win is upstream of it.** `RagContextProvider` concatenates per-knowledge-base
results in declaration order with no fusion, while different knowledge bases may use
different embedding models — so the scores being concatenated are not comparable. That is
**R3a (RRF fusion, P1, size S)**, available through `DefaultContentAggregator` already on
the classpath with no new dependency. Reranking a badly fused list is polishing the wrong
stage.

If reranking is revisited, prefer TEI's `/rerank` over in-process ONNX: no weights in the
image, no native `onnxruntime` CVE surface, and the same container operators already
deployed for embeddings.

### 4.8 Infinispan — reject

Two independent problems.

**Wrong artifact.** `quarkus-langchain4j-infinispan` is a Quarkiverse extension.
`grep quarkus-langchain4j pom.xml` returns nothing — EDDI uses plain `dev.langchain4j`
and does not use `@AiService`. `[src]` The correct coordinate is
`dev.langchain4j:langchain4j-infinispan`, which does exist at `1.20.0-beta30`. `[docs]`

**Confused rationale.** The stated use case, "caching multi-agent debate memory states",
describes conversation memory, which lives in MongoDB or PostgreSQL. An embedding store
has nothing to do with it. And it is not in-process: it needs an Infinispan server over
Hot Rod — a third stateful system for a platform whose portability pillar is "MongoDB or
PostgreSQL".

The only real capability would be a clustered replacement for the ephemeral `in-memory`
store, which pgvector, Qdrant and MongoDB Atlas already provide. **No scenario where it
wins.** Reject.

### 4.9 Structured output — `json_schema`, not GBNF

GBNF is llama.cpp-specific; there is nothing portable for EDDI to build against. The
portable lever is `response_format: {type: json_schema}`, accepted by OpenAI, vLLM and
llama-server, and modelled natively by langchain4j — `ResponseFormat.jsonSchema()` and
`OpenAiChatModelBuilder.strictJsonSchema(Boolean)` both verified present by `javap`
against the resolved 1.20.0 jars. `[src]`

This is **I4**, already logged. It is the item I most overrated as cheap in my first pass.
The traps, in rough order of nastiness:

- **`responseSchema` cannot be reused.** It is deliberately prompt-injected, and stored
  values are shape-by-example pseudo-schemas with prose hints. Wiring them into
  `ResponseFormat.jsonSchema()` would break every existing agent. Needs a new field. `[src]`
- **`strictJsonSchema` is an OpenAI *builder* field**, so it must enter the model cache
  key — and it sits awkwardly against the invariant `JsonResponseFormatPolicy` exists to
  protect (per-request, never baked into a cached instance). Whether it is inert when a
  request carries no schema is **unverified** and must be settled before design. `[unv]`
- **Portability is narrower than it looks.** Gemini rejects JSON format alongside tools
  (already a shipped bug fix); langchain4j's Anthropic binding emulates schema via forced
  tool use, with different failure modes; llama-server has open issues on `json_schema`
  through `/v1/chat/completions`, and its grammar conversion does not support every JSON
  Schema feature. `[docs]`
- **The schema constrains generation but is not shown to the model as prompt text**, so
  the existing prompt-side instruction stays necessary rather than being replaced. `[docs]`
- **It lands back in the capability matrix** — see §5.3.

Still the right lever. It is a contained project, not a weekend.

Related and already logged: **D7 — JSON mode is absent in agent and streaming modes**
(P2). And today, when `convertToObject=true` and the model returns non-JSON, `LlmTask`
logs *"convertToObject=true but LLM response is not JSON, storing as string"* and carries
on — there is no repair or retry path. `[src]` That silent degradation is the concrete
failure I4 would close.

---

## 5. What the proposal missed

### 5.1 There is no LLM-level observability hook

[`ObservableChatModel.wrapIfNeeded`](../src/main/java/ai/labs/eddi/modules/llm/impl/ObservableChatModel.java)
returns the **raw model** when no `timeout`, `logRequests` or `logResponses` is
configured — i.e. in the default case there is no decorator at all, and therefore nowhere
to hang a span, a listener or a metric. `[src]`

Two further details matter for scoping this, and they cut in opposite directions. The
streaming side is **further along than a first read suggests**:
[`ObservableStreamingChatModel`](../src/main/java/ai/labs/eddi/modules/llm/impl/ObservableStreamingChatModel.java)
exists, is wired into `ChatModelRegistry.getOrCreateStreaming`, and already overrides
`listeners()`. `[src]` The sync side is **further behind**: `ObservableChatModel`
overrides `chat(ChatRequest)` rather than `doChat(...)`, and `chat` is precisely the
default method that fires `onRequest`/`onResponse`/`onError` around `doChat` — so the
sync decorator does not merely fail to add listeners, it *suppresses* the dispatch that
`ChatModel` would otherwise perform, and it does not override `listeners()` either.
`[src]` So the work is narrower than "build an observability layer": move the sync
decorator onto `doChat`, override `listeners()`, always wrap, and give both decorators a
shared listener. Anyone picking this up should re-read both classes first — a July-vintage
description of them is already out of date.

Metrics coverage is partial in a specific way. `eddi.llm.cascade.step.latency`,
`eddi.llm.cascade.tokens` and `eddi.llm.cascade.cost` all exist — but they live in
[`CascadingModelExecutor`](../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
which `LlmTask` enters only under `if (cascadeActive)`. `[src]` **The default
single-model path emits no latency, token or cost metric at all.** (A reviewer asserted
EDDI has no LLM metrics whatsoever; that is wrong, and the distinction matters — the
plumbing exists and is reachable, it is just on one branch.)

OTel is also off by default (`quarkus.otel.sdk.disabled=true`), which is a deliberate,
documented opt-in with `docker-compose.monitoring.yml` flipping it. `[src]` That is a
defensible default, not a defect — but it means an operator who turns tracing on today
gets pipeline-task spans and no LLM span.

This is **R5 + D11, already P1**, and it is the prerequisite for every observability item
in the proposal. Do it first.

### 5.2 Prompt-cache economics

Zero references to `cache_control`, cached-token counts or prefix-stability ordering in
the LLM module. `[src]` System-prompt assembly (snippets, RAG context, history) should put
stable prefixes first, and `TokenUsage` should carry cached-token counts so cost tracking
and cascade accounting stop over-reporting. Mostly an ordering concern; partially covered
by R2 (Anthropic `cacheTools`, P1).

### 5.3 The capability matrix is wrong by design, not by neglect

`JsonResponseFormatPolicy`'s javadoc states the matrix is derived from **langchain4j
1.18.0** bindings; `pom.xml` is on **1.20.0**. `[src]` Worse, it keys on provider *type*,
so every OpenAI-compatible endpoint — vLLM, llama-server, DeepSeek, a TrustyAI gateway —
inherits OpenAI's row wholesale. That is exactly backwards for the one item the proposal
gets right (§4.2).

**Suggested shape:** promote `openai-compatible` to its own provider type with
per-endpoint capability flags, rather than treating every such endpoint as "OpenAI with a
different URL". That kills the matrix-by-type defect and makes vLLM, llama-server and TEI
first-class. It is also a precondition for doing §4.9 honestly.

### 5.4 Agent behaviour has no regression harness

EDDI has 14,000+ tests and none of them assert agent *behaviour*. A config-driven engine
with a deterministic rule layer is unusually well suited to golden-transcript replay, and
the `/v1` OpenAI-compatible adapter already exposes deployed agents to standard tooling.
This is EU AI Act Art. 9/15 testing evidence — a governance differentiator, not plumbing.
Out of scope here; noted so it is not lost.

---

## 6. Which guardrails design wins

Two planning documents disagree, and this needs settling before anyone builds a detector
integration.

[`guardrails-architecture.md`](guardrails-architecture.md) rejects langchain4j's guardrail
interfaces because "EDDI doesn't use `@AiService`" and proposes a wholly native engine.
[`langchain4j-recommendations.md`](langchain4j-recommendations.md) **§R0 reverses this**,
with source evidence: `InputGuardrail`, `OutputGuardrail`, the request/result types and
`ChatExecutor` all live in `langchain4j-core`, are non-beta, already resolved, and are
constructible programmatically — the `@AiService` wiring is optional, and the caller
supplies its own `ChatExecutor`. `[src]`

R0 also finds a real defect in the older plan: its output guardrail is string-only
(`evaluate(String, Map)`), while lc4j's `OutputGuardrailRequest` preserves
`toolExecutionRequests`. EDDI's agent mode *is* a tool loop, so a string-only output
guardrail is structurally blind to the tool calls most in need of governing. `[src]`

**R0 is the later, better-evidenced document and should win.** Adopt the lc4j types
internally, build EDDI's own ~40-line executor loop (lc4j's throws on terminal failure and
its reprompts bypass budget gates and HITL journaling), and keep the persisted
vocabulary EDDI-owned and neutral — `block | redact | reprompt | warn`, matching the
vocabulary [`ToolResultGuardrailConfig`](../src/main/java/ai/labs/eddi/modules/llm/guardrails/ToolResultGuardrailConfig.java)
already uses for tool results. `[src]`

**Granite Guardian's correct shape follows from this.** Not a lifecycle task, not a
TrustyAI-specific feature: **one `classifier` guardrail type** that calls an
OpenAI-compatible or detector-style HTTP endpoint and maps a score to the neutral
vocabulary. Granite Guardian, a TrustyAI detector, Llama Guard and ShieldGemma all become
*config values*. EDDI ships and endorses no model. That matters for licensing (§8) and it
is the only version of this that fits Golden Rule 1.

Granite Guardian's own fit is good on the merits: Apache 2.0, and a constrained yes/no
scoring mode with a confidence token — genuinely low-latency and deterministic. `[docs]`
Note the family has moved on from the proposal's reference point: 3.3-8b and 4.1-8b now
exist, alongside tiny HAP classifiers at 38 M and 125 M parameters that are far better
suited to a latency-sensitive input gate than an 8 B model. `[docs]` The proposal's
"no-think binary mode" phrasing does not appear on the 3.2 card, and its Sigstore-signed-checkpoints
claim could not be verified. `[unv]`

---

## 7. Recommended sequence

Nothing below is approved; this is the order that makes sense if any of it is taken up.

**Close the honesty gaps first — days, not weeks.**

| Item | Work | Depends on |
|---|---|---|
| **A1** | Jlama: demote (preferred) or fix the image flags and add a real smoke test — §4.1 | — |
| **A2** | Documentation page: vLLM, llama-server, OpenShift AI/KServe, TrustyAI gateway — §4.2, §4.4 | — |
| **A3** | Docs debt that A1 creates: the "12 providers" count and the Jlama section — §10 | A1 |

**Then the prerequisites the proposal skipped.**

| Item | Work | Existing entry |
|---|---|---|
| **B1** | Complete `ObservableChatModel`; always wrap; wrap streaming; adopt `ChatModelListener` — §5.1 | **R5 + D11, P1** |
| **B2** | LLM span with `gen_ai.provider.name` / `gen_ai.usage.input_tokens`; pin the schema version — §4.6 | new, after B1 |
| **B3** | `traceparent` injection into the MCP client, A2A and `SafeHttpClient` — §4.6 | new |

**Then the two inference items worth having.**

| Item | Work | Existing entry |
|---|---|---|
| **C1** | `EmbeddingInputType` QUERY/DOCUMENT — fixes a live Gemini defect | **I5** |
| **C2** | `baseUrl` (and `dimensions`) on the OpenAI embedding branch → TEI — §4.3 | new, **after C1** |
| **C3** | Deployment-level provider endpoint allow-list — §4.4 | new |

**Then the structural work.**

| Item | Work | Existing entry |
|---|---|---|
| **D1** | RRF cross-KB fusion — the real RAG win, no new dependency | **R3a, P1** |
| **D2** | Guardrails on the lc4j core SPI with EDDI's own executor loop — §6 | **R0, P1** |
| **D3** | A model-agnostic `classifier` guardrail type — §6 | after D2 |
| **D4** | `openai-compatible` as its own provider type with per-endpoint flags — §5.3 | new |
| **D5** | `jsonSchema` structured output as a **new** field — §4.9 | **I4** |

---

## 8. Rejected, and why

| Item | Reason |
|---|---|
| **Infinispan vector store** | Wrong artifact for EDDI's stack; a third stateful system; confused rationale; no advantage over pgvector or Qdrant. §4.8 |
| **In-process ONNX reranking** | Already R3b/P4-deferred on better grounds. Hot-path inference with no metrics; `maxResults` redefinition silently changes stored agents; RRF fusion (free) is the bigger win. Revisit as a *network* scoring model. §4.7 |
| **GBNF / Outlines / XGrammar** | Backend-specific; nothing portable to build. Superseded by I4. §4.9 |
| **TrustyAI guardrails gateway** | A transparent proxy cannot produce auditable verdicts and is blind to the tool loop. Use the detector API through a generic classifier guardrail. §4.5 |
| **Shipping any model weights in the image** | Every weight update forces a rebuild and digest re-pin; native `onnxruntime` adds glibc-sensitive CVE surface against UBI 10; large layers slow certification scanning and Trivy in CI; and redistribution attaches the weights' licence to EDDI. **Rule: never bake weights.** Operator-supplied by volume or init container, path in config. |
| **Jlama as a strategic bet** | Unmaintained since 2025-01-01, single maintainer, beta binding, incubator-API exposure, runtime weight download, and it is not the air-gapped path it is sold as. §4.1 |

---

## 9. Traps

**Vector API and GraalVM.** `jdk.incubator.vector` remains an incubator module and will
churn until Valhalla. [`native-image-migration.md`](native-image-migration.md) asserts
"jlama IS native-compatible — Uses Panama Vector API which GraalVM 25 supports"; that
claim is **unverified** `[unv]`, and native-image Vector API support is experimental. That
same document also references `quarkus.langchain4j.jlama.*` properties and the
`io.quarkiverse.langchain4j` group — neither of which EDDI uses. It carries the same
quarkus-langchain4j confusion as the proposal and should be corrected.

**`--enable-native-access`.** The image sets no such flag. JEP 472 makes unrestricted
native access a warning today and an error in a future release, so any FFM or JNA library
in the image is on a clock — independent of anything in this document. `[docs]`

**Capability-matrix drift.** Hand-maintained, keyed by provider type, documented against
1.18.0 while the pom is on 1.20.0. Each langchain4j bump can silently change what a
schemaless `ResponseFormat.JSON` does. Mitigation: per-endpoint flags (§5.3) plus a
contract test per provider mapper that fails loudly on upgrade.

**Guardrail-model licensing.** Granite Guardian is Apache 2.0. `[docs]` Llama Guard and
ShieldGemma are not OSI-licensed and carry use restrictions; a reviewer raised a possible
EU-domicile exclusion in the Llama 4 line, from which Llama Guard 4 derives — **unverified,
and labs.ai is EU-domiciled, so verify before naming it anywhere in documentation.** `[unv]`
This is precisely why the guardrail type must be model-agnostic: EDDI should not be the
party redistributing or endorsing any of them.

---

## 10. Docs debt this creates

- ~~`docs/langchain.md` names Jlama ten times and counts it toward "12 providers"~~ —
  moot: Jlama is being fixed rather than demoted, so the provider count stays true. The
  Jlama section was rewritten instead, to document the JVM flag and the three container
  defaults that fail silently.
- ~~`docs/langchain.md`'s Jlama example advertises an unrecognised `timeout` parameter.~~
  Withdrawn — the claim was wrong; see the correction in §4.1.
- `planning/native-image-migration.md` references Quarkiverse artifacts and config
  properties EDDI does not use, and makes an unverified Jlama native-compatibility claim.
- `planning/guardrails-architecture.md` is superseded by R0 on the SPI question and should
  carry a banner saying so rather than being silently left to contradict it.

---

## 11. Open questions

1. ~~**Jlama: fix or demote?**~~ **Answered: fix.** See the decision note in §4.1. The
   residual risk — a single-maintainer upstream last released 2025-01-01, pinned through
   a beta binding — is accepted, not solved, and should be revisited if `jlama-core`
   stops resolving against a future JDK.
2. **Is `strictJsonSchema` inert when a request carries no schema?** `[unv]` If not, it
   cannot be a builder field without reintroducing the class of bug
   `JsonResponseFormatPolicy` exists to prevent. Settle before designing I4/D5.
3. **Should `guardrails-architecture.md` be rewritten or retired** in favour of R0? §6.
4. **Does anyone actually want an offline embedding path?** I1 is measured at ~35 MB
   without weights and `docs/rag.md` gestures at it, but no user has asked. It is the real
   air-gap story if the answer is yes, and dead weight if no.
5. **Provider endpoint allow-list: default open or default closed?** §4.4. Open breaks
   nothing; closed is the stronger multi-tenant posture and a breaking change.
