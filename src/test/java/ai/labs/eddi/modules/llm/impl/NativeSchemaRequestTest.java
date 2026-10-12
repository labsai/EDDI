/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * R4: the native schema reaches the outgoing {@link ChatRequest} (per request,
 * through the same executor every non-tool turn uses) for Gemini and OpenAI,
 * and is absent for a provider that cannot enforce it.
 */
@DisplayName("Native response schema on the outgoing request (R4)")
class NativeSchemaRequestTest {

    private static final String SCHEMA = "{\"type\":\"object\",\"required\":[\"htmlResponseText\"],"
            + "\"properties\":{\"htmlResponseText\":{\"type\":\"string\"}}}";

    private static ChatRequest sent(String provider) throws Exception {
        var captured = new ArrayList<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                captured.add(request);
                return ChatResponse.builder().aiMessage(AiMessage.from("{\"htmlResponseText\":\"hi\"}")).build();
            }
        };
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType(provider);
        new LegacyChatExecutor().execute(model, List.of(UserMessage.from("hi")), task, JsonResponseFormatPolicy.of(true, provider, null, SCHEMA));
        assertEquals(1, captured.size());
        return captured.getFirst();
    }

    @Test
    @DisplayName("gemini request carries the schema")
    void gemini() throws Exception {
        assertNotNull(sent("gemini").responseFormat().jsonSchema());
    }

    @Test
    @DisplayName("openai request carries the schema")
    void openai() throws Exception {
        var schema = sent("openai").responseFormat().jsonSchema();
        assertNotNull(schema);
        assertEquals("response", schema.name());
    }

    @Test
    @DisplayName("azure-openai request carries the schema")
    void azure() throws Exception {
        assertNotNull(sent("azure-openai").responseFormat().jsonSchema());
    }

    @Test
    @DisplayName("a request to a provider without native schema support carries no schema")
    void absentForOthers() throws Exception {
        // groq accepts schemaless JSON, so a format is sent — but never the schema.
        assertNull(sent("groq").responseFormat().jsonSchema());
    }
}
