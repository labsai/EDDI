/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
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
            assertThrows(RuntimeException.class, () -> real.build(in));
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
