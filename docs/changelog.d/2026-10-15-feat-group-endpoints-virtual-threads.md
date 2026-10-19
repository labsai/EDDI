## perf(groups): run the blocking group endpoints on virtual threads (2026-10-15)

**Repo:** EDDI · **Branch:** `feat/group-endpoints-virtual-threads`

### What changed and why

This is the last part of the throughput plan's WS2 (item 5). Six group endpoints run discussion
work inline, and each one held a Quarkus worker thread for an entire multi-agent discussion,
every member turn and LLM call included:

- `discuss`
- `followUpWithMember`
- `continueDiscussion`
- `approveGroupPhase`
- `submitHumanInput`
- `approveGroupPhaseStreaming`, which calls `resumeDiscussion` on the request thread

The worker pool has about 200 threads and blocking REST endpoints share it, so a handful of
long discussions could starve unrelated requests. Conversation turns already left that pool
(PR #1045), and the member turns inside a discussion already run on virtual threads.

[`RestGroupConversation`](../../src/main/java/ai/labs/eddi/engine/internal/RestGroupConversation.java)
now marks those six methods `@RunOnVirtualThread`. The other streaming endpoints were already
asynchronous and are unchanged: `discussStreaming` goes through `startAndDiscussAsync`, and
`continueDiscussionStreaming` uses its own executor.

### Design decisions

- **The annotation is on the implementing methods, not on `IRestGroupConversation`.** The JAX-RS
  contract stays free of execution-model detail. Whether Quarkus REST honours the annotation
  there was the open question; `GroupEndpointsVirtualThreadIT` settles it over HTTP.
- **The proof is an IT, not a unit test.** The execution model is chosen when Quarkus dispatches
  the request, so calling the bean directly cannot observe it. The IT installs a Mockito
  stand-in for `GroupConversationService` with `QuarkusMock` (Mockito, not a JDK proxy, because
  `QuarkusMock` needs an instance of the bean class). The stand-in records
  `Thread.isVirtual()` for each service call and completes the streaming listener.

### Verification

- `GroupEndpointsVirtualThreadIT`: 6 tests, one per endpoint. With every annotation removed,
  all 6 fail with "a worker thread".
- `GroupConversationIT` and `GroupHitlIT`, which drive real discussions, approvals and human
  input through these endpoints, pass (12 tests).
- 1,106 group unit tests pass, along with `ImportStyleTest`, `BuildQualityGatesTest` and
  `ChangelogRotationTest`.
