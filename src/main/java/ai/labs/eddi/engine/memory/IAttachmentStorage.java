package ai.labs.eddi.engine.memory;

import java.io.InputStream;

/**
 * Service Provider Interface for binary attachment storage.
 * <p>
 * Attachments are stored externally (GridFS, PostgreSQL bytea, or object
 * storage) and referenced by a storage key. This SPI is DB-agnostic from day 1.
 * <p>
 * Implementations must be idempotent for {@code deleteByConversation}.
 *
 * @since 6.0.0
 */
public interface IAttachmentStorage {

    /**
     * Store binary data and return a storage reference key.
     *
     * @param conversationId
     *            owning conversation
     * @param fileName
     *            original file name (e.g., "screenshot.png")
     * @param mimeType
     *            MIME type (e.g., "image/png")
     * @param data
     *            binary input stream (caller closes)
     * @param sizeBytes
     *            size in bytes (-1 if unknown)
     * @return storage reference key (opaque string, used to load/delete)
     */
    String store(String conversationId, String fileName, String mimeType, InputStream data, long sizeBytes);

    /**
     * Load binary data by storage reference.
     *
     * @param storageRef
     *            key returned by {@link #store}
     * @return input stream of the binary data (caller must close)
     * @throws AttachmentNotFoundException
     *             if the reference does not exist
     */
    InputStream load(String storageRef) throws AttachmentNotFoundException;

    /**
     * Delete all attachments for a conversation (GDPR cleanup).
     *
     * @param conversationId
     *            the conversation whose attachments to delete
     * @return number of attachments deleted
     */
    long deleteByConversation(String conversationId);

    /**
     * Thrown when a storage reference does not resolve to an attachment.
     */
    class AttachmentNotFoundException extends Exception {
        public AttachmentNotFoundException(String message) {
            super(message);
        }
    }
}
