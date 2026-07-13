/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.AttachmentExtractionException;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENT_ERRORS;
import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENT_EXTRACTS;
import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * The single place attachments become langchain4j {@link Content} on the
 * outgoing user message. Replaces the image-only
 * {@code MultimodalMessageEnhancer} and the divergent handling in
 * {@code convertMessage}.
 * <p>
 * Per attachment it resolves bytes from any source (stored blob, URL, inline
 * base64) under uniform per-file and aggregate byte caps, gates on
 * {@link ModelCapabilityService}, and emits the right content:
 * <ul>
 * <li>{@code image/*} → {@link ImageContent} when the model has vision (URL
 * kept as-is when the provider fetches URLs, otherwise downloaded and inlined),
 * else a metadata note.</li>
 * <li>{@code application/pdf} → native {@link PdfFileContent} when the model
 * supports documents, else PDFBox text extraction inlined as
 * {@link TextContent}.</li>
 * <li>text-like ({@code text/*}, JSON, XML, CSV, YAML) → decoded and inlined
 * (no capability required).</li>
 * <li>{@code audio/*} → {@link AudioContent} when supported, else a note.</li>
 * <li>anything else → a metadata note pointing at the {@code readAttachment}
 * tool.</li>
 * </ul>
 * Extracted text is persisted to {@code attachments:extracts} (for history
 * stitching) and every drop/skip/gate is appended to {@code attachments:errors}
 * — never silent.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class AttachmentForwarder {

    private static final Logger LOGGER = Logger.getLogger(AttachmentForwarder.class);

    private final IAttachmentStore attachmentStore;
    private final ModelCapabilityService capabilityService;
    private final AttachmentTextExtractor textExtractor;
    private final SafeHttpClient httpClient;
    private final long maxForwardBytes;
    private final long maxAggregateBytes;
    private final Counter forwardedCounter;
    private final Counter errorsCounter;

    @Inject
    public AttachmentForwarder(IAttachmentStore attachmentStore,
            ModelCapabilityService capabilityService,
            AttachmentTextExtractor textExtractor,
            SafeHttpClient httpClient,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.attachments.max-forward-bytes",
                            defaultValue = "10485760") long maxForwardBytes,
            @ConfigProperty(name = "eddi.attachments.max-forward-aggregate-bytes",
                            defaultValue = "20971520") long maxAggregateBytes) {
        this.attachmentStore = attachmentStore;
        this.capabilityService = capabilityService;
        this.textExtractor = textExtractor;
        this.httpClient = httpClient;
        this.forwardedCounter = meterRegistry != null ? meterRegistry.counter("eddi.attachment.forwarded") : null;
        this.errorsCounter = meterRegistry != null ? meterRegistry.counter("eddi.attachment.errors") : null;
        this.maxForwardBytes = maxForwardBytes;
        this.maxAggregateBytes = maxAggregateBytes;
    }

    /**
     * Enhance the last {@link UserMessage} in {@code messages} (modified in place)
     * with content for the current step's attachments, gated on the model's
     * capabilities. Extracted text and error notes are persisted to step data.
     *
     * @param messages
     *            the outgoing chat messages (modified in place)
     * @param memory
     *            conversation memory for the current turn
     * @param provider
     *            the resolved LLM provider (e.g. {@code openai})
     * @param model
     *            the resolved model name
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model) {
        forward(messages, memory, provider, model,
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.AUTO);
    }

    /**
     * Overload honoring per-task multimodal overrides (from
     * {@code LlmConfiguration.Task.multimodal}). {@code AUTO} defers to the
     * capability service's deployment/built-in defaults.
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model,
                        ModelCapabilityService.Support visionOverride,
                        ModelCapabilityService.Support documentsOverride,
                        ModelCapabilityService.Support audioOverride) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Attachment> attachments = readAttachments(memory);
        if (attachments.isEmpty()) {
            return;
        }
        int lastUserIdx = lastUserMessageIndex(messages);
        if (lastUserIdx < 0) {
            return;
        }

        UserMessage original = (UserMessage) messages.get(lastUserIdx);
        List<Content> contents = new ArrayList<>(original.contents());

        List<String> extracts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long[] aggregate = {0L};
        int added = 0;

        for (Attachment att : attachments) {
            Content content = process(att, memory.getConversationId(), provider, model, aggregate, extracts, errors,
                    visionOverride, documentsOverride, audioOverride);
            if (content != null) {
                contents.add(content);
                added++;
            }
        }

        if (added > 0) {
            messages.set(lastUserIdx, UserMessage.from(contents));
            LOGGER.debugf("Forwarded %d attachment content item(s) to the LLM", added);
        }
        recordMetrics(added, errors.size());
        persist(memory.getCurrentStep(), extracts, errors);
    }

    private void recordMetrics(int forwarded, int errored) {
        if (forwardedCounter != null && forwarded > 0) {
            forwardedCounter.increment(forwarded);
        }
        if (errorsCounter != null && errored > 0) {
            errorsCounter.increment(errored);
        }
    }

    private Content process(Attachment att, String conversationId, String provider, String model,
                            long[] aggregate, List<String> extracts, List<String> errors,
                            ModelCapabilityService.Support visionOverride,
                            ModelCapabilityService.Support documentsOverride,
                            ModelCapabilityService.Support audioOverride) {
        String mime = att.getMimeType() == null ? "" : att.getMimeType().toLowerCase(Locale.ROOT);
        String name = att.getFileName() != null ? att.getFileName() : "unnamed";

        if (att.getContentSource() == Attachment.ContentSource.NONE) {
            errors.add("Attachment '" + name + "' has no content and was not forwarded.");
            return null;
        }

        // Fast path: an image the provider can fetch by URL — no download needed.
        boolean isImage = mime.startsWith("image/");
        boolean isAudio = mime.startsWith("audio/");
        boolean isPdf = mime.startsWith("application/pdf");

        if (isImage && att.getContentSource() == Attachment.ContentSource.URL
                && capabilityService.supportsVision(provider, model, visionOverride)
                && capabilityService.supportsImageUrl(provider, model)) {
            try {
                return ImageContent.from(URI.create(att.getUrl()));
            } catch (Exception e) {
                errors.add("Image '" + name + "' URL could not be attached: " + e.getMessage());
                return null;
            }
        }

        // Everything else needs the bytes in hand. A skip (cap/load/fetch failure)
        // still leaves a note the LLM can relay, in addition to the error record.
        byte[] bytes;
        try {
            bytes = resolveBytes(att, conversationId, aggregate);
        } catch (ForwardSkipException e) {
            errors.add(e.getMessage());
            return TextContent.from(e.getMessage());
        }

        if (isImage) {
            if (!capabilityService.supportsVision(provider, model, visionOverride)) {
                return note(errors, name, mime, att.getSizeBytes(),
                        "model does not support images");
            }
            return ImageContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType());
        }

        if (isPdf) {
            if (capabilityService.supportsDocuments(provider, model, documentsOverride)) {
                return PdfFileContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType());
            }
            return extractInline(bytes, att.getMimeType(), name, extracts, errors,
                    "PDF text-extracted (model has no native document support)");
        }

        if (textExtractor.canExtractText(att.getMimeType())) {
            return extractInline(bytes, att.getMimeType(), name, extracts, errors, "inlined as text");
        }

        if (isAudio) {
            if (!capabilityService.supportsAudio(provider, model, audioOverride)) {
                return note(errors, name, mime, att.getSizeBytes(), "model does not support audio");
            }
            return AudioContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType());
        }

        return note(errors, name, mime, att.getSizeBytes(), "unsupported type");
    }

    /**
     * Resolve the attachment bytes from its source, enforcing the per-file and
     * running aggregate caps.
     */
    private byte[] resolveBytes(Attachment att, String conversationId, long[] aggregate) throws ForwardSkipException {
        String name = att.getFileName() != null ? att.getFileName() : "unnamed";
        byte[] bytes;
        switch (att.getContentSource()) {
            case STORED -> {
                try {
                    bytes = attachmentStore.load(att.getStorageRef(), conversationId);
                } catch (IAttachmentStore.AttachmentStoreException e) {
                    throw new ForwardSkipException("Stored attachment '" + name + "' could not be loaded: " + e.getMessage());
                }
            }
            case URL -> bytes = download(att.getUrl(), name);
            case BASE64 -> {
                try {
                    bytes = Base64.getDecoder().decode(att.getBase64Data());
                } catch (IllegalArgumentException e) {
                    throw new ForwardSkipException("Attachment '" + name + "' has invalid base64 data.");
                }
            }
            default -> throw new ForwardSkipException("Attachment '" + name + "' has no content.");
        }

        if (bytes.length > maxForwardBytes) {
            throw new ForwardSkipException(("Attachment '%s' (%d bytes) exceeds the per-file forward limit of %d bytes "
                    + "and was not sent. Use the readAttachment tool to access it.")
                    .formatted(name, bytes.length, maxForwardBytes));
        }
        if (aggregate[0] + bytes.length > maxAggregateBytes) {
            throw new ForwardSkipException(("Attachment '%s' skipped: the per-request attachment budget of %d bytes "
                    + "was reached.").formatted(name, maxAggregateBytes));
        }
        aggregate[0] += bytes.length;
        return bytes;
    }

    private byte[] download(String url, String name) throws ForwardSkipException {
        try {
            validateUrl(url);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<byte[]> response = httpClient.sendValidated(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new ForwardSkipException("Attachment '" + name + "' download failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (ForwardSkipException e) {
            throw e;
        } catch (Exception e) {
            throw new ForwardSkipException("Attachment '" + name + "' could not be fetched: " + e.getMessage());
        }
    }

    private Content extractInline(byte[] bytes, String mime, String name,
                                  List<String> extracts, List<String> errors, String outcome) {
        try {
            String text = textExtractor.extractText(bytes, mime);
            if (text == null || text.isBlank()) {
                return note(errors, name, mime, bytes.length, "no extractable text");
            }
            extracts.add(name + ": " + text);
            return TextContent.from("[Attachment " + name + " (" + mime + ") — " + outcome + "]\n" + text);
        } catch (AttachmentExtractionException e) {
            return note(errors, name, mime, bytes.length, "text extraction failed: " + e.getMessage());
        }
    }

    private Content note(List<String> errors, String name, String mime, long sizeBytes, String reason) {
        String msg = "[Attachment: %s (%s, %d bytes) — not forwarded: %s. Use the readAttachment tool to read it.]"
                .formatted(name, mime, sizeBytes, reason);
        errors.add(msg);
        return TextContent.from(msg);
    }

    private List<Attachment> readAttachments(IConversationMemory memory) {
        // Exact-match read: getLatestData is a prefix scan, and ATTACHMENTS
        // ("attachments")
        // is a prefix of the attachments:extracts / attachments:errors keys this
        // forwarder
        // persists — a prefix read would return the wrong entry on a second forwarder
        // invocation in the same step.
        IData<List<?>> data = memory.getCurrentStep().getData(ATTACHMENTS);
        if (data == null || data.getResult() == null) {
            return List.of();
        }
        List<Attachment> attachments = new ArrayList<>();
        for (Object o : data.getResult()) {
            if (o instanceof Attachment a) {
                attachments.add(a);
            }
        }
        return attachments;
    }

    private static int lastUserMessageIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    private void persist(IWritableConversationStep step, List<String> extracts, List<String> errors) {
        if (!extracts.isEmpty()) {
            Data<List<String>> data = new Data<>(ATTACHMENT_EXTRACTS.key(), extracts);
            data.setPublic(false);
            step.storeData(data);
        }
        if (!errors.isEmpty()) {
            List<String> merged = new ArrayList<>();
            IData<List<?>> existing = step.getLatestData(ATTACHMENT_ERRORS.key());
            if (existing != null && existing.getResult() != null) {
                existing.getResult().forEach(e -> merged.add(String.valueOf(e)));
            }
            merged.addAll(errors);
            Data<List<String>> data = new Data<>(ATTACHMENT_ERRORS.key(), merged);
            data.setPublic(false);
            step.storeData(data);
        }
    }

    /** Internal signal that a single attachment should be skipped with a note. */
    private static final class ForwardSkipException extends Exception {
        ForwardSkipException(String message) {
            super(message);
        }
    }
}
