/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Materialize discussion attachments and bind them to the group conversation.
     * Inline base64 files are stored in the blob store owned by {@code gc.getId()}
     * (so they can be granted to members and reaped with the conversation); hosted
     * {@code url} references and pre-stored {@code storageRef}s pass through. The
     * resulting list is stashed on the {@link GroupConversation} for fan-out.
     */
    public void materializeAttachments(GroupConversation gc, List<Attachment> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        List<Attachment> materialized = new ArrayList<>();
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
                    var stored = attachmentStore.store(bytes, a.getMimeType(), a.getFileName(), gc.getId(), defaultTenantId);
                    materialized.add(new Attachment(stored.mimeType(), stored.filename(), stored.sizeBytes(), stored.storageRef()));
                } else if (a.getStorageRef() != null && !a.getStorageRef().isBlank()) {
                    materialized.add(a);
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to materialize group attachment '%s': %s", a.getFileName(), e.getMessage());
            }
        }
        if (!materialized.isEmpty()) {
            gc.setAttachments(materialized);
            LOGGER.infof("Group conversation '%s' has %d shared attachment(s)", gc.getId(), materialized.size());
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
