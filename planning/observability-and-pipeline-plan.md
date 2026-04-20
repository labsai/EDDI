# Observability & Pipeline Architecture Plan

> **Context:** These are larger architectural improvements deferred from the v6.0.2 security sprint. They span the pipeline engine, coordinator, and observability infrastructure.

## Prerequisite Reading

1. [`docs/architecture.md`](../architecture.md) — Pipeline lifecycle, task model
2. [`docs/changelog.md`](../changelog.md) — Recent changes for context
3. [`AGENTS.md`](../../AGENTS.md) — §4.2 "Core Architecture" for the lifecycle pipeline model

---

## 1. OpenTelemetry Tracing 🟡 MEDIUM

**Why:** EDDI has Micrometer metrics but no distributed tracing. For production debugging, each conversation turn should produce a trace showing: request → parse → behavior rules → actions → task execution → response.

**What to do:**

### 1a. Add OpenTelemetry dependency

```xml
<!-- pom.xml -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-opentelemetry</artifactId>
</dependency>
```

### 1b. Instrument the LifecycleManager

The main execution path is in [`LifecycleManager.executeLifecycle()`](../../src/main/java/ai/labs/eddi/engine/lifecycle/LifecycleManager.java). Add span-per-task:

```java
Span span = tracer.spanBuilder("eddi.task." + task.getId())
    .setAttribute("conversationId", conversationId)
    .setAttribute("agentId", agentId)
    .startSpan();
try (Scope scope = span.makeCurrent()) {
    task.execute(memory, component);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR);
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

### 1c. Instrument SafeHttpClient

Add outgoing HTTP spans so you can see tool HTTP timing in the trace:

```java
Span httpSpan = tracer.spanBuilder("eddi.http.outbound")
    .setAttribute("http.url", request.uri().toString())
    .setAttribute("http.method", request.method())
    .startSpan();
```

### 1d. Configuration

```properties
# application.properties
quarkus.otel.exporter.otlp.endpoint=http://localhost:4317
quarkus.otel.service.name=eddi
%dev.quarkus.otel.sdk.disabled=true
```

**Key files:**
- `src/main/java/ai/labs/eddi/engine/lifecycle/LifecycleManager.java`
- `src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java`
- `src/main/resources/application.properties`

---

## 2. LlmTask Decomposition 🟡 MEDIUM

**Why:** `LlmTask` is the largest single class in the codebase (~1000 lines). It handles model selection, prompt assembly, tool orchestration, streaming, and response parsing. It should be split for maintainability.

**Current structure:**
```
LlmTask.execute()
  └─→ Configure model (ChatModelRegistry)
  └─→ Build tool list (AgentOrchestrator)
  └─→ Assemble prompt (ConversationHistoryBuilder)
  └─→ Call LLM (stream or sync)
  └─→ Parse response
  └─→ Store in memory
```

**Proposed decomposition:**

| New class | Responsibility | Extracted from |
|-----------|---------------|----------------|
| `PromptAssembler` | System prompt template resolution, conversation history windowing | `LlmTask.buildMessages()` |
| `ToolOrchestrator` | Build tool list, handle tool calls, tool execution lifecycle | `AgentOrchestrator` (already partly extracted) |
| `ModelSelector` | Model cascading, provider routing, confidence thresholds | `LlmTask.selectModel()` |
| `ResponseParser` | Parse LLM response, extract structured data, store in memory | `LlmTask.processResponse()` |

**Important:** This is a refactor only — no behavior changes. Each step:
1. Extract method group into new class
2. Pass `IConversationMemory` as parameter (NOT as instance state)
3. Inject into `LlmTask` via CDI
4. Verify all existing tests pass

**Key files:**
- `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java`
- `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java`
- `src/test/java/ai/labs/eddi/modules/llm/impl/LlmTaskTest.java`

---

## 3. ConversationCoordinator Hardening 🟢 LOW

**Why:** `InMemoryConversationCoordinator` uses unbounded `ConcurrentHashMap<String, BlockingQueue>` keyed by conversationId. Active conversations are cleaned up, but abandoned conversations (client disconnects mid-turn) may leak entries.

**What to do:**

1. Add a `maxAge` eviction policy — evict queues not touched in >10 minutes
2. Add a max-size limit — reject new conversations when >N active (configurable)
3. Add Micrometer gauges: `eddi.coordinator.active_conversations`, `eddi.coordinator.queue_depth`

**Key files:**
- `src/main/java/ai/labs/eddi/engine/runtime/internal/InMemoryConversationCoordinator.java`
- `src/main/java/ai/labs/eddi/engine/runtime/internal/NatsConversationCoordinator.java`

---

## 4. Metrics Dashboard 🟢 LOW

**Why:** EDDI has 20+ Micrometer metrics but no dashboard to visualize them.

**What to do:**

1. Create a Grafana dashboard JSON in `docs/monitoring/eddi-grafana-dashboard.json`
2. Document Prometheus scrape config for `/q/metrics`
3. Add alerting rules for: `eddi.tool.budget.exceeded`, `eddi.tool.ratelimit.denied`, `eddi.coordinator.queue_depth > threshold`

This is documentation/config work, not code changes.

---

## Verification

- [ ] `./mvnw test` — 4,100+ tests pass
- [ ] OpenTelemetry traces visible in Jaeger/Zipkin after item 1
- [ ] LlmTask unit tests pass unchanged after item 2
- [ ] Load test coordinator with 1000 concurrent conversations after item 3
