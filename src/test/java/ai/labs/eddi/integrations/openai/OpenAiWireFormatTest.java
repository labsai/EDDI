/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.integrations.openai.model.ChatCompletionChunk;
import ai.labs.eddi.integrations.openai.model.ChatCompletionResponse;
import ai.labs.eddi.integrations.openai.model.ChatMessage;
import ai.labs.eddi.integrations.openai.model.Choice;
import ai.labs.eddi.integrations.openai.model.ChunkChoice;
import ai.labs.eddi.integrations.openai.model.ModelObject;
import ai.labs.eddi.integrations.openai.model.ModelsResponse;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import ai.labs.eddi.integrations.openai.model.TokenUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Serialization tests for the response DTOs.
 * <p>
 * These assert the exact JSON an OpenAI client receives. Snake-cased fields
 * ({@code finish_reason}, {@code owned_by}) come from {@code @JsonProperty} on
 * record components — a mapping that silently degrades to the camelCase name if
 * the annotation is dropped or a reflection-free serializer is enabled, and
 * which no other test covers on the non-streaming path.
 */
class OpenAiWireFormatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode serialize(Object value) throws Exception {
        return objectMapper.readTree(objectMapper.writeValueAsString(value));
    }

    @Test
    void chatCompletionResponse_matchesTheOpenAiShape() throws Exception {
        var response = ChatCompletionResponse.of("chatcmpl-eddi-1", "support-a3f9c1", 1_753_500_000L,
                new Choice(0, ChatMessage.assistant("Hello there", objectMapper), Choice.FINISH_STOP), null);

        JsonNode json = serialize(response);

        assertEquals("chatcmpl-eddi-1", json.get("id").asText());
        assertEquals("chat.completion", json.get("object").asText());
        assertEquals(1_753_500_000L, json.get("created").asLong());
        assertEquals("support-a3f9c1", json.get("model").asText());
        assertEquals(0, json.at("/choices/0/index").asInt());
        assertEquals("assistant", json.at("/choices/0/message/role").asText());
        assertEquals("Hello there", json.at("/choices/0/message/content").asText());
    }

    @Test
    void responseCarriesNoFieldsOutsideTheOpenAiSchema() throws Exception {
        // Asserting only that expected fields are PRESENT let a stray one ship:
        // Jackson treats ChatMessage.isPlainText() as a bean property, so every
        // assistant message went out with a "plainText" field. Harmless to
        // clients, wrong on the wire, and invisible to a presence-only check.
        var response = ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant("hi", objectMapper), Choice.FINISH_STOP), null);

        JsonNode json = serialize(response);

        assertEquals(Set.of("id", "object", "created", "model", "choices"), fieldNames(json));
        assertEquals(Set.of("index", "message", "finish_reason"), fieldNames(json.at("/choices/0")));
        assertEquals(Set.of("role", "content"), fieldNames(json.at("/choices/0/message")));
    }

    @Test
    void streamingChunkCarriesNoExtraFields() throws Exception {
        JsonNode content = serialize(ChatCompletionChunk.of("id", "m", 1L, ChunkChoice.content("hi")));

        assertEquals(Set.of("id", "object", "created", "model", "choices"), fieldNames(content));
        assertEquals(Set.of("index", "delta"), fieldNames(content.at("/choices/0")));
        // A content frame carries content alone — role is null and so omitted,
        // which is what keeps the incremental deltas minimal.
        assertEquals(Set.of("content"), fieldNames(content.at("/choices/0/delta")));

        JsonNode opening = serialize(ChatCompletionChunk.of("id", "m", 1L, ChunkChoice.role()));
        assertEquals(Set.of("role"), fieldNames(opening.at("/choices/0/delta")));

        JsonNode terminal = serialize(ChatCompletionChunk.of("id", "m", 1L, ChunkChoice.finish("stop")));
        assertEquals(Set.of("index", "delta", "finish_reason"), fieldNames(terminal.at("/choices/0")));
        assertTrue(terminal.at("/choices/0/delta").isEmpty(), "the terminal delta must serialize as {}");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void finishReasonIsSnakeCased() throws Exception {
        var response = ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant("hi", objectMapper), Choice.FINISH_STOP), null);

        JsonNode json = serialize(response);

        assertEquals("stop", json.at("/choices/0/finish_reason").asText());
        assertTrue(json.at("/choices/0/finishReason").isMissingNode(),
                "camelCase would be ignored by every OpenAI client");
    }

    @Test
    void usageIsOmittedWhenTheAgentCalledNoModel() throws Exception {
        JsonNode json = serialize(ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant("hi", objectMapper), Choice.FINISH_STOP), null));

        assertFalse(json.has("usage"),
                "a rule-based agent spends no tokens; zeros would render as a factual '0 tokens'");
    }

    @Test
    void usageIsSnakeCasedWhenPresent() throws Exception {
        JsonNode json = serialize(ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant("hi", objectMapper), Choice.FINISH_STOP),
                new TokenUsage(12L, 34L, 46L)));

        assertEquals(Set.of("prompt_tokens", "completion_tokens", "total_tokens"), fieldNames(json.get("usage")));
        assertEquals(12L, json.at("/usage/prompt_tokens").asLong());
        assertEquals(34L, json.at("/usage/completion_tokens").asLong());
        assertEquals(46L, json.at("/usage/total_tokens").asLong());
    }

    @Test
    void usageOnlyChunkCarriesEmptyChoices() throws Exception {
        // OpenAI's trailing usage frame has choices: [] — a client iterating deltas
        // must be able to skip it without special-casing, so it must carry no
        // content, and the empty array must survive NON_NULL serialization.
        JsonNode json = serialize(ChatCompletionChunk.usageOnly("id", "m", 1L, new TokenUsage(1L, 2L, 3L)));

        assertEquals(Set.of("id", "object", "created", "model", "choices", "usage"), fieldNames(json));
        assertTrue(json.get("choices").isArray() && json.get("choices").isEmpty(),
                "the usage frame must carry an empty choices array, not a delta");
        assertEquals(3L, json.at("/usage/total_tokens").asLong());
    }

    @Test
    void ordinaryChunksCarryNoUsageField() throws Exception {
        JsonNode json = serialize(ChatCompletionChunk.of("id", "m", 1L, ChunkChoice.content("hi")));

        assertFalse(json.has("usage"), "usage belongs only on the trailing frame");
    }

    @Test
    void assistantMessageSurvivesMultilineAndUnicodeText() throws Exception {
        String text = "line1\nline2 \"q\" \\ Grüße 👋";
        JsonNode json = serialize(ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant(text, objectMapper), Choice.FINISH_STOP), null));

        assertEquals(text, json.at("/choices/0/message/content").asText());
    }

    @Test
    void nullAssistantTextBecomesEmptyString_notNull() throws Exception {
        JsonNode json = serialize(ChatCompletionResponse.of("id", "m", 1L,
                new Choice(0, ChatMessage.assistant(null, objectMapper), Choice.FINISH_STOP), null));

        assertEquals("", json.at("/choices/0/message/content").asText());
    }

    @Test
    void modelsResponse_matchesTheOpenAiShape() throws Exception {
        JsonNode json = serialize(ModelsResponse.of(List.of(ModelObject.of("support-a3f9c1", 1_753_500_000L))));

        assertEquals("list", json.get("object").asText());
        assertEquals("support-a3f9c1", json.at("/data/0/id").asText());
        assertEquals("model", json.at("/data/0/object").asText());
        assertEquals("eddi", json.at("/data/0/owned_by").asText());
        assertTrue(json.at("/data/0/ownedBy").isMissingNode());
    }

    @Test
    void errorResponse_matchesTheOpenAiEnvelope() throws Exception {
        JsonNode json = serialize(OpenAiErrorResponse.of("No such model.",
                OpenAiErrorResponse.TYPE_INVALID_REQUEST, OpenAiErrorResponse.CODE_MODEL_NOT_FOUND));

        assertEquals("No such model.", json.at("/error/message").asText());
        assertEquals("invalid_request_error", json.at("/error/type").asText());
        assertEquals("model_not_found", json.at("/error/code").asText());
        assertFalse(json.at("/error").has("param"), "an unattributed error omits param");
    }

    @Test
    void errorResponse_omitsNullCode() throws Exception {
        JsonNode json = serialize(OpenAiErrorResponse.of("Busy.", OpenAiErrorResponse.TYPE_RATE_LIMIT, null));

        assertEquals("rate_limit_exceeded", json.at("/error/type").asText());
        assertFalse(json.at("/error").has("code"));
    }
}
