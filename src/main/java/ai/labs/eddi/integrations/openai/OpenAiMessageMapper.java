/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import ai.labs.eddi.integrations.openai.model.ChatMessage;
import ai.labs.eddi.integrations.openai.model.ContentPart;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates an OpenAI {@code messages[]} array into EDDI's {@link InputData}.
 * <p>
 * <b>Only the last user message is carried across.</b> The client resends the
 * whole conversation on every request because the OpenAI protocol is stateless;
 * EDDI keeps its own memory, so replaying that history would double every turn.
 * Earlier messages are discarded deliberately, not accidentally.
 * <p>
 * The last system message becomes the {@value #CONTEXT_SYSTEM_MESSAGE} context
 * entry. It does <em>not</em> override the agent's configured system prompt —
 * agent behaviour lives in {@code langchain.json}, and a client that could
 * rewrite it would make the agent non-portable. Agent designers opt in with
 * <code>{context.openai_system_message}</code>. Open WebUI's RAG chunks arrive
 * through this same path.
 * <p>
 * Images are mapped to {@code attachment_N} context entries, which
 * {@link AttachmentContextExtractor} already understands — so they flow through
 * the existing forwarder with its vision-capability gating, byte caps and
 * SSRF-guarded fetching. No adapter-side image handling is required.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class OpenAiMessageMapper {

    private static final Logger LOGGER = Logger.getLogger(OpenAiMessageMapper.class);

    /** Context key carrying the client-supplied system message, if any. */
    public static final String CONTEXT_SYSTEM_MESSAGE = "openai_system_message";

    /** Context key naming the originating channel, for agents that branch on it. */
    public static final String CONTEXT_CHANNEL = "channel";

    /** Value of {@value #CONTEXT_CHANNEL} for requests through this adapter. */
    public static final String CHANNEL_NAME = "openai";

    private static final String DATA_URI_PREFIX = "data:";

    /**
     * MIME used when a remote image URL carries no recognisable extension.
     * Deliberately concrete: {@code image/*} passes the forwarder's
     * {@code startsWith("image/")} gate but is then handed verbatim to
     * {@code ImageContent.from(base64, mimeType)}, where providers reject it.
     */
    private static final String DEFAULT_IMAGE_MIME = "image/jpeg";

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "bmp", "image/bmp",
            "svg", "image/svg+xml");

    private final ObjectMapper objectMapper;
    private final int maxAttachmentsPerTurn;

    @Inject
    public OpenAiMessageMapper(ObjectMapper objectMapper,
            @ConfigProperty(name = "eddi.attachments.max-per-turn",
                            defaultValue = "5") int maxAttachmentsPerTurn) {
        this.objectMapper = objectMapper;
        this.maxAttachmentsPerTurn = maxAttachmentsPerTurn;
    }

    /** Raised when the request carries no user message to act on. */
    public static class NoUserMessageException extends Exception {
        public NoUserMessageException(String message) {
            super(message);
        }
    }

    /**
     * Build the {@link InputData} for one turn.
     *
     * @throws NoUserMessageException
     *             when {@code messages[]} contains no {@code role:"user"} entry —
     *             there is nothing to say to the agent
     */
    public InputData toInputData(ChatCompletionRequest request) throws NoUserMessageException {
        ChatMessage userMessage = request == null ? null : request.lastUserMessage();
        if (userMessage == null) {
            throw new NoUserMessageException(
                    "The request contains no message with role 'user'. At least one is required.");
        }

        Map<String, Context> contexts = new LinkedHashMap<>();
        contexts.put(CONTEXT_CHANNEL, new Context(Context.ContextType.string, CHANNEL_NAME));

        ChatMessage systemMessage = request.lastSystemMessage();
        String systemText = extractText(systemMessage);
        if (systemText != null && !systemText.isBlank()) {
            contexts.put(CONTEXT_SYSTEM_MESSAGE, new Context(Context.ContextType.string, systemText));
        }

        String input = userMessage.isPlainText()
                ? nullToEmpty(userMessage.textContent())
                : appendAttachments(userMessage, contexts);

        return new InputData(input, contexts);
    }

    /**
     * Extract the text of an array-form message while registering its images as
     * {@code attachment_N} contexts. Multiple text parts are joined with newlines,
     * matching how clients split long prompts.
     */
    private String appendAttachments(ChatMessage message, Map<String, Context> contexts) {
        List<String> texts = new ArrayList<>();
        int index = 0;
        int skipped = 0;

        for (ContentPart part : message.contentParts(objectMapper)) {
            if (part.isText()) {
                if (part.text() != null && !part.text().isEmpty()) {
                    texts.add(part.text());
                }
                continue;
            }
            if (!part.isImageUrl()) {
                continue; // input_audio, file, … — not supported on this path yet
            }
            if (maxAttachmentsPerTurn > 0 && index >= maxAttachmentsPerTurn) {
                skipped++;
                continue;
            }

            Map<String, Object> attachment = toAttachment(part.imageUrl().url(), index);
            if (attachment == null) {
                continue;
            }
            contexts.put(AttachmentContextExtractor.ATTACHMENT_PREFIX + index,
                    new Context(Context.ContextType.object, attachment));
            index++;
        }

        if (skipped > 0) {
            LOGGER.warnf("Dropped %d image(s) beyond the per-turn cap of %d", skipped, maxAttachmentsPerTurn);
        }
        return String.join("\n", texts);
    }

    /**
     * Build one attachment map in the shape {@link AttachmentContextExtractor}
     * expects, or {@code null} when the URL is unusable.
     */
    private Map<String, Object> toAttachment(String url, int index) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("fileName", "openai-attachment-" + index);

        if (url.regionMatches(true, 0, DATA_URI_PREFIX, 0, DATA_URI_PREFIX.length())) {
            int comma = url.indexOf(',');
            if (comma < 0) {
                // "data:image/png;base64" with no payload separator. Parsing the
                // metadata by scanning to ';' would throw here on a malformed but
                // reachable input, so bail out instead.
                LOGGER.warnf("Skipping malformed data URI at attachment index %d (no ',' separator)", index);
                return null;
            }
            String metadata = url.substring(DATA_URI_PREFIX.length(), comma);
            int semicolon = metadata.indexOf(';');
            String mime = (semicolon >= 0 ? metadata.substring(0, semicolon) : metadata).trim();
            attachment.put("mimeType", mime.isEmpty() ? DEFAULT_IMAGE_MIME : mime.toLowerCase(Locale.ROOT));
            attachment.put(AttachmentContextExtractor.FIELD_DATA, url.substring(comma + 1));
            return attachment;
        }

        attachment.put("mimeType", mimeFromUrl(url));
        attachment.put("url", url);
        return attachment;
    }

    /**
     * Guess a concrete image MIME from a URL's file extension. Query strings and
     * fragments are stripped first so {@code photo.png?v=2} still resolves.
     */
    static String mimeFromUrl(String url) {
        if (url == null) {
            return DEFAULT_IMAGE_MIME;
        }
        String path = url;
        int cut = path.indexOf('?');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        cut = path.indexOf('#');
        if (cut >= 0) {
            path = path.substring(0, cut);
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return DEFAULT_IMAGE_MIME;
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME_BY_EXTENSION.getOrDefault(extension, DEFAULT_IMAGE_MIME);
    }

    /** The text of a message in either content form, or {@code null}. */
    private String extractText(ChatMessage message) {
        if (message == null) {
            return null;
        }
        if (message.isPlainText()) {
            return message.textContent();
        }
        List<String> texts = message.contentParts(objectMapper).stream()
                .filter(ContentPart::isText)
                .map(ContentPart::text)
                .filter(text -> text != null && !text.isEmpty())
                .toList();
        return texts.isEmpty() ? null : String.join("\n", texts);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
