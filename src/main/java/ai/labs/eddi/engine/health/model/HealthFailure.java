/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.util.Map;

/**
 * One failed LLM attempt of a turn and what the engine did about it — recovered
 * or not. Read from the step's {@code health:llm_failures} record.
 *
 * @param failureClass
 *            a {@code FailureClass} name ({@code TRANSIENT}, {@code TIMEOUT},
 *            ...)
 * @param model
 *            the model the attempt ran on, or {@code null} when the recording
 *            point does not know it (a same-model retry, a cascade's exhausted
 *            last step)
 * @param recovery
 *            one of {@link #RETRY}, {@link #CASCADE}, {@link #FALLBACK},
 *            {@link #NONE}
 */
public record HealthFailure(String failureClass, String model, String recovery) {

    /** Another attempt on the same model followed. */
    public static final String RETRY = "retry";
    /**
     * The cascade moved on to its next step, or served an earlier step's answer.
     */
    public static final String CASCADE = "cascade";
    /** The task's {@code onError: fallback} answer was served instead. */
    public static final String FALLBACK = "fallback";
    /** Nothing recovered it: the failure ended the model phase. */
    public static final String NONE = "none";

    /** The keys of one entry of the step record. */
    public static final String KEY_CLASS = "class";
    public static final String KEY_MODEL = "model";
    public static final String KEY_RECOVERY = "recovery";

    /**
     * Whether the engine recovered from this failure. Anything but the three
     * recoveries — {@code none}, or a cascade entry the task never settled — is
     * not.
     */
    public boolean recovered() {
        return RETRY.equals(recovery) || CASCADE.equals(recovery) || FALLBACK.equals(recovery);
    }

    /**
     * An entry of the step record, or {@code null} when it is not one.
     */
    public static HealthFailure fromRecord(Object entry) {
        if (!(entry instanceof Map<?, ?> map)) {
            return null;
        }
        Object cls = map.get(KEY_CLASS);
        if (cls == null) {
            return null;
        }
        Object model = map.get(KEY_MODEL);
        Object recovery = map.get(KEY_RECOVERY);
        return new HealthFailure(String.valueOf(cls), model == null ? null : String.valueOf(model),
                recovery == null ? NONE : String.valueOf(recovery));
    }
}
