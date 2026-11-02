/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.ResourceUtilities;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
public class WorkflowStore extends AbstractResourceStore<WorkflowConfiguration> implements IWorkflowStore {
    public static final String WORKFLOW_EXTENSIONS_FIELD = "workflowSteps";
    /**
     * Query paths into the PERSISTED document, so they must match what
     * {@link WorkflowConfiguration} actually serializes: {@code workflowSteps},
     * lower-case w.
     * <p>
     * They were spelled {@code WorkflowSteps} — and both MongoDB paths and
     * PostgreSQL JSON keys are case-sensitive, so every reverse lookup built on
     * them matched nothing, silently. That is what made
     * {@code RestWorkflowStore.deleteResourceSafely}'s "is anyone else still using
     * this?" guard a no-op: it always saw zero referencing workflows and
     * cascade-deleted configs that other workflows were still pointing at.
     */
    public static final String WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD = "workflowSteps.config.uri";
    public static final String WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD = "workflowSteps.extensions.dictionaries.config.uri";
    private static final String VERSION_QUERY_PARAM = "?version=";

    private final IDocumentDescriptorStore documentDescriptorStore;
    /**
     * Where the reverse lookup reads the referenced resource's LIVE version from,
     * whatever its type — the descriptor's lags it (see
     * {@link ResourceUtilities#clampToLiveVersion}). Resolved lazily: the library
     * reaches back to this store through the REST facades.
     */
    private final Provider<IResourceClientLibrary> resourceClientLibrary;

    @Inject
    public WorkflowStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, IDocumentDescriptorStore documentDescriptorStore,
            Provider<IResourceClientLibrary> resourceClientLibrary) {
        super(storageFactory, "workflows", documentBuilder, WorkflowConfiguration.class, WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD,
                WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD);
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceClientLibrary = resourceClientLibrary;
    }

    @Override
    public IResourceStore.IResourceId create(WorkflowConfiguration workflowConfiguration) throws IResourceStore.ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(workflowConfiguration.getWorkflowSteps(), WORKFLOW_EXTENSIONS_FIELD);
        return super.create(workflowConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, WorkflowConfiguration workflowConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(workflowConfiguration.getWorkflowSteps(), WORKFLOW_EXTENSIONS_FIELD);
        return super.update(id, version, workflowConfiguration);
    }

    @Override
    public List<DocumentDescriptor> getWorkflowDescriptorsContainingResource(String resourceURI, boolean includePreviousVersions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<DocumentDescriptor> ret = new LinkedList<>();
        ReverseLookup lookup = reverseLookup(resourceURI, includePreviousVersions);
        int version = lookup.startVersion();

        do {
            for (IResourceStore.IResourceId workflowId : referrersOf(lookup.uriWithoutVersion() + version)) {
                if (isStaleReference(workflowId.getId(), workflowId.getVersion())) {
                    continue;
                }

                boolean alreadyContainsResource = ret.stream().anyMatch(resource -> {
                    var id = RestUtilities.extractResourceId(resource.getResource()).getId();
                    return id.equals(workflowId.getId());
                });
                if (alreadyContainsResource) {
                    continue;
                }

                try {
                    var packageDescriptor = documentDescriptorStore.readDescriptor(workflowId.getId(), workflowId.getVersion());
                    ret.add(packageDescriptor);
                } catch (ResourceNotFoundException e) {
                    // skip, as this resource is not available anymore due to deletion
                }
            }

            version--;
        } while (includePreviousVersions && version >= 1);

        return ret;
    }

    @Override
    public List<IResourceStore.IResourceId> getWorkflowVersionsContainingResource(String resourceURI)
            throws IResourceStore.ResourceStoreException {
        ReverseLookup lookup = reverseLookup(resourceURI, true);
        Set<String> seen = new HashSet<>();
        List<IResourceStore.IResourceId> ret = new ArrayList<>();
        for (int version = lookup.startVersion(); version >= 1; version--) {
            for (IResourceStore.IResourceId workflowId : referrersOf(lookup.uriWithoutVersion() + version)) {
                if (seen.add(workflowId.getId() + "#" + workflowId.getVersion())) {
                    ret.add(workflowId);
                }
            }
        }
        return ret;
    }

    /** Where a reverse lookup for one resource URI starts. */
    private record ReverseLookup(String uriWithoutVersion, int startVersion) {
    }

    private ReverseLookup reverseLookup(String resourceURI, boolean walk) throws IResourceStore.ResourceStoreException {
        // Parsed, not scraped. The previous "everything after the last '='" split
        // threw NumberFormatException — undeclared by this interface — for any URI
        // without a version query or with a second query parameter, and callers such
        // as RestWorkflowStore.deleteResourceSafely feed step config.uri values in
        // verbatim. There the throw is caught and logged as "reference check failed",
        // so one unversioned reference turned cascade cleanup into a permanent no-op.
        URI parsedResourceUri = parseResourceUri(resourceURI);
        String resourceURIPart = RestUtilities.pathWithoutVersionQuery(parsedResourceUri);
        Integer version = resourceURIPart == null ? null : RestUtilities.extractResourceId(parsedResourceUri).getVersion();
        if (resourceURIPart == null || version == null || version < 1) {
            throw new IResourceStore.ResourceStoreException(
                    "Reverse lookup requires a versioned resource URI ('...?version=<n>' with n >= 1), got: " + resourceURI);
        }
        // Only a walk is clamped: an exact lookup is a fixed handful of queries, and
        // rewriting its version would answer for a version the caller did not ask
        // about.
        if (walk) {
            IResourceStore.IResourceId live = resourceClientLibrary.get().getCurrentResourceId(parsedResourceUri);
            version = ResourceUtilities.clampToLiveVersion(live == null ? null : live.getVersion(), version);
        }
        return new ReverseLookup(resourceURIPart + VERSION_QUERY_PARAM, version);
    }

    /**
     * Every workflow id/version, current or historical, that references exactly
     * {@code resourceURI} from a step's {@code config.uri} or a step's
     * dictionaries, sorted by id and then version, newest first.
     */
    private List<IResourceStore.IResourceId> referrersOf(String resourceURI) {
        List<IResourceStore.IResourceId> allIds = new LinkedList<>();

        // Search in config.uri field
        allIds.addAll(resourceStorage.findResourceIdsContaining(WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));
        allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, resourceURI));

        // Search in dictionaries config.uri field
        allIds.addAll(resourceStorage.findResourceIdsContaining(WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));
        allIds.addAll(resourceStorage.findHistoryResourceIdsContaining(WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD, resourceURI));

        Comparator<IResourceStore.IResourceId> comparator = Comparator.comparing(IResourceStore.IResourceId::getId)
                .thenComparingInt(IResourceStore.IResourceId::getVersion).reversed();
        return allIds.stream().sorted(comparator).collect(Collectors.toList());
    }

    /**
     * {@code URI.create} on a caller-supplied string, with its unchecked
     * {@link IllegalArgumentException} turned into {@code null} so the single
     * validation branch above reports every malformed input the same way.
     */
    private static URI parseResourceUri(String resourceURI) {
        if (resourceURI == null || resourceURI.isBlank()) {
            return null;
        }
        try {
            return URI.create(resourceURI);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
