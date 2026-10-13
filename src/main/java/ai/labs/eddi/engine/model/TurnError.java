/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailure;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The machine-readable reason a turn failed, as a caller sees it under the
 * {@code error} key of the response.
 * <p>
 * {@code code} is a {@link FailureClass} name when the failure was recognised
 * as a provider/transport failure, and a generic code otherwise
 * ({@value #TURN_FAILED}, {@value #INTERNAL_ERROR}). {@code retryable} says
 * whether repeating the same request can succeed; {@code retryAfterMs} is the
 * provider's own hint and is {@code null} (and the HTTP {@code Retry-After}
 * header absent) when none was given. {@code message} is the redacted,
 * length-bounded digest — never a stack trace and never a raw provider body.
 * <p>
 * The same object is the {@code error} of every snapshot in the {@code ERROR}
 * state (a plain read as well as a say): {@code taskId}, {@code taskType} and
 * {@code errorType} say which lifecycle task failed and how it was classified
 * for metrics and audit, exactly as the streaming {@code task_failed} event
 * does. They are omitted from the JSON when unknown (an error that never became
 * a task failure, such as a rejected idempotency key).
 *
 * @param code
 *            a {@link FailureClass} name or a generic code
 * @param retryable
 *            whether the identical request is worth repeating
 * @param retryAfterMs
 *            how long to wait first, or {@code null} when unknown
 * @param message
 *            a short, safe description: URLs, stack frames and credentials are
 *            stripped before it leaves the server
 * @param taskId
 *            the failing lifecycle task, e.g. {@code eddi://ai.labs.llm}, or
 *            {@code null}
 * @param taskType
 *            its type, e.g. {@code langchain}, or {@code null}
 * @param errorType
 *            the failure class used for metrics and audit (e.g.
 *            {@code LifecycleException}), or {@code null}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TurnError(String code, boolean retryable, Long retryAfterMs, String message, @JsonInclude(JsonInclude.Include.NON_NULL) String taskId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String taskType, @JsonInclude(JsonInclude.Include.NON_NULL) String errorType) {

    /** An error that is not tied to a lifecycle task. */
    public TurnError(String code, boolean retryable, Long retryAfterMs, String message) {
        this(code, retryable, retryAfterMs, message, null, null, null);
    }

    /** A task failed and nothing more specific is known. */
    public static final String TURN_FAILED = "TURN_FAILED";
    /** The request could not be processed because of a server-side fault. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** The keys a failed task's {@code taskErrors} entry carries. */
    public static final String KEY_CODE = "code";
    public static final String KEY_RETRYABLE = "retryable";
    public static final String KEY_RETRY_AFTER_MS = "retryAfterMs";
    public static final String KEY_MESSAGE = "message";

    /**
     * Builds the error for a classified failure. An {@link FailureClass#UNKNOWN}
     * verdict is not worth a class name of its own: the classifier found nothing
     * recognisable, so the generic {@value #TURN_FAILED} says exactly that.
     */
    public static TurnError of(LlmFailure failure, String safeMessage) {
        if (failure == null || failure.cls() == FailureClass.UNKNOWN) {
            return new TurnError(TURN_FAILED, false, null, safeMessage);
        }
        return new TurnError(failure.cls().name(), failure.isRetryable(), failure.retryAfterMs(), safeMessage);
    }

    /** A server-side fault that never became a turn. */
    public static TurnError internal(String safeMessage) {
        return new TurnError(INTERNAL_ERROR, false, null, safeMessage);
    }

    /** The keys of a failed task's {@code taskErrors} entry that name the task. */
    public static final String KEY_TASK_ID = "taskId";
    public static final String KEY_TASK_TYPE = "taskType";
    public static final String KEY_ERROR_TYPE = "errorType";
    /** The entry's full digest text ({@code Task 'x' failed: ...}). */
    public static final String KEY_TEXT = "text";

    /**
     * Reads a {@code taskErrors} entry back into the caller-facing error;
     * {@code null} when it is not a map. An entry that predates the
     * machine-readable keys becomes {@value #TURN_FAILED}, not retryable. The
     * message is the entry's digest text (the sentence the streaming
     * {@code task_failed} event carries), falling back to its bare message.
     */
    public static TurnError fromTaskError(Object entry) {
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        Object retryAfter = map.get(KEY_RETRY_AFTER_MS);
        String code = map.get(KEY_CODE) instanceof String c ? c : TURN_FAILED;
        String message = firstText(map.get(KEY_TEXT), map.get(KEY_MESSAGE));
        return new TurnError(code, Boolean.TRUE.equals(map.get(KEY_RETRYABLE)), retryAfter instanceof Number n ? n.longValue() : null, message,
                text(map.get(KEY_TASK_ID)), text(map.get(KEY_TASK_TYPE)), text(map.get(KEY_ERROR_TYPE)));
    }

    private static String firstText(Object first, Object second) {
        String value = text(first);
        return value != null ? value : text(second);
    }

    private static String text(Object value) {
        return value != null ? value.toString() : null;
    }

    /** {@code Retry-After} value in whole seconds (rounded up), or {@code null}. */
    public Long retryAfterSeconds() {
        return retryAfterMs == null || retryAfterMs < 0 ? null : Math.max(1L, (retryAfterMs + 999) / 1000);
    }
}
