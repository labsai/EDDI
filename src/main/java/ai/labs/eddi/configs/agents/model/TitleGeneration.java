/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Model-written conversation titles — an opt-in per agent, off by default.
 * <p>
 * Every conversation gets a title from its first non-secret user message,
 * truncated. With this switched on, a model is asked once, after the first
 * exchange, for a short title in place of that one. The call runs in the
 * background, never delays the turn, and is charged to the tenant's cost budget
 * like any other model call (and refused, like them, when the budget is spent
 * or the model is unpriced under a strict pricing policy). It sees the first
 * exchange only as stored — secret input already replaced by its placeholder. A
 * title the conversation's owner set is never replaced, however late the model
 * answers; when the call fails the derived title simply stays.
 *
 * @since 6.7.0
 */
public class TitleGeneration {

    /** The default upper bound on a generated title, in characters. */
    public static final int DEFAULT_MAX_LENGTH = 60;

    private boolean enabled = false;
    private String llmProvider;
    private String llmModel;
    private Map<String, String> parameters = new HashMap<>();
    private Double inputPricePer1M;
    private Double outputPricePer1M;
    private int maxLength = DEFAULT_MAX_LENGTH;
    private String instructions;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * The provider of the title model, as a {@code langchain} task names it
     * ({@code openai}, {@code anthropic}, ...). Required when enabled.
     */
    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    /** The model; required when enabled. A small, cheap model is plenty. */
    public String getLlmModel() {
        return llmModel;
    }

    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    /**
     * Provider parameters — credentials and endpoint, e.g. {@code "apiKey":
     * "${vault:openai-key}"}. A background call has no task to inherit them from,
     * exactly as for Dream consolidation.
     */
    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    /**
     * USD per 1M input tokens; absent takes the Model Prices catalog's price.
     */
    public Double getInputPricePer1M() {
        return inputPricePer1M;
    }

    public void setInputPricePer1M(Double inputPricePer1M) {
        this.inputPricePer1M = inputPricePer1M;
    }

    /**
     * USD per 1M output tokens; absent takes the Model Prices catalog's price.
     */
    public Double getOutputPricePer1M() {
        return outputPricePer1M;
    }

    public void setOutputPricePer1M(Double outputPricePer1M) {
        this.outputPricePer1M = outputPricePer1M;
    }

    /** The longest title kept, in characters (10 to 200); longer ones are cut. */
    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * Replaces the standard instruction to the title model; absent uses it. The
     * reply is still normalized and cut to {@link #getMaxLength()}.
     */
    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
