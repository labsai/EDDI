/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.List;

/**
 * Storage abstraction for multimodal attachments (images, PDFs, audio, etc.).
 * Supports MongoDB GridFS and PostgreSQL backends via the DB-agnostic pattern
 * in {@code DataStoreProducers}.
 * <p>
 * <strong>Ownership &amp; access:</strong> every blob is owned by the
 * conversation it was uploaded to. Read access ({@link #load} /
 * {@link #getMetadata}) is granted to the owning conversation <em>or</em> any
 * conversation that has been given an explicit {@linkplain #grantAccess grant}.
 * Grants are written only by trusted server code (e.g. group fan-out) — never
 * derived from client-supplied context. Deletion of a single blob
 * ({@link #delete}) is restricted to the owner. GDPR/conversation erasure
 * cascades via {@link #deleteByConversation(String)}, which also removes the
 * blob's grants.
 * <p>
 * This is the single blob-store abstraction for EDDI; the former
 * {@code IAttachmentStorage} was folded into it so uploads, LLM forwarding,
 * conversation deletion and GDPR erasure all operate on the same store.
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
     *            the tenant (advisory metadata only; not an access boundary)
     * @return the stored attachment metadata
     * @throws AttachmentStoreException
     *             if storage fails, MIME validation fails, the size limit is
     *             exceeded, or a per-conversation quota is exceeded
     */
    default Attachment store(byte[] bytes, String declaredMime, String filename,
                             String conversationId, String tenantId)
            throws AttachmentStoreException {
        return store(bytes, declaredMime, filename, conversationId, tenantId, null);
    }

    /**
     * Store an attachment on behalf of a user.
     * <p>
     * <strong>Quotas are enforced atomically.</strong> The per-conversation quota
     * ({@code eddi.attachments.max-per-conversation},
     * {@code max-total-bytes-per-conversation}) and, when {@code userId} is known,
     * the optional per-user quota ({@code eddi.attachments.max-per-user},
     * {@code max-total-bytes-per-user}) are checked and the blob inserted under one
     * lock per quota scope, so concurrent uploads cannot each pass the check and
     * together exceed the limit.
     *
     * @param userId
     *            the user the blob counts against — the owner of the conversation;
     *            {@code null} when unknown, which exempts the blob from the
     *            per-user quota
     * @throws AttachmentQuotaExceededException
     *             if a quota would be exceeded
     */
    Attachment store(byte[] bytes, String declaredMime, String filename,
                     String conversationId, String tenantId, String userId)
            throws AttachmentStoreException;

    /**
     * Load an attachment's bytes.
     *
     * @param storageRef
     *            the storage reference from {@link Attachment#storageRef()}
     * @param requestingConversationId
     *            the conversation requesting access (must own the blob or hold a
     *            grant)
     * @return the raw bytes
     * @throws AttachmentStoreException
     *             if not found or access is denied (not owner and not granted)
     */
    byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Resolve an attachment's server-validated metadata (MIME, filename, size,
     * owner) without transferring the bytes. Same owner-or-grant authorization as
     * {@link #load}. Used at extraction time so behavior rules and the forwarder
     * see the truth for {@code storageRef}-only references.
     *
     * @param storageRef
     *            the storage reference
     * @param requestingConversationId
     *            the conversation requesting access (must own the blob or hold a
     *            grant)
     * @return the attachment metadata
     * @throws AttachmentStoreException
     *             if not found or access is denied
     */
    Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Grant a conversation read access to a blob it does not own. Idempotent.
     * <p>
     * <strong>Trusted callers only.</strong> This must be invoked exclusively by
     * server-side orchestration (e.g. {@code GroupConversationService} at member
     * fan-out) — never from client-supplied context — since it widens the access
     * boundary of the blob. The grant lives with the blob and dies when the blob is
     * deleted.
     *
     * @param storageRef
     *            the storage reference of the blob to share
     * @param conversationId
     *            the conversation to grant read access to
     * @throws AttachmentStoreException
     *             if the blob does not exist
     */
    void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException;

    /**
     * Delete a single attachment. Restricted to the owning conversation — a grantee
     * cannot delete another conversation's blob.
     *
     * @param storageRef
     *            the storage reference
     * @param requestingConversationId
     *            the conversation requesting deletion (must be the owner)
     * @return {@code true} if a blob was deleted, {@code false} if none matched
     * @throws AttachmentStoreException
     *             if the blob exists but is owned by another conversation
     */
    boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Delete all attachments for a conversation (GDPR/conversation erasure).
     *
     * @param conversationId
     *            the conversation ID
     * @return number of attachments deleted
     */
    long deleteByConversation(String conversationId);

    /**
     * List attachments <em>owned</em> by a conversation.
     *
     * @param conversationId
     *            the conversation ID
     * @return list of attachment metadata
     */
    List<Attachment> listByConversation(String conversationId);

    /**
     * List all attachments a conversation can <em>read</em> — those it owns plus
     * those it has been {@linkplain #grantAccess granted}. Used by the
     * {@code readAttachment} tool so group members can enumerate blobs shared with
     * them (owned by the group conversation, granted to the member).
     *
     * @param conversationId
     *            the requesting conversation ID
     * @return owned + granted attachment metadata
     */
    List<Attachment> listAccessible(String conversationId);

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

    /**
     * Thrown when storing a blob would exceed a quota. {@link #getScope()} says
     * which: {@code "conversation"} or {@code "user"}.
     */
    class AttachmentQuotaExceededException extends AttachmentStoreException {
        public static final String SCOPE_CONVERSATION = "conversation";
        public static final String SCOPE_USER = "user";

        private final String scope;

        public AttachmentQuotaExceededException(String scope, String message) {
            super(message);
            this.scope = scope;
        }

        public String getScope() {
            return scope;
        }
    }

    /**
     * Thrown specifically when a conversation is not permitted to access a blob
     * (not the owner and no grant). Lets callers distinguish an authorization
     * failure (403) from a missing blob (404) without matching on message text.
     */
    class AttachmentAccessDeniedException extends AttachmentStoreException {
        public AttachmentAccessDeniedException(String message) {
            super(message);
        }
    }

    /**
     * Thrown when a storage reference does not resolve to any blob — a genuinely
     * missing attachment, as opposed to a backend/store failure (which stays a
     * plain {@link AttachmentStoreException}). Lets the REST layer return 404 for a
     * missing blob while a store outage surfaces as 500 instead of being
     * misreported as "not found".
     */
    class AttachmentNotFoundException extends AttachmentStoreException {
        public AttachmentNotFoundException(String message) {
            super(message);
        }
    }
}
