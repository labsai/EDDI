/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;

/**
 * Enhances the last user message in a ChatMessage list with multimodal content
 * from conversation memory attachments.
 * <p>
 * This bridges the gap between the attachment pipeline (which stores
 * {@link Attachment} objects in memory) and langchain4j's multimodal content
 * types ({@link ImageContent}, etc.).
 * <p>
 * Currently supports:
 * <ul>
 * <li>{@code image/*} → {@link ImageContent} (via URL or Base64)</li>
 * </ul>
 * <p>
 * Future content types (PDF, audio, video) can be added as langchain4j
 * providers expand their multimodal support.
 *
 * @since 6.0.0
 */
final class MultimodalMessageEnhancer {

    private static final Logger LOGGER = Logger.getLogger(MultimodalMessageEnhancer.class);

    private MultimodalMessageEnhancer() {
        // non-instantiable utility
    }

    /**
     * If the current conversation step contains attachments, replace the last
     * {@link UserMessage} in the messages list with a multimodal version that
     * includes both the original text and the attachment content.
     * <p>
     * Messages list is modified in-place. If there are no attachments or no
     * UserMessage in the list, nothing happens.
     *
     * @param messages
     *            the chat message list to enhance (modified in-place)
     * @param memory
     *            conversation memory to read attachments from
     */
    static void enhanceLastUserMessage(List<ChatMessage> messages, IConversationMemory memory) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // Find attachments in the current step
        IData<List<?>> attachmentData = memory.getCurrentStep().getLatestData(ATTACHMENTS);
        if (attachmentData == null || attachmentData.getResult() == null || attachmentData.getResult().isEmpty()) {
            return;
        }

        List<?> rawAttachments = attachmentData.getResult();
        List<Attachment> attachments = new ArrayList<>();
        for (Object obj : rawAttachments) {
            if (obj instanceof Attachment att) {
                attachments.add(att);
            }
        }

        if (attachments.isEmpty()) {
            return;
        }

        // Find the last UserMessage in the list
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUserIdx = i;
                break;
            }
        }

        if (lastUserIdx < 0) {
            return;
        }

        UserMessage originalMessage = (UserMessage) messages.get(lastUserIdx);

        // Build multimodal content list: original text content + attachment content
        List<Content> contentList = new ArrayList<>(originalMessage.contents());

        int imagesAdded = 0;
        for (Attachment att : attachments) {
            Content content = convertToContent(att);
            if (content != null) {
                contentList.add(content);
                imagesAdded++;
            }
        }

        if (imagesAdded > 0) {
            messages.set(lastUserIdx, UserMessage.from(contentList));
            LOGGER.debugf("Enhanced user message with %d multimodal attachment(s)", imagesAdded);
        }
    }

    /**
     * Convert an Attachment to a langchain4j Content object based on MIME type and
     * content source.
     *
     * @return the Content object, or null if the attachment type is not supported
     */
    private static Content convertToContent(Attachment attachment) {
        String mimeType = attachment.getMimeType();
        if (mimeType == null) {
            return null;
        }

        // Image attachments → ImageContent
        if (mimeType.startsWith("image/")) {
            return convertImageAttachment(attachment);
        }

        // For non-image types, add a text description so the LLM knows an attachment
        // was present (metadata-only forwarding)
        return TextContent.from(String.format("[Attachment: %s (%s, %d bytes)]",
                attachment.getFileName() != null ? attachment.getFileName() : "unnamed",
                mimeType,
                attachment.getSizeBytes()));
    }

    /**
     * Convert an image attachment to ImageContent based on its content source.
     */
    private static Content convertImageAttachment(Attachment attachment) {
        return switch (attachment.getContentSource()) {
            case URL -> {
                try {
                    yield ImageContent.from(URI.create(attachment.getUrl()));
                } catch (Exception e) {
                    LOGGER.warnf("Failed to create ImageContent from URL '%s': %s",
                            attachment.getUrl(), e.getMessage());
                    yield null;
                }
            }
            case BASE64 -> {
                try {
                    // Use data URI format: data:<mimeType>;base64,<data>
                    // ImageContent.from(String) supports both HTTP URLs and data URIs
                    String dataUri = "data:" + attachment.getMimeType() + ";base64," + attachment.getBase64Data();
                    yield ImageContent.from(dataUri);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to create ImageContent from base64 attachment '%s': %s",
                            attachment.getFileName(), e.getMessage());
                    yield null;
                }
            }
            case STORED -> {
                // Storage-backed attachments need the IAttachmentStorage SPI to load bytes.
                // For now, we log a warning. When storage impls are added, this path will
                // load bytes via attachmentStorage.load(storageRef).
                LOGGER.debugf("Storage-backed attachment '%s' — multimodal forwarding requires IAttachmentStorage (not yet available)",
                        attachment.getFileName());
                yield TextContent.from(String.format("[Stored attachment: %s (%s) — storage-based loading not yet implemented]",
                        attachment.getFileName() != null ? attachment.getFileName() : "unnamed",
                        attachment.getMimeType()));
            }
            case NONE -> null;
        };
    }
}
