/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import dev.langchain4j.data.message.AiMessage;
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

    /**
     * Serializes a single message within {@code maxBytes}, or returns null.
     * <p>
     * Truncated JSON would not parse, so an over-cap {@link AiMessage} is retried
     * with its bulk removed — first {@code text}, then {@code thinking} — keeping
     * the tool calls and provider attributes, which are what a resume needs. Null
     * rather than throwing: callers treat it as "nothing persisted".
     *
     * See {@code PendingToolCallBatch#gatingAssistantMessageJson} for why it
     * exists.
     */
    public String serializeMessage(ChatMessage message, int maxBytes) {
        if (message == null) {
            return null;
        }
        try {
            String json = withinCap(message, maxBytes);
            if (json != null) {
                return json;
            }
            if (message instanceof AiMessage ai) {
                json = withinCap(ai.toBuilder().text(null).build(), maxBytes);
                if (json != null) {
                    LOGGER.warnf("HITL tool-pause gating message exceeds cap (%d bytes) — persisting it without its "
                            + "text so the resume keeps the provider fields; the narration is still in interimText",
                            maxBytes);
                    return json;
                }
                json = withinCap(ai.toBuilder().text(null).thinking(null).build(), maxBytes);
                if (json != null) {
                    LOGGER.warnf("HITL tool-pause gating message exceeds cap (%d bytes) — persisting its tool calls and "
                            + "provider attributes only", maxBytes);
                    return json;
                }
            }
            LOGGER.warnf("HITL tool-pause gating message exceeds cap (%d bytes) even reduced — omitting; a fallback "
                    + "resume will reconstruct it from the call list", maxBytes);
            return null;
        } catch (Exception e) {
            LOGGER.errorf(e, "HITL tool-pause gating message serialization failed — omitting");
            return null;
        }
    }

    /** The serialized message when it fits {@code maxBytes}, else null. */
    private String withinCap(ChatMessage message, int maxBytes) {
        String json = ChatMessageSerializer.messageToJson(message);
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return null;
        }
        return json;
    }

    /**
     * Restores a message written by {@link #serializeMessage}, or null when there
     * is nothing to restore or it does not parse.
     */
    public ChatMessage deserializeMessage(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return ChatMessageDeserializer.messageFromJson(json);
        } catch (Exception e) {
            LOGGER.warnf("HITL tool-pause gating message could not be restored (%s) — reconstructing from the call list",
                    e.getMessage());
            return null;
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
