/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IAttachmentStore;
import ai.labs.eddi.engine.memory.IAttachmentStore.Attachment;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.Content;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Converts {@link Attachment} objects into langchain4j {@link Content} objects
 * for inclusion in LLM chat messages.
 * <p>
 * Image attachments are converted to {@link ImageContent} with base64-encoded
 * data URIs (for vision-capable providers). Non-image attachments are converted
 * to {@link TextContent} markers indicating the file type and name.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class AttachmentForwarder {

    private static final Logger LOGGER = Logger.getLogger(AttachmentForwarder.class);

    /** MIME types that can be forwarded as images to vision-capable LLMs */
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp");

    /**
     * Convert attachments into langchain4j content objects.
     *
     * @param attachments
     *            the attachments to convert
     * @param attachmentStore
     *            the store to load binary data from
     * @param conversationId
     *            the requesting conversation ID (for access control)
     * @return list of content objects to include in a UserMessage
     */
    public List<Content> toContent(List<Attachment> attachments,
                                   IAttachmentStore attachmentStore, String conversationId) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }

        List<Content> contents = new ArrayList<>();
        for (Attachment attachment : attachments) {
            try {
                Content content = convertAttachment(attachment, attachmentStore, conversationId);
                contents.add(content);
            } catch (IAttachmentStore.AttachmentStoreException e) {
                LOGGER.warnf("Failed to load attachment '%s': %s", attachment.storageRef(), e.getMessage());
                contents.add(TextContent.from("[Attachment '%s' could not be loaded]".formatted(attachment.filename())));
            }
        }
        return contents;
    }

    private Content convertAttachment(Attachment attachment,
                                      IAttachmentStore attachmentStore, String conversationId)
            throws IAttachmentStore.AttachmentStoreException {

        if (isImageType(attachment.mimeType())) {
            byte[] data = attachmentStore.load(attachment.storageRef(), conversationId);
            String base64 = Base64.getEncoder().encodeToString(data);
            String dataUri = "data:%s;base64,%s".formatted(attachment.mimeType(), base64);

            LOGGER.debugf("Converting image attachment '%s' (%s, %d bytes) to ImageContent",
                    attachment.filename(), attachment.mimeType(), attachment.sizeBytes());

            return ImageContent.from(dataUri);
        }

        // Non-image: create a text marker
        return TextContent.from("[Attached file: '%s' (%s, %d bytes)]"
                .formatted(attachment.filename(), attachment.mimeType(), attachment.sizeBytes()));
    }

    /**
     * Check if a MIME type is an image type that can be forwarded to vision models.
     */
    public static boolean isImageType(String mimeType) {
        return mimeType != null && IMAGE_MIME_TYPES.contains(mimeType.split(";")[0].trim().toLowerCase());
    }
}
