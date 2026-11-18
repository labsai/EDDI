/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.impl.ToolLoopStreamingChatModel.PartiallyStreamedRoundException;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolContextBudget;
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
 * Executes a streaming chat completion without tools — tokens are emitted in
 * real time via the {@link ConversationEventSink} — for the no-tools streaming
 * path and for a cascade's live final step.
 * <p>
 * <strong>One semantics with every other mode.</strong> The model call runs
 * through {@link ToolLoopStreamingChatModel}, the same bridge the tool loop
 * uses, and — on the no-tools path — inside {@link LegacyChatExecutor}, the
 * same executor a non-streaming call uses. So this path retries exactly as the
 * others do ({@code RetryConfiguration.executeWithRetry}: three attempts by
 * default, only failures {@code LlmFailureClassifier} calls retryable,
 * honouring {@code Retry-After} and the turn deadline), and only while nothing
 * has reached the client; a stream counts as complete only with a finish
 * reason; a failure after text reached the client fails the turn. It used to
 * run its own loop, which made one attempt by default, retried every error
 * class, ignored the turn deadline, and stored a stream that broke off — or
 * ended in an error — as a normal answer.
 * <p>
 * This class blocks until the call ends so that the lifecycle workflow can
 * proceed synchronously while tokens stream to the client.
 */
class StreamingLegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(StreamingLegacyChatExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;
    private static final String KEY_TIMEOUT = "timeout";

    /** The {@code path} tag of this executor's stream metrics. */
    static final String PATH_LEGACY = "legacy";

    /** responseValidation actions that leave a streaming timeout to the caller. */
    private static final Set<String> PASSIVE_VALIDATION_ACTIONS = Set.of("warn", "ignore");

    /**
     * Optional — used to count providers that never emit partial responses (finding
     * F8). {@code null} in the direct-construction unit tests.
     */
    private final MeterRegistry meterRegistry;

    private final LegacyChatExecutor legacyChatExecutor = new LegacyChatExecutor();

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
     *            the full response text
     * @param metadata
     *            metadata about the streaming execution (finishReason, warnings,
     *            tokenUsage)
     */
    record StreamingResult(String response, Map<String, Object> metadata) {
    }

    /**
     * Result of a streaming execution — the full text plus response metadata
     * (finish reason, token usage) captured from the final response.
     */
    record StreamResult(String response, Map<String, Object> responseMetadata) {
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink, with
     * the default retry and no turn.
     *
     * @return the full response text (for memory storage)
     */
    String execute(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) throws LifecycleException {
        return execute(streamingModel, messages, eventSink, null, JsonResponseFormatPolicy.DISABLED).response();
    }

    /**
     * Backward-compatible overload without a JSON response-format policy — the
     * streamed request carries no response format.
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task)
            throws LifecycleException {
        return execute(streamingModel, messages, eventSink, task, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * As the full form, outside a conversation turn (no cancel flag, no deadline).
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException {
        return execute(streamingModel, messages, eventSink, task, jsonPolicy, null);
    }

    /**
     * Execute a streaming chat completion for the no-tools path: the task's retry
     * policy (default three attempts) over the streaming bridge, inside the turn's
     * deadline, polling the turn's cancel flag.
     *
     * @param task
     *            task configuration (timeouts, retry, streaming contract; may be
     *            null)
     * @param jsonPolicy
     *            decides whether the streamed request carries
     *            {@code ResponseFormat.JSON} — such a request is not forwarded
     *            token by token (partial JSON is not renderable) but emitted whole
     *            once it completed
     * @param memory
     *            the turn ({@code null} outside one): its cancel flag, its
     *            deadline, and the step that keeps a failed stream's partial text
     * @return the response text and metadata
     * @throws LifecycleException
     *             when the call failed after its retries, failed after text reached
     *             the client, ended without a finish reason, or was cancelled
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy,
                            IConversationMemory memory)
            throws LifecycleException {
        StreamPolicy policy = streamPolicy(task, memory);
        var bridge = new ToolLoopStreamingChatModel(streamingModel, eventSink, policy, modelType(task), PATH_LEGACY, meterRegistry);
        LegacyChatExecutor.ChatResult result;
        try {
            result = legacyChatExecutor.execute(bridge, messages, task != null ? task : new LlmConfiguration.Task(), jsonPolicy,
                    memory != null ? memory.getTurnDeadline() : null);
        } catch (LifecycleException e) {
            PartiallyStreamedRoundException partial = partialFailure(e);
            if (partial != null && keepsPartialText(policy, task, partial)) {
                return salvaged(partial);
            }
            throw e;
        }
        Map<String, Object> metadata = new HashMap<>(result.responseMetadata() != null ? result.responseMetadata() : Map.of());
        String text = answerText(result.response(), bridge);
        emitUnlessStreamed(bridge, text, metadata, eventSink, isJsonRequest(jsonPolicy));
        return new StreamingResult(text, metadata);
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink and
     * capturing the final response metadata (token usage). Used by the cascade to
     * stream the final step live without losing cost/token evidence.
     * <p>
     * Any failure is propagated, even when tokens were already emitted: the cascade
     * must see the failure to fall back to the best previous step.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {
        return executeCapturing(streamingModel, messages, eventSink, null, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * As
     * {@link #executeCapturing(StreamingChatModel, List, ConversationEventSink)},
     * but honouring the task's resolved streaming backstop (see
     * {@link #resolveTimeoutSeconds(LlmConfiguration.Task)}) and streaming
     * contract.
     * <p>
     * The task's retry config is deliberately <em>not</em> applied: the cascade
     * owns escalation, and retrying inside a step would multiply spend against the
     * very model the cascade is about to escalate away from. Each step gets one
     * attempt, and {@code onFailureAfterText: keepPartial} does not apply — a
     * failed final step is never accepted as a successful one.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink,
                                  LlmConfiguration.Task task, JsonResponseFormatPolicy jsonPolicy) {
        StreamPolicy policy = streamPolicy(task, null);
        var bridge = new ToolLoopStreamingChatModel(streamingModel, eventSink, policy, modelType(task), PATH_LEGACY, meterRegistry);
        ResponseFormat responseFormat = jsonPolicy != null ? jsonPolicy.resolve(false) : null;
        var requestBuilder = ChatRequest.builder().messages(messages);
        if (responseFormat != null) {
            requestBuilder.responseFormat(responseFormat);
        }
        ChatResponse response = bridge.chat(requestBuilder.build());
        String text = answerText(completeResponseText(response), bridge);
        Map<String, Object> metadata = buildMetadata(response);
        emitUnlessStreamed(bridge, text, metadata, eventSink, responseFormat != null);
        return new StreamResult(text, metadata);
    }

    /**
     * Backward-compatible overload without a JSON response-format policy.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink,
                                  LlmConfiguration.Task task) {
        return executeCapturing(streamingModel, messages, eventSink, task, JsonResponseFormatPolicy.DISABLED);
    }

    /**
     * The streaming contract for {@code task} inside {@code memory}'s turn — also
     * what the tool loop's streaming bridge is built with.
     */
    StreamPolicy streamPolicy(LlmConfiguration.Task task, IConversationMemory memory) {
        return StreamPolicy.forTask(task, backstopSeconds(task), memory);
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
     * A third, {@code streaming.idleTimeoutSeconds}, bounds the silence between two
     * stream events once the first arrived.
     * <p>
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
     * Emits the whole answer as one token when the call forwarded none of it: a
     * JSON-formatted request (never forwarded token by token) or a provider that
     * completes without partial responses (finding F8 — the answer used to be
     * dropped). Only the latter is a degradation worth a warning and a metric.
     */
    private void emitUnlessStreamed(ToolLoopStreamingChatModel bridge, String text, Map<String, Object> metadata,
                                    ConversationEventSink eventSink, boolean jsonRequest) {
        if (text.isEmpty() || !bridge.lastForwardedText().isEmpty()) {
            return;
        }
        if (!jsonRequest) {
            LOGGER.warnf("Streaming provider emitted no partial responses; falling back to the complete response (%d chars). "
                    + "This provider does not support incremental streaming.", text.length());
            metadata.put("streamingNoPartials", true);
            // putIfAbsent, NOT put: "truncated" (finishReason=LENGTH) or
            // "content_filter" are the signals LlmTask.applyResponseValidation
            // dispatches on, and the transport note is already carried by
            // streamingNoPartials.
            metadata.putIfAbsent("warning", "streaming_no_partials");
            incrementNoPartials();
        }
        try {
            eventSink.onToken(text);
        } catch (Exception e) {
            LOGGER.warnf("Error sending fallback token event: %s", e.getMessage());
        }
    }

    /**
     * The answer of a completed call: the complete response's text, or — for a
     * provider whose complete response carries none — exactly the text that was
     * streamed to the client.
     */
    private static String answerText(String completeText, ToolLoopStreamingChatModel bridge) {
        if (completeText != null && !completeText.isEmpty()) {
            return completeText;
        }
        return bridge.lastForwardedText();
    }

    private static boolean isJsonRequest(JsonResponseFormatPolicy jsonPolicy) {
        return jsonPolicy != null && jsonPolicy.resolve(false) != null;
    }

    /**
     * Whether a call that failed after forwarding text keeps that text as the
     * answer: when the task opted into it ({@code onFailureAfterText:
     * keepPartial}), or, for a timeout, when the task's responseValidation names an
     * active {@code onStreamingTimeout} action — that policy exists to act on
     * exactly this text.
     */
    private static boolean keepsPartialText(StreamPolicy policy, LlmConfiguration.Task task, PartiallyStreamedRoundException partial) {
        if (policy.keepPartialText()) {
            return true;
        }
        if (!partial.timedOut() || task == null || task.getResponseValidation() == null || !task.getResponseValidation().isEnabled()) {
            return false;
        }
        String action = task.getResponseValidation().getOnStreamingTimeout();
        return action != null && !PASSIVE_VALIDATION_ACTIONS.contains(action.toLowerCase());
    }

    /** The partial answer of a failed call, flagged as such. */
    private static StreamingResult salvaged(PartiallyStreamedRoundException partial) {
        Map<String, Object> metadata = new HashMap<>();
        if (partial.timedOut()) {
            metadata.put("streamingTimeout", true);
            metadata.put("warning", "streaming_timeout_partial");
        } else {
            metadata.put("warning", "streaming_error_partial");
        }
        LOGGER.warnf("Streaming call failed after %d characters; keeping them as the answer (%s)", partial.partialText().length(),
                metadata.get("warning"));
        return new StreamingResult(partial.partialText(), metadata);
    }

    /**
     * The partially-streamed failure inside {@code failure}'s cause chain, if any.
     */
    private static PartiallyStreamedRoundException partialFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof PartiallyStreamedRoundException partial) {
                return partial;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String modelType(LlmConfiguration.Task task) {
        return task != null && task.getType() != null ? task.getType() : "streaming";
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
                // Flag non-normal finish reasons for downstream validation, matching
                // LegacyChatExecutor. Without this, responseValidation.onTruncation and
                // onContentFilter are unreachable on the streaming path.
                ToolLoopStreamingChatModel.putFinishReason(metadata, meta.finishReason());
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
     * Explicitly bounded, with a size check and a clear rather than a Caffeine
     * cache: this is read on the per-request path, and cache machinery there
     * measurably slowed it. Clearing on overflow rather than refusing to add: a
     * hard cap would silently stop warning about genuinely new misconfigurations.
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
