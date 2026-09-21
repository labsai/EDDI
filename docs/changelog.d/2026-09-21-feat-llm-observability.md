## 📊 feat(llm): per-call LLM telemetry on every provider and both execution paths (2026-09-21)

**Repo:** EDDI (`feat/llm-observability`)

An agent that names a single model — almost every agent — produced **no LLM telemetry at all**:
no latency timer, no token counter, no error counter, and no span below `eddi.pipeline.task`. A
turn that spent eleven seconds waiting on a provider was indistinguishable from one that spent
eleven seconds in EDDI's own code. The `eddi.llm.cascade.*` meters that did exist live in
`CascadingModelExecutor`, which `LlmTask` reaches solely under `if (cascadeActive)`.

### Why there was nowhere to put it

`ChatModel.chat(ChatRequest)` delegates to `chat(ChatRequest, ChatRequestOptions)`, and *that*
is where the interface merges `defaultRequestParameters()` and fires
`onRequest`/`onResponse`/`onError` from `listeners()` around `doChat`. It is the only per-call
hook langchain4j offers, and it covers every provider because the dispatch lives on the
interface rather than in each binding.

`ObservableChatModel` overrode `chat(ChatRequest)`, which bypassed that default entirely — so
the decorator had no listener dispatch of its own. And `wrapIfNeeded` returned the **bare**
model unless `timeout`/`logRequests`/`logResponses` was configured, which is the default, so
most deployments had no decorator at all.

### What changed

- **`ObservableChatModel`** now overrides `doChat` and delegates `defaultRequestParameters()`,
  `provider()` and `supportedCapabilities()`, so the inherited `chat` behaves as the provider's
  own would and fires listeners.
- **Both decorators always wrap.** Returning the bare model is what left the default path with
  nowhere to attach a listener.
- **`LlmTelemetryListener`** (new) emits `eddi.llm.request.duration`, `eddi.llm.tokens` and
  `eddi.llm.request.errors`, plus a `gen_ai.client.inference` span.

### Three design decisions that are not the obvious ones

- **`doChat` forwards to `delegate.chat(...)`, not `delegate.doChat(...)`.** A `ChatModel` may
  implement either, and `JlamaChatModel` implements `chat` — forwarding to `doChat` would throw
  `"Not implemented"` on every Jlama turn, i.e. on the provider the previous PR just fixed.
- **`listeners()` returns EDDI's listener only, never the delegate's.** Because the delegate
  re-enters its own `chat`, it dispatches its own listeners; combining the lists would fire
  every provider-registered listener twice. Mutation-tested: the combining version produces
  `[request, request, error, error]`.
- **`ObservableStreamingChatModel` moved from overriding both `chat` overloads to overriding
  `doChat`.** This was a live defect in an earlier revision of this branch: the two-argument
  `chat(request, options, handler)` is where the streaming interface reads `listeners()`, so
  overriding it meant EDDI's listener never fired on a streaming turn — silently, while the
  javadoc claimed otherwise. Nothing caught it because every streaming test passed `null` for
  the listener and asserted only on tokens.

### Also fixed

The timeout path boxed the provider's exception in a bare `RuntimeException`. Harmless before;
now that the error tag and `error.type` are derived from the exception class, it made every
failure on a timeout-configured agent read as `RuntimeException`. The cause is rethrown
directly, and the timeout itself gets a named `ChatTimeoutException`.

`LlmTelemetryListener.guard` logs at WARN, not DEBUG. langchain4j already contains a throwing
listener (`ChatModelListenerUtils` wraps each in `try/catch` and logs WARN), so catching at
DEBUG would only have downgraded upstream's message — meaning a misconfigured `MeterRegistry`
would lose every LLM meter with nothing in the log at default levels.

### Semantic conventions

The span uses the **current** names — `gen_ai.provider.name` (renamed from `gen_ai.system` in
semconv v1.37.0) and `gen_ai.usage.input_tokens`/`output_tokens`. That namespace is still
Development-status and moved to its own repository in v1.42.0 precisely so it can keep changing,
so the span also carries `eddi.semconv.schema_version` recording the revision the names came
from. Micrometer meter names stay EDDI-owned (`eddi.llm.*`) so an upstream rename cannot break a
dashboard.

### Tests

`ObservableChatModelDecoratorTest` (9, new) uses a delegate shaped like a real binding rather
than a mock stubbing `chat`, and pins the properties the old tests could not see: a `chat`-only
delegate still works, each listener fires exactly once, the decorator carries only EDDI's
listener, and the legacy `chat(List<ChatMessage>)` route — the default non-JSON path through
`LegacyChatExecutor` — is observed too. `LlmTelemetryListenerTest` (8, new) grades the meters,
including that absent token usage records *nothing* rather than a zero. Two new streaming tests
assert a listener actually fires; mutation-checking the old override shape turns them red with
`[]` against `[request, response]`.

`docs/metrics.md` and the full-metrics Grafana dashboard gained the three meters —
`MetricsDashboardCoverageTest` enforces both.

### Follow-up: the p95 panel had no series to query

Raised in review of [#809](https://github.com/labsai/EDDI/pull/809). A Micrometer timer
publishes `_count`, `_sum` and `_max` and no buckets at all, so
`eddi_llm_request_duration_seconds_bucket` — the series panel `id: 169` runs
`histogram_quantile(0.95, ...)` over — was never exported. The panel would have rendered
empty forever, which on a latency chart reads as "no LLM traffic" rather than "this metric
does not exist".

`eddi.llm.request.duration` is now registered with `publishPercentileHistogram()`, the same
way `eddi.pipeline.task.duration` in `LifecycleManager` already is — which is also why the
pipeline p95 panels next to it do work. The cost is one series per bucket per
`provider`/`model`/`outcome`, and the tag set is bounded the same way: providers are a fixed
list, `outcome` is success or error, and model names come from configuration rather than from
user input. If a deployment does find that too much, the dashboard-side alternative is to plot
`_sum / _count` as a mean and drop the line; that is recorded at the call site.

`LlmTelemetryListenerTest.durationTimerPublishesHistogramBuckets` asserts against a real
`PrometheusMeterRegistry.scrape()` rather than a `takeSnapshot().histogramCounts()`, because
the scrape text is literally what the dashboard queries — a snapshot assertion would pass on a
registry that never exports the buckets. Mutation-checked: removing
`publishPercentileHistogram()` turns exactly that test red.
