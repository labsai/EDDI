/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.MimeValidator;
import ai.labs.eddi.engine.attachments.QuotaMode;
import ai.labs.eddi.engine.attachments.QuotaReservation;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An attachment store in memory with the rules of the real ones that inbound
 * A2A files depend on: the size limit, the MIME check by signature,
 * owner-or-grant reads, owner-only deletes, and an optional per-user file
 * quota.
 */
final class FakeAttachmentStore implements IAttachmentStore {

    record Blob(byte[] bytes, String mimeType, String fileName, String conversationId, String userId, Set<String> grants) {
    }

    final Map<String, Blob> blobs = new LinkedHashMap<>();
    private final long maxSizeBytes;
    private int maxPerUser = Integer.MAX_VALUE;
    /** Fail the n-th store call (1-based), 0 for never. */
    private int failOnStoreNumber;
    private int stores;

    FakeAttachmentStore(long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    FakeAttachmentStore maxPerUser(int max) {
        this.maxPerUser = max;
        return this;
    }

    FakeAttachmentStore failOnStore(int number) {
        this.failOnStoreNumber = number;
        return this;
    }

    @Override
    public synchronized Attachment store(byte[] bytes, String declaredMime, String filename, String conversationId, String tenantId,
                                         String userId)
            throws AttachmentStoreException {
        stores++;
        if (stores == failOnStoreNumber) {
            throw new AttachmentStoreException("internal detail: mongodb://secret-host:27017 refused");
        }
        if (bytes == null || bytes.length == 0) {
            throw new AttachmentStoreException("Attachment data is empty");
        }
        if (bytes.length > maxSizeBytes) {
            throw new AttachmentStoreException("too large");
        }
        if (!MimeValidator.isCompatibleContent(declaredMime, bytes)) {
            throw new AttachmentStoreException("MIME type mismatch");
        }
        if (userId != null && blobs.values().stream().filter(b -> userId.equals(b.userId())).count() >= maxPerUser) {
            throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_USER, "user quota for " + userId);
        }
        String ref = UUID.randomUUID().toString();
        String mime = MimeValidator.normalize(declaredMime != null ? declaredMime : MimeValidator.detectMime(bytes));
        blobs.put(ref, new Blob(bytes, mime, filename, conversationId, userId, new HashSet<>()));
        return new Attachment(ref, filename, mime, bytes.length, conversationId);
    }

    private Blob readable(String ref, String conversationId) throws AttachmentStoreException {
        Blob blob = blobs.get(ref);
        if (blob == null || !(blob.conversationId().equals(conversationId) || blob.grants().contains(conversationId))) {
            throw new AttachmentStoreException("not found");
        }
        return blob;
    }

    @Override
    public synchronized byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        return readable(storageRef, requestingConversationId).bytes();
    }

    @Override
    public synchronized Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Blob blob = readable(storageRef, requestingConversationId);
        return new Attachment(storageRef, blob.fileName(), blob.mimeType(), blob.bytes().length, blob.conversationId());
    }

    @Override
    public synchronized void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        Blob blob = blobs.get(storageRef);
        if (blob == null) {
            throw new AttachmentStoreException("not found");
        }
        blob.grants().add(conversationId);
    }

    @Override
    public synchronized boolean delete(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Blob blob = blobs.get(storageRef);
        if (blob == null) {
            return false;
        }
        if (!blob.conversationId().equals(requestingConversationId)) {
            throw new AttachmentStoreException("not the owner");
        }
        blobs.remove(storageRef);
        return true;
    }

    @Override
    public synchronized long deleteByConversation(String conversationId) {
        List<String> owned = blobs.entrySet().stream().filter(e -> e.getValue().conversationId().equals(conversationId)).map(Map.Entry::getKey)
                .toList();
        owned.forEach(blobs::remove);
        return owned.size();
    }

    @Override
    public synchronized List<Attachment> listByConversation(String conversationId) {
        List<Attachment> result = new ArrayList<>();
        blobs.forEach((ref, blob) -> {
            if (blob.conversationId().equals(conversationId)) {
                result.add(new Attachment(ref, blob.fileName(), blob.mimeType(), blob.bytes().length, conversationId));
            }
        });
        return result;
    }

    @Override
    public synchronized List<Attachment> listAccessible(String conversationId) {
        List<Attachment> result = new ArrayList<>();
        blobs.forEach((ref, blob) -> {
            if (blob.conversationId().equals(conversationId) || blob.grants().contains(conversationId)) {
                result.add(new Attachment(ref, blob.fileName(), blob.mimeType(), blob.bytes().length, blob.conversationId()));
            }
        });
        return result;
    }

    // --- not used by the inbound A2A tests: the derived-blob and quota methods
    // (2a.2, 2a.3) ---

    @Override
    public Attachment storeDerived(byte[] bytes, String mimeType, String filename, Derivation derivation, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public List<Attachment> listDerived(String rootRef, Derivation.Kind kind, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public Optional<Attachment> findDerived(String rootRef, Derivation.Kind kind, String unitId, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public String rootOf(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public OwnerRef ownerOf(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public Integer pageCount(String rootRef, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public void setPageCount(String rootRef, int pages) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public void release(String reservationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public void convert(String reservationId, String storageRef) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    // --- not used by the inbound A2A tests: the streaming and tombstone methods
    // (4.1b) ---

    @Override
    public QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl, QuotaMode mode) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public Attachment store(InputStream in, long declaredLength, String declaredMime, String filename, String conversationId, String tenantId,
                            String userId, String reservationId, QuotaMode quotaMode) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public InputStream open(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public InputStream openRange(String storageRef, String requestingConversationId, long offset, long length) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public long pendingTombstones(String ownerKeyOrNull) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }

    @Override
    public int sweepTombstones(int max) {
        throw new UnsupportedOperationException("not used by the inbound A2A tests");
    }
}
