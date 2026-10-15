/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OpenAiCompatibleLanguageModelBuilder")
class OpenAiCompatibleLanguageModelBuilderTest {

    private OpenAILanguageModelBuilder delegate;
    private ArgumentCaptor<Map<String, String>> params;
    private ArgumentCaptor<Map<String, Object>> custom;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        delegate = mock(OpenAILanguageModelBuilder.class);
        when(delegate.build(any(), any())).thenReturn(mock(ChatModel.class));
        when(delegate.buildStreaming(any(), any())).thenReturn(mock(StreamingChatModel.class));
        when(delegate.recognisedParameters()).thenReturn(new OpenAILanguageModelBuilder().recognisedParameters());
        params = ArgumentCaptor.forClass(Map.class);
        custom = ArgumentCaptor.forClass(Map.class);
    }

    private OpenAiCompatibleLanguageModelBuilder builderFor(String type) {
        return new OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProviders.find(type).orElseThrow(), delegate);
    }

    private Map<String, String> merged(OpenAiCompatibleLanguageModelBuilder b, Map<String, String> in) {
        assertNotNull(b.build(in));
        verify(delegate).build(params.capture(), custom.capture());
        return params.getValue();
    }

    private Map<String, String> withKey() {
        Map<String, String> m = new HashMap<>();
        m.put("apiKey", "${vault:k}");
        return m;
    }

    @Nested
    @DisplayName("endpoint")
    class Endpoint {

        @Test
        @DisplayName("applies the default base URL")
        void defaultBaseUrl() {
            assertEquals("https://api.x.ai/v1", merged(builderFor("xai"), withKey()).get("baseUrl"));
        }

        @Test
        @DisplayName("region selects that region's URL and is not forwarded")
        void region() {
            Map<String, String> in = withKey();
            in.put("region", "cn");
            Map<String, String> out = merged(builderFor("qwen"), in);
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", out.get("baseUrl"));
            assertFalse(out.containsKey("region"));
        }

        @Test
        @DisplayName("an explicit baseUrl beats region")
        void explicitBaseUrlWins() {
            Map<String, String> in = withKey();
            in.put("region", "cn");
            in.put("baseUrl", "https://proxy.example/v1");
            assertEquals("https://proxy.example/v1", merged(builderFor("qwen"), in).get("baseUrl"));
        }

        @Test
        @DisplayName("an unknown region throws and names the valid ids")
        void unknownRegion() {
            Map<String, String> in = withKey();
            in.put("region", "mars");
            var e = assertThrows(IllegalArgumentException.class, () -> builderFor("qwen").build(in));
            assertTrue(e.getMessage().contains("cn"), e.getMessage());
        }

        @Test
        @DisplayName("a metadata-service baseUrl is still rejected by the real delegate")
        void metadataTargetRejected() {
            var real = new OpenAiCompatibleLanguageModelBuilder(OpenAiCompatibleProviders.find("xai").orElseThrow(),
                    new OpenAILanguageModelBuilder());
            Map<String, String> in = withKey();
            in.put("baseUrl", "http://169.254.169.254/v1");
            var e = assertThrows(IllegalArgumentException.class, () -> real.build(in));
            assertTrue(e.getMessage().contains("instance-metadata"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("model and key")
    class ModelAndKey {

        @Test
        @DisplayName("applies the default model; an explicit one wins")
        void model() {
            assertEquals("grok-4.7", merged(builderFor("xai"), withKey()).get("modelName"));
        }

        @Test
        @DisplayName("explicit modelName is kept")
        void explicitModel() {
            Map<String, String> in = withKey();
            in.put("modelName", "grok-4.5");
            assertEquals("grok-4.5", merged(builderFor("xai"), in).get("modelName"));
        }

        @Test
        @DisplayName("a missing apiKey throws naming the provider")
        void missingKey() {
            var e = assertThrows(IllegalArgumentException.class, () -> builderFor("moonshot").build(new HashMap<>()));
            assertTrue(e.getMessage().contains("Moonshot Kimi"), e.getMessage());
            assertTrue(e.getMessage().contains("${vault:moonshot-key}"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("preset defaults")
    class PresetDefaults {

        @Test
        @DisplayName("parameterDefaults are applied")
        void defaultsApplied() {
            Map<String, String> out = merged(builderFor("deepseek"), withKey());
            assertEquals("true", out.get("returnThinking"));
            assertEquals("true", out.get("sendThinking"));
        }

        @Test
        @DisplayName("explicit values beat parameterDefaults")
        void explicitWins() {
            Map<String, String> in = withKey();
            in.put("sendThinking", "false");
            assertEquals("false", merged(builderFor("deepseek"), in).get("sendThinking"));
        }

        @Test
        @DisplayName("a present-but-blank value counts as absent, so the preset default still applies")
        void blankValueTakesDefault() {
            Map<String, String> in = withKey();
            in.put("sendThinking", "");
            in.put("returnThinking", "  ");
            Map<String, String> out = merged(builderFor("deepseek"), in);
            assertEquals("true", out.get("sendThinking"));
            assertEquals("true", out.get("returnThinking"));
        }

        @Test
        @DisplayName("customParameters are handed to the delegate")
        void customParameters() {
            merged(builderFor("minimax"), withKey());
            assertEquals(true, custom.getValue().get("reasoning_split"));
        }

        @Test
        @DisplayName("the caller's map is not mutated")
        void callerMapUntouched() {
            Map<String, String> in = withKey();
            merged(builderFor("deepseek"), in);
            assertEquals(1, in.size());
        }
    }

    @Nested
    @DisplayName("vendor body fields and headers from the parameter catalog")
    class VendorFields {

        private Map<String, Object> body(String type, Map<String, String> in) {
            merged(builderFor(type), in);
            return custom.getValue();
        }

        @Test
        @DisplayName("a top-level body field is coerced to its catalog type and not forwarded as a parameter")
        void topLevel() {
            Map<String, String> in = withKey();
            in.put("enableThinking", " FALSE ");
            in.put("topK", "20");
            in.put("repetitionPenalty", "1.05");
            Map<String, String> out = merged(builderFor("qwen"), in);
            assertEquals(Boolean.FALSE, custom.getValue().get("enable_thinking"));
            assertEquals(20, custom.getValue().get("top_k"));
            assertEquals(1.05, custom.getValue().get("repetition_penalty"));
            assertFalse(out.containsKey("enableThinking"));
            assertFalse(out.containsKey("topK"));
        }

        @Test
        @DisplayName("a dotted wire path becomes nested objects, sharing one parent")
        void nested() {
            Map<String, String> in = withKey();
            in.put("thinkingType", "disabled");
            in.put("clearThinking", "false");
            Map<String, Object> body = body("zhipu", in);
            assertEquals(Map.of("type", "disabled", "clear_thinking", false), body.get("thinking"));
        }

        @Test
        @DisplayName("lists and JSON values are parsed; OpenRouter's reasoningEffort goes to reasoning.effort only")
        void openRouter() {
            Map<String, String> in = withKey();
            in.put("providerOrder", "anthropic, google");
            in.put("providerOnly", "[\"a,b\"]");
            in.put("providerAllowFallbacks", "false");
            in.put("plugins", "[{\"id\":\"web\",\"max_results\":3}]");
            in.put("reasoningEffort", "high");
            in.put("models", "a/b,c/d");
            Map<String, String> out = merged(builderFor("openrouter"), in);
            Map<String, Object> body = custom.getValue();
            assertEquals(Map.of("order", List.of("anthropic", "google"), "only", List.of("a,b"), "allow_fallbacks", false),
                    body.get("provider"));
            assertEquals(List.of(Map.of("id", "web", "max_results", 3)), body.get("plugins"));
            assertEquals(Map.of("effort", "high"), body.get("reasoning"));
            assertEquals(List.of("a/b", "c/d"), body.get("models"));
            assertFalse(out.containsKey("reasoningEffort"), "must not also be sent as top-level reasoning_effort");
        }

        @Test
        @DisplayName("an unusable value is dropped (and not forwarded), the rest of the body survives")
        void unusableValue() {
            Map<String, String> in = withKey();
            in.put("topK", "many");
            in.put("enableSearch", "true");
            Map<String, String> out = merged(builderFor("qwen"), in);
            assertFalse(custom.getValue().containsKey("top_k"));
            assertEquals(true, custom.getValue().get("enable_search"));
            assertFalse(out.containsKey("topK"));
        }

        @Test
        @DisplayName("the preset's customParameters are the base and a catalog field overrides them, without mutating the preset")
        void presetBase() {
            OpenAiCompatibleProvider minimax = OpenAiCompatibleProviders.find("minimax").orElseThrow();
            Map<String, Object> presetBefore = Map.copyOf(minimax.customParameters());
            assertEquals(true, body("minimax", withKey()).get("reasoning_split"));

            setUp();
            Map<String, String> in = withKey();
            in.put("reasoningSplit", "false");
            assertEquals(false, body("minimax", in).get("reasoning_split"));
            assertEquals(presetBefore, minimax.customParameters());
        }

        @Test
        @DisplayName("the user's own customParameters reach the delegate untouched, to be applied last")
        void userCustomParametersPassThrough() {
            Map<String, String> in = withKey();
            in.put("enableThinking", "false");
            in.put("customParameters", "{\"enable_thinking\":true}");
            Map<String, String> out = merged(builderFor("qwen"), in);
            assertEquals("{\"enable_thinking\":true}", out.get("customParameters"));
        }

        @Test
        @DisplayName("OpenRouter attribution keys become headers, under the user's customHeaders")
        void headers() throws Exception {
            Map<String, String> in = withKey();
            in.put("appReferer", "https://app.example");
            in.put("appTitle", "EDDI");
            in.put("customHeaders", "{\"X-Title\":\"Mine\",\"X-Other\":\"1\"}");
            Map<String, String> out = merged(builderFor("openrouter"), in);
            Map<?, ?> headers = new ObjectMapper().readValue(out.get("customHeaders"), Map.class);
            assertEquals(Map.of("HTTP-Referer", "https://app.example", "X-Title", "Mine", "X-Other", "1"), headers);
            assertFalse(out.containsKey("appReferer"));
            assertFalse(out.containsKey("appTitle"));
        }

        @Test
        @DisplayName("without attribution keys the user's customHeaders are left as written")
        void noHeaders() {
            Map<String, String> in = withKey();
            in.put("customHeaders", "{\"A\":\"1\"}");
            assertEquals("{\"A\":\"1\"}", merged(builderFor("openrouter"), in).get("customHeaders"));
        }

        @Test
        @DisplayName("recognisedParameters include the provider's catalog keys")
        void recognisedIncludesCatalog() {
            assertTrue(builderFor("qwen").recognisedParameters().contains("enableThinking"));
            assertTrue(builderFor("openrouter").recognisedParameters().contains("appReferer"));
            assertFalse(builderFor("xai").recognisedParameters().contains("enableThinking"));
        }
    }

    @Test
    @DisplayName("streaming builds through the delegate with the same merge")
    void streaming() {
        assertNotNull(builderFor("groq").buildStreaming(withKey()));
        verify(delegate).buildStreaming(params.capture(), custom.capture());
        assertEquals("https://api.groq.com/openai/v1", params.getValue().get("baseUrl"));
        assertEquals("openai/gpt-oss-120b", params.getValue().get("modelName"));
    }

    @Test
    @DisplayName("recognisedParameters adds region to the delegate's set")
    void recognised() {
        var recognised = builderFor("xai").recognisedParameters();
        assertTrue(recognised.contains("region"));
        assertTrue(recognised.contains("returnThinking"));
        assertTrue(recognised.contains("baseUrl"));
    }
}
