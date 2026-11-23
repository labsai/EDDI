/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.blob.StreamedUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
 * <strong>Libraries:</strong> a root {@linkplain #promote promoted} to a
 * library (owner scope {@link OwnerScope#USER}) belongs to the library with its
 * lineage. Its conversation id is then origin information only: the
 * conversation it came from reads it through a grant like any other, cannot
 * delete it, and does not list or count it as its own. The library methods work
 * by owner key.
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
     * together exceed the limit. Usage is the stored blobs plus the live
     * {@linkplain #reserve reservations} of the scope.
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
     * @throws AttachmentTooLargeForMemoryException
     *             if the blob is larger than
     *             {@code eddi.attachments.max-size-bytes} — such a blob is read
     *             with {@link #open} or {@link #materialize}, never into one array
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
     *             if the blob exists but is owned by another conversation, or
     *             belongs to a library ({@link AttachmentAccessDeniedException},
     *             whichever conversation asks: library documents are removed
     *             through the library)
     */
    boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Delete all attachments for a conversation (GDPR/conversation erasure),
     * together with the grants on them <em>and</em> the grants this conversation
     * held on other conversations' blobs — so grants never outlive either side. The
     * lineage of every root it owned goes with it (derived blobs are stored under
     * their root owner's conversation), and one {@link AttachmentRootDeleted} is
     * fired per root, after the delete. Views this conversation produced of a root
     * it was only granted belong to that root's owner and stay. The
     * {@linkplain #reserve quota reservations} held for the conversation go too, so
     * an erasure during an upload leaves no quota held. A library document that
     * came from this conversation is not deleted: only this conversation's grant on
     * it goes.
     *
     * @param conversationId
     *            the conversation ID
     * @return number of uploaded attachments (roots) deleted; derived blobs are not
     *         counted
     */
    long deleteByConversation(String conversationId);

    /**
     * List the uploaded attachments (roots) <em>owned</em> by a conversation — not
     * the library documents that came from it.
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
     * derived blobs are listed by {@link #listDerived}. A library document is
     * listed only through a grant, and not while it is soft-removed.
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

    // ------------------------------------------------------- quota reservations

    /**
     * Holds quota for a write that runs too long to hold the quota lock — a
     * streaming upload, a job producing a file. The reservation is written under
     * the bucket locks after the same check an upload makes, and from then on
     * <b>counts as usage</b> of its buckets — for uploads, derived media and other
     * reservations alike — until it is {@linkplain #convert converted},
     * {@linkplain #release released} or expires. Expired reservations count for
     * nothing and are swept from the buckets being reserved in.
     * <p>
     * <b>Lock order.</b> Every store takes its locks in one fixed order,
     * conversation → user → team → source ({@link AttachmentLockOrder}); the stores
     * have always locked conversation before user, so the scopes added since come
     * after them and an old and a new node can never wait on each other in a cycle.
     * <p>
     * Expiry is decided by the database's clock, never a node's, so clock skew
     * between nodes cannot stretch or cut a reservation.
     * <p>
     * Only {@link OwnerScope#CONVERSATION} owners can reserve: nothing streams into
     * a library — a file enters one through {@link #promote}, which checks the
     * library's bucket under its own lock.
     *
     * @param owner
     *            the bucket's owner; for a conversation owner its key is the
     *            conversation id
     * @param conversationId
     *            the conversation the write is for; must be the owner's key
     * @param userId
     *            the user the write counts against; {@code null} when unknown,
     *            which exempts it from the per-user quota, as for an upload
     * @param bytes
     *            the bytes to hold (may be zero when {@code count} is not)
     * @param count
     *            the files to hold (zero for bytes only, e.g. a job's output)
     * @param ttl
     *            how long it counts at most; at most
     *            {@link QuotaReservation#MAX_TTL}
     * @return the reservation, with its expiry by the database's clock
     * @throws AttachmentQuotaExceededException
     *             if the reservation would exceed a quota; nothing is held then
     * @throws AttachmentStoreException
     *             if the store fails or the locks cannot be taken in time
     * @throws IllegalArgumentException
     *             for an owner other than a conversation, a conversation that is
     *             not the owner's, or amounts and a lifetime
     *             {@link QuotaReservation#validateRequest} refuses
     */
    default QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl)
            throws AttachmentStoreException {
        return reserve(owner, conversationId, userId, bytes, count, ttl, QuotaMode.STANDARD);
    }

    /**
     * {@link #reserve(OwnerRef, String, String, long, int, Duration)} for a write
     * of the given {@link QuotaMode}. A {@link QuotaMode#LARGE_PER_USER}
     * reservation's bytes count against the user's byte quota only, never the
     * conversation's (its files still count toward the conversation's file count),
     * and it needs a known user.
     *
     * @throws IllegalArgumentException
     *             as the shorter form does, or for {@link QuotaMode#LARGE_PER_USER}
     *             without a user
     */
    QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl, QuotaMode mode)
            throws AttachmentStoreException;

    /**
     * Gives a reservation's quota back. Idempotent: an unknown, expired, released
     * or converted reservation is ignored.
     */
    void release(String reservationId);

    /**
     * Completes a reservation with the blob it was taken for: the reservation stops
     * counting and the blob counts in its place, under the same bucket locks the
     * reservation was taken under.
     * <p>
     * The blob must be stored first, without being charged again: a write against a
     * reservation stores its blob uncharged and then converts — on PostgreSQL in
     * the same transaction. Converting only once the blob is visible is what keeps
     * usage from ever reading "neither": in between it reads "both", which can
     * refuse a concurrent upload but never admits one over the quota. The streaming
     * {@link #store(InputStream, long, String, String, String, String, String, String, QuotaMode)
     * store} writes this way.
     *
     * @param reservationId
     *            the reservation
     * @param storageRef
     *            the uploaded blob (a root) the reservation was taken for
     * @throws AttachmentNotFoundException
     *             if the blob does not exist, is not a root, or is not in the
     *             reservation's conversation and user buckets
     * @throws AttachmentQuotaExceededException
     *             if the reservation expired or was released before the write
     *             completed (the blob was then never covered by quota, and the
     *             writer must delete it), or the blob is larger than the bytes
     *             reserved
     */
    void convert(String reservationId, String storageRef) throws AttachmentStoreException;

    // ---------------------------------------------------------------- streaming

    /**
     * Stores an upload from a stream, without ever holding it in memory whole.
     * <p>
     * <b>Where the bytes go.</b> A file up to
     * {@code eddi.attachments.max-size-bytes} is stored where every file has always
     * been (inline: the GridFS bucket, the {@code BYTEA} column), so {@link #load}
     * keeps working for it. A larger one goes to the configured blob backend
     * ({@code eddi.attachments.blob.backend}), streamed chunk by chunk, and only
     * {@link #open}, {@link #openRange} and {@link #materialize} can read it.
     * <p>
     * <b>Quota.</b> The quota is held by a reservation from before the first byte
     * until the blob is recorded, so the write holds no lock while it streams. With
     * {@code reservationId} the caller's reservation is used — it must be live, of
     * this conversation, user and quota mode, and hold at least
     * {@code declaredLength} bytes and one file, which is checked before the first
     * byte is read (one that does not qualify is refused and left as it is);
     * without it the store takes one itself. A reservation that qualified is used
     * up by this call: converted into the blob on success, released on failure —
     * whole, even one taken for several files, so take one reservation per file.
     * <p>
     * <b>Checks.</b> The declared type is judged by {@link MimeValidator} on the
     * first bytes, before anything is stored; the SHA-256 is computed while the
     * bytes stream. A stream longer or shorter than {@code declaredLength} fails.
     * Any failure leaves no object and no row behind (an object that cannot be
     * deleted at once is left to the tombstone sweep).
     *
     * @param in
     *            the bytes; read to its end, not closed
     * @param declaredLength
     *            the exact length, at least 1
     * @param reservationId
     *            a live reservation of this conversation and user covering the
     *            upload, or {@code null} to let the store reserve
     * @param quotaMode
     *            {@link QuotaMode#STANDARD} for today's rules — at most
     *            {@code eddi.attachments.max-size-bytes} — or
     *            {@link QuotaMode#LARGE_PER_USER}, which needs a {@code userId}
     * @throws AttachmentQuotaExceededException
     *             if the quota is full, or the reservation is gone or too small
     * @throws AttachmentStoreException
     *             if the content does not match its type, the length is wrong or
     *             too large for the mode, or the store or the backend fails
     */
    Attachment store(InputStream in, long declaredLength, String declaredMime, String filename, String conversationId,
                     String tenantId, String userId, String reservationId, QuotaMode quotaMode)
            throws AttachmentStoreException;

    /**
     * Opens a blob's bytes as a stream, whatever its size and wherever it lives;
     * the caller closes it. Same authorization as {@link #load}.
     *
     * @throws AttachmentNotFoundException
     *             if no such blob exists (as for {@link #load}); a blob whose
     *             metadata exists but whose bytes the backend cannot deliver is a
     *             plain {@link AttachmentStoreException}, never "not found"
     */
    InputStream open(String storageRef, String requestingConversationId) throws AttachmentStoreException;

    /**
     * Opens {@code length} bytes from {@code offset}, clipped at the end of the
     * blob; a range starting at or past the end is empty. Reads only the chunks the
     * range covers where the backend can; otherwise it skips. Same authorization as
     * {@link #load}.
     *
     * @throws IllegalArgumentException
     *             if {@code offset} or {@code length} is negative
     */
    InputStream openRange(String storageRef, String requestingConversationId, long offset, long length) throws AttachmentStoreException;

    /**
     * Copies a blob into a new file in {@code workDir} and returns it — for a
     * reader that needs random access to a file it cannot hold in memory. The file
     * gets a name the store chooses (never the upload's) and, where the file system
     * supports it, owner-only permissions; the caller deletes it. On failure no
     * file is left. Same authorization as {@link #load}.
     */
    default Path materialize(String storageRef, String requestingConversationId, Path workDir) throws AttachmentStoreException {
        if (workDir == null) {
            throw new IllegalArgumentException("materialize needs a work directory");
        }
        try (InputStream in = open(storageRef, requestingConversationId)) {
            return StreamedUpload.copyToNewFile(in, workDir);
        } catch (IOException e) {
            throw new AttachmentStoreException("Failed to copy the attachment to a file", e);
        }
    }

    /**
     * How many backend objects are waiting to be removed: of blobs deleted while
     * their backend could not delete the bytes, and of uploads whose writer did not
     * finish. A sweep retries them; erasure reports them as pending.
     *
     * @param ownerKeyOrNull
     *            only those of this owner key, or {@code null} for all
     */
    long pendingTombstones(String ownerKeyOrNull);

    /**
     * Retries up to {@code max} due tombstones: an object no blob references any
     * more is deleted from its backend, and the tombstone with it; one a blob
     * references (an upload that did complete, a delete that did not) is kept and
     * only the tombstone goes. The stores also sweep a few after every delete and
     * streamed upload, so nothing has to schedule this.
     *
     * @return the tombstones cleared
     */
    int sweepTombstones(int max);

    // ------------------------------------------------------------------ library

    /**
     * Puts an uploaded file into a library: <b>moves</b> the root and its whole
     * lineage to {@code newOwner}; nothing is copied. From then on the file belongs
     * to the library, not to the conversation it was uploaded to:
     * <ul>
     * <li>it counts against the library's buckets
     * ({@code eddi.documents.library.max-per-user},
     * {@code max-total-bytes-per-user}: the root and its media views) and no longer
     * against the conversation's or the uploading user's upload quota;</li>
     * <li>the promoting conversation keeps a {@linkplain #grantAccess grant}, and
     * reads it through that grant like any other grantee — the conversation id it
     * keeps is origin information only;</li>
     * <li>{@link #delete} refuses it, for every conversation (a library document is
     * removed through the library), {@link #deleteByConversation} skips it and
     * removes only that conversation's grant, and {@link #listByConversation} no
     * longer lists it.</li>
     * </ul>
     * <p>
     * <b>Only the owning conversation may promote</b>, and only a root it owns: a
     * grantee, a stranger, a derived blob or a file already in a library is
     * refused. Locks are taken in the fixed order (the library's bucket, then the
     * root's source lock), so a view being stored at the same time is stored either
     * before the move (and moves with it) or after it (under the new owner).
     * <p>
     * <b>Precondition the store cannot check:</b> the caller must refuse a root
     * that is withheld in the owning conversation
     * ({@code AttachmentContextExtractor.withheldStorageRefs(memory)}). "Withheld"
     * is conversation-memory state; the store never sees it.
     * <p>
     * Fires {@link AttachmentOwnerChanged} once, after the change committed.
     *
     * @param owningConversationId
     *            the conversation the file was uploaded to
     * @param newOwner
     *            the library: a {@link OwnerScope#USER} owner whose key is
     *            {@code SpaceTenants.tenantFor(Subjects.personalSpace(principal))}
     *            and whose user is the one who saves it
     * @param meta
     *            title, wanted slug, agent scope and retention; the store keeps
     *            slugs unique per owner
     * @return the library entry
     * @throws AttachmentNotFoundException
     *             if no such file exists
     * @throws AttachmentAccessDeniedException
     *             if the conversation does not own the file, or it is already in a
     *             library
     * @throws AttachmentQuotaExceededException
     *             with scope {@code library} if the library is full; nothing is
     *             moved then
     * @throws IllegalArgumentException
     *             for a conversation or team owner (team libraries are not
     *             available in this version)
     */
    LibraryEntry promote(String storageRef, String owningConversationId, OwnerRef newOwner, LibraryMeta meta) throws AttachmentStoreException;

    /**
     * Moves a library document — the root and its lineage — from one library to
     * another, re-keyed like {@link #promote}: the new library's quota applies, the
     * slug is kept unless the new library has it already (then it is suffixed),
     * grants are kept. Fires {@link AttachmentOwnerChanged}. <b>Trusted callers
     * only</b>; meant for the team library, which is not available in this version,
     * so the only target accepted today is a {@link OwnerScope#USER} library.
     *
     * @throws AttachmentNotFoundException
     *             if {@code oldOwnerKey} has no such document
     * @throws AttachmentQuotaExceededException
     *             with scope {@code library} if the new library is full
     * @throws IllegalArgumentException
     *             for a conversation or team target, or the same owner key
     */
    LibraryEntry moveOwner(String storageRef, String oldOwnerKey, OwnerRef newOwner) throws AttachmentStoreException;

    /**
     * A page of a library, most recently accessed first.
     *
     * @param agentScopeOrNull
     *            an agent id: only the documents that agent sees (those scoped to
     *            it and those scoped to all agents); {@code null}: all of them
     * @param includeRemoved
     *            whether soft-removed documents are listed
     * @param limit
     *            at most {@code eddi.rest.listing.max-limit}; zero or less reads as
     *            that ceiling
     */
    List<LibraryEntry> listByOwner(String ownerKey, String agentScopeOrNull, boolean includeRemoved, int offset, int limit);

    /**
     * One document of a library, soft-removed or not; empty when {@code ownerKey}
     * has no such document (whoever else may own it).
     */
    Optional<LibraryEntry> getLibraryEntry(String ownerKey, String storageRef);

    /**
     * Records that a library document was opened: its retention counts from now.
     * Anything that is not a library document is ignored.
     */
    void touchAccess(String storageRef);

    /**
     * Hides a library document: no longer listed by {@link #listByOwner} (unless
     * asked for) nor by its grantees' {@link #listAccessible}. It keeps counting
     * toward the library's quota until it is deleted.
     *
     * @return whether it was visible and is hidden now
     */
    boolean softRemove(String ownerKey, String storageRef, Instant when);

    /**
     * Undoes {@link #softRemove}.
     *
     * @return whether it was hidden and is visible now
     */
    boolean restore(String ownerKey, String storageRef);

    /**
     * Deletes everything a library owns — every document, its lineage, the grants
     * on them and the library's quota reservations — and fires one
     * {@link AttachmentRootDeleted} per document. For erasure: works by owner key
     * alone, without any conversation. A conversation id given as owner key deletes
     * nothing.
     *
     * @return the number of documents (roots) deleted
     */
    long deleteByOwner(String ownerKey);

    /**
     * Deletes one library document for good, as {@link #deleteByOwner} does for all
     * of them. For the retention reaper and administrators; a tool never
     * hard-deletes.
     *
     * @return whether {@code ownerKey} had it
     */
    boolean deleteLibraryDocument(String ownerKey, String storageRef);

    /**
     * Library documents whose retention has run out by {@code now}: their own
     * {@code retentionDays} (or the deployment default when they have none) after
     * their last access. A retention of zero never runs out. Oldest access first.
     */
    List<LibraryEntry> expired(Instant now, int limit);

    /**
     * Soft-removed library documents removed before {@code cutoff}, longest removed
     * first.
     */
    List<LibraryEntry> removedBefore(Instant cutoff, int limit);

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
     * which: {@code "conversation"} or {@code "user"}, {@code "library"} for a
     * library's own bucket ({@link #promote}, and media views of a library
     * document) — or {@code "reservation"} when a write outgrew, or outlived, the
     * {@linkplain #convert reservation} that covered it.
     */
    class AttachmentQuotaExceededException extends AttachmentStoreException {
        public static final String SCOPE_CONVERSATION = "conversation";
        public static final String SCOPE_USER = "user";
        public static final String SCOPE_LIBRARY = "library";
        public static final String SCOPE_RESERVATION = "reservation";

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
     * Thrown by {@link #load} for a blob larger than
     * {@code eddi.attachments.max-size-bytes}: it is never read into one array, so
     * no code path can pull a large file onto the heap by accident. Read it with
     * {@link #open}, {@link #openRange} or {@link #materialize}.
     */
    class AttachmentTooLargeForMemoryException extends AttachmentStoreException {
        private final long sizeBytes;
        private final long limitBytes;

        public AttachmentTooLargeForMemoryException(long sizeBytes, long limitBytes) {
            super("Attachment is too large to load into memory: %d bytes (limit: %d); read it as a stream".formatted(sizeBytes,
                    limitBytes));
            this.sizeBytes = sizeBytes;
            this.limitBytes = limitBytes;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public long getLimitBytes() {
            return limitBytes;
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
