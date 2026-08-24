/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.IRestConnectionStore;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
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
    private final ISecretProvider secretProvider;
    private final boolean authorizationEnabled;
    private final RestVersionInfo<ConnectionConfiguration> restVersionInfo;

    @Inject
    public RestConnectionStore(IConnectionStore connectionStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, ConnectionRegistry connectionRegistry, IConnectionGrantStore grantStore,
            ISecretProvider secretProvider,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, connectionStore, documentDescriptorStore);
        this.connectionStore = connectionStore;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.connectionRegistry = connectionRegistry;
        this.grantStore = grantStore;
        this.secretProvider = secretProvider;
        this.authorizationEnabled = authorizationEnabled;
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
        requireIdentityUnchanged(id, connectionConfiguration);
        requireNameIsFree(connectionConfiguration, id);
        // Last of the write checks, deliberately: it refuses a document that is not
        // wrong, only ahead of the feature, so anything genuinely malformed gets to
        // name its own field first.
        requireDefaultTenant(connectionConfiguration);
        Response response = restVersionInfo.update(id, version, connectionConfiguration);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response createConnection(ConnectionConfiguration connectionConfiguration) {
        validateForWrite(connectionConfiguration);
        requireNameIsFree(connectionConfiguration, null);
        requireDefaultTenant(connectionConfiguration);
        Response response = restVersionInfo.create(connectionConfiguration);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response duplicateConnection(String id, Integer version) {
        restVersionInfo.validateParameters(id, version);
        ConnectionConfiguration config = restVersionInfo.read(id, version);
        // A duplicate cannot keep the original's name: names are the reference
        // vocabulary, so two connections called "jira" make ${connection:jira}
        // resolve by scan order. Suffixed rather than refused, because refusing to
        // duplicate is a worse answer than producing an obviously-renamed copy.
        config.setName(nextFreeName(config));
        // A copy is a new connection, so it faces the same gate: duplicating a
        // pre-existing non-default-tenant document would mint a second one nobody
        // can link or unlink.
        requireDefaultTenant(config);
        Response response = restVersionInfo.create(config);
        connectionRegistry.invalidate();
        return response;
    }

    @Override
    public Response deleteConnection(String id, Integer version, Boolean permanent) {
        // Read the identity BEFORE deleting: afterwards there is no document to read
        // it from, and the grants are keyed by (tenant, name).
        ConnectionIdentity identity = identityOf(id);
        Response response = restVersionInfo.delete(id, version, permanent);
        // Invalidate BEFORE returning, not on a TTL: a deleted connection that keeps
        // resolving for another five minutes is a revocation that did not revoke.
        connectionRegistry.invalidate();
        deleteOrphanedGrants(identity);
        return response;
    }

    /**
     * The (tenant, name) pair grants are filed under.
     * <p>
     * Both halves were wrong before. The tenant was the literal default, so a
     * connection belonging to any other tenant had its grants looked for in a
     * tenant that did not hold them, and every refresh token survived the delete.
     * The name was read at the version being deleted, which is not necessarily the
     * version the name currently resolves at - so deleting an old version could
     * revoke against a name the live connection no longer uses, or miss the one it
     * does.
     */
    private record ConnectionIdentity(String tenantId, String name) {
    }

    /**
     * Resolves at the CURRENT version, deliberately, whichever version the request
     * names.
     */
    private ConnectionIdentity identityOf(String id) {
        try {
            IResourceStore.IResourceId current = connectionStore.getCurrentResourceId(id);
            ConnectionConfiguration connection = connectionStore.read(id, current.getVersion());
            if (connection == null || connection.getName() == null) {
                return null;
            }
            return new ConnectionIdentity(ConnectionConfiguration.effectiveTenant(connection), connection.getName());
        } catch (Exception e) {
            LOGGER.warnf("Could not resolve the identity of connection '%s'; its grants cannot be cleaned up automatically and may need "
                    + "removing by hand.", sanitize(id));
            return null;
        }
    }

    /**
     * Refuses a rename or a tenant move, because (tenant, name) is a connection's
     * identity everywhere else.
     * <p>
     * A connection reference names it, and - the part that bites - every stored
     * grant is filed under BOTH halves. Renaming "jira" to "jira-old", or moving it
     * to another tenant, therefore orphans every user's tokens rather than moving
     * them, and the next connection anyone creates under the old pair silently
     * INHERITS them: a fresh connection, possibly to an entirely different
     * provider, resolving other people's live refresh tokens on its first call.
     * Checking only the name left the tenant half of that hole wide open.
     * <p>
     * Refused rather than cascaded. A rename that rewrites grant rows is a
     * migration, not a field edit, and performing one silently inside a PUT is how
     * the inheritance above happens by accident in the first place. Create the new
     * connection and let users link it.
     * <p>
     * An identity that cannot be read is refused too, matching
     * {@link #requireNameIsFree}: permitting the write would be deciding "not a
     * rename" from no evidence, and the cost of being wrong is the inheritance
     * above.
     */
    private void requireIdentityUnchanged(String id, ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration == null) {
            return;
        }
        ConnectionIdentity current = identityOf(id);
        if (current == null) {
            throw new BadRequestException("Could not read the current identity of connection '" + id + "', so this update cannot be checked "
                    + "for a rename or a tenant move. Permitting it unchecked would orphan every grant filed under the old (tenant, name) "
                    + "and hand them to whatever is created under that pair next. Retry once the configuration store is reachable.");
        }
        var target = new ConnectionIdentity(ConnectionConfiguration.effectiveTenant(connectionConfiguration), connectionConfiguration.getName());
        if (current.equals(target)) {
            return;
        }
        throw new BadRequestException("A connection cannot be moved from '" + current.tenantId() + "/" + current.name() + "' to '"
                + target.tenantId() + "/" + target.name() + "'. The name is what a connection reference points at, and (tenant, name) "
                + "together are what every stored grant is filed under, so this would orphan this connection's grants and hand them to "
                + "whatever is created under the old pair next. Create a new connection instead.");
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
    private void deleteOrphanedGrants(ConnectionIdentity identity) {
        if (identity == null) {
            return;
        }
        try {
            if (connectionStore.readByName(identity.tenantId(), identity.name()) != null) {
                return;
            }
            int deleted = grantStore.deleteByConnection(identity.tenantId(), identity.name());
            if (deleted > 0) {
                LOGGER.infof("Deleted %d grant(s) for removed connection '%s' — tokens must not outlive the connection that produced them",
                        deleted, identity.name());
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to delete grants for removed connection '%s'. Refresh tokens may remain at rest; remove them manually.",
                    identity.name());
        }
    }

    /**
     * Refuses a name another connection already holds in the same tenant.
     * <p>
     * {@code ${connection:jira}} names ONE connection and has to keep naming the
     * same one. Without this, a second connection called "jira" — a duplicate, or a
     * staging variant someone forgot to rename — makes resolution depend on
     * descriptor scan order, which changes after a delete or a re-index. The
     * failure that produces is one system's credential going to another's
     * allowlisted origin, silently and intermittently.
     *
     * @param currentId
     *            the resource being updated, so a connection does not collide with
     *            itself; null on create
     */
    private void requireNameIsFree(ConnectionConfiguration connectionConfiguration, String currentId) {
        if (connectionConfiguration == null || connectionConfiguration.getName() == null) {
            return;
        }
        try {
            String holder = connectionStore.idOfName(connectionConfiguration.getTenantId(), connectionConfiguration.getName());
            if (holder != null && !holder.equals(currentId)) {
                throw new BadRequestException("A connection named '" + connectionConfiguration.getName() + "' already exists in this tenant. "
                        + "Names are what ${connection:…} refers to, so they must be unique — rename one of them.");
            }
        } catch (IResourceStore.ResourceStoreException e) {
            // A store that cannot be read must not silently permit a duplicate: the
            // damage from an ambiguous name is a credential sent to the wrong host.
            throw new BadRequestException("Could not verify that the connection name is unique (" + e.getClass().getSimpleName()
                    + "). Retry once the configuration store is reachable.", e);
        }
    }

    /** {@code jira} → {@code jira-copy}, {@code jira-copy-2}, … */
    private String nextFreeName(ConnectionConfiguration config) {
        String base = config.getName() + "-copy";
        try {
            if (connectionStore.idOfName(config.getTenantId(), base) == null) {
                return base;
            }
            for (int suffix = 2; suffix < 100; suffix++) {
                String candidate = base + "-" + suffix;
                if (connectionStore.idOfName(config.getTenantId(), candidate) == null) {
                    return candidate;
                }
            }
        } catch (IResourceStore.ResourceStoreException e) {
            throw new BadRequestException("Could not pick a free name for the duplicate (" + e.getClass().getSimpleName() + ").", e);
        }
        throw new BadRequestException("Too many copies of '" + config.getName() + "' already exist — rename some of them first.");
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
        requireDeploymentCanHonourIt(connectionConfiguration);
    }

    /**
     * Refuses a connection filed under any tenant but the default, until the rest
     * of the feature can follow it there.
     * <p>
     * The document carries a {@code tenantId} and the resolver honours a
     * tenant-qualified {@code ${connection:tenant/name}}, so a client-credentials
     * connection under "acme" resolves and mints a grant filed under "acme" — but
     * the per-user endpoints are still pinned to the default tenant, so that grant
     * is one the linked-accounts page cannot show and disconnect cannot delete. A
     * live refresh token with no revoke button, produced by a field that reads like
     * it is supported.
     * <p>
     * Refused at the write boundary rather than papered over at read time: the
     * runtime behaviour is consistent as it stands, and quietly rewriting an
     * author's tenantId would be a worse surprise than refusing it. Three things
     * change together when a real tenant context lands, and no one of them is
     * enough alone: {@code RestConnectionAuthorization.callerTenant()}, which
     * scopes {@code listMine} and {@code disconnect};
     * {@code ConnectionConfiguration.tenantId}, which must then be populated from
     * the tenant context rather than from the document; and this check, which comes
     * out at that point.
     */
    private static void requireDefaultTenant(ConnectionConfiguration connectionConfiguration) {
        String tenant = ConnectionConfiguration.effectiveTenant(connectionConfiguration);
        if (!ConnectionReference.DEFAULT_TENANT.equals(tenant)) {
            throw new BadRequestException("tenantId '" + tenant + "' is not supported yet — multi-tenant connections are not implemented. "
                    + "The endpoints that list and revoke a user's linked accounts are still scoped to the '"
                    + ConnectionReference.DEFAULT_TENANT + "' tenant, so a grant filed under any other one could never be shown or "
                    + "disconnected. Leave tenantId unset.");
        }
    }

    /**
     * Refuses a connection this deployment could store but could never resolve.
     * <p>
     * These two checks used to live only in the startup guard, where they threw and
     * took the next boot of every replica with them. Here they arrive as a 400 on
     * the request that causes the problem, while the administrator who wrote it is
     * still looking at it — which is both a smaller blast radius and a far better
     * error. The startup guard still logs, for a document that reached the store
     * some other way (import, a direct database write, a downgrade).
     */
    private void requireDeploymentCanHonourIt(ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration.getBinding() == Binding.PER_USER && !authorizationEnabled) {
            throw new BadRequestException("A PER_USER connection requires authorization.enabled=true. Without a verified identity any caller "
                    + "could claim any userId and resolve that user's tokens, so resolution refuses outright — the connection would save "
                    + "and then fail every call. Enable OIDC, or use SERVICE binding.");
        }
        if (connectionConfiguration.getAuthType() != null && connectionConfiguration.getAuthType().isOAuth() && !secretProvider.isAvailable()) {
            throw new BadRequestException("An OAuth connection requires an active SecretsVault (set EDDI_VAULT_MASTER_KEY). Grants are "
                    + "envelope-encrypted with the tenant DEK and there is deliberately no plaintext fallback for refresh tokens, so "
                    + "linking an account would fail at the moment the token comes back.");
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
