/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.StreamingConfig;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * How one streamed model call is awaited and judged — the single streaming
 * contract every path applies (the no-tools path, the tool loop's streaming
 * bridge and a cascade's live final step):
 * <ol>
 * <li>success only on {@code onCompleteResponse} with a finish reason (unless
 * {@link #requireFinishReason} is off) and no error;</li>
 * <li>the first terminal callback ends the call, nothing is forwarded after
 * it;</li>
 * <li>{@link #backstopSeconds} bounds the whole call,
 * {@link #idleTimeoutSeconds} the silence between two events once the first one
 * arrived;</li>
 * <li>a cancel of the turn wakes the waiting call at once
 * ({@link #cancelSubscription}) and stops the provider's stream;
 * {@link #cancelled} is also read once a second, for the cluster lease;</li>
 * <li>a failure after text was forwarded reports that text to
 * {@link #partialTextSink} as error detail.</li>
 * </ol>
 *
 * @param backstopSeconds
 *            wall-clock bound of one call
 * @param idleTimeoutSeconds
 *            longest silence after the first event; {@code 0} = off
 * @param requireFinishReason
 *            whether a completion without a finish reason is a failure
 * @param cancelled
 *            the turn's cancel flag
 * @param partialTextSink
 *            receives the text of a call that failed after forwarding some
 * @param keepPartialText
 *            the no-tools path keeps a failed call's forwarded text as the
 *            answer (the opt-in {@code onFailureAfterText: keepPartial})
 * @param cancelSubscription
 *            registers a listener the turn's cancel calls at once; the returned
 *            handle unregisters it ({@code null}: none)
 * @param endpoint
 *            names the model endpoint in the one warning a lenient
 *            finish-reason check writes
 */
record StreamPolicy(long backstopSeconds, long idleTimeoutSeconds, boolean requireFinishReason, BooleanSupplier cancelled,
        Consumer<String> partialTextSink, boolean keepPartialText, Function<Runnable, AutoCloseable> cancelSubscription, String endpoint) {

    private static final Logger LOGGER = Logger.getLogger(StreamPolicy.class);

    /**
     * How often a waiting call reads {@link #cancelled} without being woken: only
     * the cluster lease, which nothing signals, needs it.
     */
    static final long POLL_MILLIS = 1_000L;

    /**
     * Providers whose langchain4j streaming model always reports a finish reason on
     * a stream that really completed (checked against langchain4j 1.23): for them,
     * a completion without one is a connection that broke off. Every other type —
     * Ollama (older servers send no {@code done_reason}), the OpenAI-compatible
     * endpoint and catalog presets, Hugging Face, OCI — and any task with its own
     * {@code baseUrl} (a gateway or self-hosted server) is lenient by default.
     */
    static final Set<String> STRICT_FINISH_REASON_TYPES = Set.of(LlmModule.LLM_TYPE_OPENAI, LlmModule.LLM_TYPE_AZURE_OPENAI,
            LlmModule.LLM_TYPE_ANTHROPIC, LlmModule.LLM_TYPE_BEDROCK, LlmModule.LLM_TYPE_GEMINI, LlmModule.LLM_TYPE_GEMINI_VERTEX,
            LlmModule.LLM_TYPE_MISTRAL, LlmModule.LLM_TYPE_JLAMA);

    private static final BooleanSupplier NEVER = () -> false;
    private static final Consumer<String> DISCARD = text -> {
    };

    StreamPolicy {
        cancelled = cancelled != null ? cancelled : NEVER;
        partialTextSink = partialTextSink != null ? partialTextSink : DISCARD;
        idleTimeoutSeconds = Math.max(0L, idleTimeoutSeconds);
        endpoint = endpoint != null ? endpoint : "unknown";
    }

    /** Without a cancel subscription (tests, callers outside a turn). */
    StreamPolicy(long backstopSeconds, long idleTimeoutSeconds, boolean requireFinishReason, BooleanSupplier cancelled,
            Consumer<String> partialTextSink, boolean keepPartialText) {
        this(backstopSeconds, idleTimeoutSeconds, requireFinishReason, cancelled, partialTextSink, keepPartialText, null, null);
    }

    /**
     * Only a wall-clock bound: the deployment defaults otherwise (strict when the
     * deployment sets nothing), no cancel flag.
     */
    static StreamPolicy ofBackstop(long backstopSeconds) {
        StreamingSettings settings = StreamingSettings.global();
        boolean strict = settings.requireFinishReason() == null || settings.requireFinishReason();
        return new StreamPolicy(backstopSeconds, settings.idleTimeoutSeconds(), strict, null, null, false);
    }

    /**
     * The policy of {@code task} inside {@code memory}'s turn ({@code memory} may
     * be {@code null}: no cancel flag, no place for error detail).
     */
    static StreamPolicy forTask(LlmConfiguration.Task task, long backstopSeconds, IConversationMemory memory) {
        StreamingSettings settings = StreamingSettings.global();
        StreamingConfig config = task != null ? task.getStreaming() : null;
        boolean requireFinishReason = requireFinishReason(task, config, settings);
        long idle = config != null && config.getIdleTimeoutSeconds() != null
                ? config.getIdleTimeoutSeconds()
                : settings.idleTimeoutSeconds();
        boolean keepPartial = config != null && config.keepsPartialText();
        BooleanSupplier cancelled = memory != null ? memory::isCancelled : null;
        Consumer<String> sink = memory != null ? text -> recordPartialText(memory, task, text) : null;
        Function<Runnable, AutoCloseable> subscription = memory != null ? memory::onCancel : null;
        return new StreamPolicy(backstopSeconds, idle, requireFinishReason, cancelled, sink, keepPartial, subscription, endpointOf(task));
    }

    /**
     * The task's setting, else the deployment's, else the provider's default:
     * strict for {@link #STRICT_FINISH_REASON_TYPES} without a custom
     * {@code baseUrl}, lenient otherwise. An explicit setting wins in both
     * directions.
     */
    static boolean requireFinishReason(LlmConfiguration.Task task, StreamingConfig config, StreamingSettings settings) {
        if (config != null && config.getRequireFinishReason() != null) {
            return config.getRequireFinishReason();
        }
        if (settings.requireFinishReason() != null) {
            return settings.requireFinishReason();
        }
        return providerAlwaysReportsFinishReason(task);
    }

    static boolean providerAlwaysReportsFinishReason(LlmConfiguration.Task task) {
        if (task == null || task.getType() == null) {
            return true;
        }
        Map<String, String> parameters = task.getParameters();
        String baseUrl = parameters != null ? parameters.get("baseUrl") : null;
        if (baseUrl != null && !baseUrl.isBlank()) {
            return false;
        }
        return STRICT_FINISH_REASON_TYPES.contains(task.getType().trim().toLowerCase(Locale.ROOT));
    }

    static String endpointOf(LlmConfiguration.Task task) {
        if (task == null) {
            return null;
        }
        Map<String, String> parameters = task.getParameters();
        String baseUrl = parameters != null ? parameters.get("baseUrl") : null;
        String model = parameters != null ? parameters.getOrDefault("modelName", parameters.get("model")) : null;
        String host = baseUrl != null && !baseUrl.isBlank() ? hostOf(baseUrl) : null;
        return task.getType() + (host != null ? "@" + host : "") + (model != null ? "/" + model : "");
    }

    /**
     * Scheme, host and port of {@code baseUrl} only — it goes into a log line, and
     * a base URL may carry credentials in its user part or query.
     */
    static String hostOf(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            if (uri.getHost() == null) {
                return "custom";
            }
            return (uri.getScheme() != null ? uri.getScheme() + "://" : "") + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (RuntimeException e) {
            return "custom";
        }
    }

    /**
     * Keeps the text a failed stream had already sent to the client on the step, as
     * error detail ({@code llm:partial_output:<taskId>}): visible in the detailed
     * conversation, never in the outputs the history builder replays.
     */
    private static void recordPartialText(IConversationMemory memory, LlmConfiguration.Task task, String text) {
        try {
            var step = memory.getCurrentStep();
            if (step == null || text == null || text.isEmpty()) {
                return;
            }
            String taskId = task != null && task.getId() != null ? task.getId() : "default";
            step.storeData(new Data<>(MemoryKeys.LLM_PARTIAL_OUTPUT_PREFIX + taskId, text));
        } catch (RuntimeException e) {
            LOGGER.debugf("Could not record the partial output of a failed stream in conversation %s: %s",
                    sanitize(memory.getConversationId()), e.getMessage());
        }
    }
}
