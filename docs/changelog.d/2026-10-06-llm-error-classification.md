## feat(llm): classify provider failures, honour retry delays, one retry owner (2026-10-06)

**Repo:** EDDI (`feat/llm-error-classification`)

### What changed and why

LLM retries decided on the HTTP status alone, so a Gemini or OpenAI **daily quota** 429 was retried like a
per-minute rate limit (wasted attempts that cannot succeed), the provider's own retry delay was ignored, an
HTTP 500 was never retried, and langchain4j's built-in client retries stacked underneath EDDI's
(`maxAttempts: 3` could mean nine provider calls). This is R2 of the LLM turn-resilience plan.

- **Failure classes.** New `FailureClass` (`TRANSIENT`, `RATE_LIMITED`, `QUOTA_EXHAUSTED`, `AUTH`, `BAD_REQUEST`,
  `CONTEXT_TOO_LONG`, `MODEL_NOT_FOUND`, `TIMEOUT`, `UNKNOWN`), `LlmFailure(cls, retryAfterMs, reason)` and
  `LlmFailureClassifier` ([`LlmFailureClassifier.java`](../../src/main/java/ai/labs/eddi/configs/shared/LlmFailureClassifier.java)).
  It walks the whole cause chain and reads the provider error **body** that langchain4j keeps as the
  `HttpException` message: Gemini `error.status` and `details[]` (`QuotaFailure` per-day vs per-minute,
  `RetryInfo.retryDelay`), OpenAI `insufficient_quota` / `rate_limit_exceeded` / `context_length_exceeded`,
  Anthropic `overloaded_error` / `rate_limit_error` / "prompt is too long". `RetryConfiguration.isRetryableError`
  is now a wrapper: retryable = `TRANSIENT`, `RATE_LIMITED`, `TIMEOUT`. The existing "typed verdict beats an outer
  message" ordering is preserved.
- **Bug fix: HTTP 500 is now retried** (and Gemini `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`, Anthropic 529).
  Side effect: on the native-JSON-mode path a gateway that answers an unsupported `response_format` with a
  **500** used to fall back to a plain request; it now fails the turn like any other 5xx. Set
  `jsonResponseFormat: "off"` on such a gateway.
- **Quota 429s are not retried** and escalate the cascade immediately (`QUOTA_EXHAUSTED`).
- **Retry delay honoured.** New optional `retry` fields `honorRetryAfter` (default `true`) and `maxRetryAfterMs`
  (default `10000`, engine ceiling 30000). The provider's delay replaces the configured backoff when it is larger;
  a delay above `maxRetryAfterMs` stops retrying **without sleeping** so the cascade escalates at once. An HTTP
  `Retry-After` header is not reachable: langchain4j's `HttpException` keeps only the status and the body.
- **Single retry owner.** `maxRetries(0)` on the synchronous OpenAI, Anthropic, Gemini, Vertex Gemini, Mistral,
  Ollama, Azure OpenAI and Bedrock clients; `executeWithRetry` is the only loop. Hugging Face and OCI GenAI expose
  no setting, streaming clients have none (EDDI's own streaming loop retries). The judge model, tool-response
  summariser and `SummarizationService` call models with no task retry policy, so they now go through
  `RetryConfiguration.executeWithDefaultRetry` (default policy, original exception rethrown unwrapped): every
  model call has exactly one retry owner. The Azure OpenAI and Vertex Gemini builders use the same constant but are
  not asserted in tests (Azure exposes no retry field; Vertex needs GCP credentials to build).
- **Cascade.** Failed steps carry `failureClass` (and `retryAfterMs`) in the trace entry, additively, and count
  `eddi.llm.failure{class,model}`. `status` / `error` / escalation reason strings are unchanged.
- Docs: [`langchain.md`](../langchain.md#retry-configuration) (the default is 3 attempts even without a `retry`
  block, classification table), [`model-cascade.md`](../model-cascade.md), [`metrics.md`](../metrics.md).

### Design decisions

- `honorRetryAfter` defaults to `true`: it only lengthens a sleep that would have happened anyway, and retrying a
  rate limit earlier than the provider asked just burns an attempt.
- The classifier lives in `configs.shared` because `RetryConfiguration` (shared with MCP calls) is its main caller.
- No new model parameter for `maxRetries`: a configurable library retry count would recreate the double loop.

### Follow-ups

- Streaming retry loop (`StreamingLegacyChatExecutor`) still uses the plain backoff, not the provider delay.
- Manager's retry editor does not expose `honorRetryAfter` / `maxRetryAfterMs` (settable in JSON).
- R8 (circuit breaker) and R5 (`onContextTooLong`) can now key on `FailureClass`.
