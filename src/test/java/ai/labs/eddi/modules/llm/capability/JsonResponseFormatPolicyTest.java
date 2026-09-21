/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D7 — the provider matrix that decides whether one outgoing request may carry
 * a schemaless {@code ResponseFormat.JSON}.
 * <p>
 * The matrix is derived from what the langchain4j 1.18.0 bindings do with
 * {@code ChatRequest#responseFormat()}; the assertions below are the contract
 * the three execution modes rely on.
 */
@DisplayName("JsonResponseFormatPolicy")
class JsonResponseFormatPolicyTest {

    @Nested
    @DisplayName("provider matrix")
    class Matrix {

        @ParameterizedTest
        @ValueSource(strings = {"openai", "azure-openai", "mistral"})
        @DisplayName("providers that map schemaless JSON, with or without tools")
        void supportedEverywhere(String provider) {
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJson(provider));
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(provider));
        }

        @ParameterizedTest
        @ValueSource(strings = {"gemini", "gemini-vertex"})
        @DisplayName("Gemini maps JSON to responseMimeType — accepted alone, rejected alongside tools")
        void geminiOnlyWithoutTools(String provider) {
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJson(provider));
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(provider),
                    provider + " returns 400 for 'Function calling with a response mime type: application/json'");
        }

        @ParameterizedTest
        @ValueSource(strings = {"anthropic", "bedrock", "ollama", "jlama", "huggingface", "oracle-genai"})
        @DisplayName("providers that must never be sent a schemaless JSON format")
        void unsupported(String provider) {
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJson(provider));
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(provider));
        }

        @Test
        @DisplayName("provider matching is case- and whitespace-insensitive, null is unsupported")
        void normalization() {
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJson("  OpenAI "));
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJson(null));
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(null));
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("a task that did not ask for structured output never gets a format")
        void notRequested() {
            var policy = JsonResponseFormatPolicy.of(false, "openai", null);
            assertNull(policy.resolve(false));
            assertNull(policy.resolve(true));
        }

        @Test
        @DisplayName("DISABLED never applies")
        void disabled() {
            assertFalse(JsonResponseFormatPolicy.DISABLED.applies(false));
            assertFalse(JsonResponseFormatPolicy.DISABLED.applies(true));
        }

        @Test
        @DisplayName("an approved provider resolves to the schemaless JSON format")
        void approvedProviderResolvesJson() {
            ResponseFormat format = JsonResponseFormatPolicy.of(true, "mistral", null).resolve(true);
            assertNotNull(format);
            assertEquals(ResponseFormatType.JSON, format.type());
            assertNull(format.jsonSchema(), "the format must stay schemaless — providers map that to json_object");
        }

        @Test
        @DisplayName("gemini gets the format without tools and not with them")
        void geminiIsToolsAware() {
            var policy = JsonResponseFormatPolicy.of(true, "gemini", null);
            assertNotNull(policy.resolve(false));
            assertNull(policy.resolve(true));
        }

        @Test
        @DisplayName("task override 'off' suppresses an otherwise approved provider")
        void overrideOff() {
            assertNull(JsonResponseFormatPolicy.of(true, "openai", "off").resolve(false));
        }

        @Test
        @DisplayName("task override 'on' forces the format onto an unknown provider, tools included")
        void overrideOn() {
            var policy = JsonResponseFormatPolicy.of(true, "ollama", "on");
            assertNotNull(policy.resolve(false));
            assertNotNull(policy.resolve(true));
        }

        @Test
        @DisplayName("an override token cannot resurrect a task that never asked for structured output")
        void overrideOnStillNeedsRequest() {
            assertNull(JsonResponseFormatPolicy.of(false, "openai", "on").resolve(false));
        }

        @Test
        @DisplayName("unrecognized and null override tokens fall back to the matrix")
        void overrideAuto() {
            assertNotNull(JsonResponseFormatPolicy.of(true, "openai", "nonsense").resolve(false));
            assertNull(JsonResponseFormatPolicy.of(true, "anthropic", null).resolve(false));
            assertNull(new JsonResponseFormatPolicy(true, "anthropic", null).resolve(false));
        }
    }
}
