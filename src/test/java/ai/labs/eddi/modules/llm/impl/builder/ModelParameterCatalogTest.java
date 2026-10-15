/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog.Effect;
import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog.Finding;
import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog.Param;
import ai.labs.eddi.modules.llm.impl.builder.ModelParameterCatalog.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("ModelParameterCatalog")
class ModelParameterCatalogTest {

    private static final ModelParameterCatalog CATALOG = ModelParameterCatalog.defaultCatalog();

    /**
     * Every model id EDDI knows of, per provider type: the OpenAI-compatible
     * catalog's suggestions plus the {@code currentModels} (and still-callable
     * deprecated ids) the parameter research collected from each vendor's model
     * list. Each catalog rule must match at least one of them, and the expectation
     * table below exercises the near-misses.
     */
    static final Map<String, List<String>> KNOWN_MODELS = new LinkedHashMap<>();

    static {
        KNOWN_MODELS.put("openai", List.of("gpt-6-astra", "gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra",
                "gpt-5.6-luna", "gpt-5.6-cyber", "gpt-5.5", "gpt-5.5-pro", "gpt-5.4", "gpt-5.4-pro", "gpt-5.4-mini", "gpt-5.2",
                "gpt-5.2-pro", "gpt-5", "gpt-5-mini", "gpt-5-nano", "gpt-5-pro", "o3", "o3-pro", "gpt-4.1", "gpt-4.1-mini", "gpt-4o",
                "gpt-4o-mini", "gpt-oss-120b", "gpt-oss-20b", "chat-latest", "gpt-5.4-nano", "gpt-5.3-codex", "gpt-5.2-codex",
                "gpt-5.1", "gpt-5.1-chat-latest", "gpt-5.1-codex", "gpt-5.1-codex-max", "gpt-5.1-codex-mini", "gpt-5-codex",
                "gpt-5-chat-latest", "o4-mini", "o3-mini", "o1", "o1-pro", "o1-mini", "gpt-4-turbo", "gpt-3.5-turbo",
                "gpt-5-2025-08-07"));
        KNOWN_MODELS.put("azure-openai", List.of("gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-6.1-sol", "gpt-5.6-sol",
                "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.4-pro", "gpt-5.3-codex",
                "gpt-5.2", "gpt-5.2-codex", "gpt-5.1", "gpt-5.1-chat", "gpt-5.1-codex", "gpt-5.1-codex-max", "gpt-5.1-codex-mini",
                "gpt-5-pro", "gpt-5-codex", "gpt-5", "gpt-5-mini", "gpt-5-nano", "codex-mini", "o3-pro", "o4-mini", "o3", "o3-mini",
                "o1", "gpt-4o", "gpt-4.1"));
        KNOWN_MODELS.put("anthropic", List.of("claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5",
                "claude-fable-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-7", "claude-opus-4-6", "claude-opus-4-5",
                "claude-opus-4-5-20251101", "claude-sonnet-5", "claude-sonnet-4-6", "claude-haiku-4-5", "claude-haiku-4-5-20251001",
                "claude-mythos-5-1", "claude-mythos-5", "claude-mythos-preview", "claude-sonnet-4-5", "claude-opus-4-1",
                "claude-sonnet-4-20250514"));
        KNOWN_MODELS.put("gemini", List.of("gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-3.5-flash-lite",
                "gemini-3.1-flash-lite", "gemini-3.1-pro-preview", "gemini-3-flash-preview", "gemini-2.5-pro", "gemini-2.5-flash",
                "gemini-2.5-flash-lite", "gemini-3.1-flash-image", "gemini-3-pro-image", "models/gemini-3.8-flash"));
        KNOWN_MODELS.put("gemini-vertex", List.of("gemini-4-argon", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-2.5-pro"));
        KNOWN_MODELS.put("bedrock", List.of("anthropic.claude-opus-5-5", "anthropic.claude-sonnet-5-5", "anthropic.claude-haiku-5-5",
                "anthropic.claude-fable-5-1", "anthropic.claude-fable-5", "anthropic.claude-opus-5", "anthropic.claude-sonnet-5",
                "anthropic.claude-opus-4-8", "anthropic.claude-opus-4-7", "anthropic.claude-opus-4-6-v1", "anthropic.claude-sonnet-4-6",
                "anthropic.claude-haiku-4-5-20251001-v1:0", "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                "global.anthropic.claude-sonnet-4-20250514-v1:0", "amazon.nova-2-lite-v1:0", "amazon.nova-pro-v1:0",
                "meta.llama4-maverick-17b-instruct-v1:0"));
        for (OpenAiCompatibleProvider provider : OpenAiCompatibleProviders.all()) {
            KNOWN_MODELS.put(provider.id(), new ArrayList<>(provider.modelSuggestions()));
        }
        KNOWN_MODELS.get("xai").addAll(List.of("grok-4.20-multi-agent-0309"));
        KNOWN_MODELS.get("deepseek").addAll(List.of("deepseek-v4-flash"));
        KNOWN_MODELS.get("zhipu").addAll(List.of("glm-5.1", "glm-5", "glm-4.7", "glm-4.6"));
        KNOWN_MODELS.get("minimax").addAll(List.of("MiniMax-M2.5", "MiniMax-M2.5-highspeed", "MiniMax-M2.1", "MiniMax-M2"));
        KNOWN_MODELS.get("openrouter").addAll(List.of("openai/gpt-5.5", "anthropic/claude-sonnet-5-5", "google/gemini-3.8-flash"));
        KNOWN_MODELS.get("groq").addAll(List.of("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-safeguard-20b",
                "minimaxai/minimax-m2.7"));
    }

    private static Map<String, String> params(String type, String model, String key, String context) {
        Map<String, String> p = new HashMap<>();
        if (model != null) {
            p.put("azure-openai".equals(type) ? "deploymentName" : "ollama".equals(type) ? "model" : "modelName", model);
        }
        p.put(key, "0.5");
        if (!context.isEmpty()) {
            for (String pair : context.split(",")) {
                String[] kv = pair.split("=", 2);
                p.put(kv[0], kv[1]);
            }
        }
        return p;
    }

    /**
     * The effect of the one finding for {@code key}, or {@code null} when it
     * applies.
     */
    private static Effect effectFor(String type, Map<String, String> params, String key) {
        return CATALOG.adapt(type, params, true).findings().stream().filter(f -> f.key().equals(key)).map(Finding::effect).findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("the shipped catalog")
    class Shipped {

        @Test
        @DisplayName("describes every provider type EDDI ships")
        void everyType() {
            Set<String> expected = new TreeSet<>(Set.of("openai", "anthropic", "gemini", "gemini-vertex", "azure-openai", "mistral",
                    "ollama", "bedrock", "huggingface", "jlama", "oracle-genai"));
            OpenAiCompatibleProviders.all().forEach(p -> expected.add(p.id()));
            assertEquals(expected, new TreeSet<>(CATALOG.types()));
        }

        @Test
        @DisplayName("is loaded once")
        void singleton() {
            assertSame(CATALOG, ModelParameterCatalog.defaultCatalog());
        }

        @Test
        @DisplayName("every rule's regex matches at least one known model id of its provider")
        void noDeadRegex() {
            List<String> dead = new ArrayList<>();
            for (String type : CATALOG.types()) {
                List<String> models = KNOWN_MODELS.getOrDefault(type, List.of());
                for (Param param : CATALOG.params(type)) {
                    for (Rule rule : param.rules()) {
                        if (models.stream().noneMatch(m -> rule.models().matcher(m).find())) {
                            dead.add(type + "." + param.key() + " " + rule.models().pattern());
                        }
                    }
                }
            }
            assertTrue(dead.isEmpty(), "Rules that match no known model id: " + dead);
        }

        @Test
        @DisplayName("every body parameter is an OpenAI-compatible provider's, and has a wire path")
        void bodyOnlyOnCompatible() {
            for (String type : CATALOG.types()) {
                for (Param param : CATALOG.params(type)) {
                    if (param.body() || param.header() != null) {
                        assertTrue(OpenAiCompatibleProviders.find(type).isPresent(), type + "." + param.key());
                    }
                    if (param.body()) {
                        assertNotNull(param.wire(), type + "." + param.key());
                    }
                }
            }
        }

        @Test
        @DisplayName("an OpenAI-compatible provider inherits every OpenAI setting, without OpenAI's rules")
        void inherits() {
            assertTrue(CATALOG.keys("xai").containsAll(CATALOG.keys("openai")));
            assertTrue(CATALOG.find("xai", "temperature").orElseThrow().rules().isEmpty());
            assertFalse(CATALOG.find("openai", "temperature").orElseThrow().rules().isEmpty());
            // an own entry replaces the inherited one
            assertEquals("thinking.type", CATALOG.find("deepseek", "thinkingType").orElseThrow().wire());
            assertTrue(CATALOG.find("openrouter", "reasoningEffort").orElseThrow().body());
            assertFalse(CATALOG.find("openai", "reasoningEffort").orElseThrow().body());
        }

        @Test
        @DisplayName("lookups are case- and whitespace-insensitive and empty for an unknown type")
        void lookups() {
            assertTrue(CATALOG.find(" OpenAI ", "temperature").isPresent());
            assertTrue(CATALOG.find("openai", "nope").isEmpty());
            assertTrue(CATALOG.keys("nope").isEmpty());
            assertTrue(CATALOG.params("nope").isEmpty());
            assertEquals("Anthropic", CATALOG.displayName("anthropic").orElseThrow());
        }
    }

    // ---------------------------------------------------------------------
    // Expectations: (type, model, key under test, other parameters, effect)
    // ---------------------------------------------------------------------

    static Stream<Arguments> expectations() {
        Effect r = Effect.REJECTED;
        Effect i = Effect.IGNORED;
        return Stream.of(
                // OpenAI sampling vs reasoning
                arguments("openai", "gpt-4o", "temperature", "", null),
                arguments("openai", "gpt-4.1-mini", "temperature", "", null),
                arguments("openai", "gpt-oss-120b", "temperature", "", null),
                arguments("openai", "chat-latest", "temperature", "", null),
                arguments("openai", "gpt-5-chat-latest", "temperature", "", null),
                arguments("openai", "gpt-5-chat-latest", "frequencyPenalty", "", null),
                arguments("openai", "gpt-5-chat-latest", "maxTokens", "", null),
                arguments("openai", "gpt-5", "frequencyPenalty", "", r),
                arguments("azure-openai", "gpt-5-chat-latest", "presencePenalty", "", null),
                arguments("openai", "o3", "temperature", "", r),
                arguments("openai", "o4-mini", "temperature", "", r),
                arguments("openai", "o1-mini", "topP", "", r),
                arguments("openai", "gpt-5", "temperature", "", r),
                arguments("openai", "gpt-5-mini", "temperature", "reasoningEffort=minimal", r),
                arguments("openai", "gpt-5-2025-08-07", "topP", "", r),
                arguments("openai", "gpt-5.1", "temperature", "", null),
                arguments("openai", "gpt-5.1", "temperature", "reasoningEffort=high", r),
                arguments("openai", "gpt-5.2", "temperature", "reasoningEffort=none", null),
                arguments("openai", "gpt-5.4", "topP", "reasoningEffort=low", r),
                arguments("openai", "gpt-5.5", "temperature", "", r),
                arguments("openai", "gpt-5.5", "temperature", "reasoningEffort=NONE", null),
                arguments("openai", "gpt-5.6-sol", "topP", "reasoningEffort=none", null),
                arguments("openai", "gpt-6-sol", "temperature", "", r),
                arguments("openai", "gpt-6-luna", "temperature", "reasoningEffort=none", null),
                arguments("openai", "gpt-6-astra", "temperature", "reasoningEffort=none", r),
                arguments("openai", "gpt-6.1-sol", "temperature", "reasoningEffort=none", r),
                arguments("openai", "gpt-5.5-pro", "temperature", "reasoningEffort=none", r),
                arguments("openai", "GPT-5", "temperature", "", r),
                arguments("openai", "o3", "frequencyPenalty", "", r),
                arguments("openai", "gpt-4o", "presencePenalty", "", null),
                arguments("openai", "gpt-5.2", "logitBias", "", r),
                arguments("openai", "o3", "maxTokens", "", r),
                arguments("openai", "gpt-4.1", "maxTokens", "", null),
                arguments("openai", "o3", "stopSequences", "", r),
                arguments("openai", "o3-pro", "stopSequences", "", r),
                arguments("openai", "o4-mini", "stopSequences", "", r),
                arguments("openai", "o3-mini", "stopSequences", "", null),
                arguments("openai", "gpt-4o", "stopSequences", "", null),
                // OpenAI reasoningEffort values
                arguments("openai", "gpt-4o", "reasoningEffort", "reasoningEffort=high", r),
                arguments("openai", "o3", "reasoningEffort", "reasoningEffort=high", null),
                arguments("openai", "gpt-5", "reasoningEffort", "reasoningEffort=none", r),
                arguments("openai", "gpt-5", "reasoningEffort", "reasoningEffort=minimal", null),
                arguments("openai", "gpt-5.1", "reasoningEffort", "reasoningEffort=none", null),
                arguments("openai", "gpt-6-astra", "reasoningEffort", "reasoningEffort=none", r),
                arguments("openai", "gpt-6-luna", "reasoningEffort", "reasoningEffort=none", null),
                arguments("openai", "gpt-5.2", "reasoningEffort", "reasoningEffort=minimal", r),
                arguments("openai", "gpt-5.1", "reasoningEffort", "reasoningEffort=xhigh", r),
                arguments("openai", "gpt-5.1-codex-max", "reasoningEffort", "reasoningEffort=xhigh", null),
                arguments("openai", "gpt-5.2", "reasoningEffort", "reasoningEffort=xhigh", null),
                arguments("openai", "gpt-5.5", "reasoningEffort", "reasoningEffort=max", r),
                arguments("openai", "gpt-5.6-sol", "reasoningEffort", "reasoningEffort=max", null),
                arguments("openai", "gpt-6-sol", "reasoningEffort", "reasoningEffort=max", null),
                // Azure: rules read baseModel, else the deployment name
                arguments("azure-openai", "gpt-5", "temperature", "", r),
                arguments("azure-openai", "prod-chat", "temperature", "", null),
                arguments("azure-openai", "prod-chat", "temperature", "baseModel=o3", r),
                arguments("azure-openai", "gpt-5", "temperature", "baseModel=gpt-4o", null),
                arguments("azure-openai", "gpt-6-sol", "temperature", "", null),
                arguments("azure-openai", "gpt-6-astra", "presencePenalty", "", null),
                arguments("azure-openai", "gpt-6-sol", "presencePenalty", "", r),
                arguments("azure-openai", "gpt-4.1", "reasoningEffort", "reasoningEffort=low", r),
                arguments("azure-openai", "gpt-5-codex", "reasoningEffort", "reasoningEffort=minimal", r),
                arguments("azure-openai", "gpt-5-nano", "reasoningEffort", "reasoningEffort=minimal", null),
                arguments("azure-openai", "gpt-5", "reasoningEffort", "reasoningEffort=none", r),
                arguments("azure-openai", "gpt-5.1", "reasoningEffort", "reasoningEffort=none", null),
                // Anthropic sampling
                arguments("anthropic", "claude-opus-5-5", "temperature", "", r),
                arguments("anthropic", "claude-opus-5-5", "temperature", "temperature=1", null),
                arguments("anthropic", "claude-sonnet-5", "temperature", "temperature=1.0", null),
                arguments("anthropic", "claude-mythos-preview", "temperature", "", r),
                arguments("anthropic", "claude-opus-4-6", "temperature", "", null),
                arguments("anthropic", "claude-opus-4-6", "temperature", "thinkingType=adaptive", r),
                arguments("anthropic", "claude-sonnet-4-5", "temperature", "thinkingType=enabled", r),
                arguments("anthropic", "claude-sonnet-4-5", "temperature", "thinkingType=disabled", null),
                arguments("anthropic", "claude-opus-5-5", "topP", "topP=0.99", null),
                arguments("anthropic", "claude-opus-5-5", "topP", "topP=0.99,temperature=1", r),
                arguments("anthropic", "claude-haiku-5-5", "topP", "", r),
                arguments("anthropic", "claude-sonnet-4-5", "topP", "temperature=0.2", r),
                arguments("anthropic", "claude-sonnet-4-5", "topP", "", null),
                arguments("anthropic", "claude-opus-4-6", "topP", "temperature=0.2", null),
                arguments("anthropic", "claude-haiku-5-5", "topK", "", r),
                arguments("anthropic", "claude-haiku-4-5", "topK", "", null),
                arguments("anthropic", "claude-haiku-4-5-20251001", "topK", "thinkingType=enabled", r),
                // Anthropic thinking
                arguments("anthropic", "claude-opus-4-7", "thinkingType", "thinkingType=enabled", r),
                arguments("anthropic", "claude-opus-4-6", "thinkingType", "thinkingType=enabled", null),
                arguments("anthropic", "claude-mythos-preview", "thinkingType", "thinkingType=enabled", null),
                arguments("anthropic", "claude-opus-4-5-20251101", "thinkingType", "thinkingType=adaptive", r),
                arguments("anthropic", "claude-opus-4", "thinkingType", "thinkingType=adaptive", r),
                arguments("anthropic", "claude-opus-4-6", "thinkingType", "thinkingType=adaptive", null),
                arguments("anthropic", "claude-fable-5-1", "thinkingType", "thinkingType=disabled", r),
                arguments("anthropic", "claude-opus-5", "thinkingType", "thinkingType=disabled,effort=high", null),
                arguments("anthropic", "claude-opus-5", "thinkingType", "thinkingType=disabled,effort=max", r),
                arguments("anthropic", "claude-opus-5", "thinkingType", "thinkingType=adaptive,effort=max", null),
                arguments("anthropic", "claude-opus-5-5", "thinkingType", "thinkingType=disabled", r),
                arguments("anthropic", "claude-sonnet-5-5", "thinkingType", "thinkingType=between_tools", null),
                arguments("anthropic", "claude-sonnet-5", "thinkingType", "thinkingType=between_tools", r),
                arguments("anthropic", "claude-sonnet-5-5", "thinkingType", "thinkingType=between_tools,effort=xhigh", r),
                arguments("anthropic", "claude-opus-4-7", "thinkingBudgetTokens", "", r),
                arguments("anthropic", "claude-opus-4-6", "thinkingBudgetTokens", "", null),
                arguments("anthropic", "claude-mythos-preview", "thinkingBudgetTokens", "", null),
                arguments("anthropic", "claude-opus-4-6", "thinkingDisplay", "thinkingType=disabled", r),
                arguments("anthropic", "claude-opus-4-6", "thinkingDisplay", "thinkingType=adaptive", null),
                arguments("anthropic", "claude-sonnet-4-5", "effort", "effort=high", r),
                arguments("anthropic", "claude-opus-4-5", "effort", "effort=high", null),
                arguments("anthropic", "claude-opus-4-5", "effort", "effort=max", r),
                arguments("anthropic", "claude-opus-4-6", "effort", "effort=max", null),
                arguments("anthropic", "claude-opus-4-6", "effort", "effort=xhigh", r),
                arguments("anthropic", "claude-opus-4-7", "effort", "effort=xhigh", null),
                // Gemini
                arguments("gemini", "gemini-3.6-flash", "temperature", "", i),
                arguments("gemini", "gemini-3.8-flash", "topP", "", i),
                arguments("gemini", "models/gemini-3.8-flash", "topK", "", i),
                arguments("gemini", "gemini-4-argon", "temperature", "", i),
                arguments("gemini", "gemini-3.1-pro-preview", "temperature", "", null),
                arguments("gemini", "gemini-3-flash-preview", "temperature", "", null),
                arguments("gemini", "gemini-2.5-flash", "temperature", "", null),
                arguments("gemini", "gemini-2.5-pro", "thinkingLevel", "thinkingLevel=high", r),
                arguments("gemini", "gemini-3.1-flash-lite", "thinkingLevel", "thinkingLevel=minimal", null),
                arguments("gemini", "gemini-3.6-flash", "thinkingLevel", "thinkingLevel=minimal", null),
                arguments("gemini", "gemini-3.8-flash", "thinkingLevel", "thinkingLevel=minimal", r),
                arguments("gemini", "gemini-4-argon", "thinkingLevel", "thinkingLevel=MINIMAL", r),
                arguments("gemini", "gemini-4-argon", "thinkingLevel", "thinkingLevel=low", null),
                arguments("gemini", "gemini-4-argon", "cachedContentName", "", r),
                arguments("gemini", "gemini-3.1-pro-preview", "cachedContentName", "", null),
                // Bedrock
                arguments("bedrock", "anthropic.claude-opus-5-5", "temperature", "", r),
                arguments("bedrock", "us.anthropic.claude-sonnet-5", "temperature", "", r),
                arguments("bedrock", "anthropic.claude-sonnet-4-6", "temperature", "", null),
                arguments("bedrock", "amazon.nova-pro-v1:0", "temperature", "", null),
                arguments("bedrock", "us.anthropic.claude-sonnet-4-5-20250929-v1:0", "topP", "temperature=0.1", r),
                arguments("bedrock", "anthropic.claude-haiku-4-5-20251001-v1:0", "reasoningEffort", "", r),
                arguments("bedrock", "global.anthropic.claude-sonnet-4-20250514-v1:0", "reasoningEffort", "", r),
                arguments("bedrock", "anthropic.claude-opus-4-6-v1", "reasoningEffort", "", null),
                arguments("bedrock", "anthropic.claude-opus-4-8", "reasoningBudgetTokens", "", r),
                arguments("bedrock", "anthropic.claude-opus-4-6-v1", "reasoningBudgetTokens", "", null),
                // xAI
                arguments("xai", "grok-4.7", "stopSequences", "", r),
                arguments("xai", "grok-4.3", "presencePenalty", "", r),
                arguments("xai", "grok-4.3", "presencePenalty", "reasoningEffort=low", r),
                // acceptance with reasoning off is not documented, so it is not rejected
                arguments("xai", "grok-4.3", "presencePenalty", "reasoningEffort=none", null),
                arguments("xai", "grok-4.3", "stopSequences", "reasoningEffort=none", null),
                arguments("xai", "grok-4.7", "presencePenalty", "reasoningEffort=none", r),
                // Azure follows the OpenAI structure for sampling settings
                arguments("azure-openai", "gpt-5-chat", "temperature", "", null),
                arguments("azure-openai", "gpt-5-chat-latest", "topP", "", null),
                arguments("azure-openai", "gpt-5.2", "temperature", "reasoningEffort=none", null),
                arguments("azure-openai", "gpt-5.2", "temperature", "", null),
                arguments("azure-openai", "gpt-5.2", "temperature", "reasoningEffort=low", r),
                arguments("azure-openai", "gpt-5", "temperature", "reasoningEffort=none", r),
                arguments("azure-openai", "o3", "topP", "", r),
                arguments("xai", "grok-4.5", "frequencyPenalty", "", r),
                arguments("xai", "grok-4.20-0309-reasoning", "stopSequences", "", r),
                arguments("xai", "grok-4.20-0309-non-reasoning", "stopSequences", "", null),
                arguments("xai", "grok-4.20-0309-non-reasoning", "temperature", "", null),
                arguments("xai", "grok-4.7", "logitBias", "", i),
                // DeepSeek
                arguments("deepseek", "deepseek-flash", "temperature", "", i),
                arguments("deepseek", "deepseek-v4-pro", "temperature", "thinkingType=disabled", null),
                arguments("deepseek", "deepseek-flash", "topP", "", null),
                arguments("deepseek", "deepseek-flash", "topP", "thinkingType=disabled", i),
                arguments("deepseek", null, "temperature", "", i),
                arguments("deepseek", "deepseek-v4-pro", "presencePenalty", "", i),
                // Moonshot
                arguments("moonshot", "kimi-k3", "temperature", "", r),
                arguments("moonshot", "kimi-k3", "temperature", "temperature=1", null),
                arguments("moonshot", "kimi-k2.7-code-highspeed", "temperature", "", r),
                arguments("moonshot", "kimi-k2.6", "temperature", "temperature=0.6,thinkingType=disabled", null),
                arguments("moonshot", "kimi-k2.6", "temperature", "temperature=1,thinkingType=disabled", r),
                arguments("moonshot", "kimi-k2.6", "temperature", "temperature=0.6", r),
                arguments("moonshot", "kimi-k2.6", "temperature", "temperature=1", null),
                arguments("moonshot", "kimi-k2.6", "temperature", "temperature=1,thinkingType=enabled", null),
                arguments("moonshot", "kimi-k2.6", "topP", "topP=0.95", null),
                arguments("moonshot", "kimi-k3", "topP", "", r),
                arguments("moonshot", "kimi-k2.6", "presencePenalty", "presencePenalty=0.0", null),
                arguments("moonshot", "kimi-k2.6", "frequencyPenalty", "", r),
                arguments("moonshot", "kimi-k3", "thinkingType", "thinkingType=enabled", r),
                arguments("moonshot", "kimi-k2.7-code", "thinkingType", "thinkingType=disabled", r),
                arguments("moonshot", "kimi-k2.7-code", "thinkingType", "thinkingType=enabled", null),
                arguments("moonshot", "kimi-k2.6", "thinkingType", "thinkingType=disabled", null),
                // Z.ai
                arguments("zhipu", "glm-5.3", "temperature", "", null),
                arguments("zhipu", "glm-5.3", "temperature", "doSample=false", i),
                arguments("zhipu", "glm-5.2", "topP", "doSample=FALSE", i),
                arguments("zhipu", "glm-5.1", "reasoningEffort", "reasoningEffort=high", i),
                arguments("zhipu", "glm-5", "reasoningEffort", "reasoningEffort=high", i),
                arguments("zhipu", "glm-5.2", "reasoningEffort", "reasoningEffort=high", null),
                arguments("zhipu", "glm-5.2", "reasoningEffort", "reasoningEffort=high,thinkingType=disabled", i),
                arguments("zhipu", "glm-5.3", "thinkingType", "thinkingType=disabled", i),
                arguments("zhipu", "glm-5.3-flashx", "thinkingType", "thinkingType=disabled", null),
                arguments("zhipu", "glm-5.3-flash", "responseFormat", "responseFormat=json", i),
                arguments("zhipu", "glm-5.3", "responseFormat", "responseFormat=json", null),
                // MiniMax
                arguments("minimax", "MiniMax-M2.7", "presencePenalty", "", i),
                arguments("minimax", "MiniMax-M3", "logitBias", "", i),
                arguments("minimax", "MiniMax-M3.1-Flash-Preview", "reasoningEffort", "reasoningEffort=none", r),
                arguments("minimax", "MiniMax-M3.1-Flash-Preview", "reasoningEffort", "reasoningEffort=high", null),
                arguments("minimax", "MiniMax-M3", "reasoningEffort", "reasoningEffort=none", null),
                arguments("minimax", "MiniMax-M3.1-Flash-Preview", "thinkingType", "thinkingType=disabled", r),
                arguments("minimax", "MiniMax-M2.7-highspeed", "thinkingType", "thinkingType=disabled", i),
                arguments("minimax", "MiniMax-M3", "thinkingType", "thinkingType=disabled", null),
                // OpenRouter
                arguments("openrouter", "openai/gpt-5.5", "topK", "", i),
                arguments("openrouter", "anthropic/claude-sonnet-5-5", "topK", "", null),
                arguments("openrouter", "openai/gpt-5.5", "temperature", "", null),
                // Groq
                arguments("groq", "openai/gpt-oss-120b", "reasoningEffort", "reasoningEffort=xhigh", r),
                arguments("groq", "openai/gpt-oss-20b", "reasoningEffort", "reasoningEffort=high", null),
                arguments("groq", "qwen/qwen3.8-27b", "reasoningEffort", "reasoningEffort=none", null),
                arguments("groq", "qwen/qwen3.8-27b", "reasoningEffort", "reasoningEffort=max", r),
                arguments("groq", "openai/gpt-oss-safeguard-20b", "reasoningEffort", "reasoningEffort=xhigh", null),
                arguments("groq", "openai/gpt-oss-20b", "reasoningFormat", "reasoningFormat=parsed", r),
                arguments("groq", "qwen/qwen3.8-27b", "reasoningFormat", "reasoningFormat=parsed", null),
                arguments("groq", "openai/gpt-oss-20b", "includeReasoning", "includeReasoning=false", null),
                arguments("groq", "openai/gpt-oss-20b", "includeReasoning", "reasoningFormat=hidden", null),
                arguments("groq", "qwen/qwen3.8-27b", "includeReasoning", "includeReasoning=true", r),
                arguments("groq", "openai/gpt-oss-120b", "parallelToolCalls", "", i),
                arguments("groq", "llama-3.3-70b-versatile", "parallelToolCalls", "", null),
                arguments("groq", "llama-3.1-8b-instant", "presencePenalty", "", i));
    }

    @ParameterizedTest(name = "{0} {1}: {2} [{3}] -> {4}")
    @MethodSource("expectations")
    @DisplayName("model rules")
    void rule(String type, String model, String key, String context, Effect expected) {
        assertEquals(expected, effectFor(type, params(type, model, key, context), key));
    }

    @Nested
    @DisplayName("adapt")
    class Adapt {

        @Test
        @DisplayName("removes a rejected parameter and keeps an ignored one")
        void dropsRejectedKeepsIgnored() {
            Map<String, String> in = Map.of("modelName", "o3", "temperature", "0.2", "maxCompletionTokens", "100");
            var adapted = CATALOG.adapt("openai", in, true);
            assertFalse(adapted.parameters().containsKey("temperature"));
            assertEquals("100", adapted.parameters().get("maxCompletionTokens"));
            Finding f = adapted.findings().getFirst();
            assertEquals("temperature", f.key());
            assertEquals("o3", f.model());
            assertTrue(f.removed());

            var gemini = CATALOG.adapt("gemini", Map.of("modelName", "gemini-3.8-flash", "temperature", "0.2"), true);
            assertEquals("0.2", gemini.parameters().get("temperature"));
            assertEquals(Effect.IGNORED, gemini.findings().getFirst().effect());
            assertFalse(gemini.findings().getFirst().removed());
        }

        @Test
        @DisplayName("a cascade step is judged by its own provider's model key, not the task's leftover one")
        void providerOwnModelKeyWins() {
            // a cascade merges the step's parameters over the task's, so the task's
            // modelName stays in the map
            Map<String, String> azureStep = Map.of("modelName", "gpt-5", "deploymentName", "gpt4o-prod", "temperature", "0.3", "maxTokens", "500");
            var adapted = CATALOG.adapt("azure-openai", azureStep, true);
            assertEquals("0.3", adapted.parameters().get("temperature"));
            assertEquals("500", adapted.parameters().get("maxTokens"));
            assertTrue(adapted.findings().isEmpty());
            assertEquals("gpt4o-prod", ModelParameterCatalog.modelId("azure-openai", azureStep));

            assertEquals("us.anthropic.claude-opus-4-7",
                    ModelParameterCatalog.modelId("bedrock", Map.of("modelName", "gpt-4o", "modelId", "us.anthropic.claude-opus-4-7")));
            assertEquals("llama3", ModelParameterCatalog.modelId("ollama", Map.of("modelName", "gpt-4o", "model", "llama3")));
            // the other way round: the own key is absent, so the fixed order still finds a
            // model
            assertEquals("gpt-4o", ModelParameterCatalog.modelId("openai", Map.of("model", "gpt-4o")));
            assertEquals("base", ModelParameterCatalog.modelId("azure-openai", Map.of("baseModel", "base", "deploymentName", "dep")));
        }

        @Test
        @DisplayName("a rejected maxTokens is carried over as maxCompletionTokens, unless that is configured")
        void migratesTheOutputCap() {
            var migratedResult = CATALOG.adapt("openai", Map.of("modelName", "gpt-5", "maxTokens", "4000"), true);
            var migrated = migratedResult.parameters();
            assertFalse(migrated.containsKey("maxTokens"));
            assertEquals("4000", migrated.get("maxCompletionTokens"));
            assertEquals("maxCompletionTokens", migratedResult.findings().getFirst().migratedTo(),
                    "the finding says where the value went, so the log does not claim it was not sent");

            var explicitFinding = CATALOG.adapt("openai", Map.of("modelName", "gpt-5", "maxTokens", "4000", "maxCompletionTokens", "9"), true);
            assertNull(explicitFinding.findings().getFirst().migratedTo());

            var azure = CATALOG.adapt("azure-openai", Map.of("baseModel", "o3", "deploymentName", "my-deploy", "maxTokens", "4000"), true)
                    .parameters();
            assertEquals("4000", azure.get("maxCompletionTokens"));

            var explicit = CATALOG.adapt("openai", Map.of("modelName", "gpt-5", "maxTokens", "4000", "maxCompletionTokens", "9000"), true)
                    .parameters();
            assertFalse(explicit.containsKey("maxTokens"));
            assertEquals("9000", explicit.get("maxCompletionTokens"));

            var untouched = CATALOG.adapt("openai", Map.of("modelName", "gpt-4o", "maxTokens", "4000"), true).parameters();
            assertEquals("4000", untouched.get("maxTokens"));
            assertFalse(untouched.containsKey("maxCompletionTokens"));
        }

        @Test
        @DisplayName("with dropRejected=false a rejected parameter is only reported")
        void keepWhenNotDropping() {
            Map<String, String> in = Map.of("modelName", "o3", "temperature", "0.2");
            var adapted = CATALOG.adapt("openai", in, false);
            assertSame(in, adapted.parameters());
            assertEquals(Effect.REJECTED, adapted.findings().getFirst().effect());
            assertFalse(adapted.findings().getFirst().removed());
        }

        @Test
        @DisplayName("conditions read the configured map, so dropping one parameter never changes another's verdict")
        void conditionsUseOriginalMap() {
            // claude-opus-5-5: temperature 0.5 is rejected; topP is rejected with
            // temperature present — even though temperature itself is being dropped.
            var adapted = CATALOG.adapt("anthropic",
                    Map.of("modelName", "claude-opus-5-5", "temperature", "0.5", "topP", "0.99"), true);
            assertFalse(adapted.parameters().containsKey("temperature"));
            assertFalse(adapted.parameters().containsKey("topP"));
            assertEquals(List.of("temperature", "topP"), adapted.findings().stream().map(Finding::key).toList());
        }

        @Test
        @DisplayName("an unknown type, a missing model or an unknown model leaves the map untouched")
        void failsOpen() {
            Map<String, String> in = Map.of("modelName", "o3", "temperature", "0.2");
            assertSame(in, CATALOG.adapt("no-such-provider", in, true).parameters());
            Map<String, String> noModel = Map.of("temperature", "0.2");
            assertSame(noModel, CATALOG.adapt("openai", noModel, true).parameters());
            Map<String, String> custom = Map.of("modelName", "my-finetune", "temperature", "0.2");
            var adapted = CATALOG.adapt("openai", custom, true);
            assertSame(custom, adapted.parameters());
            assertTrue(adapted.findings().isEmpty());
            assertTrue(CATALOG.adapt("openai", null, true).parameters().isEmpty());
        }

        @Test
        @DisplayName("a blank value is not a configured parameter")
        void blankIsUnset() {
            var adapted = CATALOG.adapt("openai", Map.of("modelName", "o3", "temperature", "  "), true);
            assertTrue(adapted.findings().isEmpty());
        }

        @Test
        @DisplayName("the model comes from the first non-blank model key, Azure's baseModel, or a compatible preset")
        void modelId() {
            assertEquals("llama3", ModelParameterCatalog.modelId("ollama", Map.of("model", "llama3")));
            assertEquals("m", ModelParameterCatalog.modelId("bedrock", Map.of("modelName", " ", "modelId", " m ")));
            assertEquals("o3", ModelParameterCatalog.modelId("azure-openai", Map.of("deploymentName", "prod", "baseModel", "o3")));
            assertEquals("prod", ModelParameterCatalog.modelId("azure-openai", Map.of("deploymentName", "prod", "baseModel", "")));
            // baseModel is Azure-only
            assertEquals("gpt-4o", ModelParameterCatalog.modelId("openai", Map.of("modelName", "gpt-4o", "baseModel", "o3")));
            assertEquals("kimi-k3", ModelParameterCatalog.modelId("moonshot", Map.of()));
            assertEquals(null, ModelParameterCatalog.modelId("openai", Map.of()));
        }

        @Test
        @DisplayName("dedicated keys add region for OpenAI-compatible providers only")
        void dedicated() {
            assertTrue(ModelParameterCatalog.dedicatedKeys("xai").contains("region"));
            assertFalse(ModelParameterCatalog.dedicatedKeys("bedrock").contains("region"));
            assertTrue(ModelParameterCatalog.dedicatedKeys("openai").contains("apiKey"));
        }
    }

    // ---------------------------------------------------------------------
    // Condition semantics on a hand-written catalog
    // ---------------------------------------------------------------------

    private static final String CONDITIONS = """
            {"schemaVersion": 1, "providers": {"p": {"displayName": "P", "params": [
              {"key": "a", "type": "double", "group": "sampling", "label": "A", "description": "A.", "source": "https://x.test",
               "rules": [{"models": "^m", "effect": "rejected", "note": "when any", "when": {"key": "b"}}]},
              {"key": "c", "type": "double", "group": "sampling", "label": "C", "description": "C.", "source": "https://x.test",
               "rules": [{"models": "^m", "effect": "rejected", "note": "when value", "when": {"key": "b", "values": ["on", "2"]}}]},
              {"key": "d", "type": "double", "group": "sampling", "label": "D", "description": "D.", "source": "https://x.test",
               "rules": [{"models": "^m", "effect": "ignored", "note": "unless set-or-unset",
                          "unless": {"key": "b", "values": ["off"], "whenUnset": true}}]},
              {"key": "e", "type": "double", "group": "sampling", "label": "E", "description": "E.", "source": "https://x.test",
               "rules": [{"models": "^m", "effect": "rejected", "note": "first", "when": {"key": "b", "values": ["x"]}},
                         {"models": "^m", "effect": "ignored", "note": "second"}]}
            ]}}}
            """;

    private static Effect conditionEffect(String key, String b) {
        ModelParameterCatalog catalog = ModelParameterCatalog.parse(CONDITIONS, "test");
        Map<String, String> p = new HashMap<>(Map.of("modelName", "m1", key, "1"));
        if (b != null) {
            p.put("b", b);
        }
        return catalog.adapt("p", p, true).findings().stream().map(Finding::effect).findFirst().orElse(null);
    }

    @Test
    @DisplayName("when without values holds whenever the other key is set")
    void whenAny() {
        assertEquals(Effect.REJECTED, conditionEffect("a", "anything"));
        assertEquals(null, conditionEffect("a", null));
        assertEquals(null, conditionEffect("a", " "));
    }

    @Test
    @DisplayName("when with values matches case-insensitively and numerically")
    void whenValues() {
        assertEquals(Effect.REJECTED, conditionEffect("c", "ON"));
        assertEquals(Effect.REJECTED, conditionEffect("c", "2.0"));
        assertEquals(null, conditionEffect("c", "off"));
        assertEquals(null, conditionEffect("c", null));
    }

    @Test
    @DisplayName("unless with whenUnset=true also exempts an unset key")
    void unlessWhenUnset() {
        assertEquals(null, conditionEffect("d", null));
        assertEquals(null, conditionEffect("d", "off"));
        assertEquals(Effect.IGNORED, conditionEffect("d", "on"));
    }

    @Test
    @DisplayName("the first matching rule wins")
    void firstWins() {
        assertEquals(Effect.REJECTED, conditionEffect("e", "x"));
        assertEquals(Effect.IGNORED, conditionEffect("e", "y"));
        ModelParameterCatalog catalog = ModelParameterCatalog.parse(CONDITIONS, "test");
        assertEquals(1, catalog.adapt("p", Map.of("modelName", "m1", "e", "1", "b", "x"), false).findings().size(),
                "one finding per parameter");
    }

    // ---------------------------------------------------------------------
    // Loader validation
    // ---------------------------------------------------------------------

    private static final String PARAM = """
            {"key": "k", "type": "double", "group": "sampling", "label": "K", "description": "K.", "source": "https://x.test"%s}""";

    private static String doc(String paramExtra) {
        return "{\"schemaVersion\": 1, \"providers\": {\"p\": {\"displayName\": \"P\", \"params\": [" + PARAM.formatted(paramExtra)
                + "]}}}";
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                arguments("{\"providers\": {}}", "schemaVersion"),
                arguments("{\"schemaVersion\": 2, \"providers\": {}}", "schemaVersion"),
                arguments("{\"schemaVersion\": 1, \"providers\": {}}", "declares no providers"),
                arguments("{\"schemaVersion\": 1, \"providers\": {\"P\": {\"displayName\": \"P\", \"params\": [" + PARAM.formatted("")
                        + "]}}}", "lower-case"),
                arguments("{\"schemaVersion\": 1, \"providers\": {\"p\": {\"displayName\": \"P\", \"params\": []}}}", "non-empty array"),
                arguments("{\"schemaVersion\": 1, \"providers\": {\"p\": {\"displayName\": \"P\", \"inherits\": \"q\", \"params\": ["
                        + PARAM.formatted("") + "]}}}", "inherits unknown provider"),
                arguments(doc(", \"colour\": \"red\""), "unknown field 'colour'"),
                arguments(doc(", \"type\": \"float\"").replace("\"type\": \"double\", ", ""), "unknown type"),
                arguments(doc("").replace("\"sampling\"", "\"misc\""), "unknown group"),
                arguments(doc("").replace("https://x.test", "http://x.test"), "https"),
                arguments(doc("").replace("\"key\": \"k\"", "\"key\": \"apiKey\""), "dedicated key"),
                arguments(doc(", \"enum\": [\"a\"]"), "only type enum"),
                arguments(doc("").replace("\"double\"", "\"enum\""), "needs a non-empty \"enum\""),
                arguments(doc(", \"min\": 2, \"max\": 1"), "min is greater than max"),
                arguments(doc(", \"body\": true"), "needs a \"wire\" path"),
                arguments(doc(", \"wire\": \"a.b\""), "only meaningful with"),
                arguments(doc(", \"body\": true, \"wire\": \"a..b\""), "dotted path"),
                arguments(doc(", \"body\": true, \"wire\": \"a\", \"header\": \"X\""), "not both"),
                arguments(doc(", \"rules\": []"), "non-empty array"),
                arguments(doc(", \"rules\": [{\"models\": \"gpt\", \"effect\": \"rejected\", \"note\": \"n\"}]"), "anchored"),
                arguments(doc(", \"rules\": [{\"models\": \"^(gpt\", \"effect\": \"rejected\", \"note\": \"n\"}]"), "invalid models regex"),
                arguments(doc(", \"rules\": [{\"models\": \"^gpt\", \"effect\": \"fatal\", \"note\": \"n\"}]"), "rejected or ignored"),
                arguments(doc(", \"rules\": [{\"models\": \"^gpt\", \"effect\": \"rejected\"}]"), "\"note\" is required"),
                arguments(doc(", \"rules\": [{\"models\": \"^gpt\", \"effect\": \"rejected\", \"note\": \"n\", \"when\": {\"values\": [\"x\"]}}]"),
                        "\"key\" is required"),
                arguments(doc(
                        ", \"rules\": [{\"models\": \"^gpt\", \"effect\": \"rejected\", \"note\": \"n\", \"unless\": {\"key\": \"x\", \"values\": []}}]"),
                        "non-empty array of strings"),
                arguments("{\"schemaVersion\": 1, \"providers\": {\"p\": {\"displayName\": \"P\", \"params\": [" + PARAM.formatted("") + ","
                        + PARAM.formatted("") + "]}}}", "duplicate parameter 'k'"),
                arguments("not json", "not valid JSON"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("malformed")
    @DisplayName("a malformed catalog fails fast and says what is wrong")
    void malformedCatalog(String json, String expectedMessage) {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ModelParameterCatalog.parse(json, "test.json"));
        assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
        assertTrue(e.getMessage().startsWith("test.json"), e.getMessage());
    }

    @Test
    @DisplayName("a missing resource fails fast")
    void missingResource() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ModelParameterCatalog.load("/llm/nope.json"));
        assertTrue(e.getMessage().contains("/llm/nope.json"));
    }

    @Test
    @DisplayName("an inherited parent may not inherit itself")
    void noInheritanceChains() {
        String json = "{\"schemaVersion\": 1, \"providers\": {"
                + "\"a\": {\"displayName\": \"A\", \"params\": [" + PARAM.formatted("") + "]},"
                + "\"b\": {\"displayName\": \"B\", \"inherits\": \"a\", \"params\": [" + PARAM.formatted("").replace("\"k\"", "\"j\"") + "]},"
                + "\"c\": {\"displayName\": \"C\", \"inherits\": \"b\", \"params\": [" + PARAM.formatted("").replace("\"k\"", "\"i\"") + "]}}}";
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> ModelParameterCatalog.parse(json, "test.json"));
        assertTrue(e.getMessage().contains("itself inherits"), e.getMessage());
    }
}
