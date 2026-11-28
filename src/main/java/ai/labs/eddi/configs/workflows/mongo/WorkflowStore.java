/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.mongo;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.ResourceUtilities;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.WorkflowConfigSaveValidator;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.ReferringVersions;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.RuntimeUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
        WorkflowConfigSaveValidator.validate(workflowConfiguration);
        trimReferenceUris(workflowConfiguration);
        return super.create(workflowConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, WorkflowConfiguration workflowConfiguration)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(workflowConfiguration.getWorkflowSteps(), WORKFLOW_EXTENSIONS_FIELD);
        WorkflowConfigSaveValidator.validate(workflowConfiguration);
        trimReferenceUris(workflowConfiguration);
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

    /**
     * Stores the two reference fields the reverse lookup queries
     * ({@link #WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD},
     * {@link #WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD}) without
     * surrounding whitespace. The save check and the runtime
     * ({@code ResourceUris.require}) both trim a padded {@code config.uri}, but the
     * lookup is an exact match on the stored value: a padded reference was
     * invisible to it, and the in-use guard let the resource it named be deleted.
     */
    static void trimReferenceUris(WorkflowConfiguration workflow) {
        if (workflow == null || workflow.getWorkflowSteps() == null) {
            return;
        }
        for (WorkflowConfiguration.WorkflowStep step : workflow.getWorkflowSteps()) {
            if (step == null) {
                continue;
            }
            step.setConfig(withTrimmedUri(step.getConfig()));
            if (step.getExtensions() != null && step.getExtensions().get("dictionaries") instanceof List<?> dictionaries) {
                List<Object> trimmed = new ArrayList<>(dictionaries.size());
                boolean changed = false;
                for (Object entry : dictionaries) {
                    Object next = entry;
                    if (entry instanceof Map<?, ?> dictionary && dictionary.get("config") instanceof Map<?, ?> config) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) config;
                        Map<String, Object> newConfig = withTrimmedUri(typed);
                        if (newConfig != config) {
                            Map<Object, Object> copy = new LinkedHashMap<>(dictionary);
                            copy.put("config", newConfig);
                            next = copy;
                            changed = true;
                        }
                    }
                    trimmed.add(next);
                }
                if (changed) {
                    Map<String, Object> extensions = new LinkedHashMap<>(step.getExtensions());
                    extensions.put("dictionaries", trimmed);
                    step.setExtensions(extensions);
                }
            }
        }
    }

    /**
     * {@code config} itself when its uri needs no trimming, else a trimmed copy.
     */
    private static Map<String, Object> withTrimmedUri(Map<String, Object> config) {
        if (config == null || !(config.get("uri") instanceof String uri) || uri.equals(uri.trim())) {
            return config;
        }
        Map<String, Object> copy = new LinkedHashMap<>(config);
        copy.put("uri", uri.trim());
        return copy;
    }

    @Override
    public ReferringVersions findWorkflowVersionsReferencing(Collection<String> resourceUris) {
        List<IResourceStore.IResourceId> live = new ArrayList<>();
        List<IResourceStore.IResourceId> historical = new ArrayList<>();
        List<String> uris = new ArrayList<>(new LinkedHashSet<>(resourceUris));
        // From the primary, for the reason AgentStore.findAgentVersionsListing gives.
        var storage = resourceStorage.primaryReads();
        for (int from = 0; from < uris.size(); from += IAgentStore.REFERENCE_QUERY_CHUNK) {
            List<String> chunk = uris.subList(from, Math.min(uris.size(), from + IAgentStore.REFERENCE_QUERY_CHUNK));
            for (String field : List.of(WORKFLOW_EXTENSIONS_CONFIG_URI_FIELD, WORKFLOW_EXTENSIONS_DICTIONARIES_CONFIG_URI_FIELD)) {
                live.addAll(storage.findResourceIdsContainingAny(field, chunk));
                historical.addAll(storage.findHistoryResourceIdsContainingAny(field, chunk));
            }
        }
        return new ReferringVersions(live, historical);
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
