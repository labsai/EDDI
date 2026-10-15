## ✨ feat(observability): W3C trace propagation on outbound calls and execute_tool spans (2026-10-15)

**Repo:** EDDI (`feat/observability-trace-propagation`)

### What changed and why

A trace used to stop at EDDI's edge: it showed that a turn spent four seconds in
the LLM task, but not which MCP server or API made it slow. Two additions close
that gap (pillar 6, observability).

- **`execute_tool {tool}` span per tool call** — opened in
  `ToolExecutionService.executeToolWrapped`, the one seam every tool source
  passes through (builtin, http, mcp, a2a, dynamic, memory, recall, plus the
  `mcpcalls` step). GenAI semconv attributes (`gen_ai.operation.name`,
  `gen_ai.tool.name`, `gen_ai.tool.call.id`, `eddi.tool.source`, `error.type`),
  semconv revision pinned via `LlmTelemetryListener.SEMCONV_SCHEMA_VERSION`. The
  span is *current* while the tool runs — inline, and on the timeout worker
  through the existing `carryContext` — so outbound requests are its children.
  Cache hits (`eddi.tool.cache_hit=true`) and rate-limited calls
  (`error.type=rate_limited`) get a span too: cheap when OTel is off, and exactly
  what a slow-turn investigation needs to see.
- **W3C `traceparent`/`tracestate` on outbound calls**, via a new
  `TracePropagation` bean (`W3CTraceContextPropagator` only — never `baggage`):
  httpcalls (`HttpClientWrapper`, on a copy of the request so the memory record
  and the HITL approval fingerprint do not change per send), `SafeHttpClient`
  (first hop and same-origin hops; dropped on cross-origin hops like
  credentials — the Vert.x redirect handler now drops them too), A2A (card fetch
  and task call) and MCP (`params._meta` per SEP-414 through langchain4j's
  `metaSupplier`, plus the HTTP header).
- **Config:** `eddi.tracing.propagate-outbound` (default `true`). Inert while
  `quarkus.otel.sdk.disabled=true` — a no-op span has an invalid context and
  produces no header, never an all-zero one.

### Design decisions

- **MCP threading.** Verified in the langchain4j-mcp 1.22.0-beta32 bytecode:
  `DefaultMcpClient` calls the meta supplier synchronously on the calling thread
  before the transport, but `StreamableHttpMcpTransport` rebuilds the request on
  an HTTP-client callback thread when it retries after session expiry. So the
  header supplier reads the trace context **from the message's `_meta`**, not
  from `Context.current()` — header and `_meta` cannot disagree, whichever thread
  builds the request.
- **Source and call id travel in the OTel `Context`** (`ToolCallTrace`), set by
  `ToolLoopRunner` around its one call into the service, rather than as two more
  parameters: they label the span and nothing else, `ToolInvocation`'s equality
  is a cost/cache identity, and a new overload would have broken the Mockito
  stubs of 17 test classes.
- **Vert.x client.** Quarkus' Vert.x HTTP tracer may also be active on the
  httpcalls client (it is built from the Quarkus `Vertx`); when it sees the
  parent it replaces the header with its own client span, a child of
  `execute_tool` — same trace. The explicit header is only added when absent, so
  an author-configured `traceparent` is never overwritten.
- **Tracer:** lazily resolved `GlobalOpenTelemetry.getTracer("eddi.tools")`, same
  pattern as `LlmTelemetryListener`/`LifecycleManager`; package-private field so
  tests install an SDK tracer without mutating the JVM-wide global.

### Docs

Monitoring guide: `execute_tool` span, propagation rules, and a "Sending traces
to Langfuse / Arize Phoenix / Jaeger / Tempo" section with endpoints verified
against each project's docs (Phoenix Cloud left explicitly unverified).
Configuration reference row; tracing note in `metrics.md`; Platform Operator
prompt line on tracing a slow tool (revision 16 → 17).

**Files:** [`TracePropagation.java`](../../src/main/java/ai/labs/eddi/engine/tracing/TracePropagation.java),
[`ToolExecutionService.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolExecutionService.java),
[`ToolCallTrace.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolCallTrace.java),
[`SafeHttpClient.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/SafeHttpClient.java),
[`HttpClientWrapper.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/impl/HttpClientWrapper.java),
[`HttpClientModule.java`](../../src/main/java/ai/labs/eddi/engine/httpclient/bootstrap/HttpClientModule.java),
[`McpToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/McpToolProviderManager.java),
[`A2AToolProviderManager.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/A2AToolProviderManager.java),
[`monitoring-guide.md`](../monitoring/monitoring-guide.md).

### Not verified

The acceptance check — one connected trace through the monitoring compose
(pipeline task → gen_ai span → execute_tool → downstream span) — was not run
end-to-end in a live stack; the unit tests pin each link.
