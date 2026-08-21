/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.IRestConnectionStore;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for the connection store.
 */
@ApplicationScoped
public class RestConnectionStore implements IRestConnectionStore {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionStore.class);

    private final IConnectionStore connectionStore;
    private final IConnectionGrantStore grantStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final ConnectionRegistry connectionRegistry;
    private final RestVersionInfo<ConnectionConfiguration> restVersionInfo;

    @Inject
    public RestConnectionStore(IConnectionStore connectionStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, ConnectionRegistry connectionRegistry, IConnectionGrantStore grantStore) {
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, connectionStore, documentDescriptorStore);
        this.connectionStore = connectionStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.connectionRegistry = connectionRegistry;
        this.grantStore = grantStore;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(ConnectionConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readConnectionDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors(filter, index, limit);
    }

    @Override
    public ConnectionConfiguration readConnection(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateConnection(String id, Integer version, ConnectionConfiguration connectionConfiguration) {
        validateForWrite(connectionConfiguration);
        Response response = restVersionInfo.update(id, version, connectionConfiguration);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response createConnection(ConnectionConfiguration connectionConfiguration) {
        validateForWrite(connectionConfiguration);
        Response response = restVersionInfo.create(connectionConfiguration);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response duplicateConnection(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ConnectionConfiguration config = restVersionInfo.read(id, version);
        Response response = restVersionInfo.create(config);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response deleteConnection(String id, Integer version, Boolean permanent) {
        // Read the name BEFORE deleting: afterwards there is no document to read it
        // from, and the grants are keyed by name.
        String name = nameOf(id, version);
        Response response = restVersionInfo.delete(id, version, permanent);
        // Invalidate BEFORE returning, not on a TTL: a deleted connection that keeps
        // resolving for another five minutes is a revocation that did not revoke.
        connectionRegistry.invalidate();
        deleteOrphanedGrants(name);
        return response;
    }

    /**
     * Deletes the grants of a connection that no longer resolves.
     * <p>
     * Decided by re-reading the NAME rather than by the {@code permanent} flag. A
     * soft delete of the current version already stops the name resolving, so a
     * flag-driven rule would leave live refresh tokens at rest for a connection
     * nobody can use — and deleting an older version of a connection that is still
     * live must not revoke anybody. Asking "does this name still resolve" answers
     * both cases with one question.
     * <p>
     * Failure here is logged, not propagated: the connection IS deleted at this
     * point, and turning a cleanup failure into a 500 would tell the operator the
     * delete failed when it did not.
     */
    private void deleteOrphanedGrants(String name) {
        if (name == null) {
            return;
        }
        try {
            if (connectionStore.readByName(ConnectionReference.DEFAULT_TENANT, name) != null) {
                return;
            }
            int deleted = grantStore.deleteByConnection(ConnectionReference.DEFAULT_TENANT, name);
            if (deleted > 0) {
                LOGGER.infof("Deleted %d grant(s) for removed connection '%s' — tokens must not outlive the connection that produced them",
                        deleted, name);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to delete grants for removed connection '%s'. Refresh tokens may remain at rest; remove them manually.",
                    name);
        }
    }

    private String nameOf(String id, Integer version) {
        try {
            ConnectionConfiguration connection = connectionStore.read(id, version);
            return connection == null ? null : connection.getName();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Rejects a connection the engine cannot honour, at the boundary where
     * rejecting is recoverable by the author.
     * <p>
     * The store validates too — that is the authoritative check, and it also covers
     * import. This one exists so the failure arrives as a 400 with the offending
     * field named rather than as a 500.
     */
    private void validateForWrite(ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration == null) {
            // RestVersionInfo produces its own error for a missing body.
            return;
        }
        try {
            connectionConfiguration.validate();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return connectionStore.getCurrentResourceId(id);
    }
}
