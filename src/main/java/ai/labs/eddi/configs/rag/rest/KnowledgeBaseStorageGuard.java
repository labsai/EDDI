/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.documents.index.DocumentIndexEligibility;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
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
 * already uses if the caller may edit that knowledge base too. A check that
 * cannot see every other knowledge base — too many to scan, or one that cannot
 * be read — refuses the location. Checked only when the value is new or
 * changed, so a stored configuration is never refused on a save that does not
 * touch it.</li>
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
        requireEligibleIfDocumentIndex(config);
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
        // A name-layout knowledge base without an explicit location is stored where
        // its name maps. That location changes - unchecked - when it is renamed, and
        // also when an explicit location is dropped: renaming while keeping the
        // explicit location and then removing it reached the default store of a
        // different name. So does a change of backend or of the server it connects
        // to: the same name maps to that backend's or server's default store, which
        // can be another 6.5.0 knowledge base of the same name there. Any of these
        // moves it to a store of its own, keyed by its id.
        boolean losesExplicitLocation = previous != null && KnowledgeBaseStorage.explicitPhysicalName(previous) != null;
        if (previous != null && !KnowledgeBaseStorage.usesIdNamespace(updated)
                && (!Objects.equals(previous.getName(), updated.getName()) || losesExplicitLocation
                        || KnowledgeBaseStorage.movesServer(previous, updated))
                && KnowledgeBaseStorage.explicitPhysicalName(updated) == null) {
            LOGGER.warnf("Knowledge base %s (was '%s', now '%s') changed its name, backend or server, or dropped its explicit location,"
                    + " while still in the 6.5.0 name layout. It now uses "
                    + "storeNamespace '%s': a store of its own, keyed by its id, rather than the one named after '%s'.",
                    LogSanitizer.sanitize(id), LogSanitizer.sanitize(previous.getName()), LogSanitizer.sanitize(updated.getName()),
                    KnowledgeBaseStorage.NAMESPACE_ID, LogSanitizer.sanitize(updated.getName()));
            updated.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        }
        requireDocumentIndexTransitionAllowed(id, previous, updated);
        boolean becomesDocumentIndex = DocumentIndexEligibility.isDocumentIndex(updated) && !DocumentIndexEligibility.isDocumentIndex(previous);
        String before = previous == null ? null : KnowledgeBaseStorage.explicitPhysicalName(previous);
        String after = KnowledgeBaseStorage.explicitPhysicalName(updated);
        boolean storeTypeChanged = previous != null && !Objects.equals(previous.getStoreType(), updated.getStoreType());
        // Turning the flag on re-checks an unchanged location too: it may be shared
        // with another knowledge base, which a document index may not do.
        if (after != null && (!after.equals(before) || storeTypeChanged || becomesDocumentIndex)) {
            requireUsableExplicitLocation(id, updated);
        }
    }

    /**
     * Who uses a RAG configuration as a workflow step. Optional so the guard can be
     * built with {@code new} (as {@link RestRagStore} does, which hands it on);
     * without it, turning {@code documentIndex} on is refused, because the check it
     * would run cannot be made.
     */
    private Provider<IWorkflowStore> workflowStore;

    @Inject
    void useWorkflowStore(Provider<IWorkflowStore> workflowStore) {
        this.workflowStore = workflowStore;
    }

    /** A flagged configuration must be eligible. */
    private static void requireEligibleIfDocumentIndex(RagConfiguration config) {
        if (!DocumentIndexEligibility.isDocumentIndex(config)) {
            return;
        }
        String reason = DocumentIndexEligibility.ineligibility(config);
        if (reason != null) {
            throw new BadRequestException("documentIndex: " + reason + ".");
        }
    }

    /**
     * The document-index rules of an update — enforced here, at the RAG side, so
     * the separation from agents' knowledge bases holds whichever configuration is
     * edited first:
     * <ul>
     * <li>a flagged configuration must be eligible;</li>
     * <li>once a configuration is a document index it stays one, and its storage —
     * store type, server, layout, location — cannot move: chunks may exist, and an
     * LLM task may name it. Switched off, its users' chunks would sit in a store
     * that agents' retrieval may read; moved, they would be stranded where nothing
     * deletes them. Delete the configuration instead (its chunks go with the
     * store);</li>
     * <li>it cannot be turned on while a workflow uses the configuration as a RAG
     * step — the workflow side refuses the reverse order.</li>
     * </ul>
     */
    private void requireDocumentIndexTransitionAllowed(String id, RagConfiguration previous, RagConfiguration updated) {
        boolean wasIndex = DocumentIndexEligibility.isDocumentIndex(previous);
        boolean isIndex = DocumentIndexEligibility.isDocumentIndex(updated);
        if (wasIndex && !isIndex) {
            throw new BadRequestException("documentIndex cannot be switched off: this RAG configuration is a document index and may "
                    + "hold users' document chunks. Delete it instead, after removing it from every LLM task's documents.index.rag.");
        }
        if (wasIndex && (!Objects.equals(KnowledgeBaseStorage.locationKey(id, previous), KnowledgeBaseStorage.locationKey(id, updated))
                || KnowledgeBaseStorage.movesServer(previous, updated))) {
            throw new BadRequestException("The storage of a document index cannot be moved (store type, server, layout or location): "
                    + "the document chunks already written would be left where nothing deletes them.");
        }
        requireEligibleIfDocumentIndex(updated);
        if (isIndex && !wasIndex) {
            requireNoWorkflowUses(id);
        }
    }

    private void requireNoWorkflowUses(String id) {
        if (workflowStore == null) {
            throw new BadRequestException("documentIndex cannot be turned on here: the workflows that may use this RAG configuration "
                    + "cannot be checked.");
        }
        List<DocumentDescriptor> users;
        try {
            Integer current = ragStore.getCurrentResourceId(id).getVersion();
            // Every version of this configuration, as referenced by the current
            // version of any workflow.
            users = workflowStore.get().getWorkflowDescriptorsContainingResource(IRestRagStore.resourceURI + id + "?version=" + current, true);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException | RuntimeException e) {
            // Fail closed, like the location scan.
            throw new IllegalStateException("Could not list the workflows that use knowledge base " + id
                    + " to check that it may become a document index", e);
        }
        if (users != null && !users.isEmpty()) {
            List<String> named = users.stream()
                    .map(descriptor -> descriptor.getName() != null && !descriptor.getName().isBlank()
                            ? descriptor.getName()
                            : String.valueOf(descriptor.getResource()))
                    .toList();
            throw new BadRequestException("documentIndex cannot be turned on while a workflow uses this RAG configuration as a RAG step "
                    + "(" + String.join(", ", named) + "): a document index is never an agent's knowledge base. Remove the step first.");
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
        if ("elasticsearch".equals(config.getStoreType()) && !KnowledgeBaseStorage.isPlainElasticsearchIndex(explicit)) {
            throw new BadRequestException("storeParameters." + parameter + " must name one index (lower-case letters, digits, "
                    + "'_', '-' and '.'): Elasticsearch reads it as an index expression, so wildcards, lists and exclusions "
                    + "would search other indexes.");
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
        for (Occupant other : occupantsOf(ownKey, selfId)) {
            // A document index shares its store with nobody, in either direction: a
            // knowledge base in the 6.5.0 layout reads its store unfiltered, and
            // sharing with any other one is no use to an index.
            if (other.documentIndex() || DocumentIndexEligibility.isDocumentIndex(config)) {
                throw new BadRequestException("storeParameters." + parameter + " '" + explicit + "' is already the location of "
                        + "another knowledge base, and a document index shares its location with no other knowledge base. "
                        + "Choose a location of this knowledge base's own.");
            }
            String otherId = other.id();
            if (!resourceAccessGuard.hasAccess(otherId, AccessLevel.EDIT)) {
                throw new BadRequestException("storeParameters." + parameter + " '" + explicit + "' is already the location of "
                        + "another knowledge base, which you cannot edit. Choose a location of this knowledge base's own.");
            }
            LOGGER.infof("Knowledge base %s shares its explicit %s location with knowledge base %s (the caller may edit both)",
                    LogSanitizer.sanitize(selfId == null ? "(new)" : selfId), LogSanitizer.sanitize(parameter),
                    LogSanitizer.sanitize(otherId));
        }
    }

    /** Another knowledge base whose explicit location matches. */
    private record Occupant(String id, boolean documentIndex) {
    }

    /** The other knowledge bases whose explicit location matches. */
    private List<Occupant> occupantsOf(String collisionKey, String selfId) {
        List<Occupant> matches = new ArrayList<>();
        boolean complete = false;
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
                complete = true;
                break;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                Occupant other = occupantOf(descriptor, selfId, collisionKey);
                if (other != null) {
                    matches.add(other);
                }
            }
            if (descriptors.size() < PAGE_SIZE) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            // Fail closed: a collision with a knowledge base past the scan budget is
            // exactly as unauthorized as one inside it.
            throw new BadRequestException("The explicit storage location could not be checked against every knowledge base (more than "
                    + MAX_PAGES * PAGE_SIZE + " exist). Omit it to get a location of this knowledge base's own.");
        }
        return matches;
    }

    private Occupant occupantOf(DocumentDescriptor descriptor, String selfId, String collisionKey) {
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
            return explicit != null && KnowledgeBaseStorage.collisionKey(other, explicit).equals(collisionKey)
                    ? new Occupant(resourceId.getId(), DocumentIndexEligibility.isDocumentIndex(other))
                    : null;
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Deleted since it was listed: it no longer owns a location.
            LOGGER.debugf("Skipping knowledge base %s in the storage collision check: %s", LogSanitizer.sanitize(resourceId.getId()),
                    e.getMessage());
            return null;
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            // Fail closed, like a scan past its page budget: the knowledge base that
            // cannot be read may be the very one that owns this location.
            throw new IllegalStateException("Could not read knowledge base " + resourceId.getId()
                    + " to check the explicit storage location against it", e);
        }
    }
}
