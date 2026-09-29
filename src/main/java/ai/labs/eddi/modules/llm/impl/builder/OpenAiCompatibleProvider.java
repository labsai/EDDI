/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import java.util.List;
import java.util.Map;

/**
 * One named provider that speaks the OpenAI chat-completions protocol (xAI,
 * DeepSeek, Moonshot, ...), as declared in
 * {@code llm/openai-compatible-providers.json}.
 * <p>
 * The catalog is the single source of truth for the provider's endpoint, its
 * default model and the parameter defaults EDDI applies so that the provider's
 * quirks (echoing reasoning back inside a tool loop, for instance) work without
 * the agent designer knowing about them.
 *
 * @param id
 *            the LLM task {@code type} value; lower-case
 * @param displayName
 *            what the Manager shows
 * @param defaultBaseUrl
 *            endpoint used when neither {@code baseUrl} nor {@code region} is
 *            configured
 * @param regions
 *            alternative endpoints selectable through the {@code region}
 *            parameter
 * @param defaultModel
 *            model used when no {@code modelName} is configured
 * @param modelSuggestions
 *            model ids offered in the Manager
 * @param apiKeyUrl
 *            where a user obtains an API key
 * @param apiKeyPlaceholder
 *            shape of a key, shown as the input placeholder
 * @param parameterDefaults
 *            parameters applied unless the configuration sets them
 * @param customParameters
 *            extra JSON body fields sent with every request
 * @param capabilities
 *            what the provider's API supports
 */
public record OpenAiCompatibleProvider(String id, String displayName, String defaultBaseUrl, List<Region> regions, String defaultModel,
        List<String> modelSuggestions, String apiKeyUrl, String apiKeyPlaceholder, Map<String, String> parameterDefaults,
        Map<String, Object> customParameters, Capabilities capabilities) {

    /** A named alternative endpoint, e.g. {@code cn} for China-mainland keys. */
    public record Region(String id, String baseUrl) {
    }

    /**
     * @param jsonMode
     *            the API accepts a request-level JSON response format
     * @param jsonModeWithTools
     *            the API accepts it in the same request as tool definitions
     * @param visionModelTokens
     *            substrings of a model id that identify image-capable models
     */
    public record Capabilities(boolean jsonMode, boolean jsonModeWithTools, List<String> visionModelTokens) {
        public Capabilities {
            visionModelTokens = visionModelTokens == null ? List.of() : List.copyOf(visionModelTokens);
        }
    }

    public OpenAiCompatibleProvider {
        regions = regions == null ? List.of() : List.copyOf(regions);
        modelSuggestions = modelSuggestions == null ? List.of() : List.copyOf(modelSuggestions);
        parameterDefaults = parameterDefaults == null ? Map.of() : Map.copyOf(parameterDefaults);
        customParameters = customParameters == null ? Map.of() : Map.copyOf(customParameters);
        capabilities = capabilities == null ? new Capabilities(false, false, List.of()) : capabilities;
    }

    /**
     * Resolve the endpoint for a region id.
     *
     * @param region
     *            a region id, or {@code null}/blank for the default endpoint
     * @throws IllegalArgumentException
     *             when the region is not one of this provider's regions
     */
    public String resolveBaseUrl(String region) {
        if (region == null || region.isBlank()) {
            return defaultBaseUrl;
        }
        String wanted = region.trim();
        for (Region candidate : regions) {
            if (candidate.id().equalsIgnoreCase(wanted)) {
                return candidate.baseUrl();
            }
        }
        throw new IllegalArgumentException(
                "Unknown region '" + wanted + "' for " + displayName + ". Valid regions: " + regions.stream().map(Region::id).toList());
    }
}
