/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.parser.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.parser.IParserStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestParserStore implements IRestParserStore {
    private static final Logger log = Logger.getLogger(RestParserStore.class);

    private final IParserStore parserStore;
    private final RestVersionInfo<ParserConfiguration> restVersionInfo;
    private final ResourceAccessGuard resourceAccessGuard;

    @Inject
    public RestParserStore(IParserStore parserStore, IDocumentDescriptorStore documentDescriptorStore,
            ResourceAccessGuard resourceAccessGuard) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, parserStore, documentDescriptorStore, resourceAccessGuard);
        this.parserStore = parserStore;
        this.resourceAccessGuard = resourceAccessGuard;
    }

    /**
     * Refuses a parser whose extensions name a dictionary the caller may not read.
     * <p>
     * The same check {@code RestWorkflowStore} runs on a workflow step's
     * references. A parser document is resolved below the access guard, by the
     * standalone parse endpoint and by an export of an agent that uses it, so a
     * reference to another space's private dictionary, saved unchecked, exposed
     * that dictionary's expressions or its whole content. A no-op unless workspaces
     * are enforced, and for administrators.
     *
     * @param alreadyReferenced
     *            resource keys the stored version already references; keeping one
     *            grants nothing new
     */
    private void requireAccessToReferences(ParserConfiguration parserConfiguration, Set<String> alreadyReferenced) {
        if (resourceAccessGuard == null || resourceAccessGuard.seesEverything()) {
            return;
        }
        for (Map.Entry<String, URI> reference : referencedResources(parserConfiguration).entrySet()) {
            if (alreadyReferenced.contains(reference.getKey())) {
                continue;
            }
            URI uri = reference.getValue();
            String label = "configuration referenced by this parser (" + uri.getHost() + ")";
            resourceAccessGuard.requireAccess(RestUtilities.extractResourceId(uri).getId(), AccessLevel.VIEW, label);
        }
    }

    static Map<String, URI> referencedResources(ParserConfiguration parserConfiguration) {
        Map<String, URI> references = new LinkedHashMap<>();
        if (parserConfiguration != null) {
            RestWorkflowStore.addExtensionReferences(parserConfiguration.getExtensions(), references);
        }
        return references;
    }

    private Set<String> referencedKeysOfStoredVersion(String id, Integer version) {
        try {
            Integer resolved = restVersionInfo.validateParameters(id, version);
            return referencedResources(parserStore.read(id, resolved)).keySet();
        } catch (Exception e) {
            log.debugf("Could not read parser %s v%s to compare its references: %s", sanitize(id), version, sanitize(e.getMessage()));
            return Set.of();
        }
    }

    @Override
    public List<DocumentDescriptor> readParserDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public ParserConfiguration readParser(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateParser(String id, Integer version, ParserConfiguration parserConfiguration) {
        restVersionInfo.requireEditAccess(id);
        requireAccessToReferences(parserConfiguration, referencedKeysOfStoredVersion(id, version));
        return restVersionInfo.update(id, version, parserConfiguration);
    }

    @Override
    public Response createParser(ParserConfiguration parserConfiguration) {
        requireAccessToReferences(parserConfiguration, Set.of());
        return restVersionInfo.create(parserConfiguration);
    }

    @Override
    public Response deleteParser(String id, Integer version, Boolean permanent) {
        return restVersionInfo.delete(id, version, permanent);
    }

    @Override
    public Response duplicateParser(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ParserConfiguration parserConfiguration = restVersionInfo.read(id, version);
        // The copy belongs to whoever duplicates it, so its references are theirs to
        // justify.
        requireAccessToReferences(parserConfiguration, Set.of());
        return restVersionInfo.create(parserConfiguration);
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public void requireCurrentVersionAccess(String id) {
        restVersionInfo.requireViewAccess(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return parserStore.getCurrentResourceId(id);
    }
}
