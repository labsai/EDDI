/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.List;

/**
 * Storage abstraction for multimodal attachments (images, PDFs, audio, etc.).
 * Supports MongoDB GridFS and PostgreSQL backends via the DB-agnostic pattern
 * in {@code DataStoreProducers}.
 * <p>
 * <strong>Security:</strong> All access is scoped to the owning conversation.
 * Cross-conversation access is rejected. GDPR erasure cascades via
 * {@link #deleteByConversation(String)}.
 *
 * @since 6.0.0
 */
public interface IAttachmentStore {

    /**
     * Store an attachment.
     *
     * @param bytes
     *            the raw file bytes
     * @param declaredMime
     *            the MIME type declared by the client
     * @param filename
     *            the original filename
     * @param conversationId
     *            owning conversation
     * @param tenantId
     *            the tenant (for quota enforcement)
     * @return the stored attachment metadata
     * @throws AttachmentStoreException
     *             if storage fails, MIME validation fails, or size limit exceeded
     */
    Attachment store(byte[] bytes, String declaredMime, String filename,
                     String conversationId, String tenantId)
            throws AttachmentStoreException;

    /**
     * Load an attachment by storage reference.
     *
     * @param storageRef
     *            the storage reference from {@link Attachment#storageRef()}
     * @param requestingConversationId
     *            the conversation requesting access (must match owning
     *            conversation)
     * @return the raw bytes
     * @throws AttachmentStoreException
     *             if not found or cross-conversation access attempted
     */
    byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Delete all attachments for a conversation (GDPR erasure).
     *
     * @param conversationId
     *            the conversation ID
     * @return number of attachments deleted
     */
    long deleteByConversation(String conversationId);

    /**
     * List all attachments for a conversation.
     *
     * @param conversationId
     *            the conversation ID
     * @return list of attachment metadata
     */
    List<Attachment> listByConversation(String conversationId);

    /**
     * Attachment metadata record.
     *
     * @param storageRef
     *            unique reference for loading
     * @param filename
     *            original filename
     * @param mimeType
     *            validated MIME type
     * @param sizeBytes
     *            file size in bytes
     * @param conversationId
     *            owning conversation
     */
    record Attachment(String storageRef, String filename, String mimeType,
            long sizeBytes, String conversationId) {
    }

    class AttachmentStoreException extends Exception {
        public AttachmentStoreException(String message) {
            super(message);
        }

        public AttachmentStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
