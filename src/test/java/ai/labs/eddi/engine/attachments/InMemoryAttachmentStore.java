/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small in-memory {@link IAttachmentStore} with the real stores' rules that
 * the share and structure tests depend on: owner-or-grant reads, decided on the
 * root for a derived blob; derived blobs owned by their root's owner
 * (conversation scope), de-duplicated by {@code (ownerKey, rootRef, sha256)};
 * {@link #listAccessible} returns roots only. Wrap it in a Mockito {@code spy}
 * to verify calls.
 */
public class InMemoryAttachmentStore implements IAttachmentStore {

    private final Map<String, Attachment> blobs = new LinkedHashMap<>();
    private final Map<String, byte[]> bytes = new HashMap<>();
    private final Map<String, Derivation> derivations = new LinkedHashMap<>();
    private final Map<String, Set<String>> grants = new LinkedHashMap<>();
    private final Map<String, Integer> pageCounts = new HashMap<>();
    private int sequence;

    /** Adds a root blob owned by {@code ownerConversationId}. */
    public InMemoryAttachmentStore put(String storageRef, String filename, String mimeType, long size, String ownerConversationId) {
        blobs.put(storageRef, new Attachment(storageRef, filename, mimeType, size, ownerConversationId));
        return this;
    }

    /** Adds a root blob with content. */
    public InMemoryAttachmentStore put(String storageRef, String filename, String mimeType, byte[] content, String ownerConversationId) {
        put(storageRef, filename, mimeType, content.length, ownerConversationId);
        bytes.put(storageRef, content);
        return this;
    }

    /** Adds a derived blob of {@code rootRef}, owned with the root. */
    public InMemoryAttachmentStore putDerived(String storageRef, String filename, String mimeType, String rootRef,
                                              Derivation.Kind kind) {
        Attachment root = blobs.get(rootRef);
        blobs.put(storageRef, new Attachment(storageRef, filename, mimeType, 1, root.conversationId()));
        derivations.put(storageRef, Derivation.of(rootRef, kind, null));
        return this;
    }

    /** Whether {@code conversationId} holds a grant on {@code storageRef}. */
    public boolean isGranted(String storageRef, String conversationId) {
        return grants.getOrDefault(storageRef, Set.of()).contains(conversationId);
    }

    /** The number of grants on {@code storageRef}. */
    public int grantCount(String storageRef) {
        return grants.getOrDefault(storageRef, Set.of()).size();
    }

    /** The derived blobs of a root and kind, in insertion order. */
    public List<String> derivedRefs(String rootRef, Derivation.Kind kind) {
        List<String> refs = new ArrayList<>();
        derivations.forEach((ref, d) -> {
            if (rootRef.equals(d.rootRef()) && d.kind() == kind) {
                refs.add(ref);
            }
        });
        return refs;
    }

    private String root(String storageRef) {
        Derivation d = derivations.get(storageRef);
        return d != null ? d.rootRef() : storageRef;
    }

    private boolean canRead(Attachment a, String conversationId) {
        Attachment root = blobs.get(root(a.storageRef()));
        return root != null && (root.conversationId().equals(conversationId) || isGranted(root.storageRef(), conversationId));
    }

    private Attachment readable(String storageRef, String conversationId) throws AttachmentStoreException {
        Attachment a = storageRef != null ? blobs.get(storageRef) : null;
        if (a == null) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        if (!canRead(a, conversationId)) {
            throw new AttachmentAccessDeniedException("Access denied");
        }
        return a;
    }

    @Override
    public Attachment store(byte[] bytes, String declaredMime, String filename, String conversationId, String tenantId,
                            String userId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] load(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        readable(storageRef, requestingConversationId);
        return bytes.getOrDefault(storageRef, new byte[0]);
    }

    @Override
    public Attachment getMetadata(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        return readable(storageRef, requestingConversationId);
    }

    @Override
    public void grantAccess(String storageRef, String conversationId) throws AttachmentStoreException {
        if (!blobs.containsKey(storageRef)) {
            throw new AttachmentNotFoundException("Attachment not found");
        }
        grants.computeIfAbsent(root(storageRef), k -> new HashSet<>()).add(conversationId);
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
        return blobs.values().stream().filter(a -> !derivations.containsKey(a.storageRef()) && a.conversationId().equals(conversationId))
                .toList();
    }

    @Override
    public List<Attachment> listAccessible(String conversationId) {
        List<Attachment> result = new ArrayList<>();
        for (Attachment a : blobs.values()) {
            if (!derivations.containsKey(a.storageRef()) && canRead(a, conversationId)) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public Attachment storeDerived(byte[] content, String mimeType, String filename, Derivation derivation,
                                   String requestingConversationId)
            throws AttachmentStoreException {
        Attachment root = readable(root(derivation.rootRef()), requestingConversationId);
        String sha = DerivedBlobs.sha256Hex(content);
        for (Map.Entry<String, Derivation> e : derivations.entrySet()) {
            if (root.storageRef().equals(e.getValue().rootRef()) && sha.equals(e.getValue().sha256())) {
                return blobs.get(e.getKey());
            }
        }
        String ref = String.format("%024x", 0xd0000000L + ++sequence);
        // Owned with the root, never with the requesting conversation.
        Attachment stored = new Attachment(ref, filename, mimeType, content.length, root.conversationId());
        blobs.put(ref, stored);
        bytes.put(ref, content);
        derivations.put(ref, derivation.resolved(root.storageRef(), root.storageRef(), sha));
        return stored;
    }

    @Override
    public Optional<Derivation> getDerivation(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        readable(storageRef, requestingConversationId);
        return Optional.ofNullable(derivations.get(storageRef));
    }

    @Override
    public List<Attachment> listDerived(String rootRef, Derivation.Kind kind, String requestingConversationId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Attachment> findDerived(String rootRef, Derivation.Kind kind, String unitId, String requestingConversationId)
            throws AttachmentStoreException {
        readable(rootRef, requestingConversationId);
        Attachment found = null;
        for (Map.Entry<String, Derivation> e : derivations.entrySet()) {
            Derivation d = e.getValue();
            if (rootRef.equals(d.rootRef()) && d.kind() == kind && unitId != null && unitId.equals(d.unitId())) {
                found = blobs.get(e.getKey());
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public String rootOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        readable(storageRef, requestingConversationId);
        return root(storageRef);
    }

    @Override
    public OwnerRef ownerOf(String storageRef, String requestingConversationId) throws AttachmentStoreException {
        readable(storageRef, requestingConversationId);
        Attachment root = blobs.get(root(storageRef));
        return new OwnerRef(OwnerScope.CONVERSATION, root.conversationId(), null);
    }

    @Override
    public Integer pageCount(String rootRef, String requestingConversationId) throws AttachmentStoreException {
        readable(rootRef, requestingConversationId);
        return pageCounts.get(rootRef);
    }

    @Override
    public void setPageCount(String rootRef, int pages) {
        pageCounts.put(rootRef, pages);
    }

    @Override
    public QuotaReservation reserve(OwnerRef owner, String conversationId, String userId, long bytes, int count, Duration ttl) {
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
}
