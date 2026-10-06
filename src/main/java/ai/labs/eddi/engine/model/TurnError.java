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
 *
 * @param code
 *            a {@link FailureClass} name or a generic code
 * @param retryable
 *            whether the identical request is worth repeating
 * @param retryAfterMs
 *            how long to wait first, or {@code null} when unknown
 * @param message
 *            a short, safe description
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TurnError(String code, boolean retryable, Long retryAfterMs, String message) {

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

    /**
     * Reads a {@code taskErrors} entry back; {@code null} when it predates these
     * fields (or is not a map), so the caller can fall back to a generic error.
     */
    public static TurnError fromTaskError(Object entry) {
        if (!(entry instanceof Map<?, ?> map) || !(map.get(KEY_CODE) instanceof String code)) {
            return null;
        }
        Object retryAfter = map.get(KEY_RETRY_AFTER_MS);
        Object message = map.get(KEY_MESSAGE);
        return new TurnError(code, Boolean.TRUE.equals(map.get(KEY_RETRYABLE)),
                retryAfter instanceof Number n ? n.longValue() : null,
                message instanceof String s ? s : null);
    }

    /** {@code Retry-After} value in whole seconds (rounded up), or {@code null}. */
    public Long retryAfterSeconds() {
        return retryAfterMs == null || retryAfterMs < 0 ? null : Math.max(1L, (retryAfterMs + 999) / 1000);
    }
}
