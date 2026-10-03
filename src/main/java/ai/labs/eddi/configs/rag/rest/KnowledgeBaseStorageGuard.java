/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides, when a knowledge base is written, which vector store it may address
 * — see {@link KnowledgeBaseStorage} for the layouts.
 *
 * <ul>
 * <li>Every <strong>new</strong> knowledge base — created, duplicated or
 * imported — gets the per-id layout. Choosing the 6.5.0 name layout for a new
 * one is what would let it share another knowledge base's store just by sharing
 * its name.</li>
 * <li>An <strong>update</strong> that omits {@code storeNamespace} keeps the
 * stored value, so a client that does not know the field cannot move a
 * knowledge base's store by saving it. Switching back from {@code "id"} to
 * {@code "name"} is refused. Renaming a knowledge base that still uses the name
 * layout moves it to the per-id layout: in 6.5.0 a rename already moved it to a
 * new, empty store (the one named after the new name — possibly another
 * knowledge base's), so the only thing that changes is that the new store is
 * its own.</li>
 * <li>An <strong>explicit</strong> physical location ({@code table},
 * {@code collectionName}, {@code indexName}) may not use EDDI's reserved
 * {@code eddi_kb} prefix, and may only name a location another knowledge base
 * already uses if the caller may edit that knowledge base too. Checked only
 * when the value is new or changed, so a stored configuration is never refused
 * on a save that does not touch it.</li>
 * </ul>
 */
@ApplicationScoped
public class KnowledgeBaseStorageGuard {

    private static final Logger LOGGER = Logger.getLogger(KnowledgeBaseStorageGuard.class);

    private static final String RAG_DESCRIPTOR_TYPE = "ai.labs.rag";
    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 40;

    private final IRagStore ragStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public KnowledgeBaseStorageGuard(IRagStore ragStore, IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard) {
        this.ragStore = ragStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceAccessGuard = resourceAccessGuard;
    }

    /**
     * Stamps the per-id layout on a knowledge base about to be created, and checks
     * an explicit physical location.
     *
     * @param refuseNameLayout
     *            {@code true} on the REST create, where asking for {@code "name"}
     *            is refused with a 400; {@code false} on duplicate and import,
     *            where the copied value is simply replaced
     */
    public void prepareNew(RagConfiguration config, boolean refuseNameLayout) {
        if (config == null) {
            return;
        }
        if (refuseNameLayout && KnowledgeBaseStorage.NAMESPACE_NAME.equals(config.getStoreNamespace())) {
            throw new BadRequestException("storeNamespace '" + KnowledgeBaseStorage.NAMESPACE_NAME + "' is the 6.5.0 layout, kept only "
                    + "for knowledge bases that already use it. A new knowledge base is addressed by its id; omit storeNamespace.");
        }
        config.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        requireUsableExplicitLocation(null, config);
    }

    /**
     * Carries the layout forward, refuses a switch back to the name layout,
     * migrates a renamed name-layout knowledge base, and checks a changed explicit
     * physical location.
     *
     * @param previous
     *            the version being replaced, or {@code null} when it could not be
     *            read (the update itself then fails on its own terms)
     */
    public void prepareUpdate(String id, RagConfiguration previous, RagConfiguration updated) {
        if (updated == null) {
            return;
        }
        String previousNamespace = previous == null ? null : previous.getStoreNamespace();
        if (updated.getStoreNamespace() == null) {
            updated.setStoreNamespace(previousNamespace);
        }
        if (KnowledgeBaseStorage.usesIdNamespace(previous) && !KnowledgeBaseStorage.usesIdNamespace(updated)) {
            throw new BadRequestException("This knowledge base is addressed by its id; it cannot be switched back to storeNamespace '"
                    + KnowledgeBaseStorage.NAMESPACE_NAME + "', which would point it at whatever store its name maps to.");
        }
        if (previous != null && !KnowledgeBaseStorage.usesIdNamespace(updated)
                && !Objects.equals(previous.getName(), updated.getName())
                && KnowledgeBaseStorage.explicitPhysicalName(updated) == null) {
            LOGGER.warnf("Knowledge base %s was renamed from '%s' to '%s' while still in the 6.5.0 name layout. It now uses "
                    + "storeNamespace '%s': a store of its own, keyed by its id, rather than the one named after '%s'.",
                    LogSanitizer.sanitize(id), LogSanitizer.sanitize(previous.getName()), LogSanitizer.sanitize(updated.getName()),
                    KnowledgeBaseStorage.NAMESPACE_ID, LogSanitizer.sanitize(updated.getName()));
            updated.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        }
        String before = previous == null ? null : KnowledgeBaseStorage.explicitPhysicalName(previous);
        String after = KnowledgeBaseStorage.explicitPhysicalName(updated);
        boolean storeTypeChanged = previous != null && !Objects.equals(previous.getStoreType(), updated.getStoreType());
        if (after != null && (!after.equals(before) || storeTypeChanged)) {
            requireUsableExplicitLocation(id, updated);
        }
    }

    private void requireUsableExplicitLocation(String selfId, RagConfiguration config) {
        String explicit = KnowledgeBaseStorage.explicitPhysicalName(config);
        if (explicit == null) {
            return;
        }
        String parameter = KnowledgeBaseStorage.physicalNameParameter(config.getStoreType());
        // The location must be written out, not referenced. A ${vars:...} value is
        // resolved only when the store is built — and global variables are writable
        // by any editor — so a reference here could resolve to another knowledge
        // base's table, or to SQL (the pgvector store interpolates the name), after
        // every check below had passed on the reference text. Stored values that
        // already use one are not re-judged; EmbeddingStoreFactory validates what
        // they resolve to.
        if (explicit.contains("${")) {
            throw new BadRequestException("storeParameters." + parameter + " must be a literal name, not a ${...} reference: "
                    + "the location decides which knowledge base's documents an agent reads, so it is checked when it is saved.");
        }
        if ("pgvector".equals(config.getStoreType()) && !KnowledgeBaseStorage.isPlainPgIdentifier(explicit)) {
            throw new BadRequestException("storeParameters." + parameter + " must be a plain table name, optionally qualified "
                    + "with a schema (letters, digits, '_' and '$'): the pgvector store puts it into SQL as it is, so quotes, "
                    + "whitespace and punctuation are refused.");
        }
        if (KnowledgeBaseStorage.isReservedName(explicit)) {
            throw new BadRequestException("storeParameters." + parameter + " may not start with '" + KnowledgeBaseStorage.RESERVED_PREFIX
                    + "': EDDI derives every knowledge base's default location from that prefix, so the name could address "
                    + "another knowledge base's store. Choose another name, or omit it to get a location of this knowledge base's own.");
        }
        String ownKey = KnowledgeBaseStorage.collisionKey(config, explicit);
        for (String otherId : idsUsing(ownKey, selfId)) {
            if (!resourceAccessGuard.hasAccess(otherId, AccessLevel.EDIT)) {
                throw new BadRequestException("storeParameters." + parameter + " '" + explicit + "' is already the location of "
                        + "another knowledge base, which you cannot edit. Choose a location of this knowledge base's own.");
            }
            LOGGER.infof("Knowledge base %s shares its explicit %s location with knowledge base %s (the caller may edit both)",
                    LogSanitizer.sanitize(selfId == null ? "(new)" : selfId), LogSanitizer.sanitize(parameter),
                    LogSanitizer.sanitize(otherId));
        }
    }

    /** Ids of the other knowledge bases whose explicit location matches. */
    private List<String> idsUsing(String collisionKey, String selfId) {
        List<String> matches = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<DocumentDescriptor> descriptors;
            try {
                // Unrestricted on purpose: a collision with a knowledge base the caller
                // cannot even see is exactly the case to refuse.
                descriptors = documentDescriptorStore.readDescriptors(RAG_DESCRIPTOR_TYPE, null, page, PAGE_SIZE, false);
            } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
                throw new IllegalStateException("Could not list knowledge bases to check the storage location", e);
            }
            if (descriptors == null || descriptors.isEmpty()) {
                break;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                String otherKey = explicitKeyOf(descriptor, selfId);
                if (otherKey != null && otherKey.equals(collisionKey)) {
                    matches.add(RestUtilities.extractResourceId(descriptor.getResource()).getId());
                }
            }
            if (descriptors.size() < PAGE_SIZE) {
                break;
            }
        }
        return matches;
    }

    private String explicitKeyOf(DocumentDescriptor descriptor, String selfId) {
        if (descriptor == null || descriptor.getResource() == null) {
            return null;
        }
        IResourceStore.IResourceId resourceId;
        try {
            resourceId = RestUtilities.extractResourceId(descriptor.getResource());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (resourceId == null || resourceId.getId() == null || resourceId.getId().equals(selfId)) {
            return null;
        }
        try {
            RagConfiguration other = ragStore.read(resourceId.getId(), resourceId.getVersion());
            String explicit = KnowledgeBaseStorage.explicitPhysicalName(other);
            return explicit == null ? null : KnowledgeBaseStorage.collisionKey(other, explicit);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException | RuntimeException e) {
            LOGGER.debugf("Skipping knowledge base %s in the storage collision check: %s", LogSanitizer.sanitize(resourceId.getId()),
                    e.getMessage());
            return null;
        }
    }
}
