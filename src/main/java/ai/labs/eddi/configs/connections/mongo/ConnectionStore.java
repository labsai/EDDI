/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.mongo;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

/**
 * Versioned store for connection configurations, over whichever backend
 * {@link IResourceStorageFactory} is configured — the package name says
 * {@code mongo} to match its siblings, but nothing here is Mongo-specific.
 * <p>
 * Named lookup goes through the descriptor index rather than a backend-specific
 * query, for the same reason {@code PromptSnippetService} does: a query written
 * against one storage engine is the one thing that would make this store not
 * DB-agnostic, and the set is small and cached a layer up.
 */
@ApplicationScoped
public class ConnectionStore extends AbstractResourceStore<ConnectionConfiguration> implements IConnectionStore {

    private static final Logger LOGGER = Logger.getLogger(ConnectionStore.class);

    /** Descriptor type for connection resources. */
    public static final String RESOURCE_TYPE = "ai.labs.connection";

    private final IDocumentDescriptorStore descriptorStore;

    @Inject
    public ConnectionStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, IDocumentDescriptorStore descriptorStore) {
        super(storageFactory, "connections", documentBuilder, ConnectionConfiguration.class);
        this.descriptorStore = descriptorStore;
    }

    /**
     * Rejects an unsafe connection at save time.
     * <p>
     * Deliberately on the write path only: a document already stored must keep
     * loading even if the rules tightened after it was written, or a validator
     * change would take down running agents rather than failing an author's save.
     */
    @Override
    protected void validate(ConnectionConfiguration content) {
        if (content != null) {
            content.validate();
        }
    }

    @Override
    public ConnectionConfiguration readByName(String tenantId, String name) throws ResourceStoreException {
        Match match = findByName(tenantId, name);
        return match == null ? null : match.connection();
    }

    @Override
    public String idOfName(String tenantId, String name) throws ResourceStoreException {
        Match match = findByName(tenantId, name);
        return match == null ? null : match.id();
    }

    /** One connection and the resource id it lives under. */
    private record Match(String id, ConnectionConfiguration connection) {
    }

    private Match findByName(String tenantId, String name) throws ResourceStoreException {
        if (name == null || name.isBlank()) {
            return null;
        }
        String effectiveTenant = ConnectionConfiguration.effectiveTenant(tenantId);
        try {
            // null rather than "": a blank filter is still a filter, and the descriptor
            // store turns it into five OR'd regex clauses over fields a connection
            // lookup does not consult.
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(RESOURCE_TYPE, null, 0, IDescriptorStore.NO_LIMIT, false);
            if (descriptors == null) {
                return null;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                URI resourceUri = descriptor.getResource();
                if (resourceUri == null) {
                    continue;
                }
                String id = idOf(resourceUri);
                ConnectionConfiguration candidate = readIfPresent(id, resourceUri);
                if (candidate == null) {
                    continue;
                }
                if (name.equals(candidate.getName()) && effectiveTenant.equals(ConnectionConfiguration.effectiveTenant(candidate))) {
                    return new Match(id, candidate);
                }
            }
            return null;
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Reads one descriptor's resource, tolerating a dangling reference but never a
     * store failure.
     * <p>
     * The two cases look alike here and could not be more different downstream. A
     * descriptor whose resource has been deleted must not abort the scan — one
     * stale index entry would break credential resolution for every connection in
     * the deployment. A store that could not answer is the opposite: swallowing it
     * turns "the database blinked" into "no connection by that name", and
     * {@code ConnectionRegistry} caches an absent connection for the whole TTL, so
     * one transient read error fails every turn that uses a live connection for a
     * minute after the database recovered. That one propagates, and the registry
     * deliberately does not cache what it never got an answer for.
     */
    private ConnectionConfiguration readIfPresent(String id, URI resourceUri) throws ResourceStoreException {
        try {
            return read(id, versionOf(resourceUri));
        } catch (ResourceNotFoundException e) {
            LOGGER.debugv("Connection descriptor references a missing resource: {0}", resourceUri);
            return null;
        }
    }

    private static String idOf(URI resourceUri) {
        String path = resourceUri.getPath();
        if (path == null) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static Integer versionOf(URI resourceUri) {
        String query = resourceUri.getQuery();
        if (query == null) {
            return 1;
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith("version=")) {
                try {
                    return Integer.valueOf(pair.substring("version=".length()));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
        }
        return 1;
    }
}
