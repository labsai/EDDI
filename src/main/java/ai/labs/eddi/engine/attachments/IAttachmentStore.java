/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.List;
import java.util.Optional;

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
 * ({@link #delete}) is restricted to the owner (a blob that records no owner
 * stays deletable). GDPR/conversation erasure cascades via
 * {@link #deleteByConversation(String)}, which also removes the blob's grants.
 * <p>
 * <strong>Lineage:</strong> an uploaded blob is a <em>root</em>; the views
 * stored of it ({@link #storeDerived}) are <em>derived</em> blobs. A derived
 * blob has its root's owner, and access to it is decided on its root: whoever
 * may read the root may read every view of it, and a grant is always on the
 * root ({@link #grantAccess} given a derived ref grants its root). Deleting a
 * root deletes its lineage, then announces {@link AttachmentRootDeleted}.
 * Listings and file-count quotas see roots only.
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
     * deleted. A grant is always on a root: given a derived ref, the grant is
     * written on its root, which is what authorizes the view anyway.
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
     * cannot delete another conversation's blob. Deleting a root deletes its whole
     * lineage and its grants, then fires one {@link AttachmentRootDeleted};
     * deleting a derived blob deletes that blob only and fires nothing.
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
     * Delete all attachments for a conversation (GDPR/conversation erasure),
     * together with the grants on them <em>and</em> the grants this conversation
     * held on other conversations' blobs — so grants never outlive either side. The
     * lineage of every root it owned goes with it (derived blobs are stored under
     * their root owner's conversation), and one {@link AttachmentRootDeleted} is
     * fired per root, after the delete. Views this conversation produced of a root
     * it was only granted belong to that root's owner and stay.
     *
     * @param conversationId
     *            the conversation ID
     * @return number of uploaded attachments (roots) deleted; derived blobs are not
     *         counted
     */
    long deleteByConversation(String conversationId);

    /**
     * List the uploaded attachments (roots) <em>owned</em> by a conversation.
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
     * them (owned by the group conversation, granted to the member). Roots only:
     * derived blobs are listed by {@link #listDerived}.
     *
     * @param conversationId
     *            the requesting conversation ID
     * @return owned + granted attachment metadata
     */
    List<Attachment> listAccessible(String conversationId);

    // ------------------------------------------------------------------ lineage

    /**
     * Stores a derived blob — a view of an uploaded file (a manifest, a page
     * window's text, an image, a slice).
     * <p>
     * <b>The owner is the root's</b>, copied from the root inside the same lock
     * (and on PostgreSQL the same transaction), never the caller's: a view a
     * grantee conversation extracts belongs to the root's owner, is readable by
     * every reader of the root and survives the grantee's deletion. The caller must
     * be able to read the root.
     * <p>
     * The write takes the root's source lock, after the owner's quota locks when
     * the kind counts toward the owner's byte quota (lock order: conversation →
     * user → source). Under it: the root is re-read (gone → not found); identical
     * bytes already stored for the same owner and root are returned instead of
     * stored again (dedup on {@code (ownerKey, rootRef, sha256)}, the existing
     * blob's derivation unchanged); the per-source caps of {@link DerivedCaps}
     * apply, evicting least recently used slices; media kinds are checked against
     * the owner's byte quotas. A derived blob never counts toward a file-count
     * quota and is never listed by {@link #listByConversation} or
     * {@link #listAccessible}.
     *
     * @param derivation
     *            where it comes from. Its {@code rootRef} or {@code parentRef} may
     *            name a derived blob; the store records the lineage's original root
     *            and the direct parent. Its {@code sha256} is ignored — the store
     *            computes it
     * @param requestingConversationId
     *            the conversation whose work produced the view; must be able to
     *            read the root
     * @return the stored (or deduplicated) blob; its {@code conversationId} is the
     *         root owner's
     * @throws AttachmentDerivedCapException
     *             if a per-source cap would be exceeded
     * @throws AttachmentQuotaExceededException
     *             if a media kind would exceed the owner's byte quota
     * @throws AttachmentNotFoundException
     *             if the root does not exist or the caller cannot read it
     */
    Attachment storeDerived(byte[] bytes, String mimeType, String filename, Derivation derivation,
                            String requestingConversationId)
            throws AttachmentStoreException;

    /**
     * Where a blob comes from: empty for a root.
     *
     * @throws AttachmentNotFoundException
     *             if it does not exist or the caller cannot read its root
     */
    Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * The derived blobs of a root, oldest first.
     *
     * @param kind
     *            only this kind, or {@code null} for all
     * @throws AttachmentNotFoundException
     *             if the root does not exist or the caller cannot read it
     */
    List<Attachment> listDerived(String rootRef, Derivation.Kind kind, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Exact lookup by the {@code (kind, unitId)} key a producer chose — the cache
     * primitive for manifests, slices and summaries. When the key was written more
     * than once (bytes that changed between writes), the most recent match wins. A
     * blob deduplicated under another key is not found here.
     *
     * @throws AttachmentNotFoundException
     *             if the root does not exist or the caller cannot read it
     */
    Optional<Attachment> findDerived(String rootRef, Derivation.Kind kind, String unitId, String requestingConversationId)
            throws AttachmentStoreException;

    /**
     * The root of a blob's lineage: the blob itself for a root.
     *
     * @throws AttachmentNotFoundException
     *             if it does not exist or the caller cannot read its root — the
     *             same answer for both, so it is no oracle
     */
    String rootOf(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * The owner of a blob's lineage — for a derived blob, its root's.
     *
     * @throws AttachmentNotFoundException
     *             if it does not exist or the caller cannot read its root
     */
    OwnerRef ownerOf(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * A root's page count, as recorded by {@link #setPageCount}; {@code null} when
     * unknown. For a derived ref, its root's.
     *
     * @throws AttachmentNotFoundException
     *             if it does not exist or the caller cannot read its root
     */
    Integer pageCount(String rootRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Records a root's page count. <b>Trusted callers only</b> (an extractor that
     * opened the file); a derived ref or a missing root is ignored.
     */
    void setPageCount(String rootRef, int pages);

    /**
     * The owner of a lineage.
     *
     * @param scope
     *            whom it belongs to
     * @param ownerKey
     *            the key everything owned by it is stored under (the conversation
     *            id for {@link OwnerScope#CONVERSATION})
     * @param ownerUserId
     *            the user who uploaded or contributed it; {@code null} when unknown
     */
    record OwnerRef(OwnerScope scope, String ownerKey, String ownerUserId) {
    }

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
     * Thrown when a derived blob would exceed a per-source cap of its root
     * ({@link DerivedCaps}). {@link #getCap()} names it, e.g.
     * {@code "max-per-source"}. The producer degrades the block to a placeholder
     * rather than failing the turn.
     */
    class AttachmentDerivedCapException extends AttachmentStoreException {
        private final String cap;

        public AttachmentDerivedCapException(String cap, String message) {
            super(message);
            this.cap = cap;
        }

        public String getCap() {
            return cap;
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
