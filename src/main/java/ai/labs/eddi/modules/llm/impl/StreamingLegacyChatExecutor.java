/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes streaming chat completion — tokens are emitted in real-time via the
 * {@link ConversationEventSink}.
 * <p>
 * This class blocks until the full response is received (via CountDownLatch) so
 * that the lifecycle workflow can proceed synchronously while tokens stream to
 * the client.
 */
class StreamingLegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(StreamingLegacyChatExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final String KEY_TIMEOUT = "timeout";

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
     * Resolve the attempt count from the task's retry config, clamped to at least
     * one: {@code maxAttempts <= 0} means "don't retry", not "never call the
     * model". Without the clamp the retry loop body never runs and the caller gets
     * a null response indistinguishable from silence.
     */
    private static int resolveMaxAttempts(LlmConfiguration.Task task) {
        RetryConfiguration retryConfig = task != null ? task.getRetry() : null;
        int configured = retryConfig != null && retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 1;
        return Math.max(1, configured);
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
     */
    static long resolveTimeoutSeconds(LlmConfiguration.Task task) {
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

        long timeoutSeconds = resolveTimeoutSeconds(task);

        RetryConfiguration retryConfig = task != null ? task.getRetry() : null;

        Map<String, Object> metadata = new HashMap<>();
        String responseText = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            // Each attempt reports its own outcome. Without this reset, a
            // streamingTimeout/warning recorded by a failed attempt survives into a
            // successful retry, and responseValidation then acts on a stale signal —
            // replacing a perfectly good answer with the timeout fallback.
            metadata.clear();

            var latch = new CountDownLatch(1);
            var fullResponse = new StringBuilder();
            var errorRef = new AtomicReference<Throwable>();
            var responseRef = new AtomicReference<ChatResponse>();
            // Abandoning an attempt (timeout, interrupt, or a retried error) does not
            // stop the provider's callback thread — this executor cannot cancel it. Without
            // this gate a late token from a timed-out attempt keeps writing to the shared
            // event sink while the retry streams its own tokens into the same sink, so the
            // client renders two answers interleaved and neither matches what is stored in
            // memory. Once an attempt is abandoned its handler goes silent.
            var abandoned = new AtomicBoolean(false);

            var requestBuilder = ChatRequest.builder().messages(messages);
            if (responseFormat != null) {
                requestBuilder.responseFormat(responseFormat);
            }
            var chatRequest = requestBuilder.build();

            streamingModel.chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    if (abandoned.get()) {
                        return;
                    }
                    fullResponse.append(partialResponse);
                    try {
                        eventSink.onToken(partialResponse);
                    } catch (Exception e) {
                        LOGGER.warnf("Error sending token event: %s", e.getMessage());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    responseRef.set(completeResponse);
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });

            boolean timedOut = false;
            boolean interrupted = false;
            try {
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    LOGGER.warn("Streaming chat timed out");
                    timedOut = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Streaming chat was interrupted");
                interrupted = true;
            }

            if (timedOut || interrupted) {
                // Silence the still-running handler before reading what it produced, so the
                // text returned to memory is exactly the text the client was sent.
                abandoned.set(true);
            }

            responseText = fullResponse.toString();
            metadata.putAll(buildMetadata(responseRef.get()));

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
                metadata.put("streamingTimeout", true);
                if (!responseText.isEmpty()) {
                    // Partial response available — return it with a warning
                    metadata.put("warning", "streaming_timeout_partial");
                    LOGGER.warnf("Streaming timed out after %ds with partial response (%d chars)", timeoutSeconds, responseText.length());
                    return new StreamingResult(responseText, metadata);
                }
                // No response at all — treat as retryable failure
                if (attempt < maxAttempts) {
                    LOGGER.warnf("Streaming timed out with empty response, retrying (attempt %d/%d)", attempt, maxAttempts);
                    RetryConfiguration.backoff(attempt, retryConfig);
                    continue;
                }
                LOGGER.errorf("Streaming timed out with empty response after %d attempts", maxAttempts);
                return new StreamingResult("", metadata);
            }

            if (errorRef.get() != null) {
                if (salvagePartialOnError && !responseText.isEmpty()) {
                    // Error fired but we have partial content — return it with warning
                    metadata.put("warning", "streaming_error_partial");
                    metadata.put("errorMessage", errorRef.get().getMessage());
                    LOGGER.warnf("Streaming error with partial response (%d chars): %s", responseText.length(), errorRef.get().getMessage());
                    return new StreamingResult(responseText, metadata);
                }
                // Nothing salvageable (no content, or the caller wants errors
                // propagated) — retry if possible
                if (attempt < maxAttempts) {
                    LOGGER.warnf("Streaming error with empty response, retrying (attempt %d/%d): %s",
                            attempt, maxAttempts, errorRef.get().getMessage());
                    abandoned.set(true);
                    RetryConfiguration.backoff(attempt, retryConfig);
                    continue;
                }
                LOGGER.errorf("Streaming chat error after %d attempts: %s", maxAttempts, errorRef.get().getMessage());
                throw new RuntimeException("Streaming chat failed", errorRef.get());
            }

            // Success — break out of retry loop
            break;
        }

        return new StreamingResult(responseText, metadata);
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
                var usage = meta.tokenUsage();
                metadata.put("tokenUsage", Map.of("inputTokens", usage.inputTokenCount() != null ? usage.inputTokenCount() : 0, "outputTokens",
                        usage.outputTokenCount() != null ? usage.outputTokenCount() : 0, "totalTokens",
                        usage.totalTokenCount() != null ? usage.totalTokenCount() : 0));
            }
        }
        return metadata;
    }
}
