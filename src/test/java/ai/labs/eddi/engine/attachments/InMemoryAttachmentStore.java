/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small in-memory {@link IAttachmentStore} with the real stores'
 * owner-or-grant read rule, for the share tests. Wrap it in a Mockito
 * {@code spy} to verify grants.
 */
class InMemoryAttachmentStore implements IAttachmentStore {

    private final Map<String, Attachment> blobs = new LinkedHashMap<>();
    private final Map<String, Set<String>> grants = new LinkedHashMap<>();

    /** Adds a blob owned by {@code ownerConversationId}. */
    InMemoryAttachmentStore put(String storageRef, String filename, String mimeType, long size, String ownerConversationId) {
        blobs.put(storageRef, new Attachment(storageRef, filename, mimeType, size, ownerConversationId));
        return this;
    }

    /** Whether {@code conversationId} holds a grant on {@code storageRef}. */
    boolean isGranted(String storageRef, String conversationId) {
        return grants.getOrDefault(storageRef, Set.of()).contains(conversationId);
    }

    /** The number of grants on {@code storageRef}. */
    int grantCount(String storageRef) {
        return grants.getOrDefault(storageRef, Set.of()).size();
    }

    private boolean canRead(Attachment a, String conversationId) {
        return a.conversationId().equals(conversationId) || isGranted(a.storageRef(), conversationId);
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename, String conversationId, String tenantId,
                            String userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        getMetadata(storageRef, requestingConversationId);
        return new byte[0];
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        Attachment a = blobs.get(storageRef);
        if (a == null) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        if (!canRead(a, requestingConversationId)) {
            throw new AttachmentAccessDeniedException("Access denied");
        }
        return a;
    }

    @Override
    public void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        if (!blobs.containsKey(storageRef)) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        grants.computeIfAbsent(storageRef, k -> new HashSet<>()).add(conversationId);
    }

    @Override
    public boolean delete(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long deleteByConversation(String conversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Attachment> listByConversation(String conversationId) {
        return blobs.values().stream().filter(a -> a.conversationId().equals(conversationId)).toList();
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        List<Attachment> result = new ArrayList<>();
        for (Attachment a : blobs.values()) {
            if (canRead(a, conversationId)) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public Attachment storeDerived(byte[] bytes, String mimeType, String filename, Derivation derivation,
                                   String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Attachment> listDerived(String rootRef, Derivation.Kind kind, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Attachment> findDerived(String rootRef, Derivation.Kind kind, String unitId,
                                            String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String rootOf(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public OwnerRef ownerOf(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Integer pageCount(String rootRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPageCount(String rootRef, int pages) {
        throw new UnsupportedOperationException();
    }

    @Override
    public QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl,
                                    QuotaMode mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void release(String reservationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void convert(String reservationId, String storageRef) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Attachment store(InputStream in, long declaredLength, String declaredMime, String filename, String conversationId,
                            String tenantId, String userId, String reservationId, QuotaMode quotaMode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream open(String storageRef, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public InputStream openRange(String storageRef, String requestingConversationId, long offset, long length) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long pendingTombstones(String ownerKeyOrNull) {
        return 0;
    }

    @Override
    public int sweepTombstones(int max) {
        return 0;
    }
}
