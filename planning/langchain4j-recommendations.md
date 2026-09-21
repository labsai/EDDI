# langchain4j × EDDI — Analysis and Recommendations

**Single source.** Everything from this investigation: what to adopt from langchain4j 1.18.0, what to build or fix natively instead, what is rejected and why, the EDDI defects surfaced along the way, and the order to do it in.

langchain4j 1.18.0 / 1.18.0-beta28 · EDDI branch `chore/langchain4j-1.18.0` · 2026-07-20

Confidence tags used throughout: `[src]` verified from EDDI source or a resolved jar/`javap` · `[docs]` from upstream docs or PR metadata · `[inf]` inferred from verified facts · `[unv]` unverified.

---

## Table of contents

1. [Bottom line](#1-bottom-line)
2. [Methodology and confidence](#2-methodology-and-confidence)
3. [The decision rule](#3-the-decision-rule)
4. [Master decision table](#4-master-decision-table) · [4.1 By work type — fix vs build](#41-by-work-type--fix-vs-build)
5. [The 1.17.0 → 1.18.0 upgrade](#5-the-1170--1180-upgrade-done)
6. [Adopt from langchain4j](#6-adopt-from-langchain4j)
7. [Build or fix natively](#7-build-or-fix-natively)
8. [Where EDDI is ahead](#8-where-eddi-is-ahead)
9. [Rejected](#9-rejected)
10. [Worth investigating](#10-worth-investigating)
11. [Open questions](#11-open-questions--resolve-before-p1)
12. [Upstream contributions](#12-upstream-contributions)
13. [Sequencing](#13-sequencing)
14. [Revisit triggers](#14-revisit-triggers)

**Part II — Implementation specs** (P0–P2, execution-ready):
[D5](#d5--toolcacheservice-cache-is-global-not-tenant-scoped) ·
[D3](#d3--tooltrace-never-reaches-the-sse-stream) ·
[D2](#d2--maxbudgetperconversation-bounds-nothing-the-same-key-mismatch-voids-toolratelimits) ·
[D1](#d1--audit-ledger-records-zero-tokens-cost-and-toolcalls) ·
[D11+R5](#d11r5--complete-observablechatmodel-then-chatmodellistener-observability) ·
[R4](#r4--retry-classification-via-httpexceptionstatuscode) ·
[R0](#r0--guardrail-spi-from-langchain4j-core--native-executor-loop) ·
[R3a](#r3a--rrf-fusion-via-defaultcontentaggregator) ·
[R2](#r2--anthropic-cachetools--thinking-params) ·
[R1](#r1--binary-document-ingestion-for-rag)


---

## 1. Bottom line

**Stay at the low level. Adopt contracts and value objects. Do not hand over a loop that EDDI's governance lives inside.**

EDDI drives langchain4j through `ChatModel` / `ChatRequest` / `ToolSpecification` and runs its own orchestration. langchain4j 1.18.0 now ships high-level orchestration — agentic module, guardrails, advanced RAG — into exactly that space. The verdict is to take **six small things** (interfaces, value objects, an annotation, one pure function) and reject every abstraction that wants to own a control loop.

Two qualifications that matter more than the headline:

**The upgrade itself is done and was the highest-confidence item.** No breaking changes, and it carries a live MCP crash-class fix.

**This investigation found more broken or inert EDDI behavior than worthwhile langchain4j adoptions.** An audit ledger writing zeros into three documented fields; a budget ceiling whose price table matches zero real tool names; a live tool-call display that has never received a byte; a tool cache with no tenant scoping; streaming tasks silently running without the timeout their config specifies. Config that silently does nothing is worse than config that does not exist, because it misleads the agent designer whose autonomy the whole architecture exists to protect. **Fix those first** — they are §7 P0, and none of them is langchain4j work.

---

## 2. Methodology and confidence

**Pass 1 was primed toward rejection.** Its grounding prompt told every verifier agent, verbatim, that compile-time-vs-runtime tension is "the single most common reason a langchain4j high-level abstraction is a POOR fit for EDDI", instructed them to "be harsh", and supplied EDDI's native capabilities framed as rejection grounds. The output — every high-level abstraction rejected, four value objects accepted — is exactly what that priming would manufacture.

**Pass 2 applied the opposite prior**, steelmanning every rejection under an adopt-bias and requiring source/jar evidence rather than docs; re-verified every defect claim independently, then ran refutation rounds; and audited the process itself.

**What pass 2 changed:** 9 of 10 rejections were flagged as reached on primed reasoning (only lc4j HITL was confirmed unprimed). 6 of 13 defect claims were wrong or overstated. Two rejections became adoptions (guardrail SPI, `ChatModelListener`). The **direction** survived — but because langchain4j's high-level surfaces are entangled with `AiServices` and `ChatMemory`, not because of the compile-time argument pass 1 leaned on.

**The most damaging error class was reading docs instead of bytecode.** `docs.langchain4j.dev` still states `ChatModel` "does not expose a `listeners()` method" (it is a default method) and reads as though the agentic module is annotation-only (it ships ten programmatic builders). AGENTS.md §2 rule 7 exists for exactly this.

**What remains unverified:**

| Item | Status |
|---|---|
| `langchain4j-guardrails` artifact contents | The **core** SPI is verified in `langchain4j-core` 1.17.0. The separate artifact is not in the local `.m2` and its two impls were never inspected. Rejected on Golden Rule 1, not inspection. `[unv]` |
| Anthropic prompt-cache savings | The ~90% figure is Anthropic-documented, never EDDI-measured. Given the tail-volatility finding (§6 R2) it is likely *negative* for RAG agents. |
| ONNX pruned image delta | ~51 MB computed from measured jar contents, not an actual `docker build` diff. Gated on measuring it. |
| `mvnw clean verify` on 1.18.0 | `clean compile` verified exit 0; full verify needs Docker and is CI-only per AGENTS.md. |
| 1.18.0 `ToolSpecificationHelper` | PR #5726 verified merged and landing in that file; post-fix source not read offline. |

**Process note for any future pass:** run it *without* a supplied rejection rubric; require an explicit verification method per claim; and enforce that steelman and verification verdicts propagate into the final write-up. Pass 1's own machinery flagged 9 of 10 rejections as primed and reported neither. That gap, not the priming itself, was the actual failure.

---

## 3. The decision rule

One rule decides "use theirs" vs "build ours". It replaces pass 1's *"adopt data, reject control loops"*, which was close to a tautology — "owns a control loop" was defined post hoc by whatever got rejected, and it failed to predict two of the adoptions below.

> **Reject anything whose *state substrate* is `ChatMemory`, or whose *construction path* is `AiServices`.**
> **Everything else is evaluable on merit.**

**Why it holds.** Pillars 2 (deterministic governance), 6 (observability) and 8 (persistent memory) are implemented **in EDDI's loop bodies** — the write-ahead journal, the audit keys, the budget gates, the token accounting. Handing loop control to a foreign executor means enforcing those pillars *around* a black box, and the failure modes are precisely the ones this analysis kept finding: reprompts that bypass budget and timeout, compensations that cannot journal, retrieval that fails whole-turn instead of per-KB.

**Check it against the evidence.** `OutputGuardrailExecutor` reads `requestParams().chatMemory()`; `DefaultContentInjector` rewrites the message list; `AgenticServices` constructs every agent through `AiServices`; `Skills` recovers state from `ToolExecutionResultMessage.attributes()` in a history EDDI does not keep — all rejected. `InputGuardrail`, `ChatModelListener`, `Skill`, `@CompensateFor`, `HttpException`, `ContentAggregator`, `AnthropicChatRequestParameters`, `EmbeddingModel` touch neither — all adopt or investigate. The rule is grep-able against a future release, which is what a standing rule needs to be.

**Two things it does not license:** it is not a licence to skip reading the jar (§2), and it says nothing about the layer *above* — `quarkus-langchain4j` is a Quarkus integration, not a langchain4j abstraction, and the rule cannot adjudicate it (§11).

---

## 4. Master decision table

| # | Item | Decision | Effort | Priority |
|---|---|---|---|---|
| **A0** | langchain4j 1.17.0 → 1.18.0 | ✅ **DONE** | S | — |
| **D1** | Audit ledger records zero tokens/cost/toolCalls | 🔧 Fix ours | M | **P0** |
| **D2** | `maxBudgetPerConversation` bounds nothing; voids `toolRateLimits` | 🔧 Fix ours | S | **P0** |
| **D3** | `toolTrace` never reaches SSE — live tool display never worked | 🔧 Fix ours | XS | **P0** |
| **D5** | `ToolCacheService` cache is global — no user/conversation scoping | 🔧 Fix ours | S | **P0** |
| **D5b** | `CacheImpl.put` discards the TTL ⇒ tool cache entries **never expire**; smart-TTL table inert | 🔧 Fix ours | M | **P0** |
| **R0** | Guardrail SPI (`InputGuardrail`/`OutputGuardrail` + results) | ⬇️ Use theirs (types only) | S | **P1** |
| **R4** | Retry classification — add lc4j typed tiers to the shared classifier #593 built (~70% done) | ⬇️ Use theirs (+ ours) | XS | **P1** |
| **R5** | `ChatModelListener` as observability SPI | ⬇️ Use theirs | S–M | **P1** |
| **D11** | `ObservableChatModel` incomplete; streaming never wrapped | 🔧 Fix ours (R5 prerequisite) | S | **P1** |
| **R3a** | RRF fusion via `DefaultContentAggregator` | ⬇️ Use theirs | S | **P1** |
| **R2** | Anthropic `cacheTools` + `thinkingBudgetTokens` | ⬇️ Use theirs | S | **P1** |
| **R6a** | `@CompensateFor` annotation | ⬇️ Use theirs (marker only) | XS | **P2** |
| **R6b** | Tool compensation mechanism | 🔧 Build ours | M | **P2** |
| **D4** | Turn-scoped tool idempotency journal | 🔧 Fix ours | M | **P2** |
| **R1** | Binary document ingestion for RAG | 🔧 Mostly ours + narrow lib | M | **P2** |
| **D7** | JSON mode absent in agent + streaming modes | 🔧 Fix ours | M | **P2** |
| **D6** | Cross-turn tool history lossy; in-turn context unbounded | 🔧 Fix ours (1 lc4j invariant) | M | **P2** |
| **D14** | RAG knowledge-base fan-out is serial | 🔧 Fix ours | XS | **P2** |
| **D8** | `injectionStrategy`/`contextTemplate` dead config | 🔧 Fix ours | S | **P3** |
| **D10** | `enableParallelExecution` dead config | 🗑️ Delete ours | XS | **P3** |
| **D9** | `TenantQuotaService.recordCost` has no callers (the 2 store sub-bugs were fixed on main) | 🔧 Fix ours | XS | **P3** |
| **D13** | Embedding spend unmetered | 🔧 Fix ours | XS | **P3** |
| **D15** | Docs describe config that does not exist | 📝 Fix docs | XS | **P3** |
| — | `EddiChatMemoryStore` (zero consumers) | 🗑️ Delete ours | XS | **P3** |
| **R3b** | `ReRankingContentAggregator` | ⏸️ Defer | M | P4 |
| **I1–I6** | ONNX embeddings · `Skill` SPI · agent-mode streaming · `JsonSchema` · `EmbeddingInputType` · MCP `outputSchema` | 🔍 Investigate | S–L | P4 |
| **U1–U3** | Upstream contributions | 📤 Contribute | S each | P4 |
| — | agentic module · lc4j HITL · `ChatMemory` · `DefaultRetrievalAugmentor` · `DefaultContentInjector` · Tika · micrometer modules · `SqlDatabaseContentRetriever` | ❌ Rejected (§9) | — | — |

⬇️ use langchain4j · 🔧 improve/fix EDDI's own · 🗑️ delete · 🔍 investigate · 📤 upstream · ❌ rejected

### 4.1 By work type — fix vs build

The table above sequences by priority, which deliberately interleaves two very different kinds of
work. This view splits them. Note the `D`/`R` prefixes are historical and **not** a reliable type
signal — `D13` and `D14` are not defects, and `R4` is half bug-fix.

**Headline: of ~25 items, 13 are bugs or dead config in already-shipped EDDI code, and only ~6 are
actual langchain4j adoptions.** This is a remediation backlog with a small adoption backlog attached.

#### 🐞 Bugs — shipped behavior that does not work

| # | Defect | Class |
|---|---|---|
| **D5** | Tool cache has no user/conversation scoping — one user's result is served to another | **Data isolation** |
| **D5b** | `CacheImpl.put` discards the TTL; `CachedResult` has no `expiresAt` ⇒ **entries never expire**; the whole smart-TTL table is inert | **Data isolation** |
| **D2** | `maxBudgetPerConversation` bounds nothing (price table keyed on config slugs, live path passes `@Tool` method names); same mismatch voids `toolRateLimits` | **Cost control** |
| **D1** | Audit ledger writes zeros into three documented fields (tokens, cost, `toolCalls`). #593 fixed streaming-metadata capture but **added a 4th `AuditEntry` site** with the same defect. PR #604 then landed `fix(llm): stop discarding agent-mode token usage` — **the token data source now exists** (`AgentOrchestrator:493,849`), but `audit:token_usage` still has **zero writers**, so the ledger is unchanged. **This item is now much cheaper: wire an existing signal, don't build one.** | **Compliance** |
| **D4** | Cascade escalation re-invokes on low confidence with no error ⇒ side-effecting tools re-execute; `future.cancel(true)` can cause *concurrent* duplicates | Correctness |
| **D3** | `toolTrace` writer/reader key prefixes cannot match ⇒ live tool display has never received a byte | User-visible |
| **D7** | `jsonMode` never threaded into agent or streaming modes ⇒ tool-enabled mistral/azure agents silently get no API-level JSON | User-visible |
| **D11** | `ObservableChatModel` incomplete; streaming never wrapped ⇒ `timeout`/`logRequests`/`logResponses` silently discarded and hung streams have **no timeout** | Correctness |
| **I5** | Ingestion and retrieval share one cached embedding model ⇒ **every Gemini RAG user embeds queries as documents** | Correctness |
| **D9** | ~~Two Mongo upsert keys per tenant; `>=` vs `>` divergence~~ — **both sub-bugs FIXED on main** by `7641f60f3 fix(tenancy): align at-limit cost comparison and fix costMonth JSON shape`. The **core defect stands**: `recordCost` still has **zero production callers**, so the ceiling can never fire | Low (stub subsystem) |

#### 🔇 Dead config — same root cause, same fix pattern

Config an agent designer can set that does nothing. Separated because the remedy is uniform (wire it
or delete it) and because it actively misleads the designer whose autonomy the architecture exists to
protect.

| # | Surface |
|---|---|
| **D8** | `injectionStrategy` / `contextTemplate` — zero call sites, and **rendered as a dropdown in the shipped Manager bundle** |
| **D10** | `enableParallelExecution` — a **live Manager checkbox** promising concurrent tool calls; the machinery is vestigial and type-incompatible with the dispatch path. **Delete, do not wire** |
| **D15** | `docs/mcp-server.md` documents an `mcpServers` field that does not exist; `eddi.audit.retentionDays` is read by no Java code |

#### ✨ Features — genuinely new capability

| # | Capability | Source |
|---|---|---|
| **R0** | Input/output guardrails — **greenfield, EDDI has zero** | lc4j SPI + native loop |
| **R5** | LLM observability — **EDDI meters zero tokens today** | lc4j listener + native bodies |
| **R6b** | Tool compensation — reserve→charge→confirm is not safely implementable today | Native |
| **R1** | Binary document ingestion — RAG is **text-only** today | Native + narrow lib |
| **R2** | Anthropic prompt caching + thinking budget | lc4j params |
| **I1–I4, I6** | Offline embeddings · `Skill` SPI · agent-mode streaming · `JsonSchema` output · MCP `outputSchema` | Mixed |

#### 📈 Improvements — works today, could work better

| # | Improvement |
|---|---|
| **R3a** | RRF fusion — cross-KB ranking where today it is declaration-order concatenation |
| **R3b** | Re-ranking (deferred to P4) |
| **D14** | RAG knowledge-base fan-out is serial ⇒ parallelize. **Not a defect** |
| **D6** | Tool calls invisible across turns — *and* in-turn context is unbounded. **The unbounded half IS a bug** (can hard-fail mid-loop); the history half is an improvement |
| **D13** | Embedding spend unmetered. **Gap, not a defect** |

#### 🧹 Cleanup · 📤 Upstream · ⛔ Do not action

- **Cleanup:** delete `EddiChatMemoryStore` (zero consumers) · **R4** dedupe two drifted retry classifiers — *part bug*, since post-#587 misclassification corrupts two Micrometer metrics
- **Upstream:** **U1** model fallback (#874) · **U2** HITL timeout policies · **U3** public `AgentInstance` registration API
- **⛔ D12** (`SecretRedactionFilter` scope) — **downgraded to a non-issue; the original claim was false.** There are four production sites including unconditional audit scrubbing. Do not action.

#### Triage recommendation

**Triage D5 and D5b together; ship them separately.** They compound — an unscoped entry that also
never expires is strictly worse than either alone — and neither appeared as a *security* item in the
priority table, only as a cache bug. But they are **not one change**, and the Part II D5 spec puts
D5b explicitly out of scope:

- **D5 (scoping)** is contained: a scope tag in `buildKey` plus fail-closed resolution. Ship first.
- **D5b (TTL)** is larger than it looks. `ICacheFactory.getCache(name, Duration)` exists
  (`CacheFactory.java:35-48`) and *does* honour `expireAfterWrite`, but it is per-TTL-**value**,
  whereas `ToolCacheService` wants per-**tool** TTLs in one cache. A real fix needs a Caffeine
  `Expiry` implementation or a per-TTL-bucket cache set. File as its own item.

Until D5b lands, do not let any test, doc sentence or commit message imply that cached tool results
expire on the per-tool TTL. They expire on size eviction only.

---

## 5. The 1.17.0 → 1.18.0 upgrade ✅ done

Four properties (`pom.xml` 20/21/22/30) → `1.18.0` / `1.18.0-beta28`. **Line 22 `langchain4j-libs.version` is the one that gets missed** — it governs eight providers (open-ai, anthropic, ollama, google-ai-gemini, http-client-jdk, mistral-ai, azure-open-ai, bedrock), so bumping only three leaves those pinned at 1.17.0 against a 1.18.0 core: a split classpath that still compiles. `[src]`

No declared breaking changes (last `Breaking Changes` heading was 1.16.0, already absorbed). Also picks up skipped 1.17.1/1.17.2. All **25** declared artifacts resolve. `[src]`

**What EDDI gains:**

| Fix | Lands where | Relevance |
|---|---|---|
| MCP `anyOf` + `type:"object"` `ClassCastException` (#5726) | `ToolSpecificationHelper` | **The payoff.** See below. |
| Tool-arg precision long/BigInteger/BigDecimal (#5755), locale-independent enum coercion (#5778) | `DefaultToolExecutor` | `@Tool` built-ins only — http/MCP/A2A use custom executors with string schemas, so there is no typing to preserve |
| Null-function guard in streamed OpenAI tool-call deltas (#5712) | open-ai | `StreamingLegacyChatExecutor` is live |
| cohere/HuggingFace off Retrofit/OkHttp (#5780) | cohere, hugging-face | Transitive tree change |
| `ChatMemory.set` defensive copy (#5714) | core | **Moot** — `EddiChatMemoryStore` has zero consumers |

**On #5726 — the failure mode is worse than a crash.** `McpToolProviderManager` feeds arbitrary third-party inputSchemas through. `McpToolProvider.provideTools` catches the failure internally (`failIfOneServerFails` defaults `false`, EDDI never sets it), returns empty, and EDDI takes the **success** branch — logging INFO `"Discovered 0 tools from MCP server 'X'"`. The REST discovery probe then returns HTTP 200 `{"tools":[],"count":0}`, indistinguishable from a server that genuinely exposes no tools; its 502 path is dead. `[src]` The bump removes the *cause*; the silent-success reporting is an EDDI-side gap the bump cannot fix.

**Verification performed:** `mvnw clean compile` — 740 sources, BUILD SUCCESS. `mvnw test` — 11075 tests; the 21 failing classes are all Docker/Testcontainers (Mongo*, Postgres*) or loopback-socket HTTP (`SafeHttpClientTest`, `SlackWebApiClientTest`, `WeatherToolTest`, `WebScraperToolTest`), with zero langchain4j in any stack trace. Baseline-compared: a 5-class subset on 1.17.0 gives 73 tests / 73 errors; the same 5 on 1.18.0 give the same 73. Identical counts and causes ⇒ pre-existing environmental, not regressions. ITs remain CI-only per AGENTS.md.

**Still to check in CI:** Jackson (→ 2.22.1) mediation against the Quarkus BOM — EDDI declares langchain4j with explicit versions rather than importing its BOM, so Maven mediation decides and a skew surfaces as a runtime `NoSuchMethodError` from a clean compile. Re-validate the CVE pins at `pom.xml:53-65` (reactor-netty-http, postgresql) against the new tree; they were pinned against 1.17.0's. PR #5735 rewrites `EmbeddingModel` across bedrock/cohere — **not applicable**, EDDI never implements it, only consumes it. `[src]`

---

## 6. Adopt from langchain4j

Every item is an interface, value object, annotation or pure function. None owns a loop; none touches `ChatMemory` or `AiServices`.

### R0 — Guardrail SPI · `langchain4j-core` · non-beta · already resolved · **S**

**Take:** `InputGuardrail`, `OutputGuardrail`, `InputGuardrailResult`, `OutputGuardrailResult`, `InputGuardrailRequest`, `OutputGuardrailRequest`, `GuardrailRequestParams`, `ChatExecutor`. Plus `JsonExtractorOutputGuardrail` free, which serves the known `responseSchema` gap. `[src]`

**Why theirs.** This is greenfield — all 35 `guardrail` hits in `src/main/java` are `UserMemoryConfig.Guardrails` (memory-write key-length caps) or dynamic-agent creation limits. `[src]` Pass 1 filed this under "rejected — duplicates existing EDDI capability", which was the primed move; **EDDI has no guardrails.** And their vocabulary is measurably better than the one `planning/guardrails-architecture.md` invents:

| Our planned vocabulary | lc4j core |
|---|---|
| `Verdict{PASS, BLOCK, REDACT, REPROMPT}` — **no failure/fatal axis** | `success`/`successWith`/`failure`/`fatal`/`retry`/`reprompt`/`failureWithMessageRemoval`, `blockRetry()`, accumulating `List<Failure>` |
| `evaluate(String content, Map<String,String>)` — **string-only** | `OutputGuardrailRequest.withText()` explicitly preserves `toolExecutionRequests` |

The second row is a real defect in our own plan: agent mode **is** a tool loop, so a string-only output guardrail is structurally blind to the tool calls we most need to govern. `[src]`

**Build ours: the executor loop (~40 lines).** Reject `OutputGuardrailExecutor` — *not* because it is sealed (pass 1's reason was wrong: `OutputGuardrailExecutor` is explicitly `public non-sealed` with a `protected` constructor and non-final `execute`, so it **is** subclassable `[src]`), but because:
- it **throws** on terminal failure, and the thrown `OutputGuardrailException` embeds guardrail messages that `AgentExecutionHelper.isRetryableError` substring-matches — so a PII guardrail quoting user text containing `"connection"` can trigger a full tool-loop replay;
- each reprompt is an LLM call bypassing budget gates, token accounting and HITL journaling;
- its `chatMemory` mutation surface is inert for EDDI.

**Supply our own `ChatExecutor` (~20 lines)** merging the reprompt into EDDI's `currentMessages`. `ChatExecutor` is a public two-method interface the *caller* supplies, so pass 1's "reprompts with no system prompt, no history, no RAG context" objection is a property of an `@Internal` implementation, not of the abstraction. `[src]` **Correction: this is mandatory, not optional** — `OutputGuardrailRequest`'s private constructor calls `ensureNotNull(builder.chatExecutor, "chatExecutor")`, so an EDDI `ChatExecutor` is required to construct the request at all. Also note `GuardrailResult<GR>` is a **sealed interface** permitting only the two result types, and its nested `Failure` is sealed too — extend neither. `[src]`

**Config contract:** stored JSON stays EDDI-owned and neutral — `block` | `redact` | `reprompt` | `warn`. Adopt the Java types internally; never name persisted values after lc4j classes.

---

### R4 — Retry classification · `dev.langchain4j.exception` · **S**

**Current state (post-#593).** EDDI now has **one shared classifier**,
`configs/shared/RetryConfiguration.isRetryableError`, with a typed-JDK tier, a `WebApplicationException`
status branch, and a substring tier — plus a **still-divergent cascade copy**
(`CascadingModelExecutor.isRetryableError`, one call site that governs only a metric/SSE *label*, not
retry gating). Neither uses `dev.langchain4j.exception.*` — zero hits repo-wide. `[src]`

> **#593 did ~70% of this item.** It extracted the shared classifier, reduced `AgentExecutionHelper`
> to a delegating shim, and added the typed-JDK and `WebApplicationException` tiers this item called
> for. **What remains is the langchain4j part R4 is actually about:** add tier 1
> (`RetriableException`/`NonRetriableException`) and tier 2 (`HttpException.statusCode()`) to the shared
> classifier, and make the cascade copy delegate to it. **Two edits, no new class.** Part II R4 is
> authoritative — the multi-layer correction history that used to live here has been folded into it.

**The trap: the obvious adoption is a regression.** The `ExceptionMapper.DEFAULT` call sites in OpenAI, Anthropic, Ollama, Mistral and Google-AI-Gemini are **all inside `ServerSentEventListener` implementations — streaming only**. `[src]` On the *synchronous* `chat()` path — the only path our classifiers guard (`AgentExecutionHelper.java:96`, `LegacyChatExecutor.java:76`, `AgentOrchestrator.java:851`) — those five throw raw `HttpException`, which extends `LangChain4jException` **directly and is neither `RetriableException` nor `NonRetriableException`**. Sync-path typed coverage is **3 of 12 builders** (Azure, Bedrock, and new at beta28, Vertex Gemini). Catching only the Retriable/NonRetriable split would classify OpenAI, Anthropic, Ollama, Mistral, Gemini, HuggingFace, Jlama and Oracle failures as **non-retryable** — strictly worse than the substring hack.

**Take instead:** `HttpException` is **public API** (`dev.langchain4j.exception`, not `.internal`) and exposes `statusCode()`. `[src]` Uniform, string-free classification across every HTTP-based provider.

**One shared classifier, both call sites, one change:**

1. `RetriableException` → retry, `NonRetriableException` → fail fast *(Azure, Bedrock, Vertex Gemini)*
2. **`HttpException.statusCode()`** → 408/429/5xx retry, 401/403/404 fail fast *(the tier that actually covers us)*
3. Existing typed JDK checks + the `WebApplicationException` branch — **restore it to the cascade path**
4. Substring fallback, last resort only *(HuggingFace, Jlama, Oracle)* — use the **union** of both current sets so neither path loses a case

Log which tier classified each failure, or there is no way to know when tier 4 can be retired. Do **not** call `@Internal ExceptionMapper.DEFAULT`; replicate the status table in EDDI code. Config uses EDDI-owned neutral names (`TIMEOUT`, `RATE_LIMIT`, `SERVER_ERROR`, `NETWORK`, `AUTH`), `retryOnUnclassified: true` for one release.

**Leave `ObservableChatModel.java:78` out of this.** Pass 1 called `new RuntimeException(e.getCause())` a classifier hazard. It sets the message to `cause.toString()` — FQCN **plus** original message — and **preserves the cause**, which both classifiers walk to the end of the chain. Of the 15 classes in `dev.langchain4j.exception`, exactly one FQCN matches a trigger substring (`TimeoutException`) and it *is* retriable; six of the eight triggers cannot appear in any FQCN (spaces, digit runs). Cosmetic, no reachable consequence. `[src]`

---

### R5 — `ChatModelListener` as EDDI's observability SPI · **S–M**

**Why this reversed.** Pass 1 rejected it as "per-provider-builder registration; Jlama and HuggingFace do not expose `listeners()`, so coverage is permanently uneven". The builder half is true; the conclusion is wrong, because **builder registration is not where dispatch happens.** `ChatModel` declares `default List<ChatModelListener> listeners()`, and the interface's own `default chat(ChatRequest, ChatRequestOptions)` fires `onRequest`/`onResponse`/`onError` around `doChat`. Any `ChatModel` — **including EDDI's own decorator** — supplies listeners by overriding `listeners()`. Coverage is **uniform across all 12 providers** precisely because a decorator can host it. `[src]` Listener and decorator are not alternatives; the decorator is the correct vehicle.

**"EDDI already owns the seam" does not survive contact with the code.** `ObservableChatModel.wrapIfNeeded` returns the **raw model unwrapped** unless `timeout`, `logRequests` or `logResponses` is set — the default case. `ChatModelRegistry.getOrCreateStreaming` **never calls it at all**. It has **no error hook**: on failure it logs nothing, counts nothing, and rethrows. `[src]` Ours is not a superset of the listener contract; it is a strict subset missing the failure half and the streaming path.

**And EDDI has zero observability on LLM calls.** Across 29 files using `MeterRegistry` and ~60 `eddi.*` metric names there is not one for LLM latency, tokens, errors or attempts. We meter nonce-replay rejections and prompt-snippet cache hits, but not a single token. `[src]`

**Work (~40–60 lines):**
1. **Prerequisite D11 — complete the decorator.** Override `doChat()` instead of `chat()`, plus `listeners()`, `provider()`, `defaultRequestParameters()`, `supportedCapabilities()`. It currently implements neither `doChat()` nor the two-arg `chat` overload, so the two-arg form hits `doChat()`'s `throw new RuntimeException("Not implemented")` — a hard blocker on `ChatRequestOptions`, the mechanism for per-conversation listener context. `[src]`
2. Drop the `wrapIfNeeded` early return (always-wrap) and **wrap the streaming path too**.
3. `listeners()` returns EDDI's CDI-discovered listener beans.
4. Pass conversation/agent/attempt via `ChatRequestOptions.listenerAttributes()` — the upstream answer to the shared-cached-model context problem, which a native decorator has no answer for either.

**Metric names:** adopt the **OpenTelemetry GenAI semantic conventions** (`gen_ai.client.token.usage`, `gen_ai.client.operation.duration`, tagged `gen_ai.system` / `gen_ai.request.model` / `gen_ai.response.model`) as EDDI's own names. We already ship `quarkus-micrometer-registry-prometheus` and `quarkus-opentelemetry` with a pre-wired OTLP endpoint — standard names light up Grafana/Datadog/Langfuse/Arize with zero EDDI-authored dashboards. `[src]`

**Honest counterweight:** a listener is a mechanism, not a metric. Nothing improves until we write the bodies, and we still touch each call site to pass `ChatRequestOptions`. The net saving over "instrument the call sites" is smaller than it first appears. But: one place instead of seven-and-growing, error coverage that does not exist, streaming coverage that does not exist, and third-party interop at zero architectural cost — a listener is a **pure sink** that cannot alter request or response.

---

### R3a — RRF fusion via `DefaultContentAggregator` · **S**

**Not a 1.18.0 item.** `DefaultContentAggregator`, `ReRankingContentAggregator` and `ContentMetadata` are **byte-identical between `langchain4j-core` 1.17.0 and 1.18.0** — shipped capability already on our classpath, never used. `[src]`

**Today:** `RagContextProvider.java:159` does `allResults.addAll(...)` per KB, concatenated in KB-declaration order by `formatRagContext()` (`:194-212`). No dedup, no cross-KB ranking, no fusion. `[src]`

**Why RRF is not merely convenient but the only *correct* fusion.** Each KB is a separate `RagConfiguration` with its own `embeddingProvider`, resolved per-KB at `RagContextProvider.java:138,152`. `[src]` Different embedding models mean cosine scores across KBs are **not on a comparable scale**, so a naive score-sort would be actively wrong. `ReciprocalRankFuser` is purely rank-based (`1.0 / (k + rank)`, k=60) — exactly the right primitive for incomparable-scale lists.

**⚠️ Dedup does NOT come free — this claim was wrong.** `DefaultContent.equals` does delegate to `textSegment`, but `TextSegment.equals` (core 1.18.0) compares **`metadata` as well as `text`**. EDDI stamps per-KB metadata at retrieval, so the same chunk retrieved from two KBs is **not** equal and will **not** collapse. `[src]` Cross-KB ranking (the primary win) still lands; dedup requires an explicit text-only key. See Part II R3a for the corrected approach — and note this also **dissolves** the provenance-vs-dedup tension described below, since metadata-based dedup was never working in the first place.

**Three things that will bite:**
- **RRF over a single KB is a no-op.** All of R3a's value is conditional on agents configuring >1 knowledge base. Gate the fusion so single-KB tasks are byte-identical to today.
- **Provenance and dedup are in direct tension.** Stamping `kbName` into `TextSegment` metadata requires *reconstructing* segments, which makes otherwise-identical chunks from different KBs unequal — **disabling the dedup above**. Decide explicitly: stamp for provenance, or keep an out-of-band `textHash → kbName` map (first-writer-wins) and preserve dedup. Pass 1 presented the metadata stamp as free; it is not.
- After fusion results interleave KBs, so `formatRagContext`'s emit-header-on-change logic (`:199-205`) emits repeated `### Source:` headers. Group before formatting.

**R3b — `ReRankingContentAggregator` · defer to P4.** `CohereScoringModel` and `VertexAiScoringModel` are already on the classpath (`pom.xml:257-259`, `:262-265`), but it needs a new `ScoringModelFactory` (cloning `EmbeddingModelFactory` including its vault path), new config in two model files, a synchronous per-query network call on the conversation hot path in a subsystem with **zero** `MeterRegistry` references, and a per-query dollar cost with no tracking. Do **not** redefine `maxResults` (default 5) as the first-stage candidate count — that silently changes behavior for every stored agent config; add `firstStageMaxResults` instead. Always pass an explicit `querySelector`, since `DEFAULT_QUERY_SELECTOR` **throws** on >1 query and becomes a hard failure the day query expansion lands. `[src]` Document that `rerank.minScore` is on a scoring-model scale unrelated to the embedding cosine `minScore` (default 0.6), or designers will copy 0.6 across and drop everything.

---

### R2 — Anthropic request parameters · **S**

`AnthropicLanguageModelBuilder.java:21-26` exposes exactly six params (`apiKey`, `modelName`, `timeout`, `temperature`, `logRequests`, `logResponses`); `grep -rniE "cache_control|cacheControl|promptCach"` over `src/main/java` → **zero hits**. `[src]` `cacheSystemMessages(Boolean)` and `cacheTools(Boolean)` are **also methods on the model builder**, so existing param plumbing suffices — no `ChatRequest` refactor. `[src]`

**Adopt `cacheTools: true` — safe unconditionally.** `AnthropicMapper.java:381-397` applies `cache_control` to the *last tool only*, and the tools block precedes system in the request, so the cached prefix is tools-only and immune to system-tail churn. `[src]`

**The money is intra-turn.** `AgentOrchestrator.java:852-868` re-sends the identical system+tools prefix on every iteration up to `maxToolIterations` (default 10). Anthropic's 5-minute ephemeral TTL covers a turn comfortably, so iterations 2..N hit the cache written by iteration 1 even when cross-turn misses. `[src]`

**`cacheSystemMessages` is opt-in and documented as HARMFUL with RAG or `conversationSummary`.** Pass 1 claimed "EDDI's system message is a large invariant prefix" — false, and it cited the volatile parts as proof. RAG context (`LlmTask.java:355`), httpCall RAG (`:339`) and the rolling summary (`ConversationHistoryBuilder.java:85,187`) are all appended to the system-message **tail**, and `AnthropicMapper.java:243-252` places the cache breakpoint on the **last** system block only. `[src]` A per-turn-varying tail means a cross-turn **miss every turn** while still paying Anthropic's ~25% cache-write premium — a net cost **increase**. `LlmTask.java:318` also runs Qute over params, so `{properties.x}` varies per turn even with RAG off.

**Do NOT touch `ChatModelRegistry.filterParams()`.** Pass 1's instruction here was backwards and would introduce a bug: it is a 9-key **denylist** whose remainder forms `ModelCacheKey`. `[src]` Cache settings change model identity and **must stay in the cache key**; adding them to the denylist would make two tasks with different cache settings share one cached `ChatModel`. Add a comment recording why.

**Also expose:** `thinkingType`, `thinkingBudgetTokens` (gives Multi-Model Cascading a cheaper escalation axis than model-swapping), `returnThinking`, `disableParallelToolUse` (the provider-side half of the dead `enableParallelExecution` knob). Anthropic silently no-ops below a ~1024-token cacheable prefix. `[docs]`

**Follow-up that restores the original headline:** move the RAG/summary blocks out of the system-message tail (into a leading `UserMessage`, or a second `SystemMessage` placed *before* the stable prompt) so the stable prefix ends at the breakpoint. That converts `cacheSystemMessages` from harmful to ~90% effective for RAG agents.

---

### R6a — `@CompensateFor` annotation · **XS**

`@CompensateFor` is `@since 1.17.0` in **`langchain4j-core`** — already on our classpath, not a 1.18.0 novelty, not in the agentic beta module. `[src]` Honor `@CompensateFor("toolName")` when scanning built-in `@Tool` beans in `buildToolList`, populating an EDDI-owned compensation registry alongside `toolExecutors`/`toolSources`. One annotation, one `String`, zero interface surface, zero behavior. If upstream removes it we lose a marker, not a mechanism.

**Reject their implementation:** all compensation logic is `private` on `@Internal ToolService`, reachable only via the AiServices builder flag, and it rewrites a `ChatMemory` we do not have. Nothing to call. `[src]` *(Correction for the record: pass 1 said the feature "excludes everything EDDI has". The gate is `!(toolExecutor instanceof DefaultToolExecutor)` and EDDI's built-ins **are** registered as `DefaultToolExecutor` at `AgentOrchestrator.java:712` — the exclusion bites http/mcp/a2a/dynamic, not built-ins.)*

---

## 7. Build or fix natively

### P0 — defects in shipped, documented, billed behavior

**D1 · Audit ledger records zeros in three documented fields.** `LifecycleManager.java:460` *reads* `audit:token_usage`; the only other repo occurrence is a Mockito stub. Cost is a literal `0.0` at `:475` at **all three** production `AuditEntry` sites, and no copy-with helper can ever set it. `toolCalls` is hardcoded `null` at `:474`. `[src]` Tokens *are* computed (`LegacyChatExecutor.java:99-101`) and thrown away at the audit boundary; agent mode never captures them. The Manager UI aggregates a permanently-zero rollup. EU AI Act relevant. Adjacent bug on the same lines: `:419` reads `audit:confidence` while `LlmTask.java:517` writes `audit:cascade_confidence` — *and* writes a `String` into a `Double` slot.

> **Fix carefully:** do **not** use `Map.of` (NPEs on null counts — `SummarizationService.java:133-138` already null-guards); do **not** nest a map into `llmDetail` without first making `AuditHmac.sortedMapString` recurse, because `org.bson.Document.toString()` prefixes `Document{` on read-back and **breaks HMAC verification**; accumulate across the sub-task loop and cascade steps; write `audit:compiled_prompt` in `executeResume` or the whole `llmDetail` block is dropped on every resumed turn.

**D2 · `maxBudgetPerConversation` bounds nothing, and the same bug voids `toolRateLimits`.** `ToolCostTracker.TOOL_COSTS` is keyed on **config-whitelist slugs** (`websearch`, `pdfreader`) but the live path passes `toolRequest.name()` — the `@Tool` **method** name (`searchWeb`, `extractTextFromPdf`). Exact case-sensitive lookup ⇒ **every built-in tool prices at $0.00**, not just http/mcp/a2a/dynamic. The identical mismatch silently voids documented per-tool `toolRateLimits` overrides at `AgentOrchestrator.java:1071-1072`. `[src]` Corroboration this is a known-shape bug: `ToolCacheService.getSmartTTL` uses lowercase+substring matching *for exactly this reason*; `ToolCostTracker` never got that treatment. Meanwhile an httpCall a designer happens to name `webscraper` **is** charged $0.002/call — pricing by accidental string collision.

> Fix at the boundary once for all three consumers; re-scale the table (max $0.002/call against documented $2–$5 budgets is decorative); then move it to config per Golden Rule 1. **Ship enforcement opt-in for one release** — activating a ceiling that has been inert since it shipped will break live agents.

**D3 · `toolTrace` has never reached the SSE stream.** Writer stores `langchain:trace:<modelType>:<configTaskId>` (`LlmTask.java:627,768`); reader looks up prefix `langchain:trace:ai.labs.llm` (`LifecycleManager.java:414`, from `LlmTask.ID`). `getLatestData` is a `startsWith` scan — the prefixes cannot match. `[src]` `summary["toolTrace"]` is never populated and `RestAgentEngineStreaming.java:72-79` is **dead code**, despite the comment at `:413` claiming it "enables live tool call display in UI". `LifecycleManagerTest.java:1028` mocks a key production never produces, so the test is green and vacuous. This also retires the claim that tool-call visibility is already solved (relevant to I3) and downgrades D12.

**D5 · `ToolCacheService` cache is global.** `buildKey` is `toolName + ":" + arguments` against a single `@ApplicationScoped` Caffeine instance named `tool-results`, with **no conversation, user or tenant scoping**. `[src]` Any user's result is served to any other user, any agent, any conversation, process-wide. `docs/security.md:243` misdescribes it as scoped ("Identical tool calls within the same conversation…"). Triage first.

> **Three corrections from the Part II spec pass, all of which change the fix:**
> 1. **"Tenant-scoped" is not achievable** — there is no usable tenant identity (`TenantQuotaService.getDefaultTenantId()` is a single-tenant stub). The real axes are `userId` / `agentId` / `conversationId`. Scope on **`USER` by default, fail-closed**.
> 2. **`docs/security.md:241` is also wrong** — it claims the key is a SHA-256 hash; `buildKey` returns **plaintext** `toolName + ":" + arguments` for args ≤ 2048 chars, hashing only above that.
> 3. **NEW pre-existing defect (D5b) — the smart-TTL table has never done anything.** `CacheFactory.getCache(String)` builds a size-only Caffeine and `CacheImpl.put(k,v,ttl,unit)` **discards the TTL**; `CachedResult` has no `expiresAt` field despite `CacheImpl`'s javadoc claiming `ToolCacheService` tracks expiry. `[src]` **Tool cache entries therefore never expire** — only `maximumSize` eviction applies, and `tool-results` is absent from `CACHE_SIZES` so the bound is the 1000-entry default. A stale or poisoned result persists indefinitely. This compounds the scoping bug and should be fixed with it.
> 4. Do **not** ship a built-in "pure tool ⇒ GLOBAL" name table — `getSmartTTL`'s substring match already fails against real `@Tool` method names (`"calculate".contains("calculator")` is false), so a name-based table would silently misclassify. GLOBAL must be explicit per-tool config.

### P1–P2

**D11 · Complete `ObservableChatModel`** — prerequisite for R5 (§6). Second, **non-latent** hole: `getOrCreateStreaming` never calls `wrapIfNeeded` *and* strips `timeout`/`logRequests`/`logResponses` via `filterParams`, so on any streaming task those three documented settings are silently discarded and a hung provider connection has **no timeout**. `[src]` A real fix needs streaming-appropriate semantics (first-token / inter-token gap), not `Future.get`.

**R6b + D4 · Compensation and idempotency — orthogonal, keep both.** Idempotency prevents the same call firing twice; compensation undoes a call that legitimately fired once when a later step fails. Fixing one leaves the other hole wide open. *(Pass 1 redirected compensation to idempotency as "the correct first increment" — that was a category error.)*

- **Compensation (R6b, build ours).** `grep -rniE "compensat" src/main/java` → **zero hits**. `runToolCallLoop` executes a batch sequentially; if call 3 of 4 fails, calls 1–2 have committed and EDDI only feeds the error back to the model, which re-plans and re-executes. `[src]` The canonical agentic workload (reserve → charge → confirm) is not safely implementable today. Per Golden Rule 1 the primary surface is JSON — `compensatingCall` on httpCall definitions plus MCP/A2A equivalents, executed in reverse order by our own loop. Because we own the unified executor map this is a **strict superset** of upstream's reflection-limited capability, and the natural upstream contribution. `IHitlToolJournalStore.tryClaim/markExecuted` is already most of a saga log.
- **Idempotency (D4, fix ours).** The replay path is **not** mainly `executeWithRetry`: `enableToolCaching` defaults `true` and short-circuits before the executor (300s TTL vs ≤10s backoff), providers already retry 429/timeout/5xx internally, and HITL-gated calls execute under the journal *outside* the retry lambda. `[src]` The unmitigated path is **cascade escalation** — `CascadingModelExecutor` re-invokes the same messages on the next step on **low confidence with no error at all** (the designed happy path), with a *different* model, making argument-serialization drift the expected case; its `future.cancel(true)` on timeout can produce **concurrent** duplicate execution. Group `MemberFailurePolicy.RETRY` is a third path. ⇒ Fix with a turn-scoped journal keyed on `(conversationId, turn, toolName, sha256(args))` around `executeToolWrapped` — the only point below all three replay mechanisms. Narrowing `executeWithRetry` to wrap only `chatModel.chat()` is good hygiene but does **not** make re-execution structurally impossible, and it silently changes the abandoned-thread terminal state (`LifecycleInterruptedException` relies on being re-wrapped).

**R1 · Binary document ingestion for RAG — mostly ours.** `RagIngestionService.java:80` is `Document.from(documentContent, …)` with `documentContent` a `String`, and there is no `DocumentParser` import; repo-wide, `EmbeddingStoreIngestor|Document.from|DocumentParser` matches only that file. RAG ingests plain text only — confirmed. `[src]`

- **Correction 1 — the blocker is the REST contract, not a missing parser.** `IRestRagIngestion.java:27,40` is `@Consumes(MediaType.TEXT_PLAIN)` with a `String` body. `[src]` Binary bytes cannot reach the service at all; dropping in a `DocumentParser` changes nothing until the endpoint changes. That is why this is **M**, not S.
- **Correction 2 — we already own the highest-value slice.** `AttachmentTextExtractor` is a shared, stateless, `@ApplicationScoped`, `byte[]`-in service already doing capped PDF extraction (PDFBox 3.0.7, `pom.xml:267-269`) plus text-like formats, page ranges and PDF metadata, with **four existing production consumers**. `[src]` Zero new dependencies needed for the main case.

**Three increments:**
1. **Unblock transport.** Binary sibling endpoint (`@Consumes(APPLICATION_OCTET_STREAM)`, `byte[]` + declared MIME param), keeping TEXT_PLAIN for back-compat. Validate with existing `MimeValidator.detectMime`/`isCompatible`; size-cap mirroring `eddi.attachments.max-forward-bytes`.
2. **Reuse `AttachmentTextExtractor`.** Inject into `RagIngestionService`. **Critical:** its default cap is `10_000` chars — correct for inline LLM context, catastrophic for RAG ingestion. Add a separate RAG-scoped cap. Fail with an actionable status on unsupported types rather than embedding an empty document.
3. **Office only if demanded.** `langchain4j-document-parser-apache-poi` (**not** `-tika`, see §9), with `RagConfiguration.parserType: "auto"|"text"|"pdf"|"office"`, default `"auto"`. Extend `AttachmentTextExtractor` with the Office branch so RAG *and* the attachment path are fixed together — `MimeValidator` already accepts `.docx`/`.xlsx`/`.pptx` uploads that `AttachmentForwarder` then rejects as "unsupported type". `[src]`

**D7 · JSON-mode enforcement is provider-asymmetric.** *Reframed:* pass 1's "three competing capability matrices" is **wrong** — there is exactly one (`AgentSetupService.supportsResponseFormat`); `McpSetupTools:149` is a documented one-line test delegate that cannot drift, and `ModelCapabilityService` is an unrelated multimodal matrix. `[src]` **The real defect:** the matrix approves mistral and azure-openai but only `OpenAILanguageModelBuilder` reads `responseFormat`, and the compensating request-level path (`LegacyChatExecutor:78`) is reached **only in the no-tools, non-streaming branch** — `jsonMode` is never threaded into `AgentOrchestrator` or `StreamingLegacyChatExecutor`. Since `AgentSetupService` sets `enableBuiltInTools(true)` in the same method that sets `convertToObject=true`, tool-enabled mistral/azure agents get **no** API-level JSON while an identical openai agent does; with `addToOutput=false` the quickReplies/sentiment output degrades silently. ⇒ Thread `jsonMode` into all three execution modes, *then* drop the two providers from the matrix. Do **not** add builder-level `responseFormat` — `docs/changelog.md:6180` records a Gemini 400 caused by exactly that (JSON mode baked into a cached model later reused with tools).

**D6 · History lossy, in-turn context unbounded.** `ConversationHistoryBuilder` emits only `UserMessage`/`AiMessage` text; no tool message type appears anywhere in it. `[src]` *Correction:* http-sourced tools **do** persist into `conversationOutputs` via `PrePostUtils.createMemoryEntry`, so `{memory.last.httpCalls.*}` is a real escape hatch for that source — the gap is specific to builtin/MCP/A2A/dynamic/memory/recall. **Separate new defect:** `runToolCallLoop` appends an `AiMessage` plus one `ToolExecutionResultMessage` per call per iteration up to `maxToolIterations`, with per-result truncation but **no aggregate budget** — a tool-heavy turn can exceed the model context window mid-loop and hard-fail.

> **The one artifact worth taking from `TokenWindowChatMemory` without the dependency:** evicting an `AiMessage` carrying `ToolExecutionRequests` requires evicting its trailing `ToolExecutionResultMessage`s, or the eviction itself becomes a provider 400.
> **Implementation note:** read the trace from the step stack at message-build time (mirroring `ConversationLogGenerator.withAttachmentExtracts`) — do **not** write it into `conversationOutputs`, which is also the SSE, A2A peer-response, GDPR-export and MCP payload channel.

**D14 · RAG fan-out is serial.** `RagContextProvider.retrieveContext` iterates KBs in a strict `for` loop, each doing an embedding call plus a vector query, additive on the critical path of every turn. `[src]` ~15 lines with Quarkus `ManagedExecutor`, keeping the per-KB `try/catch` inside each task — captures the one real benefit of `DefaultRetrievalAugmentor` without its fail-fast composite-future defect.

### P3 — cleanup

| Item | Action |
|---|---|
| **D8** `injectionStrategy`/`contextTemplate` | Confirmed dead: zero production call sites, only POJO round-trip tests. `[src]` **Understated** — `injectionStrategy` is a rendered **dropdown in the shipped Manager bundle** for both `knowledgeBases[]` and `ragDefaults`. **Overstated** — pass 1's "two injection points" conflates a bugfix (`LlmTask:355`, existing ignored config) with a feature proposal (`LlmTask:339`, where `httpCallRag` is a bare `String` with no config object to ignore). The two modes are mutually exclusive branches, so there is no `ref → ragDefaults` chain. Do **not** throw from `configure()` on unknown values (fails the whole executable workflow). `user_message` injection needs token accounting fixed first — `buildTokenAwareMessages` never counts the system message against `maxContextTokens`. |
| **D10** `enableParallelExecution` | **Delete** both fields, the two orphaned methods, and the Manager checkbox. The existing `executeToolsParallel` is reflection-based and type-incompatible with the `ToolExecutionRequest`/`ToolExecutor` dispatch path — vestigial, not near-miss plumbing, so "wire it up" is not a wire-up. Stored configs safe (`FAIL_ON_UNKNOWN_PROPERTIES=false`). Note `parallelExecutionCounter` and siblings register at `@PostConstruct`, so five `/q/metrics` meters can never be non-zero. |
| **D9** `TenantQuotaService` | **Downgraded high → low.** Self-labelled a stub (`AGENTS.md:136`), opt-in twice, sibling slots work, and the designed scope is *tool* cost from a table that (per D2) prices everything at $0.00. Pass 1's "no default quota is seeded" is **wrong** — all three stores bootstrap one in their CDI constructor. Two real sub-bugs worth fixing: `MongoTenantQuotaStore.tryAddCost` upserts on `(tenantId, costMonth)` while `tryIncrementConversations` upserts on `(tenantId, dayStart)`, creating **two documents per tenant** so `getMonthlyCost` returns 0.0 forever; and `>=` vs `>` boundary divergence across the three stores. |
| **D13** Embedding spend unmetered | Confirmed, **downgraded to low** — every retrieval/ingestion is logged at INFO with KB name and counts, RAG is off by default, and the spend is ~4 orders of magnitude below the equally-unmetered chat-token spend. Sequence behind R5. `EmbeddingModel.addListeners(...)` is a **core default method** at 1.17.0 with `tokenUsage()` — one line at the `EmbeddingModelFactory.build()` choke point. **Beware:** the wrapper does not override `dimension()`, whose core default is a **billed** `embed("test")` call masking `DimensionAwareEmbeddingModel.knownDimension()`. `[src]` |
| **D12** `SecretRedactionFilter` scope | **Downgraded to informational.** Pass 1's "HITL strings only, absent from model I/O" is **false**: four production sites, including `AuditLedgerService.scrubSecrets` (unconditional on every entry, recursing into `input`/`output`/`llmDetail`) and `BoundedLogStore.capture`. Model I/O **is** redacted on the persisted audit path. `[src]` Residual gap is the tool trace — and per D3 that SSE channel is dead. If tool args are worth redacting, do it at the producer (`AgentOrchestrator.java:1032,1089`). |
| **D15** Docs | `docs/mcp-server.md:633-676` documents an `mcpServers` array on a langchain task and a `setup_agent(mcpServers:…)` parameter; `LlmConfiguration` has no such field and `getMcpServers()` has **zero** call sites. `[src]` The live path is the `mcpcalls` workflow extension. Also `eddi.audit.retentionDays` is read by no Java code. |
| `EddiChatMemoryStore` | **Delete** — zero production consumers; its javadoc premise (`quarkus-langchain4j`) does not exist in this build. Two test classes exist solely to cover it. |

---

## 8. Where EDDI is ahead

Do not touch these — but each has one thing worth learning.

| Area | Why we win | Worth stealing |
|---|---|---|
| **HITL** | Four timeout policies, `pauseEpoch`-keyed write-ahead journal, per-call verdicts with argument amendment, EU AI Act ledger, owner/admin/approver authz, cross-pod recovery, Slack+MCP+REST surfaces. Theirs: four classes, all but the exception in `.internal`, no timeout policy, no authz, no audit, no at-most-once. `[src]` | Our human is only ever a **gate** — `HitlVerdict` is `{APPROVED, REJECTED}` and `MemberType` is `{AGENT, GROUP}` with no `HUMAN`. langchain4j models the human as a **participant with typed output** flowing into workflow state. Add `MemberType.HUMAN` + an elicitation-shaped decision, reusing existing timeout/audit/authz/journal/Slack machinery. **Zero lc4j code transfers.** |
| **Model fallback / cascading** | Does not exist upstream (open issue #874). | Nothing — upstream it (U1). |
| **Conversation memory** | Against its real counterpart `ConversationHistoryBuilder` (not `IConversationMemory` — pass 1's comparand was a category error), `ChatMemory` cannot express `anchorFirstSteps`, the omitted-turns gap marker, rolling-summary `skipSteps`, or multimodal `Content` parts. Adopting would be a feature regression. `[src]` | The tool-message eviction invariant (D6). |
| **RAG error isolation** | Our `try/catch` sits *inside* the per-KB loop: one failing KB logs a WARN, records a per-KB trace, and survivors still contribute. Their `retrieveFromAll` uses `allOf(futures).thenApply(…join)` — **one retriever exception fails the composite future**, taking down RAG for the entire turn. `[src]` | Their concurrency (D14), not their composition. |
| **Orchestration governance** | Group conversations (7 styles, group-of-groups), dynamic agents, cascade, budget gates, audit — all inside our loops. | We genuinely lack conditional routing, runtime map-fan-out, a scope-persistence SPI, and scored response selection. Build natively; "duplicates with less capability" was not accurate as a blanket claim. |

---

## 9. Rejected

| Item | Reason (post-correction) |
|---|---|
| **`dev.langchain4j.agentic`** | The commonly-stated reason is **false** — the module ships a complete *programmatic* builder API (`sequenceBuilder`, `parallelBuilder`, `loopBuilder`, `conditionalBuilder`, `supervisorBuilder`, `plannerBuilder`, …, each with an untyped `Map<String,Object>` overload, plus `AgentBuilder.withoutDeclarativeConfiguration(Class)`). Topology **is** assemblable at runtime from a MongoDB document. `[src]` **The real blocker:** `AgentBuilder.build()` is `build(DefaultAgenticScope, AiServiceContext, AiServices<T>)` — every agent is constructed **through `AiServices`**, whose tool-call loop is where our HITL journaling, budget gates, cascade escalation and audit writes live. The escape hatch is unusable: `NonAiAgentInstance`, `AgentExecutor`, `AgentInvoker`, `PlannerExecutor` are all `.internal` with **no public registration API**. Fixable upstream — see U3. *For the record:* Blackboard/Debate/BDI are in `langchain4j-agentic-patterns`, **not on our classpath**. |
| **lc4j HITL** (`HumanInTheLoop`, `SuspendedResponse`) | **The one rejection confirmed unprimed.** `SuspendedResponse` *and* `PendingResponse` are both in `.internal` — only the exception is public. Checkpointing occurs only if an `AgenticScopeStore` is configured, so it is a component of the rejected runtime, not a standalone primitive. `[src]` The portability argument is nullified by EDDI being a closed platform. |
| **Guardrail executors** | Adopt the SPI, reject the loop — see R0. Not because it is sealed (it is `non-sealed` and overridable) but because it throws on terminal failure, its reprompts bypass budget/token/HITL, and its memory-mutation surface is inert. |
| **`PatternBasedPromptInjectionGuardrail`, `MessageModeratorInputGuardrail`** | Pattern set is `private static final List<Pattern> DEFAULT_PATTERNS` — hardcoded in Java, unreachable from JSON. Golden Rule 1. Lift the regex set as data; do not add the `langchain4j-guardrails` artifact. `[unv]` — never inspected; rejection rests on Golden Rule 1. |
| **`ChatMemory`/`ChatMemoryStore`** | Feature regression against `ConversationHistoryBuilder` (§8), and it owns a mutation loop that fights our rebuild-from-`conversationOutputs` model. *Closed question:* the orphaned-`ToolExecutionResultMessage` eviction hazard **does not apply to us** — `ConversationHistoryBuilder` has zero references to tool message types. |
| **`DefaultRetrievalAugmentor`** | Confirmed on a **different reason** than pass 1 gave — two of its three objections were one-line-neutralizable and one was factually wrong (`augment()` returns `AugmentationResult` carrying **both** `chatMessage()` and `contents()`; the unbounded `ThreadPoolExecutor` is an overridable default; per-KB `maxResults`/`minScore` precedence is **not** lost). **The real blocker is error isolation** (§8) — a hard availability regression on a multi-tenant config-driven system. |
| **`DefaultContentInjector`** | `createPrompt()` does an unconditional `((UserMessage) chatMessage).singleText()` cast, which **throws** on any multimodal `UserMessage` and `ClassCastException` on a `SystemMessage` — the conversion branch pass 1 described is unreachable dead code. Structurally cannot produce our `system_message` strategy. `[src]` |
| **`ChatModelListener` — the *artifacts*** | Reject `langchain4j-micrometer-metrics` / `langchain4j-observation`: `@Experimental`, builder-level registration, and the metrics module ships essentially one class emitting one metric (not even operation duration). **Adopt the `gen_ai.*` convention names natively** (R5). |
| **Apache Tika** | *New rejection.* **CVE-2025-66516, CVSS 10.0** — XXE via crafted XFA embedded in a PDF, affecting `tika-parsers-standard-package` through 3.2.1 `[docs]` — i.e. exactly the untrusted-operator-binary path R1 creates, in a codebase that banned `ScriptEngine` and hand-wrote `SafeMathParser` rather than accept that risk class. ServiceLoader-driven ⇒ native-image blocker (the same objection pass 1 used against ONNX while recommending Tika in the same document). Its only unique value over what we own is Office, for which `-apache-poi` is the narrow alternative. |
| **Model fallback / circuit breaker from lc4j** | Does not exist upstream (#874). Ours is ahead. For circuit breaking use `smallrye-fault-tolerance` — idiomatic Quarkus. |
| **`SqlDatabaseContentRetriever`** | LLM-generated SQL against a live `DataSource`. Consistent with a documented project constraint. |
| **`@P` defaultValue** | Premise was wrong in the *other* direction — we *do* use `DefaultToolExecutor`, so both halves already activate on `@Tool` built-ins. Nothing to adopt. |
| **OpenAI TTS (#4697)** | Out of scope — we model multimodal *input* only. Recorded so it is not re-raised. |

---

## 10. Worth investigating

| # | Item | Why | Blocker / cost |
|---|---|---|---|
| **I1** | **`langchain4j-embeddings` (ONNX)** — *reopened from rejected* | We have **no offline embedding path**: all 8 providers need network egress or a second process. `docs/rag.md:218` already commits to "ONNX in-process embeddings (air-gapped / edge)" by name. `OnnxEmbeddingModel(Path, Path, PoolingMode)` lets operators supply their own weights — a leaf provider in the existing `switch`. `[src]` | Pass 1's ~128 MB is correct for neither figure: as-declared **~174 MB**; **~51 MB** pruned to linux-x64 (our `docker build` is single-platform); **~35 MB** without bundled weights. `[src]` The offered substitute does not substitute — `JlamaEmbeddingModel`'s **constructor downloads weights from HuggingFace**, so it is not air-gapped. **Gate on a measured image delta.** |
| **I2** | **`Skill` interface as an SPI** | Real unfilled gap: progressive disclosure of **procedures**. `DiscoverToolsTool` discloses by crude substring match and returns only name+description — never *how* to use them. `PromptSnippet` is a Skill minus disclosure (selection is static). `AgentConfiguration.Capability` is `{skill, attributes, confidence}` with **zero instruction content**. `[src]` Pass 1's "Capability Registry already owns this" is false. | Reject the **`Skills` class** — its activation state is recovered by scanning `ToolExecutionResultMessage.attributes()`, and `ConversationHistoryBuilder` discards tool messages, so activation would silently deactivate every turn. `[src]` Adopt the **interface** (three String-returning methods) over a MongoDB-backed config, driving activation through existing LAZY machinery. Effort **M**, mostly native. |
| **I3** | **Agent-mode streaming** | `LlmTask.java:551-553` emits the whole agent response as one `onToken` blob; the comment at `:500-501` claiming agent mode already streams is false. `[src]` | **L.** Hard parts are ours: HITL pause mid-stream needs an emitted-offset marker; cascade escalation after streaming step 1 needs a retraction event. Scope to text tokens only. |
| **I4** | **`JsonSchema` structured output** | `LegacyChatExecutor.java:78` sets `ResponseFormatType.JSON` but never `.jsonSchema(...)`. `JsonRawSchema.from(String)` takes a raw string — ideal for JSON-authored configs. `JsonExtractorOutputGuardrail` (R0) partially serves this free. | Stored `responseSchema` values are **pseudo-schemas** (shape-by-example with prose hints, `docs/langchain.md:1017`) — reusing the field would break every existing agent. Needs a new field. Gemini rejects `responseFormat=JSON` + function calling. `strictJsonSchema` is an OpenAI *builder* field ⇒ must go through the cache key. |
| **I5** | **`EmbeddingInputType` QUERY/DOCUMENT** | Fixes a **live defect**: ingestion (`RagIngestionService.java:86`) and retrieval (`RagContextProvider.java:138`) both call `getOrCreate(ragConfig)` and get the **same cached instance**; Gemini's `taskType` defaults to `RETRIEVAL_DOCUMENT` (`EmbeddingModelFactory.java:172`), so every Gemini RAG user embeds *queries as documents*. `[src]` | `@Experimental`, honored by ~3 of 8 providers — below ~6/8 it repeats the dead-config pattern. Flipping it on an existing index needs re-ingestion or recall degrades silently. Model as `auto\|on\|off` against `ModelCapabilityService`. |
| **I6** | **MCP surface beyond the anyOf fix** | `outputSchema` (#5293) would let us validate MCP tool *results*, which surface today as unchecked strings; server instructions (#5425) are discarded; subset-of-clients tool provider (#5602) is per-task MCP scoping — a config-shaped governance knob. Zero hits for any in EDDI. `[src]` | Unscoped. One focused pass against `McpToolProviderManager`. |
| — | **`MistralAiBatchChatModel`** | We have the batch shape: `DreamService`, `SummarizationService`, bulk re-ingestion. A 50% batch discount is material to `DreamService.maxCostPerRun`. | Unexamined, single-provider. |
| — | **`AgenticScopeStore`-shaped durable scope** | A 4-method persistence SPI plus `Planner.executionState()`/`restoreExecutionState(Map)` — an independently-designed statement of the crash-resilient suspend/resume problem we solved ad hoc for HITL. Worth a design review even if nothing is adopted. `[src]` | Inseparable from the agentic runtime as shipped. |

---

## 11. Open questions — resolve before P1

**`quarkus-langchain4j`.** `docs/changelog.md:8843` records dropping the extension in Phase 6E; `planning/native-image-migration.md` selects **Strategy B2 = re-adopt it** (`enable-integration=false`) for build-time reflection registration. `[src]` It is not a langchain4j abstraction — it is a Quarkus integration — so §3's rule cannot adjudicate it, and **no pass of this analysis examined it.** A plan premised on "stay on plain low-level langchain4j" is silent on a planned migration that reintroduces the layer above. **This is the single highest-value unexamined question.**

**Recurring version-skew cost.** ~25 artifacts, three version properties, two release trains, hand-maintained CVE pins at `pom.xml:53-65`. Priced as a one-off `S`; it is a per-release tax and the strongest argument for importing a BOM.

---

## 12. Upstream contributions

Not considered by pass 1 at all. An adopt/reject frame cannot see the option that changes the relationship.

| # | What | Matching issue |
|---|---|---|
| **U1** | Model fallback / cascading — ours is ahead, upstream has none | open **#874** |
| **U2** | HITL timeout policies + at-most-once journal | ahead of PR **#5767** |
| **U3** | **Public `AgentInstance` registration API** so a non-`AiServices` agent can join the planner/workflow layer | none filed — *this is the exact blocker that makes agentic adoption impossible for us; small, well-scoped PR* |

U3 is the highest-leverage: it converts a permanently-diverging relationship into an optional-interop one.

---

## 13. Sequencing

1. **P0 defects** — **D5 first** (data isolation), then **D5b** as its own change (larger than it
   looks — see §4.1), then D3 (XS, and #587's `sendJsonEvent` gives it a clean landing place), then
   D2, D1. **None of these is langchain4j work.** All are shipped, documented, billed behavior that
   silently does nothing — see §4.1 for the fix-vs-build split.
2. **Resolve §11** (`quarkus-langchain4j`) — it re-prices several P1 items and the R1 parser choice.
3. **P1** — D11 → R5 (decorator before observability), then R4, R0, R3a, R2.
4. **P2** — R6a+R6b+D4 together, R1, D7, D6, D14.
5. **P3** cleanup; **P4** investigations. File U1–U3 anytime.

---

## 14. Revisit triggers

Observable events, not a calendar date.

1. **Native-image migration starts.** Highest-probability invalidator. It forces §11, and if Strategy B2 executes, "stay on plain low-level langchain4j" is no longer the project's position. **Revisit this entire document at that moment.** It also re-prices R1 and I1.
2. **langchain4j decouples guardrails/agentic from `ChatMemory` and `AiServices`** — concretely, a `GuardrailExecutor` taking messages rather than reading `requestParams().chatMemory()`, or a **public** `AgentInstance` registration API. Either flips §3's rule. Plausible within 2–3 releases; file U3 rather than wait.
3. **We add query expansion** → `ReRankingContentAggregator.DEFAULT_QUERY_SELECTOR`'s throw goes from latent to hard failure. **We add agent-mode streaming** → reopens the executor and observability questions together.
4. **`EmbeddingInputType` provider coverage crosses ~6/8** → straight bugfix for the live Gemini defect (I5).
5. **lc4j ships fallback / circuit breaking (#874)** → our cascading either upstreams or diverges permanently. Decide deliberately.
6. **Any release moving `langchain4j-agentic-patterns` or the agentic scope store toward a public, `AiServices`-free API** — that is the surface we are re-deriving natively each release.

---

# Part II — Implementation specs

Part I decides **what and why**. Part II is the **how**: one spec per P0–P2 item, precise enough to
execute without redoing analysis. Each was produced by re-opening the source and verifying every
citation, so **where Part II contradicts Part I, Part II wins** — Part I's citations came from
analysis agents and a minority were wrong.

Every spec follows the same shape: corrections to the decision doc · files to touch (MODIFY /
CREATE / DELETE with real signatures) · config surface · stored-config back-compat · tests ·
acceptance criteria · traps · out of scope · sequencing.

## What the spec pass corrected in Part I

Applied inline above, listed here so nothing is silently revised:

| Item | Correction |
|---|---|
| **R3a** | **"Dedup comes free" was wrong.** `TextSegment.equals` compares `metadata` as well as `text`, and EDDI stamps per-KB metadata — so identical chunks from two KBs never collapse. Cross-KB ranking still lands; dedup needs an explicit text-only key. Also dissolves the stated provenance-vs-dedup tension. |
| **R4** | **The cascade classifier does not gate cascade retry.** `CascadingModelExecutor.isRetryableError` has exactly one call site (`:233`). Unification is still right; what each classifier governs is not what Part I assumed. |
| **R0** | **An EDDI `ChatExecutor` is mandatory, not optional** — `OutputGuardrailRequest` does `ensureNotNull(chatExecutor)`. `GuardrailResult` and its nested `Failure` are **sealed**. |
| **D5** | **Tenant scoping is unachievable** (no tenant identity exists) — scope on `userId`, fail-closed. Plus **new defect D5b: tool cache entries never expire** — `CacheImpl.put` discards the TTL and `CachedResult` has no `expiresAt`, so the whole smart-TTL table is inert and only 1000-entry size eviction applies. |
| **D1** | The three `AuditEntry` sites are **not** all in `LifecycleManager` — one is in `GdprComplianceService`. |
| **R2** | `AnthropicMapper` line numbers are wrong, and "the tools block precedes system" is misleading — JSON field order is `model, messages, system, …, tools`. The cache-prefix justification needs restating (see spec). |
| **D11+R5** | `ChatRequestOptions` lives in `dev.langchain4j.model.chat`, **not** `...model.chat.request`. |
| **D3** | All four cited facts verified correct — no corrections. |

## Baseline commit — read this before executing

**Part II's citations were verified against `70091de90` (PR #593). Part I is current as of `7d92b57b5`;
Part II's line numbers for D1, D2, D3 and R2 are one drift event behind** — `AgentOrchestrator`
(+31/-8) and `LlmTask` (+46/-50) moved again in PRs #603/#604. The defects are re-confirmed present
(see the drift table below); only the citations need re-checking at execution time.

It is accurate against *a commit*, not indefinitely. **Before executing any spec, run:**

```bash
git log --oneline 7d92b57b5..origin/main -- <the files that spec touches>
```

If anything comes back, re-verify that spec's citations before editing.

> **`main` on this repo moves fast — assume drift.** Four merges landed during the hours this
> analysis took, and the mechanism above caught every one:
>
> | Merge | Effect on these specs |
> |---|---|
> | **#587** model-cascade hardening | **Invalidated six specs** (D3, D2, D1, D11+R5, R4, R2) — regenerated |
> | **#600** MCP conversation ownership | None — touched only files no spec cites |
> | group conversations | None — one cited file (`ToolExecutionTrace`), comment-only change |
> | **#593** holistic error handling | **Invalidated the same six** — regenerated. And it *implemented part of the backlog*: see below |
>
> **#593 shipped work this doc recommended, which is the mechanism paying off** — re-running the
> check told us EDDI's own team had done part of R4/D1 before we started, so the specs shrank instead
> of recommending infrastructure that now exists:
>
> - **R4 ≈ 70% done by #593.** It extracted a single shared classifier
>   (`configs/shared/RetryConfiguration.isRetryableError`), collapsed `AgentExecutionHelper` to a
>   delegating shim, and added the typed-JDK and `WebApplicationException` tiers. What remains is
>   exactly the langchain4j part R4 is *about*: add the `RetriableException`/`HttpException.statusCode()`
>   tiers and make the still-divergent cascade copy delegate. **Two edits, not a new class.**
> - **D1 — one sub-item done, one new instance added.** #593 made `StreamingLegacyChatExecutor.execute`
>   return a `StreamingResult` record carrying metadata, closing the streaming-token-capture gap. But
>   it also added a **fourth `new AuditEntry(...)` site** with the same zero-cost/null-toolCalls defect.
> - **D11 — streaming half partially invalidated.** The `StreamingLegacyChatExecutor` rewrite changed
>   the failure path; `ObservableChatModel` / `ChatModelRegistry` are untouched, so the decorator work stands.
> - **D2, D3, R2 — untouched in substance**, line numbers moved.
>
> Run the command above rather than trusting this table — it records what was true at `70091de90`.

This is not hypothetical. The first spec pass was written against `d9f7fcb97`; PR #587 landed
mid-analysis and rewrote six of the eighteen cited files (`CascadingModelExecutor` +445/-103,
`ConfidenceEvaluator` +278/-63, `LlmConfiguration` +242, `LlmTask` +90/-51, `AgentOrchestrator`
+79/-10, `StreamingLegacyChatExecutor` +39/-2). Those six specs (D3, D2, D1, D11+R5, R4, R2) were
regenerated against post-#587 source. **D5, R0, R3a and R1 were not affected and retain their
original verification.**

What #587 changed for the affected items:

| Item | Effect of #587 |
|---|---|
| **R4** | Premise survives, **scope shrinks**. #587 rewrote the cascade classifier as a regex (`CascadingModelExecutor:734`) and **added a typed-JDK tier**, closing one of the two divergences. It also **widened the blast radius**: `errorType` now tags two Micrometer metrics (`:404-405`), so misclassification corrupts dashboards, not just a trace string. Retry path no longer goes through `AgentExecutionHelper` directly. Two new tests pin the defective classifier. |
| **D1** | #587 added cascade token surfacing (`responseMetadata`, `audit:cascade_token_usage`) — check whether a real token signal now exists that makes this materially cheaper. |
| **D3** | #587 added typed SSE events + a `sendJsonEvent` helper — likely a better landing place for the fix than hand-rolled JSON. |
| **D2** | #587 added token accumulation into `ExecutionResult.responseMetadata`, which may change the cost story. |
| **D11+R5** | #587 added live-stream cascade paths and a "live-stream timeout" fix — verify how much of the streaming-timeout hole is now closed. |
| **R2** | System-message assembly and the tool loop both moved; cache-prefix reasoning re-derived. |

**Correction to an earlier claim in this document's own history:** `CascadeConfigValidator.java` was
**not** deleted by #587 — it still exists in `modules/llm/impl/`. `CascadingStrategy` and
`EvaluationStrategy` were **moved** to `modules/llm/model/`, not removed. A rename was misread as a
deletion.

**Scope note.** Part II covers P0–P2 only. P3 cleanup and P4 investigations remain at decision level
in Part I and need a scoping pass of their own before execution — do not treat them as ready.

---

## D5 — `ToolCacheService` cache is global, not tenant-scoped

---

##### 0. Corrections to the decision doc / task brief

| Claim | Reality (verified) |
|---|---|
| "tenant-scoped" framing | There is no usable tenant identity. `TenantQuotaService.getDefaultTenantId()` (`src/main/java/ai/labs/eddi/engine/tenancy/TenantQuotaService.java:76`) is a single-tenant stub; `AgentOrchestrator.java:1052` is its only caller. **Tenant is not a viable scoping key.** The available identity axes are `memory.getUserId()`, `memory.getAgentId()`, `memory.getConversationId()` (`IConversationMemory.java:26,28,32`). |
| `docs/security.md:241` "Key: SHA-256 hash of `toolName + arguments`" | False. `buildKey` (`ToolCacheService.java:251-256`) returns plaintext `toolName + ":" + arguments` for args ≤ 2048 chars; SHA-256 only above that threshold. |
| `docs/security.md:243` "Identical tool calls **within the same conversation** return cached results" | False — this is the defect. Cache is a single `@ApplicationScoped` Caffeine instance named `tool-results` (`ToolCacheService.java:35,103`), keyed only by tool+args. Any user's result is served to any other user, any agent, any conversation, process-wide. |
| Smart-TTL table is live behavior | **Also false, and pre-existing.** `CacheFactory.getCache(String)` builds a size-only Caffeine (`CacheFactory.java:26-32`) and `CacheImpl.put(k,v,ttl,unit)` **discards the TTL** (`CacheImpl.java:47-50`). The class javadoc (`CacheImpl.java:22-23,43`) claims `ToolCacheService` tracks expiry via `CachedResult.expiresAt` — **that field does not exist**; `CachedResult` has only `result` and `cachedAt` (`ToolCacheService.java:78-86`), and `cachedAt` is used solely for a debug log line (`:149`). Entries expire **never**; only `maximumSize` eviction applies, and `tool-results` is absent from `CACHE_SIZES` → **1 000 entries** (`CacheFactory.java:20-23`). Treat as trap, see §6; fix is §7 (out of scope, file as D5b). |
| Smart TTL matches real tool names | It does not. Agent-mode tool names are `@Tool` method names (`AgentOrchestrator.java:711`), e.g. `calculate` (`CalculatorTool.java:35`), `getCurrentWeather` (`WeatherTool.java:48`). `getSmartTTL`'s contains-match (`ToolCacheService.java:194-198`) tests `"calculate".contains("calculator")` → false → DEFAULT. **Consequence for this spec: do NOT ship a built-in "pure tool ⇒ GLOBAL" name-matching table — it would silently misclassify.** GLOBAL must be opt-in per tool via config. |
| `ToolExecutionService.executeTool(Object, Method, ...)` (`:69-139`) is a production path | It is not. Only callers are `executeToolsParallel` (`:235`) — itself uncalled in `src/main` — and tests. Production goes exclusively through `executeToolWrapped` (`:163`), called once at `AgentOrchestrator.java:1075-1076`. |

---

##### 1. Design (decided — do not re-open)

Cache key gains a **scope tag** prefix. Scope is per-tool configurable, `USER` by default, fail-closed.

```
key = scopeTag + "|" + toolName + ":" + (args.length() > 2048 ? sha256(args) : args)

scopeTag:  GLOBAL        -> "g"
           USER          -> "u:" + sha256(userId).substring(0,32)
           CONVERSATION   -> "c:" + conversationId
```

Resolution, per tool call:

```
scope = task.toolCacheScopes.get(toolName)          // per-tool override
     ?? task.defaultToolCacheScope                   // task default
     ?? USER                                         // engine default
```

Fail-closed degradation (in `ToolCacheService.resolveScopeTag`):

| effective scope | userId | conversationId | result |
|---|---|---|---|
| GLOBAL | — | — | `"g"` |
| USER | non-blank | — | `"u:<hash>"` |
| USER | blank | non-blank | `"c:<convId>"` (degrade) |
| USER | blank | blank | `null` → **cache bypassed entirely** (no get, no put) |
| CONVERSATION | — | non-blank | `"c:<convId>"` |
| CONVERSATION | — | blank | `null` → **bypassed** |

`userId` is hashed because it may be an email/OIDC subject; `conversationId` is already an opaque id and stays plaintext.

**Old `get`/`put`/`invalidate` signatures are DELETED, not overloaded** — an overload would let a future caller silently reintroduce the global key.

---

##### 2. Files to touch

##### MODIFY — `src/main/java/ai/labs/eddi/modules/llm/tools/ToolCacheService.java`

| Where | Change |
|---|---|
| after `DEFAULT_TTL_SECONDS` (`:55`) | add `public enum CacheScope { GLOBAL, USER, CONVERSATION }` — see note below on placement |
| `get` (`:120`) | signature → `public String get(String scopeTag, String toolName, String arguments)`. First line: `if (scopeTag == null) { return null; }` (before `cacheGetTimer.record`, so an unscopable call is not counted as a miss). |
| `put` (`:158`) | `public void put(String scopeTag, String toolName, String arguments, String result)` |
| `put` TTL variant (`:166`) | `public void put(String scopeTag, String toolName, String arguments, String result, long ttl, TimeUnit unit)`. First line: `if (scopeTag == null) { meterRegistry.counter("eddi.tool.cache.skipped.unscoped", "tool", toolName).increment(); return; }` |
| `invalidate` (`:210`) | `public void invalidate(String scopeTag, String toolName, String arguments)`; no-op when `scopeTag == null` |
| `buildKey` (`:251`) | `private String buildKey(String scopeTag, String toolName, String arguments)` → `return scopeTag + "|" + toolName + ":" + (arguments.length() > 2048 ? sha256(arguments) : arguments);` |
| new, place directly **above** `buildKey` | `public static String resolveScopeTag(CacheScope scope, String userId, String conversationId)` — implements the §1 table. Static + pure ⇒ trivially thread-safe and unit-testable without CDI. |
| `getSmartTTL` (`:185`), `clear` (`:221`), `getStats` (`:232`), `getToolStats` (`:242`), `getConfiguredTTL` (`:275`), `CacheStats` (`:282`), `CachedResult` (`:78`) | **unchanged** |

`CacheScope` placement: put it as a nested `public enum` in `ToolCacheService` (referenced as `ToolCacheService.CacheScope`) **and** have `LlmConfiguration.Task` use that same type — avoids a duplicate enum. Import it by simple name in `LlmConfiguration` (AGENTS.md §4.7 Imports).

##### MODIFY — `src/main/java/ai/labs/eddi/modules/llm/tools/ToolExecutionService.java`

| Where | Change |
|---|---|
| `executeToolWrapped` (`:163-164`) | new signature: `public String executeToolWrapped(String toolName, String arguments, String conversationId, String cacheScopeTag, Supplier<String> toolExecution, boolean enableRateLimiting, boolean enableCaching, boolean enableCostTracking, int rateLimit)` — `cacheScopeTag` inserted after `conversationId`. Update the javadoc `@param` block (`:145-161`). |
| `:178` | `String cachedResult = cacheService.get(cacheScopeTag, toolName, arguments);` |
| `:191` | `cacheService.put(cacheScopeTag, toolName, arguments, result);` |
| `executeTool` (`:69`) | add `String cacheScopeTag` param after `conversationId`; `:88` → `cacheService.get(cacheScopeTag, toolName, arguments)`; `:111` → `cacheService.put(cacheScopeTag, ...)`. Non-production path (§0) — threading the param through keeps it fail-closed rather than accidentally global. |
| `executeToolsParallel` (`:219`), `executeToolsParallelAndWait` (`:247`) | add `String cacheScopeTag` param after `conversationId`, forward to `executeTool` at `:235`. |

##### MODIFY — `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java`

In `Task` (class opens at `:64`), immediately after `toolRateLimits` (`:252`):

```java
/** Default cache scope for tool results when no per-tool override applies. */
private ToolCacheService.CacheScope defaultToolCacheScope;

/** Per-tool cache-scope overrides, keyed by the LLM-facing tool name. */
private Map<String, ToolCacheService.CacheScope> toolCacheScopes;
```

Getters/setters in the same style as `getToolRateLimits`, placed adjacent to `getEnableToolCaching`/`setEnableToolCaching` (`:562-568`):

```java
public ToolCacheService.CacheScope getDefaultToolCacheScope() { ... }
public void setDefaultToolCacheScope(ToolCacheService.CacheScope defaultToolCacheScope) { ... }
public Map<String, ToolCacheService.CacheScope> getToolCacheScopes() { ... }
public void setToolCacheScopes(Map<String, ToolCacheService.CacheScope> toolCacheScopes) { ... }
```

Both fields default to `null` (NOT to an enum constant) — `null` means "engine default `USER`", so a `Task` constructed outside Jackson behaves identically.

##### MODIFY — `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java`

| Where | Change |
|---|---|
| after `:361` (live path) and after `:844` (HITL-resume path) | `Map<String, ToolCacheService.CacheScope> toolCacheScopes = task.getToolCacheScopes();` and `ToolCacheService.CacheScope defaultCacheScope = task.getDefaultToolCacheScope() != null ? task.getDefaultToolCacheScope() : ToolCacheService.CacheScope.USER;` — plus `String userId = memory.getUserId();` (live path already has `conversationId` at `:849`; the resume path has `conversationId` in scope). |
| `executeSingleToolCall` (`:986-991`) and `executeSingleToolCallResult` (`:1010-1016`) | add two params after `String conversationId`: `String userId, Map<String, ToolCacheScope> toolCacheScopes, ToolCacheService.CacheScope defaultCacheScope`. Both are already package-private/`void`+`String` — keep visibility. Forward from `executeSingleToolCall` → `executeSingleToolCallResult` (`:993-995`). |
| inside `executeSingleToolCallResult`, immediately above the `executeToolWrapped` call (`:1075`) | ```java\nToolCacheService.CacheScope scope = toolCacheScopes != null && toolCacheScopes.get(toolRequest.name()) != null\n        ? toolCacheScopes.get(toolRequest.name())\n        : defaultCacheScope;\nString cacheScopeTag = ToolCacheService.resolveScopeTag(scope, userId, conversationId);\n``` and pass `cacheScopeTag` as the new 4th arg. |
| call sites `:399`, `:919`, `:957`, `:994` | update to the new arg lists. |

Import `ToolCacheService` by simple name.

##### MODIFY — `src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java`

`CACHE_SIZES` (`:20-23`): add `"tool-results", 10_000L`. Scoping partitions the keyspace by user; at the current default of 1 000 entries a scoped cache would thrash to a ~0% hit rate. `Map.of` is capped at 10 pairs — this is the 6th, fine.

##### MODIFY — `src/main/java/ai/labs/eddi/engine/caching/CacheImpl.java`

Javadoc `:15-24` and the inline comment `:39-45` reference a non-existent `CachedResult.expiresAt`. Replace with a truthful statement: TTL args are **discarded**; `tool-results` entries are evicted by `maximumSize` only. Comment-only change; no behavior.

##### MODIFY — `docs/security.md` (§ "Smart Caching", `:239-243`)

Replace lines 241–243 with:

- **Key:** `scope | toolName : arguments` — arguments over 2 048 characters are replaced by their SHA-256 digest.
- **Scope:** `USER` by default — a cached result is never served to a different authenticated user. Override per task with `defaultToolCacheScope`, or per tool with `toolCacheScopes` (`GLOBAL` / `USER` / `CONVERSATION`). `GLOBAL` is only safe for tools whose result is a pure function of their arguments.
- **Configuration:** `enableToolCaching` (default `true`), `defaultToolCacheScope` (default `USER`), `toolCacheScopes` (default empty).
- **Lifetime:** entries are evicted by cache size (10 000), **not** by the per-tool TTL table — the TTL values reported by `GET /llm/tools/cache/ttl/{toolName}` are advisory only. *(Remove this bullet when D5b lands.)*

##### MODIFY — `docs/langchain.md`

Config table `:441` and feature table `:844`: add `defaultToolCacheScope` / `toolCacheScopes` rows; correct `:844` "Deduplicates identical tool calls by arguments hash" → "…by scope + tool + arguments". Add the two fields to the JSON example at `:859`.

> The former `docs/agent-father-langchain-tools-guide.md` also carried a copy of
> this config table and was named here as a MODIFY target. It was deleted along
> with the rest of the Agent Father; `docs/langchain.md` is now the only place
> the table lives.

##### MODIFY — `docs/changelog.md`

New top entry (AGENTS.md §2 rule 8), same branch, same commit as the code.

##### NOT MODIFIED

`src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java` — its `getExtensionDescriptor()` declares exactly one `ConfigValue` (`"Resource URI"`, `:930`). Task-level LLM fields are not surfaced through the descriptor, so **no extension-descriptor entry is needed**.

---

##### 3. Config surface

| JSON field | POJO | Type | Default | Validation |
|---|---|---|---|---|
| `tasks[].defaultToolCacheScope` | `LlmConfiguration.Task` (`LlmConfiguration.java`, class at `:64`) | `ToolCacheService.CacheScope` (`GLOBAL`\|`USER`\|`CONVERSATION`) | `null` ⇒ engine default `USER` | Jackson enum binding; unknown string ⇒ deserialization error. Add `@JsonProperty` only if a name differs (it does not). |
| `tasks[].toolCacheScopes` | same | `Map<String, CacheScope>` keyed by LLM-facing tool name | `null` ⇒ no overrides | Keys are not validated against the active tool set — an unknown key is inert (mirrors `toolRateLimits`, `:252`). |

Example:

```json
{
  "actions": ["help"],
  "type": "openai",
  "enableBuiltInTools": true,
  "enableToolCaching": true,
  "defaultToolCacheScope": "USER",
  "toolCacheScopes": {
    "calculate": "GLOBAL",
    "convertUnits": "GLOBAL",
    "readUserMemory": "CONVERSATION"
  }
}
```

Manager UI: nothing to render (see §2, `LlmTask` note).

---

##### 4. Stored-config back-compat

| Scenario | Outcome |
|---|---|
| Existing `langchain.json` in MongoDB / ZIP import with neither new field | Deserializes unchanged; both fields `null`; engine applies `USER`. **No config is invalid after this change; no migration.** |
| Field removed? | None removed. (`FAIL_ON_UNKNOWN_PROPERTIES=false` is set at `SerializationCustomizer.java:38` and `TaskListParser.java:35` — noted for completeness, not needed here.) |
| Existing cache entries | The cache is an in-process Caffeine map (`CacheFactory.java:29-31`), never persisted and empty at every JVM start. Old-format keys cannot exist across a deploy. **Key-structure change is safe by construction — no invalidation step required.** |
| **Semantics change on deploy — state this in the release note** | Live agents stop sharing tool results across users. Expected effects: (a) tool-call volume and cost rise for workloads that were (incorrectly) benefiting from cross-user hits; (b) `eddi.tool.cache.hits` drops, `…misses` rises; (c) any agent that *relied* on cross-user reuse (e.g. a shared web-search corpus) must set `toolCacheScopes: {"<tool>": "GLOBAL"}` to restore prior behavior. This is a deliberate, doc'd security fix — **do not add a global kill-switch flag**. |

---

##### 5. Tests

Existing, verified:

| Path | Status |
|---|---|
| `src/test/java/ai/labs/eddi/modules/llm/tools/ToolCacheServiceTest.java` | **BREAKS** — every `get`/`put`/`invalidate` call and every key assertion. Fix listed below. |
| `src/test/java/ai/labs/eddi/modules/llm/tools/ToolExecutionServiceTest.java` | **BREAKS** — ~14 `executeToolWrapped(...)` calls (`:76,92,106,120,134,150,164,178,192,206,223,240,258,275`) + `executeTool(...)` (`:305,322,340,358,374,391,508`). |
| `.../ToolExecutionServiceExtendedTest.java` | **BREAKS** — `:84,98,111,126,140,154,170,184,195,207,216,226,236,254`. |
| `.../ToolExecutionServiceBranchTest.java` | **BREAKS** — `:88,112,141,163,179,194,219`. |
| `src/test/java/ai/labs/eddi/modules/llm/impl/AgentOrchestratorCoverageTest.java` | **BREAKS** — stub `:149` and `verify(...)` `:406,427` need one extra matcher. |
| `.../AgentOrchestratorCoverage2Test.java:160`, `.../AgentOrchestratorResumeToolLoopTest.java:142`, `.../AgentOrchestratorToolPauseTest.java:138` | **BREAK** — stub arity. |
| `src/test/java/ai/labs/eddi/modules/llm/rest/RestToolHistoryTest.java` | Compiles unchanged (`getStats`/`CacheStats` untouched). |

Mechanical fix for all `executeToolWrapped` stubs: insert one `any()`/`anyString()` for `cacheScopeTag` after the `conversationId` matcher. **Do not use `anyString()` for the scope-tag position** in `verify` — `null` is a legal, meaningful value there and `anyString()` does not match `null`, which would make the verification vacuous (see the `extractResourceId`/`anyString` trap). Use `nullable(String.class)` or an exact `eq(...)`.

Vacuous tests to fix as part of this work:

| Test | Problem |
|---|---|
| `ToolCacheServiceTest.CacheKeyTests.shortArgs_readableKey` (`:148-153`) | Byte-identical to `GetPutTests.put_storesInCache` (`:124-129`) — same call, same `verify`. Merge or make it assert the *scope prefix* specifically. |
| `ToolCacheServiceTest.CacheKeyTests.longArgs_sha256Key` (`:157-169`) | Asserts only `startsWith("calculator:") && length < 200` — passes for any truncation, never checks the digest. Replace with an exact-key assertion against an independently computed SHA-256. |
| `ToolCacheServiceTest` TTL block (`:56-105`, `:259-283`) | Asserts `getConfiguredTTL` returns table values while the TTL is discarded downstream (§0). Not caused by this change; add a `@Disabled`-free comment pointing at D5b rather than deleting, and **do not** let these tests read as proof that expiry works. |

New tests — `ToolCacheServiceTest`, new `@Nested class ScopeTests`:

| Method | Assertion |
|---|---|
| `resolveScopeTag_global_ignoresIdentity()` | `resolveScopeTag(GLOBAL, null, null)` equals `"g"`. |
| `resolveScopeTag_user_hashesUserId()` | `resolveScopeTag(USER, "alice@example.com", "c1")` starts with `"u:"`, has length 34, and **does not contain** `"alice"`. |
| `resolveScopeTag_userStable()` | Two calls with the same userId return equal tags. |
| `resolveScopeTag_userBlank_degradesToConversation()` | `resolveScopeTag(USER, "  ", "c1")` equals `"c:c1"`. |
| `resolveScopeTag_noIdentity_returnsNull()` | `resolveScopeTag(USER, null, null)` and `resolveScopeTag(CONVERSATION, "u", null)` both return `null`. |
| `get_nullScopeTag_returnsNullWithoutCacheAccess()` | `service.get(null, "calculate", "2+2")` is `null` **and** `verifyNoInteractions(cache)`. |
| `put_nullScopeTag_doesNotWrite()` | `verify(cache, never()).put(any(), any(), anyLong(), any())`. |
| `buildKey_includesScopePrefix()` | `service.put("u:abc", "calculate", "2+2", "4")` ⇒ `verify(cache).put(eq("u:abc|calculate:2+2"), any(), anyLong(), any())`. |
| `differentUsers_produceDifferentKeys()` | Two `put`s, same tool+args, scope tags from two userIds ⇒ two distinct captured keys. **This is the regression test for D5.** |
| `longArgs_hashedWithinScope()` | args of 3 000 chars ⇒ key equals `"u:abc|calculate:" + sha256(args)` computed in the test. |
| `invalidate_usesScopedKey()` | `verify(cache).remove("c:c1\|calculate:2+2")`. |

New tests — `ToolExecutionServiceTest`, new `@Nested class CacheScopingTests`:

| Method | Assertion |
|---|---|
| `executeToolWrapped_passesScopeTagToCache()` | `verify(cacheService).get("u:abc", "myTool", "arg1")` and `verify(cacheService).put("u:abc", "myTool", "arg1", "result")`. |
| `executeToolWrapped_nullScopeTag_stillExecutesTool()` | With `cacheScopeTag = null` and `enableCaching = true`, the supplier runs and its value is returned (cache bypass must not swallow execution). |

New tests — `AgentOrchestratorCoverageTest` (or a new small class):

| Method | Assertion |
|---|---|
| `toolCacheScope_defaultsToUserScope()` | Task with neither field set ⇒ captured `cacheScopeTag` starts with `"u:"` for a memory whose `getUserId()` returns `"alice"`. |
| `toolCacheScope_perToolOverrideWins()` | `toolCacheScopes = {"calculate": GLOBAL}`, `defaultToolCacheScope = CONVERSATION` ⇒ captured tag for `calculate` is `"g"`. |
| `toolCacheScope_taskDefaultApplies()` | `defaultToolCacheScope = CONVERSATION`, no override ⇒ tag is `"c:" + conversationId`. |

Runnable locally: all of the above are plain Mockito/JUnit — `.\mvnw.cmd test -Dtest=ToolCacheServiceTest`, `-Dtest=ToolExecutionServiceTest`, `-Dtest=AgentOrchestratorCoverageTest`. Filter by **whole class name**, never `Class#method` (`@Nested` methods silently match 0 tests and exit 0). No Docker/loopback dependency.

---

##### 6. Acceptance criteria

1. `.\mvnw.cmd clean compile` succeeds. **`clean` is mandatory** — this changes method signatures across four production classes; an incremental build reuses stale `.class` files and hides breaks in unedited callers.
2. `grep -rn "cacheService.get(\|cacheService.put(" src/main/java` returns exactly 4 hits, and **every one** passes a scope-tag argument as its first parameter.
3. `grep -rn "public String get(String toolName" src/main/java/.../ToolCacheService.java` returns 0 hits (old signature gone, not overloaded).
4. `.\mvnw.cmd test -Dtest=ToolCacheServiceTest` passes, including `differentUsers_produceDifferentKeys`.
5. `.\mvnw.cmd test -Dtest='ToolExecutionService*Test'` passes.
6. `.\mvnw.cmd test -Dtest='AgentOrchestrator*Test'` passes.
7. `.\mvnw.cmd test` is green overall.
8. `.\mvnw.cmd validate` (Checkstyle) passes; no unused imports; no inline FQNs introduced (AGENTS.md §4.7).
9. A stored `langchain.json` containing neither new field loads and runs; `resolveScopeTag` yields a `"u:"` tag for an authenticated turn — verifiable by a debug log or the new orchestrator test.
10. `CacheFactory.CACHE_SIZES` contains `"tool-results" -> 10_000L`.
11. `docs/security.md` no longer contains the strings `SHA-256 hash of` (in the Smart Caching section) or `within the same conversation`.
12. `docs/changelog.md` has a new top entry on the same branch/commit as the code.
13. Manual smoke (optional, needs a running instance): two conversations under different `userId`s issuing the identical tool call both produce `eddi.tool.execution.cached` = 0 on the second user's first call — check `/q/metrics`.

---

##### 7. Traps

1. **Do not build a name-based "pure tool ⇒ GLOBAL" default table.** Agent-mode tool names are `@Tool` method names (`calculate`, `getCurrentWeather`), not class names, and the existing contains-matcher in `getSmartTTL` already fails on them. GLOBAL is opt-in via config only.
2. **Two orchestrator paths, not one.** The live loop (`AgentOrchestrator.java:843-849`) and the HITL-resume path (`:360-367`) both compute the execution flags independently and both funnel into `executeSingleToolCallResult`. Miss the resume path and approved tool calls after a human approval keep using an unscoped/`null` tag — silently bypassing cache rather than leaking, but inconsistent.
3. **`null` scope tag must fail closed, not fall through to a literal.** Do not default to `""` or `"unknown"` — that recreates a shared partition for every anonymous request.
4. **`anyString()` does not match `null`.** Mockito verifications on the scope-tag position must use `nullable(String.class)` or `eq(...)`, or the assertion is vacuous.
5. **Thread safety.** `ToolCacheService` and `ToolExecutionService` are `@ApplicationScoped` singletons. `resolveScopeTag` must be `static` and side-effect free. Do **not** cache the resolved tag in an instance field — the identity is per-conversation state and belongs on the call stack (derived from `IConversationMemory`).
6. **`executeToolsParallel` shares one `ToolExecutionTrace`** across the fixed thread pool (`:30`, `:235`). Not introduced here, but do not add per-call mutable state to `ToolExecutionService` while touching those signatures.
7. **Hit rate will drop and cost will rise on deploy.** Expected. Raise `tool-results` size (criterion 10) before assuming the scoping itself is the cause of a regression report.
8. **TTL is inert (§0).** Do not write a test, a doc sentence, or a commit message that implies scoped entries expire after the per-tool TTL. They expire on size eviction only. File **D5b — `CacheImpl` discards per-entry TTL; `ToolCacheService` smart-TTL table is decorative** as a follow-up (fix: `ICacheFactory.getCache(name, Duration)` exists at `CacheFactory.java:35-48` and does honour `expireAfterWrite`, but is per-TTL-value — a real fix needs a Caffeine `Expiry` or a per-TTL-bucket cache set).
9. `DELETE /llm/tools/cache` (`RestToolHistory.java:~190`) still clears **every** scope for every user. Unchanged behavior, admin-only endpoint — note it, do not redesign it here.
10. `ToolRateLimiter` is likewise keyed by tool name only and is therefore also cross-user. Out of scope for D5 — file separately if not already covered.

---

##### 8. Out of scope

- Fixing TTL enforcement (`CacheImpl` / `CacheFactory` behavior) — comment corrections only; file D5b.
- Rate limiter scoping.
- Scoped cache-invalidation endpoints or a `invalidateUserScope(userId)` hook for GDPR erasure — desirable, but would be dead code today. File as a follow-up.
- Real multi-tenancy (`TenantQuotaService` is a stub); do **not** introduce a `TENANT` enum constant that resolves to a constant.
- `ToolCostTracker`, `maxBudgetPerConversation` (that is D2).
- `enableParallelExecution` / `executeToolsParallel*` behavior beyond the mechanical signature change (deletion is D10).
- Manager UI, extension descriptors.

---

##### 9. Sequencing (each step independently committable, each compiles + tests green)

| # | Step | Files | Commit |
|---|---|---|---|
| 1 | Add `CacheScope` enum + static `resolveScopeTag` + its unit tests. No callers yet. | `ToolCacheService.java`, `ToolCacheServiceTest.java` | `feat(tools): add tool-cache scope resolution` |
| 2 | Change `get`/`put`/`invalidate`/`buildKey` signatures; update + de-vacuum `ToolCacheServiceTest`. | same two files | `fix(tools): scope tool cache keys by identity` |
| 3 | Thread `cacheScopeTag` through `ToolExecutionService` (all 4 public methods); update its 3 test classes. | `ToolExecutionService.java` + 3 tests | `refactor(tools): thread cache scope through ToolExecutionService` |
| 4 | Add the two `Task` config fields + accessors. | `LlmConfiguration.java` | `feat(llm): add defaultToolCacheScope / toolCacheScopes config` |
| 5 | Wire resolution in both `AgentOrchestrator` paths; update the 4 orchestrator test classes; add the 3 new orchestrator tests. | `AgentOrchestrator.java` + 4 tests | `fix(llm): resolve tool-cache scope per tool call` |
| 6 | Raise `tool-results` cache size; correct `CacheImpl` javadoc. | `CacheFactory.java`, `CacheImpl.java` | `chore(caching): size tool-results for scoped keys; fix false TTL javadoc` |
| 7 | Docs + changelog. | `docs/security.md`, `docs/langchain.md`, `docs/changelog.md` | `docs: correct tool-cache scope and key description` |

Effort: **S** (confirms the decision doc). Steps 3 and 5 are the mechanical bulk — ~30 test call sites, all one-argument insertions.

---

I have all facts verified against the current working tree. Producing the replacement spec.

## D3 — `toolTrace` never reaches the SSE stream

---

### 1. Changes since PR #593

**Premise fully intact. The defect is byte-identical to the #587-era spec — #593 never touched the trace writer, the reader, or the `onTaskComplete` handler.** Only line numbers moved. Not fixed, not worsened *by #593 specifically* (the #587 cascade-widening at `LlmTask.java:509` still applies). The old Part II spec was verified against `5268a72bf`; every citation below is re-verified against the current tree.

**What #593 did to the cited files, and whether it touched D3:**

| File (#593 delta) | Touched D3 surface? |
|---|---|
| `LlmTask.java +122` | **No.** Writer expression unchanged: `KEY_LANGCHAIN + ":trace:" + task.getType() + ":" + task.getId()` — now at **`:686`** (`executeTask`) and **`:925`** (`executeResume`), was `:663`/`:804`. `KEY_LANGCHAIN="langchain"` (`:82`). |
| `LifecycleManager.java +110` | **No.** Reader unchanged: `getLatestData("langchain:trace:" + task.getId().name())` — now **`:462`** (was `:414`), inside `buildTaskSummary` (**`:454-472`**). Call site **`:324`** (after `task.execute` `:320`, before `eventSink.onTaskComplete` `:327`, gated `eventSink != null` `:326`). |
| `StreamingLegacyChatExecutor.java +214` | **Irrelevant to D3.** `grep trace\|storeData\|dataFactory` over the file = **0 hits** (re-confirmed). Legacy non-agent path produces no tool trace by construction. |
| `RestAgentEngineStreaming` | **No.** `onTaskComplete` still hand-rolls JSON via `StringBuilder`; dead `if (summary.containsKey("toolTrace"))` block now at **`:78-85`** (was `:78-84`). `sendJsonEvent` helper at **`:165-174`**, records at **`:177`/`:181`**. |

**Prefix-mismatch, re-confirmed on current tree:**

| | Value | Source |
|---|---|---|
| Writer key | `langchain:trace:<Task.getType()>:<Task.getId()>` e.g. `langchain:trace:openai:taskA` | `LlmTask.java:686,925`; `task` is `LlmConfiguration.Task` (`.getType()`=model type `:382`, `.getId()`=config id `:366`) |
| Reader key | `langchain:trace:ai.labs.llm` | `LifecycleManager.java:462`; `task.getId().name()` where `task`=the `LlmTask` singleton, `TASK_ID.name()="ai.labs.llm"` (`LlmTask.java:78,218-219`) |
| **Overlap** | **none — prefixes can never match** | The `toolTrace` field on the `task_complete` SSE frame has never been emitted. |

**Vacuous test still present.** `LifecycleManagerTest.summaryWithToolTrace` (**`:1046-1075`**, stub at **`:1067`** `when(currentStep.getLatestData("langchain:trace:llm"))`, task mock `getId()→new TaskId("llm")` `:1050`, `getType()→"langchain"` `:1051`). Passes because it hand-feeds the reader the exact string the reader computes for `TaskId("llm")` — a name no real task has. Asserts the reader against a stub, never against the writer.

**#593 introduced no new instance of this defect class in the trace path.** It did add two adjacent trace keys — `rag:trace:` (`RagContextProvider.java:173`) and `rag:httpcall:trace:` (`LlmTask.java:345`) — but these are RAG traces with no cross-reader; neither `.startsWith("langchain:trace:")` (both false), so they are safely ignored by both the reader and `RestToolHistory`. Relevant only as prefix-collision guards (see §7 T4).

**#593's SSE rework does not give a cleaner landing spot.** `sendJsonEvent` (from #587, untouched by #593) serializes a *whole* payload; `toolTrace` is one field inside the composite `task_complete` object alongside `taskId`/`taskType`/`durationMs`/`actions`/`confidence`. Adopting it means converting the whole handler to a typed record (§2e, optional/separable). No payload-shape change from #593.

---

### 2. Files to touch

| File | Action |
|---|---|
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\engine\memory\MemoryKeys.java` | MODIFY — add 2 `String` constants after `PROMPT` (`:69`); fix class javadoc (`:14-15`) |
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\engine\lifecycle\internal\LifecycleManager.java` | MODIFY — replace `:461-465`; add `collectToolTrace` helper; add 2 static imports |
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\impl\LlmTask.java` | MODIFY — `:686`, `:925` use the constant; add static import |
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\rest\RestToolHistory.java` | MODIFY — `:72` literal → constant; add static import |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\engine\lifecycle\internal\LifecycleManagerTest.java` | MODIFY — replace vacuous `summaryWithToolTrace` (`:1046-1075`), add 5 cases |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\modules\llm\impl\LlmTaskCoverageTest.java` | MODIFY — tighten `:770` |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\engine\internal\RestAgentEngineStreamingExtendedTest.java` | MODIFY — tighten `onTaskCompleteIncludesToolTrace` (`:116-129`) |

**No CREATE, no DELETE.**

#### 2a. `MemoryKeys.java` — insert after `:69` (`PROMPT`), inside `// ---- Langchain ----`

```java
/**
 * Prefix for LLM tool-execution trace keys. Full key shape:
 * {@code langchain:trace:<modelType>:<configTaskId>}. Written by LlmTask
 * (executeTask / executeResume), read by LifecycleManager (SSE task summary)
 * and RestToolHistory (persisted-snapshot replay).
 */
public static final String LANGCHAIN_TRACE_PREFIX = "langchain:trace:";

/** Task type reported by LlmTask ({@link ILifecycleTask#getType()}). */
public static final String TASK_TYPE_LANGCHAIN = "langchain";
```

Plain `String`, matching the `OUTPUT_PREFIX`/`HTTP_CALLS_PREFIX`/`CONTEXT_PREFIX` convention for dynamic-suffix keys. `import java.util.List` already at `:7`. Also fix the now-wrong class javadoc at **`:14-15`** (`Keys that are task-internal (e.g., "langchain:trace") can remain as local … constants`) — `langchain:trace` has three consumers across two packages; not task-internal.

#### 2b. `LifecycleManager.java` — replace `:461-465`

Current:
```java
// Tool execution trace (for LLM tasks) — enables live tool call display in UI
IData<?> traceData = conversationMemory.getCurrentStep().getLatestData("langchain:trace:" + task.getId().name());
if (traceData != null && traceData.getResult() != null) {
    summary.put("toolTrace", traceData.getResult());
}
```
Replacement:
```java
// Tool execution trace (LLM tasks only) — enables live tool call display in UI.
// One LlmTask execution iterates llmConfig.tasks() and writes one
// "langchain:trace:<modelType>:<configTaskId>" key PER config task; aggregate
// them in write order. getLatestData() cannot be used: it reverses and returns
// only the LAST prefix match (ConversationStep.java:167-179), and an un-gated
// prefix scan would leak the LLM trace into every subsequent task's summary in
// the same step.
if (TASK_TYPE_LANGCHAIN.equals(task.getType())) {
    List<Object> toolTrace = collectToolTrace(conversationMemory.getCurrentStep());
    if (!toolTrace.isEmpty()) {
        summary.put("toolTrace", toolTrace);
    }
}
```
New private helper, placed between `buildTaskSummary` (ends `:472`) and `buildAuditEntry` (`:478`):
```java
/**
 * Aggregates every {@code langchain:trace:*} entry in the current step, in write
 * order. Caller must gate on task type — this method does not.
 */
private List<Object> collectToolTrace(IConversationMemory.IConversationStep currentStep) {
    List<Object> aggregated = new ArrayList<>();
    for (IData<?> element : currentStep.getAllElements()) {
        if (element != null && element.getKey() != null
                && element.getKey().startsWith(LANGCHAIN_TRACE_PREFIX)
                && element.getResult() instanceof List<?> entries) {
            aggregated.addAll(entries);
        }
    }
    return aggregated;
}
```
Imports: add `import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;` and `…TASK_TYPE_LANGCHAIN;` next to the existing `import static …MemoryKeys.ACTIONS;` (**`:37`**). `ArrayList`/`List` via `java.util.*` (`:34`); `IData` (`:19`), `IConversationMemory` (`:18`) already imported. No inline FQNs (AGENTS.md §4.7).

Verified: `getAllElements()` declared on `IConversationMemory.IConversationStep` (`IConversationMemory.java:271`); impl = `new ArrayList<>(store.values())` over a `LinkedHashMap` (`ConversationStep.java:18,24,162-164`) ⇒ **insertion-ordered defensive copy, no reversal needed**. `getCurrentStep()` returns `IWritableConversationStep` (extends `IConversationStep`), so passing it to the helper compiles.

#### 2c. `LlmTask.java` — `:686` and `:925`, expression only

| Line | Method | Before | After |
|---|---|---|---|
| 686 | `executeTask` | `KEY_LANGCHAIN + ":trace:" + task.getType() + ":" + task.getId()` | `LANGCHAIN_TRACE_PREFIX + task.getType() + ":" + task.getId()` |
| 925 | `executeResume` | same | same |

Add `import static ai.labs.eddi.engine.memory.MemoryKeys.LANGCHAIN_TRACE_PREFIX;`. Produced strings **byte-identical** — de-dup only. **Do NOT touch** `:551` (`langchain:cascade:trace:`) or `:648`/`:896` (`langchain:<type>:<id>` response key).

#### 2d. `RestToolHistory.java:72`

`data.getKey().startsWith("langchain:trace:")` → `data.getKey().startsWith(LANGCHAIN_TRACE_PREFIX)`; add the static import.

#### 2e. *(Optional, separable — commit 4)* typed `task_complete` payload

`RestAgentEngineStreaming.onTaskComplete` (**`:70-91`**) is the last hand-rolled `StringBuilder` JSON on this class. Converting to `sendJsonEvent(eventSink, sse, "task_complete", new TaskCompleteEvent(...))` with
```java
@JsonInclude(JsonInclude.Include.NON_NULL)
private record TaskCompleteEvent(String taskId, String taskType, long durationMs,
                                 Object actions, Object toolTrace, Double confidence) {}
```
deletes the `toJsonArray` call, the `try/catch` at `:79-84`, and the swallow-at-DEBUG. **Changes the failure mode** of `toolTraceSerializationFailure` (`RestAgentEngineStreamingExtendedTest:251-268`): under `sendJsonEvent` the whole event degrades to `{}` instead of the event minus `toolTrace`. If taken, update that test's expectation explicitly. Ship after the fix is green.

---

### 3. Config surface

**None.** No JSON field, POJO, `ExtensionDescriptor`, or Manager-UI field. Trace is emitted whenever an LLM task in a **streaming** turn produced tool calls. Non-streaming `say` has `eventSink == null` (`:326`); the summary then feeds only `buildAuditEntry` (`:333`), which reads `"actions"` (`:515`) and nothing else — byte-identical audit output. A kill-switch/cap would each need a config field (Golden Rule 1) — **out of scope** (§8).

---

### 4. Stored-config back-compat

| Concern | Outcome |
|---|---|
| Stored agent/LLM JSON in MongoDB | **Untouched.** No config schema participates. |
| ZIP import (`AbstractBackupService`) | **Untouched.** |
| Persisted `ConversationMemorySnapshot` docs | **Untouched.** Memory-key strings byte-identical; `RestToolHistory` keeps reading historical snapshots (`:72` scans same prefix). |
| Live behavior delta | `POST …/sayStreaming` against an agent whose LLM task runs tools (incl. cascade): the `task_complete` SSE frame gains a `toolTrace` field previously **always** absent. Additive; no existing field changes. Strict unknown-field-rejecting clients would break — [UNVERIFIED — implementer must check eddi-chat-ui / EDDI-Manager SSE parsers]. |
| **Security delta** | **Tool arguments and tool results leave the process on an unredacted channel for the first time.** Previously reachable only via owner-scoped `RestToolHistory` and the audit ledger (`AuditLedgerService.scrubSecrets`). Nothing on the summary path redacts. Correct fix location is the producer (`AgentOrchestrator` `tool_call`/`tool_result` maps), per D12 — **out of scope here, but MUST be called out in the PR description.** |

---

### 5. Tests

**Existing coverage (verified in current tree):**

| Test | Line | Status |
|---|---|---|
| `LifecycleManagerTest.summaryWithToolTrace` | `:1046-1075` | **VACUOUS — rewrite.** Stubs `getLatestData("langchain:trace:llm")`; new reader never consults it. Unstubbed `getAllElements()` → Mockito empty `List` ⇒ **fails correctly** until rewritten. |
| `LifecycleManagerTest.summaryWithConfidence` | `:1077+` | Unaffected (`audit:confidence` via `getLatestData`). |
| `LifecycleManagerTest.summaryWithActions` | `:~1042` | Unaffected. |
| `RestAgentEngineStreamingExtendedTest.onTaskCompleteIncludesToolTrace` | `:116-129` | Passes (feeds handler directly). Weak: `assertTrue(data.contains("toolTrace"))` `:128`. Tighten. |
| `RestAgentEngineStreamingExtendedTest.toolTraceSerializationFailure` | `:251-268` | Keep as-is unless §2e taken. |
| `LlmTaskCoverageTest.resume_nonEmptyTrace_stored` | `:757-771` | Passes; `startsWith("langchain:trace:")` `:770` can't catch a prefix regression. Tighten. `task("taskA",…)` sets `type="openai"`, `id="taskA"` (`:153-156`) ⇒ exact key `langchain:trace:openai:taskA`. |
| `LlmTaskCoverage2Test` cascade trace | `:536-537` | Unaffected — asserts `langchain:cascade:trace:`. |
| `RestToolHistoryTest` | `:110,:166,:451` | Unaffected — literal `langchain:trace:step1` still matches the constant. |

**New / rewritten (all plain Mockito/JUnit — no Docker, no sockets):**

| Class | Method | Assertion |
|---|---|---|
| `LifecycleManagerTest` | `summaryIncludesToolTraceForLangchainTask()` *(replaces `summaryWithToolTrace`)* | Task mock `getId()→new TaskId("ai.labs.llm")`, `getType()→"langchain"`. Step mock `getAllElements()→List.of(data("langchain:trace:openai:t1", List.of(Map.of("type","tool_call","tool","weather"))))`. Assert `summary.get("toolTrace")` equals that 1-element list. **Stub `getAllElements`, never `getLatestData`.** |
| `LifecycleManagerTest` | `summaryAggregatesMultipleToolTraceKeys()` | Two elements `langchain:trace:openai:t1`, `langchain:trace:anthropic:t2`, one entry each → `toolTrace.size()==2`, order preserved. Guards the "only last match" regression `getLatestData` would reintroduce. |
| `LifecycleManagerTest` | `summaryOmitsToolTraceForNonLangchainTask()` | Same trace element present, task `getType()→"behavior_rules"` → `assertFalse(summary.containsKey("toolTrace"))`. Guards cross-task leakage. |
| `LifecycleManagerTest` | `summaryOmitsToolTraceWhenNoTraceKeys()` | `getAllElements()→` only `input`/`actions` → no `toolTrace`. |
| `LifecycleManagerTest` | `summaryIgnoresCascadeTraceKey()` | Only `langchain:cascade:trace:t1` present → no `toolTrace`. Locks non-collision against a future looser prefix. |
| `LifecycleManagerTest` | `summaryIgnoresNonListTraceResult()` | Element `langchain:trace:openai:t1` whose `getResult()` is a `String` → no `toolTrace`, no `ClassCastException`. Covers the `instanceof List<?>` guard. |
| `LlmTaskCoverageTest` | tighten `:770` | `verify(dataFactory).createData(eq("langchain:trace:openai:taskA"), eq(trace))` — exact equality. Writer half of the contract whose reader half `LifecycleManagerTest` now asserts. |
| `RestAgentEngineStreamingExtendedTest` | tighten `onTaskCompleteIncludesToolTrace` | Feed `Map.of("toolTrace", List.of(Map.of("type","tool_call","tool","weather","arguments","{}"), Map.of("type","tool_result","tool","weather","result","sunny")))`; `MAPPER.readTree(capturedData)`; assert `node.get("toolTrace").isArray()`, `size()==2`, `get(0).get("type").asText().equals("tool_call")`. |

> Mockito default for a `List`-returning method is an empty list, so unstubbed `getAllElements()` in `summaryWithConfidence`/`summaryWithActions` yields no `toolTrace` — they stay green without edits.

---

### 6. Acceptance criteria

1. `grep -rn '"langchain:trace:"' src/main` returns **only** `MemoryKeys.java` (the constant).
2. `grep -n 'getLatestData("langchain:trace' src/main/.../LifecycleManager.java` returns **no hits**.
3. `grep -n 'langchain:cascade:trace' src/main/.../LlmTask.java` still returns `:551` unchanged.
4. `.\mvnw.cmd clean compile` succeeds (clean, not incremental — stale `.class` hides breaks).
5. `.\mvnw.cmd test -Dtest=LifecycleManagerTest` green, includes the six §5 methods. **Filter by class, never `Class#method`** (nested-class method filters silently run 0 tests, exit 0).
6. `.\mvnw.cmd test -Dtest=LlmTaskCoverageTest` green with exact-key `verify`.
7. `.\mvnw.cmd test -Dtest=RestAgentEngineStreamingExtendedTest` green with the parsed-JSON assertion.
8. `.\mvnw.cmd test -Dtest=RestToolHistoryTest,LlmTaskCoverage2Test,LifecycleManagerStreamingTest` green, unmodified.
9. `.\mvnw.cmd validate` (Checkstyle) passes; no unused imports.
10. Mutation check: revert gate to `true` → `summaryOmitsToolTraceForNonLangchainTask` fails. Revert `collectToolTrace` to `getLatestData(LANGCHAIN_TRACE_PREFIX)` → `summaryAggregatesMultipleToolTraceKeys` fails.
11. E2E: a `sayStreaming` turn in agent mode with ≥1 tool emits a `task_complete` frame whose JSON has a `toolTrace` array with ≥1 `"type":"tool_call"` and ≥1 `"type":"tool_result"`. [UNVERIFIED — needs running instance + LLM provider.]
12. Same turn: `task_complete` frames for parser/behavior/output tasks carry **no** `toolTrace`.
13. `docs/changelog.md` entry in the same commit (AGENTS.md §2 rule 8), noting the §4 SSE security delta.

---

### 7. Traps

1. **Do not "fix" the writer key.** `RestToolHistory.java:72` and every persisted snapshot depend on `langchain:trace:` + arbitrary suffix. **Reader-side fix only.**
2. **`getLatestData` is the wrong tool.** `startsWith` + reverse + first-hit = only the **newest** match (`ConversationStep.java:167-179`). `LlmTask` writes one trace key per config task; `getLatestData(LANGCHAIN_TRACE_PREFIX)` silently drops all but the last.
3. **Cross-task leakage.** Step data survives across tasks within a step. A bare prefix scan without the `TASK_TYPE_LANGCHAIN` gate makes every task after the LLM task report the LLM's trace. Gate is load-bearing (`summaryOmitsToolTraceForNonLangchainTask` locks it).
4. **Non-`langchain:trace:` keys must not be swept.** `langchain:cascade:trace:` (`LlmTask.java:551`), `rag:trace:` (`RagContextProvider.java:173`), `rag:httpcall:trace:` (`LlmTask.java:345`) all `.startsWith("langchain:trace:") == false` — safe today; any move to a `"langchain:"`-rooted prefix breaks them. `summaryIgnoresCascadeTraceKey` locks the cascade one.
5. **Ordering.** `buildTaskSummary` at `:324`, after `task.execute` (`:320`), before `onTaskComplete` (`:327`). Do not move — the trace does not exist before `execute` returns.
6. **Cascade assigns, not appends.** `LlmTask.java:509` is `toolTrace = cascadeResult.agentResult().trace()` — a replacement; only the winning step's trace ships. Do not "fix" here.
7. **Thread safety.** `LifecycleManager` is `@ApplicationScoped`; `collectToolTrace` allocates its own list, holds no state. The reaching `List` is read-only from this point.
8. **`buildAuditEntry` also receives `summary`** (`:333`) but reads only `"actions"` (`:515`) and passes `null` for `toolCalls` (`:522`). Adding `toolTrace` changes no audit output. Do **not** opportunistically wire it into `AuditEntry.toolCalls` — separate change, separate redaction question.
9. **Redaction.** See §4 — surface in the PR description; do not fix here.
10. **Payload size.** One `tool_call` + one `tool_result` per call per iteration up to `maxToolIterations`; `tool_result` carries truncated output. `task_complete` can get large. No cap; adding one needs a config field (Golden Rule 1). Note it.
11. **Serialization.** Trace entries are `Map<String,Object>` of `String`+`boolean` — trivially serializable. No custom serializer.
12. **Field order not guaranteed** (`HashMap` producers). Consumers key by name; assertions must not depend on JSON key order.

---

### 8. Out of scope

- Writer key format; `langchain:cascade:trace:` and its `docs/model-cascade.md` contract.
- Per-cascade-step trace attribution (only the winner propagates to `LlmTask`).
- Redaction of tool arguments/results (D12 — producer-side).
- Any size cap / truncation of the SSE `toolTrace`, and any enable/disable config field.
- Wiring `toolTrace` into `AuditEntry.toolCalls`.
- `rag:trace:` / `rag:httpcall:trace:` plumbing (adjacent, no reader mismatch).
- `ToolExecutionTrace` / `ToolCall` model, `processStepTrace`, `RestToolHistory` semantics.
- Manager-UI / chat-UI consumption of the new field.

---

### 9. Sequencing

**XS — ~1.5 h with tests** (2 h with §2e). Four independently committable steps; branch from `origin/main` (AGENTS.md §2 rule 3); **do not push without explicit approval.**

| # | Commit | Content | Gate |
|---|---|---|---|
| 1 | `refactor(memory): extract langchain trace key prefix constant` | `MemoryKeys.java` (+2 constants, javadoc fix `:14-15`); `LlmTask.java:686,925` + `RestToolHistory.java:72` switched; `LlmTaskCoverageTest` exact-key. Zero behavior change. | `.\mvnw.cmd clean compile && .\mvnw.cmd test -Dtest=LlmTaskCoverageTest,RestToolHistoryTest` |
| 2 | `fix(streaming): toolTrace now reaches the SSE task_complete event` | `LifecycleManager` `:461-465` + `collectToolTrace`; six `LifecycleManagerTest` methods replacing the vacuous one; changelog incl. security delta. | `.\mvnw.cmd test -Dtest=LifecycleManagerTest` |
| 3 | `test(streaming): assert task_complete toolTrace payload shape` | `RestAgentEngineStreamingExtendedTest.onTaskCompleteIncludesToolTrace` parsed-JSON assertion. | `.\mvnw.cmd test -Dtest=RestAgentEngineStreamingExtendedTest` |
| 4 | *(optional)* `refactor(streaming): typed task_complete payload via sendJsonEvent` | §2e — `TaskCompleteEvent` record, drop the `StringBuilder`; adjust `toolTraceSerializationFailure`. | `.\mvnw.cmd test -Dtest=RestAgentEngineStreamingExtendedTest && .\mvnw.cmd validate` |

---

### 10. Resulting SSE payload shape

`event: task_complete`
```json
{
  "taskId": "eddi://ai.labs.llm",
  "taskType": "langchain",
  "durationMs": 1840,
  "actions": ["answer_question"],
  "toolTrace": [
    {"type": "tool_call",   "tool": "searchWeb", "arguments": "{\"query\":\"vienna weather\"}"},
    {"type": "tool_result", "tool": "searchWeb", "result": "18C, clear"}
  ],
  "confidence": 0.95
}
```
Field order within objects is not guaranteed (`HashMap` producers). `type` values are produced by `AgentOrchestrator` (`tool_call`/`tool_result`/`tool_error`/`hitl_*`).

All facts verified against the current working tree. Producing the replacement spec.

## D2 — `maxBudgetPerConversation` bounds nothing; the same key mismatch voids `toolRateLimits`

### 1. Changes since PR #593

**Premise HOLDS in full. PR #593 is a no-op for every engine surface this item touches.** Confirmed by direct source read on the current tree (HEAD, `70091de90` is an ancestor):

| Claim | Verification |
|---|---|
| #593 did **not** touch `AgentOrchestrator.java` | `git show --stat 70091de90` lists it only under `test/` (`AgentOrchestratorExtendedTest`, `AgentOrchestratorToolPauseTest`). Main source absent. |
| #593 did **not** touch `ToolCostTracker` / `ToolCacheService` / `ToolExecutionService` / `ToolRateLimiter` | Absent from the commit's file list entirely. |
| `ToolCostTracker.TOOL_COSTS` still keyed on config slugs, looked up by exact `getOrDefault` on the `@Tool` **method** name | `:35-43` (table verbatim = spec's `DEFAULT_TOOL_PRICES`), `:123` `getOrDefault(toolName, 0.0)`, live path passes `toolRequest.name()` at `AgentOrchestrator:1144`. Every built-in still prices `$0.00`; `maxBudgetPerConversation` still bounds nothing; `toolRateLimits` slug form still inert; `getSmartTTL` substring-matches (`:195`) so method names like `searchWeb`/`calculate` still fall through to flat `DEFAULT_TTL_SECONDS=300L` (`:55`). |

**The post-#587 HOW is correct against the current tree**, with the drift below. The two files #593 *did* change (`LlmConfiguration.java` +170/-43, `LlmTask.java` +123) shifted line numbers only — no structural conflict with this item.

#### 1.1 What #593 changed that this item must account for

| # | Change | Effect on D2 |
|---|---|---|
| A | **`RetryConfiguration` extracted out of `LlmConfiguration` into `ai.labs.eddi.configs.shared.RetryConfiguration`** (new file, 215 lines). `LlmConfiguration.Task.retry` is now typed `RetryConfiguration` (`:233`, imported `:9`); getter/setter `:558-563`. | **Orthogonal. Does NOT affect the `ToolNameResolver` plan or any pricing/rate-limit logic.** `retry` sits immediately *above* the `// === Budget & Cost Control ===` block where `enforceBudget`/`toolPricing` get added. The extraction freed lines but introduced no naming/classifier that D2 interacts with. No cross-dependency. |
| B | `LlmConfiguration.Task` field/getter positions shifted (retry extraction + cascade fields). | Re-cited in §2.6 below. Class opens at **`:65`** (was `:64`); budget block **`:235-238`** (was `:232-238`); getters **`:566-611`** (was `:546-552`). |
| C | `LlmTask.java` grew ~+120 lines. | Re-cited: `new AgentOrchestrator(...)` **`:195`** (was `:194`); `@ConfigProperty("eddi.hitl.tool.enabled")` **`:149`** (was `:148`); `getExtensionDescriptor()` **`:1086-1090`**, still only `uri` (`FieldType.URI`, `:1090`) — **no Manager change**. |
| D | `AgentOrchestratorToolPauseTest` gained a comment (+3). | `executeToolWrapped` stub now at **`:139`** (was `:138`). |
| E | #593 introduced **no new instance of this defect class.** `RetryConfiguration.isRetryableError` and `CascadingModelExecutor`'s separate classifier match on exception type/substring, not on tool names — no slug-vs-method mismatch possible. | No cleanup added to D2 scope. |

**#593 did NOT implement any part of D2.** The item is unchanged in size (S–M) and shape.

#### 1.2 Stale citations corrected (re-verified on current tree)

| Old spec (§/line) | Current tree |
|---|---|
| §2.6 budget block `:232-238` | **`:235-238`**; `maxBudgetPerConversation` field **`:238`**, getter/setter **`:566-572`** |
| §2.6 getters "beside `:546-552`" | insert after `setMaxBudgetPerConversation` (**`:572`**) or the `toolRateLimits` accessors (**`:606-612`**) |
| Part I line 677 "`Task` opens `:64` … `toolRateLimits` `:252`" | **`:65`** / field **`:255`**, accessors **`:606-611`** |
| §2.5(h) / trap 10 `LlmTask.java:194`, `:148` | `new AgentOrchestrator` **`:195`**; `@ConfigProperty` **`:149`** |
| §3 / §8 `getExtensionDescriptor ~:965-973` | **`:1086-1090`** (still `uri`-only) |
| §5.2 `AgentOrchestratorToolPauseTest:138` stub | **`:139`** |
| §1.2 rate-limit test `:413` | method `toolCall_perToolRateLimitOverride_usesConfiguredLimit` at **`:411`**, `verify` **`:427`** |

**Everything else in the post-#587 spec verified EXACT** (no drift): `AgentOrchestrator` — `ExecutionResult:221`, `ToolSetup:673-674`, `buildToolSetup:684`, proxy-unwrap `:713-716`, `toolAnnotation.name():725`, `toolSources.put:727`, `return new ToolSetup:756`, `runToolCallLoop:852`, call sites `:407/:950/:994`, `executeSingleToolCall:1054-1060`, `executeSingleToolCallResult:1079-1085`, budget gate `:1104-1117`, rate-limit+dispatch `:1137-1148` (lookup `:1140-1142`, `executeToolWrapped` call `:1144`), `sourceForBuiltInTool:1172-1184`, whitelist slugs `:1524-1557`, ctor `:168`, `class AgentOrchestrator:92`. `ToolCostTracker` — `TOOL_COSTS:35`, `trackToolCall:122`, `getOrDefault:123`, metrics `:138/:141`, `isWithinBudget:174`. `ToolCacheService` — `put:158`, 5-arg `put:166`, `getSmartTTL:185`, TTL table `:38-53`, `buildKey`. `ToolExecutionService` — `executeToolWrapped:163`, `tryAcquire:170`, `get:178`, `put:191`, `trackToolCall:196`, dead `executeTool:69`/`executeToolsParallel:219`/`…AndWait:247`. Test anchors — `CoverageTest` stub `:149`, budget test `:339`/`setMaxBudget:341`, rate-limit `:411`/verify `:427`, `verify eq("calculate")` `:406`, positional `ToolSetup` ctor `:536`; `Coverage2Test:160`; `ResumeToolLoopTest:142`.

---

### 2. Files to touch

| Path | Action |
|---|---|
| `…/modules/llm/tools/ToolNameResolver.java` | **CREATE** |
| `…/modules/llm/tools/ToolInvocation.java` | **CREATE** |
| `…/modules/llm/tools/ToolCostTracker.java` | **MODIFY** |
| `…/modules/llm/tools/ToolCacheService.java` | **MODIFY** |
| `…/modules/llm/tools/ToolExecutionService.java` | **MODIFY** |
| `…/modules/llm/impl/AgentOrchestrator.java` | **MODIFY** |
| `…/modules/llm/model/LlmConfiguration.java` | **MODIFY** |
| `docs/langchain.md`, `docs/security.md` | **MODIFY** |
| `docs/changelog.md` | **MODIFY** (AGENTS.md §2 rule 8) |

No `application.properties` change. No new CDI wiring for `RetryConfiguration` (orthogonal).

#### 2.1 CREATE `ToolNameResolver.java` (`…/modules/llm/tools/`)

Stateless final class, all static. `canonicalForClass(String toolClassSimpleName)` = a `switch` mirroring `sourceForBuiltInTool` (`AgentOrchestrator:1172-1184`); slugs are the exact `builtInToolsWhitelist` tokens (`:1524-1557`, verified). `canonical(String dispatchName, Map<String,String> canonicalNames)` = `canonicalNames == null ? dispatchName : canonicalNames.getOrDefault(dispatchName, dispatchName)`.

| class simple name | slug | | class simple name | slug |
|---|---|---|---|---|
| `CalculatorTool` | `calculator` | | `FetchToolResponsePageTool` | `fetch_tool_response_page` |
| `DateTimeTool` | `datetime` | | `UserMemoryTool` | `usermemory` |
| `WebSearchTool` | `websearch` | | `ConversationRecallTool` | `conversationRecall` |
| `DataFormatterTool` | `dataformatter` | | `ReadAttachmentTool` | `readattachment` |
| `WebScraperTool` | `webscraper` | | `CreateSubAgentTool` | `create_sub_agent` |
| `TextSummarizerTool` | `textsummarizer` | | `ConverseWithAgentTool` | `converse_with_agent` |
| `PdfReaderTool` | `pdfreader` | | `FindAgentsByCapabilityTool` | `find_agents_by_capability` |
| `WeatherTool` | `weather` | | `TeardownAgentTool` | `teardown_agent` |
| | | | default | `null` |

> No substring matching. `discover_tools` (spec name, `AgentOrchestrator:779/:1162`) is not a class — omit; it falls through to itself via `canonical`.

#### 2.2 CREATE `ToolInvocation.java` (§1.3 of old spec)

```java
public record ToolInvocation(String dispatchName, String canonicalName, Double priceOverride) {
    public static ToolInvocation of(String name) { return new ToolInvocation(name, name, null); }
}
```

#### 2.3 MODIFY `ToolCostTracker.java`

Rename `TOOL_COSTS` (`:35-43`) → `static final Map<String,Double> DEFAULT_TOOL_PRICES` (content already matches: `websearch 0.001, weather 0.0005, calculator 0.0, datetime 0.0, dataformatter 0.0, webscraper 0.002, textsummarizer 0.0, pdfreader 0.001`). Add:

```java
public double trackToolCall(String canonicalToolName, String conversationId, Double priceOverride) // NEW
public double trackToolCall(String toolName, String conversationId) // KEEP :122 — delegates, priceOverride == null
```

`:123` → `double cost = priceOverride != null ? Math.max(0.0, priceOverride) : DEFAULT_TOOL_PRICES.getOrDefault(canonicalToolName, 0.0);` (clamp — trap 12). Metric tags `:138/:141` and per-tool map `:126` receive whatever string is passed (now the slug — see trap 4). `isWithinBudget:174` unchanged. No new `@Inject` (stays `new`-constructible for `ToolCostTrackerTest`).

#### 2.4 MODIFY `ToolCacheService.java`

Add `public void put(String toolName, String canonicalToolName, String arguments, String result)`; body: `long ttl = getSmartTTL(canonicalToolName != null ? canonicalToolName : toolName); put(toolName, arguments, result, ttl, TimeUnit.SECONDS);`. Existing 3-arg `put:158`, `getSmartTTL:185` (substring-matcher — keep), `buildKey`, `get:120` **unchanged**. Cache key stays the raw dispatch name (trap 1).

#### 2.5 MODIFY `ToolExecutionService.java`

Keep 8-arg `executeToolWrapped:163` verbatim (delegates via `ToolInvocation.of(toolName)`). Add overload taking `ToolInvocation invocation` as arg 1. Body deltas vs `:163-214`:

| line | new |
|---|---|
| `:170` | `rateLimiter.tryAcquire(invocation.dispatchName(), rateLimit)` |
| `:178` | `cacheService.get(invocation.dispatchName(), arguments)` |
| `:191` | `cacheService.put(invocation.dispatchName(), invocation.canonicalName(), arguments, result)` |
| `:196` | `costTracker.trackToolCall(invocation.canonicalName(), conversationId, invocation.priceOverride())` |

Dead `executeTool:69`/`executeToolsParallel:219`/`…AndWait:247` untouched (§8).

#### 2.6 MODIFY `AgentOrchestrator.java`

- **(a)** `ToolSetup` (`:673-674`) → 5th component `Map<String,String> toolCanonicalNames`.
- **(b)** `buildToolSetup` (`:684`): declare `Map<String,String> toolCanonicalNames = new HashMap<>();` beside `:709`. After `:727` add: `String canonical = ToolNameResolver.canonicalForClass(toolClass.getSimpleName()); toolCanonicalNames.put(toolName, canonical != null ? canonical : toolName);` — reuse the **already proxy-unwrapped** `toolClass` (`:713-716`; trap 9). Return `:756` → `new ToolSetup(toolSpecs, toolExecutors, toolSources, builtInSpecs, Map.copyOf(toolCanonicalNames))`.
- **(c)** live `runToolCallLoop` (`:852`, locals `:859`): add `toolCanonicalNames = setup.toolCanonicalNames()`, `toolPricing = task.getToolPricing()`, `enforceBudget = task.getEnforceBudget() != null ? task.getEnforceBudget() : BUDGET_ENFORCE_DEFAULT`.
- **(d)** resume path (`:407` region, `executeSingleToolCallResult` call): same three locals.
- **(e)** append `Map<String,String> toolCanonicalNames, Map<String,Double> toolPricing, boolean enforceBudget` to `executeSingleToolCall` (`:1054-1060`) and `executeSingleToolCallResult` (`:1079-1085`); update the internal delegate (`:1062-1064`) and the three call sites `:407 / :950 / :994`.
- **(f)** budget gate (`:1105`): `if (enforceBudget && maxBudget != null && conversationId != null && !…isWithinBudget(conversationId, maxBudget))`. Body `:1107-1117` unchanged.
- **(g)** rate-limit + dispatch (`:1137-1148`): resolve `String canonical = ToolNameResolver.canonical(toolRequest.name(), toolCanonicalNames)`; rate limit = `toolRateLimits.get(toolRequest.name())` **first** (trap 3), else `toolRateLimits.get(canonical)`, else `defaultRateLimit`; `Double price = toolPricing != null ? toolPricing.get(canonical) : null`; call `executeToolWrapped(new ToolInvocation(toolRequest.name(), canonical, price), …)`.
- **(h)** constant beside the `private static final` block (`:100-110`): `private static final boolean BUDGET_ENFORCE_DEFAULT = ConfigProvider.getConfig().getOptionalValue("eddi.tools.budget.enforce-by-default", Boolean.class).orElse(false);` — `AgentOrchestrator` is package-private (`:92`), `new`-ed at `LlmTask.java:195`; `@ConfigProperty` will not inject (trap 10).

#### 2.7 MODIFY `LlmConfiguration.java`

In `Task` (`:65`), inside `// === Budget & Cost Control ===` (`:235-238`), after `maxBudgetPerConversation` (`:238`):

```java
/** Enforce {@link #maxBudgetPerConversation}. Default false (inert pre-6.x; unconditional
 * enforcement would abort tool calls on live agents). Cost tracking runs regardless. */
private Boolean enforceBudget;
/** Per-call tool prices (USD), keyed on the canonical built-in slug (same tokens as
 * builtInToolsWhitelist). Overrides ToolCostTracker.DEFAULT_TOOL_PRICES. */
private Map<String, Double> toolPricing;
```

Getters/setters after `setMaxBudgetPerConversation` (`:572`): `getEnforceBudget()/set…`, `getToolPricing()/set…`. (Do not disturb the extracted `RetryConfiguration retry` accessors at `:558-563`.)

---

### 3. Config surface

| JSON field | POJO | Type | Default | Descriptor |
|---|---|---|---|---|
| `enforceBudget` | `LlmConfiguration.Task` | `Boolean` | `null` → `eddi.tools.budget.enforce-by-default` → `false` | none |
| `toolPricing` | `LlmConfiguration.Task` | `Map<String,Double>` | `null` → `DEFAULT_TOOL_PRICES` | none |
| `maxBudgetPerConversation` / `toolRateLimits` | unchanged | unchanged | unchanged | none |

```json
{ "type":"eddi://ai.labs.langchain", "maxBudgetPerConversation":5.0, "enforceBudget":true,
  "toolPricing":{"websearch":0.005,"webscraper":0.002}, "toolRateLimits":{"websearch":30} }
```

Property `eddi.tools.budget.enforce-by-default` (boolean, default `false`) is the fallback for `enforceBudget`.

---

### 4. Stored-config back-compat

| Stored config | After deploy |
|---|---|
| No budget/pricing fields | `websearch/webscraper/pdfreader/weather` built-ins price non-zero; `eddi.tool.costs` becomes non-zero. **No behavioral change to any tool call.** |
| `maxBudgetPerConversation:5.0`, no `enforceBudget` | Still not enforced — identical to today. Opt-in required. |
| `maxBudgetPerConversation:5.0, enforceBudget:true` | Enforced from turn 1; crossing call `→ Error: Budget exceeded for conversation <id>`. |
| `toolRateLimits:{"websearch":30}` (documented slug form) | **Now binds** — 30/min each to `searchWeb/searchNews/searchWikipedia` (per-dispatch buckets, trap 2). Previously dead. Release-note it. |
| `toolRateLimits:{"calculate":7}` (method-name form) | Unchanged — dispatch key wins (trap 3). |
| Cache TTL | Built-ins get intended TTLs vs flat 300 (`ToolCacheService:38-53`): `calculator` 7d, `dataformatter/textsummarizer/pdfreader` 24h, `webscraper` 1h, `websearch` 30m, `datetime` **60s** (tightened 5×), `weather` 300s. |
| Metrics | `eddi.tool.calls{tool=…}` / `eddi.tool.costs{tool=…}` tag values flip method→slug for built-ins (trap 4). |

No field removed. `FAIL_ON_UNKNOWN_PROPERTIES=false` → older instances ignore `enforceBudget`/`toolPricing` (rolling-deploy + ZIP-import safe).

---

### 5. Tests

**Will BREAK — must update:**

| Test | Fix |
|---|---|
| `AgentOrchestratorCoverageTest:536` positional `new ToolSetup(all, Map.of(), Map.of(), builtIn)` | add `, Map.of()` |
| `…CoverageTest#…BudgetExceeded…` (`:339`, `setMaxBudgetPerConversation:341`) | add `task.setEnforceBudget(true);` |
| stubs `CoverageTest:149`, `Coverage2Test:160`, `ResumeToolLoopTest:142`, `ToolPauseTest:139` | re-point to `executeToolWrapped(any(ToolInvocation.class), anyString(), any(), any(Supplier.class), anyBoolean(), anyBoolean(), anyBoolean(), anyInt())` — else the 8-arg `anyString()` stub no longer matches, returns `null`, tool results silently null |
| `CoverageTest` verifies `eq("calculate")` (`:406`, `:427`) | `argThat(inv -> "calculate".equals(inv.dispatchName()) && "calculator".equals(inv.canonicalName()))` |

**CREATE `ToolNameResolverTest`**: all 16 classes → slug; unknown → null; `"CalculatorTool_ClientProxy"` → null (guards trap 9); `canonical` unmapped/null-map → verbatim/no-NPE; `canonical("searchWeb", Map.of("searchWeb","websearch"))` → `"websearch"`.

**ADD** `ToolCostTrackerTest`: `trackToolCall("searchWeb","c")→0.0` & `("websearch","c")→0.001`; priceOverride wins; override prices unknown slug; negative clamps to 0.0; 2-arg delegates. `ToolCacheServiceTest`: canonical for TTL / raw for key; `put("searchWeb","websearch",…)` then `get("searchNews",…)→null` (trap-1 guard); null-canonical falls back. `ToolExecutionServiceTest`: cost under canonical, rate-limit under dispatch, priceOverride passed, legacy overload identical names. `AgentOrchestratorCoverageTest`: slug rate-limit key matches; dispatch key wins over slug; budget NOT enforced when flag absent (`verify(costTracker, never()).isWithinBudget`); enforced when true; `toolPricing` override reaches executor; `buildToolSetup_populatesCanonicalNames` (`get("calculate")=="calculator"`).

All plain Mockito/JUnit — no sockets/Docker.

---

### 6. Acceptance criteria

1. `.\mvnw.cmd validate` passes. 2. `.\mvnw.cmd clean test` passes — **`clean` mandatory** (record + 2 signature changes masked by stale `.class`). 3. `grep -rn "TOOL_COSTS" src/main/java` → 0. 4. `grep -rn "toolRequest.name(), toolRequest.arguments(), conversationId" src/main/java` → 0. 5–8. Per-class `-Dtest=…` green (filter by **class**, never `Class#method`). 9. `builtInToolsWhitelist:["websearch"]` → non-zero `eddi.tool.costs{tool="websearch"}`. 10. `maxBudgetPerConversation:0.0` alone → tool runs; `+enforceBudget:true` → `Error: Budget exceeded…`. 11. `toolRateLimits:{"websearch":1}` → 2nd `searchWeb`/min → `Error: Rate limit exceeded for tool: searchWeb`. 12. `toolPricing:{"websearch":0.05}` → `GET /toolhistory/costs` reflects $0.05/call. 13–14. Docs (`docs/langchain.md`, `docs/security.md`) + top changelog entry name the three live-visible effects (rate limits bind; TTLs change; metric tags→slugs) and the inert budget flag; `docs/langchain.md` states `maxBudgetPerConversation` covers **tool** cost only (LLM token cost is cascade-scoped `maxCostPerRun`).

---

### 7. Traps

1. **Never canonicalize the cache key** (`buildKey` stays raw dispatch name) — `searchWeb/searchNews/searchWikipedia` all → `websearch`; shared key serves wrong results.
2. Rate-limit **buckets** stay per-dispatch-name; only the **limit value** is slug-looked-up. `{"websearch":30}` = three independent 30/min buckets.
3. Dispatch-name key checked **before** slug in `toolRateLimits`, else `CoverageTest:411-427` regresses.
4. Metric tags move method→slug (`ToolCostTracker:126/138/141`). Deliberate. To preserve dashboards, pass `dispatchName()` into metrics and `canonicalName()` only into pricing — decide before commit 3.
5. Do **not** default `enforceBudget` true, nor when `maxBudget` is set — that is the forbidden live break.
6. Keep legacy 8-arg `executeToolWrapped` + 2-arg `trackToolCall` (five test classes depend on them).
7. `isWithinBudget` uses `<=` (`:180`), checked **before** the call (`:1105`) — crossing call allowed, next blocked. Do not "fix."
8. Thread safety: `ToolNameResolver` static; `ToolInvocation` immutable; `ToolSetup.toolCanonicalNames` via `Map.copyOf` at `:756`; `DEFAULT_TOOL_PRICES` a `Map.of`.
9. `toolClass` at `:713-716` is **already proxy-unwrapped** — use it, not `tool.getClass()` (which yields `…_ClientProxy` → every canonical lookup null; same bug in a new coat).
10. `AgentOrchestrator` is not a CDI bean (`:92`, `new`-ed at `LlmTask:195`) — `@ConfigProperty` won't inject; use `ConfigProvider` in a `static final`.
11. Both live (`:950/:994`) and resume (`:407`) feed `executeSingleToolCallResult` — miss the resume path → "budget enforced on live turns, ignored after HITL approval."
12. `toolPricing` doubles are operator-supplied — clamp `Math.max(0.0, …)` in `trackToolCall` (negative would credit the budget).
13. `CoverageTest:536` positionally constructs `ToolSetup` — compiles today, breaks after (a).

---

### 8. Out of scope

`ToolExecutionService.executeTool/executeToolsParallel*` (zero prod callers — don't touch). Cascade LLM-token cost (`CascadingModelExecutor.computeCost/runCostUsd/maxCostPerRun`) stays run-scoped — folding it into `ToolCostTracker` to make `maxBudgetPerConversation` a true total ceiling is a **separate** follow-up. `ToolCacheService` conversation/tenant scoping (**D5**). `TenantQuotaService` + tenant cost block `AgentOrchestrator:1119-1133` (**D9**). Audit-ledger cost fields (**D1** — wires plumbing; D2 makes tool numbers real). `toolTrace` SSE (**D3**). Rate-limit bucket sharing (trap 2). Budget `<=`/pre-check boundary (trap 7). EDDI-Manager UI (`getExtensionDescriptor:1086-1090` exposes only `uri`). **`RetryConfiguration` (the #593 extraction) — orthogonal; do not modify.**

---

### 9. Sequencing

**Size S–M. Six commits, each compilable + green.**

| # | Commit | Verify |
|---|---|---|
| 1 | `feat(tools): add ToolNameResolver and ToolInvocation for canonical tool naming` (§2.1/2.2 + `ToolNameResolverTest`) | `-Dtest=ToolNameResolverTest` |
| 2 | `refactor(tools): make tool pricing agent-configurable` (§2.3 + §2.7 `toolPricing` + `ToolCostTrackerTest`) | `-Dtest=ToolCostTrackerTest,ToolCostTrackerModelsTest` |
| 3 | `fix(tools): resolve canonical tool name once at the executor boundary` (§2.4/2.5 + §2.6 a-e,g + 4 stub updates + `ToolSetup` ctor `:536`) — **behavior-changing; message names the three live effects** | `.\mvnw.cmd clean test` |
| 4 | `feat(llm): add opt-in enforceBudget flag for maxBudgetPerConversation` (§2.7 `enforceBudget` + §2.6 f,h + budget tests) | `-Dtest=AgentOrchestratorCoverageTest` |
| 5 | `test(tools): cache-key collision and canonical-name regression guards` | `.\mvnw.cmd test` |
| 6 | `docs: document toolPricing, enforceBudget and slug-based toolRateLimits` + `docs/changelog.md` | manual |

Commits 1–2 land alone safely. **Commit 4 must ship in the same release as 3.**

I have verified everything against the current tree. Producing the replacement spec.

## D1 — Audit ledger records zero tokens, cost, and toolCalls

**Premise HOLDS post-#593.** `LifecycleManager:522` still passes literal `null` for `toolCalls`, `:523` still passes literal `0.0` for `cost`, and `audit:token_usage` (read at `:508`) still has **zero writers** in `src/main/java`. `audit:confidence` (read at `:467`) still has **zero writers** — `LlmTask:560` writes the wrong key `audit:cascade_confidence` as a `String`. Re-verified by grep across `src/main/java`.

---

### 1. Changes since PR #593

The post-#587 spec was structurally right; #593 shifted every line and **already closed one sub-item** (streaming metadata capture). It did **not** touch the audit-key wiring, the HMAC canonicalizer, the retry double-count, the cascade token under-count, or the `LegacyChatExecutor` NPE. It **added a fourth `new AuditEntry(` site** with the same zero-cost/null-tools defect.

#### 1.1 What #593 (or intervening work) already fixed — delete from the post-#587 spec

| Old-spec item | Status now | Evidence (current tree) |
|---|---|---|
| §1.5 / §2.2b / Trap 13 — "swap `streamingLegacyChatExecutor.execute(...)` → `executeCapturing(...)` at `:591` to capture streaming metadata" | ✅ **DONE — remove entirely** | `StreamingLegacyChatExecutor.execute` now returns `record StreamingResult(String response, Map<String,Object> metadata)` (`:44`); `buildMetadata:276-304` puts `tokenUsage` (`:297-299`, null-guarded `?:0`). `LlmTask:609-611` calls `execute(...)`, reads `.response()` **and** `responseMetadata.putAll(streamingResult.metadata())`. Streaming `tokenUsage` already lands in `responseMetadata`. **No call-site swap. `StreamingLegacyChatExecutor` is no longer touched by D1.** |
| §2.2a token surfacing on cascade | ✅ **Partly DONE** | `LlmTask:516-517` now does `responseMetadata.put("tokenUsage", cascadeResult.tokenUsage())`; `:536-539` add `cascadeCostUsd`/`cascadeModel`/`cascadeStep`/`cascadeConfidence`. Cascade token/cost now reach `responseMetadata`; the gap is only the audit-key write. |
| Legacy sync branches surface metadata | ✅ DONE | `LlmTask:582,:617,:626` assign `responseMetadata = chatResult.responseMetadata()`. |

#### 1.2 What #593 made worse / left broken — the same defect class

| Defect | Location (re-verified) | Verdict |
|---|---|---|
| `audit:cascade_confidence` written as `String.valueOf(...)`; reader wants `audit:confidence` as `Double` | `LlmTask:560` | dead key + String-into-Double slot bug — **unfixed** |
| `audit:cascade_cost` = `String.format("%.6f", runCostUsd)` (String), zero readers | `LlmTask:561-562` | dead key, real dollars dropped — **unfixed** |
| `audit:cascade_token_usage`, zero readers; reader wants `audit:token_usage` | `LlmTask:563-564` | dead key — **unfixed** |
| **NEW 4th `new AuditEntry(` — task-failure path** hardcodes `toolCalls=null, cost=0.0` | `LifecycleManager:407-413` (`failureEntry`) | **NEW instance from #593.** See §2.3 scope decision. Trap 11 must update: **four** non-store sites now, not three. |
| Retry double-count: `tokenHolder[0]` accumulated inside the retry lambda, never reset on replay | `AgentOrchestrator:820`/`:457` alloc → `:870` lambda → `:910` accum | **live bug, unfixed.** #593 gutted `AgentExecutionHelper` to delegate to `RetryConfiguration.executeWithRetry` (`:33`), which re-invokes `action.call()` in a `while` loop (`RetryConfiguration:100-142`) → the lambda replays with `tokenHolder[0]` retained. |
| Cascade `tokenUsage` reports the accepted step only, while `runCostUsd` is a true run total | `CascadingModelExecutor:387-388`, `:335`, `withRun:756-762` | **unfixed** — two fields disagree |
| `LegacyChatExecutor` `Map.of` over three boxed `Integer` token accessors → NPE on null | `LegacyChatExecutor:110-113` | **unfixed** |
| `AuditHmac.sortedMapString` non-recursive (`Objects.toString`) | `AuditHmac:124-130` | **UNCHANGED by #593** — trap stands verbatim |

`ConfidenceEvaluator` contains **no** `audit:*` keys (grep clean). The entire audit surface is `LlmTask` + `LifecycleManager`.

#### 1.3 Net effect

Still a **wiring + canonicalization** change, same four production files as the post-#587 spec, minus `StreamingLegacyChatExecutor`:

```
AuditHmac            recurse (must land first)
LlmTask              re-key cascade audit; capture agent-branch responseMetadata;
                     accumulate token/tool/cost at audit block; executeResume compiled_prompt + accumulate
LifecycleManager     consume audit:token_usage / audit:tool_calls / audit:cost; add cascadeModel+confidence to llmDetail
LegacyChatExecutor   null-safe token map
AgentOrchestrator    reset tokenHolder on retry; toolCostUsd delta into responseMetadata
CascadingModelExecutor  accumulate tokenUsage across attempted steps
```

**Effort: S.** ~180 lines production, ~500 lines test.

---

### 2. Files to touch (real signatures, current lines)

| File | Action |
|---|---|
| `.../engine/audit/AuditHmac.java` | MODIFY — recursive canonicalization at `sortedMapString:124-130` |
| `.../engine/lifecycle/internal/LifecycleManager.java` | MODIFY — `buildAuditEntry:478-526` consume new keys; replace `null` `:522` / `0.0` `:523`; add `cascadeModel`+`confidence` to `llmDetail` block `:497-511` |
| `.../modules/llm/impl/LlmTask.java` | MODIFY — cascade audit re-key `:557-566`; agent-branch metadata capture `:572-578`,`:595-604`; accumulators at audit block `:665-682`; `executeResume:846` audit block `:913-921` |
| `.../modules/llm/impl/LegacyChatExecutor.java` | MODIFY — null-safe token map `:110-113` |
| `.../modules/llm/impl/AgentOrchestrator.java` | MODIFY — retry reset `:870`; `toolCostUsd` into `responseMetadata` `:824-828` & `:468-472` |
| `.../modules/llm/impl/CascadingModelExecutor.java` | MODIFY — run-total tokenUsage accumulator |
| test: `AuditHmacTest`, `LifecycleManagerTest`, `LlmTaskCoverage2Test`, `LlmTaskDeepBranchTest`, `AgentOrchestratorTest`, `CascadingModelExecutorEnterpriseTest`, `LegacyChatExecutorTest` | MODIFY |

No CREATE, no DELETE. **`StreamingLegacyChatExecutor(.java/Test)` no longer touched.**

#### 2.1 `AuditHmac.java` — recursive canonicalization (unchanged design; line shift 122→124)

Replace `sortedMapString` at **`:124-130`**; keep `buildCanonicalString:97-118` (already routes `input`/`output`/`llmDetail`/`toolCalls` at `:110-113`).

```java
private static String sortedMapString(Map<String, Object> map) {
    if (map == null) return "";
    return new TreeMap<>(map).entrySet().stream()
            .map(e -> e.getKey() + "=" + canonicalValue(e.getValue()))
            .collect(Collectors.joining(",", "{", "}"));
}

@SuppressWarnings("unchecked")
private static String canonicalValue(Object value) {
    if (value == null) return "";
    if (value instanceof Map<?, ?> m) return sortedMapString((Map<String, Object>) m);
    if (value instanceof List<?> l) return canonicalList(l);
    return value.toString();
}

private static String canonicalList(List<?> list) {
    return list.stream().map(AuditHmac::canonicalValue).collect(Collectors.joining(",", "[", "]"));
}
```

Scalars keep `toString()` ⇒ flat maps produce a **byte-identical** canonical string ⇒ historical HMACs still verify. Add `import java.util.List;` if absent.

#### 2.2 `LlmTask.java`

**(a) Cascade audit block — replace `:557-566` wholesale.**

```java
if (memory.getAuditCollector() != null) {
    String cascadeModelDesc = cascadeAuditModel + " (step " + cascadeResult.stepUsed() + ")";
    currentStep.storeData(dataFactory.createData("audit:cascade_model", cascadeModelDesc));
    // Double, and the key LifecycleManager.buildTaskSummary:467 / buildAuditEntry actually read.
    currentStep.storeData(dataFactory.createData("audit:confidence", cascadeResult.confidence()));
    accumulateCost(currentStep, cascadeResult.runCostUsd());
    accumulateTokenUsage(currentStep, cascadeResult.tokenUsage());
}
```

Retire `audit:cascade_confidence`/`_cost`/`_token_usage` (zero readers). `cascadeResult.confidence()` is primitive `double` (`CascadingModelExecutor:125`) → autoboxes to `Double`, matching `IData<Double>` at `LifecycleManager:467`.

**(b) Capture agent-branch metadata** — the two agent branches drop `responseMetadata` today:

| Branch | Line | Change |
|---|---|---|
| skipCascade agent | `:572-578` | after `toolTrace = agentResult.trace();` add `responseMetadata.putAll(agentResult.responseMetadata());` |
| standard agent | `:595-604` | same |

`ExecutionResult.responseMetadata()` carries `tokenUsage` (`AgentOrchestrator:824-828`, `:468-472`). Cascade/legacy/streaming branches already populate `responseMetadata`.

**(c) Accumulators** — add three private helpers after `executeTask` (`:304`). Sub-tasks loop `:283-294`; `getLatestData` is last-write-wins, so accumulate.

```java
private void accumulateTokenUsage(IWritableConversationStep step, Map<String, Object> delta) { … }
private void accumulateToolCalls(IWritableConversationStep step, List<Map<String, Object>> trace, String taskId) { … }
private void accumulateCost(IWritableConversationStep step, double delta) { … }
```

| Memory key | Java type | Shape |
|---|---|---|
| `audit:token_usage` | `Map<String,Object>` | `{"inputTokens","outputTokens","totalTokens"}` — **`LinkedHashMap`+`put`, never `Map.of`**; null-safe add (`a==null?0:a`+`b==null?0:b`, mirroring `AgentOrchestrator.sumInt:1029`) |
| `audit:tool_calls` | `Map<String,Object>` | `{"calls": List<Map<String,Object>>}`, each entry augmented with `"llmTaskId": task.getId()` |
| `audit:cost` | `Double` | running sum: cascade `runCostUsd` + tool-cost delta |

At the audit block (`:665-682`, inside `if (memory.getAuditCollector() != null)`), after `audit:model_name`, add:
```java
Object tu = responseMetadata.get("tokenUsage");
if (tu instanceof Map) accumulateTokenUsage(currentStep, (Map<String,Object>) tu);
Object toolCost = responseMetadata.get("toolCostUsd");
if (toolCost instanceof Number n) accumulateCost(currentStep, n.doubleValue());
```
`accumulateToolCalls(currentStep, toolTrace, task.getId())` — call where `toolTrace` is in scope (non-empty guarded at `:685`). Cascade cost/tokens are accumulated in the cascade block (a); do **not** double-add via `cascadeCostUsd`.

**(d) `executeResume` (`:846`), audit block `:913-921`** — prepend `audit:compiled_prompt` (LM gates the whole `llmDetail` block on it) and accumulate:

```java
if (memory.getAuditCollector() != null) {
    currentStep.storeData(dataFactory.createData("audit:compiled_prompt",
            processedParams.getOrDefault(KEY_SYSTEM_MESSAGE, "") + "\n---\n"
                    + processedParams.getOrDefault(KEY_PROMPT, "")));
    // … existing audit:model_response :915 / audit:model_name :919 …
    if (result != null && result.responseMetadata() != null) {
        Object tu = result.responseMetadata().get("tokenUsage");
        if (tu instanceof Map) accumulateTokenUsage(currentStep, (Map<String,Object>) tu);
        Object tc = result.responseMetadata().get("toolCostUsd");
        if (tc instanceof Number n) accumulateCost(currentStep, n.doubleValue());
    }
    accumulateToolCalls(currentStep, toolTrace, task.getId());
}
```
`result` = `ExecutionResult` from `resumeToolLoop` (`:884`); `toolTrace` at `:888`. `executeResume` has no `systemMessage` local — read from `processedParams` (built `:877`). Verify `KEY_SYSTEM_MESSAGE`/`KEY_PROMPT` constants exist and match the keys `runTemplateEngineOnParams` emits [UNVERIFIED — implementer must confirm the param key names; `executeTask:668` uses `KEY_PROMPT` and a `systemMessage` local, not `KEY_SYSTEM_MESSAGE`].

#### 2.3 `LifecycleManager.java`

**`buildTaskSummary:467`** — no code change; `audit:confidence` is now really produced by §2.2a. Add a comment naming the producer.

**`buildAuditEntry:478-526`** — inside the `llmDetail` block (`:497-511`, gated on `audit:compiled_prompt` `:498`) add `cascadeModel` (from `audit:cascade_model`) and `confidence` (from `audit:confidence`). The existing `audit:token_usage` read (`:508-510`) starts returning data. Then before the `return` (`:517`):

```java
Map<String, Object> toolCalls = null;
IData<Map<String, Object>> toolCallData = currentStep.getLatestData("audit:tool_calls");
if (toolCallData != null && toolCallData.getResult() != null && !toolCallData.getResult().isEmpty())
    toolCalls = toolCallData.getResult();

double cost = 0.0;
IData<Double> costData = currentStep.getLatestData("audit:cost");
if (costData != null && costData.getResult() != null)
    cost = costData.getResult();
```
Replace `null` at `:522` with `toolCalls`; `0.0` at `:523` with `cost`.

> ⚠️ `:502-510` use loose `if (data != null)` (no result-null check). **Keep the loose form for `modelResponse`/`modelName` (`:502-507`)** — out of scope. **Tighten `audit:token_usage` (`:508-510`) to `!= null && getResult() != null`** — it has never produced a value, nothing grandfathered.

**Failure-path `AuditEntry` (`:407-413`, NEW in #593):** scope decision — **leave `cost=0.0`, `toolCalls=null`.** On the task-failure path the accumulators are partial/unreliable (the task threw mid-execution; `responseMetadata` may be absent). Reading `audit:*` there is a speculative enhancement, not the D1 defect. Note it in the changelog as a known limitation, not a fix. Trap 11 updated: this is the **fourth** non-store site and is deliberately out of scope, like `CapabilityMatchCondition:179` / `GdprComplianceService:420`.

#### 2.4 `AgentOrchestrator.java`

1. **Retry reset.** Insert `tokenHolder[0] = null;` as the **first statement inside** the `executeWithRetry` lambda at **`:870`**, before `List<ChatMessage> currentMessages = new ArrayList<>(initialMessages);` (`:871`). Without it a retried turn double-counts (`RetryConfiguration.executeWithRetry` replays the lambda; `tokenHolder` accum at `:910`). Line unchanged from post-#587 spec (coincidence).
2. **Tool-cost delta.** In `executeWithTools` (`:795`, around the `responseMetadata` build at `:824-828`) and `resumeToolLoop` (`:329`, around `:468-472`), snapshot conversation cost around `runToolCallLoop` and put the delta in `responseMetadata`:

```java
double costBefore = conversationCostOrZero(conversationId);
… runToolCallLoop(…) …
responseMetadata.put("toolCostUsd", Math.max(0.0, conversationCostOrZero(conversationId) - costBefore));
```
Private static helper: `toolExecutionService.getCostTracker()` (`ToolExecutionService:50`) → `.getConversationCosts(cid)` (`ToolCostTracker:160`, **returns null when untracked**) → `.getTotalCost()` (`ToolCostTracker:110`). Null-guard; `Math.max(0.0, …)` because `resetConversation:213` can fire between snapshots. No instance fields (`AgentOrchestrator` is a singleton). `resumeToolLoop` has `conversationId` available via `memory.getConversationId()`.

#### 2.5 `CascadingModelExecutor.java` — accumulate tokens across attempted steps

`runCostUsd` is accumulated (`:224` decl, `:315` `+=`) but `tokenUsage` is not: accepted return `:387-388`, `bestSoFar :335`, and `withRun:756-762`/`finalizeBest:765-768` all carry a **single** step's `tokenUsage`. A 3-model escalation under-reports two models. Add a `Map<String,Object> runTokenUsage` accumulator beside `runCostUsd` (`:224`), merge each `stepResult.tokenUsage` after `:315` (null-safe int add on `inputTokens`/`outputTokens`/`totalTokens`), and thread it through `withRun` (both overloads `:756`,`:760`) and `finalizeBest:765` as a new param, and into the accepted-return `CascadeResult` (`:387-388`). `CascadeResult` **record arity is unchanged** (`tokenUsage` field already exists at `:125`; it is now populated with the run total instead of the accepted step). The per-step trace `tokenUsage` (`:319-320`) stays as per-step evidence.

> This changes `withRun`/`finalizeBest` **method** signatures → run `./mvnw clean test` before the dependent commit (stale `.class` risk per MEMORY).

#### 2.6 `LegacyChatExecutor.java` — null-safe token map

Replace `:110-113` (`Map.of` over three boxed `Integer` — live NPE):

```java
if (metadata.tokenUsage() != null) {
    Map<String, Object> tu = new LinkedHashMap<>();
    tu.put("inputTokens", metadata.tokenUsage().inputTokenCount());
    tu.put("outputTokens", metadata.tokenUsage().outputTokenCount());
    tu.put("totalTokens", metadata.tokenUsage().totalTokenCount());
    responseMetadata.put("tokenUsage", tu);
}
```
Add `import java.util.LinkedHashMap;`. **Only `LegacyChatExecutor` is unguarded** — `StreamingLegacyChatExecutor.buildMetadata:297-299` (`?:0`) and `AgentOrchestrator.tokenUsageMap:1036-1041` (`?:0`) already null-guard; leave them.

---

### 3. Config surface

**None.** No JSON fields, no `LlmConfiguration` change, no `ExtensionDescriptor`, no Manager field. Gated on runtime `memory.getAuditCollector() != null`.

> **Deliberately not done:** hoisting `inputPricePer1M`/`outputPricePer1M` (`LlmConfiguration:1178/1181` on `ModelCascadeConfig`, `:1318/1324` on `CascadeStep` — cascade-only) to `Task` level so non-cascade tasks get dollar LLM cost. Real config addition + `ExtensionDescriptor` + Manager surface → follow-up. D1 consumes existing cascade price data; non-cascade tasks report tool cost only (~$0 until D2).

---

### 4. Stored-config back-compat

| Concern | Outcome |
|---|---|
| Stored agent JSON / ZIP import | Untouched — no config schema change |
| Existing `audit`/`audit_ledger` rows | Read path unchanged; `AuditStore.fromDocument:164` / `PostgresAuditStore.fromRow:234` tolerate absent `llmDetail`/`toolCalls` |
| **HMAC of pre-existing entries** | ✅ Preserved — historical entries have flat-or-null `input`/`output`/`llmDetail`, `toolCalls==null`; `canonicalValue` falls through to `toString()` for scalars ⇒ byte-identical. **Assert, don't assume — AC-2.** |
| Retired `audit:cascade_confidence`/`_cost`/`_token_usage` | Zero readers repo-wide (grep verified). Never persisted — lived only in in-flight `ConversationStep` data. No migration. |
| Behavior change on deploy | LLM-task entries gain `llmDetail.tokenUsage`/`.cascadeModel`/`.confidence`, non-null `toolCalls`, non-zero `cost` (real dollars on cascade tasks with configured prices; tool cost elsewhere). Resumed HITL turns gain a full `llmDetail` block. Entries larger. Task-failure entries unchanged (still zero — §2.3). |
| `FAIL_ON_UNKNOWN_PROPERTIES` | N/A — nothing removed from a POJO |

---

### 5. Tests

**Vacuous — must be repaired (paths/line refs [UNVERIFIED — implementer must re-open; #593 shifted test lines too]):**

| Test | Why vacuous | Fix |
|---|---|---|
| `LifecycleManagerTest.auditEntryWithLlmDetails` | stubs `audit:token_usage`, a key no production code wrote; asserts only `containsKey` | keep stub (now real), add nested-map `assertEquals`, add sibling asserting absence when unstubbed |
| `LlmTaskCoverage2Test` cascade audit test (`verify(dataFactory).createData(eq("audit:cascade_confidence"), any())`) | pins wrong key, any type — hides String-into-Double bug | → `verify(...).createData(eq("audit:confidence"), eq(<Double>))` **plus** `verify(..., never()).createData(eq("audit:cascade_confidence"), any())`; rename |
| `LlmTaskCoverage2Test` (`eq("audit:cascade_token_usage")`) | pins dead key | retarget to `audit:token_usage` with concrete map |
| `LlmTaskDeepBranchTest.auditCollectorStoresData` (`verify(..., atLeast(3)).createData(anyString(), any())`) | passes for any three keys | explicit `verify` per key |

**New tests:**

| Class | Method | Assertion |
|---|---|---|
| `AuditHmacTest` | `flatMapCanonicalStringUnchangedByRecursion` | canonical string == **literal captured from pre-change impl** — proves historical HMAC verify |
| `AuditHmacTest` | `nestedMapProducesSortedDeterministicString` | two `tokenUsage` maps, opposite insertion order → same HMAC |
| `AuditHmacTest` | `bsonDocumentAndLinkedHashMapHashIdentically` | `llmDetail={"tokenUsage": LinkedHashMap}` vs `org.bson.Document` → same HMAC |
| `AuditHmacTest` | `nestedListOfMapsHashesDeterministically` | `toolCalls={"calls": List<Map>}` vs `List<Document>` → same HMAC |
| `AuditHmacTest` | `nullValueInNestedMapDoesNotThrow` | all-null token counts → `""`, no NPE |
| `LifecycleManagerTest` | `auditEntryPopulatesToolCallsFromMemory` / `…NullWhenKeyAbsent` / `…NullWhenMapEmpty` | `toolCalls()` non-null w/ `calls` / null / null |
| `LifecycleManagerTest` | `auditEntryPopulatesCostFromMemory` / `…ZeroWhenKeyAbsent` | `cost()==0.0042` / `==0.0` |
| `LifecycleManagerTest` | `auditEntryLlmDetailCarriesConfidenceAndCascadeModel` | both in `llmDetail` |
| `LlmTaskCoverage2Test` | `cascadeWritesConfidenceAsDoubleUnderAuditConfidence` | see vacuous table |
| `LlmTaskCoverage2Test` | `cascadeRunCostAccumulatedIntoAuditCost` | `audit:cost`==`runCostUsd()` as `Double`; `audit:cascade_cost` never written |
| `LlmTaskDeepBranchTest` | `tokenUsageAccumulatedAcrossSubTasks` | 2 sub-tasks ×10/20 → `audit:token_usage`=20/40/60 |
| `LlmTaskDeepBranchTest` | `tokenUsageAccumulationHandlesNullCounts` | null counts no NPE, don't zero prior |
| `LlmTaskDeepBranchTest` | `executeResumeWritesCompiledPrompt` | `verify(...).createData(eq("audit:compiled_prompt"), any())` on resume |
| `LlmTaskDeepBranchTest` | `toolCallsAccumulatedAcrossSubTasksWithTaskId` | merged `calls` length == sum; each carries `llmTaskId` |
| `LlmTaskDeepBranchTest` | `agentModeSurfacesTokenUsage` | agent branch (`:572`/`:595`) yields non-empty `audit:token_usage` (guards §2.2b) |
| `AgentOrchestratorTest` | `retriedLoopDoesNotDoubleCountTokens` | first attempt throws retryable → final counts == single run. **#593 regression guard.** |
| `AgentOrchestratorTest` | `responseMetadataCarriesToolCostDelta` | `toolCostUsd`==post−pre; `0.0` when `getConversationCosts` null |
| `CascadingModelExecutorEnterpriseTest` | `tokenUsageAccumulatedAcrossEscalatedSteps` | 3-step escalation → `CascadeResult.tokenUsage` is the sum |
| `LegacyChatExecutorTest` | `nullTokenCountsDoNotThrow` | `TokenUsage(null,null,null)` → metadata present, no NPE |

**CI-only:** `AuditStoreTest`, any `*IT.java` — do not run locally (loopback/Docker per MEMORY). Filter by **whole class**, never `Class#method` (nested-class filter silently runs 0 tests).

---

### 6. Acceptance criteria

1. `./mvnw compile` exits 0.
2. `AuditHmacTest` passes incl. `flatMapCanonicalStringUnchangedByRecursion` vs a captured literal.
3. `grep -rn "audit:cascade_confidence\|audit:cascade_cost\|audit:cascade_token_usage" src/main src/test` → **zero**.
4. `grep -n "Map.of" src/main/java/ai/labs/eddi/modules/llm/impl/LegacyChatExecutor.java` → zero.
5. `LifecycleManager:522/:523` no longer literal `null`/`0.0` for the **main** entry (`:517`); the failure entry `:407-413` intentionally unchanged.
6. Reader/writer symmetry: for every `audit:*` key read in `LifecycleManager`, ≥1 writer exists in `LlmTask` (the check the original bug and #593 regression both fail).
7. `LifecycleManagerTest`, `LlmTaskDeepBranchTest`, `LlmTaskCoverage2Test`, `AgentOrchestratorTest`, `CascadingModelExecutorEnterpriseTest`, `LegacyChatExecutorTest` pass under `./mvnw clean test` (**clean required** — §2.5 changes helper signatures).
8. Smoke (CI/manual): tool-using LLM turn with auditing → `GET /audit/{conversationId}` shows `llmDetail.tokenUsage.totalTokens > 0`, non-empty `toolCalls.calls`; ledger verify reports **HMAC-valid after store round-trip**; historical entries still verify.
9. Cascade turn with prices configured → `entry.cost() > 0`. HITL-resumed turn → non-null `llmDetail` with `compiledPrompt`.
10. `./mvnw validate` passes; no inline FQNs in new code.

---

### 7. Traps

1. **`AuditHmac` recursion lands FIRST** (commit 1), before any nested map reaches `llmDetail`/`toolCalls`. Reversed order ships entries that fail their own HMAC on read-back; audit is write-once.
2. **Recurse into `List`, not only `Map`** — `toolCalls.calls` is a list of maps; `AuditStore.fromDocument` returns `List<Document>` whose `toString()` is `Document{…}`.
3. **Never `Map.of` for token counts** — only `LegacyChatExecutor:110` unguarded; leave `StreamingLegacyChatExecutor:297` and `AgentOrchestrator.tokenUsageMap:1036`.
4. **Accumulate across the sub-task loop** (`LlmTask:283-294`, last-write-wins).
5. **Accumulate across cascade steps** (`CascadingModelExecutor:387` reports accepted step only; `runCostUsd` is a run total — they disagree today).
6. **Reset `tokenHolder[0]` inside the retry lambda** (`AgentOrchestrator:870`) — live #593 double-count, not hypothetical. `RetryConfiguration.executeWithRetry` replays the lambda.
7. **`executeResume` must write `audit:compiled_prompt`** — `LifecycleManager:498` gates the entire `llmDetail` block on it. Resume path currently writes only `model_response`/`model_name` (`:915`/`:919`).
8. **`audit:confidence` must be a `Double`** — `LifecycleManager:467` declares `IData<Double>`; a `String` (what `LlmTask:560` does today) flows unchecked past erasure and fails in the Manager UI.
9. **Thread safety** — `LlmTask`/`AgentOrchestrator` are stateless singletons; accumulators are locals or memory-backed, **no instance fields**.
10. **`ToolCostTracker.getConversationCosts` returns null** (`:160-162`) — null-guard both snapshots; `Math.max(0.0, …)` the delta (`resetConversation:213` can fire between).
11. **Four `new AuditEntry(` sites now.** In scope: `LifecycleManager:517` only. Leave `CapabilityMatchCondition:179`, `GdprComplianceService:420`, **and the new `LifecycleManager:407` failure entry** (§2.3).
12. **Don't change null-handling of `modelResponse`/`modelName`** (`:502-507`); stricter guard for new keys only.
13. **`clean` before the CascadingModelExecutor/AuditEntry-consumer commit** — helper-signature change risks stale `.class` reuse (MEMORY: verify refactors with clean compile).

---

### 8. Out of scope

- **D2** (`ToolCostTracker.TOOL_COSTS` slug mismatch) — tool-cost numbers stay ~$0 until D2. Don't re-key `TOOL_COSTS`.
- **Hoisting cascade prices to task level** (§3 follow-up).
- **D3** (`langchain:trace:*` / dead SSE `toolTrace`) — `buildTaskSummary:462` stays; D1 routes trace to the ledger.
- **D9 / D13 / R5·D11.** No new Micrometer meters — `CascadingModelExecutor.recordStepMetrics:773-780` already meters cascade tokens (`eddi.llm.cascade.tokens`); don't extend.
- **`AuditEntry` record shape** — `llmDetail`/`toolCalls`/`cost` already exist (`:74-77`); `agentSignature` is the 20th field, pre-existing.
- **Task-failure `AuditEntry:407`** — deliberately keeps zeros (§2.3).
- **`ConfidenceEvaluator`** — #593-era rewrite, no audit keys.
- **Manager UI** — rollup works once data is non-zero.

---

### 9. Sequencing

| # | Commit | Scope | Gate |
|---|---|---|---|
| 1 | `fix(audit): recurse into nested maps and lists when canonicalizing for HMAC` | `AuditHmac` + `AuditHmacTest` | AC-2. **First.** |
| 2 | `fix(llm): null-safe token map and no token double-count on retry` | `LegacyChatExecutor:110-113`, `AgentOrchestrator:870` reset + tests | AC-4, `retriedLoopDoesNotDoubleCountTokens` |
| 3 | `feat(llm): accumulate cascade token usage and surface tool cost delta` | `CascadingModelExecutor` run-total tokens, `AgentOrchestrator` `toolCostUsd` (`:824`/`:468`) + tests | AC-7 (needs `clean`) |
| 4 | `fix(audit): write token usage, tool calls and cost into the audit ledger` | `LlmTask` (cascade re-key `:557`, agent-branch capture `:572`/`:595`, accumulators `:665`, `executeResume:913`), `LifecycleManager:497-523` + de-vacuumed tests | AC-3, AC-5, AC-6, AC-9 |
| 5 | `docs(changelog): D1 audit ledger token/cost/toolCalls` | `docs/changelog.md` (note failure-entry limitation) | AGENTS §2 rule 8, same branch |

`./mvnw compile` before every commit; `./mvnw clean test` before commit 4. Expect `mvnw` to reformat unrelated tracked `.java` — don't stage those.

I have everything verified against the current tree. Writing the replacement spec.

## D11+R5 — Complete `ObservableChatModel`, then `ChatModelListener` observability

---

### 1. Changes since PR #593

**Verified against the current working tree (branch `chore/langchain4j-1.18.0`).** #593 (`70091de90`) did **not** touch `ObservableChatModel.java` or `ChatModelRegistry.java` — both are byte-identical to the old-spec citations. It **did** rewrite `StreamingLegacyChatExecutor.java` (+214/-38), and that rewrite **partially invalidates the streaming half of D11's premise.**

| Old-spec / #587-baseline claim | Status in current tree |
|---|---|
| `ObservableChatModel` overrides only `chat(ChatRequest)` @ `:43-68`; two-arg form hits inherited `doChat` throw | **STILL TRUE, unchanged.** `chat(ChatRequest)` @ `:43-68`, `chatWithTimeout` @ `:70-83`, `wrapIfNeeded` @ `:95-116`, early return @ `:111-113`. |
| `ChatModelRegistry.getOrCreateStreaming` never wraps; `filterParams` strips `timeout`/`logRequests`/`logResponses` | **STILL TRUE, unchanged.** ctor @ `:55-61`, `@PostConstruct` @ `:69-81`, sync `wrapIfNeeded` @ `:111`, `getOrCreateStreaming` @ `:121-146` (no wrap), `filterParams` @ `:152-165`. |
| **"streaming has no timeout — a hung provider connection hangs up to the 120 s latch"** | **★ CLOSED by #593.** `StreamingLegacyChatExecutor` now honours a **configurable** `LlmConfiguration.Task.streamingTimeoutSeconds` (new field @ `LlmConfiguration:319`, getter `:686`), applied at `execute:152-155` as the `latch.await(timeoutSeconds, SECONDS)` bound (`:203`), default `DEFAULT_TIMEOUT_SECONDS = 120` (`:34`). |
| `StreamingLegacyChatExecutor` is where streaming `.chat()` is invoked | **CONFIRMED SOLE INVOKER.** `streamingModel.chat(chatRequest, handler)` @ `:176` is the only streaming `.chat()` call in `src/main`. Its two upstreams — `LlmTask:607→609` and `CascadingModelExecutor:286→573` — both funnel through it, so the latch bounds **every** streaming turn. |
| `AgentExecutionHelper.executeChatWithRetry` @ `:92`, inline FQNs present | **STALE.** #593 gutted it to a thin shim: `executeChatWithRetry` @ `:39-44`, `executeWithRetry`→`RetryConfiguration.executeWithRetry` @ `:33`. **No inline FQNs remain** — the old spec's "clean the FQNs" side-task is already done. |

**What this means for the item — the streaming half INVERTS.**

The old spec grew the streaming half ("the streaming half gets slightly larger") and specced a `WatchdogHandler` + static `ScheduledExecutorService` + `AtomicBoolean` double-termination guard implementing first-token / inter-token timeout inside `ObservableStreamingChatModel`. **That is now superseded.** #593 owns streaming timeout via `streamingTimeoutSeconds`. Adding a decorator watchdog would create a **second, competing, configurable streaming-timeout knob** (`timeout` param vs `streamingTimeoutSeconds`) — a config-clarity regression. 

**Decision (HOW, not re-litigating the WHAT): drop the watchdog. `ObservableStreamingChatModel` becomes observability-only** — fire `ChatModelListener` on the streaming path (R5 uniformity) and honour `logRequests`/`logResponses` for streaming (the still-open D11 logging gap). This **shrinks** the item vs. the old spec: no scheduler, no `AtomicBoolean`, no timeout math, no double-termination guard.

**What #593 did NOT fix (still fully in scope):**
- `ObservableChatModel` decorator is still incomplete → **zero `ChatModelListener` dispatch on any path.** R5 blocked.
- `getOrCreateStreaming` still never wraps → streaming turns emit **no** `gen_ai.*` metrics and silently discard `logRequests`/`logResponses`.
- Non-cascade LLM turns still emit **zero** LLM meters. #587 gave the cascade path 9 `eddi.llm.cascade.*` meters (`CascadingModelExecutor:773-783`, e.g. `eddi.llm.cascade.tokens:780`); everything else is dark. **R5 is "make it uniform", not "make it exist."**

**No new same-class defect** was introduced by #593 (the cascade still routes through the sole latch-bounded streaming executor).

---

### 2. Files to touch

#### MODIFY — `modules/llm/impl/ObservableChatModel.java`  *(unchanged from old spec — sync half is fully valid)*

Delete `chat(ChatRequest)` (`:43-68`), replace with `doChat`; add contract methods:

```java
@Override public ChatResponse doChat(ChatRequest chatRequest)   // body = old chat() @ :44-67 verbatim
@Override public List<ChatModelListener> listeners()            // return this.listeners
@Override public ModelProvider provider()                       // delegate.provider()
@Override public Set<Capability> supportedCapabilities()        // delegate.supportedCapabilities()
@Override public ChatRequestParameters defaultRequestParameters() {
    var p = delegate.defaultRequestParameters();
    return p != null ? p : DefaultChatRequestParameters.EMPTY;  // MANDATORY null-guard — §7 trap 1
}
```

`chatWithTimeout` (`:70-83`) unchanged, **including** `new RuntimeException(e.getCause())` @ `:78` (R4 territory — leave it). Ctor (`:35`) gains `List<ChatModelListener> listeners`; field `private final List<ChatModelListener> listeners = listeners == null ? List.of() : List.copyOf(listeners);`. Rename `wrapIfNeeded`→`wrap` (6-arg), **delete early return `:111-113`** (always wrap). Only callers are `ChatModelRegistry:111` + `ObservableChatModelTest` (grep-confirmed).

Add imports (simple names, AGENTS.md §4.7): `dev.langchain4j.model.ModelProvider`, `dev.langchain4j.model.chat.Capability`, `dev.langchain4j.model.chat.listener.ChatModelListener`, `dev.langchain4j.model.chat.request.ChatRequestParameters`, `dev.langchain4j.model.chat.request.DefaultChatRequestParameters`, `java.util.List`, `java.util.Set`.

#### CREATE — `modules/llm/impl/ObservableStreamingChatModel.java`  *(★ simplified — observability-only, no watchdog)*

```java
class ObservableStreamingChatModel implements StreamingChatModel {

    ObservableStreamingChatModel(StreamingChatModel delegate, String modelType,
                                 boolean logRequests, boolean logResponses,
                                 List<ChatModelListener> listeners);   // NO Duration params

    @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler);
    @Override public List<ChatModelListener> listeners();               // this.listeners
    @Override public ModelProvider provider();                          // delegate.provider()
    @Override public ChatRequestParameters defaultRequestParameters();  // null-guarded, as sync
    @Override public Set<Capability> supportedCapabilities();

    static StreamingChatModel wrap(StreamingChatModel model, String modelType,
                                   String logReq, String logResp, List<ChatModelListener> listeners); // ALWAYS wraps
}
```

`doChat` contract (no timeout, no scheduler, no `AtomicBoolean`):
1. If `logRequests`: log request (mirror `ObservableChatModel:45-49`).
2. `delegate.chat(request, effectiveHandler)` where `effectiveHandler = logResponses ? new LoggingHandler(handler, modelType) : handler`.
3. Listeners fire automatically: the executor calls the decorator's **default** `chat(request, handler)` → default `chat(request, EMPTY, handler)` fires `listeners()` `onRequest`/`onResponse`/`onError` (verified `StreamingChatModel.java:42-125`), then calls our `doChat`.

`LoggingHandler` (private static) **must override all 10 `StreamingChatResponseHandler` methods and forward each** — copy the exact forwarding shape of lc4j's own `observingHandler` (`StreamingChatModel.java:66-122`); log only inside `onCompleteResponse`/`onError`. Overriding a subset silently drops thinking/tool-call/raw events (§7 trap 3). No synchronous-throw catch needed inside `doChat` (delegate.chat is a plain streaming dispatch; the R5 `onError` listener path is covered by the interface default's observingHandler, and StreamingLegacyChatExecutor already catches on its side).

#### MODIFY — `modules/llm/impl/ChatModelRegistry.java`

| Line | Change |
|---|---|
| `:55-61` ctor | add `Instance<ChatModelListener> chatModelListeners` param; store it |
| `:69-81` `registerSecretInvalidation()` (`@PostConstruct`) | resolve **once**: `this.listeners = chatModelListeners.stream().toList();` into a new `private List<ChatModelListener> listeners` field |
| `:111` | `var model = ObservableChatModel.wrap(rawModel, type, timeoutMs, logReq, logResp, listeners);` |
| `:121-146` `getOrCreateStreaming` | hoist `logReq`/`logResp` from `processedParams` **before** `filterParams` (mirror `:92-93`); **inside the try, after `buildStreaming` @ `:139`**: `var model = ObservableStreamingChatModel.wrap(raw, type, logReq, logResp, listeners); streamingModelCache.put(cacheKey, model); return model;` — never wrap the `null` returned on `UnsupportedOperationException` (`:142-145`, §7 trap 4) |
| `:152-165` `filterParams` | **no change** (trap: un-stripping `timeout` changes the cache key and activates dead provider-native branches) |

Note: streaming does **not** hoist `timeout` — the streaming decorator has no timeout. Imports: `dev.langchain4j.model.chat.listener.ChatModelListener`, `jakarta.enterprise.inject.Instance`.

#### CREATE — `modules/llm/observability/GenAiMetricsChatModelListener.java`  *(package is new)*

```java
@ApplicationScoped
public class GenAiMetricsChatModelListener implements ChatModelListener {
    static final String ATTR_START_NANOS   = "eddi.gen_ai.start_nanos";
    public static final String ATTR_AGENT_ID       = "eddi.gen_ai.agent_id";
    public static final String ATTR_LLM_TASK_ID    = "eddi.gen_ai.task_id";
    public static final String ATTR_OPERATION_NAME = "eddi.gen_ai.operation";

    @Inject GenAiMetricsChatModelListener(MeterRegistry meterRegistry,
        @ConfigProperty(name = "eddi.llm.metrics.enabled", defaultValue = "true") boolean enabled);

    @Override public void onRequest(ChatModelRequestContext ctx);   // put ATTR_START_NANOS = nanoTime()
    @Override public void onResponse(ChatModelResponseContext ctx);
    @Override public void onError(ChatModelErrorContext ctx);
}
```

Context accessors verified in 1.18.0 sources: `ChatModelRequestContext.chatRequest()/modelProvider()/attributes()`; `ChatModelResponseContext.chatResponse()/chatRequest()/modelProvider()/attributes()`; `ChatModelErrorContext.error()/chatRequest()/modelProvider()/attributes()`. `attributes()` returns `Map<Object,Object>`.

| Meter | Type | Source | Tags |
|---|---|---|---|
| `gen_ai.client.token.usage` | `DistributionSummary`, `baseUnit("token")` | `ctx.chatResponse().metadata().tokenUsage().inputTokenCount()`/`.outputTokenCount()` — both `Integer`; null-guard `metadata()`, `tokenUsage()`, each count | `gen_ai.system`, `gen_ai.operation.name`, `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.token.type`∈{`input`,`output`} |
| `gen_ai.client.operation.duration` | `Timer` | `nanoTime() − (Long) ctx.attributes().get(ATTR_START_NANOS)` (skip if null) | same minus `gen_ai.token.type`, plus `error.type` (`none` \| `ctx.error().getClass().getSimpleName()`) |

Tag values: `gen_ai.system` ← `ctx.modelProvider()` lower-cased (`OTHER` for delegates not overriding `provider()`); `gen_ai.request.model` ← `ctx.chatRequest().parameters().modelName()` (`"unknown"` on null); `gen_ai.response.model` ← `ctx.chatResponse().metadata().modelName()` (fall back to request model); `gen_ai.operation.name` ← `(String) ctx.attributes().getOrDefault(ATTR_OPERATION_NAME, "chat")`. **When `enabled==false`, all three callbacks return immediately.** Stateless (`@ApplicationScoped`): start-nanos lives in the attributes map, never a field.

**Cardinality (hard):** never tag `conversationId`/`userId`/`agentId`/prompt text. `ATTR_AGENT_ID` is for future span/log enrichment only.

#### CREATE — `modules/llm/observability/GenAiListenerAttributes.java`  *(plumbing — commit 5, droppable)*

```java
public final class GenAiListenerAttributes {
    public static ChatRequestOptions forTask(IConversationMemory memory,
                                             LlmConfiguration.Task task, String operation);
}
```
Builds `ChatRequestOptions.builder().addListenerAttribute(ATTR_AGENT_ID, memory.getAgentId()).addListenerAttribute(ATTR_LLM_TASK_ID, task.getId()).addListenerAttribute(ATTR_OPERATION_NAME, operation).build()`. Null-safe on `memory`/`task` → `ChatRequestOptions.EMPTY`. `ChatRequestOptions` is in **`dev.langchain4j.model.chat`** (verified); `builder().addListenerAttribute(Object,Object)` + `EMPTY` confirmed. `memory.getAgentId()` confirmed (`IConversationMemory`, used @ `AgentOrchestrator:1552`). `task.getId()` confirmed (`LlmConfiguration:366`).

#### MODIFY — plumbing to pass listener attributes  *(commit 5 — highest churn, lowest value, droppable)*

| File | Current | Edit |
|---|---|---|
| `AgentExecutionHelper.java` | shim `executeChatWithRetry(chatModel, messages, task)` @ `:39-44` | add overload `executeChatWithRetry(chatModel, messages, task, ChatRequestOptions options)` → `executeWithRetry(() -> chatModel.chat(ChatRequest.builder().messages(messages).build(), options), task, "Chat model execution")`; 3-arg delegates with `EMPTY` |
| `LegacyChatExecutor.java` | 4-arg `execute` @ `:69`; json `chatModel.chat(requestBuilder.build())` @ `:79`; non-json `executeChatWithRetry(...)` @ `:85`,`:88` | add trailing `ChatRequestOptions options`; `:79`→`.chat(requestBuilder.build(), options)`; `:85`/`:88`→4-arg `executeChatWithRetry`; 3-arg `execute` @ `:50` passes `EMPTY` |
| `LlmTask.java` | `getOrCreate` @ `:466`; sync `legacyChatExecutor.execute(...)` @ `:580`,`:615`,`:624` | build `var opts = GenAiListenerAttributes.forTask(memory, task, task.isAgentMode() ? "agent" : "chat");` after `:466`; pass to the three sync `execute` calls |
| `AgentOrchestrator.java` | `runToolCallLoop` @ `:852` (from `:821` live, `:458` HITL resume); `chatModel.chat(chatRequest)` @ `:904` | **no signature change** — `runToolCallLoop` already has `memory`+`task`; build opts once before the loop, `:904`→`chatModel.chat(chatRequest, opts)` |
| `CascadingModelExecutor.java` | `executeLegacyModeStep` @ `:553`; sync `legacyChatExecutor.execute(chatModel, messages, task, jsonMode)` @ `:579`; also fallback call @ `:615` | thread `ChatRequestOptions options` from `doExecute`(has `memory`) through `executeStepWithTimeout` @ `:515/:528` into `executeLegacyModeStep`; forward to `:579`; `operation="cascade"`. Commits 1–4 leave this at `EMPTY` (still fully metered via decorator, `operation.name=chat`) |

**Streaming attribute plumbing is out of scope** (see §8): `StreamingLegacyChatExecutor:176` calls the 2-arg `chat(request, handler)`, so streaming turns get metrics with `operation.name=chat` and no agent/task attributes. Threading `ChatRequestOptions` into `execute`/`executeCapturing`→`chat(request, options, handler)` is a follow-up.

#### MODIFY — docs
- `docs/langchain.md` `:126`,`:129` — note `logRequests`/`logResponses`/`timeout` behaviour; add a row documenting `streamingTimeoutSeconds` (added by #593, currently **undocumented** in the table) and that it — not `timeout` — bounds the streaming path.
- `docs/changelog.md` — one entry per commit, same branch (AGENTS.md §2 rule 8).

---

### 3. Config surface

No new JSON field, no POJO change, no `ExtensionDescriptor` change. `timeout`/`logRequests`/`logResponses` remain free-form `Task.parameters` entries. `streamingTimeoutSeconds` already exists (typed `Task` field, #593).

| New deployment property | Type | Default | Effect |
|---|---|---|---|
| `eddi.llm.metrics.enabled` | boolean | `true` | `false` → `GenAiMetricsChatModelListener` short-circuits all callbacks. Add to `application.properties` with a comment. |

Default `true` is a pure addition (new `/q/metrics` series, no behaviour change).

---

### 4. Stored-config back-compat

| Stored config | After deploy |
|---|---|
| No observability params (common) | Model **always** wrapped now. `timeout==null`, both log flags false ⇒ sync `doChat`=`delegate.chat` + two `if(false)`; streaming `doChat`=`delegate.chat(req,handler)`. Byte-identical output; one extra frame + the interface-default request rebuild (`defaultRequestParameters().overrideWith(...)` — no-op merge). |
| `timeout: "15000"`, non-streaming | Unchanged — `chatWithTimeout` via `Future.get`. |
| `timeout: "15000"`, **streaming** | **No behaviour change from this item.** `timeout` still does not reach the streaming path (by design — dropped watchdog). Streaming bound is `streamingTimeoutSeconds` (#593). This is the corrected position vs. the old spec, which would have made 15 s abort streaming. |
| `logRequests`/`logResponses: "true"`, streaming | **New:** now emit for streaming turns (previously silently discarded — the D11 logging gap). |
| ZIP import / MongoDB JSON | Unaffected — no bound type gains/loses a field. |

**Identity leak:** `getOrCreate` returns `ObservableChatModel` for every type; `getOrCreateStreaming` returns `ObservableStreamingChatModel` for every streaming-capable type. Grep: no production code narrows a model to a provider concrete type; only tests carry `instanceof`/`assertSame` couplings (§5). `LlmAgentEngineIT` references `ObservableChatModel` in a javadoc comment only.

---

### 5. Tests

**Existing (verified):** `ObservableChatModelTest.java` (273 lines, 27 `wrapIfNeeded`/`assertSame` hits — rewrite); `ChatModelRegistryTest.java` (391 lines, 11 hits — 3 tests break, ctor gains 4th param); `LlmTaskCoverage{,2}Test`, `CascadingModelExecutor{Test,CoverageTest,ExecuteTest,EnterpriseTest}` (compile-check after signature changes, commit 5 only); `LlmAgentEngineIT` (comment-only, CI-only).

**Break → fix:**

| Test | Why | Fix |
|---|---|---|
| `ObservableChatModelTest.WrapIfNeeded.*_returnsOriginalModel`/`*_noWrapping` | `assertSame` — always-wrap | invert to `assertInstanceOf(ObservableChatModel.class, …)`, rename `…_stillWraps` |
| every `wrapIfNeeded(...)` call in `ChatDelegation`/`TimeoutBehavior`/`MultipleInvocations` | renamed + 6th arg | mechanical → `ObservableChatModel.wrap(delegate, type, …, List.of())` |
| `ChatModelRegistryTest.SyncTests.getOrCreate_validType_createsModel` `assertSame` | always-wrap | `assertInstanceOf(ObservableChatModel.class, …)` |
| `ChatModelRegistryTest.StreamingTests.getOrCreateStreaming_supportedType_createsModel` `assertSame` | always-wrap | `assertInstanceOf(ObservableStreamingChatModel.class, …)` |
| `ChatModelRegistryTest.ObservabilityTests.getOrCreate_noObservabilityParams_returnsRawModel` | always-wrap | rename `…returnsWrappedModel`, `assertInstanceOf` |
| `ChatModelRegistryTest` `@BeforeEach` ctor | ctor gains 4th param | pass a mocked `Instance<ChatModelListener>` whose `stream()` yields the listener (or empty) |

**Vacuity guard (do not loosen):** stubs `when(delegate.chat(request)).thenReturn(...)` then `verify(delegate).chat(request)` still match after `doChat` override, because the inherited two-arg default rebuilds `ChatRequest` with `defaultRequestParameters().overrideWith(...)`, and `ChatRequest.equals` is value-based on `messages`+`parameters` with `EMPTY.overrideWith(EMPTY)` value-equal — **only if §7 trap 1's null-guard is present.**

**New — `ObservableChatModelTest` (nested):** `DecoratorContract.doChat_isOverridden_twoArgChatDoesNotThrowNotImplemented()`, `provider_delegatesToUnderlying()`, `defaultRequestParameters_delegatesToUnderlying()`, `defaultRequestParameters_nullDelegate_returnsEmpty()`, `supportedCapabilities_delegatesToUnderlying()`, `listeners_returnsInjectedList()`/`listeners_isImmutable()`; `ListenerDispatch.chat_firesOnRequestThenOnResponse()` (`InOrder`), `chat_delegateThrows_firesOnErrorAndRethrows()`, `listenerAttributes_reachOnRequestContext()`, `throwingListener_doesNotBreakChat()` (pins `ChatModelListenerUtils:37` swallow-and-warn).

**New — `ObservableStreamingChatModelTest` (CREATE):** `wrap_alwaysReturnsWrapper()`; `doChat_forwardsPartialResponsesToHandler()`; `doChat_forwardsAllHandlerCallbackVariants()` (drives all 8 non-terminal callbacks; each reaches delegate handler exactly once — guards the `default`-method drop trap); `logResponses_logsOnCompleteResponse()`; `listeners_firedOnCompleteResponse()`/`listeners_firedOnError()` (via 2-arg `chat(request, handler)` default); `provider_delegatesToUnderlying()`. **No timeout/watchdog tests** (dropped). No sockets/Docker; latch/`Awaitility`; runs locally.

**New — `GenAiMetricsChatModelListenerTest` (CREATE, `SimpleMeterRegistry`):** `onResponse_emitsInputAndOutputTokenSummaries()`, `onResponse_emitsOperationDurationWithErrorTypeNone()`, `onError_emitsOperationDurationWithErrorTypeClassName()`, `onResponse_nullTokenUsage_emitsNoTokenMeters()`/`_nullMetadata_…`, `tags_neverContainConversationId()` (walk `getMeters()`, no tag key matches `(?i)conversation|user|agent`), `onResponse_withoutPriorOnRequest_doesNotEmitDuration()`, `disabled_emitsNothing()`, `operationName_fromListenerAttribute()`.

**New — `ChatModelRegistryTest`:** `StreamingTests.getOrCreateStreaming_wrapsWithObservableStreaming()`, `getOrCreateStreaming_sameParams_returnsSameWrapper()` (cached), `getOrCreateStreaming_unsupportedBuilder_returnsNullNotWrapper()` (§7 trap 4); `ObservabilityTests.cdiListeners_arePassedToBothDecorators()`, `listenersResolvedOnce_notPerGetOrCreate()`.

**New — `GenAiListenerAttributesTest`:** `forTask_nullMemory_returnsEmpty()`, `forTask_populatesAgentTaskAndOperation()`.

---

### 6. Acceptance criteria

1. `./mvnw clean compile` succeeds (clean — type-signature refactor; incremental reuses stale `.class`).
2. `grep -n "public ChatResponse chat(ChatRequest" …/ObservableChatModel.java` → no match; `grep -c "doChat" …` → exactly one `@Override`.
3. `grep -rc "wrapIfNeeded" src/main/java src/test/java` → 0.
4. `grep -n "ObservableStreamingChatModel.wrap" …/ChatModelRegistry.java` matches **inside** the `getOrCreateStreaming` try block.
5. `./mvnw test -Dtest=ObservableChatModelTest` green incl. `doChat_isOverridden_twoArgChatDoesNotThrowNotImplemented`.
6. `./mvnw test -Dtest=ObservableStreamingChatModelTest` green.
7. `./mvnw test -Dtest=GenAiMetricsChatModelListenerTest` green incl. `tags_neverContainConversationId`.
8. `./mvnw test -Dtest=ChatModelRegistryTest` green.
9. `./mvnw test -Dtest=LlmTaskCoverageTest` and `-Dtest=CascadingModelExecutorCoverageTest` green (no signature drift).
10. `./mvnw test` full unit suite green; JaCoCo instruction ≥90 % for `ObservableChatModel`, `ObservableStreamingChatModel`, and `…modules.llm.observability`.
11. `./mvnw validate` clean; `./mvnw formatter:format` no diff beyond intended edits.
12. Dev mode, `"logResponses":"true"` + SSE streaming: log shows a response line for the **streaming** turn (today never appears).
13. Dev mode, one non-cascade LLM turn: `curl -s localhost:7070/q/metrics | grep gen_ai` returns non-zero `gen_ai_client_token_usage` and `gen_ai_client_operation_duration` (Prometheus `.`→`_`).
14. Same curl with `eddi.llm.metrics.enabled=false` → nothing.
15. Dev mode, `"streamingTimeoutSeconds": 3` against a stalled streaming provider: the turn fails in ~3 s. *(Bound is `streamingTimeoutSeconds`, not `timeout` — corrected vs old spec.)*
16. `docs/changelog.md` entry on the same branch naming D11 and R5, the streaming-logging behaviour change, the `eddi.llm.cascade.tokens` overlap note, and that streaming timeout is `streamingTimeoutSeconds` (#593), not the decorator.

---

### 7. Traps

1. **`defaultRequestParameters()` MUST null-guard.** A Mockito mock returns `null`; the inherited two-arg default dereferences `defaultRequestParameters().overrideWith(...)` and NPEs before the delegate runs — breaks every existing `ObservableChatModelTest`. Return `DefaultChatRequestParameters.EMPTY` on null; in production return the delegate's real params so `gen_ai.request.model` populates.
2. **`ChatRequestOptions` is in `dev.langchain4j.model.chat`**, not `…request`. `Capability` is `dev.langchain4j.model.chat.Capability`. Both verified.
3. **Streaming handler has 10 methods (8 `default` + 2 abstract).** `LoggingHandler` must override and forward **all 10** (copy `StreamingChatModel.java:66-122`). A subset drops thinking/tool-call/raw events.
4. **`getOrCreateStreaming` returns `null`** when `buildStreaming` throws `UnsupportedOperationException` (`:142-145`). Wrap **inside** the try after `buildStreaming` — never wrap null.
5. **`ChatModelListenerUtils` is `@Internal`, package-private** (verified `:18-19`). No hand-rolled dispatch — dispatch comes only from overriding `doChat` and letting the interface default fire listeners.
6. **`listeners()` returns a pre-resolved immutable list.** Resolve `Instance.stream().toList()` once in `@PostConstruct`; never call CDI from the callback thread. `List.copyOf` in the ctor.
7. **Streaming timeout is `streamingTimeoutSeconds` (#593), not the decorator.** Do **not** re-add a decorator watchdog — it would duplicate the sole latch bound and create a second config knob. `CascadingModelExecutor.STREAMING_STEP_TIMEOUT_MS = 125_000` (`:68`, applied `:300`) and `StreamingLegacyChatExecutor.DEFAULT_TIMEOUT_SECONDS = 120` (`:34`) are unchanged and out of scope.
8. **Metric cardinality.** Never tag `conversationId`/`userId`/`agentId`. `error.type` uses `getSimpleName()`, never `getMessage()`.
9. **`filterParams` stays as-is** (`:152-165`). Un-stripping `timeout` changes the sync cache key and activates dead provider-native `KEY_TIMEOUT` branches.
10. **`ObservableChatModel:78** `new RuntimeException(e.getCause())` — leave it (R4 territory).
11. **`eddi.llm.cascade.tokens` double-counts** `gen_ai.client.token.usage` on cascade turns. Intentional (per-cascade-step vs per-model-call). Do not remove #587's meter.
12. **Delegate double-dispatch.** `doChat`→`delegate.chat(request)` runs the delegate's own (empty) listener chain. Zero `ChatModelListener` refs in `…impl/builder/` today; document.
13. **Thread safety.** Both decorators are cached in `ConcurrentHashMap` and shared across conversations — no per-call state on the decorator; per-call state lives in `ChatRequestOptions.listenerAttributes()` (interface default copies it into a fresh `ConcurrentHashMap` per invocation).
14. **Surefire nested-test filter.** `-Dtest=Class#method` runs 0 tests (false green) for `@Nested` methods — filter by whole class.
15. **`mvnw` auto-formats** tracked `.java` — expect spurious diffs, don't commit them.

---

### 8. Out of scope

- **Streaming watchdog / first-token / inter-token timeout** — superseded by #593's `streamingTimeoutSeconds`. Any finer-grained inter-token semantics belong as a follow-up on `StreamingLegacyChatExecutor`'s latch, not the decorator.
- **Streaming listener-attribute plumbing** — `StreamingLegacyChatExecutor:176` uses the 2-arg `chat(request, handler)`; streaming turns get metrics with `operation.name=chat` and no agent/task attributes. Threading `ChatRequestOptions` through `execute`/`executeCapturing`→`chat(request, options, handler)` is a follow-up.
- `filterParams` and dead provider-native `KEY_TIMEOUT` branches.
- `StreamingLegacyChatExecutor.DEFAULT_TIMEOUT_SECONDS`, `CascadingModelExecutor.STREAMING_STEP_TIMEOUT_MS`.
- `ObservableChatModel:78` exception wrapping (R4).
- `EmbeddingModel` listener support / D13 (after R5).
- OTel **spans**, Grafana dashboards, `langchain4j-micrometer-metrics` / `langchain4j-observation` artifacts (rejected in decision §9).
- Agent-mode streaming (I3); a distinct `streamingInterTokenTimeout` param; adding `timeout`/log flags to any `ExtensionDescriptor`.
- `ToolResponseTruncator`, `SummarizationService`, `ConfidenceEvaluator:254`, `CascadingModelExecutor:267/:468` call-site attribute plumbing (metered for free via the decorator).

---

### 9. Sequencing

| # | Commit | Content | Independently green |
|---|---|---|---|
| 1 | `fix(llm): complete ObservableChatModel decorator contract` | `doChat` + `listeners`/`provider`/`defaultRequestParameters`(null-guarded)/`supportedCapabilities`; ctor gains `listeners`; `wrapIfNeeded`→`wrap` (6-arg, `List.of()`, **early return still present**). Rewrite `ObservableChatModelTest` `DecoratorContract`+`ListenerDispatch`; mechanical `wrap(...)` updates. | ✅ |
| 2 | `fix(llm): always wrap chat models, add streaming observability decorator` | drop sync early return; **simplified** `ObservableStreamingChatModel` (observability-only, no watchdog); `getOrCreateStreaming` wiring. Update `WrapIfNeeded`+`ChatModelRegistryTest`; new `ObservableStreamingChatModelTest`. **D11 payload — closes the streaming logging gap; streaming timeout already closed by #593.** | ✅ |
| 3 | `feat(llm): discover ChatModelListener beans via CDI` | `Instance<ChatModelListener>` in registry, resolved once in `@PostConstruct`, passed to both decorators. New registry tests. Zero listeners ⇒ no runtime change. | ✅ |
| 4 | `feat(llm): OpenTelemetry GenAI metrics via ChatModelListener` | `GenAiMetricsChatModelListener` + `eddi.llm.metrics.enabled` + `application.properties` + tests. **R5 payload — every LLM call on every path metered.** | ✅ |
| 5 | `feat(llm): pass conversation context via ChatRequestOptions listener attributes` | `GenAiListenerAttributes`; `AgentExecutionHelper`/`LegacyChatExecutor`/`CascadingModelExecutor.executeLegacyModeStep` overloads; `LlmTask` + `AgentOrchestrator:904` call sites. Highest churn, droppable without losing 1–4. | ✅ |
| 6 | `docs(llm): streaming timeout + gen_ai metrics` | `docs/langchain.md` (+`streamingTimeoutSeconds` row), `docs/changelog.md`. | ✅ |

Effort: **S** for 1–4 (streaming half shrank — no scheduler/watchdog), **M** for 5. ~300 lines production + ~600 lines test (down from old spec's ~380+~700 — the dropped watchdog machinery is the delta).

**Key correction vs. old spec:** the streaming decorator is **observability-only**. #593 closed the streaming-timeout hole via `streamingTimeoutSeconds`; re-adding a watchdog would regress config clarity. Files verified against the current tree at the line numbers cited above.

## R4 — Retry classification via `HttpException.statusCode()`

### 1. Changes since PR #593

**#593 implemented ~70% of this item. The premise survives; the spec shrinks to two edits.** Verified against the current working tree (branch `chore/langchain4j-1.18.0`).

| Old (pre-#593) Part II spec said | Current tree | Impact on spec |
|---|---|---|
| CREATE new `modules/llm/impl/RetryClassifier.java` (Tier/Category/Classification record ladder) | **Obsolete.** #593 extracted a shared classifier `ai.labs.eddi.configs.shared.RetryConfiguration.isRetryableError(Exception)` (`:180-214`). | **No new class.** Extend the existing boolean method. |
| MODIFY `AgentExecutionHelper` — delete enum + method, delegate at `:64` | **Already done.** `AgentExecutionHelper` is a thin shim (`:32-34`): `executeWithRetry` → `RetryConfiguration.executeWithRetry(action, task.getRetry(), desc)`. Old `RetryableErrorType` enum + substring classifier GONE. | **Zero changes to `AgentExecutionHelper`.** The main retry path already routes through the shared classifier — editing `RetryConfiguration.isRetryableError` is the entire main-path fix. |
| MODIFY `LlmConfiguration.RetryConfiguration` (`:902-939`) add field | **Nested class gone.** `LlmConfiguration.Task.getRetry()` (`:558`) now returns `ai.labs.eddi.configs.shared.RetryConfiguration`. No `retryOn`/`categories`/`retryOnUnclassified` fields exist. | Config lives in the new shared class. This increment adds **no** field (see §3). |
| #593 added typed-JDK tier + WAE branch to shared | Confirmed: shared `isRetryableError` has typed-JDK (`:185-190`) + `WebApplicationException` `{429,502,503,504}` (`:193-198`) + substring (`:201-208`). | Tier 3/4/5 already present. **What remains: tier 1 (`RetriableException`/`NonRetriableException`) + tier 2 (`HttpException.statusCode()`).** |

**Did #593 introduce a new instance of the defect class?** Yes — partially. It left the cascade copy divergent **in both directions**:

| | shared `RetryConfiguration.isRetryableError` `:180` | cascade `CascadingModelExecutor.isRetryableError` `:804` |
|---|---|---|
| typed JDK (4 types) | ✅ `:185-190` | ✅ `:807-808` (identical) |
| `WebApplicationException` status | ✅ `{429,502,503,504}` `:193-198` | ❌ absent |
| substring set | `timeout`/`rate limit`/`too many requests`/`connection refused`/`connection reset`/`service unavailable`/`bad gateway`/`gateway timeout` `:203-206` | regex `timeout\|rate limit\|too many requests\|429\|50[234]` `:798` |
| typed lc4j (`Retriable`/`Non`/`HttpException`) | ❌ | ❌ |

Neither uses `dev.langchain4j.exception.*` — `grep -rn "dev.langchain4j.exception" src/main/java` → **0 hits**. This is the untouched core of R4.

**langchain4j-core 1.18.0 verified** (`javap` on the resolved jar):
- `public class HttpException extends LangChain4jException { public int statusCode(); }` — public API, **not** `RetriableException`/`NonRetriableException`.
- `RetriableException` ← `RateLimitException`, `TimeoutException`, `InternalServerException`.
- `NonRetriableException` ← `AuthenticationException`, `InvalidRequestException`, `ModelNotFoundException`.

**Premise intact:** cascade classifier still has exactly **one** call site (`:436`) and still governs only a **label** (trace `status` `:438`, two Micrometer tags `:443-444`, SSE `onCascadeEscalation` reason `:450`) — **it does not gate cascade retry.** Cascade retry runs inside the shared `executeWithRetry` reached via `LegacyChatExecutor`/`AgentOrchestrator`.

---

### 2. Files to touch

| Path | Action |
|---|---|
| `src/main/java/ai/labs/eddi/configs/shared/RetryConfiguration.java` | **MODIFY** — add tier 1 + tier 2 to `isRetryableError` (`:180-214`); add 3 imports |
| `src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java` | **MODIFY** — delete `RETRYABLE_MESSAGE` (`:797-798`) + `isRetryableError` (`:800-818`); drop `import java.util.regex.Pattern` (`:32`); add `import ai.labs.eddi.configs.shared.RetryConfiguration`; replace call at `:436` |
| `src/test/java/ai/labs/eddi/configs/shared/RetryConfigurationTest.java` | **MODIFY** — append tier 1/2 tests to `IsRetryableErrorTests` (additions only) |
| `src/test/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutorCoverageTest.java` | **MODIFY** — append `HttpException`/`WebApplicationException` label tests (additions only) |
| `docs/changelog.md` | **MODIFY** — AGENTS.md §2 rule 8 |

**No CREATE, no DELETE files. `AgentExecutionHelper.java`, `LlmConfiguration.java`, `StreamingLegacyChatExecutor.java`, `LlmTask` extension descriptor — untouched.**

#### 2.1 MODIFY `RetryConfiguration.isRetryableError` (`:180-214`)

Add three top-level imports (AGENTS.md §4.7 — proper imports; no name collision, so no inline FQN needed):

```java
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
```

Insert tiers 1–2 at the **top of the `while` loop body, before** the existing typed-JDK block (`:185`). Per-link, first hit wins, then descend:

```java
while (current != null) {
    // Tier 1: typed langchain4j classification (authoritative). Covers Azure,
    // Bedrock, Vertex Gemini which throw Retriable/NonRetriable subclasses.
    if (current instanceof RetriableException) {
        return true;                       // RateLimit/Timeout/InternalServer
    }
    if (current instanceof NonRetriableException) {
        return false;                      // Auth/InvalidRequest/ModelNotFound — fail fast now
    }

    // Tier 2: HTTP status from langchain4j HttpException (extends LangChain4jException
    // directly). The tier that actually covers OpenAI/Anthropic/Ollama/Mistral/Gemini
    // on the SYNC path, which throw raw HttpException. Log statusCode() only — the
    // message is the raw response body.
    if (current instanceof HttpException he) {
        int status = he.statusCode();
        if (status == 408 || status == 429 || status >= 500) {
            return true;
        }
        if (status >= 400) {
            return false;                  // other 4xx (401/403/404/...) fail fast
        }
        // status < 400: no HTTP-based decision — fall through
    }

    // Tier 3 (existing :185-190): typed JDK
    // Tier 4 (existing :193-198): WebApplicationException {429,502,503,504}
    // Tier 5 (existing :201-208): substring fallback
    ...
    current = current.getCause();
}
return false;
```

- Keep the existing `WebApplicationException` branch **verbatim at `{429,502,503,504}`** — do **not** widen to `>=500` (avoids a main-path behavior change; it is a JAX-RS-internal path distinct from `HttpException`). The `>=500` table applies to `HttpException` only. Note the deliberate asymmetry in the changelog.
- **Substring set unchanged.** Do **not** import the cascade regex's bare-number tokens `429`/`50[234]` into the shared set — tier 2 supersedes them typologically, and bare-number `contains` would add false positives the main path never had (e.g. `"5040 tokens"`). Documented loss: a *wrapped, untyped, message-only* `"HTTP 503"` with no textual trigger stops matching; a real `HttpException(503)` still matches at tier 2. Accept.

#### 2.2 MODIFY `CascadingModelExecutor` (delete copy, delegate)

- DELETE `RETRYABLE_MESSAGE` (`:797-798`, incl. comment) and `private static boolean isRetryableError` + javadoc (`:800-818`).
- DELETE `import java.util.regex.Pattern;` (`:32`) — verified only two `Pattern` uses (`:32`, `:798`), both removed.
- ADD `import ai.labs.eddi.configs.shared.RetryConfiguration;` (currently **not** imported — verified).
- Replace `:436`:
```java
String errorType = RetryConfiguration.isRetryableError(e) ? "retryable_error" : "error";
```
- The `catch (TimeoutException e)` at cascade `:367` is `java.util.concurrent.TimeoutException` (via `import java.util.concurrent.*` `:31`). **Do NOT add any `dev.langchain4j.exception` import to this file** — none is needed; delegation carries the classification.

---

### 3. Config surface

**Current `RetryConfiguration` fields (verified `:23-26`):** `maxAttempts` (3), `backoffDelayMs` (1000), `backoffMultiplier` (2.0), `maxBackoffDelayMs` (10000). **No `retryOn`, no `categories`, no `retryOnUnclassified`.**

**This increment adds no config field.** The no-match default stays `return false` (fail-fast) — behavior-preserving. The Part I "`retryOnUnclassified: true` for one release" and neutral-category vocabulary were coupled to the abandoned `RetryClassifier` design; with a boolean extension there is no unclassified-retry knob to expose. `retryOn`/per-category policy is deferred (§8). No JSON surface change, no `ExtensionDescriptor` change.

---

### 4. Stored-config back-compat

No config schema change → trivially compatible. Stored `langchain.json` / `mcpcalls.json` `retry` blocks deserialize unchanged; `SerializationCustomizer` sets `FAIL_ON_UNKNOWN_PROPERTIES=false` anyway.

**Runtime behavior deltas on deploy (all deliberate):**

| # | Delta | Path |
|---|---|---|
| 1 | **The win.** Typed `HttpException` now classifies by status. Empty-body `429`/`5xx` from OpenAI/Anthropic/Ollama/Mistral/Gemini now **retry** (previously matched only if the body text happened to contain a trigger). `401/403/404` now **fail fast for the right reason**. `RetriableException`/`NonRetriableException` (Azure/Bedrock/Vertex) now classify. | main retry (`executeWithRetry` → shared classifier) **and** cascade label |
| 2 | **`NonRetriableException`/`HttpException(4xx)` now fail-fast *immediately*** — the classifier returns `false` mid-walk instead of descending/substring-matching. A `NonRetriableException("...timeout...")` no longer retries on the incidental substring. Rare in practice; correct semantics. | main retry + cascade label |
| 3 | **Cascade label/metric only.** `WebApplicationException(503)` and `HttpException(429)` step failures now tag `retryable_error` (was `error`) on trace `status`, `eddi.llm.cascade.escalations`, `eddi.llm.cascade.step.errors`. **Does not change what the cascade retries.** | cascade `:438,:443-444` |
| 4 | Cascade loses bare-number substring (`"429"`/`"50x"` in message text with no textual trigger and no typed exception). Superseded by tier 2. | cascade label only |

Deltas 1–2 must appear in the changelog + PR description.

---

### 5. Tests

**Canonical classifier test:** `configs/shared/RetryConfigurationTest.java` → `@Nested IsRetryableErrorTests`. Existing `unknownException` (`:212-216`, asserts `false` for `IllegalArgumentException`) **stays green** — no-match default preserved. Existing typed-JDK/WAE/substring tests stay green.

**Append to `IsRetryableErrorTests` (plain JUnit, no `@QuarkusTest`, no sockets):**

| Method | Asserts |
|---|---|
| `tier1_retriableException_retries` | `new RateLimitException("x")` → `true` |
| `tier1_lc4jTimeoutRetries` | `new dev.langchain4j.exception.TimeoutException("x")` → `true` (via `RetriableException`) |
| `tier1_internalServerRetries` | `new InternalServerException("x")` → `true` |
| `tier1_authenticationFailsFast` | `new AuthenticationException("x")` → `false` |
| `tier1_invalidRequestFailsFast` | `new InvalidRequestException("x")` → `false` |
| `tier1_nonRetriableWithTriggerSubstring_stillFailsFast` | `new InvalidRequestException("request timeout")` → `false` (tier 1 short-circuits substring — delta #2) |
| `tier2_httpException429Retries` / `_408Retries` / `_500Retries` / `_503Retries` | `new HttpException(n, "")` → `true` |
| `tier2_httpException401FailsFast` / `_403` / `_404` / `_400` | `new HttpException(n, "")` → `false` |
| `tier2_httpExceptionEmptyBody429Retries` | `new HttpException(429, "")` → `true` (empty body — the pre-#593 substring hack returned `false`) |
| `causeChain_innerHttpExceptionRetries` | `new RuntimeException("wrap", new HttpException(429, ""))` → `true` |
| `causeChain_outerNonRetriableFailsFast` | `new AuthenticationException("x")` wrapping a `SocketTimeoutException` → `false` (outer link decides) |

**Append to `CascadingModelExecutorCoverageTest.java` — additions only** (add `import dev.langchain4j.exception.HttpException;`, `import jakarta.ws.rs.*` as needed; follow the two-step `bad`/`good` `ChatModelRegistry` mock shape at `:610-638`):

| Method | Asserts |
|---|---|
| `httpException429_labelsRetryableError` | step throws `new HttpException(429, "")` → `result.trace().get(0).get("status")` == `"retryable_error"` (**today `"error"`** — empty body, regex can't match) |
| `webApplicationException503_labelsRetryableError` | step throws `WebApplicationException(503)` → `"retryable_error"` (**today `"error"`** — cascade had no WAE branch) |
| `httpException401_labelsError` | `new HttpException(401, "")` → `"error"` |

**Must remain unmodified (verify via `git diff`):** `error_escalatesWithSink` (`:580`, `"boom"`→`"error"`), `retryableError_escalates` (`:612`, `"429 rate limit exceeded"`→tier 5 `"rate limit"`→`"retryable_error"`), `typedRetryableError_escalates` (`:642`, `ConnectException`→tier 3→`"retryable_error"`). All three stay green under delegation — do not re-baseline.

---

### 6. Acceptance criteria

1. `.\mvnw.cmd clean compile` exits 0 (**`clean`** — a deleted `private static` member + `static final Pattern` are what a stale `.class` hides).
2. `grep -rc "isRetryableError" src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java` → **1** (only the delegating call at `:436`); `grep -c "private static boolean isRetryableError"` → **0**.
3. `grep -nE "RETRYABLE_MESSAGE|java.util.regex.Pattern" src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java` → **0**.
4. `grep -rn "ExceptionMapper" src/main/java/ai/labs/eddi/modules/llm` → **0** (no `@Internal` dependency; status table replicated in EDDI code).
5. `grep -c "dev.langchain4j.exception" src/main/java/ai/labs/eddi/configs/shared/RetryConfiguration.java` → **3** (Http/Retriable/NonRetriable imports); cascade file → **0**.
6. `.\mvnw.cmd test -Dtest=RetryConfigurationTest` passes, 0 skipped.
7. `.\mvnw.cmd test -Dtest=CascadingModelExecutorCoverageTest` passes; `git diff` on that file is additions-only.
8. `.\mvnw.cmd test -Dtest='CascadingModelExecutor*Test'`, `-Dtest='AgentExecutionHelperTest'`, `-Dtest='LegacyChatExecutor*Test'`, `-Dtest='StreamingLegacyChatExecutorRetryTest'`, `-Dtest='LlmConfiguration*Test'` all pass. *(Filter by whole class — `Class#method` runs 0 tests for `@Nested` methods.)*
9. Full `.\mvnw.cmd test` — no new failures vs. pre-change baseline (Docker/loopback classes remain the only failures).
10. `.\mvnw.cmd validate` exits 0; `.\mvnw.cmd formatter:format` produces no diff outside the touched set.
11. `docs/changelog.md` top entry names deltas #1–#4, states the cascade change is **label + metric-tag only** (not a retry change), and that `HttpException.statusCode()` is the adopted classification.

---

### 7. Traps

| # | Trap |
|---|---|
| **T1** | **The cascade classifier does not gate retries.** Its only consumer is `:436`, feeding trace `status` (`:438`), two metric tags (`:443-444`), one SSE reason (`:450`). Real retry lives in the shared `executeWithRetry`. Commit message = **label/metric fix**, not retry fix. Anyone "verifying the fix" by asserting the cascade retries a 429 is in the wrong file. |
| **T2** | **`AgentExecutionHelper` is already a shim — do not re-add a classifier to it.** The main-path adoption is achieved entirely by editing `RetryConfiguration.isRetryableError`. Touching `AgentExecutionHelper` is out of scope. |
| **T3** | **`HttpException` extends `LangChain4jException` directly** (javap-verified) — it is neither `RetriableException` nor `NonRetriableException`. Tier 1 will **not** catch it; tier 2 (`statusCode()`) is mandatory. A `catch (LangChain4jException)` shortcut misclassifies `HttpException` and the three direct-descendant exceptions. |
| **T4** | **`HttpException`'s message is the raw response body.** Never substring-match or log it — tier 2 returns before the substring tier is reached on that link, and only `statusCode()` is used. |
| **T5** | **Fail-fast returns `false` mid-walk (delta #2).** `NonRetriableException` and `HttpException(4xx)` return `false` immediately without descending. This is the one behavior change vs. the current "return `false` only after exhausting the chain" walk. Intentional; pinned by `tier1_nonRetriableWithTriggerSubstring_stillFailsFast` + `causeChain_outerNonRetriableFailsFast`. |
| **T6** | **No `dev.langchain4j.exception` import in `CascadingModelExecutor`.** Its `catch (TimeoutException e)` (`:367`) is `java.util.concurrent.TimeoutException` (via `import java.util.concurrent.*`). Adding a lc4j import there would collide. Delegation needs only the `RetryConfiguration` import. |
| **T7** | **`ToolApprovalRequiredException` must never reach the classifier.** `RetryConfiguration.executeWithRetry:116-118` rethrows it before `isRetryableError`; `CascadingModelExecutor:432-434` rethrows before `:436`. Both bypasses stay above the classifier. |
| **T8** | **WAE branch stays `{429,502,503,504}`** — do not "unify" it to `>=500`; that widens the main path. Only `HttpException` uses `>=500`. |
| **T9** | **Cause-chain loop.** Current classifier has **no** iteration cap (`:183-211`); a self-referential chain loops forever today. Since we are editing this loop, add a bounded walk (e.g. `for (int i = 0; i < 25 && current != null; i++)`) — no `IdentityHashMap` (hot-path allocation). Optional but recommended; note in changelog. |
| **T10** | **`RetryConfiguration` is static/stateless.** No new mutable statics, no caching. The union list is already inline `contains` checks — leave it. |

---

### 8. Out of scope

- `StreamingLegacyChatExecutor` — retries unconditionally on any `errorRef`/timeout (`:240-264`) with **no** classifier call; it uses `RetryConfiguration.backoff` only. Adding classification there is a behavior change, not R4.
- Per-category retry policy (`retryOn: [...]`), neutral `Category` enum, per-category backoff, jitter, circuit breaking.
- `ObservableChatModel:78` `new RuntimeException(e.getCause())` — belongs to D11 (cosmetic, cause preserved).
- `maxAttempts`/`backoff*` semantics or defaults; `AgentExecutionHelper`, `LegacyChatExecutor`, `AgentOrchestrator` retry structure.
- `ConfidenceEvaluator`, `CascadeConfigValidator`, escalation policy, `returnBestAcrossSteps`, metrics beyond the existing `errorType` tag.
- `LlmConfiguration` / `LlmTask` extension descriptor; classifier-self metrics (would force CDI on a static utility).
- `pom.xml` — `dev.langchain4j.exception.*` ships in `langchain4j-core` (transitive via `dev.langchain4j:langchain4j`), already on the compile path.

---

### 9. Sequencing

**S. Two functional commits + changelog. Steps 1 and 2 are independent once merged conceptually; commit order 1→2.**

| # | Commit | Contents | Green after |
|---|---|---|---|
| 1 | `feat(retry): classify langchain4j HttpException status + Retriable/NonRetriable` | MODIFY `RetryConfiguration.java` (tiers 1–2 + 3 imports + optional loop cap); append tier 1/2 tests to `RetryConfigurationTest`. Improves the main retry path immediately (`AgentExecutionHelper` already delegates). | `-Dtest=RetryConfigurationTest`, `-Dtest=AgentExecutionHelperTest`, `-Dtest='LegacyChatExecutor*Test'` |
| 2 | `fix(llm): unify cascade error labelling on the shared retry classifier` | MODIFY `CascadingModelExecutor.java` (delete `:797-798` + `:800-818`, drop `Pattern` import, add `RetryConfiguration` import, delegate `:436`); append the three cascade label tests. Message states **trace label + SSE reason + metric tags**, not retry. | `-Dtest='CascadingModelExecutor*Test'` |
| 3 | `docs(changelog): R4 retry classification via HttpException.statusCode` | `docs/changelog.md` — may fold into commit 2 per AGENTS.md §2 rule 8. | — |

Run `.\mvnw.cmd clean compile` before committing **2** — it deletes a `private static` member an incremental build resolves from a stale `.class`.

## R0 — Guardrail SPI from `langchain4j-core` + native executor loop

Verified against `C:\dev\git\EDDI` @ `feat/group-followups`, `pom.xml` langchain4j **1.18.0**, and the resolved jar/sources at `~/.m2/repository/dev/langchain4j/langchain4j-core/1.18.0/`.

---

### 0. Corrections to the decision doc

| Doc claim | Reality | Consequence for the spec |
|---|---|---|
| §2 "core SPI verified in `langchain4j-core` **1.17.0**" | All named types exist in **1.18.0** (the version `pom.xml:21` actually resolves). Verified by `unzip -l` + `javap`. | None — spec against 1.18.0. |
| §6 R0 "Take … `GuardrailResult`" | `GuardrailResult<GR>` is a **`sealed interface … permits InputGuardrailResult, OutputGuardrailResult`** (`GuardrailResult.java:19-20`). Its nested `Failure` is sealed too. | EDDI **cannot** implement `GuardrailResult` or `Failure`. EDDI's neutral verdict must be a separate EDDI enum mapped *from* `GuardrailResult.Result`. |
| §6 R0 "Plus `JsonExtractorOutputGuardrail` free, which serves the known `responseSchema` gap" | Constructors are `(Class<T>)` / `(TypeReference<T>)` / `(ObjectMapper, Class<T>)` / `(ObjectMapper, TypeReference<T>)` — a **compile-time Java type**. EDDI's `responseSchema` is a JSON string (`LlmTask.java:88, 381`). | It serves only "is the output valid JSON of shape `Map`/`List`". Ship it bound to `Map.class` under type `json-object`. It does **not** close I4. Say so in docs. |
| §6 R0 "Supply our own `ChatExecutor` (~20 lines)" reads as optional | `OutputGuardrailRequest` private ctor does `ensureNotNull(builder.chatExecutor, "chatExecutor")` (`OutputGuardrailRequest.java:20`). | A `ChatExecutor` is **mandatory** on every output-guardrail invocation, including pure `block` guardrails that never reprompt. |
| Doc is silent on `GuardrailRequestParams` required fields | `ensureNotNull(userMessageTemplate)` and `ensureNotNull(variables)` (`GuardrailRequestParams.java:31-32`). `chatMemory`, `augmentationResult`, `invocationContext` may be null. | EDDI must always pass a non-null template string (use `""`) and a non-null map. NPE-on-build otherwise. |
| §6 R0 "`OutputGuardrailExecutor` … `chatMemory` mutation surface" | Confirmed: `OutputGuardrailExecutor.java:85,117`. `InputGuardrailExecutor` has **zero** `chatMemory` references. | Input side has no lc4j-executor blocker beyond throw-on-failure; still write our own for symmetry + neutral actions. |
| §6 R0 "`OutputGuardrailExecutor` is `public non-sealed`, `protected` ctor, non-final `execute`" | Confirmed verbatim (`OutputGuardrailExecutor.java:28,44,55`). Its parent `AbstractGuardrailExecutor` is `abstract sealed` (`:31`) but that does not block subclassing the concrete class. | Reason for rejection stands as written (throws at `:78,:107`); do not re-litigate. |
| D3 (`langchain:trace:` prefix mismatch) — cited as `LlmTask.java:627,768` / `LifecycleManager.java:414` | **Confirmed.** Writer `LlmTask.java:627` = `"langchain" + ":trace:" + task.getType() + ":" + task.getId()`; reader `LifecycleManager.java:414` = `"langchain:trace:" + task.getId().name()` (= `ai.labs.llm`). | Guardrail SSE must **not** be routed through `buildTaskSummary` alone, or it inherits the same dead channel. See §6 T4. |
| `planning/guardrails-architecture.md` Option A: "LangChain4j's guardrails are designed for `@AiService` annotated interfaces … adopting would require a fundamental architectural change" | **False.** `InputGuardrail`/`OutputGuardrail` are plain interfaces in `dev.langchain4j.guardrail` with no `AiServices` reference. Coupling lives only in the *executors*. | **This spec supersedes `guardrails-architecture.md` §"Design Decision Option A", §"Guardrail Interface & Registry", and the `IGuardrail.java` / `GuardrailResult.java` rows of its file table.** Its Option B (config-driven, inside `LlmTask`), audit keys, SSE event, and built-in type catalogue are **retained**. Do **not** create `IGuardrail` or a `Verdict` enum with a `PASS` member. |

`langchain4j-core` is not declared in `pom.xml`; it arrives transitively via `dev.langchain4j:langchain4j` (`pom.xml:138-141`). EDDI already compiles against core types (`dev.langchain4j.data.message.ChatMessage`, `LlmTask.java:44`). **No `pom.xml` change required.**

---

### 1. Files to touch

#### CREATE — `src/main/java/ai/labs/eddi/modules/llm/guardrails/`

| File | Contents |
|---|---|
| `GuardrailAction.java` | `public enum GuardrailAction { BLOCK, REDACT, REPROMPT, WARN; public static GuardrailAction parse(String v) }` — `parse` is case-insensitive, returns `BLOCK` for null/blank/unknown and logs WARN. **EDDI-owned; never serialize lc4j class names.** |
| `GuardrailType.java` | `@Retention(RUNTIME) @Target(TYPE) public @interface GuardrailType { String value(); }` — plain marker, **not** a CDI qualifier. |
| `GuardrailOutcome.java` | `public record GuardrailOutcome(GuardrailAction action, String type, String phase, String message, String rewrittenText, int retryCount)` — EDDI-owned audit/SSE payload. |
| `GuardrailService.java` | `@ApplicationScoped`, stateless. The executor loop. Signatures in §1.1. |
| `EddiChatExecutor.java` | `final class EddiChatExecutor implements dev.langchain4j.guardrail.ChatExecutor` — §1.2. |
| `impl/PiiRedactionGuardrail.java` | `@ApplicationScoped @GuardrailType("pii-redaction")` implementing **both** `InputGuardrail` and `OutputGuardrail`. Regex SSN/email/CC/phone. Returns `successWith(redacted)` when it changed text, `success()` otherwise. |
| `impl/RegexFilterGuardrail.java` | `@ApplicationScoped @GuardrailType("regex-filter")`, both directions. Patterns from per-invocation params (§2.3). |
| `impl/MaxLengthGuardrail.java` | `@ApplicationScoped @GuardrailType("max-length")`, input only. `failure(...)` over `maxChars`. |
| `impl/PromptInjectionGuardrail.java` | `@ApplicationScoped @GuardrailType("prompt-injection")`, input only. Heuristic pattern list, **loaded from config params with a built-in default list** (Golden Rule 1 — do not hardcode-only). |
| `impl/JsonObjectGuardrail.java` | `@ApplicationScoped @GuardrailType("json-object")` — thin `extends JsonExtractorOutputGuardrail<Map>` bound to `Map.class`. Output only. |

#### MODIFY

| File | Change |
|---|---|
| `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` | Add two fields + getters on `Task` (after `multimodal`, `:309`, before `toolApprovals`, `:316`); add nested `public static class GuardrailConfig` next to `MultimodalOverride` (`:676`). §2. |
| `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java` | (a) field-inject `GuardrailService` next to `attachmentForwarder` (`:118-122`); (b) **extract** the execution block `:481-575` into `private ModelOutcome executeModel(List<ChatMessage> messages, …)`; (c) input phase call after `:451`; (d) output loop replaces the single call at `:481`; (e) audit keys after `:623`; (f) extension descriptor `:926-934`. §1.3. |
| `src/main/java/ai/labs/eddi/engine/lifecycle/ConversationEventSink.java` | Add `default void onGuardrailTriggered(String type, String phase, String action, String taskId) {}` after `onCascadeEscalation` (`:93-94`). |
| `src/main/java/ai/labs/eddi/engine/api/IConversationService.java` | Add `default void onGuardrailTriggered(String type, String phase, String action, String taskId) {}` to nested `interface StreamingResponseHandler` (after `onError`, `:153`). |
| `src/main/java/ai/labs/eddi/engine/internal/ConversationService.java` | Add the override to the anonymous sink at `:571-596`, delegating to `streamingHandler`. |
| `src/main/java/ai/labs/eddi/engine/internal/RestAgentEngineStreaming.java` | Add the override in the anonymous handler (`:57-111`), emitting `sendEvent(eventSink, sse, "guardrail_triggered", …)`. |
| `src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java` | In `buildAuditEntry` (`:430`), after the `llmDetail` block (`:463`), collect `currentStep.getAllData("audit:guardrail:")` into `output.put("guardrails", …)`. §5. |
| `docs/langchain.md` | Document `inputGuardrails`/`outputGuardrails`, the four neutral actions, the built-in types, and the `json-object` limitation. |
| `docs/changelog.md` | Entry per AGENTS.md §2 rule 8. |

#### DELETE
None.

---

#### 1.1 `GuardrailService` — the executor loop

```java
package ai.labs.eddi.modules.llm.guardrails;

@ApplicationScoped
public class GuardrailService {

    /** Reserved key under GuardrailRequestParams.variables() carrying per-invocation config params. */
    public static final String PARAMS_KEY = "eddi.guardrail.params";

    @Inject Instance<InputGuardrail>  inputBeans;
    @Inject Instance<OutputGuardrail> outputBeans;
    @Inject MeterRegistry meterRegistry;

    /** Resolved once at @PostConstruct: @GuardrailType value -> bean. Immutable thereafter. */
    private Map<String, InputGuardrail>  inputByType;
    private Map<String, OutputGuardrail> outputByType;

    @PostConstruct void indexBeans() { /* duplicate @GuardrailType value -> log ERROR, first wins */ }

    /**
     * Runs the configured input guardrails in declaration order.
     * @return the (possibly rewritten) user text, or null when a guardrail BLOCKed.
     */
    public InputPhaseResult runInput(List<GuardrailConfig> configs,
                                     UserMessage userMessage,
                                     Map<String, Object> templateDataObjects,
                                     Consumer<GuardrailOutcome> onOutcome);

    /**
     * Runs the configured output guardrails with a bounded reprompt loop.
     * reinvoke is the SAME execution branch that produced responseFromLLM — cascade,
     * agent, or legacy — so every reprompt stays inside budget, token, retry and HITL accounting.
     */
    public OutputPhaseResult runOutput(List<GuardrailConfig> configs,
                                       ChatResponse responseFromLLM,
                                       List<ChatMessage> currentMessages,
                                       Function<List<ChatMessage>, ChatResponse> reinvoke,
                                       Map<String, Object> templateDataObjects,
                                       Consumer<GuardrailOutcome> onOutcome)
            throws LifecycleException;

    public record InputPhaseResult(String text, GuardrailOutcome blocked) {}
    public record OutputPhaseResult(String text, List<GuardrailOutcome> outcomes, GuardrailOutcome blocked) {}
}
```

Loop body of `runOutput` (~40 lines, the piece the doc prices):

```
attempts = 0
messages = new ArrayList<>(currentMessages)
response = responseFromLLM
loop:
  for cfg in configs:
     guardrail = outputByType.get(cfg.getType());  if null -> log WARN, continue   // never throw from config
     params = GuardrailRequestParams.builder()
                 .userMessageTemplate("")                      // MANDATORY non-null
                 .variables(varsPlus(templateDataObjects, PARAMS_KEY, cfg.getParameters()))
                 .build();                                     // chatMemory deliberately NOT set
     req = OutputGuardrailRequest.builder()
                 .responseFromLLM(response)
                 .chatExecutor(new EddiChatExecutor(messages, reinvoke))   // MANDATORY non-null
                 .requestParams(params).build();
     GuardrailResult.Result r = guardrail.validate(req).result();   // never let lc4j throw escape:
                                                                    // wrap validate() in try/catch(RuntimeException)
                                                                    // -> treat as WARN, log, continue
     map r + cfg.getAction() -> EDDI action:
        SUCCESS                     -> continue
        SUCCESS_WITH_RESULT         -> if action==REDACT  : response = req.withText(result.successfulText()).responseFromLLM(); emit REDACT
                                       else               : emit WARN, keep original text
        FAILURE | FATAL             -> switch(cfg.getAction()):
              WARN     -> emit WARN, continue
              REDACT   -> response = req.withText(cfg.getBlockMessage()); emit REDACT; continue
              BLOCK    -> return OutputPhaseResult(cfg.getBlockMessage(), outcomes, outcome)   // no exception
              REPROMPT -> if ++attempts > effectiveMaxRetries(cfg) -> degrade to BLOCK
                          messages.add(response.aiMessage())
                          messages.add(UserMessage.from(repromptText(result, cfg)))
                          response = reinvoke.apply(messages)     // <-- inside all accounting
                          emit REPROMPT; restart loop from configs[0]
  return OutputPhaseResult(response.aiMessage().text(), outcomes, null)
```

Hard rules encoded above:
- **Never throw** `OutputGuardrailException`/`InputGuardrailException` out of `GuardrailService`. A terminal `BLOCK` is a *returned value*. This is the fix for the doc's `AgentExecutionHelper.isRetryableError` substring-collision hazard (`AgentExecutionHelper.java:143-148` — `"connection"`, `"timeout"`, `"temporary"`); a guardrail message quoting user text must never reach that classifier.
- `repromptText` prefers `result.getReprompt()` (`OutputGuardrailResult.getReprompt()` → `Optional<String>`) and falls back to the first `Failure.message()`.
- `result.isRetry()` / `blockRetry()` are **not honoured** in v1 — lc4j `retry` means "same messages again", which is indistinguishable from EDDI's existing retry budget. Map `retry` → EDDI `REPROMPT` with an empty reprompt hint, and note it in javadoc.

#### 1.2 `EddiChatExecutor`

```java
final class EddiChatExecutor implements ChatExecutor {
    private final List<ChatMessage> currentMessages;
    private final Function<List<ChatMessage>, ChatResponse> reinvoke;
    private final AtomicInteger invocations = new AtomicInteger();
    private static final int MAX_GUARDRAIL_INVOCATIONS = 4;

    EddiChatExecutor(List<ChatMessage> currentMessages, Function<List<ChatMessage>, ChatResponse> reinvoke) { … }

    @Override public ChatResponse execute() { return execute(List.of()); }

    /** Merges the supplied messages onto EDDI's currentMessages, then re-enters EDDI's own
     *  execution branch. Guarded so a misbehaving guardrail cannot mint unbounded LLM calls. */
    @Override public ChatResponse execute(List<ChatMessage> chatMessages) {
        if (invocations.incrementAndGet() > MAX_GUARDRAIL_INVOCATIONS)
            throw new IllegalStateException("Guardrail exceeded chat executor invocation budget");
        var merged = new ArrayList<>(currentMessages);
        merged.addAll(chatMessages);            // merge, do NOT replace — preserves system prompt, history, RAG
        return reinvoke.apply(merged);
    }
}
```

One instance per guardrail invocation (constructed inside the loop) — no shared mutable state across conversations. `AtomicInteger` is per-instance, so `GuardrailService` remains a stateless singleton.

#### 1.3 `LlmTask` changes

**(a)** next to `attachmentForwarder` (`LlmTask.java:118-122`), same documented rationale (direct-construction unit tests):

```java
@Inject
GuardrailService guardrailService;
```

**(b) Extract**, verbatim, `LlmTask.java:481-575` into:

```java
private ModelOutcome executeModel(List<ChatMessage> messages, List<ChatMessage> chatMessagesWithoutSystem,
                                  ChatModel chatModel, String systemMessage, Map<String, String> processedParams,
                                  Task task, IConversationMemory memory, IWritableConversationStep currentStep,
                                  ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex,
                                  ConversationEventSink eventSink, boolean addToOutputExplicitlyFalse, boolean jsonMode)
        throws LifecycleException, ChatModelRegistry.UnsupportedLlmTaskException;

private record ModelOutcome(String responseContent, Map<String, Object> responseMetadata,
                            List<Map<String, Object>> toolTrace, boolean usedToolMode) {}
```

Pure move. No behavior change. **Commit this separately** (sub-step 1, §8).

**(c) Input phase** — insert after the `if (messages.isEmpty()) return;` guard (`:449-451`) and **before** `chatModelRegistry.getOrCreate` (`:453`):

```java
if (guardrailService != null && !isNullOrEmpty(task.getInputGuardrails())) {
    var lastUser = ConversationHistoryBuilder /* last UserMessage in messages */;
    var res = guardrailService.runInput(task.getInputGuardrails(), lastUser, templateDataObjects,
            o -> emitGuardrail(memory, currentStep, task, o));
    if (res.blocked() != null) {
        writeGuardrailAudit(currentStep, res.blocked());
        storeBlockedResponse(currentStep, task, res.blocked().message());   // canned text -> output, same path as :638-643
        return;                                                             // LLM is never called
    }
    if (res.text() != null) replaceLastUserText(messages, res.text());       // REDACT
}
```

**(d) Output phase** — wrap the extracted call:

```java
ModelOutcome outcome = executeModel(messages, …);
if (guardrailService != null && !isNullOrEmpty(task.getOutputGuardrails()) && outcome.responseContent() != null) {
    var initial = ChatResponse.builder().aiMessage(AiMessage.from(outcome.responseContent())).build();
    var res = guardrailService.runOutput(task.getOutputGuardrails(), initial, messages,
            m -> { try { return ChatResponse.builder()
                            .aiMessage(AiMessage.from(executeModel(m, …).responseContent())).build(); }
                   catch (Exception e) { throw new GuardrailReinvokeException(e); } },
            templateDataObjects, o -> emitGuardrail(memory, currentStep, task, o));
    res.outcomes().forEach(o -> writeGuardrailAudit(currentStep, o));
    responseContent = res.blocked() != null ? res.blocked().message() : res.text();
} else {
    responseContent = outcome.responseContent();
}
```

`GuardrailReinvokeException extends RuntimeException` is unwrapped by `runOutput` and rethrown as `LifecycleException`. `ToolApprovalRequiredException` must be **rethrown unchanged** through both the lambda and `runOutput` — mirror the guard at `AgentExecutionHelper.java:58-60`.

**(e) Audit** — inside the existing `if (memory.getAuditCollector() != null)` block (`:610-623`):
```java
private void writeGuardrailAudit(IWritableConversationStep step, GuardrailOutcome o) {
    step.storeData(dataFactory.createData("audit:guardrail:" + o.phase() + ":" + o.type(),
        Map.of("action", o.action().name(), "reason", o.message() == null ? "" : o.message(),
               "retryCount", o.retryCount())));
}
```
Values are `Map<String,Object>` with **no null members** (`Map.of` NPEs on null — carried forward from the D1 warning).

**(f) Extension descriptor** (`:926-934`), add before `return`:
```java
extensionDescriptor.getConfigs().put("inputGuardrails",  new ConfigValue("Input Guardrails",  FieldType.ARRAY, true, null));
extensionDescriptor.getConfigs().put("outputGuardrails", new ConfigValue("Output Guardrails", FieldType.ARRAY, true, null));
```
[UNVERIFIED — implementer must check that `FieldType.ARRAY` exists in `ExtensionDescriptor.FieldType`; if not, use the enum member the Manager already renders for `knowledgeBases`.]

---

### 2. Config surface

#### 2.1 JSON (stored in `langchain.json`)

```json
{ "tasks": [ {
  "actions": ["support"], "type": "openai",
  "inputGuardrails": [
    { "type": "prompt-injection", "action": "block", "blockMessage": "I can't process that request." },
    { "type": "pii-redaction",    "action": "redact", "parameters": { "patterns": "email,ssn,creditcard" } }
  ],
  "outputGuardrails": [
    { "type": "pii-redaction", "action": "redact" },
    { "type": "json-object",   "action": "reprompt", "maxRetries": 2 }
  ]
} ] }
```

#### 2.2 POJO — `LlmConfiguration.java`

On `Task` (fields after `:309`, getters after `getMultimodal()` `:658`):

| Field | Type | Default | Validation |
|---|---|---|---|
| `inputGuardrails` | `List<GuardrailConfig>` | `null` | none at `configure()` |
| `outputGuardrails` | `List<GuardrailConfig>` | `null` | none at `configure()` |

```java
public static class GuardrailConfig {
    private String type;                       // required; unknown -> WARN + skip at runtime
    private String action = "block";           // block | redact | reprompt | warn
    private Integer maxRetries = 2;            // REPROMPT only; clamped to [0, 5]
    private String blockMessage;               // null -> "Request blocked by guardrail '<type>'."
    private Map<String, String> parameters;    // per-type, passed via variables()[PARAMS_KEY]
    // getters only, matching the existing style in this file
}
```

**Do not throw from `LlmTask.configure()` on an unknown `type` or `action`** — carried forward from the D8 note; an unknown value must degrade (WARN log + skip / default `BLOCK`), never fail the whole executable workflow.

#### 2.3 Per-invocation parameters

`GuardrailConfig.parameters` reaches the bean as `request.requestParams().variables().get(GuardrailService.PARAMS_KEY)` → `Map<String,String>`. Guardrail beans **must not** cache it. This is the only reason `variables()` is populated, and is why the beans stay stateless singletons.

---

### 3. Stored-config back-compat

| Scenario | Result |
|---|---|
| Existing stored `langchain.json` with no `inputGuardrails`/`outputGuardrails` | Both fields deserialize to `null`. `LlmTask` guards with `isNullOrEmpty(...)`. **Zero new code executes.** Byte-identical behavior. |
| ZIP import of a pre-R0 agent | Unchanged — additive fields only. |
| Field removed later | Covered by `FAIL_ON_UNKNOWN_PROPERTIES=false`. [UNVERIFIED — implementer must confirm the ObjectMapper config in `ai.labs.eddi.datastore.serialization`; the doc asserts it at §7 D10 but the setting was not re-read for this spec.] |
| A live agent the moment this deploys | **No change unless the operator adds guardrail config.** No default guardrail is seeded. This is deliberate — a default-on content filter would break live agents exactly the way D2's inert budget ceiling would if switched on. |
| Downgrade to a pre-R0 build with guardrail config present | Unknown properties ignored; agent runs unguarded. Note this in `docs/langchain.md`. |

---

### 4. Tests

#### Existing coverage (verified paths)

| Class | Relevance |
|---|---|
| `src/test/java/ai/labs/eddi/modules/llm/impl/LlmTaskTest.java` (+ `LlmTaskBranchTest`, `LlmTaskCoverageTest`, `LlmTaskCoverage2Test`, `LlmTaskDeepBranchTest`, `LlmTaskExtendedTest`, `LlmTaskExtendedBranchTest`, `LlmTaskResumeModeTest`, `LlmTaskConfigureTest`) | 9 classes construct `LlmTask` directly. **Field-injecting `GuardrailService` (not a constructor param) keeps all 9 compiling and green** — `guardrailService` is null there and every call site is null-guarded. If you instead add a constructor arg, all 9 break. |
| `src/test/java/ai/labs/eddi/modules/llm/impl/AgentExecutionHelperTest.java` | Retry-classifier coverage. Must stay green — proves guardrail messages never reach `isRetryableError`. |
| `src/test/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManagerStreamingTest.java` | Covers `buildTaskSummary` / SSE. Will need a case for the guardrail audit collection in `buildAuditEntry`. |
| `src/test/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutorExecuteTest.java` | Must stay green after the `executeModel` extraction — the extraction is the highest-regression-risk step. |

**Vacuous test to fix as part of this work:** the doc flags `LifecycleManagerTest.java:1028` mocking `langchain:trace:ai.labs.llm`, a key production never writes (D3, re-confirmed above at `LlmTask.java:627` vs `LifecycleManager.java:414`). Do **not** copy that pattern for guardrails: any new `LifecycleManager` test must stub the key using the **same expression the production writer uses**, i.e. `"audit:guardrail:" + phase + ":" + type`.

#### New tests

`src/test/java/ai/labs/eddi/modules/llm/guardrails/GuardrailServiceTest.java`

| Method | Asserts |
|---|---|
| `runInput_noConfigs_returnsPassThroughAndNeverTouchesBeans()` | returns `text()==null, blocked()==null`; `verify(bean, never()).validate(any())` |
| `runInput_blockAction_returnsBlockedOutcomeAndDoesNotCallModel()` | `blocked().action()==BLOCK`, `blocked().message()` equals configured `blockMessage` |
| `runInput_redactAction_returnsRewrittenText()` | `text()` equals the guardrail's `successWith` payload |
| `runOutput_repromptStopsAtMaxRetries_thenBlocks()` | `reinvoke` invoked exactly `maxRetries` times; final `blocked().action()==BLOCK` |
| `runOutput_repromptMergesIntoCurrentMessages()` | captured message list on the 2nd `reinvoke` contains the original `SystemMessage` **plus** the prior `AiMessage` **plus** the reprompt `UserMessage`, in that order |
| `runOutput_neverThrowsOutputGuardrailException()` | guardrail returning `fatal("connection timeout")` → returns a `blocked()` outcome; no exception; message never propagates to a caller that classifies |
| `runOutput_unknownType_isSkippedWithWarn()` | `reinvoke` never called; result text == original |
| `runOutput_guardrailThrowsRuntimeException_degradesToWarn()` | result text == original, one `WARN` outcome emitted |
| `runOutput_preservesToolExecutionRequests()` | build the request from a `ChatResponse` whose `AiMessage` has 2 `ToolExecutionRequest`s; assert `req.withText("x").responseFromLLM().aiMessage().toolExecutionRequests()` has size 2 (pins `OutputGuardrailRequest.java:56-60`) |
| `outputRequest_requiresChatExecutor()` | building without `chatExecutor` throws — pins the mandatory-field trap |
| `requestParams_requireNonNullTemplateAndVariables()` | `GuardrailRequestParams.builder().build()` throws; EDDI's builder helper does not |

`src/test/java/ai/labs/eddi/modules/llm/guardrails/EddiChatExecutorTest.java`

| Method | Asserts |
|---|---|
| `execute_mergesOntoCurrentMessages()` | merged list = current + supplied, current unmutated |
| `execute_exceedsInvocationBudget_throws()` | 5th call throws `IllegalStateException` |

`src/test/java/ai/labs/eddi/modules/llm/guardrails/impl/PiiRedactionGuardrailTest.java` — one method per pattern class asserting the exact redacted string; one asserting a clean input returns `success()` (not `successWith`), so the REDACT branch does not fire spuriously.

`src/test/java/ai/labs/eddi/modules/llm/guardrails/GuardrailConfigDeserializationTest.java`
- `unknownFields_areIgnored()` — a `GuardrailConfig` JSON with a bogus key deserializes.
- `absentGuardrails_deserializeToNull()` — a pre-R0 `langchain.json` string round-trips with both fields null. **This is the stored-config back-compat proof.**

Add to `LifecycleManagerStreamingTest`: `buildAuditEntry_collectsGuardrailKeys()` — stub `getAllData("audit:guardrail:")`, assert `output.get("guardrails")` non-empty.

None of the above binds a socket or needs Docker → all runnable via `.\mvnw.cmd test`. Note the surefire nested-test filter caveat: filter by whole class, not `Class#method`.

---

### 5. Audit + SSE keys

| Channel | Key / event | Payload |
|---|---|---|
| Memory (audit) | `audit:guardrail:input:<type>` | `{ action, reason, retryCount }` |
| Memory (audit) | `audit:guardrail:output:<type>` | `{ action, reason, retryCount }` |
| Ledger | `AuditEntry.output["guardrails"]` | `List<Map<String,Object>>` collected in `buildAuditEntry` |
| SSE | `event: guardrail_triggered` | `{"type":"…","phase":"input|output","action":"BLOCK|REDACT|REPROMPT|WARN","taskId":"…"}` |
| Micrometer | `eddi.guardrail.triggered` | tags `type`, `phase`, `action` |

**HMAC trap (carried from D1):** the guardrail value is a `Map` nested inside `AuditEntry.output`. If `AuditHmac.sortedMapString` does not recurse, `org.bson.Document.toString()` prefixes `Document{` on read-back and **breaks HMAC verification**. Before nesting, verify `AuditHmac.sortedMapString` recurses into nested maps; if it does not, either make it recurse (preferred) or flatten guardrail entries to `List<String>` of `"<phase>:<type>=<action>"`.

---

### 6. Traps

1. **T1 — do not throw.** `OutputGuardrailExecutor` throws at `:78` and `:107`; a thrown message containing `"connection"`, `"timeout"`, `"temporary"`, `"502"`, `"503"`, `"504"`, `"rate limit"`, or `"too many requests"` matches `AgentExecutionHelper.java:145-147` and triggers a full tool-loop replay. EDDI's loop returns values.
2. **T2 — reprompts must go through `executeModel`, not `chatModel.chat`.** Calling the model directly bypasses `AgentOrchestrator`'s budget gates, the tool loop, cascade selection, and HITL journaling. This is the entire reason R0 says "build ours".
3. **T3 — `ToolApprovalRequiredException` must travel up unchanged** through the reprompt lambda and `runOutput`. Mirror `AgentExecutionHelper.java:58-60`. If it is wrapped, a tool pause during a reprompt silently becomes a hard failure.
4. **T4 — do not route the SSE event through `buildTaskSummary`.** That path is D3-broken (`LifecycleManager.java:414` vs `LlmTask.java:627`). Guardrail events go through `ConversationEventSink.onGuardrailTriggered` → `StreamingResponseHandler` → `RestAgentEngineStreaming`, a live channel (`sendEvent`, `:119-129`). Note that the anonymous sink in `ConversationService.java:571-596` does **not** override `onCascadeStepStart`/`onCascadeEscalation`, so those default no-ops silently drop cascade events today — you must add the guardrail override or it will drop identically.
5. **T5 — mandatory builder fields.** `chatExecutor` (`OutputGuardrailRequest.java:20`), `userMessageTemplate` + `variables` (`GuardrailRequestParams.java:31-32`). All three NPE at build time, inside the conversation hot path.
6. **T6 — `GuardrailResult` is sealed.** EDDI cannot add a result type. Map to `GuardrailAction` at the boundary.
7. **T7 — thread safety.** `GuardrailService` is a singleton; `inputByType`/`outputByType` are built once in `@PostConstruct` and never mutated. Guardrail beans are `@ApplicationScoped` singletons and **must hold no per-invocation state** — all context arrives via `variables()`. `EddiChatExecutor` is per-invocation.
8. **T8 — ordering.** Input guardrails run **after** RAG/counterweight/identity-masking system-prompt assembly (`LlmTask.java:329-392`) and **after** message building (`:418-429`), but **before** `getOrCreate` (`:453`) and before `executePreRequestPropertyInstructions` (`:454`). A blocked input must not run `preRequest` property mutations.
9. **T9 — redaction target.** Input REDACT rewrites the **last `UserMessage` in `messages`**, not `systemMessage` and not the memory `input` key. The original user input stays in conversation memory (the "full data is never deleted by optimization" rule); only what the LLM sees changes.
10. **T10 — `chatMemory` stays null** in `GuardrailRequestParams`. It is nullable and EDDI has no `ChatMemory` (`EddiChatMemoryStore` is slated for deletion per §7 P3). Any adopted guardrail that dereferences it will NPE — catch `RuntimeException` around `validate()` (T-loop above) so a third-party guardrail cannot kill a turn.
11. **T11 — the extraction is the regression risk.** `LlmTask.java:481-575` contains four mutually exclusive branches (cascade / cascade-skipped-agent / agent / legacy-streaming / legacy). Extract it as a **pure move with zero edits**, commit, run the full `llm` test package, and only then add guardrails.

---

### 7. Out of scope

- `langchain4j-guardrails` artifact — **do not add to `pom.xml`**. `PatternBasedPromptInjectionGuardrail` and `MessageModeratorInputGuardrail` hardcode `private static final List<Pattern> DEFAULT_PATTERNS`, unreachable from JSON (Golden Rule 1). Lift the regexes as data into `PromptInjectionGuardrail`'s default param set if desired.
- `OutputGuardrailExecutor`, `InputGuardrailExecutor`, `AbstractGuardrailExecutor`, `GuardrailsConfig`/`DefaultInput|OutputGuardrailsConfig`, the `dev.langchain4j.spi.guardrail.*` factories, and `dev.langchain4j.observability.api.event.*GuardrailExecutedEvent` — none of them.
- `ChatMemory` / `EddiChatMemoryStore` — untouched here.
- D3 (toolTrace SSE key mismatch) — **not fixed by this item**, only routed around. Separate P0.
- D1 (audit zeros), D2 (cost table), D5 (cache scoping) — separate P0 items; do not fold in.
- I4 `JsonSchema` / `responseSchema` — `json-object` only validates "is a JSON object". Do not reuse or redefine the stored `responseSchema` field.
- LLM-powered guardrails (`toxicity`, `topic-scope`, `custom-llm` from `guardrails-architecture.md` Phase 3) — deferred; they need cost tracking and a model handle, which is a follow-up.
- Manager UI editor — Manager repo, separate work.
- `ObservableChatModel` / R5 / D11 — sequenced before this per §13, but no code overlap.

---

### 8. Effort and sequencing (S, ~1.5–2 days)

| # | Step | Independently committable | Verify |
|---|---|---|---|
| 1 | Pure extraction of `LlmTask.java:481-575` → `executeModel` + `ModelOutcome` | ✅ `refactor(llm): extract model execution branch from LlmTask.executeTask` | `.\mvnw.cmd test -Dtest='LlmTask*Test'` + `CascadingModelExecutor*Test` all green, no assertion changes |
| 2 | `GuardrailAction`, `GuardrailType`, `GuardrailOutcome`, `EddiChatExecutor` + its test | ✅ | `EddiChatExecutorTest` green |
| 3 | `GuardrailService` (bean index + both loops) + `GuardrailServiceTest` | ✅ | 11 test methods green |
| 4 | `LlmConfiguration.GuardrailConfig` + Task fields + `GuardrailConfigDeserializationTest` | ✅ | back-compat test green |
| 5 | Wire into `LlmTask` (input phase, output loop, audit keys, extension descriptor) | ✅ | all 9 `LlmTask*Test` classes still green |
| 6 | Built-in guardrail impls + per-impl tests | ✅ | new tests green |
| 7 | SSE plumbing (`ConversationEventSink` → `IConversationService` → `ConversationService` → `RestAgentEngineStreaming`) + `LifecycleManager.buildAuditEntry` guardrail collection | ✅ | `LifecycleManagerStreamingTest` green |
| 8 | `docs/langchain.md` + `docs/changelog.md` | ✅ (bundle with 7 per AGENTS.md §2 rule 8) | — |

---

### 9. Acceptance criteria

1. `.\mvnw.cmd clean compile` exits 0. (`clean` is mandatory — the extraction changes method signatures; incremental builds hide breaks in unedited callers.)
2. `.\mvnw.cmd test` shows no new failures vs. the pre-change baseline. The only expected failures are the known Docker/Testcontainers + loopback-socket classes (Mongo\*, Postgres\*, `SafeHttpClientTest`, `SlackWebApiClientTest`, `WeatherToolTest`, `WebScraperToolTest`).
3. All 9 `LlmTask*Test` classes compile **without modification** — proves the field-injection choice held.
4. `.\mvnw.cmd test -Dtest=GuardrailConfigDeserializationTest` passes: a `langchain.json` string containing no `inputGuardrails`/`outputGuardrails` deserializes with both fields `null`.
5. Deploying an agent whose stored `langchain.json` has no guardrail config produces a turn whose `audit:guardrail:*` key set is **empty** and whose `AuditEntry.output` has no `guardrails` member.
6. `grep -rn "OutputGuardrailException\|InputGuardrailException\|GuardrailExecutor" src/main/java` returns **zero** hits.
7. `grep -rn "\"pass\"\|Verdict.PASS\|dev.langchain4j" src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` returns zero hits — no lc4j type name and no `PASS` action is ever persisted.
8. `GuardrailServiceTest#runOutput_repromptStopsAtMaxRetries_thenBlocks` proves the reinvoke count equals `maxRetries` exactly (not `maxRetries+1`).
9. `GuardrailServiceTest#runOutput_repromptMergesIntoCurrentMessages` proves the second model call receives the original `SystemMessage`.
10. With a `block` input guardrail configured, a matching input produces a turn where `chatModelRegistry.getOrCreate` is never invoked (Mockito `verify(never())` in an `LlmTask` test).
11. An SSE turn with a triggered guardrail emits an `event: guardrail_triggered` line before `event: done`. [CI/manual — the SSE path binds a socket and cannot be verified locally.]
12. `.\mvnw.cmd validate` (Checkstyle) passes and `.\mvnw.cmd formatter:format` produces no diff on the new files. No fully-qualified inline type names in new code (AGENTS.md §4.7 Imports) — note that `LlmTask.java:58` and `:316` already violate this; do not add more.

---

## R3a — RRF fusion via `DefaultContentAggregator`

---

### 0. Corrections to the decision doc

| Doc claim | Reality (verified) | Impact |
|---|---|---|
| `RagContextProvider.java:159` `allResults.addAll(...)` | ✅ correct — line 159 exactly | none |
| `formatRagContext()` at `:194-212`, header logic `:199-205` | ✅ correct (method decl line 194, close 212; header block 199–205) | none |
| per-KB embedding provider resolved at `:138,152` | `:138` = `embeddingModelFactory.getOrCreate(ragConfig)` ✅. `:152` is **not** provider resolution — it is `traceEntry.put("provider", ragConfig.getEmbeddingProvider())`. Argument still holds (one `EmbeddingModel` per `RagConfiguration`, line 138 inside the per-KB loop). | cosmetic |
| **"Dedup comes free."** | **Materially wrong for EDDI.** `DefaultContent.equals` → `Objects.equals(this.textSegment, that.textSegment())` ✅, but `TextSegment.equals` (core 1.18.0, lines 51–57) compares `text` **AND** `metadata`. `RagIngestionService.java:80` stamps `Metadata.from("source", documentName).put("kbId", kbId)` on every ingested document, and the splitter propagates document metadata to every segment. Two KBs ⇒ different `kbId` ⇒ segments **already unequal** ⇒ **dedup never fires** for anything EDDI ingested. It fires only for stores populated outside `RagIngestionService` with byte-identical metadata. | Spec below keeps dedup mechanically correct but does **not** claim it as a delivered benefit. It also means the provenance/dedup "tension" is largely moot — see §5. |
| `ContentMetadata`, `DefaultContentAggregator`, `ReciprocalRankFuser` on classpath | ✅ verified in `langchain4j-core-1.18.0.jar`. `ContentMetadata` = `enum {SCORE, RERANKED_SCORE, EMBEDDING_ID}`. `ReciprocalRankFuser.fuse(Collection<List<Content>>)` and `fuse(Collection<List<Content>>, int k)`, k default 60, `ensureBetween(k,1,MAX)`. `DefaultContentAggregator.aggregate(Map<Query, Collection<List<Content>>>)`. | none |

**Design consequence of the `kbId` finding:** `DefaultContentAggregator` is the wrong entry point. It is a two-stage fuser whose stage 1 is per-`Query` — EDDI has exactly **one** `Query` (`Query.from(userQuery)`, line 145). `aggregate(Map.of(query, listOfLists))` degenerates to `ReciprocalRankFuser.fuse(listOfLists)` with one extra map allocation and one extra pass. **Call `ReciprocalRankFuser.fuse` directly.** Keep `DefaultContentAggregator` out — it buys nothing today and drags in a per-query abstraction EDDI does not have until query expansion lands. (Revisit if/when multi-query lands; the swap is one line.)

---

### 1. Files to touch

| File | Action |
|---|---|
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\impl\RagContextProvider.java` | MODIFY — per-retriever list collection, fusion gate, provenance side-map, grouped formatting |
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\model\LlmConfiguration.java` | MODIFY — add `ragFusion`, `ragFusionK`, `ragMaxTotalResults` to `Task` (fields after `ragDefaults`, line 218) |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\modules\llm\impl\RagContextProviderExtendedTest.java` | MODIFY — add multi-KB helper + fusion nest |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\modules\llm\impl\RagContextProviderFusionTest.java` | CREATE |
| `C:\dev\git\EDDI\docs\changelog.md` | MODIFY — entry per AGENTS.md §2 rule 8 |

No new stores, no new REST, no new `ExtensionDescriptor` entry (verified: `LlmTask.getExtensionDescriptor()` registers only `KEY_URI` at line 930–931; all task-level RAG fields live inside the `langchain.json` document and are not Manager-rendered from a descriptor).

#### 1.1 `RagContextProvider` — structural change

Replace the flat `List<RetrievalResult> allResults` with per-retriever lists + a provenance side-map.

```java
// new imports
import dev.langchain4j.rag.content.aggregator.ReciprocalRankFuser;
import java.util.LinkedHashMap;
```

Inside `retrieveContext`, replace line 105 declaration:

```java
List<List<Content>> perKbResults = new ArrayList<>();          // one list per KB, KB-declaration order
Map<Content, String> contentToKb = new LinkedHashMap<>();      // provenance, first-writer-wins
List<Map<String, Object>> traceEntries = new ArrayList<>();
```

Replace line 159 (`allResults.addAll(...)`) with:

```java
if (!relevant.isEmpty()) {
    perKbResults.add(relevant);
    for (Content c : relevant) {
        contentToKb.putIfAbsent(c, kbName);   // Content.equals == textSegment equality
    }
}
```

Replace lines 177–188 with:

```java
if (perKbResults.isEmpty()) {
    return null;
}

List<RetrievalResult> ordered = fuseIfEnabled(task, perKbResults, contentToKb);

String formattedContext = formatRagContext(ordered);
var ragContextData = dataFactory.createData("rag:context:" + taskId, formattedContext);
currentStep.storeData(ragContextData);
return formattedContext;
```

#### 1.2 New private methods (place immediately after `retrieveContext`, before `formatRagContext`)

```java
/**
 * Applies Reciprocal Rank Fusion across knowledge bases when enabled and more
 * than one KB returned results. With a single KB the input list is returned in
 * retrieval order — byte-identical to pre-fusion behaviour.
 */
private List<RetrievalResult> fuseIfEnabled(LlmConfiguration.Task task,
                                            List<List<Content>> perKbResults,
                                            Map<Content, String> contentToKb) {

    String mode = task.getRagFusion() == null ? "auto" : task.getRagFusion();
    boolean fuse = !"none".equalsIgnoreCase(mode)
            && ("rrf".equalsIgnoreCase(mode) || perKbResults.size() > 1);

    List<Content> ordered;
    if (fuse && perKbResults.size() > 1) {
        int k = resolveFusionK(task);
        ordered = ReciprocalRankFuser.fuse(perKbResults, k);
        LOGGER.debugf("RAG RRF fusion over %d knowledge bases (k=%d) → %d contents",
                perKbResults.size(), k, ordered.size());
    } else {
        ordered = flatten(perKbResults);
    }

    Integer cap = task.getRagMaxTotalResults();
    if (cap != null && cap > 0 && ordered.size() > cap) {
        ordered = ordered.subList(0, cap);
    }

    List<RetrievalResult> results = new ArrayList<>(ordered.size());
    for (Content c : ordered) {
        results.add(new RetrievalResult(contentToKb.getOrDefault(c, "unknown"), c));
    }
    return results;
}

private static int resolveFusionK(LlmConfiguration.Task task) {
    Integer k = task.getRagFusionK();
    return (k == null || k < 1) ? 60 : k;
}

private static List<Content> flatten(List<List<Content>> perKbResults) {
    List<Content> all = new ArrayList<>();
    perKbResults.forEach(all::addAll);
    return all;
}
```

**Gate semantics — the single-KB byte-identity guarantee:** when `perKbResults.size() == 1`, `flatten` returns the retriever's list unchanged and `contentToKb` maps every element back to the one `kbName`. `formatRagContext` then emits exactly one header and the same chunk order as today. This holds even for `ragFusion: "rrf"` (the `&& perKbResults.size() > 1` in the branch condition), because RRF over a single list is a stable re-sort that would still be an unnecessary object churn.

> Trap: `ReciprocalRankFuser.fuse` calls `ensureBetween(k, 1, Integer.MAX_VALUE, "k")` — it **throws** on `k < 1`. `resolveFusionK` clamps a bad stored config to 60 rather than exploding a live conversation. Do not pass `task.getRagFusionK()` through unvalidated.

#### 1.3 `formatRagContext` — grouping fix (replaces lines 194–212)

After fusion, results interleave KBs, so the emit-header-on-change logic repeats `### Source:` headers. Group by KB, preserving **first-appearance order of the KB in the fused ranking** (so the KB owning the top-ranked chunk leads), and chunk order within a KB from the fused ranking.

```java
/**
 * Formats retrieval results into a structured context string for the LLM.
 * Results are grouped by knowledge base; KB order follows first appearance in
 * {@code results}, chunk order within a KB follows {@code results}.
 */
private String formatRagContext(List<RetrievalResult> results) {
    Map<String, List<RetrievalResult>> grouped = new LinkedHashMap<>();
    for (RetrievalResult r : results) {
        grouped.computeIfAbsent(r.kbName(), k -> new ArrayList<>()).add(r);
    }

    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (var entry : grouped.entrySet()) {
        if (!first) {
            sb.append("\n");
        }
        first = false;
        sb.append("### Source: ").append(entry.getKey()).append("\n\n");
        for (RetrievalResult r : entry.getValue()) {
            var seg = r.content().textSegment();
            sb.append(seg != null && seg.text() != null ? seg.text() : "").append("\n\n");
        }
    }
    return sb.toString().trim();
}
```

Byte-identity check for the single-KB / no-fusion path: old code emitted `### Source: X\n\n` then each chunk + `\n\n`, `.trim()`ed. New code emits the same, with the inter-group `\n` suppressed for the first group exactly as `currentKb != null` did. Identical output. For multi-KB **without** fusion (`ragFusion: "none"`), input is already KB-contiguous, so grouping is a no-op and output is also byte-identical to today.

#### 1.4 Trace additions

Extend the trace payload so fusion is auditable (append after the per-KB loop, before the `traceEntries.isEmpty()` check at line 172):

```java
if (perKbResults.size() > 1) {
    Map<String, Object> fusionTrace = new HashMap<>();
    fusionTrace.put("fusion", fuseApplied ? "rrf" : "none");
    fusionTrace.put("fusionK", resolveFusionK(task));
    fusionTrace.put("inputLists", perKbResults.size());
    fusionTrace.put("inputCount", perKbResults.stream().mapToInt(List::size).sum());
    fusionTrace.put("outputCount", orderedSize);
    traceEntries.add(fusionTrace);
}
```

This forces a small reordering: compute the fused list **before** storing the trace. Restructure to: loop → `List<RetrievalResult> ordered = fuseIfEnabled(...)` → build fusion trace from `ordered.size()` and a `boolean fuseApplied` returned alongside (simplest: make `fuseIfEnabled` return a small local record `record FusionOutcome(List<RetrievalResult> results, boolean fused, int k) {}` declared next to `RetrievalResult` at line 68) → store trace → format. Do **not** store the trace before the loop-exit guard; the existing `perKbResults.isEmpty() → return null` must still run **after** trace storage, exactly as today (lines 172–179 order), or the error-trace test at `RagContextProviderExtendedTest:252` breaks.

---

### 2. Config surface

All three fields go on `LlmConfiguration.Task` (`src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java`), declared immediately after `private RagDefaults ragDefaults;` (line 218), getters/setters after `setRagDefaults` (line 526).

| JSON field | Java type | Default | Meaning | Validation |
|---|---|---|---|---|
| `ragFusion` | `String` | `"auto"` (field initialised to `"auto"`) | `"auto"` = RRF when >1 KB returned results, passthrough otherwise. `"rrf"` = same as auto today (reserved: forces fusion when multi-query lands). `"none"` = never fuse, concatenate in KB-declaration order (today's behaviour). | Unknown value ⇒ treated as `"auto"`; no exception. Case-insensitive. |
| `ragFusionK` | `Integer` | `null` → 60 | RRF `k` constant. Lower = top-of-list dominance, higher = flatter. | `null` or `< 1` ⇒ clamped to 60 in `resolveFusionK`. Never passed raw to `ensureBetween`. |
| `ragMaxTotalResults` | `Integer` | `null` (uncapped) | Post-fusion cap on total chunks across all KBs. | `null` or `<= 0` ⇒ no cap. Applied after fusion, before grouping. |

```java
/**
 * Cross-knowledge-base fusion strategy: "auto" (default — Reciprocal Rank
 * Fusion when more than one KB returns results), "rrf", or "none"
 * (concatenate in KB-declaration order — pre-6.x behaviour).
 */
private String ragFusion = "auto";

/** RRF ranking constant k (null or &lt; 1 → 60). */
private Integer ragFusionK;

/** Cap on total chunks after fusion (null → uncapped). */
private Integer ragMaxTotalResults;

public String getRagFusion() { return ragFusion; }
public void setRagFusion(String ragFusion) { this.ragFusion = ragFusion; }
public Integer getRagFusionK() { return ragFusionK; }
public void setRagFusionK(Integer ragFusionK) { this.ragFusionK = ragFusionK; }
public Integer getRagMaxTotalResults() { return ragMaxTotalResults; }
public void setRagMaxTotalResults(Integer ragMaxTotalResults) { this.ragMaxTotalResults = ragMaxTotalResults; }
```

Sample config:

```json
{
  "tasks": [{
    "id": "answer",
    "knowledgeBases": [
      { "name": "product-docs", "maxResults": 5 },
      { "name": "support-tickets", "maxResults": 5 }
    ],
    "ragFusion": "auto",
    "ragFusionK": 60,
    "ragMaxTotalResults": 8
  }]
}
```

**Extension descriptor:** none. Verified `LlmTask.getExtensionDescriptor()` (lines ~928–932) exposes only `uri`; the Manager edits `langchain.json` as a document. No descriptor entry to add.

---

### 3. Stored-config back-compat

| Scenario | Behaviour after deploy |
|---|---|
| Existing `langchain.json` with **one** KB (explicit ref or workflow discovery yielding one RAG step) | `ragFusion` deserialises to its field default `"auto"`; `perKbResults.size() == 1` ⇒ passthrough. Output string **byte-identical**. No change. |
| Existing config with **multiple** KBs | **Semantics change on deploy.** Chunks are RRF-ranked across KBs and grouped by KB with the top-ranked KB first, instead of strict KB-declaration order. The *set* of chunks is unchanged (no cap by default), the *order* and the header order change. This is the intended, doc-sanctioned change. An operator wanting the old order sets `"ragFusion": "none"`. **Call this out in the changelog and in `docs/`.** |
| Existing config with multiple KBs where a KB returned zero results | Previously that KB contributed no header (loop emitted nothing). Same now — empty lists are not added to `perKbResults`. No change. |
| Config authored *after* this change, loaded by an *older* EDDI (rollback / ZIP import into an old instance) | `SerializationCustomizer.java:38` sets `FAIL_ON_UNKNOWN_PROPERTIES=false`; `TaskListParser.java:35` does the same. The three unknown fields are silently ignored. Safe. |
| No field is removed or renamed | — |

---

### 4. Tests

#### Existing coverage (verified paths)

| Test class | Relevance |
|---|---|
| `src/test/java/ai/labs/eddi/modules/llm/impl/RagContextProviderTest.java` | 5 tests, early-return paths only (lines 69, 81, 93, 109, 127). Unaffected. |
| `src/test/java/ai/labs/eddi/modules/llm/impl/RagContextProviderExtendedTest.java` | The real coverage. **All retrieval helpers set up exactly one RAG workflow step** (`setupWorkflowWithRagConfig`, lines 327–353) and stub `resourceClientLibrary.getResource(any(URI.class), eq(RagConfiguration.class))` to return a single config regardless of URI. So every existing assertion runs the single-KB path ⇒ **none should break**. Verify by running, do not assume. |
| `RagContextProviderExtendedTest:314-323` `retrievalResultRecord` | Touches `RagContextProvider.RetrievalResult` directly — keep the record's shape `(String kbName, Content content)` unchanged so this compiles. |
| `LlmTaskCoverage2Test`, `LlmTaskTest`, `LlmTaskBranchTest`, `LlmTaskDeepBranchTest`, `LlmTaskExtendedBranchTest`, `LlmTaskExtendedTest`, `LlmTaskConfigureTest`, `LlmTaskResumeModeTest`, `LlmTaskCoverageTest` | All mock `RagContextProvider` wholesale. Unaffected — do not touch. |

#### Vacuous-test flag

`RagContextProviderExtendedTest` line 269: `verify(currentStep, atLeastOnce()).storeData(any())` in `embeddingModelCreationFails` asserts only "something was stored" — it does not assert the stored key is `rag:trace:task1` nor that the entry carries `error`. Since this spec adds a *second* kind of trace entry, tighten it as part of this work:

```java
@Test
void embeddingModelCreationFails_storesErrorTraceOnly() {
    // ... existing setup ...
    verify(dataFactory).createData(eq("rag:trace:task1"), argThat(o -> {
        List<Map<String, Object>> t = (List<Map<String, Object>>) o;
        return t.size() == 1 && t.get(0).containsKey("error") && !t.get(0).containsKey("fusion");
    }));
    verify(dataFactory, never()).createData(eq("rag:context:task1"), any());
}
```

#### New test class — `RagContextProviderFusionTest.java`

Needs a multi-KB harness the existing helpers cannot provide. Add:

```java
private void setupTwoKbs(String kbA, List<String> chunksA, String kbB, List<String> chunksB)
```

which builds **two** `WorkflowConfiguration.WorkflowStep`s with distinct `uri` config values (`.../rag/rag-a?version=1`, `.../rag/rag-b?version=1`) and stubs `resourceClientLibrary.getResource(argThat(u -> u.toString().contains("rag-a")), eq(RagConfiguration.class))` per-KB, plus two distinct `EmbeddingStore` mocks routed by `embeddingStoreFactory.getOrCreate(any(), eq(kbA))` / `eq(kbB)`. Each `EmbeddingMatch` carries a **descending** score so the retriever list order is the KB's own ranking.

| Test method | Assertion |
|---|---|
| `singleKb_noFusion_outputByteIdenticalToLegacy()` | One KB, three chunks. Assert the exact string `"### Source: docs\n\nA\n\nB\n\nC"`. Guards the byte-identity gate. |
| `singleKb_ragFusionRrf_stillPassthrough()` | Same as above with `ragFusion:"rrf"`. Same exact string. |
| `twoKbs_defaultAuto_appliesRrfOrdering()` | KB-A `[a1,a2]`, KB-B `[b1,b2]`. RRF k=60 gives a1=b1=1/61, a2=b2=1/62; `LinkedHashMap` insertion order + stable `List.sort` ⇒ `[a1,b1,a2,b2]`. Grouped output ⇒ `### Source: kbA\n\na1\n\na2\n\n\n### Source: kbB\n\nb1\n\nb2`. Assert the exact string — this is the interleave-then-group fix. |
| `twoKbs_ragFusionNone_preservesDeclarationOrder()` | Same inputs, `ragFusion:"none"` ⇒ `[a1,a2,b1,b2]`, identical grouped output to the legacy concatenation. Assert exact string. |
| `twoKbs_headerEmittedOncePerKb()` | Assert `countOccurrences(result, "### Source:") == 2` on the fused result. Directly guards the doc's third "will bite". |
| `twoKbs_ragMaxTotalResults_capsAfterFusion()` | KB-A 3 chunks, KB-B 3 chunks, `ragMaxTotalResults:2`. Assert result contains exactly 2 chunk texts and both are rank-1 items (`a1`,`b1`). |
| `ragFusionK_null_defaultsTo60()` | Two KBs, `ragFusionK` unset; assert the `rag:trace:*` fusion entry has `fusionK == 60`. |
| `ragFusionK_zero_clampedTo60_noException()` | `ragFusionK:0`. Assert no exception (`ensureBetween` would throw) and trace `fusionK == 60`. |
| `ragFusionK_unknownMode_treatedAsAuto()` | `ragFusion:"banana"`, two KBs ⇒ fused ordering, no exception. |
| `identicalSegmentsAcrossKbs_dedupToSingleEntry()` | Two KBs both returning `TextSegment.from("same")` **with no metadata** (bypasses the `kbId` stamp — models a store populated outside `RagIngestionService`). Assert the chunk text appears once and provenance is the **first** KB (first-writer-wins via `putIfAbsent`). |
| `differingSegmentMetadataAcrossKbs_noDedup()` | Same text, `TextSegment.from("same", Metadata.from("kbId","a"))` vs `…"b"`. Assert the text appears **twice**, once under each header. Pins the corrected understanding from §0 so a future refactor of `RagIngestionService` metadata does not silently change dedup. |
| `fusionTraceRecordedForMultiKb()` | Assert `rag:trace:task1` payload's last entry has `fusion=="rrf"`, `inputLists==2`, `inputCount==4`, `outputCount==4`. |
| `noFusionTraceForSingleKb()` | Assert no trace entry contains key `"fusion"`. |

All are plain Mockito unit tests — no sockets, no Docker. Runnable locally with `.\mvnw.cmd test -Dtest=RagContextProviderFusionTest`.

> Trap (from memory index): if any of these live in a `@Nested` class, `-Dtest=Class#method` silently runs 0 tests and exits 0. Filter by whole class.

---

### 5. Provenance vs. dedup — the decision

**Recommendation: out-of-band side-map (`Map<Content, String> contentToKb`, `putIfAbsent`, first-writer-wins). Do NOT stamp `kbName` into `TextSegment` metadata.**

Reasons, in order of weight:

1. **Stamping requires reconstructing every `TextSegment`** (`Metadata` is copy-on-build), which changes `TextSegment.equals`, which changes `Content.equals`, which changes RRF's `Map<Content,Double>` key — i.e. it disables dedup *and* the rank-boost-for-appearing-twice, the only mechanical benefit RRF's map gives us.
2. **The side-map is free.** `Content.equals`/`hashCode` are the same relation RRF uses, so a `LinkedHashMap<Content,String>` keyed by the same objects resolves provenance for every fused entry in O(1) with zero reconstruction, zero allocation beyond the map.
3. **`putIfAbsent` gives a defined tie-break** (KB-declaration order wins) instead of "whichever KB the fuser happened to keep".
4. The doc's framing assumed dedup was a live benefit. Per §0 it mostly is not, given `kbId` stamping at ingestion — but that is an artefact of `RagIngestionService`, changeable later. The side-map is correct under **both** worlds; the metadata stamp is correct under neither.

`contentToKb.getOrDefault(c, "unknown")` is the belt-and-braces fallback; it should be unreachable (every fused `Content` came from a list we walked), and the `"unknown"` header is a visible signal if the invariant ever breaks.

---

### 6. Acceptance criteria

1. `.\mvnw.cmd compile` succeeds.
2. `.\mvnw.cmd test -Dtest=RagContextProviderTest` — 5/5 pass, unmodified.
3. `.\mvnw.cmd test -Dtest=RagContextProviderExtendedTest` — all pass; only the `embeddingModelCreationFails` assertion was tightened (§4), no other test body changed.
4. `.\mvnw.cmd test -Dtest=RagContextProviderFusionTest` — all 13 new tests pass.
5. `singleKb_noFusion_outputByteIdenticalToLegacy` asserts an **exact string**, not `contains`.
6. `grep -n "DefaultContentAggregator" src/main/java` returns **zero hits** (we call `ReciprocalRankFuser.fuse` directly — see §0).
7. `grep -n "allResults" src/main/java/ai/labs/eddi/modules/llm/impl/RagContextProvider.java` returns zero hits.
8. `RagContextProvider` has no new instance fields (stateless singleton invariant, AGENTS.md §4.1 rule 2); all fusion state is method-local. Verify by inspection of the field block, lines 47–52.
9. `twoKbs_headerEmittedOncePerKb` passes — `### Source:` count equals distinct KB count for a fused multi-KB result.
10. `ragFusionK_zero_clampedTo60_noException` passes — no `IllegalArgumentException` from `ensureBetween`.
11. A stored `langchain.json` containing none of the three new fields round-trips through Jackson with `ragFusion == "auto"`, `ragFusionK == null`, `ragMaxTotalResults == null`.
12. `.\mvnw.cmd validate` (Checkstyle) clean; `.\mvnw.cmd formatter:format` produces no diff after the change.
13. `docs/changelog.md` entry lands in the same commit as the code, and explicitly states the multi-KB ordering behaviour change plus the `"ragFusion": "none"` escape hatch.

---

### 7. Traps

| # | Trap |
|---|---|
| T1 | **`ReciprocalRankFuser.fuse(lists, k)` throws on `k < 1`** (`ensureBetween(k, 1, Integer.MAX_VALUE, "k")`). A stored config with `ragFusionK: 0` must never reach it. Clamp in `resolveFusionK`. |
| T2 | **Single-KB byte-identity is the whole gate.** Any refactor that routes the one-KB case through `fuse` (even harmlessly) risks reordering equal-score chunks and invalidates the "zero risk to existing agents" claim. Keep `&& perKbResults.size() > 1` in the branch condition. |
| T3 | **Trace-then-guard ordering.** Today lines 172–175 store the trace *before* the `allResults.isEmpty() → return null` guard at 177. Preserve that. `RagContextProviderExtendedTest:252` (`embeddingModelCreationFails`) depends on the error trace being stored on a null-returning path. |
| T4 | **`TextSegment.equals` includes `Metadata`.** Do not "improve" `RagIngestionService.java:80` to drop `kbId` in order to make dedup work — that would silently merge chunks across KBs and destroy provenance at the store level. Out of scope (§8). |
| T5 | **`DefaultContent.equals` uses `getClass() != o.getClass()`**, not `instanceof`. Any custom `Content` implementation would never equal a `DefaultContent`. `EmbeddingStoreContentRetriever` produces `DefaultContent` (via `Content.from(...)`, line 264), so this is fine today — but do not introduce a wrapper `Content` type. |
| T6 | **Stateless singleton.** `RagContextProvider` is `@ApplicationScoped`. No fusion caches, no memoised aggregator instance, no mutable fields. `ReciprocalRankFuser.fuse` is `static` and pure. |
| T7 | **`Map<Content, String>` must be `LinkedHashMap`**, not `HashMap` — determinism of the `"unknown"`-free provenance is not affected, but the grouped-KB header order in `formatRagContext` is derived from fused rank order, and any incidental iteration over `contentToKb` must be reproducible for the exact-string tests. |
| T8 | **`ordered.subList(0, cap)` returns a view.** It is consumed immediately by the `RetrievalResult` loop, so no escape — but do not store the view in memory data. |
| T9 | Per-KB `maxResults` still applies **before** fusion. A two-KB task with `maxResults:5` each and no `ragMaxTotalResults` now sends up to 10 chunks to the LLM, same as today. Fusion does not reduce token count; if a designer wants that, they set `ragMaxTotalResults`. Document this — it is the single most likely misreading. |

---

### 8. Out of scope

- `ReRankingContentAggregator` / `ScoringModelFactory` / `firstStageMaxResults` — that is **R3b, deferred to P4**.
- `DefaultContentAggregator` itself, and any multi-`Query` / query-expansion plumbing.
- **D14** (serial KB fan-out → parallel). The per-KB loop stays serial. This change makes D14 *easier* (each KB already produces an independent list), but do not do it here.
- **D8** (`injectionStrategy` / `contextTemplate` dead config on `KnowledgeBaseReference` and `RagDefaults`) — still dead after this change; leave it.
- `RagIngestionService` metadata stamping (T4).
- `EmbeddingModelFactory`, `EmbeddingStoreFactory`, `RagConfiguration` — no changes.
- `LlmTask.java:353` call site — signature of `retrieveContext` is unchanged.
- Micrometer instrumentation of the RAG subsystem (the doc notes zero `MeterRegistry` references there; adding it is its own item).

---

### 9. Sequencing

| Step | Content | Independently committable |
|---|---|---|
| 1 | `LlmConfiguration.Task`: three fields + accessors. `.\mvnw.cmd compile`. | ✅ `feat(rag): add ragFusion/ragFusionK/ragMaxTotalResults task config` (inert — nothing reads them yet) |
| 2 | `formatRagContext` grouping rewrite, **no fusion yet**. Existing tests must stay green (grouping is a no-op on KB-contiguous input). | ✅ `refactor(rag): group RAG context by knowledge base before formatting` |
| 3 | Per-KB list collection + `contentToKb` side-map + `fuseIfEnabled` + `FusionOutcome` record. Fusion live. | ✅ `feat(rag): RRF fusion across knowledge bases via ReciprocalRankFuser` |
| 4 | Fusion trace entry + tightened `embeddingModelCreationFails` assertion. | ✅ `test(rag): assert RAG trace contents; record fusion in trace` |
| 5 | `RagContextProviderFusionTest` (13 tests) + multi-KB harness. | ✅ `test(rag): multi-KB fusion, dedup and ordering coverage` |
| 6 | `docs/changelog.md` — fold into step 5's commit or the final one per AGENTS.md §2 rule 8 (must be on the same branch). | — |

Effort: **S** — ~90 lines net in `RagContextProvider`, ~25 in `LlmConfiguration`, ~250 test lines.

---

I have everything verified. Writing the replacement spec.

## R2 — Anthropic `cacheTools` + thinking params

### 1. Changes since PR #593

**Premise fully intact. #593 implemented NONE of R2.** R2's core surface — `AnthropicLanguageModelBuilder`, `AnthropicMapper` (in `langchain4j-anthropic-1.18.0` jar, not the repo), `ChatModelRegistry.filterParams`/cache key, and the `AgentOrchestrator` tool loop — was **not touched** by #593. The only drift is line numbers in the two files #593 did rewrite (`LlmTask` +122, `CascadingModelExecutor` +77) and the config POJO (`LlmConfiguration` +127/-43). The item is still purely additive param-plumbing to one builder. Scope unchanged: **6 files, 7 keys.**

| Old-spec claim (baseline 4b5b68876, pre-#593) | Status in CURRENT tree | Action |
|---|---|---|
| `AnthropicLanguageModelBuilder`: constants `:21-26`, `build` `:28-52`, `buildStreaming` `:54-78`, `return builder.build()` `:51`/`:77` | ✅ **Byte-identical.** #593 did not touch this file (last commits: `253e5ec7d` SPDX, `f7da4c14f` formatter). `grep -rniE "cache_control\|cacheControl\|cacheTools\|cacheSystemMessages" src/main/java` → **zero hits**. | §2.1 applies **verbatim** |
| `ChatModelRegistry`: `filterParams` `:152-165`, `remove(KEY_LOG_RESPONSES)` `:163`, `return returnMap` `:164`, cache key `:96`/`:124`, `record ModelCacheKey` `:251`, `evictMatching` `:222` | ✅ **Byte-identical.** #593 did not touch. | §2.2, T2 stand |
| `AgentOrchestrator` tool loop: `executeWithRetry(() -> {` `:870`, `for … i < maxIterations` `:886`, `ChatRequest.builder().messages(currentMessages)` `:896`, `toolSpecifications(activeSpecs)` `:899`, `Thread.interrupted()` `:892`, LAZY `activeSpecs.add(spec)` `:1459` | ✅ **Byte-identical at every cited line.** #593 gutted `AgentExecutionHelper` into a shim, but the **call site** `:870` still reads `AgentExecutionHelper.executeWithRetry(() -> {` (now delegates to `RetryConfiguration.executeWithRetry`). Intra-turn identical-prefix re-send is unchanged. | keep — **intra-turn `cacheTools` payoff intact** |
| `AnthropicMapper` breakpoints: system `:281`/`:301`/`:302-303`, tools `:446`/`:455`/`:471-496`; `AnthropicChatModel.java:846` `sendThinking` default `true`, `:886-895` `toThinking`, setters `:532-614` | ✅ **Unchanged** — external jar, langchain4j pinned `1.18.0` (branch `chore/langchain4j-1.18.0`). | keep |
| `LlmTask`: Qute call site `:320`, method decl `:846`; RAG appends `:342`/`:358`; masking `:370`; counterweight `:378`; response-format `:386`/`:391` | ⚠️ **+1 shift in region; method decl +121.** NEW: Qute call site **`:321`** (live path) + decl **`:967`**; httpCall RAG **`:343`**; vector RAG **`:359`**; masking **`:371`**; counterweight **`:379`**; response-format **`:387`**/**`:392`**. `getExtensionDescriptor` `:965`→**`:1086`**, `KEY_URI` put **`:1091`**. | renumber |
| — | 🆕 **#593 added a SECOND Qute-over-params site.** `LlmTask.java:877` (HITL `executeResume` path) re-runs `runTemplateEngineOnParams(...)` then `chatModelRegistry.getOrCreate(...)` `:879` to rebuild the model on resume. Another per-turn `systemMessage` reconstruction ⇒ **reinforces T1** (cross-turn `cacheSystemMessages` miss even on the resume path). Not a new mutator, a new *re-template site*. | strengthen T1 |
| `ConversationHistoryBuilder`: summary append `:84-86`/`:186-188` | ⚠️ **-1.** Breakpoint statement now `:85` (`buildMessages`) / `:187` (`buildTokenAwareMessages`); guards `:84`/`:186`. Both still `systemMessage = (isNullOrEmpty(systemMessage) ? "" : systemMessage + "\n\n") + summaryPrefix`. | renumber |
| `CascadingModelExecutor`: `mergeParams(base, templateParams(step.getParameters()…))` `:245`, `getOrCreate` `:267`, `getOrCreateStreaming` `:280`; judge `:467-468`; `augmentMessagesForStructuredOutput` `:619-637` incl. confidence rewrites `:626`/`:634` | ⚠️ **Shifted +6 upper / ~+40 lower** (#593 +77). NEW: `mergeParams` call **`:251`**, `getOrCreate` **`:273`**, `getOrCreateStreaming` **`:286`**; judge `templateParams(judgeConfig.getParameters()…)` **`:504-505`** (no `mergeParams` — judge still does NOT inherit); `augmentMessagesForStructuredOutput` decl **`:659`**, confidence rewrites **`:666`** (`sm.text() + buildConfidenceInstruction()`) / **`:674`** (prepend); `mergeParams` decl **`:683`**. All claims hold. | renumber |
| — | 🆕 **Cascade STILL has its own divergent classifier** (out of R2 scope, noted for context): `RETRYABLE_MESSAGE` `:798`, `isRetryableError` `:804`, one call site (errorType label) `:436`. #593 did not unify it with `RetryConfiguration`. **Do not touch here.** | — |
| `LlmConfiguration.LlmTask.parameters` field `:85`, getter `:387`, setter `:390`; cascade-step params `:1217`/`:1251`/`:1254`; judge params `:1300`/`:1311` | ⚠️ **Path + lines corrected.** File is `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java`. NEW: task params field **`:86`**, getter **`:406`**, setter **`:410`**; `CascadeStep` class **`:1291`**, params field **`:1301`** (javadoc "Merged with task-level params (step params win)" **`:1299-1300`**), getter **`:1334`**, setter **`:1338`**; `JudgeModelConfig` class **`:1379`**, params field **`:1384`**, getter **`:1394`**, setter **`:1398`** (cascade reads `judgeModel.getParameters()`; `getJudgeModel()` **`:1230`**). | renumber |
| `docs/langchain.md` §"Anthropic Claude" `:158-182` | ⚠️ Anchor `#### Anthropic Claude` **`:158`**, section runs to **`:183`** (next `#### Google Gemini` `:184`). | renumber |
| Test anchors: `LanguageModelBuildersTest.AnthropicTests` `:82-111` (vacuous `assertNotNull`); `ChatModelRegistryTest.SyncTests` `:86-129` | ⚠️ ±2. `AnthropicTests` class **`:82`**, `build()` **`:88`** (`assertNotNull` **`:98`**), `buildStreaming()` **`:103`** — **still vacuous**. `SyncTests` class **`:88`**; `getOrCreate_filtersSystemMessage` `:115`; `observabilityParamsDontAffectCacheKey` `:225`; `InvalidationTests` `:247`. | renumber |

**Net: same 6 files, same 7 keys. All patches unchanged; line refs corrected. T1 strengthened by the new resume-path re-template site.**

---

### 2. Files to touch

| File (absolute) | Action |
|---|---|
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\impl\builder\AnthropicLanguageModelBuilder.java` | **MODIFY** — 7 constants after `:26`, 1 private static helper, 7 guarded blocks × 2 methods |
| `C:\dev\git\EDDI\src\main\java\ai\labs\eddi\modules\llm\impl\ChatModelRegistry.java` | **MODIFY** — comment only, after `:163` |
| `C:\dev\git\EDDI\docs\langchain.md` | **MODIFY** — extend §`#### Anthropic Claude` (`:158-183`) |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\modules\llm\impl\builder\LanguageModelBuildersTest.java` | **MODIFY** — `@Nested class AnthropicTests` `:82` |
| `C:\dev\git\EDDI\src\test\java\ai\labs\eddi\modules\llm\impl\ChatModelRegistryTest.java` | **MODIFY** — new test in `@Nested class SyncTests` (`:88`) |
| `C:\dev\git\EDDI\docs\changelog.md` | **MODIFY** — entry in same commit (AGENTS.md §2 r8) |

**No file created/deleted. No POJO, store, REST, or descriptor change.**

#### 2.1 `AnthropicLanguageModelBuilder.java`

Verified current signatures — **unchanged from old spec**:
```java
@Override public ChatModel build(Map<String, String> parameters)                  // :28-29
@Override public StreamingChatModel buildStreaming(Map<String, String> parameters) // :54-55
```

Insert after `KEY_LOG_RESPONSES` (`:26`):
```java
private static final String KEY_CACHE_SYSTEM_MESSAGES = "cacheSystemMessages";
private static final String KEY_CACHE_TOOLS = "cacheTools";
private static final String KEY_THINKING_TYPE = "thinkingType";
private static final String KEY_THINKING_BUDGET_TOKENS = "thinkingBudgetTokens";
private static final String KEY_RETURN_THINKING = "returnThinking";
private static final String KEY_SEND_THINKING = "sendThinking";
private static final String KEY_DISABLE_PARALLEL_TOOL_USE = "disableParallelToolUse";
```

New private static helper, after `buildStreaming` (`:78`):
```java
/**
 * Anthropic rejects a {@code thinking} block carrying {@code budget_tokens}
 * without {@code type}. langchain4j emits the block when EITHER is set
 * (AnthropicChatModel.toThinking, 1.18.0 sources :886-895). Normalize:
 * budget without type implies "enabled".
 */
private static String resolveThinkingType(Map<String, String> parameters) {
    var type = parameters.get(KEY_THINKING_TYPE);
    if (!isNullOrEmpty(type)) {
        return type;
    }
    return isNullOrEmpty(parameters.get(KEY_THINKING_BUDGET_TOKENS)) ? null : "enabled";
}
```

Insert in **both** methods immediately before `return builder.build();` (`:51` and `:77`):
```java
if (!isNullOrEmpty(parameters.get(KEY_CACHE_SYSTEM_MESSAGES))) {
    builder.cacheSystemMessages(Boolean.parseBoolean(parameters.get(KEY_CACHE_SYSTEM_MESSAGES)));
}
if (!isNullOrEmpty(parameters.get(KEY_CACHE_TOOLS))) {
    builder.cacheTools(Boolean.parseBoolean(parameters.get(KEY_CACHE_TOOLS)));
}
var thinkingType = resolveThinkingType(parameters);
if (thinkingType != null) {
    builder.thinkingType(thinkingType);
}
if (!isNullOrEmpty(parameters.get(KEY_THINKING_BUDGET_TOKENS))) {
    builder.thinkingBudgetTokens(Integer.parseInt(parameters.get(KEY_THINKING_BUDGET_TOKENS)));
}
if (!isNullOrEmpty(parameters.get(KEY_RETURN_THINKING))) {
    builder.returnThinking(Boolean.parseBoolean(parameters.get(KEY_RETURN_THINKING)));
}
if (!isNullOrEmpty(parameters.get(KEY_SEND_THINKING))) {
    builder.sendThinking(Boolean.parseBoolean(parameters.get(KEY_SEND_THINKING)));
}
if (!isNullOrEmpty(parameters.get(KEY_DISABLE_PARALLEL_TOOL_USE))) {
    builder.disableParallelToolUse(Boolean.parseBoolean(parameters.get(KEY_DISABLE_PARALLEL_TOOL_USE)));
}
```

No import changes (`Map` `:15`, `isNullOrEmpty` `:17` already imported).

**7 setters (1.18.0 sources)** — [UNVERIFIED — implementer must reconfirm against the jar on the classpath; unchanged from old spec, no reason to re-pull]:

| Setter | `AnthropicChatModelBuilder` | `AnthropicStreamingChatModelBuilder` |
|---|---|---|
| `cacheSystemMessages(Boolean)` | `AnthropicChatModel.java:532` | `AnthropicStreamingChatModel.java:389` |
| `cacheTools(Boolean)` | `:547` | `:404` |
| `thinkingType(String)` | `:555` | `:412` |
| `thinkingBudgetTokens(Integer)` | `:563` | `:420` |
| `returnThinking(Boolean)` | `:598` | `:456` |
| `sendThinking(Boolean)` | `:614` | `:472` |
| `disableParallelToolUse(Boolean)` | `:417` | `:610` |

#### 2.2 `ChatModelRegistry.java` — comment ONLY

Insert after `returnMap.remove(KEY_LOG_RESPONSES);` (`:163`), before `return returnMap;` (`:164`):
```java
// NOTE: provider-native settings (e.g. Anthropic cacheSystemMessages / cacheTools /
// thinkingType / thinkingBudgetTokens / returnThinking / sendThinking /
// disableParallelToolUse) are deliberately NOT removed here. This is a DENYLIST whose
// remainder forms the ModelCacheKey (:96, :124); those settings are baked into the built
// model's defaultRequestParameters, so they change model IDENTITY. Removing them would let
// two tasks with different cache/thinking settings share one cached ChatModel instance.
```

---

### 3. Config surface

**No POJO change.** `LlmConfiguration.LlmTask.parameters` is `Map<String,String>` — field `:86`, getter `:406`, setter `:410`. Cascade step params: `CascadeStep` `:1291`, field `:1301`, getter `:1334`, setter `:1338` (javadoc "step params win" `:1299-1300`). Judge params: `JudgeModelConfig` `:1379`, field `:1384`, getter `:1394`.

**No extension-descriptor change.** `LlmTask.getExtensionDescriptor()` `:1086-…` declares only `KEY_URI` (`:1091`). Model parameters were never descriptor-declared; Manager renders `parameters` free-form.

```json
{
  "llmTasks": [{
    "id": "main",
    "type": "eddi://ai.labs.llm.anthropic",
    "parameters": {
      "apiKey": "${vault:anthropic_key}",
      "modelName": "claude-sonnet-4-6",
      "cacheTools": "true",
      "thinkingType": "enabled",
      "thinkingBudgetTokens": "2048",
      "temperature": "1.0"
    }
  }]
}
```

| Key (inside `parameters`) | Parsed as | Default when absent | Validation |
|---|---|---|---|
| `cacheTools` | `Boolean.parseBoolean` | unset → `null` → `NO_CACHE` | none; non-`"true"` ⇒ `false` |
| `cacheSystemMessages` | `Boolean.parseBoolean` | unset → `null` → `NO_CACHE` | **opt-in**; docs must warn (T1) |
| `thinkingType` | `String` verbatim | `null`, unless `thinkingBudgetTokens` set ⇒ `"enabled"` | provider rejects bad values |
| `thinkingBudgetTokens` | `Integer.parseInt` | `null` | `NumberFormatException` out of `build()` — same mode as existing `timeout` `:39` / `temperature` `:42` |
| `returnThinking` | `Boolean.parseBoolean` | `null` | none |
| `sendThinking` | `Boolean.parseBoolean` | `null` → **request default `true`** (`AnthropicChatModel.java:846`) | none |
| `disableParallelToolUse` | `Boolean.parseBoolean` | `null` | none |

All defaults preserve current behavior byte-for-byte (both cache types → `NO_CACHE`; `toThinking` → `null` unless type/budget non-null).

---

### 4. Stored-config back-compat

- **Nothing removed/renamed.** `parameters` is untyped `Map<String,String>` — arbitrary keys already round-trip through Mongo + ZIP import; `FAIL_ON_UNKNOWN_PROPERTIES` not engaged.
- **A stored config setting none of the 7 keys → byte-identical Anthropic request after deploy.** Every new builder call is `isNullOrEmpty`-guarded; every underlying default is `null`.
- **Previously-inert keys becoming live — re-verify before merge:** `grep -rniE "cacheTools|cacheSystemMessages|thinkingType|thinkingBudgetTokens|returnThinking|sendThinking|disableParallelToolUse" src/main/resources docs/agent-configs src/main/java` → **zero hits** in current tree (confirmed this pass).
- **Cache-key impact:** keys not denylisted ⇒ adding `cacheTools:"true"` yields a **new** `ModelCacheKey`, a freshly-built `ChatModel`; old instance stays until secret / global-var invalidation (`ChatModelRegistry.evictMatching` `:222`). Expected.
- **Cascade back-compat:** task-level keys propagate to every step via `mergeParams` (`CascadingModelExecutor.java:251`, decl `:683`). A stored cascade previously producing N cached models still produces N — under different keys. Non-Anthropic steps ignore the keys (their builders never read them). Judge does **not** inherit (`:504-505`).

---

### 5. Tests

**Existing coverage (verified):**

| Location | Relevance |
|---|---|
| `LanguageModelBuildersTest.java` `@Nested AnthropicTests` (`:82`): `build()` `:88-98`, `buildStreaming()` `:103-…` | Assert **only `assertNotNull(model)` (`:98`)** — **VACUOUS**: pass against a builder that ignores every parameter. Strengthen as part of this work. |
| `ChatModelRegistryTest.java`: `getOrCreate_filtersSystemMessage` `:115`, `getOrCreate_observabilityParamsDontAffectCacheKey` `:225` | Cover the denylist direction only. **No test asserts a non-denylisted param SPLITS the key.** |

**Nothing breaks** — no signature/default/wire-format change.

**New tests — `LanguageModelBuildersTest.AnthropicTests`** (assert via covariant `defaultRequestParameters()`; [UNVERIFIED — accessors `AnthropicChatModel.java:908` / `AnthropicStreamingChatModel.java:853`, `AnthropicChatRequestParameters.java:47,51,55,59,63,71,79` — reconfirm against jar]):

| Method | Assertion |
|---|---|
| `build_cacheParams_appearInDefaultRequestParameters()` | `cacheTools=true`,`cacheSystemMessages=true` ⇒ `.cacheTools()`/`.cacheSystemMessages()` both `TRUE` |
| `build_noCacheParams_leavesCacheSettingsNull()` | minimal ⇒ both accessors `null` (back-compat guard) |
| `build_thinkingBudgetOnly_defaultsThinkingTypeToEnabled()` | only `thinkingBudgetTokens=2048` ⇒ `.thinkingType()=="enabled"`, `.thinkingBudgetTokens()==2048` |
| `build_explicitThinkingType_isNotOverridden()` | `thinkingType=enabled`, no budget ⇒ `"enabled"`/`null` |
| `build_noThinkingParams_leavesThinkingNull()` | minimal ⇒ both `null` |
| `build_returnAndSendThinking_areApplied()` | `returnThinking=true`,`sendThinking=false` ⇒ `TRUE`/`FALSE` (use `false` for `sendThinking` — `TRUE` cannot distinguish applied value from request default) |
| `build_disableParallelToolUse_isApplied()` | `disableParallelToolUse=true` ⇒ `TRUE` |
| `build_invalidThinkingBudget_throwsNumberFormatException()` | `thinkingBudgetTokens="abc"` ⇒ `assertThrows(NumberFormatException.class, …)` |
| `buildStreaming_cacheAndThinkingParams_areApplied()` | same against `AnthropicStreamingChatModel.defaultRequestParameters()` — guards sync/streaming drift (T6) |

**New test — `ChatModelRegistryTest.SyncTests`:**

| Method | Assertion |
|---|---|
| `getOrCreate_providerNativeParamsSplitCacheKey()` | Two `getOrCreate("openai", …)` differing only in a `cacheTools` entry ⇒ **builder invoked twice**. Locks in that `filterParams` never denylists these keys. |

> ⚠️ **Do NOT write as `assertNotSame`.** The shared `openai` fixture returns the *same* mock instance every call. Build a **local** `ChatModelRegistry` (pattern: `@Nested InvalidationTests` `:247+`) whose `openai` builder increments an `AtomicInteger` in `build(...)`; assert counter `== 2`.

Both classes plain JUnit + Mockito — no Docker, no loopback ⇒ runnable locally.

---

### 6. Acceptance criteria

1. `.\mvnw.cmd compile` exits 0.
2. `.\mvnw.cmd validate` exits 0; `.\mvnw.cmd formatter:format` produces no diff on touched files (mvnw auto-formats — verify `git diff` is limited to intended changes).
3. `.\mvnw.cmd test -Dtest=LanguageModelBuildersTest` exits 0 with all 9 new Anthropic methods executed. **Filter by whole class only** — `-Dtest=Class#method` runs 0 tests / exits 0 for `@Nested`.
4. `.\mvnw.cmd test -Dtest=ChatModelRegistryTest` exits 0 including `getOrCreate_providerNativeParamsSplitCacheKey`.
5. `grep -nE "cacheTools|cacheSystemMessages|thinkingType|thinkingBudgetTokens|returnThinking|sendThinking|disableParallelToolUse" src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java` returns **only** comment lines — zero `returnMap.remove(...)` matches.
6. For each of the 7 keys: `grep -c "isNullOrEmpty(parameters.get(KEY_CACHE_TOOLS))" …/AnthropicLanguageModelBuilder.java` returns `2` (sync + streaming parity). Same for the other six.
7. `grep -rniE "cacheTools|cacheSystemMessages|thinkingBudgetTokens" src/main/resources docs/agent-configs` returns nothing.
8. `.\mvnw.cmd test -Dtest=LlmTaskCoverageTest,LlmTaskCoverage2Test,AgentOrchestratorExtendedTest,CascadingModelExecutorCoverageTest` exits 0 (no-regression sweep over #593-touched classes; this item does not modify them — a failure means an unrelated break). [UNVERIFIED — confirm these test class names still exist post-#593]
9. `docs/langchain.md` §"Anthropic Claude" documents all 7 keys, marks `cacheTools` recommended-on, and carries an explicit **"do NOT enable `cacheSystemMessages` if this agent uses RAG (`knowledgeBases`/`httpCallRag`), `conversationSummary`, `identityMasking`, `counterweight`, `convertToObject`, a `modelCascade` with confidence evaluation, or HITL tool approvals (resume path re-templates the system message)"** warning, with the last-system-block-breakpoint reason.
10. `docs/langchain.md` states cascade **step** params inherit task params (`mergeParams` `:251`) but the **judge** model does not (`:504-505`) — set the key inside `modelCascade.judgeModel.parameters` to cache the judge prefix.
11. `docs/changelog.md` top entry names branch, files, and the `filterParams` non-change rationale.

---

### 7. Traps

- **T1 — `cacheSystemMessages` is a net cost *increase*; #593 made it slightly worse.** The single system breakpoint lands on the **last** `SystemMessage` (`AnthropicMapper.java:301-303`). EDDI mutates that message's tail in **six** places: httpCall RAG `LlmTask.java:343`, vector RAG `:359`, identity masking `:371`, counterweight `:379` (channel-tag dependent), JSON response-format `:387`/`:392`, rolling summary `ConversationHistoryBuilder.java:85`/`:187` — plus a seventh on the cascade confidence path (`CascadingModelExecutor.java:666`/`:674`). Any one varying per turn ⇒ cross-turn **miss every turn** while paying Anthropic's cache-**write** premium. Qute runs over params at **two** sites now — live `LlmTask.java:321` and **HITL resume `:877`** — so `{properties.x}` in `systemMessage` varies per turn (and per resume) even with all seven off. Ship opt-in, default off, documented harmful.
- **T2 — do NOT touch `filterParams()`** (`ChatModelRegistry.java:152-165`). Denylist whose *remainder* is the `ModelCacheKey` (`:96`,`:124`). Adding these keys would make tasks with different cache/thinking settings share one `ChatModel`. Comment only.
- **T3 — `sendThinking` is an opt-OUT, not a requirement.** `AnthropicChatModel.java:846` applies `getOrDefault(parameters.sendThinking(), true)` — thinking blocks already echoed on tool-use turns, so `AgentOrchestrator`'s accumulating `currentMessages` (`:896`) works with thinking on and no extra config. Expose so designers can set `false` deliberately; document that `sendThinking:false` **with `thinkingType` + tools breaks the request** (Anthropic requires thinking blocks echoed on tool-use turns).
- **T4 — budget-without-type is a provider 400.** `toThinking` emits the block if **either** field non-null. Handled by `resolveThinkingType`; do not simplify away.
- **T5 — Anthropic requires `temperature = 1` with extended thinking.** Builder sets temperature `:41-43`. `temperature:"0.3"` + `thinkingType:"enabled"` ⇒ rejection. Document; do **not** add client-side validation (Golden Rule 1).
- **T6 — sync/streaming drift.** `build` (`:28-52`) and `buildStreaming` (`:54-78`) are hand-rolled duplicates. Every key in both; AC #6 enforces.
- **T7 — ~1024-token minimum.** Anthropic silently no-ops caching below a ~1024-token prefix. With a small tool set `cacheTools:true` is a **no-op, not a regression**. Document.
- **T8 — thread safety unaffected.** Builder is `@ApplicationScoped`, stateless; `resolveThinkingType` `static` + pure. No instance state.
- **T9 — cancellation forfeits reads (#593-era).** `AgentOrchestrator.java:892` `Thread.interrupted()` aborts the tool loop on a cascade per-step timeout. Cache writes paid, reads lost. Marginal, timeout-path only.
- **T10 — task params leak into every cascade step.** `CascadingModelExecutor.java:251` merges base params into each step. Task-level `cacheTools` reaches non-Anthropic steps — ignored by their builder but still splits their `ModelCacheKey`. Harmless; do **not** "fix" by denylisting (that is T2).
- **T11 — LAZY tool mode invalidates the tool prefix mid-turn** (pre-existing, commit `2e21e1c40`, not #593). `activateDiscoveredTools` appends to `activeSpecs` at `AgentOrchestrator.java:1459`; the next `toolSpecifications(activeSpecs)` (`:899`) sends a longer array ⇒ one extra cache write, then hits resume. Document; do not stabilize the tool list here.
- **T12 — counterweight `strict` caps iterations at 5** (`AgentOrchestrator.java:872`/`:882`, from default 10). Halves the intra-turn cache-read count on strict agents. Expected, document.
- **T13 (NEW, #593) — the HITL resume path rebuilds the model per resume.** `LlmTask.java:877-879` re-templates params + `getOrCreate` on `executeResume`. `cacheTools` still helps (tools prefix stable); but any `cacheSystemMessages` user pays another write on every resume. Fold into the T1 warning.

---

### 8. Out of scope

- `ChatModelRegistry.filterParams()` **behavior** (comment only).
- Any other builder in `.../impl/builder/` — **Bedrock also fronts Claude and is explicitly NOT in scope.**
- `ObservableChatModel` / D11 / R5.
- The §6 R2 follow-up (move RAG/summary/masking/counterweight/response-format out of the system-message tail) — now **seven** tail sites across `LlmTask`, `ConversationHistoryBuilder`, `CascadingModelExecutor`. Do not restructure here.
- `AnthropicChatRequestParameters` per-request plumbing — model-builder path suffices; do not touch `ChatRequest` construction in `AgentOrchestrator.java:896-902`, `LegacyChatExecutor`, or `StreamingLegacyChatExecutor` (the latter rewritten by #593 — leave alone).
- Unifying the cascade's own retry classifier (`CascadingModelExecutor.java:798-812`) with `RetryConfiguration` — a separate #593-adjacent concern, not R2.
- `AnthropicServerTool`, `AnthropicSkill`, `strictTools`, `returnCacheDiagnostics`, `midConversationSystemMessages`, `userId` — deliberately not adopted.
- `enableParallelExecution` deletion (D10) — `disableParallelToolUse` is the provider-side half; D10 is its own P3 item.
- Cascade judge-model param inheritance (`CascadingModelExecutor.java:504-505`) — documented, not changed.
- EDDI-Manager UI — no descriptor entry exists or is needed.

---

### 9. Sequencing

1. Patch `AnthropicLanguageModelBuilder.java` (§2.1) → `.\mvnw.cmd compile`.
2. Add comment to `ChatModelRegistry.java` (§2.2).
3. Strengthen `LanguageModelBuildersTest.AnthropicTests` + add `ChatModelRegistryTest.getOrCreate_providerNativeParamsSplitCacheKey` (§5) → run both test classes (AC #3, #4).
4. Update `docs/langchain.md` (AC #9, #10) — 7 keys, `cacheTools` recommended-on, `cacheSystemMessages` harmful-warning incl. HITL resume path.
5. No-regression sweep (AC #8).
6. `docs/changelog.md` entry + commit (single commit, human-attributed, no push without approval).

**Independent of the other five R-items.** No shared files with D1/D2/D3/D5/D11/R5. Can land first or last; zero merge coupling.

## R1 — Binary document ingestion for RAG

**Scope:** increments 1 and 2 specced to implementation depth; increment 3 (apache-poi) outline only. Tika is rejected (CVE-2025-66516) — do not add `langchain4j-document-parser-apache-tika` or any transitive Tika.

---

### 0. Corrections to the decision doc

| Doc claim (§7 R1, lines 283–291) | Verified? | Reality |
|---|---|---|
| `IRestRagIngestion.java:27,40` `@Consumes(TEXT_PLAIN)` + `String` body | ✅ correct | `:27` `@Consumes(MediaType.TEXT_PLAIN)`, `:40` `String documentContent` |
| `RagIngestionService.java:80` `Document.from(documentContent, …)`, no `DocumentParser` import | ✅ correct | `:80` exactly; imports are `Document`, `DocumentSplitter`, `Metadata`, `DocumentSplitters` only |
| `AttachmentTextExtractor` is `@ApplicationScoped`, stateless, `byte[]`-in, PDFBox 3.0.7 | ✅ correct | `pom.xml:267-269` = pdfbox 3.0.7. Class is `@ApplicationScoped`, only field is `private final int defaultMaxChars` |
| default cap `10_000` | ✅ correct | `DEFAULT_MAX_CHARS = 10_000`, config `eddi.attachments.extraction.max-chars` (`application.properties:165`) |
| **"four existing production consumers"** | ⚠️ **overstated** | There are **three** direct call-sites: `AttachmentForwarder` (`:237`, `:309`), `PdfReaderTool` (`:53,69,85`), `ReadAttachmentTool` (`:82,83,84`). `LlmTask:122,133` and `AgentOrchestrator:150,157-159,1609` only *inject and hand it through* to `ReadAttachmentTool` — no extraction call. Five files reference it, three consume it. |
| "size-cap mirroring `eddi.attachments.max-forward-bytes`" | ✅ property exists | Declared **only inline** at `AttachmentForwarder.java:92` and `RestAttachmentUpload.java:66`, both `defaultValue = "10485760"`. It is **not** in `application.properties` — do not grep there and conclude it is missing. |
| "`MimeValidator` already accepts `.docx`/`.xlsx`/`.pptx` uploads that `AttachmentForwarder` then rejects as 'unsupported type'" | ✅ correct | `MimeValidator.MIME_ZIP_SUBTYPES` (`:170-176`) whitelists the three OOXML types against the `PK\x03\x04` detection; `AttachmentForwarder.java:248` `return note(errors, name, mime, att.getSizeBytes(), "unsupported type");` |

Additional facts the doc does not state, and which the implementer needs:

- `MimeValidator` is a **final utility class with a private constructor** — `detectMime`, `normalize`, `isCompatible` are all `static`. Do not `@Inject` it.
- `MimeValidator.detectMime` **cannot distinguish docx from xlsx from pptx from a plain zip** — all return `"application/zip"`. Dispatch must use the **declared** MIME; detection is only for the compatibility check.
- `MimeValidator.detectMime` returns `"application/octet-stream"` for plain text/JSON/CSV, and `isCompatible` (`:135-137`) returns `true` whenever detection is `application/octet-stream`. Text-like declared types therefore pass validation trivially — correct, but do not write a test asserting detection identifies `text/plain`.
- `quarkus.http.limits.max-body-size=25M` is already set (`application.properties:161`), so a 10 MB octet-stream POST reaches the resource.
- `RagConfiguration` is a **mutable POJO with getters/setters** (not a record), and there is **no `ExtensionDescriptor`** for RAG — it is a config store, not an `ILifecycleTask`. The Manager UI renders it from `IRestRagStore` `@Path("/jsonSchema")`. No descriptor work is required for increment 1 or 2.

---

### 1. Increment 1 — unblock transport (binary sibling endpoint)

#### Files

| Action | Path | Change |
|---|---|---|
| MODIFY | `src/main/java/ai/labs/eddi/configs/rag/IRestRagIngestion.java` | Add a second `@POST` method. Leave `ingestDocument` byte-identical. |
| MODIFY | `src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java` | Implement it; add constructor params. |
| MODIFY | `src/main/resources/application.properties` | Add two documented properties. |
| MODIFY | `docs/rag.md` (table at `:137-138`, examples at `:143-165`) | Document the new endpoint + status codes. |
| MODIFY | `docs/changelog.md` | Entry, same commit (AGENTS.md §2 rule 8). |

#### `IRestRagIngestion` — new method (place directly **after** `ingestDocument`, before `getIngestionStatus`)

```java
@POST
@Path("/{id}/ingest/binary")
@Consumes(MediaType.APPLICATION_OCTET_STREAM)
@Produces(MediaType.APPLICATION_JSON)
@APIResponse(responseCode = "202", description = "Ingestion started — returns ingestion ID for status polling.")
@APIResponse(responseCode = "413", description = "Document exceeds the configured binary ingestion size limit.")
@APIResponse(responseCode = "415", description = "MIME type is unsupported, or does not match the file content.")
@APIResponse(responseCode = "422", description = "No extractable text in the document.")
@Operation(summary = "Ingest binary document",
           description = "Ingest a binary document (PDF or text-like) into a knowledge base. "
                   + "Text extraction runs synchronously so failures are reported as HTTP status; "
                   + "embedding and storage run async on a virtual thread.")
Response ingestBinaryDocument(@PathParam("id") String ragConfigId,
                              @Parameter(name = "version", required = true, example = "1")
                              @QueryParam("version") Integer version,
                              @Parameter(name = "kbId", description = "Knowledge base ID (defaults to RAG config name)")
                              @QueryParam("kbId") String kbId,
                              @Parameter(name = "documentName", description = "Display name for the document")
                              @QueryParam("documentName") @DefaultValue("unnamed") String documentName,
                              @Parameter(name = "mimeType", required = true,
                                         description = "Declared MIME type, e.g. application/pdf",
                                         example = "application/pdf")
                              @QueryParam("mimeType") String mimeType,
                              byte[] documentBytes);
```

`@RolesAllowed({"eddi-admin","eddi-editor"})` is on the interface type — inherited, do not repeat.

#### `RestRagIngestion` — constructor and method

Constructor becomes:

```java
@Inject
public RestRagIngestion(IRestRagStore restRagStore,
        RagIngestionService ragIngestionService,
        AttachmentTextExtractor textExtractor,
        @ConfigProperty(name = "eddi.rag.ingestion.max-bytes",
                        defaultValue = "10485760") long maxIngestBytes,
        @ConfigProperty(name = "eddi.rag.ingestion.max-chars",
                        defaultValue = "5000000") int maxIngestChars) {
```

Imports to add (simple names only, per AGENTS.md §4.7 Imports): `ai.labs.eddi.engine.attachments.MimeValidator`, `ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor`, `ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.AttachmentExtractionException`, `org.eclipse.microprofile.config.inject.ConfigProperty`.

Method body — **order is load-bearing**, cheapest/most-actionable rejections first:

```java
@Override
public Response ingestBinaryDocument(String ragConfigId, Integer version, String kbId,
                                     String documentName, String mimeType, byte[] documentBytes) {
    if (documentBytes == null || documentBytes.length == 0) {
        return error(Response.Status.BAD_REQUEST, "Document content is required");
    }
    if (mimeType == null || mimeType.isBlank()) {
        return error(Response.Status.BAD_REQUEST, "Query parameter 'mimeType' is required");
    }
    if (documentBytes.length > maxIngestBytes) {
        return error(Response.Status.REQUEST_ENTITY_TOO_LARGE,
                "Document is %d bytes, exceeding the ingestion limit of %d bytes"
                        .formatted(documentBytes.length, maxIngestBytes));
    }

    String declared = MimeValidator.normalize(mimeType);
    String detected = MimeValidator.detectMime(documentBytes);
    if (!MimeValidator.isCompatible(declared, detected)) {
        return error(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                "Declared MIME type '%s' does not match detected content type '%s'"
                        .formatted(declared, detected));
    }
    if (!textExtractor.canExtractText(declared)) {
        return error(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                "MIME type '%s' is not supported for RAG ingestion. Supported: application/pdf, text/*, "
                        + "application/json, application/xml, application/csv, application/yaml".formatted(declared));
    }

    RagConfiguration ragConfig;
    try {
        ragConfig = restRagStore.readRag(ragConfigId, version);
    } catch (Exception e) {
        LOGGER.warnf("Failed to load RAG config %s v%d: %s", sanitize(ragConfigId), version, e.getMessage());
        return error(Response.Status.NOT_FOUND,
                "RAG configuration not found: " + ragConfigId + " v" + version);
    }

    String text;
    try {
        text = textExtractor.extractText(documentBytes, declared, maxIngestChars);
    } catch (AttachmentExtractionException e) {
        return error(Response.Status.UNSUPPORTED_MEDIA_TYPE,
                "Text extraction failed for '" + sanitize(documentName) + "': " + e.getMessage());
    }
    if (text == null || text.isBlank()) {
        return error(422, "No extractable text found in '" + sanitize(documentName)
                + "' (" + declared + "). Nothing was ingested.");
    }

    boolean truncated = text.endsWith(AttachmentTextExtractor.truncationSuffix(maxIngestChars));

    String effectiveKbId = kbId != null && !kbId.isBlank()
            ? kbId : ragConfig.getName() != null ? ragConfig.getName() : ragConfigId;

    String ingestionId = ragIngestionService.ingest(effectiveKbId, text, documentName, ragConfig);

    LOGGER.infof("Binary ingestion started: id=%s, kb=%s, doc=%s, mime=%s, bytes=%d, chars=%d, truncated=%b",
            ingestionId, sanitize(effectiveKbId), sanitize(documentName), declared,
            documentBytes.length, text.length(), truncated);

    return Response.accepted(Map.of("ingestionId", ingestionId, "kbId", effectiveKbId,
            "status", "pending", "mimeType", declared, "extractedChars", text.length(),
            "truncated", truncated)).build();
}
```

Add two private helpers at the bottom of the class (below `getIngestionStatus`):

```java
private static Response error(Response.Status status, String message) {
    return Response.status(status).entity(Map.of("error", message)).build();
}

private static Response error(int status, String message) {
    return Response.status(status).entity(Map.of("error", message)).build();
}
```

> **Why extraction is synchronous here and not inside `RagIngestionService`:** the doc requires "an actionable status on unsupported types rather than embedding an empty document". The existing async path can only report failure through `getStatus()` as a `"failed: …"` string. Extraction is CPU-bound and bounded by the 10 MB cap; embedding (the network-bound part) stays async.

#### `AttachmentTextExtractor` — one additive method (increment 1 dependency)

MODIFY `src/main/java/ai/labs/eddi/modules/llm/tools/impl/AttachmentTextExtractor.java`. Place immediately **after** `getDefaultMaxChars()`:

```java
/**
 * The exact suffix {@link #extractText} appends when a result is capped at
 * {@code maxChars}. Callers that need to know whether truncation occurred can
 * compare against this rather than re-deriving the format string.
 */
public static String truncationSuffix(int maxChars) {
    return String.format(TRUNCATION_SUFFIX_FMT, maxChars).trim();
}
```

No behavior change; `cap()` already produces exactly this (`:182` — `(substring + suffix).trim()`, and the format string has no trailing whitespace, so `endsWith` on the trimmed suffix is exact).

#### Config surface (increment 1)

Append to `src/main/resources/application.properties`, adjacent to the attachment block (after line 171):

```properties
# RAG binary ingestion — POST /ragstore/rags/{id}/ingest/binary
# Max size of a single binary document accepted for ingestion, in bytes (default 10 MB).
# Mirrors eddi.attachments.max-forward-bytes; must stay <= quarkus.http.limits.max-body-size.
eddi.rag.ingestion.max-bytes=10485760
# Max characters of extracted text per ingested document. Deliberately far larger than
# eddi.attachments.extraction.max-chars (10000), which is sized for inline LLM context —
# a 10k cap would silently discard almost every real document at ingestion time.
eddi.rag.ingestion.max-chars=5000000
```

Both are **deployment-level** (`application.properties`), not `RagConfiguration` JSON. No stored-config field, no `jsonSchema` change, no Manager UI work in increment 1.

#### Stored-config back-compat (increments 1 + 2)

| Concern | Outcome |
|---|---|
| Existing `RagConfiguration` documents in MongoDB | **Untouched.** No field added, removed or renamed in increments 1–2. |
| Existing ZIP imports containing `*.rag.json` | Unaffected. |
| Existing clients POSTing `text/plain` to `/{id}/ingest` | **Byte-identical behavior.** `ingestDocument` and `RagIngestionService.ingest(String, String, String, RagConfiguration)` keep their exact signatures and bodies. |
| A live agent the moment this deploys | **No semantic change.** The only new behavior is a new URL path. Nothing existing routes differently. |
| Increment 3 (`parserType`) | New optional field, default `"auto"`; absent in stored configs → Jackson leaves the initializer value. `FAIL_ON_UNKNOWN_PROPERTIES=false` is not even needed here since nothing is removed. |

---

### 2. Increment 2 — RAG-scoped cap, wired through the service

Increment 1 already injects `AttachmentTextExtractor` into the **REST layer** with `maxIngestChars`. Increment 2 is the decision doc's "inject into `RagIngestionService`" — implemented as a **binary-aware service overload** so that non-REST callers (future NATS ingestion, bulk re-ingestion, MCP tooling) get the same capped path rather than each re-implementing it.

#### Files

| Action | Path | Change |
|---|---|---|
| MODIFY | `src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java` | New constructor params; new `ingestBinary` overload; `truncated` metadata on the `Document`. |
| MODIFY | `src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java` | Delegate to `ingestBinary`; drop the extractor/char-cap injection added in increment 1 from the REST class (validation stays, extraction moves). |
| MODIFY | `src/test/java/ai/labs/eddi/modules/rag/RagIngestionServiceTest.java` | **Constructor call breaks** — see §4. |

#### `RagIngestionService` changes

Constructor (replaces the one at `:46-50`):

```java
@Inject
public RagIngestionService(EmbeddingModelFactory embeddingModelFactory,
        EmbeddingStoreFactory embeddingStoreFactory,
        AttachmentTextExtractor textExtractor,
        @ConfigProperty(name = "eddi.rag.ingestion.max-chars",
                        defaultValue = "5000000") int maxIngestChars) {
```

New public method, placed directly **after** `ingest(...)` (`:65-72`) and **before** `processIngestion`:

```java
/**
 * Ingest a binary document. Text extraction runs synchronously on the calling
 * thread so unsupported types and empty extractions surface to the caller;
 * embedding and storage then run on a virtual thread exactly as
 * {@link #ingest} does.
 *
 * @throws AttachmentExtractionException
 *             if the MIME type is unsupported or extraction fails
 * @throws EmptyExtractionException
 *             if the document yields no text — never embed an empty document
 */
public String ingestBinary(String kbId, byte[] documentBytes, String mimeType,
        String documentName, RagConfiguration ragConfig)
        throws AttachmentExtractionException, EmptyExtractionException {

    String text = textExtractor.extractText(documentBytes, mimeType, maxIngestChars);
    if (text == null || text.isBlank()) {
        throw new EmptyExtractionException(
                "No extractable text in '" + documentName + "' (" + mimeType + ")");
    }
    boolean truncated = text.endsWith(AttachmentTextExtractor.truncationSuffix(maxIngestChars));
    if (truncated) {
        LOGGER.warnf("Document '%s' truncated at %d chars during ingestion into KB '%s'",
                sanitize(documentName), maxIngestChars, sanitize(kbId));
    }
    return ingest(kbId, text, documentName, ragConfig, mimeType, truncated);
}
```

Add a private 6-arg overload of `ingest` and thread the two extra values into `processIngestion`, so the metadata at `:80` becomes:

```java
Metadata metadata = Metadata.from("source", documentName).put("kbId", kbId);
if (mimeType != null) {
    metadata.put("mimeType", mimeType);
}
if (truncated) {
    metadata.put("truncated", "true");
}
Document document = Document.from(documentContent, metadata);
```

The existing 4-arg `ingest` delegates with `mimeType = "text/plain"`, `truncated = false` — **no metadata key appears that did not appear before** for the text path except `mimeType`, which is additive and does not affect retrieval (`RagContextProvider` reads `source`/`kbId` only — [UNVERIFIED — implementer must confirm `RagContextProvider.formatRagContext` reads no other metadata keys before adding `mimeType` to the text path; if in doubt, gate `mimeType` on the binary path only]).

New nested exception at the bottom of `RagIngestionService`, alongside nothing (the class has no nested types today):

```java
/** Thrown when a document parses successfully but yields no text to embed. */
public static class EmptyExtractionException extends Exception {
    public EmptyExtractionException(String message) {
        super(message);
    }
}
```

#### `RestRagIngestion` after increment 2

Constructor drops `maxIngestChars` (moves to the service) but **keeps** `AttachmentTextExtractor` — it still needs `canExtractText` for the pre-flight 415 — and keeps `maxIngestBytes`. The extraction block becomes:

```java
String ingestionId;
try {
    ingestionId = ragIngestionService.ingestBinary(effectiveKbId, documentBytes, declared, documentName, ragConfig);
} catch (AttachmentExtractionException e) {
    return error(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Text extraction failed: " + e.getMessage());
} catch (EmptyExtractionException e) {
    return error(422, e.getMessage() + " Nothing was ingested.");
}
```

`extractedChars` / `truncated` leave the 202 body (the REST layer no longer holds the text); keep `mimeType`. Update the increment-1 tests accordingly — do this in the **same commit** as increment 2 so no commit ships a red build.

---

### 3. Increment 3 — Office via apache-poi (OUTLINE ONLY)

Do not implement without a separate spec pass.

1. `pom.xml`: add `dev.langchain4j:langchain4j-document-parser-apache-poi` (pinned; check its transitive `poi-ooxml` for open CVEs before merge). **Never** `-apache-tika`.
2. `AttachmentTextExtractor`: add `isOffice(String mime)` over the three `MIME_ZIP_SUBTYPES` OOXML values, an `extractOfficeText(byte[], int)` branch in `extractText`, and extend `canExtractText`. This fixes the attachment path (`AttachmentForwarder.java:248` "unsupported type") and the RAG path in one change — the doc's stated reason for doing it here rather than in RAG code.
3. `RagConfiguration`: new field `private String parserType = "auto";` (`"auto"|"text"|"pdf"|"office"`). Getter/setter. Default `"auto"` = current dispatch-on-MIME behavior, so stored configs are unchanged. Do **not** throw from validation on unknown values (§7 D8 trap) — log a WARN and fall back to `"auto"`.
4. `MimeValidator` needs **no** change: the OOXML types are already whitelisted against the ZIP signature.
5. Trap carried forward: `detectMime` returns `application/zip` for all OOXML. Dispatch on **declared** MIME; a malicious caller declaring `…wordprocessingml.document` over an arbitrary zip is handled by POI failing to parse → `AttachmentExtractionException` → 415. Confirm POI does not follow external entities (XXE) on the OOXML path before merge — this is the exact risk class Tika was rejected for.

---

### 4. Tests

#### Existing coverage (verified paths)

| Class | Covers | Impact |
|---|---|---|
| `src/test/java/ai/labs/eddi/configs/rag/rest/RestRagIngestionTest.java` | 6 tests over `ingestDocument` + `getIngestionStatus`. Plain Mockito, no `@QuarkusTest`. | **BREAKS** — constructor gains params. Update `setUp()`; all 6 existing tests keep their assertions unchanged. |
| `src/test/java/ai/labs/eddi/modules/rag/RagIngestionServiceTest.java` | 3 tests. | **BREAKS at increment 2** — `new RagIngestionService(embeddingModelFactory, embeddingStoreFactory)` (`:42`). |
| `src/test/java/ai/labs/eddi/modules/llm/tools/impl/AttachmentTextExtractorTest.java` | ~20 tests incl. real PDFBox-generated PDFs. `new AttachmentTextExtractor(10_000)`. | Unaffected; extend. |
| `src/test/java/ai/labs/eddi/engine/attachments/MimeValidatorTest.java` | detection + compatibility | Unaffected. |
| `src/test/java/ai/labs/eddi/modules/llm/impl/AttachmentForwarderTest.java` | forwarding | Unaffected in increments 1–2. |

#### VACUOUS tests that must be fixed as part of this work

Both in `RagIngestionServiceTest`:

1. **`ingest_shouldReturnIngestionId` (`:46`)** — stubs `embeddingModelFactory`, `embeddingStoreFactory` and both `embed` overloads, then asserts only `assertNotNull(ingestionId)` / `assertFalse(isBlank())`. The virtual thread is never joined, so **no stub is ever verified** and the test passes even if `processIngestion` throws on line 1. Fix: poll `getStatus(id)` with a bounded await (e.g. up to 5 s, 25 ms interval) until it is terminal, assert `"completed"`, and `verify(embeddingStore, atLeastOnce()).addAll(any(), any())` (or whatever `EmbeddingStoreIngestor` actually calls — confirm at implementation time).
2. **`getStatus_shouldReturnPendingInitially` (`:62`)** — asserts the status is any of `pending|processing|completed|failed*`, i.e. every value the method can return. Asserts nothing. Fix: assert the status is `"pending"` or `"processing"` **immediately** after `ingest` returns (deterministic: `ingest` does `put("pending")` before `startVirtualThread`), and separately assert the terminal state after awaiting.

Leaving these green as-is is not acceptable; the whole point of increment 2 is that the ingestion path fails loudly.

#### New tests

**`RestRagIngestionTest`** (add; all plain Mockito, no socket, runs locally):

| Method | Asserts |
|---|---|
| `ingestBinary_nullBytes_shouldReturn400` | status 400, `ragIngestionService` never called |
| `ingestBinary_emptyBytes_shouldReturn400` | status 400 |
| `ingestBinary_missingMimeType_shouldReturn400` | status 400 for `null` and for `"  "` |
| `ingestBinary_oversizedDocument_shouldReturn413` | construct with `maxIngestBytes=100`, post 200 bytes → 413, error message names both numbers, service never called |
| `ingestBinary_mimeMismatch_shouldReturn415` | declared `application/pdf`, body `PNG` magic bytes → 415, service never called |
| `ingestBinary_unsupportedType_shouldReturn415` | declared `image/png` with PNG magic bytes (compatible but unextractable) → 415, message lists supported types |
| `ingestBinary_configNotFound_shouldReturn404` | `readRag` throws → 404, **and** no extraction/ingest occurred |
| `ingestBinary_emptyExtraction_shouldReturn422` | service throws `EmptyExtractionException` → 422 |
| `ingestBinary_extractionFailure_shouldReturn415` | service throws `AttachmentExtractionException` → 415 |
| `ingestBinary_validPdf_shouldReturn202` | real PDFBox-built PDF bytes (copy the builder helper from `AttachmentTextExtractorTest`), stub `ingestBinary` → 202, body has `ingestionId`, `kbId`, `status=pending`, `mimeType=application/pdf` |
| `ingestBinary_explicitKbId_shouldOverrideConfigName` | `verify(service).ingestBinary(eq("custom-kb"), …)` |
| `ingestBinary_textPlainBody_shouldReturn202` | declared `text/plain`, UTF-8 bytes → 202 (guards the `octet-stream` detection leniency path) |
| `ingestDocument_textPath_isUnchanged` | re-assert the 202 body of the existing text endpoint after the constructor change — the back-compat canary |

**`RagIngestionServiceTest`** (add):

| Method | Asserts |
|---|---|
| `ingestBinary_unsupportedMime_shouldThrowAttachmentExtractionException` | `assertThrows`, and `verifyNoInteractions(embeddingModelFactory, embeddingStoreFactory)` — **nothing is embedded** |
| `ingestBinary_blankExtraction_shouldThrowEmptyExtractionException` | whitespace-only `text/plain` bytes → `EmptyExtractionException`, no embedding factory interaction |
| `ingestBinary_pdf_shouldReachTerminalCompleted` | real PDF bytes → await terminal, assert `"completed"` |
| `ingestBinary_shouldUseRagCapNotAttachmentCap` | build the service with a real `new AttachmentTextExtractor(10_000)` and `maxIngestChars = 50_000`; feed 20 000 chars of `text/plain`; capture the `Document` reaching the ingestor (or assert via a splitter-count proxy) and assert the text is **not** truncated at 10 000. **This is the regression test for the doc's "catastrophic" warning — it must fail if someone wires the attachment cap through.** |
| `ingest_textPath_stillWorks` | existing 4-arg `ingest` unchanged, terminal `"completed"` |

**`AttachmentTextExtractorTest`** (add):

| Method | Asserts |
|---|---|
| `truncationSuffixMatchesEmittedSuffix` | for a 50-char cap, `extractText(longText, "text/plain", 50).endsWith(truncationSuffix(50))` is `true` |
| `untruncatedTextDoesNotEndWithSuffix` | short text → `false` |

**CI-only:** none of the above binds a socket or needs Docker; all run under `./mvnw test`. If an `*IT.java` end-to-end POST of a PDF is added later, it is CI-only per AGENTS.md.

---

### 5. Acceptance criteria

1. `./mvnw clean compile` exits 0. (`clean` is mandatory — the constructor-signature change is exactly the case where an incremental build hides a broken caller.)
2. `./mvnw validate` (Checkstyle) exits 0 and `./mvnw formatter:format` produces no diff on the files touched.
3. `./mvnw test -Dtest=RestRagIngestionTest` — all tests pass, and the class contains **≥12** `@Test` methods against `ingestBinaryDocument`.
4. `./mvnw test -Dtest=RagIngestionServiceTest` passes, and `ingest_shouldReturnIngestionId` now awaits a terminal status and verifies at least one embedding-store interaction (grep the file: no test asserts only `assertNotNull(ingestionId)`).
5. `./mvnw test -Dtest=AttachmentTextExtractorTest` passes with the two new truncation tests.
6. `grep -rn "tika" pom.xml` returns **nothing**.
7. `git diff` on `IRestRagIngestion.java` shows **zero** changed lines within `ingestDocument` (lines 25–40 of the original), and `git diff` on `RagIngestionService.java` shows the 4-arg `public String ingest(String, String, String, RagConfiguration)` signature unchanged.
8. `grep -n "eddi.rag.ingestion" src/main/resources/application.properties` returns both `max-bytes=10485760` and `max-chars=5000000`.
9. `grep -n "getDefaultMaxChars\|DEFAULT_MAX_CHARS" src/main/java/ai/labs/eddi/modules/rag/ src/main/java/ai/labs/eddi/configs/rag/` returns **nothing** — the 10 000 attachment cap must not reach the ingestion path.
10. Manual (dev mode, `./mvnw compile quarkus:dev`): `curl -X POST 'http://localhost:7070/ragstore/rags/{id}/ingest/binary?version=1&mimeType=application/pdf&documentName=x.pdf' -H 'Content-Type: application/octet-stream' --data-binary @sample.pdf` returns **202** with a non-empty `ingestionId`; polling `/ingestion/{id}/status` reaches `"completed"`.
11. Same call with `mimeType=image/png` and PNG bytes returns **415** with an error message naming the supported types; the vector store receives **no** document (status endpoint returns `"unknown"` for any id — no ingestion was created).
12. Same call with a 0-byte PDF stub whose text extraction is empty returns **422**, not 202.
13. `docs/rag.md` table (`:137-138`) lists the binary endpoint with its 202/413/415/422 statuses, and `docs/changelog.md` has a top entry on the same branch as the code.

---

### 6. Traps

1. **The 10 000-char cap is the headline hazard.** `AttachmentTextExtractor.extractText(byte[], String)` — the 2-arg form — silently applies `defaultMaxChars`. **Never call the 2-arg form from any ingestion code path.** Always the 3-arg form with the RAG cap. Acceptance criterion 9 exists solely to enforce this.
2. **Never embed an empty document.** `extractText` returns `""` (not an exception) for `bytes == null || length == 0` (`:93-95`), and a scanned image-only PDF extracts to whitespace via PDFBox with no error. Both must become an actionable HTTP status, not a silent zero-chunk ingestion.
3. **`MimeValidator.isCompatible` is lenient by design** — `true` on any null argument (`:130-134`) and `true` whenever detection is `application/octet-stream` (`:135-137`). It is a *mismatch* detector, not an allowlist. The allowlist is `textExtractor.canExtractText(declared)`, and it must be checked **in addition**, never instead.
4. **Dispatch on the declared MIME, validate with the detected one.** `detectMime` collapses docx/xlsx/pptx/jar/epub to `application/zip`. Reversing this makes increment 3 impossible.
5. **`MimeValidator` is a static utility with a private constructor.** `@Inject`ing it fails CDI at build time.
6. **Thread safety.** `RagIngestionService` is `@ApplicationScoped` — the new `textExtractor` and `maxIngestChars` must be `private final`, set in the constructor. `AttachmentTextExtractor` is already stateless-with-final-int, so it is safe to share. **Do not** add per-ingestion mutable fields; the existing Caffeine `ingestionStatus` cache is the only mutable state and it is thread-safe. Conversation memory is not involved — this is a REST/service path, not an `ILifecycleTask`.
7. **Extraction on the caller thread is deliberate.** It is bounded by the 10 MB byte cap. Do not "optimize" it into the virtual thread — that reinstates the swallowed-failure mode the whole increment exists to remove.
8. **Truncation marker leaks into the vector store.** `cap()` appends `"[Content truncated - showing first N characters]"` to the returned text, which then becomes a chunk. Detect it via `truncationSuffix(maxChars)`, log a WARN and stamp `truncated=true` metadata. Do not silently drop the suffix by substring surgery — the marker is genuinely useful provenance and stripping it by index is fragile.
9. **`quarkus.http.limits.max-body-size=25M` must stay ≥ `eddi.rag.ingestion.max-bytes`**, or oversized posts die as a bare HTTP 413 from Vert.x before the resource sees them and the actionable error message is never produced. It is currently 25M vs a 10 MB cap — fine, but note it in the property comment.
10. **Do not change `RagIngestionService.ingest(String, String, String, RagConfiguration)`'s signature.** `RestRagIngestion.ingestDocument` and `RestRagIngestionTest` both bind to it, and the doc's back-compat requirement is that the TEXT_PLAIN endpoint is preserved verbatim.
11. **Increments 1 and 2 must land as one build-green sequence.** Increment 1 puts extraction in the REST class; increment 2 moves it to the service and changes the REST tests. If you commit them separately, commit 1 must be fully green on its own (AGENTS.md §2 rule 6) — the sub-step ordering in §7 assumes this.

---

### 7. Out of scope

- **Apache Tika** — rejected on CVE-2025-66516 (CVSS 10.0, XXE via XFA-in-PDF) and on ServiceLoader/native-image grounds. Do not add it, do not add anything that pulls it transitively.
- **Office/OOXML support** — increment 3, outline only, separate spec.
- `AttachmentForwarder.java:248` `"unsupported type"` for docx — fixed *by* increment 3, not before it.
- `RagContextProvider` / retrieval, RRF fusion (**R3a**), serial fan-out (**D14**) — different items.
- `EmbeddingInputType` QUERY/DOCUMENT (**I5**) — different item, even though it touches `RagIngestionService.java:86`.
- Embedding cost metering (**D13**), `EmbeddingModelFactory`, `EmbeddingStoreFactory` — untouched.
- Manager UI: no `ExtensionDescriptor`, no `jsonSchema` change in increments 1–2 (no `RagConfiguration` field is added). A Manager upload widget for the binary endpoint is a separate Manager-repo task.
- NATS JetStream ingestion (the `RagIngestionService` javadoc's "planned") — still planned.
- Chunking strategy: `ragConfig.getChunkStrategy()` is read nowhere in `processIngestion` (only `chunkSize`/`chunkOverlap` at `:83`). That is a real dead-config smell but it is **not** this item — file it separately, do not fix it here.

---

### 8. Effort and sequencing

| # | Step | Files | Independently committable | Effort |
|---|---|---|---|---|
| 1 | `truncationSuffix(int)` on `AttachmentTextExtractor` + its 2 tests | 2 | ✅ yes | XS |
| 2 | Binary endpoint: interface method, REST impl with validation ladder + synchronous extraction, 2 config properties | 3 | ✅ yes (tests in step 3) | S |
| 3 | `RestRagIngestionTest` — constructor fix + 13 new tests | 1 | ✅ (commit with step 2 if you prefer one green unit) | S |
| 4 | Fix the two vacuous `RagIngestionServiceTest` tests (bounded await + real verification) — **do this before step 5** so the move is guarded by a test that can actually fail | 1 | ✅ yes | S |
| 5 | `RagIngestionService.ingestBinary` + `EmptyExtractionException` + metadata; REST delegates; update both test classes | 4 | ✅ yes | S–M |
| 6 | `docs/rag.md` + `docs/changelog.md` | 2 | fold into step 5's commit per AGENTS.md §2 rule 8 | XS |
| — | Increment 3 (apache-poi + `parserType`) | — | separate spec | M |

Total for increments 1+2: **M**, matching the decision doc. Step 4 before step 5 is not optional — moving extraction into a service whose only test cannot fail is how the 10 000-char cap gets silently reintroduced.
