/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.Arrays;

/**
 * Confidence-evaluation strategies for a multi-model cascade step — the single
 * source of truth for the recognized {@code evaluationStrategy} values used
 * throughout the engine and the config validator.
 * <p>
 * The wire/config form deliberately stays a lenient {@code String} on
 * {@link LlmConfiguration.ModelCascadeConfig}: an unrecognized (typo'd, or from
 * a newer engine) value degrades gracefully — the validator warns and the
 * runtime falls back to {@link #DEFAULT} — and the original value round-trips
 * unchanged through export/import. Parsing to this enum happens at the boundary
 * via {@link #fromConfig(String)} / {@link #fromConfigOrDefault(String)}.
 */
public enum EvaluationStrategy {

    STRUCTURED_OUTPUT("structured_output"), HEURISTIC("heuristic"), JUDGE_MODEL("judge_model"), NONE("none");

    /** Applied when the config value is null, blank, or unrecognized. */
    public static final EvaluationStrategy DEFAULT = STRUCTURED_OUTPUT;

    private final String configValue;

    EvaluationStrategy(String configValue) {
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
    public static EvaluationStrategy fromConfig(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return Arrays.stream(values()).filter(s -> s.configValue.equalsIgnoreCase(v)).findFirst().orElse(null);
    }

    /**
     * Like {@link #fromConfig(String)} but falls back to {@link #DEFAULT} for
     * unrecognized values.
     */
    public static EvaluationStrategy fromConfigOrDefault(String value) {
        EvaluationStrategy strategy = fromConfig(value);
        return strategy != null ? strategy : DEFAULT;
    }
}
