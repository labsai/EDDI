package ai.labs.eddi.modules.llm.impl;

import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * The one structured INFO line every LLM recovery action leaves (R11): a repair
 * of the model's reply, a same-model re-ask, an escalation to the next cascade
 * step, a skipped (open-circuit) model, a served fallback.
 * <p>
 * One recovery, one line — the call site that performs the action emits it, and
 * nothing else logs the same event at INFO. The failures that triggered a
 * recovery keep their own WARN/ERROR lines; this line is the answer to "what
 * did the engine do about it", greppable by {@code LLM recovery} and keyed
 * {@code conversationId / agentId / class / action / outcome / attempt /
 * durationMs} so an operator can follow one conversation or count one action.
 * <p>
 * The values are engine-generated identifiers and labels, never model output or
 * user input; they are stripped of whitespace and control characters anyway so
 * a crafted id cannot split the line.
 */
final class LlmRecoveryLog {

    private static final Logger LOGGER = Logger.getLogger(LlmRecoveryLog.class);

    /** The actions, as they appear in {@code action=} and in the metric. */
    static final String REPAIR = "repair";
    static final String RETRY = "retry";
    static final String ESCALATE = "escalate";
    static final String CIRCUIT_SKIP = "circuit_skip";
    static final String FALLBACK = "fallback";

    /** Test hook: receives every line as well. Null in production. */
    static volatile Consumer<String> observer;

    private LlmRecoveryLog() {
    }

    /**
     * @param failureClass
     *            what was being recovered from: a failure class ({@code TIMEOUT}),
     *            a reply problem ({@code invalid_json}) or an escalation reason
     * @param action
     *            one of the constants above
     * @param outcome
     *            how it ended ({@code recovered}, {@code still_invalid},
     *            {@code failed}, {@code served}, {@code next_step},
     *            {@code skipped}, ...)
     * @param attempt
     *            the 1-based attempt of this action (the re-ask number, the cascade
     *            step the engine moved on from, 1 for one-shot actions)
     * @param durationMs
     *            how long the action took
     */
    static void log(String conversationId, String agentId, String failureClass, String action, String outcome, int attempt, long durationMs) {
        String line = format(conversationId, agentId, failureClass, action, outcome, attempt, durationMs);
        LOGGER.info(line);
        Consumer<String> sink = observer;
        if (sink != null) {
            sink.accept(line);
        }
    }

    static String format(String conversationId, String agentId, String failureClass, String action, String outcome, int attempt, long durationMs) {
        return "LLM recovery conversationId=" + clean(conversationId) + " agentId=" + clean(agentId) + " class=" + clean(failureClass) + " action="
                + clean(action) + " outcome=" + clean(outcome) + " attempt=" + attempt + " durationMs=" + durationMs;
    }

    /**
     * A re-ask listener that forwards the start of each re-ask to {@code delegate}
     * and logs its outcome — the single place the "retry" line is written.
     */
    static FormatRetryRunner.ReaskListener reaskListener(String conversationId, String agentId, FormatRetryRunner.ReaskListener delegate) {
        return new FormatRetryRunner.ReaskListener() {
            @Override
            public void onReask(FormatRetryRunner.Trigger trigger, int attempt) {
                if (delegate != null) {
                    delegate.onReask(trigger, attempt);
                }
            }

            @Override
            public void onReaskDone(FormatRetryRunner.Trigger trigger, int attempt, String outcome, long durationMs) {
                log(conversationId, agentId, trigger.label(), RETRY, outcome, attempt, durationMs);
            }
        };
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) || Character.isWhitespace(c) ? '_' : c);
        }
        return out.toString();
    }
}
