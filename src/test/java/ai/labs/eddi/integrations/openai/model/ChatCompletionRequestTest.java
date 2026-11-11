/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OpenAI-compatible request: the flags it reads, the two shapes of
 * {@code user}, the chat id in {@code metadata}, and which messages it picks.
 */
class ChatCompletionRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ChatCompletionRequest parse(String json) throws Exception {
        return MAPPER.readValue(json, ChatCompletionRequest.class);
    }

    private static ChatMessage message(String role, String text) {
        return new ChatMessage(role, TextNode.valueOf(text));
    }

    private static ChatCompletionRequest withMessages(ChatMessage... messages) {
        return new ChatCompletionRequest("m", Arrays.asList(messages), null, null, null, null, null);
    }

    @Test
    void flagsDefaultToOffAndReadTheirFields() throws Exception {
        var bare = parse("{\"model\":\"agent\"}");
        assertFalse(bare.isStreaming());
        assertFalse(bare.includeUsage());
        assertFalse(bare.isStateless());

        var full = parse("{\"model\":\"agent\",\"stream\":true,\"stream_options\":{\"include_usage\":true},\"stateless\":true,"
                + "\"unknown\":1}");
        assertTrue(full.isStreaming());
        assertTrue(full.includeUsage());
        assertTrue(full.isStateless());

        assertFalse(parse("{\"stream_options\":{\"include_usage\":false}}").includeUsage());
        assertFalse(parse("{\"stream_options\":{}}").includeUsage());
    }

    @Test
    void userIsReadAsAStringOrFromOpenWebUisObject() throws Exception {
        assertEquals("u-1", parse("{\"user\":\"u-1\"}").userAsString());
        assertNull(parse("{\"user\":\" \"}").userAsString());
        assertNull(parse("{\"user\":null}").userAsString());
        assertNull(parse("{}").userAsString());
        assertEquals("ow-7", parse("{\"user\":{\"id\":\"ow-7\",\"name\":\"Ada\"}}").userAsString());
        assertNull(parse("{\"user\":{\"id\":\"\"}}").userAsString());
        assertNull(parse("{\"user\":{\"id\":7}}").userAsString());
        assertNull(parse("{\"user\":{\"name\":\"Ada\"}}").userAsString());
        assertNull(parse("{\"user\":42}").userAsString());
    }

    @Test
    void theChatIdComesFromMetadataTrimmed() throws Exception {
        assertEquals("chat-9", parse("{\"metadata\":{\"chat_id\":\" chat-9 \"}}").metadataChatId());
        assertNull(parse("{}").metadataChatId());
        assertNull(parse("{\"metadata\":\"chat-9\"}").metadataChatId());
        assertNull(parse("{\"metadata\":{}}").metadataChatId());
        assertNull(parse("{\"metadata\":{\"chat_id\":5}}").metadataChatId());
        assertNull(parse("{\"metadata\":{\"chat_id\":\"  \"}}").metadataChatId());
    }

    @Test
    void historyIsAnAssistantMessageOrASecondUserMessage() {
        assertFalse(withMessages(message("system", "s"), message("user", "hi")).hasHistory());
        assertTrue(withMessages(message("user", "hi"), message("assistant", "hello"), message("user", "more")).hasHistory());
        assertTrue(withMessages(message("user", "one"), null, message("user", "two")).hasHistory());
        assertFalse(new ChatCompletionRequest("m", null, null, null, null, null, null).hasHistory());
    }

    @Test
    void theFirstAndLastMessagesOfARoleArePicked() {
        var system1 = message("system", "s1");
        var user1 = message("user", "u1");
        var system2 = message("system", "s2");
        var user2 = message("user", "u2");
        var request = withMessages(null, system1, user1, message("assistant", "a"), system2, user2, null);
        assertSame(user1, request.firstUserMessage());
        assertSame(system1, request.firstSystemMessage());
        assertSame(user2, request.lastUserMessage());
        assertSame(system2, request.lastSystemMessage());

        var none = new ChatCompletionRequest("m", null, null, null, null, null, null);
        assertNull(none.firstUserMessage());
        assertNull(none.lastSystemMessage());
        var assistantOnly = new ChatCompletionRequest("m", List.of(message("assistant", "a")), null, null, null, null, null);
        assertNull(assistantOnly.firstSystemMessage());
        assertNull(assistantOnly.lastUserMessage());
    }
}
