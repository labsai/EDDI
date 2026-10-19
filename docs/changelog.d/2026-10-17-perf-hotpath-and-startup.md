## ⚡ perf(engine): convert only the current step for a turn's response, drop per-turn INFO logging (2026-10-17)

**Repo:** EDDI (`perf/hotpath-and-startup`)

### What changed and why

Part of the throughput work ([`planning/throughput-optimization-plan.md`](../../planning/throughput-optimization-plan.md), WS3b and WS8.5). A JProfiler run at 1,000 concurrent conversations put 9% of running time in Jackson and about 2% in per-turn `Logger.info` calls and `String.format`.

- **Current-step-only conversion (WS3b).** The memory overload of `ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot` converted every step of the conversation and then returned only the last one, which is the default turn response (`returnCurrentStepOnly=true`) and the HITL replies. With `returnCurrentStepOnly` it now builds the snapshot through `convertConversationMemoryForCurrentStep`: same header, outputs, properties and redo cache, and a step list of the same size whose entries are converted only when read. The projection reads the last step and the list's size, so a turn converts one step instead of all of them. Redaction, secret-turn masking, the error field and the undo/redo flags are unchanged: they run in `convertSimpleConversationMemory`, which still does the work. A reader that walks every step still gets correct values, because each step converts on first access; it just gets no saving. Callers in `ConversationService` needed no change.
- **A quadratic loop in `convertConversationMemory`.** `IConversationMemory.getAllSteps()` copies the whole step list on every call, and the conversion called it twice per step. It is now called once. The persistence path uses this conversion too.
- **Per-turn INFO logs are now DEBUG.** Every turn logged four INFO lines, even for an agent with no tools: "Discovering httpcall tools…", "Discovered 0 httpcall tools…" and the two `mcpcalls` equivalents. Lines that fire on every turn when their feature is configured moved to DEBUG as well: built-in tool counts and lazy-tool loading (`AgentOrchestrator`, `ToolLoopRunner`), the contextual memory/recall/attachment tools, RAG and rolling-summary injection (`LlmTask`, `RagContextProvider`), the httpcall tool result line, and the cascade step lines. The string-concatenating ones now use `debugf`, so nothing is built while DEBUG is off.
- **No more `new ObjectMapper()` per discover_tools result (WS8.5).** `ToolLoopRunner.activateDiscoveredTools` built a mapper for every result; it now uses a shared one.
- **Bound Jackson readers and writers.** The Mongo `JacksonCodec` binds an `ObjectReader` for its type and an `ObjectWriter` once, instead of resolving them through the mapper for every document. `JsonSerialization` does the same for its writer and untyped reader.

### Measurements

Conversion, single thread, best of five (memory with ten properties and twelve data per step):

| Steps | Full conversion (persist) before → after | Turn response before → after |
|---|---|---|
| 10 | 8.0 → 6.6 µs | ≥ 8 → 1.0 µs |
| 50 | 57.8 → 30.4 µs | ≥ 58 → 2.6 µs |
| 200 | 376 → 113 µs | ≥ 376 → 2.5 µs |

"Turn response before" is at least the old full conversion, which the projection ran first. End to end (`scripts/loadtest`, `origin/main` against this branch, alternating A/B/A/B on Docker Desktop on a shared machine, so treat the numbers as indicative): steady 50 conversations with a 0 ms mock LLM, 81.9 → 102.9 turns/s on average (the rounds overlap: 88.7 and 75.1 against 120.1 and 85.7); long-conversation (5 × 200 turns), p50 87 → 80 ms at the same throughput, since MongoDB dominates there. INFO lines per run: 16,043 → 43.

### Tests

`ConversationMemoryCurrentStepConversionTest`: 168 golden-equivalence cases compare the new path with the full conversion plus trim across six memories (plain, secret last turn, secret earlier turn, error, redo, single step) × `returnDetailed` × `returnCurrentStepOnly` × seven `returningFields` shapes. One test checks that the path still redacts credentials, drops `audit:*` and masks a secret turn. One guard checks that only the newest step is read. Mutation-checked: reading the steps in the wrong order fails 51 cases, converting eagerly fails the guard, and dropping the redo cache fails 14 cases.

### Not done here

PR #1066 adds a `conversationCost` sum over **all** steps to `convertSimpleConversationMemory`. It stays correct on this path, because the lazy list converts every step it is asked for, but it gives up most of the saving for the default response. Once both land, the cost sum should read `audit:cost` from the live steps (or from a running total) rather than from converted snapshots.

**Files:** [`ConversationMemoryUtilities.java`](../../src/main/java/ai/labs/eddi/engine/memory/ConversationMemoryUtilities.java), [`JacksonCodec.java`](../../src/main/java/ai/labs/eddi/datastore/mongo/codec/JacksonCodec.java), [`JsonSerialization.java`](../../src/main/java/ai/labs/eddi/datastore/serialization/JsonSerialization.java), [`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java), [`HttpCallToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/HttpCallToolsProvider.java), [`McpToolsProvider.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolsProvider.java), `AgentOrchestrator`, `ContextualToolsProvider`, `LlmTask`, `RagContextProvider`, `CascadingModelExecutor`.
