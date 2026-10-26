## ✨ feat(httpcalls): `forEach` — one call per element, every result collected (2026-10-24)

**Repo:** EDDI (`feat/step-checks-foreach`) — item E5 part A of the evaluated-generation plan

### What changed and why

`preRequest.batchRequests` only works with `fireAndForget`, so a call that needed one request per element of an array
threw every response away. An httpcall now takes an optional `forEach` block that sends the call once per element and
stores what each element produced — `index`, `ok`, `httpCode`, `body` or `error`, `timedOut`, `sent`, `attempts`,
`bodyDropped` — as one array under `responseObjectName`, in source order whatever order the items finished in.
`postResponse` runs once with `200` when every item succeeded and `207` (Multi-Status) when any failed, so property
instructions gated on the default codes only see complete results unless the author opts in.

- **New field, not an extension of `batchRequests`.** A stored config with `batchRequests` on a call that waits for its
  response sends exactly one request today; reusing the field would have turned it into N requests silently. Without
  `forEach` nothing changes, and `forEach` is refused (400) together with `fireAndForget` or `batchRequests`.
- **Bounded four ways.** `maxItems` (the batch default 100 and ceiling 1000, refused as a whole before anything is
  sent), `maxConcurrency` (default 1 — the load the same calls written one after another would send; at most 16, on
  virtual threads), the turn deadline per item (an item whose turn comes after it is not sent), and memory: each body
  is capped by `maxResponseSizeInBytes` and all of them together by `maxTotalResponseSizeInBytes` (default the same
  value, so the array gets what one response would).
- **Never abandon paid work.** A failing item never cancels its siblings; the pool is shut down, never interrupted;
  `onItemFailure: "fail"` fails the step only after every item finished and the results are stored. When the step stops
  waiting, queued items are not started at all, so nothing is sent whose result nobody collects.
- **A request that threw is never retried** — a timeout above all, since the API may have processed (and billed) it.
  `retryApiCallInstruction` still applies per item to responses.
- **Security unchanged.** Every item's request is built on the turn's thread by the same `buildRequest` a single call
  uses (templating, vault/connection/caller resolution, SSRF check, approval scope); only sending is parallel. The
  element is bound as template data, never template source. A `forEach` call is unpinnable for HITL fingerprints, like
  a fire-and-forget batch.
- **Seams left for parallel work.** `BoundedFanOut.Admission` is asked before each item starts and told when it
  finished — where the per-conversation cost reservation (E4) plugs in. The fan-out runner is generic, so mcpcalls can
  reuse it. The deploy-time turn-budget warning counts a `forEach` call once per round.

**Files:** [`ForEachInstruction.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/ForEachInstruction.java),
[`ApiCall.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/model/ApiCall.java),
[`ApiCallExecutor.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`ForEachItems.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ForEachItems.java),
[`ForEachItemResult.java`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ForEachItemResult.java),
[`BoundedFanOut.java`](../../src/main/java/ai/labs/eddi/engine/runtime/BoundedFanOut.java),
[`ApiCallsConfigurationValidator.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/ApiCallsConfigurationValidator.java),
[`RestApiCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/rest/RestApiCallsStore.java),
[`TurnResilienceWarnings.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/TurnResilienceWarnings.java),
[`docs/httpcalls.md`](../httpcalls.md) (also drops a duplicated "Turn deadline" section), the Platform Operator prompt
and revision.

### What's next

Part B of E5 — `checks` on httpcall and mcpcall results, reusing the output guardrail types — and `forEach` on
mcpcalls are not in this change; the plan is in the PR description.

```decision-log
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
```

### End-to-end proof

`ForEachHttpCallsIT` drives a deployed agent over REST against WireMock: one request per element (templated into path
and body, source order, read back from the stored conversation), a filter leaving index gaps, `maxItems` refusing the
whole call before anything is sent, the total-size budget counting UTF-8 bytes of a non-ASCII payload, an empty list
sending nothing, a 500 and a timeout not stopping or being retried, `207` gating of `postResponse`, `onItemFailure:
"fail"` running every item first, and the concurrency bound (1 by default, 2 when configured) observed from WireMock's
arrival times.
