## 🐛 fix(llm): malformed model JSON no longer fails the turn; fenced/prefixed JSON is recovered (2026-10-06)

**Repo:** EDDI (`feat/llm-output-parsing-never-throws`)

### What changed and why

First step of the LLM turn-resilience plan ([`planning/llm-turn-resilience-plan.md`](../../planning/llm-turn-resilience-plan.md), items R3 and R14). A production 5.x deployment with `convertToObject: "true"` showed turns failing or rendering empty bubbles whenever the model wrapped its JSON in a markdown fence, added a sentence around it, or ran out of tokens mid-object.

**Bug fix (behaviour change):** under `convertToObject`, output that is not valid JSON **no longer fails the turn**. The raw string is stored under `responseObjectName` with the existing WARN, exactly as plain-text replies always were. Before this, a reply that merely started with `{` and was malformed or truncated threw out of `LlmTask` on both the live and the HITL-resume path.

- New `ModelOutputParser` (`modules/llm/impl`) used by both paths in `LlmTask`: trim, strip one surrounding fence (`` ``` `` / `` ```json ``, tag case-insensitive), then extract the outermost balanced `{...}` / `[...]` (string- and escape-aware). No other repair. A fenced or prose-wrapped reply that used to be stored as a string now becomes an object.
- Result is `VALID` (flag `repaired`), `INVALID(reason)` or `EMPTY`. Reasons are EDDI-generated constants (`not JSON`, `truncated JSON`, `unbalanced braces`, `invalid JSON syntax`) and never contain model output, so a later corrective re-ask (R16) can quote them safely.
- Outcome recorded on the step under `llm:output:outcome:<taskId>` (`valid|repaired|invalid|empty`; not a public snapshot key because of its `llm:` prefix) and counted in `eddi.llm.output{outcome}`.

**R14 (test scope only):** `FaultInjectingChatModel` (`src/test/.../modules/llm/testing`), a scripted langchain4j `ChatModel` for later resilience work: per call it returns text, an empty reply, a finish reason (`LENGTH`, `CONTENT_FILTER`), throws a simulated HTTP status (mapped through langchain4j's own `ExceptionMapper`, so `RetryConfiguration.isRetryableError` classifies it like a real provider error), throws any exception, or delays. It is not registered as a model type and nothing in `src/main` knows about it.

### Design decisions

- One parser class rather than two inline copies, so the live and resume paths cannot drift.
- Successive top-level balanced candidates are tried in order, so `Use {name} here: {"a":1}` still yields the object.
- Not done: the optional WireMock cascade IT from the plan (not trivial); retry/corrective re-ask (R5), schema validation (R4) and the fallback answer (R6/R7) build on this and follow.

### Docs

[`docs/langchain.md`](../langchain.md) Structured Output: parsing steps, "never throws", outcome key and metric.

**Files:** [`ModelOutputParser.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java), [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`FaultInjectingChatModel.java`](../../src/test/java/ai/labs/eddi/modules/llm/testing/FaultInjectingChatModel.java)
