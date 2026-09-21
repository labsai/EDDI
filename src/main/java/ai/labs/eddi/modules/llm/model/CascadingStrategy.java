/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.Arrays;

/**
 * How a multi-model cascade sequences its steps — the single source of truth
 * for the recognized {@code strategy} values.
 * <ul>
 * <li>{@link #CASCADE} — sequential escalation (the only implemented
 * mode).</li>
 * <li>{@link #PARALLEL} — reserved for a future parallel mode; currently runs
 * sequentially with a deploy-time warning.</li>
 * </ul>
 * The wire/config form stays a lenient {@code String} on
 * {@link LlmConfiguration.ModelCascadeConfig} for the same
 * forward-compatibility reasons as {@link EvaluationStrategy}; parse at the
 * boundary via {@link #fromConfig(String)}.
 */
public enum CascadingStrategy {

    CASCADE("cascade"), PARALLEL("parallel");

    /** Applied when the config value is null, blank, or unrecognized. */
    public static final CascadingStrategy DEFAULT = CASCADE;

    private final String configValue;

    CascadingStrategy(String configValue) {
        this.configValue = configValue;
    }

    /** The canonical lowercase token as it appears in JSON configs. */
    public String configValue() {
        return configValue;
    }

    /**
     * Resolve a config token (case-insensitive, trimmed) to a strategy, or
     * {@code null} when the value is null/blank/unrecognized.
     */
    public static CascadingStrategy fromConfig(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return Arrays.stream(values()).filter(s -> s.configValue.equalsIgnoreCase(v)).findFirst().orElse(null);
    }
}
