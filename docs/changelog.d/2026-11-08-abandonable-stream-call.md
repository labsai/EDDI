## ♻️ refactor(llm): one AbandonableStreamCall for both streaming skeletons (2026-11-08)

**Repo:** EDDI (`refactor/abandonable-stream-call`)

### What changed and why

The legacy streaming path (`StreamingLegacyChatExecutor`) and the tool loop's streaming bridge (`ToolLoopStreamingChatModel`) each carried their own copy of the same concurrency skeleton: a latch per call, an `abandoned` gate, a lock that makes recording, forwarding and abandoning mutually exclusive, and the await with a timeout. The legacy copy was marked "KEEP IN SYNC" with the other, which is the only thing that kept a concurrency fix in one from missing the other.

- **New `AbandonableStreamCall`** — the one implementation. A fresh instance per call is the provider's `StreamingChatResponseHandler`; `await(seconds)` abandons the call before it returns on a timeout or an interrupt, `abandon()` covers a retried error, `forwardedText()` reads under the lock, and `runUnlessAbandoned` covers the legacy path's single-chunk fallback emit. Both callers keep their own policy: what an interrupt means (legacy salvages or throws, the tool loop throws), the timeout metric, the separator between tool-loop rounds, and the log wording.
- **The below-backstop `timeout` warning is no longer static state.** Its warn-once key set moved into an instance helper owned by the executor (one executor per `LlmTask`, shared with the cascade, so still once per process in production), and the two test-only static hooks (`shortTimeoutWarningCount`, `resetShortTimeoutWarnings`) are gone — the tests count the WARN lines instead.
- **`resolveTimeoutSeconds` got its Javadoc back.** The precedence rules for `timeout` against `streamingTimeoutSeconds` sat above a second Javadoc block and documented nothing. The static method is now the pure resolution (the cascade's step margin uses it and logs nothing); requests go through the instance method `backstopSeconds`, which also warns. `LlmTask`'s tool-loop bridge uses `backstopSeconds`, so that path still warns as before.

Behaviour is unchanged: the same text reaches the sink and memory, the same exceptions, metadata, metrics and log lines.

**Files:** [`AbandonableStreamCall.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/AbandonableStreamCall.java), [`StreamingLegacyChatExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/StreamingLegacyChatExecutor.java), [`ToolLoopStreamingChatModel.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopStreamingChatModel.java), [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`AbandonableStreamCallTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/AbandonableStreamCallTest.java), [`StreamingLegacyChatExecutorTimeoutTest.java`](../../src/test/java/ai/labs/eddi/modules/llm/impl/StreamingLegacyChatExecutorTimeoutTest.java)

### Design decisions

- **A handler object, not a template method.** The two callers differ in everything after the await (retry and salvage versus a thrown exception the tool loop's retry classifies), so the shared part is the call's state and its gate, and each caller keeps its control flow.
- **`abandoned` is a plain field under the lock.** Both copies only ever read and wrote the `AtomicBoolean` inside the lock, so the atomic added nothing.
- **Not unified here:** the timeout counter is still incremented from the executor's injected `MeterRegistry` on one path and from `Metrics.globalRegistry` on the other. Moving the tool loop onto the injected registry changes which registry a deployment sees the `tool_loop` series in, which is a behaviour change for a separate PR.
