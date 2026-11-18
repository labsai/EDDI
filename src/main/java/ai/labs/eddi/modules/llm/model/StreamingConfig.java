/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

/**
 * The streaming contract of one LLM task: when a streamed model call counts as
 * complete, how long it may stay silent, and what a failure after text has
 * reached the client does. Every field is optional; an absent field takes the
 * deployment default.
 * <p>
 * The contract itself is the same on every streaming path (the plain path, the
 * tool loop and a cascade's live final step): a stream succeeds only when the
 * provider completes it with a finish reason and reports no error, the first
 * terminal callback ends the call, and a failure before the first forwarded
 * token is retried like a non-streaming call.
 *
 * @since 6.7.0
 */
public class StreamingConfig {

    /** {@link #onFailureAfterText} value: the turn fails (the default). */
    public static final String ON_FAILURE_AFTER_TEXT_FAIL = "fail";

    /**
     * {@link #onFailureAfterText} value: the text forwarded before the failure is
     * kept as the answer, flagged {@code streaming_error_partial} — the behaviour
     * of the no-tools streaming path before 6.7.
     */
    public static final String ON_FAILURE_AFTER_TEXT_KEEP_PARTIAL = "keepPartial";

    /**
     * Whether a stream that ends without a finish reason is a failure. {@code null}
     * takes {@code eddi.llm.streaming.require-finish-reason} (default
     * {@code true}). Set {@code false} only for a server that never sends one (some
     * OpenAI-compatible servers, Ollama before {@code done_reason}): a connection
     * that simply closes mid-answer is then indistinguishable from a finished one.
     */
    private Boolean requireFinishReason;

    /**
     * Longest silence, in seconds, between two stream events once the first one
     * arrived. {@code null} takes {@code eddi.llm.streaming.idle-timeout-seconds}
     * (default 30); {@code 0} turns it off. The time to the first token is not
     * covered — a reasoning model may think for minutes before it — that is what
     * the {@code timeout} model parameter and {@code streamingTimeoutSeconds} are
     * for.
     */
    private Integer idleTimeoutSeconds;

    /**
     * What happens when the stream fails after text already reached the client.
     * {@value #ON_FAILURE_AFTER_TEXT_FAIL} (the default, also for {@code null})
     * fails the turn: the partial text is kept as error detail, never stored as an
     * answer later turns would replay. {@value #ON_FAILURE_AFTER_TEXT_KEEP_PARTIAL}
     * keeps the partial text as the answer with a {@code streaming_error_partial}
     * warning. Applies to the no-tools streaming path only: the tool loop always
     * fails such a round.
     */
    private String onFailureAfterText;

    public Boolean getRequireFinishReason() {
        return requireFinishReason;
    }

    public void setRequireFinishReason(Boolean requireFinishReason) {
        this.requireFinishReason = requireFinishReason;
    }

    public Integer getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(Integer idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public String getOnFailureAfterText() {
        return onFailureAfterText;
    }

    public void setOnFailureAfterText(String onFailureAfterText) {
        this.onFailureAfterText = onFailureAfterText;
    }

    /** Whether {@link #onFailureAfterText} asks to keep the partial text. */
    public boolean keepsPartialText() {
        return ON_FAILURE_AFTER_TEXT_KEEP_PARTIAL.equalsIgnoreCase(onFailureAfterText);
    }
}
