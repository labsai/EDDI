/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes streaming chat completion — tokens are emitted in real-time via the
 * {@link ConversationEventSink}.
 * <p>
 * This class blocks until the full response is received (via
 * {@link AbandonableStreamCall}) so that the lifecycle workflow can proceed
 * synchronously while tokens stream to the client.
 */
class StreamingLegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(StreamingLegacyChatExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final String KEY_TIMEOUT = "timeout";

    /**
     * Optional — used to count providers that never emit partial responses (finding
     * F8). {@code null} in the direct-construction unit tests.
     */
    private final MeterRegistry meterRegistry;

    /**
     * Tasks this executor has already warned about a below-backstop
     * {@code timeout}.
     */
    private final ShortTimeoutWarnings shortTimeoutWarnings = new ShortTimeoutWarnings();

    StreamingLegacyChatExecutor() {
        this(null);
    }

    StreamingLegacyChatExecutor(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Result of a streaming chat execution, including response text and metadata.
     *
     * @param response
     *            the full accumulated response text
     * @param metadata
     *            metadata about the streaming execution (finishReason, warnings)
     */
    record StreamingResult(String response, Map<String, Object> metadata) {
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink.
     * <p>
     * Backward-compatible wrapper that delegates to the task-aware overload with a
     * null task (uses default timeout, no retry).
     *
     * @param streamingModel
     *            the streaming-capable chat model
     * @param messages
     *            the full message list (system + history + user)
     * @param eventSink
     *            the sink to emit token events to
     * @return the full accumulated response text (for memory storage)
     */
    String execute(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {
        return execute(streamingModel, messages, eventSink, null, JsonResponseFormatPolicy.DISABLED).response();
    }

    /**
     * Result of a streaming execution — the full text plus response metadata
     * (finish reason, token usage) captured from the final response.
     */
    record StreamResult(String response, Map<String, Object> responseMetadata) {
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink and
     * capturing the final response metadata (token usage). Used by the cascade to
     * stream the final step live without losing cost/token evidence.
     * <p>
     * Unlike
     * {@link #execute(StreamingChatModel, List, ConversationEventSink, LlmConfiguration.Task)},
     * a mid-stream error is <em>always</em> propagated here, even when tokens were
     * already emitted: the cascade must see the failure to fall back to the best
     * previous step. Salvaging the partial text would let a failed final step be
     * accepted as a successful one.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {
        return executeCapturing(streamingModel, messages, eventSink, null, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * As
     * {@link #executeCapturing(StreamingChatModel, List, ConversationEventSink)},
     * but honouring the task's resolved streaming backstop (see
     * {@link #resolveTimeoutSeconds(LlmConfiguration.Task)}).
     * <p>
     * The task's retry config is deliberately <em>not</em> applied: the cascade
     * owns escalation, and retrying inside a step would multiply spend against the
     * very model the cascade is about to escalate away from. Each step gets one
     * attempt.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink,
                                  LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy) {
        var result = execute(streamingModel, messages, eventSink, task, false, 1, jsonPolicy);
        return new StreamResult(result.response(), result.metadata());
    }

    /**
     * Backward-compatible overload without a JSON response-format policy.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink,
                                  LlmConfiguration.Task task) {
        return executeCapturing(streamingModel, messages, eventSink, task, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * Execute a streaming chat completion with configurable timeout, retry on total
     * failure, and response metadata capture (finish reason and token usage).
     *
     * @param streamingModel
     *            the streaming-capable chat model
     * @param messages
     *            the full message list (system + history + user)
     * @param eventSink
     *            the sink to emit token events to
     * @param task
     *            task configuration (for timeout and retry settings, may be null)
     * @param jsonPolicy
     *            decides whether the streamed request carries
     *            {@code ResponseFormat.JSON}; {@code null} is treated as
     *            {@link JsonResponseFormatPolicy#DISABLED}
     * @return a {@link StreamingResult} with the response text and metadata
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy) {
        return execute(streamingModel, messages, eventSink, task, true, resolveMaxAttempts(task), jsonPolicy);
    }

    /**
     * Backward-compatible overload without a JSON response-format policy — the
     * streamed request carries no response format.
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task) {
        return execute(streamingModel, messages, eventSink, task, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * Resolve the attempt count from the task's retry config, bounded at both ends.
     *
     * <p>
     * At least one: {@code maxAttempts <= 0} means "don't retry", not "never call
     * the model". Without that the retry loop body never runs and the caller gets a
     * null response indistinguishable from silence.
     * </p>
     *
     * <p>
     * And at most {@code RetryConfiguration.MAX_ATTEMPTS_CEILING}. This used to
     * read {@code getMaxAttempts()} raw, so the engine ceiling that bounds the tool
     * loop did not apply here: the identical {@code retry} block was capped on one
     * path and unbounded on the other, and a streaming task could hold its worker
     * for as many attempts as the config asked for.
     * </p>
     */
    private static int resolveMaxAttempts(LlmConfiguration.Task task) {
        RetryConfiguration retryConfig = task != null ? task.getRetry() : null;
        int configured = retryConfig != null && retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 1;
        return Math.max(1, RetryConfiguration.clampAttempts(configured));
    }

    /**
     * Resolve the overall wall-clock backstop for one streaming attempt.
     * <p>
     * Two settings bound a stream, and they are deliberately kept distinct rather
     * than merged, because they bound different things:
     * <ul>
     * <li>the {@code timeout} model parameter (milliseconds) is handed to the
     * provider's streaming HTTP client — for the JDK client it bounds the time to
     * the first response, so it detects a provider that never answers without
     * truncating one that answers slowly;</li>
     * <li>{@code streamingTimeoutSeconds} is this overall backstop, covering the
     * whole stream for providers whose native timeout does not fire (or does not
     * exist).</li>
     * </ul>
     * Precedence, preserving both back-compatible shapes:
     * <ol>
     * <li>an explicit positive {@code streamingTimeoutSeconds} always wins —
     * configs that set only that field behave exactly as before;</li>
     * <li>otherwise the backstop is the 120s default, raised (never lowered) to
     * cover an explicitly configured {@code timeout}. A config that sets only
     * {@code timeout} therefore keeps the 120s default for any value up to 120s,
     * and no longer has a long deliberate timeout silently cut short at 120s;</li>
     * <li>otherwise the 120s default.</li>
     * </ol>
     * The {@code timeout} value is read from the task's raw parameters. A
     * Qute-templated value cannot be resolved here and simply leaves the default in
     * place — the pre-existing behaviour, never a shorter bound.
     * <p>
     * This resolution logs no warning, so a caller that only derives a bound from
     * it (the cascade's step margin) adds nothing to the log. Requests go through
     * {@link #backstopSeconds}, which also says, once per task, when a short
     * {@code timeout} does not shorten the backstop.
     */
    static long resolveTimeoutSeconds(LlmConfiguration.Task task) {
        return resolveTimeoutSeconds(task, null);
    }

    /**
     * {@link #resolveTimeoutSeconds(LlmConfiguration.Task)} for a request this
     * executor (or the tool loop's streaming bridge) is about to make: the same
     * bound, plus a warning the first time a task's {@code timeout} turns out
     * shorter than the backstop it cannot shorten (finding F11).
     */
    long backstopSeconds(LlmConfiguration.Task task) {
        return resolveTimeoutSeconds(task, shortTimeoutWarnings);
    }

    /**
     * @param warnings
     *            where to record a below-backstop warning, or {@code null} to
     *            resolve without warning
     */
    private static long resolveTimeoutSeconds(LlmConfiguration.Task task, ShortTimeoutWarnings warnings) {
        if (task == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        if (task.getStreamingTimeoutSeconds() != null && task.getStreamingTimeoutSeconds() > 0) {
            return task.getStreamingTimeoutSeconds();
        }
        Map<String, String> parameters = task.getParameters();
        String timeoutMs = parameters != null ? parameters.get(KEY_TIMEOUT) : null;
        if (timeoutMs == null || timeoutMs.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            long millis = Long.parseLong(timeoutMs.trim());
            if (millis <= 0) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
            // Round up so a sub-second timeout never collapses the backstop to zero.
            long seconds = (millis + 999) / 1000;
            // Finding F11: the raise-only floor is deliberate (see the javadoc above),
            // but it used to be silent — an operator configuring timeout=5000 still
            // waited the full 120s with nothing in the log explaining why. Say so.
            if (warnings != null && seconds < DEFAULT_TIMEOUT_SECONDS && warnings.firstFor(task, millis)) {
                LOGGER.warnf("The 'timeout' parameter (%dms ≈ %ds) is SHORTER than the %ds streaming backstop and does NOT shorten it — "
                        + "the provider's own client timeout still applies, but this executor will wait up to %ds. "
                        + "Set 'streamingTimeoutSeconds' to lower the backstop.", millis, seconds, DEFAULT_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
            }
            return Math.max(DEFAULT_TIMEOUT_SECONDS, seconds);
        } catch (NumberFormatException e) {
            LOGGER.debugf("Ignoring non-numeric 'timeout' parameter '%s' when deriving the streaming backstop", timeoutMs);
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * Core streaming execution.
     *
     * @param salvagePartialOnError
     *            when {@code true}, a mid-stream error that already produced tokens
     *            returns the partial text with a {@code streaming_error_partial}
     *            warning instead of throwing — the turn keeps whatever the model
     *            managed to produce. When {@code false}, any error is propagated so
     *            the caller can treat the step as failed.
     * @param maxAttempts
     *            number of attempts; already clamped to >= 1 by the caller.
     * @param jsonPolicy
     *            decides whether the streamed request carries
     *            {@code ResponseFormat.JSON}. Streaming never carries tool
     *            specifications, so the policy is resolved with
     *            {@code toolsInRequest=false}.
     */
    private StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                                    ConversationEventSink eventSink, LlmConfiguration.Task task, boolean salvagePartialOnError, int maxAttempts,
                                    JsonResponseFormatPolicy jsonPolicy) {

        ResponseFormat responseFormat = jsonPolicy != null ? jsonPolicy.resolve(false) : null;

        LOGGER.debug("Executing with streaming (legacy mode)" + (responseFormat != null ? " with JSON response format" : ""));

        long timeoutSeconds = backstopSeconds(task);

        RetryConfiguration retryConfig = task != null ? task.getRetry() : null;

        Map<String, Object> metadata = new HashMap<>();
        String responseText = null;
        // Carried across attempts so this loop honours the same total-backoff
        // budget executeWithRetry applies. The per-sleep ceiling alone does not
        // bound it: ten attempts at a configured 30s delay is 270s of blocked
        // pipeline thread.
        long totalBackoffMs = 0L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Each attempt reports its own outcome. Without this reset, a
            // streamingTimeout/warning recorded by a failed attempt survives into a
            // successful retry, and responseValidation then acts on a stale signal —
            // replacing a perfectly good answer with the timeout fallback.
            metadata.clear();

            // One call per attempt: abandoning an attempt (timeout, interrupt, or a
            // retried error) silences its still-running callback thread, so a late
            // token never interleaves with the retry's tokens on the shared sink.
            var call = AbandonableStreamCall.forwardingTo((token, firstOfCall) -> {
                try {
                    eventSink.onToken(token);
                } catch (Exception e) {
                    LOGGER.warnf("Error sending token event: %s", e.getMessage());
                }
            });

            var requestBuilder = ChatRequest.builder().messages(messages);
            if (responseFormat != null) {
                requestBuilder.responseFormat(responseFormat);
            }
            streamingModel.chat(requestBuilder.build(), call);

            boolean timedOut = false;
            boolean interrupted = false;
            try {
                if (!call.await(timeoutSeconds)) {
                    LOGGER.warn("Streaming chat timed out");
                    timedOut = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Streaming chat was interrupted");
                interrupted = true;
            }

            // A timed-out or interrupted call is already abandoned, so this is exactly
            // the text the client was sent — an in-flight emission is included whole.
            responseText = call.forwardedText();
            ChatResponse response = call.response();
            Throwable error = call.error();
            metadata.putAll(buildMetadata(response));

            // Finding F8: a provider that completes without ever calling
            // onPartialResponse leaves the accumulated buffer empty, and the buffer used
            // to be the ONLY text source — onCompleteResponse's ChatResponse was read for
            // metadata and its aiMessage().text() thrown away. The turn then returned a
            // silent empty answer with no error, warning or metric. Fall back to the
            // complete response, and emit the token to the sink so the SSE client is not
            // left with nothing.
            if (!timedOut && !interrupted && error == null && responseText.isEmpty()) {
                String completeText = completeResponseText(response);
                if (completeText != null && !completeText.isEmpty()) {
                    LOGGER.warnf("Streaming provider emitted no partial responses; falling back to the complete response (%d chars). "
                            + "This provider does not support incremental streaming.", completeText.length());
                    metadata.put("streamingNoPartials", true);
                    // putIfAbsent, NOT put: buildMetadata may already have recorded
                    // "truncated" (finishReason=LENGTH) or "content_filter" — the only
                    // signals LlmTask.applyResponseValidation dispatches on. Overwriting
                    // them here would silently disable responseValidation.onTruncation /
                    // onContentFilter for this provider. The transport-capability note is
                    // the weaker signal and is already carried by streamingNoPartials.
                    metadata.putIfAbsent("warning", "streaming_no_partials");
                    incrementNoPartials();
                    responseText = completeText;
                    call.runUnlessAbandoned(() -> {
                        try {
                            eventSink.onToken(completeText);
                        } catch (Exception e) {
                            LOGGER.warnf("Error sending fallback token event: %s", e.getMessage());
                        }
                    });
                }
            }

            // An interrupt is a cancellation request — the cascade cancelling a step, or
            // the request being aborted. Never retry it (that would ignore the
            // cancellation) and never report it as a successful empty response, which
            // would let a cancelled step be accepted as a real answer. This matches how
            // AgentOrchestrator treats a set interrupt flag.
            if (interrupted) {
                metadata.put("streamingInterrupted", true);
                if (salvagePartialOnError && !responseText.isEmpty()) {
                    metadata.put("warning", "streaming_interrupted_partial");
                    LOGGER.warnf("Streaming interrupted with partial response (%d chars)", responseText.length());
                    return new StreamingResult(responseText, metadata);
                }
                throw new RuntimeException("Streaming chat interrupted");
            }

            if (timedOut) {
                incrementStreamTimeout();
                metadata.put("streamingTimeout", true);
                if (!responseText.isEmpty()) {
                    // Partial response available — return it with a warning
                    metadata.put("warning", "streaming_timeout_partial");
                    LOGGER.warnf("Streaming timed out after %ds with partial response (%d chars)", timeoutSeconds, responseText.length());
                    return new StreamingResult(responseText, metadata);
                }
                // No response at all — treat as retryable failure
                if (attempt < maxAttempts) {
                    long slept = RetryConfiguration.backoff(attempt, retryConfig, totalBackoffMs);
                    if (slept >= 0) {
                        totalBackoffMs += slept;
                        LOGGER.warnf("Streaming timed out with empty response, retrying (attempt %d/%d)", attempt, maxAttempts);
                        continue;
                    }
                    LOGGER.warnf("Streaming timed out with empty response and the retry backoff budget is spent after %d attempt(s)",
                            attempt);
                }
                LOGGER.errorf("Streaming timed out with empty response after %d attempt(s)", attempt);
                return new StreamingResult("", metadata);
            }

            if (error != null) {
                if (salvagePartialOnError && !responseText.isEmpty()) {
                    // Error fired but we have partial content — return it with warning
                    metadata.put("warning", "streaming_error_partial");
                    metadata.put("errorMessage", error.getMessage());
                    LOGGER.warnf("Streaming error with partial response (%d chars): %s", responseText.length(), error.getMessage());
                    return new StreamingResult(responseText, metadata);
                }
                // Nothing salvageable (no content, or the caller wants errors
                // propagated) — retry if possible
                if (attempt < maxAttempts) {
                    call.abandon();
                    long slept = RetryConfiguration.backoff(attempt, retryConfig, totalBackoffMs);
                    if (slept >= 0) {
                        totalBackoffMs += slept;
                        LOGGER.warnf("Streaming error with empty response, retrying (attempt %d/%d): %s",
                                attempt, maxAttempts, error.getMessage());
                        continue;
                    }
                    LOGGER.warnf("Streaming error with empty response and the retry backoff budget is spent after %d attempt(s): %s",
                            attempt, error.getMessage());
                }
                LOGGER.errorf("Streaming chat error after %d attempt(s): %s", attempt, error.getMessage());
                throw new RuntimeException("Streaming chat failed", error);
            }

            // Success — break out of retry loop
            break;
        }

        return new StreamingResult(responseText, metadata);
    }

    /**
     * The text of a completed streaming response, or {@code null} when the provider
     * gave none. Finding F8 — this value was previously never read.
     */
    static String completeResponseText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return null;
        }
        return response.aiMessage().text();
    }

    /**
     * L3: EDDI's streaming backstop fired. The provider's own timeout may report
     * the failure through the telemetry listener much later — or, for a stream that
     * simply stalls, never — so the abandonment is counted where EDDI decides it,
     * at the moment it happens.
     */
    private void incrementStreamTimeout() {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.stream.timeouts", "path", "legacy").increment();
        }
    }

    private void incrementNoPartials() {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.streaming.no_partials").increment();
        }
    }

    private static Map<String, Object> buildMetadata(ChatResponse response) {
        Map<String, Object> metadata = new HashMap<>();
        if (response != null && response.metadata() != null) {
            var meta = response.metadata();
            if (meta.finishReason() != null) {
                var finishReason = meta.finishReason().toString();
                metadata.put("finishReason", finishReason);

                // Flag non-normal finish reasons for downstream validation, matching
                // LegacyChatExecutor. Without this, responseValidation.onTruncation and
                // onContentFilter are unreachable on the streaming path. A later
                // timeout/error warning deliberately overwrites this — a transport
                // failure is the more urgent signal.
                if ("CONTENT_FILTER".equalsIgnoreCase(finishReason)) {
                    metadata.put("warning", "content_filter");
                    LOGGER.warnf("Streaming response was filtered by content policy (finishReason=%s)", finishReason);
                } else if ("LENGTH".equalsIgnoreCase(finishReason)) {
                    metadata.put("warning", "truncated");
                    LOGGER.warnf("Streaming response was truncated due to token limit (finishReason=%s)", finishReason);
                }
            }
            if (meta.tokenUsage() != null) {
                metadata.put("tokenUsage", ToolContextBudget.tokenUsageMap(meta.tokenUsage()));
            }
        }
        return metadata;
    }

    /**
     * Tasks already warned about a below-backstop {@code timeout}, keyed by task id
     * and configured value.
     * <p>
     * {@link #backstopSeconds} runs on the PER-REQUEST path (once per streaming
     * turn, once per live-streamed cascade step, and once per streamed tool-loop
     * turn), so an unconditional warning fires on every single turn for the whole
     * lifetime of a config that will never change. {@code ChatModelRegistry} makes
     * the same distinction deliberately — it warns on the build path only, never on
     * a cache hit.
     * <p>
     * Owned by the executor instance, not static: {@code LlmTask} builds one
     * executor and hands the same one to the cascade, so per instance is per
     * process in production, and each test gets a fresh one without a reset hook.
     * <p>
     * Explicitly bounded. An earlier comment claimed it was "bounded because a
     * config edit re-keys the entry", which is backwards: re-keying ADDS a key and
     * leaves the old one behind, so config edits are exactly what made this grow.
     * Keyed on (taskId, timeoutMs), the key space is unbounded over the life of a
     * process.
     * <p>
     * Bounded with a size check and a clear, NOT with a Caffeine cache: this is
     * read on the per-request path, and putting cache machinery there measurably
     * slowed it — two timing-sensitive tests failed on a 66ms budget when it was
     * tried. A plain key set plus an O(1) size check costs nothing per call.
     * <p>
     * Clearing on overflow rather than refusing to add: a hard cap would silently
     * stop warning about genuinely new misconfigurations, which is the failure mode
     * this warning exists to prevent. Worst case here is that an already-warned
     * task warns once more — noise, not silence.
     */
    private static final class ShortTimeoutWarnings {
        /** Upper bound on retained warn-suppression keys. */
        private static final int MAX_KEYS = 1_000;

        private final Set<String> warned = ConcurrentHashMap.newKeySet();

        /**
         * Record that this task's {@code timeout} has been warned about, returning true
         * when this call is the one that should emit the warning.
         */
        boolean firstFor(LlmConfiguration.Task task, long millis) {
            if (warned.size() >= MAX_KEYS) {
                warned.clear();
            }
            String id = task.getId() != null ? task.getId() : "<unnamed>";
            return warned.add(id + '@' + millis);
        }
    }
}
