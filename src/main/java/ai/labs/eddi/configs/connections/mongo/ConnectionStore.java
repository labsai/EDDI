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
        if (name == null || name.isBlank()) {
            return null;
        }
        String effectiveTenant = tenantId == null || tenantId.isBlank() ? "default" : tenantId;
        try {
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(RESOURCE_TYPE, "", 0, IDescriptorStore.NO_LIMIT, false);
            if (descriptors == null) {
                return null;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                ConnectionConfiguration candidate = readQuietly(descriptor.getResource());
                if (candidate == null) {
                    continue;
                }
                String candidateTenant = candidate.getTenantId() == null ? "default" : candidate.getTenantId();
                if (name.equals(candidate.getName()) && effectiveTenant.equals(candidateTenant)) {
                    return candidate;
                }
            }
            return null;
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    /**
     * Reads one descriptor's resource, tolerating a dangling reference.
     * <p>
     * A descriptor whose resource has been deleted must not abort the scan — that
     * would make one stale index entry break credential resolution for every
     * connection in the deployment.
     */
    private ConnectionConfiguration readQuietly(URI resourceUri) {
        if (resourceUri == null) {
            return null;
        }
        try {
            return read(idOf(resourceUri), versionOf(resourceUri));
        } catch (ResourceNotFoundException e) {
            LOGGER.debugv("Connection descriptor references a missing resource: {0}", resourceUri);
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnv("Failed to read connection {0}: {1}", resourceUri, e.getMessage());
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
