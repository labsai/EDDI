/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.AttachmentExtractionException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Built-in LLM tool for reading the current conversation's uploaded attachments
 * on demand — the multi-turn recall path for content that was forwarded on an
 * earlier turn, is too large to inline, or needs a specific PDF page.
 * <p>
 * The conversation is implicit: the tool is constructed with the conversation
 * id by {@code AgentOrchestrator}, so the LLM never supplies a
 * userId/conversationId — it can only see attachments belonging to (or granted
 * to) its own conversation, enforced by {@link IAttachmentStore}.
 * <p>
 * Constructed per-invocation — NOT a CDI bean.
 *
 * @since 6.1.0
 */
@Vetoed
public class ReadAttachmentTool {

    private static final Logger LOGGER = Logger.getLogger(ReadAttachmentTool.class);

    private final IAttachmentStore attachmentStore;
    private final AttachmentTextExtractor textExtractor;
    private final String conversationId;

    public ReadAttachmentTool(IAttachmentStore attachmentStore, AttachmentTextExtractor textExtractor,
            String conversationId) {
        this.attachmentStore = attachmentStore;
        this.textExtractor = textExtractor;
        this.conversationId = conversationId;
    }

    @Tool("Lists the files attached to this conversation. Returns each attachment's name, type and size. "
            + "Use this to discover what is available before calling readAttachment.")
    public String listAttachments() {
        List<Attachment> attachments = attachmentStore.listByConversation(conversationId);
        if (attachments.isEmpty()) {
            return "No attachments are available in this conversation.";
        }
        StringBuilder sb = new StringBuilder("Attachments in this conversation:\n");
        for (Attachment a : attachments) {
            sb.append("- ").append(a.filename() != null ? a.filename() : "(unnamed)")
                    .append(" [").append(a.mimeType()).append(", ").append(a.sizeBytes()).append(" bytes]")
                    .append(" ref=").append(a.storageRef()).append("\n");
        }
        return sb.toString();
    }

    @Tool("Reads the text content of one attachment in this conversation. Identify it by file name or "
            + "reference. For PDFs, pass a 1-based page number to read just that page, or 0 for the whole "
            + "document. Returns extracted text, or a note if the attachment has no extractable text.")
    public String readAttachment(
                                 @P("The attachment's file name or storage reference") String nameOrRef,
                                 @P("For PDFs: 1-based page to read, or 0 for the whole document. Ignored for other types.") int page) {

        Attachment match = resolve(nameOrRef);
        if (match == null) {
            return "No attachment named '" + nameOrRef + "' was found in this conversation. "
                    + "Call listAttachments to see what is available.";
        }
        try {
            byte[] bytes = attachmentStore.load(match.storageRef(), conversationId);
            String mime = match.mimeType() == null ? "" : match.mimeType().toLowerCase(Locale.ROOT);

            String text;
            if (mime.startsWith("application/pdf") && page > 0) {
                text = textExtractor.extractPdfText(bytes, page, page, textExtractor.getDefaultMaxChars());
            } else if (textExtractor.canExtractText(match.mimeType())) {
                text = textExtractor.extractText(bytes, match.mimeType());
            } else {
                return "Attachment '" + display(match) + "' is a " + match.mimeType()
                        + " and has no extractable text.";
            }
            if (text == null || text.isBlank()) {
                return "Attachment '" + display(match) + "' contains no extractable text.";
            }
            return "Content of '" + display(match) + "' (" + match.mimeType() + "):\n" + text;
        } catch (IAttachmentStore.AttachmentStoreException e) {
            LOGGER.debugf("readAttachment load failed for '%s': %s", nameOrRef, e.getMessage());
            return "Could not read attachment '" + nameOrRef + "': " + e.getMessage();
        } catch (AttachmentExtractionException e) {
            LOGGER.debugf("readAttachment extraction failed for '%s': %s", nameOrRef, e.getMessage());
            return "Could not extract text from '" + display(match) + "': " + e.getMessage();
        }
    }

    private Attachment resolve(String nameOrRef) {
        if (nameOrRef == null || nameOrRef.isBlank()) {
            return null;
        }
        List<Attachment> attachments = attachmentStore.listByConversation(conversationId);
        // Prefer an exact storageRef match, then an exact file-name match, then
        // case-insensitive name.
        for (Attachment a : attachments) {
            if (nameOrRef.equals(a.storageRef())) {
                return a;
            }
        }
        for (Attachment a : attachments) {
            if (nameOrRef.equals(a.filename())) {
                return a;
            }
        }
        for (Attachment a : attachments) {
            if (a.filename() != null && a.filename().equalsIgnoreCase(nameOrRef)) {
                return a;
            }
        }
        return null;
    }

    private static String display(Attachment a) {
        return a.filename() != null ? a.filename() : a.storageRef();
    }
}
