## 🐛 fix(apicalls): preRequest updates template data in place; final-attempt failure keys; fire-and-forget batch continues (2026-11-07)

**Repo:** EDDI (`fix/api-call-executor-defects`)

### What changed and why

**`preRequest.propertyInstructions` no longer wipe the call's template data.**
`PrePostUtils.executePreRequestPropertyInstructions` ran the instructions and
then rebuilt the template data from conversation memory, returning a fresh map.
Everything the caller had put in the map was gone: the arguments an LLM tool
call merged in (`DELETE /items/{id}` went out as `DELETE /items/`), and in a
rule-triggered batch the `{responseObjectName}`, `…Error`, `…HttpCode` and
header values the call wrote, which the next call of the turn could no longer
template. An empty `propertyInstructions: []` list did the same. The method now
works like `runPostResponse`: the instructions update the caller's map in place
(each one already refreshed `{properties}` there), a plaintext a
`scope: "secret"` instruction vaulted is scrubbed from it, and the same map is
returned. `LlmTask` (which ignored the return value) and `McpCallsTask` are fixed
by the same change. `PrePostUtils` no longer needs `IMemoryItemConverter`; the
constructor parameter is gone. The HITL pinning predicate
(`canExecuteDivergeFromResolve`) is unchanged — a call with pre-request
instructions stays unpinnable — and its Javadoc now gives the real reason.

**A retried call's failure keys come from its final attempt.** On a non-2xx
response with `saveResponse`, every attempt wrote `{name}Error` and
`{name}HttpCode` to memory and to the template data, and nothing removed them:
a `503` followed by a successful retry left `weatherHttpCode=503` and the 503
body next to a good `weather`, where `postResponse`, output templates and the
next call of the turn all saw a failure. A `503` with a body followed by a
`500` with a blank one paired the first body with the second code. Now the
template data holds the current attempt's failure keys only (they are removed
before the next attempt, so a `responseValuePathMatchers` entry on
`{name}Error` still judges the response it decides about), and memory records
the final attempt's failure once, after the loop — as the `forEach` path does.

**One failed request of a fire-and-forget batch no longer drops the rest.**
A `fireAndForget` call with `preRequest.batchRequests` sends its requests as
one background task. With `executeCallsSequentially`, a request that threw (a
timeout, a connection reset) ended that task, so every later request was never
sent; the only trace was a generic runtime error naming neither the call nor
the item. In the concurrent mode the same happened when a dispatch threw
synchronously. Each request is now sent on its own: a failure is logged with
the call name, its position (`request 2 of 5`) and a redacted reason, the rest
of the batch still goes out, and a summary line counts the failures. An
interrupted batch thread stops sending and says how many were left.

### Behaviour changes

- A `preRequest` property instruction with a `toObjectPath` now writes into the
  template data the request renders from; the rebuild used to discard it.
- The `{memory…}` view in the request templates is no longer rebuilt after the
  instructions run, so it does not show a property they just set;
  `{properties.x}` does, as it always has and as it does in `postResponse`.
- A retry request can no longer template the previous attempt's
  `{name}HttpCode` / `{name}Error`. That was undocumented, and it is the
  divergence between the previewed and the sent request that the HITL pinning
  guard warns about.
- Memory gets one `{name}Error` / `{name}HttpCode` entry per call instead of one
  per failed attempt, written after the response headers rather than before.

**Files:** [`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`httpcalls.md`](../httpcalls.md) (`executeCallsSequentially` row);
tests `PrePostUtilsTest`, `PrePostUtilsDeepBranchTest`, `PrePostUtilsSecretScopeTest`,
`ApiCallExecutorTest`, `ApiCallExecutorPreRequestTest` (new), `ApiCallExecutorFireAndForgetBatchTest` (new),
and the constructor call in the other `PrePostUtils` users.
