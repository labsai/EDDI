/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Materializes, re-hydrates and grants a group discussion's shared attachments.
 * Extracted from {@code GroupConversationService} (Wave R, R1 step 1) as a pure
 * move — no behavior change.
 *
 * @author ginccc
 */
public class GroupAttachmentBinder {

    private static final Logger LOGGER = Logger.getLogger(GroupAttachmentBinder.class);

    private final IAttachmentStore attachmentStore;
    private final String defaultTenantId;

    public GroupAttachmentBinder(IAttachmentStore attachmentStore, String defaultTenantId) {
        this.attachmentStore = attachmentStore;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Materialize discussion attachments that arrived from a client. Equivalent to
     * {@link #materializeAttachments(GroupConversation, List, String)} with no
     * trusted source conversation, so every {@code storageRef}-only attachment is
     * refused.
     */
    public void materializeAttachments(GroupConversation gc, List<Attachment> incoming)
            throws AttachmentQuotaExceededException {
        materializeAttachments(gc, incoming, null);
    }

    /**
     * Materialize discussion attachments and bind them to the group conversation.
     * Inline base64 files are stored in the blob store owned by {@code gc.getId()}
     * (so they can be granted to members and reaped with the conversation) and
     * counted against {@code gc.getUserId()}, so the per-user quotas
     * ({@code eddi.attachments.max-per-user}, {@code max-total-bytes-per-user})
     * apply to a group upload exactly as to a 1:1 one. Hosted {@code url}
     * references pass through. The resulting list is stashed on the
     * {@link GroupConversation} for fan-out.
     * <p>
     * <strong>A {@code storageRef} is a capability, not a name.</strong> Passed
     * through unchecked, {@link #grantAndInjectAttachments} would grant every
     * member conversation read access to whatever blob the reference names. One is
     * therefore accepted only when {@code trustedSourceConversationId} — the
     * conversation server code took the reference from (the parent discussion, for
     * a nested group) — can read that blob, and {@code gc} is then granted access
     * too, so the next nesting level can make the same check against it. A
     * reference that arrived any other way is dropped with a warning.
     *
     * @param trustedSourceConversationId
     *            the conversation server code took the references from, or
     *            {@code null} when the attachments came from a client
     * @throws AttachmentQuotaExceededException
     *             when an inline file would exceed a per-conversation or per-user
     *             quota. Blobs this call had already stored are removed first, so a
     *             refused request leaves nothing behind
     */
    public void materializeAttachments(GroupConversation gc, List<Attachment> incoming, String trustedSourceConversationId)
            throws AttachmentQuotaExceededException {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        List<Attachment> materialized = new ArrayList<>();
        List<String> storedByThisCall = new ArrayList<>();
        for (Attachment a : incoming) {
            try {
                if (a.getUrl() != null && !a.getUrl().isBlank()) {
                    // Hosted URL — forwarded as-is; no blob store required.
                    materialized.add(a);
                } else if (a.getBase64Data() != null && !a.getBase64Data().isBlank()) {
                    if (attachmentStore == null) {
                        LOGGER.warn("Inline group attachment provided but no attachment store is configured; skipping it.");
                        continue;
                    }
                    byte[] bytes = Base64.getDecoder().decode(a.getBase64Data());
                    // The 6-argument overload: with the 5-argument one the blob had no
                    // owning user, so no per-user quota counted it and nothing keyed on
                    // the user (erasure, export) could find it.
                    var stored = attachmentStore.store(bytes, a.getMimeType(), a.getFileName(), gc.getId(), defaultTenantId,
                            gc.getUserId());
                    storedByThisCall.add(stored.storageRef());
                    materialized.add(new Attachment(stored.mimeType(), stored.filename(), stored.sizeBytes(), stored.storageRef()));
                } else if (a.getStorageRef() != null && !a.getStorageRef().isBlank()
                        && mayPassThrough(a.getStorageRef(), gc, trustedSourceConversationId)) {
                    materialized.add(a);
                }
            } catch (AttachmentQuotaExceededException e) {
                rollBack(storedByThisCall, gc.getId());
                LOGGER.warnf("Group attachment '%s' refused for group conversation '%s': the %s quota is exhausted",
                        sanitize(a.getFileName()), gc.getId(), e.getScope());
                throw e;
            } catch (Exception e) {
                LOGGER.warnf("Failed to materialize group attachment '%s': %s", sanitize(a.getFileName()),
                        sanitize(e.getMessage()));
            }
        }
        if (!materialized.isEmpty()) {
            gc.setAttachments(materialized);
            LOGGER.infof("Group conversation '%s' has %d shared attachment(s)", gc.getId(), materialized.size());
        }
    }

    /**
     * Whether a pre-stored reference may be shared with {@code gc}'s members: only
     * when server code took it from {@code trustedSourceConversationId} and that
     * conversation can read the blob (as owner or grantee). On success {@code gc}
     * is granted read access as well.
     */
    private boolean mayPassThrough(String storageRef, GroupConversation gc, String trustedSourceConversationId) {
        if (trustedSourceConversationId == null || trustedSourceConversationId.isBlank() || attachmentStore == null) {
            LOGGER.warnf("Refused an attachment storageRef for group conversation '%s': only references taken from the "
                    + "caller's own conversation are shared", gc.getId());
            return false;
        }
        try {
            attachmentStore.getMetadata(storageRef, trustedSourceConversationId);
            attachmentStore.grantAccess(storageRef, gc.getId());
            return true;
        } catch (Exception e) {
            // Both values are sanitized at the call site (LogSanitizer's rule): the
            // source id is a caller-supplied conversation id and the store's message
            // may echo the reference it refused.
            LOGGER.warnf("Refused an attachment storageRef for group conversation '%s': its source conversation '%s' "
                    + "cannot read it (%s)", gc.getId(), sanitize(trustedSourceConversationId), sanitize(e.getMessage()));
            return false;
        }
    }

    private void rollBack(List<String> storageRefs, String ownerConversationId) {
        for (String ref : storageRefs) {
            try {
                attachmentStore.delete(ref, ownerConversationId);
            } catch (Exception e) {
                LOGGER.warnf("Could not remove attachment '%s' of refused group request '%s': %s", sanitize(ref),
                        ownerConversationId, sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Re-hydrate a group conversation's shared attachments from the durable blob
     * store. {@link GroupConversation#getAttachments()} is {@code @JsonIgnore}
     * transient, so a GC reloaded on a HITL resume has lost them; without this, a
     * member whose first turn lands after the resume gets neither the blob grant
     * nor the {@code attachment_*} context from {@link #grantAndInjectAttachments}.
     * <p>
     * No-op when attachments are already present (fresh discussion — set by
     * {@link #materializeAttachments}) or when the store holds none. URL-only
     * attachments are not blob-backed and are intentionally not recovered here.
     */
    public void rehydrateAttachmentsFromStore(GroupConversation gc) {
        if (attachmentStore == null || gc.getAttachments() != null && !gc.getAttachments().isEmpty()) {
            return;
        }
        try {
            var storedAttachments = attachmentStore.listByConversation(gc.getId());
            if (storedAttachments.isEmpty()) {
                return;
            }
            List<Attachment> rehydrated = new ArrayList<>();
            for (var stored : storedAttachments) {
                rehydrated.add(new Attachment(stored.mimeType(), stored.filename(),
                        stored.sizeBytes(), stored.storageRef()));
            }
            gc.setAttachments(rehydrated);
            LOGGER.infof("Re-hydrated %d shared attachment(s) for group conversation '%s' from the blob store",
                    rehydrated.size(), gc.getId());
        } catch (Exception e) {
            LOGGER.warnf("Failed to re-hydrate shared attachments for group conversation '%s': %s",
                    gc.getId(), e.getMessage());
        }
    }

    /**
     * Grant a member conversation access to the group's stored attachments and
     * inject them as {@code attachment_*} context on the member's first turn. URL
     * references are forwarded as-is (no grant needed).
     */
    public void grantAndInjectAttachments(GroupConversation gc, String memberConvId, Map<String, Context> context) {
        List<Attachment> atts = gc.getAttachments();
        if (atts == null || atts.isEmpty()) {
            return;
        }
        int index = 0;
        for (Attachment a : atts) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (a.getStorageRef() != null) {
                if (attachmentStore != null) {
                    try {
                        attachmentStore.grantAccess(a.getStorageRef(), memberConvId);
                    } catch (Exception e) {
                        LOGGER.warnf("Failed to grant attachment '%s' to member conversation '%s': %s",
                                a.getStorageRef(), memberConvId, e.getMessage());
                        continue;
                    }
                }
                value.put("storageRef", a.getStorageRef());
                if (a.getFileName() != null) {
                    value.put("fileName", a.getFileName());
                }
            } else if (a.getUrl() != null) {
                value.put("mimeType", a.getMimeType());
                value.put("url", a.getUrl());
                if (a.getFileName() != null) {
                    value.put("fileName", a.getFileName());
                }
            } else {
                continue;
            }
            context.put("attachment_" + index, new Context(Context.ContextType.object, value));
            index++;
        }
    }
}
