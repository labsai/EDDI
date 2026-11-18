/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.StreamingConfig;
import org.jboss.logging.Logger;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

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
 * <li>{@link #cancelled} is polled while waiting, and a cancel stops the
 * provider's stream;</li>
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
 *            the turn's cancel flag, polled while waiting
 * @param partialTextSink
 *            receives the text of a call that failed after forwarding some
 * @param keepPartialText
 *            the no-tools path keeps a failed call's forwarded text as the
 *            answer (the opt-in {@code onFailureAfterText: keepPartial})
 */
record StreamPolicy(long backstopSeconds, long idleTimeoutSeconds, boolean requireFinishReason, BooleanSupplier cancelled,
        Consumer<String> partialTextSink, boolean keepPartialText) {

    private static final Logger LOGGER = Logger.getLogger(StreamPolicy.class);

    /** How often a waiting call looks at the cancel flag. */
    static final long POLL_MILLIS = 200L;

    private static final BooleanSupplier NEVER = () -> false;
    private static final Consumer<String> DISCARD = text -> {
    };

    StreamPolicy {
        cancelled = cancelled != null ? cancelled : NEVER;
        partialTextSink = partialTextSink != null ? partialTextSink : DISCARD;
        idleTimeoutSeconds = Math.max(0L, idleTimeoutSeconds);
    }

    /**
     * Only a wall-clock bound: the deployment defaults otherwise, no cancel flag.
     */
    static StreamPolicy ofBackstop(long backstopSeconds) {
        StreamingSettings settings = StreamingSettings.global();
        return new StreamPolicy(backstopSeconds, settings.idleTimeoutSeconds(), settings.requireFinishReason(), null, null, false);
    }

    /**
     * The policy of {@code task} inside {@code memory}'s turn ({@code memory} may
     * be {@code null}: no cancel flag, no place for error detail).
     */
    static StreamPolicy forTask(LlmConfiguration.Task task, long backstopSeconds, IConversationMemory memory) {
        StreamingSettings settings = StreamingSettings.global();
        StreamingConfig config = task != null ? task.getStreaming() : null;
        boolean requireFinishReason = config != null && config.getRequireFinishReason() != null
                ? config.getRequireFinishReason()
                : settings.requireFinishReason();
        long idle = config != null && config.getIdleTimeoutSeconds() != null
                ? config.getIdleTimeoutSeconds()
                : settings.idleTimeoutSeconds();
        boolean keepPartial = config != null && config.keepsPartialText();
        BooleanSupplier cancelled = memory != null ? memory::isCancelled : null;
        Consumer<String> sink = memory != null ? text -> recordPartialText(memory, task, text) : null;
        return new StreamPolicy(backstopSeconds, idle, requireFinishReason, cancelled, sink, keepPartial);
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
