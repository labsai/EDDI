/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.capability;

import ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Support;
import ai.labs.eddi.modules.llm.impl.builder.OpenAiCompatibleEndpoint;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WS10: a self-hosted {@code openai-compatible} endpoint is judged by the
 * capability flags in its own parameters, not by the OpenAI rows it used to
 * inherit — and {@code type: openai} keeps exactly the rows it had.
 */
@DisplayName("openai-compatible endpoint capabilities")
class OpenAiCompatibleEndpointCapabilitiesTest {

    private static final String TYPE = "openai-compatible";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"string\"}}}";

    private static Map<String, String> params(String... kv) {
        Map<String, String> p = new HashMap<>();
        p.put("baseUrl", "http://vllm:8000/v1");
        p.put("modelName", "some/model");
        for (int i = 0; i < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    private static ResponseFormat resolve(String type, String override, Map<String, String> params, boolean tools) {
        return JsonResponseFormatPolicy.of(true, type, override, null, params).resolve(tools);
    }

    @Nested
    @DisplayName("JSON response format")
    class Json {

        @Test
        @DisplayName("default flags: no JSON format, with or without tools")
        void defaultsSendNothing() {
            assertNull(resolve(TYPE, null, params(), false));
            assertNull(resolve(TYPE, null, params(), true));
            assertNull(resolve(TYPE, "auto", params("jsonMode", "false", "jsonModeWithTools", "false"), false));
        }

        @Test
        @DisplayName("jsonMode=true, jsonModeWithTools=false: JSON only on tool-less requests")
        void jsonModeOnly() {
            var p = params("jsonMode", "true", "jsonModeWithTools", "false");
            assertSame(ResponseFormat.JSON, resolve(TYPE, null, p, false));
            assertNull(resolve(TYPE, null, p, true));
        }

        @Test
        @DisplayName("both flags on: JSON with and without tools")
        void bothOn() {
            var p = params("jsonMode", "true", "jsonModeWithTools", "true");
            assertSame(ResponseFormat.JSON, resolve(TYPE, null, p, false));
            assertSame(ResponseFormat.JSON, resolve(TYPE, null, p, true));
        }

        @Test
        @DisplayName("the per-task override still wins over the endpoint flags, both ways")
        void overrideWins() {
            assertNull(resolve(TYPE, "off", params("jsonMode", "true", "jsonModeWithTools", "true"), false));
            assertSame(ResponseFormat.JSON, resolve(TYPE, "on", params(), true));
        }

        @Test
        @DisplayName("never a native json_schema, even with a responseSchema and jsonMode on")
        void noNativeSchema() {
            var format = JsonResponseFormatPolicy.of(true, TYPE, null, SCHEMA, params("jsonMode", "true")).resolve(false);
            assertSame(ResponseFormat.JSON, format);
            assertFalse(JsonResponseFormatPolicy.supportsNativeSchema(TYPE));
        }

        @Test
        @DisplayName("convertToObject=false: nothing, whatever the flags say")
        void notRequested() {
            assertNull(JsonResponseFormatPolicy.of(false, TYPE, null, null, params("jsonMode", "true")).resolve(false));
        }

        @Test
        @DisplayName("a flag that is not a boolean at request time counts as false instead of failing the turn")
        void badFlag() {
            // Per request: degrade to the conservative answer, never fail the turn.
            assertNull(resolve(TYPE, null, params("jsonModeWithTools", "yes"), true));
            assertNull(resolve(TYPE, null, params("jsonMode", "${vault:unresolved}"), false));
            assertSame(ResponseFormat.JSON, resolve(TYPE, null, params("jsonMode", "true", "jsonModeWithTools", "yes"), false));
        }

        @Test
        @DisplayName("a ${vars:...} flag is resolved before it is read; a resolver failure counts as false")
        void resolvedFlags() {
            var p = params("jsonMode", "${vars:vllm-json}", "vision", "${vars:vllm-vision}");
            Map<String, String> resolved = OpenAiCompatibleEndpoint.withResolvedFlags(TYPE, p,
                    v -> v.equals("${vars:vllm-json}") ? "true" : v.equals("${vars:vllm-vision}") ? "TRUE" : v);
            assertSame(ResponseFormat.JSON, resolve(TYPE, null, resolved, false));
            assertTrue(OpenAiCompatibleEndpoint.declaresVision(TYPE, resolved));
            assertEquals("${vars:vllm-json}", p.get("jsonMode"), "the input map is not modified");

            Map<String, String> failed = OpenAiCompatibleEndpoint.withResolvedFlags(TYPE, p, v -> {
                throw new IllegalStateException("store down");
            });
            assertNull(resolve(TYPE, null, failed, false));
            // Any other type is returned untouched, resolver never consulted.
            assertSame(p, OpenAiCompatibleEndpoint.withResolvedFlags("openai", p, v -> {
                throw new AssertionError("not called");
            }));
        }

        @Test
        @DisplayName("without parameters the generic type is never sent JSON by the matrix")
        void matrixAlone() {
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJson(TYPE));
            assertFalse(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools(TYPE));
            assertNull(JsonResponseFormatPolicy.of(true, TYPE, null, null).resolve(false));
        }
    }

    @Nested
    @DisplayName("type: openai is unchanged (regression pin)")
    class OpenAiRegression {

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        @DisplayName("JSON with and without tools, with or without a baseUrl, and endpoint flags are ignored")
        void openAiRows(boolean tools) {
            Map<String, String> selfHostedViaOpenAi = new HashMap<>(Map.of("baseUrl", "http://vllm:8000/v1", "modelName", "x",
                    "jsonMode", "false", "jsonModeWithTools", "false"));
            assertSame(ResponseFormat.JSON, resolve("openai", null, Map.of(), tools));
            assertSame(ResponseFormat.JSON, resolve("openai", null, selfHostedViaOpenAi, tools));
            assertNull(JsonResponseFormatPolicy.of(true, "openai", null, null, selfHostedViaOpenAi).endpoint());
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJson("openai"));
            assertTrue(JsonResponseFormatPolicy.supportsRequestLevelJsonWithTools("openai"));
            assertTrue(JsonResponseFormatPolicy.supportsNativeSchema("openai"));
            assertNull(resolve("openai", "off", selfHostedViaOpenAi, tools));
        }

        @Test
        @DisplayName("openai still sends a native schema")
        void openAiNativeSchema() {
            var format = JsonResponseFormatPolicy.of(true, "openai", null, SCHEMA, Map.of("baseUrl", "http://vllm:8000/v1")).resolve(true);
            assertNotNull(format.jsonSchema());
        }

        @Test
        @DisplayName("an old type: openai + baseUrl configuration deserialises and is judged exactly as before")
        void oldConfigDeserialises() throws Exception {
            String json = """
                    {"tasks":[{"id":"t","type":"openai","actions":["a"],
                      "parameters":{"apiKey":"k","baseUrl":"http://vllm:8000/v1","modelName":"llama","convertToObject":"true"}}]}
                    """;
            var config = new ObjectMapper().readValue(json, LlmConfiguration.class);
            var task = config.tasks().getFirst();
            var policy = JsonResponseFormatPolicy.of(true, task.getType(), task.getJsonResponseFormat(), null, task.getParameters());
            assertNull(policy.endpoint());
            assertSame(ResponseFormat.JSON, policy.resolve(true));
        }

        @Test
        @DisplayName("openai vision is still decided by model name, and an endpoint flag does not touch it")
        void openAiVision() {
            var service = new ModelCapabilityService(key -> Optional.empty());
            Map<String, String> p = Map.of("vision", "true");
            assertEquals(Support.AUTO, ModelCapabilityService.visionOverride(Support.AUTO, "openai", p));
            assertTrue(service.supportsVision("openai", "gpt-4o", ModelCapabilityService.visionOverride(Support.AUTO, "openai", p)));
            assertFalse(service.supportsVision("openai", "gpt-3.5-turbo", ModelCapabilityService.visionOverride(Support.AUTO, "openai", p)));
            assertTrue(service.supportsImageUrl("openai", "gpt-4o"));
        }
    }

    @Nested
    @DisplayName("vision")
    class Vision {

        private final Map<String, String> deployment = new HashMap<>();
        private final ModelCapabilityService service = new ModelCapabilityService(key -> Optional.ofNullable(deployment.get(key)));

        private boolean vision(Support task, Map<String, String> params, String model) {
            return service.supportsVision(TYPE, model, ModelCapabilityService.visionOverride(task, TYPE, params));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Qwen/Qwen2.5-VL-7B-Instruct", "my-finetune", "gpt-4o", ""})
        @DisplayName("vision=true enables images for any model name")
        void visionTrue(String model) {
            assertTrue(vision(Support.AUTO, params("vision", "true"), model));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Qwen/Qwen2.5-VL-7B-Instruct", "llava", "gpt-4o"})
        @DisplayName("default off, even for a model name the table knows as multimodal")
        void defaultOff(String model) {
            assertFalse(vision(Support.AUTO, params(), model));
            assertFalse(vision(Support.AUTO, params("vision", "false"), model));
        }

        @Test
        @DisplayName("precedence: per-task override > endpoint flag > deployment > default")
        void precedence() {
            assertFalse(vision(Support.OFF, params("vision", "true"), "m"));
            assertTrue(vision(Support.ON, params("vision", "false"), "m"));
            deployment.put("eddi.multimodal.openai-compatible.vision", "on");
            assertTrue(vision(Support.AUTO, params(), "m"));
        }

        @Test
        @DisplayName("images are inlined, never sent by URL, to a self-hosted server")
        void noImageUrl() {
            assertFalse(service.supportsImageUrl(TYPE, "llava"));
        }

        @Test
        @DisplayName("a vision flag that is not a boolean at request time counts as unset")
        void badFlag() {
            assertFalse(vision(Support.AUTO, params("vision", "1"), "m"));
        }
    }
}
