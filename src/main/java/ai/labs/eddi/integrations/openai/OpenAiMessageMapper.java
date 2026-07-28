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
 * Binary content parts — {@code image_url}, {@code input_audio} and
 * {@code file} — are mapped to {@code attachment_N} context entries, which
 * {@link AttachmentContextExtractor} already understands. From there the
 * existing forwarder applies capability gating (vision, audio, native document
 * support), byte caps, PDF text extraction and SSRF-guarded fetching, so the
 * adapter itself handles no media at all.
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

    /** Fallback for a file part whose type cannot be determined at all. */
    private static final String DEFAULT_FILE_MIME = "application/octet-stream";

    private static final Map<String, String> MIME_BY_EXTENSION = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("wav", "audio/wav"),
            Map.entry("mp3", "audio/mpeg"));

    /**
     * {@code input_audio.format} → MIME. The protocol carries a bare format name
     * rather than a media type, and {@code mp3} maps to {@code audio/mpeg} rather
     * than the {@code audio/mp3} a naive concatenation would produce.
     */
    private static final Map<String, String> AUDIO_MIME_BY_FORMAT = Map.of(
            "wav", "audio/wav",
            "mp3", "audio/mpeg",
            "mpeg", "audio/mpeg",
            "ogg", "audio/ogg",
            "flac", "audio/flac",
            "m4a", "audio/mp4",
            "webm", "audio/webm");

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
            if (!part.isImageUrl() && !part.isInputAudio() && !part.isFile()) {
                continue; // an unknown part type — ignore rather than fail the turn
            }
            if (maxAttachmentsPerTurn > 0 && index >= maxAttachmentsPerTurn) {
                skipped++;
                continue;
            }

            Map<String, Object> attachment = toAttachment(part, index);
            if (attachment == null) {
                continue;
            }
            contexts.put(AttachmentContextExtractor.ATTACHMENT_PREFIX + index,
                    new Context(Context.ContextType.object, attachment));
            index++;
        }

        if (skipped > 0) {
            LOGGER.warnf("Dropped %d attachment(s) beyond the per-turn cap of %d", skipped, maxAttachmentsPerTurn);
        }
        return String.join("\n", texts);
    }

    /**
     * Build one attachment map in the shape {@link AttachmentContextExtractor}
     * expects, or {@code null} when the part carries nothing usable.
     */
    private Map<String, Object> toAttachment(ContentPart part, int index) {
        if (part.isImageUrl()) {
            return fromImageUrl(part.imageUrl().url(), index);
        }
        if (part.isInputAudio()) {
            return fromInputAudio(part.inputAudio(), index);
        }
        return fromFile(part.file(), index);
    }

    private Map<String, Object> fromImageUrl(String url, int index) {
        if (url.regionMatches(true, 0, DATA_URI_PREFIX, 0, DATA_URI_PREFIX.length())) {
            return fromDataUri(url, DEFAULT_IMAGE_MIME, "openai-attachment-" + index, index);
        }
        Map<String, Object> attachment = newAttachment("openai-attachment-" + index);
        attachment.put("mimeType", mimeFromUrl(url));
        attachment.put("url", url);
        return attachment;
    }

    /**
     * Audio is the one payload the protocol sends as <b>raw base64</b> rather than
     * a {@code data:} URI, with the type in a separate {@code format} field.
     */
    private Map<String, Object> fromInputAudio(ContentPart.InputAudio audio, int index) {
        String format = audio.format() == null ? "" : audio.format().trim().toLowerCase(Locale.ROOT);
        String mime = AUDIO_MIME_BY_FORMAT.get(format);
        if (mime == null) {
            // An unrecognised format is still probably audio; guessing
            // "audio/<format>" beats dropping the payload, and the forwarder's
            // capability gate has the final say.
            mime = format.isEmpty() ? "audio/wav" : "audio/" + format;
            LOGGER.debugf("Unrecognised input_audio format '%s' at index %d, using %s",
                    audio.format(), index, mime);
        }
        Map<String, Object> attachment = newAttachment(
                "openai-attachment-" + index + (format.isEmpty() ? "" : "." + format));
        attachment.put("mimeType", mime);
        attachment.put(AttachmentContextExtractor.FIELD_DATA, audio.data());
        return attachment;
    }

    /**
     * File parts carry a full {@code data:} URI in {@code file_data}. A part that
     * only references {@code file_id} cannot be resolved — that is the OpenAI Files
     * API, which EDDI does not implement — so it is skipped with a warning rather
     * than silently producing an empty attachment.
     */
    private Map<String, Object> fromFile(ContentPart.FilePart file, int index) {
        String fileName = file.filename() != null && !file.filename().isBlank()
                ? file.filename().trim()
                : "openai-attachment-" + index;

        if (file.fileData() == null || file.fileData().isBlank()) {
            if (file.fileId() != null && !file.fileId().isBlank()) {
                LOGGER.warnf("Skipping file part '%s' at index %d: it references file_id '%s', "
                        + "and EDDI does not implement the OpenAI Files API. Send file_data inline instead.",
                        fileName, index, file.fileId());
            } else {
                LOGGER.warnf("Skipping file part '%s' at index %d: no file_data.", fileName, index);
            }
            return null;
        }

        // The declared filename is the better MIME hint here: a generic
        // "application/octet-stream" data URI plus "report.pdf" should still
        // reach the PDF path.
        String fallbackMime = mimeFromFileName(fileName, DEFAULT_FILE_MIME);
        return fromDataUri(file.fileData(), fallbackMime, fileName, index);
    }

    /**
     * Parse {@code data:<mime>[;base64],<payload>} into an attachment map. Returns
     * {@code null} when the URI has no payload separator.
     */
    private Map<String, Object> fromDataUri(String uri, String fallbackMime, String fileName, int index) {
        int comma = uri.indexOf(',');
        if (comma < 0) {
            // "data:application/pdf;base64" with no payload separator. Deriving the
            // MIME by scanning to ';' would throw on this malformed but reachable
            // input, so bail out instead.
            LOGGER.warnf("Skipping malformed data URI at attachment index %d (no ',' separator)", index);
            return null;
        }
        String metadata = uri.substring(DATA_URI_PREFIX.length(), comma);
        int semicolon = metadata.indexOf(';');
        String mime = (semicolon >= 0 ? metadata.substring(0, semicolon) : metadata).trim();

        Map<String, Object> attachment = newAttachment(fileName);
        // A declared but useless type loses to the filename-derived one.
        attachment.put("mimeType", mime.isEmpty() || DEFAULT_FILE_MIME.equalsIgnoreCase(mime)
                ? fallbackMime
                : mime.toLowerCase(Locale.ROOT));
        attachment.put(AttachmentContextExtractor.FIELD_DATA, uri.substring(comma + 1));
        return attachment;
    }

    private static Map<String, Object> newAttachment(String fileName) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("fileName", fileName);
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
        return mimeFromFileName(path, DEFAULT_IMAGE_MIME);
    }

    /** Extension-derived MIME, or {@code fallback} when unrecognised. */
    static String mimeFromFileName(String name, String fallback) {
        if (name == null) {
            return fallback;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return fallback;
        }
        return MIME_BY_EXTENSION.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), fallback);
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
