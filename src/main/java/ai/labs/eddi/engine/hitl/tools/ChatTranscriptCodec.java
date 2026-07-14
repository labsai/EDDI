/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes the in-flight LLM tool-loop transcript for a durable HITL tool
 * pause, and restores it on resume.
 * <p>
 * Wraps langchain4j's
 * {@link ChatMessageSerializer}/{@link ChatMessageDeserializer} (Jackson codec
 * with mixins for AiMessage + ToolExecutionRequest, ToolExecutionResultMessage
 * and multimodal contents) behind a size cap and a typed failure so callers can
 * fall back to history reconstruction when a stored transcript cannot be parsed
 * (e.g. after a langchain4j upgrade mid-pause).
 */
public class ChatTranscriptCodec {
    private static final Logger LOGGER = Logger.getLogger(ChatTranscriptCodec.class);

    /** Serialization outcome — {@code omitted=true} means the cap was exceeded. */
    public record CodecResult(String json, boolean omitted) {
    }

    /**
     * Thrown when a stored transcript cannot be restored; callers must fall back.
     */
    public static class TranscriptCodecException extends Exception {
        public TranscriptCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public CodecResult serialize(List<ChatMessage> messages, int maxBytes) {
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                LOGGER.warnf("HITL tool-pause transcript exceeds cap (%d bytes) — omitting; resume will use fallback reconstruction",
                        maxBytes);
                return new CodecResult(null, true);
            }
            return new CodecResult(json, false);
        } catch (Exception e) {
            LOGGER.errorf(e, "HITL tool-pause transcript serialization failed — omitting; resume will use fallback reconstruction");
            return new CodecResult(null, true);
        }
    }

    public List<ChatMessage> deserialize(String json) throws TranscriptCodecException {
        try {
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(json);
            if (messages == null || messages.isEmpty()) {
                throw new TranscriptCodecException("transcript deserialized to empty list", null);
            }
            return messages;
        } catch (TranscriptCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new TranscriptCodecException("failed to restore HITL tool-pause transcript: " + e.getMessage(), e);
        }
    }
}
