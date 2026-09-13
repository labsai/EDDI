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
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for the connection store.
 */
@ApplicationScoped
public class RestConnectionStore implements IRestConnectionStore {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionStore.class);

    private final IConnectionStore connectionStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;
    private final IConnectionGrantStore grantStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final ConnectionRegistry connectionRegistry;
    private final ISecretProvider secretProvider;
    private final boolean authorizationEnabled;
    private final RestVersionInfo<ConnectionConfiguration> restVersionInfo;

    /**
     * See {@link #createUnderNameLock}. Sixty-four stripes: plenty for a config
     * write path.
     */
    private final Object[] nameLocks = new Object[64];

    /**
     * Read for {@value ConnectionsConfig#ALLOW_PLAINTEXT_REMOTE_ORIGINS}.
     * Field-injected so the constructor keeps its shape; a store built without a
     * container reads the property as {@code false}, the refusing default.
     */
    @Inject
    ConnectionsConfig connectionsConfig;

    @Inject
    public RestConnectionStore(IConnectionStore connectionStore, IDocumentDescriptorStore documentDescriptorStore,
            IJsonSchemaCreator jsonSchemaCreator, ConnectionRegistry connectionRegistry, IConnectionGrantStore grantStore,
            ISecretProvider secretProvider,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            ResourceAccessGuard resourceAccessGuard) {
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, connectionStore, documentDescriptorStore, resourceAccessGuard);
        this.connectionStore = connectionStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceAccessGuard = resourceAccessGuard;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.connectionRegistry = connectionRegistry;
        this.grantStore = grantStore;
        this.secretProvider = secretProvider;
        this.authorizationEnabled = authorizationEnabled;
        for (int stripe = 0; stripe < nameLocks.length; stripe++) {
            nameLocks[stripe] = new Object();
        }
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
        ConnectionConfiguration current = requireIdentityUnchanged(id, connectionConfiguration);
        requireNameIsFree(connectionConfiguration, id);
        requireGrantsNotStranded(current, connectionConfiguration);
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
        return createUnderNameLock(connectionConfiguration);
    }

    /**
     * The one path every new connection document takes, so that a name can be
     * claimed only once.
     * <p>
     * {@link #requireNameIsFree} is a check-then-act: two creates of "jira" a few
     * milliseconds apart both find the name free, both land, and
     * {@code ${connection:jira}} then resolves by descriptor scan order — one
     * system's credential to another system's allowlisted origin, intermittently.
     * The store is a versioned document store, so no unique index can enforce the
     * rule; two things close the window instead.
     * <p>
     * Inside one JVM, creates of the same (tenant, name) are serialised on a lock —
     * and the descriptor, which is what a name scan reads, is written INSIDE that
     * lock rather than left to {@code DocumentDescriptorFilter} after the method
     * returns. Without that the lock closed nothing: the second create took the
     * lock the moment the first released it, scanned, found no descriptor for the
     * first document yet, and both landed. With it, the common single-node
     * deployment never races at all. Across replicas,
     * {@link #requireCreateWonTheName} re-asks the store who holds the name AFTER
     * the write landed and rolls this one back if somebody else does. What remains
     * is the interval between a replica's descriptor write and its becoming visible
     * to the other's scan — replication lag, against a check that used to be
     * absent.
     */
    private Response createUnderNameLock(ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration == null) {
            // RestVersionInfo produces its own error for a missing body.
            return restVersionInfo.create(null);
        }
        String tenant = ConnectionConfiguration.effectiveTenant(connectionConfiguration);
        synchronized (nameLock(tenant, connectionConfiguration.getName())) {
            requireNameIsFree(connectionConfiguration, null);
            // Last of the write checks, deliberately: it refuses a document that is not
            // wrong, only ahead of the feature, so anything genuinely malformed gets to
            // name its own field first.
            requireDefaultTenant(connectionConfiguration);
            Response response = restVersionInfo.create(connectionConfiguration);
            URI createdUri = createdUriOf(response);
            writeDescriptorNow(createdUri, connectionConfiguration.getName());
            connectionRegistry.invalidate();
            requireCreateWonTheName(tenant, connectionConfiguration.getName(), createdUri);
            return response;
        }
    }

    /**
     * Writes the new document's descriptor before the name lock is released.
     * <p>
     * The same descriptor {@code DocumentDescriptorFilter} would write once the
     * response is on its way — it finds this one and leaves it alone — only
     * earlier, because a name scan reads descriptors and a document without one is
     * invisible to the next create of the same name. Also what lets an in-process
     * caller such as the import service create a connection without writing a
     * descriptor by hand.
     * <p>
     * A descriptor that cannot be written is logged and left to the filter to
     * retry: the document is already there, and refusing the whole create for an
     * index row the filter can still produce would be the worse outcome.
     */
    private void writeDescriptorNow(URI createdUri, String name) {
        if (createdUri == null) {
            return;
        }
        IResourceStore.IResourceId created = RestUtilities.extractResourceId(createdUri);
        try {
            documentDescriptorStore.createDescriptor(created.getId(), created.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(createDocumentDescriptor(createdUri)));
        } catch (Exception e) {
            LOGGER.warnf(e, "Created connection '%s' (id %s) but could not write its descriptor inside the name lock; the response "
                    + "filter will retry, and until then a concurrent create of the same name cannot see this one.", sanitize(name),
                    sanitize(created.getId()));
        }
    }

    /**
     * Striped, so the lock table cannot grow with the number of names ever created.
     */
    private Object nameLock(String tenant, String name) {
        int stripe = Math.floorMod((tenant + "/" + name).hashCode(), nameLocks.length);
        return nameLocks[stripe];
    }

    /**
     * The cross-replica half of the uniqueness rule.
     * <p>
     * Our own descriptor is visible by now — {@link #writeDescriptorNow} — so our
     * own id is expected in the scan and filtered out; the rule is about everyone
     * else: any OTHER holder of the name visible now completed its create
     * concurrently with ours, and ours stands down. It is removed permanently,
     * descriptor included (nothing has been told about it yet, so there is nothing
     * to soft-delete for), and the caller is answered 409.
     * <p>
     * "Any other holder wins" rather than "the oldest wins", deliberately. When two
     * replicas each see only themselves plus the other, both stand down and both
     * callers are told to retry — an empty name, and a second request. The
     * alternative, each keeping its own when it is the older, duplicates the name
     * whenever one replica's scan runs before the other's descriptor has
     * replicated, and a duplicate name is a credential that may go to the wrong
     * host. A wasted request is the cheaper failure by a wide margin.
     * <p>
     * A store that cannot be scanned fails closed the same way the pre-check does:
     * the document is removed again and the caller is asked to retry.
     */
    private void requireCreateWonTheName(String tenant, String name, URI createdUri) {
        if (createdUri == null) {
            LOGGER.warnf("Created connection '%s' but could not read its id back, so the cross-replica name check was skipped.",
                    sanitize(name));
            return;
        }
        IResourceStore.IResourceId created = RestUtilities.extractResourceId(createdUri);
        List<String> holders;
        try {
            holders = connectionStore.idsOfName(tenant, name);
        } catch (IResourceStore.ResourceStoreException e) {
            removeLosingCreate(created, name);
            throw new BadRequestException("Could not verify that the connection name '" + name + "' is still unique after creating it ("
                    + e.getClass().getSimpleName() + "), so the new connection was removed again. Retry once the configuration store is "
                    + "reachable.", e);
        }
        List<String> others = holders.stream().filter(holder -> !holder.equals(created.getId())).toList();
        if (others.isEmpty()) {
            return;
        }
        removeLosingCreate(created, name);
        throw new ClientErrorException("A connection named '" + name + "' was created concurrently on another node: " + others.get(0)
                + " also held the name when this one landed, so this one has been removed again. If ${connection:" + name
                + "} resolves, that one survived — reference it. If it does not, the other node stood down for the same reason: retry "
                + "the create.", Response.Status.CONFLICT);
    }

    private static URI createdUriOf(Response response) {
        String createdUri = response == null ? null : response.getHeaderString("X-Resource-URI");
        if (createdUri == null || createdUri.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(createdUri);
            RestUtilities.extractResourceId(uri);
            return uri;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Removes a create that lost the name — the document and the descriptor
     * {@link #writeDescriptorNow} gave it, or the name scan would go on finding a
     * descriptor whose resource is gone.
     */
    private void removeLosingCreate(IResourceStore.IResourceId created, String name) {
        try {
            connectionStore.deleteAllPermanently(created.getId());
            connectionRegistry.invalidate();
        } catch (Exception e) {
            LOGGER.errorf(e, "Connection '%s' (id %s) lost a concurrent-create race but could not be removed; two connections now hold the "
                    + "name and the loser must be deleted by hand.", sanitize(name), sanitize(created.getId()));
        }
        try {
            documentDescriptorStore.deleteAllDescriptor(created.getId());
        } catch (Exception e) {
            LOGGER.warnf(e, "Connection '%s' (id %s) was removed after losing a concurrent-create race but its descriptor could not be; "
                    + "the dangling descriptor is skipped by name lookups and can be deleted by hand.", sanitize(name), sanitize(created.getId()));
        }
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
        // A copy is a new connection, so it faces every gate a create does. The
        // deployment checks in particular: without them a PER_USER or OAuth
        // document that predates OIDC or the vault being switched off could be
        // duplicated into a second connection that saves and then fails every call.
        validateForWrite(config);
        // Duplicating a pre-existing non-default-tenant document would mint a
        // second one nobody can link or unlink — createUnderNameLock refuses it.
        return createUnderNameLock(config);
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
        ConnectionConfiguration connection = currentOf(id);
        if (connection == null || connection.getName() == null) {
            return null;
        }
        return new ConnectionIdentity(ConnectionConfiguration.effectiveTenant(connection), connection.getName());
    }

    /** The document at its current version, or null when it cannot be read. */
    private ConnectionConfiguration currentOf(String id) {
        try {
            IResourceStore.IResourceId current = connectionStore.getCurrentResourceId(id);
            return connectionStore.read(id, current.getVersion());
        } catch (Exception e) {
            LOGGER.warnf("Could not resolve the identity of connection '%s'; its grants cannot be cleaned up automatically and may need "
                    + "removing by hand.", sanitize(id));
            return null;
        }
    }

    /**
     * Refuses an {@code authType} or {@code binding} change while the connection
     * still has linked accounts.
     * <p>
     * The rename rule protects the (tenant, name) a grant is filed under; this
     * protects what the grant IS. A grant minted by the authorization-code flow
     * under PER_USER binding is a refresh token for one end user. Re-save the
     * connection as STATIC, or as SERVICE-bound client credentials, and the
     * resolver never reads those rows again — but nothing deletes them either, so
     * every user's live refresh token stays at rest under a name that now means
     * something else, invisible to the linked-accounts page of a connection that no
     * longer has one.
     * <p>
     * Refused with the count and the two ways forward, rather than cascaded: each
     * user unlinks through {@code DELETE /connections/{name}/grant}, or the
     * administrator deletes the connection — which cascades to its grants — and
     * creates the new one. A silent cascade inside a PUT would be a mass revocation
     * nobody asked for.
     */
    private void requireGrantsNotStranded(ConnectionConfiguration current, ConnectionConfiguration target) {
        if (current == null || target == null) {
            return;
        }
        boolean sameAuthType = current.getAuthType() == target.getAuthType();
        boolean sameBinding = current.getBinding() == target.getBinding();
        if (sameAuthType && sameBinding) {
            return;
        }
        String tenant = ConnectionConfiguration.effectiveTenant(current);
        long linked;
        try {
            linked = grantStore.countByConnection(tenant, current.getName());
        } catch (Exception e) {
            throw new BadRequestException("Could not count the linked accounts of connection '" + current.getName() + "' ("
                    + e.getClass().getSimpleName() + "), so this authType/binding change cannot be checked for grants it would strand. "
                    + "Retry once the grant store is reachable.", e);
        }
        if (linked == 0) {
            return;
        }
        throw new ClientErrorException("Connection '" + current.getName() + "' has " + linked + " linked account(s) whose grants were "
                + "produced under authType " + current.getAuthType() + " / binding " + current.getBinding() + ". Changing it to "
                + target.getAuthType() + " / " + target.getBinding() + " would leave their refresh tokens at rest under a name the "
                + "resolver never reads them for. Have each user unlink with DELETE /connections/" + current.getName()
                + "/grant, or delete the connection — which deletes its grants with it — and create the new one.",
                Response.Status.CONFLICT);
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
    private ConnectionConfiguration requireIdentityUnchanged(String id, ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration == null) {
            return null;
        }
        ConnectionConfiguration currentDocument = currentOf(id);
        ConnectionIdentity current = currentDocument == null || currentDocument.getName() == null
                ? null
                : new ConnectionIdentity(ConnectionConfiguration.effectiveTenant(currentDocument), currentDocument.getName());
        if (current == null) {
            throw new BadRequestException("Could not read the current identity of connection '" + id + "', so this update cannot be checked "
                    + "for a rename or a tenant move. Permitting it unchecked would orphan every grant filed under the old (tenant, name) "
                    + "and hand them to whatever is created under that pair next. Retry once the configuration store is reachable.");
        }
        var target = new ConnectionIdentity(ConnectionConfiguration.effectiveTenant(connectionConfiguration), connectionConfiguration.getName());
        if (current.equals(target)) {
            return currentDocument;
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
        if (connectionConfiguration.getBinding() == Binding.CALLER_SUPPLIED && !authorizationEnabled) {
            // The credential arrives in a request header, and CallerIdentityContext
            // drops that header for an anonymous identity — deliberately, so an
            // unauthenticated caller cannot make EDDI spend a credential on its behalf.
            // With authorization off every caller is anonymous, so the connection
            // would save and then fail every call with NO_CALLER_CREDENTIAL.
            throw new BadRequestException("A CALLER_SUPPLIED connection requires authorization.enabled=true. The credential travels in the "
                    + "X-EDDI-Connection-Credential header, which is only read from an authenticated caller — an anonymous request has "
                    + "it dropped — so with OIDC off the connection would save and then refuse every call as NO_CALLER_CREDENTIAL. Enable "
                    + "OIDC, or use SERVICE binding with a vaulted key.");
        }
        if (connectionConfiguration.getAuthType() != null && connectionConfiguration.getAuthType().isOAuth() && !secretProvider.isAvailable()) {
            throw new BadRequestException("An OAuth connection requires an active SecretsVault (set EDDI_VAULT_MASTER_KEY). Grants are "
                    + "envelope-encrypted with the tenant DEK and there is deliberately no plaintext fallback for refresh tokens, so "
                    + "linking an account would fail at the moment the token comes back.");
        }
        requirePlaintextOriginsPermitted(connectionConfiguration);
    }

    /**
     * Refuses a remote plaintext http origin unless the deployment allows one, so
     * the connection is not saved only to be refused on every call by the resolver.
     * Loopback is always allowed. Runs after {@code validate()}, so every entry
     * already canonicalises.
     */
    private void requirePlaintextOriginsPermitted(ConnectionConfiguration connectionConfiguration) {
        if ((connectionsConfig != null && connectionsConfig.isAllowPlaintextRemoteOrigins())
                || connectionConfiguration.getBaseUrlAllowlist() == null) {
            return;
        }
        for (String origin : connectionConfiguration.getBaseUrlAllowlist()) {
            String canonical = ConnectionConfiguration.requireCanonicalOrigin(origin, "baseUrlAllowlist");
            if (ConnectionConfiguration.isPlaintextRemoteOrigin(canonical)) {
                throw new BadRequestException("baseUrlAllowlist entry " + canonical + " would send this connection's credential over plaintext "
                        + "http to a remote host, and " + ConnectionsConfig.ALLOW_PLAINTEXT_REMOTE_ORIGINS + "=false, so every call to it "
                        + "would be refused. Use an https origin, or set " + ConnectionsConfig.ALLOW_PLAINTEXT_REMOTE_ORIGINS + "=true on "
                        + "this deployment to accept an unencrypted credential deliberately. Loopback hosts are always allowed.");
            }
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
