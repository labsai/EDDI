/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.bootstrap.LlmModule;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.StreamingConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * S1: whether a completion without a finish reason is a failure depends on the
 * provider, unless the task or the deployment says otherwise.
 */
@DisplayName("StreamPolicy — finish-reason defaults per provider")
class StreamPolicyTest {

    private static final StreamingSettings UNSET = new StreamingSettings(null, 30);

    private static LlmConfiguration.Task task(String type, Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setType(type);
        task.setParameters(parameters);
        return task;
    }

    @Test
    @DisplayName("providers that always report a finish reason are strict by default; the others lenient")
    void providerDefaults() {
        for (String strict : List.of("openai", "azure-openai", "anthropic", "bedrock", "gemini", "gemini-vertex", "mistral", "jlama")) {
            assertTrue(StreamPolicy.requireFinishReason(task(strict, Map.of()), null, UNSET), strict);
        }
        for (String lenient : List.of(LlmModule.LLM_TYPE_OLLAMA, LlmModule.LLM_TYPE_OPENAI_COMPATIBLE, LlmModule.LLM_TYPE_HUGGING_FACE,
                LlmModule.LLM_TYPE_ORACLE_GENAI)) {
            assertFalse(StreamPolicy.requireFinishReason(task(lenient, Map.of()), null, UNSET), lenient);
        }
    }

    @Test
    @DisplayName("a custom baseUrl (a gateway, a self-hosted server) is lenient by default, even for a strict type")
    void customBaseUrlIsLenient() {
        assertFalse(StreamPolicy.requireFinishReason(task("openai", Map.of("baseUrl", "http://vllm.internal:8000/v1")), null, UNSET));
    }

    @Test
    @DisplayName("an explicit setting wins in both directions: task over deployment over provider")
    void explicitSettingsWin() {
        var strictConfig = new StreamingConfig();
        strictConfig.setRequireFinishReason(true);
        var lenientConfig = new StreamingConfig();
        lenientConfig.setRequireFinishReason(false);

        assertTrue(StreamPolicy.requireFinishReason(task("ollama", Map.of()), strictConfig, UNSET), "task forces strict");
        assertFalse(StreamPolicy.requireFinishReason(task("openai", Map.of()), lenientConfig, UNSET), "task forces lenient");
        assertTrue(StreamPolicy.requireFinishReason(task("ollama", Map.of()), null, new StreamingSettings(true, 30)), "deployment strict");
        assertFalse(StreamPolicy.requireFinishReason(task("openai", Map.of()), null, new StreamingSettings(false, 30)), "deployment lenient");
        assertTrue(StreamPolicy.requireFinishReason(task("ollama", Map.of()), strictConfig, new StreamingSettings(false, 30)),
                "the task beats the deployment");
    }

    @Test
    @DisplayName("the endpoint named in the lenient warning never carries a base URL's credentials or query")
    void endpointHidesCredentials() {
        String endpoint = StreamPolicy.endpointOf(task(LlmModule.LLM_TYPE_OPENAI_COMPATIBLE,
                Map.of("baseUrl", "https://user:s3cret@gateway.example:8443/v1?api_key=abc", "modelName", "llama")));

        assertEquals(LlmModule.LLM_TYPE_OPENAI_COMPATIBLE + "@https://gateway.example:8443/llama", endpoint);
        assertFalse(endpoint.contains("s3cret"));
        assertFalse(endpoint.contains("abc"));
    }

    private static StreamingChatModel completingWithoutFinishReason(List<String> tokens) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                tokens.forEach(handler::onPartialResponse);
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(String.join("", tokens))).build());
            }
        };
    }

    @Test
    @DisplayName("a lenient endpoint's completion without a finish reason is accepted and counted; a strict one fails")
    void lenientIsCountedStrictFails() {
        var meters = new SimpleMeterRegistry();
        var lenient = new StreamPolicy(30, 0, false, null, null, false, null, "ollama/llama3");
        var bridge = new ToolLoopStreamingChatModel(completingWithoutFinishReason(List.of("Hi")), mock(ConversationEventSink.class), lenient,
                "ollama", ToolLoopStreamingChatModel.PATH_TOOL_LOOP, meters);
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build();

        assertEquals("Hi", bridge.chat(request).aiMessage().text());
        bridge.chat(request);
        assertEquals(2.0, meters.counter("eddi.llm.stream.no_finish_reason_accepted", "path", ToolLoopStreamingChatModel.PATH_TOOL_LOOP)
                .count());

        var strict = new StreamPolicy(30, 0, true, null, null, false, null, "openai/gpt");
        var strictBridge = new ToolLoopStreamingChatModel(completingWithoutFinishReason(List.of()), mock(ConversationEventSink.class), strict,
                "openai", ToolLoopStreamingChatModel.PATH_TOOL_LOOP, meters);
        assertThrows(RuntimeException.class, () -> strictBridge.chat(request));
    }

    @Test
    @DisplayName("JSON mode: the plain path forwards a JSON answer token by token; the tool loop does not")
    void jsonRoundsAreForwardedWhereSafe() {
        List<String> forwarded = new ArrayList<>();
        ConversationEventSink sink = mock(ConversationEventSink.class);
        doAnswer(invocation -> forwarded.add(invocation.getArgument(0))).when(sink).onToken(anyString());
        var policy = new StreamPolicy(30, 0, false, null, null, false, null, "test");
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).responseFormat(ResponseFormat.JSON).build();
        var tokens = List.of("{\"a\":", "1}");

        new ToolLoopStreamingChatModel(completingWithoutFinishReason(tokens), sink, policy, "test", ToolLoopStreamingChatModel.PATH_TOOL_LOOP)
                .forwardingJsonRounds().chat(request);
        assertEquals(tokens, forwarded, "the plain path's JSON answer is the client's answer: forwarded as it arrives");

        forwarded.clear();
        new ToolLoopStreamingChatModel(completingWithoutFinishReason(tokens), sink, policy, "test", ToolLoopStreamingChatModel.PATH_TOOL_LOOP)
                .chat(request);
        assertTrue(forwarded.isEmpty(), "a tool-loop JSON round may be a tool call: not forwarded");
    }
}
