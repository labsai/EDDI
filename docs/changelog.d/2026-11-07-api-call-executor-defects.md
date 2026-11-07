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

### Behaviour changes

- A `preRequest` property instruction with a `toObjectPath` now writes into the
  template data the request renders from; the rebuild used to discard it.
- The `{memory…}` view in the request templates is no longer rebuilt after the
  instructions run, so it does not show a property they just set;
  `{properties.x}` does, as it always has and as it does in `postResponse`.

**Files:** [`PrePostUtils.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/PrePostUtils.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java);
tests `PrePostUtilsTest`, `PrePostUtilsDeepBranchTest`, `PrePostUtilsSecretScopeTest`,
`ApiCallExecutorPreRequestTest` (new), and the constructor call in the other `PrePostUtils` users.
