/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model a {@code convergence.judge: "SERVICE"} phase asks directly, instead
 * of running a full moderator-agent turn.
 * <p>
 * Shaped like {@code ContextWindowConfig} and {@code StanceSummaryConfig}: the
 * same provider/model pair and the same optional price fields feeding the
 * discussion's cost ledger, so a group author learns one shape for every piece
 * of discussion machinery that spends on the group's behalf. It adds
 * {@code parameters}, because unlike those two the judge must be able to
 * authenticate against a keyed provider on its own — and {@code timeoutMs},
 * because a convergence check is an optimization and must never hold a phase
 * for long.
 *
 * @param llmProvider
 *            provider type, as an LLM task names it (e.g. {@code "openai"}).
 *            Without it (or {@code llmModel}) the judge falls back to the
 *            moderator agent, exactly as {@code judge: SERVICE} did before this
 *            block existed
 * @param llmModel
 *            model name for the judge call
 * @param parameters
 *            extra model-builder parameters ({@code apiKey}, {@code baseUrl},
 *            {@code temperature}, ...). A credential-named key must hold a
 *            {@code ${vault:...}} or {@code ${vars:...}} reference — a literal
 *            is refused at save time, so a group config cannot become a
 *            plaintext key store. {@code timeout} is set from {@code timeoutMs}
 *            and any value here is overridden
 * @param inputPricePer1M
 *            optional USD price per 1M input tokens. Null or negative =
 *            unpriced ($0), the same semantics as the other group summarizers
 * @param outputPricePer1M
 *            optional USD price per 1M output tokens, same semantics
 * @param timeoutMs
 *            per-call timeout in milliseconds (default
 *            {@value #DEFAULT_TIMEOUT_MS}, ceiling {@value #MAX_TIMEOUT_MS}).
 *            Non-positive = default
 */
public record ServiceJudgeConfig(String llmProvider, String llmModel, Map<String, String> parameters, Double inputPricePer1M,
        Double outputPricePer1M, Integer timeoutMs) {

    public static final int DEFAULT_TIMEOUT_MS = 20_000;
    public static final int MAX_TIMEOUT_MS = 60_000;

    /**
     * Normalizes at the one choke point every reader passes through. Blank
     * identifiers are absent, negative prices are unpriced, the timeout is
     * defaulted and capped, and the parameter map is an immutable copy without null
     * keys or values.
     */
    public ServiceJudgeConfig {
        llmProvider = llmProvider == null || llmProvider.isBlank() ? null : llmProvider.trim();
        llmModel = llmModel == null || llmModel.isBlank() ? null : llmModel.trim();
        if (parameters == null || parameters.isEmpty()) {
            parameters = Map.of();
        } else {
            var copy = new LinkedHashMap<String, String>();
            parameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    copy.put(key, value);
                }
            });
            parameters = Map.copyOf(copy);
        }
        inputPricePer1M = inputPricePer1M == null || inputPricePer1M < 0 ? null : inputPricePer1M;
        outputPricePer1M = outputPricePer1M == null || outputPricePer1M < 0 ? null : outputPricePer1M;
        timeoutMs = timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
    }

    /**
     * Whether a model is named. Without one the judge falls back to the moderator
     * agent.
     */
    @JsonIgnore
    public boolean isConfigured() {
        return llmProvider != null && llmModel != null;
    }
}
