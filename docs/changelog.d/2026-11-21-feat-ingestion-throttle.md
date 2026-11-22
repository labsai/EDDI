## feat(ingestion): a provider's rate limit pauses a run instead of failing its documents, T1-min (2026-11-21)

**Repo:** EDDI (`feat/ingestion-throttle`, on `feat/google-drive-ingestion`)

### What changed and why

A real ingestion of a 68-file Shared Drive with `gemini-embedding-2` on the free tier hit
`429 RESOURCE_EXHAUSTED` (`EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier`, limit
100, `retryDelay: 26s`). langchain4j's retry waited about a second in all, ignoring the delay, and
the pipeline counted each document failed: 49 of 68. This is phase T1-min (§3.2) of
`planning/rag-large-scale-multimodal-ingestion-plan.md` (branch `docs/rag-scale-multimodal-plan`).

- **`ThrottledEmbeddingModel`** (`modules/llm/impl`). `EmbeddingModelFactory` wraps the `DOCUMENT`
  role only, outside `InputTypedEmbeddingModel` and on the pinned-task-type path too. A per-minute
  refusal is slept out for the provider's delay, up to `maxRetries` (default 5) per call, while the
  wait fits before the run's deadline. A run binds its deadline with `boundTo`, because the cached
  model is shared. An unbound caller waits at most `eddi.ingestion.throttle.unbound-max-wait`
  (default 10 s) per call; direct ingestion (`RagIngestionService`) binds 10 minutes of its own.
  Otherwise the model throws `ProviderThrottledException(retryAfter, daily, quotaId)`, whose
  `retryAfter` is only ever the provider's own delay. `embedAll` sends batches of at most
  `eddi.ingestion.embed-batch-size` (default 100) segments, each throttled on its own. Waits have a
  1 s floor.
- **Provider retries off for ingestion.** For `openai`, `ollama`, `mistral` and `gemini` (langchain4j's
  own HTTP client), the ingestion model is built with `maxRetries(0)`, and the decorator also retries
  5xx and timeouts with a 1 s, 2 s, 4 s… backoff. `azure-openai`, `vertex`, `bedrock` and `cohere`
  keep their SDK retries. The query role is built exactly as before.
- **`ProviderRetryHints`.** It reads the delay and the window through the existing
  `LlmFailureClassifier`, which gains `violatedQuotaId`. The delay is `RetryInfo.retryDelay`, else the
  message's "retry in Ns", else 30 s. The window is the quota id (`…PerMinute…` or `…PerDay…`);
  without one, a delay over 5 minutes counts as daily. `insufficient_quota` is not a throttle: it
  becomes `ProviderBillingException` (via the new `LlmFailureClassifier.isBillingFailure`), and the
  run stops `FAILED` with an error naming the account's plan and billing, no document counted failed.
- **Pipeline.** `acceptDocumentCounted` catches the throttle before `onPage`/`onFile`/`onText` can
  record a failure. It counts the document in `throttled`, marks a known document unreachable, and
  cancels the run. Every later changed document is deferred without a fetch or a provider call; an
  unchanged one is still counted unchanged and stamped seen, because the throttle guard sits after
  the version-token and citation checks (on both the fetch and the prefetched path). An interrupted
  wait is a `CancellationException`, not a throttle: the run ends `CANCELLED`. `discover()`
  then reports `StopReason.THROTTLED` with coverage `PARTIAL`, whatever the connector said, so
  nothing is tombstoned and the orphan sweep does not run. `IngestionReport.Outcome.THROTTLED`;
  the run's `status` stays `COMPLETED` and `error` stays empty. Both `StopReason` enums gain
  `THROTTLED`.
- **Records.** `IngestionRun` and `IngestionReport` gain `documentsThrottled` and `resumeAfter`, with
  back-compatible constructors. Postgres: `ADD COLUMN IF NOT EXISTS documents_throttled INTEGER NOT
  NULL DEFAULT 0` and `resume_after TIMESTAMP`. Mongo: `documentsThrottled` and `resumeAfter`. Absent
  values read as 0 and null.
- **`resumeAfter`.** For a rate limit, the provider's named delay, or null. For a daily quota, the
  provider's documented reset — Gemini's is midnight Pacific time (stated on its rate-limits page,
  verified 2026-11-21; `ProviderRetryHints.dailyReset`, `gemini` only, not `vertex`) — otherwise the
  named delay or `eddi.ingestion.throttle.daily-cooldown` (default 1 h).
- **Schedules and the gate.** `ScheduleFireExecutor` counts a `THROTTLED` run as a benign outcome, so a
  throttled run writes no FAILED row to the fire log. `RagSourceIngestionService.processScheduledFire`
  asks `IngestionPipeline.throttledUntil` before it claims a run: while the source's last run stopped
  `THROTTLED` and `resumeAfter` (or, without one, its end plus `eddi.ingestion.throttle.cooldown`,
  default 1 min) is ahead, the fire returns `IngestionReport.heldBackByThrottle` — no listing, no
  provider call — and the fire log gets a `COMPLETED` row whose text says until when. **Run now** is
  the explicit override and is not held.
- **REST.** `ProviderThrottledExceptionMapper` turns a `ProviderThrottledException` that reaches a
  request into `429` with `Retry-After` and a fixed message (no quota id).
- **Metrics.** `eddi.ingestion.throttled` (tags `knowledgeBase`, `source`) and
  `eddi.provider.throttle.wait` (timer, tag `provider`), listed in
  [`docs/metrics.md`](../metrics.md). `documentsThrottled` counts the documents the source still
  handed over after the stop, not everything left to do.
- **Manager.** The run history shows a pause icon. The stop reason reads "Paused by the provider's
  rate limit — it takes requests again from 14:32…", with the date when it is not today
  (`formatUpcomingTime`). Runs gain a "Waiting (rate limit)" counter and "N waiting" in the summary.
  The progress estimate ignores throttled runs. Strings are in all 11 locales.
- **Platform Operator.** The prompt explains `THROTTLED`, `documentsThrottled`, `resumeAfter`, the
  gate, and that `insufficient_quota` is a billing failure; revision bumped.
- **Docs.** [`docs/rag.md`](../rag.md#when-the-embedding-provider-throttles).

### What langchain4j carries (verified)

`JdkHttpClient.execute` throws `HttpException(status, body)`, with the raw response body as the
message, and drops the headers. `DefaultExceptionMapper` maps 429 to `new RateLimitException(cause)`.
`RetryUtils` with `maxRetries = 0` makes exactly one attempt. `ProviderRetryHintsTest` pins all of this
against a real `GoogleAiEmbeddingModel` over a fake HTTP client answering the live 429 body. An HTTP
`Retry-After` header is therefore unreachable through these clients, so no parser for it was added.

### Design decisions

- **No one-shot resume schedule.** §3.2 item 5 allows either the next scheduled run or a one-shot at
  `resumeAfter`. This phase gates the scheduled run on `resumeAfter` and relies on its full listing. A
  source without a cron waits for **Run now**, and neither the docs nor the Manager promise an
  automatic resume.
- **The gate holds scheduled fires only, and records no run row.** A run row per held fire would
  push real runs out of the history (a `*/10` cron writes 144 a day) and would itself become "the
  last run" and open the gate; the fire-log note says it instead.
- **Batching lives in the decorator,** not the pipeline, so direct ingestion and any other caller of
  the `DOCUMENT` model get it. A document is still stored whole or not at all.
- **#1489 (`KnowledgeBaseReembedService`).** Its synchronous probe now waits at most 10 s and throws
  `ProviderThrottledException`; its `catch (RuntimeException)` must rethrow that (so the new mapper
  answers 429 with `Retry-After`) or `RagFailures` must map it, before the two merge. `RagFailures`
  also needs branches for `ProviderThrottledException` and `ProviderBillingException` ahead of its
  `RateLimitException` cause check, or a spent balance reads "try again later".
- **No new run status.** `Status.parse` reads an unknown value as `FAILED`, so a rollback would have
  shown every throttled run as failed. The stop reason carries it instead.
- **Compatible with phase Q (§3.3).** The decorator is the single place that decides wait-or-stop.
  The shared cooldown row and the `requestsPerMinute` bucket go in front of the same call loop,
  keyed per `(provider, host, model, credential scope)`.

### Not done

- Not run against a real Gemini free-tier key.
