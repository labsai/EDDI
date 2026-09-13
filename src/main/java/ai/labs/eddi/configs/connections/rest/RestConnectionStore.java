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
import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore;
import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore.NameClaim;
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
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.createDocumentDescriptor;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * REST implementation for the connection store.
 */
@ApplicationScoped
public class RestConnectionStore implements IRestConnectionStore {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionStore.class);

    /**
     * How long a name claim with no connection recorded is presumed to belong to a
     * create still in flight. Past it, that create is presumed to have crashed
     * between claiming the name and creating the document, and the claim may be
     * taken over. Judged by the claim store's database clock, never by a replica's
     * own. A create that really is this slow is not duplicated by a takeover: it
     * loses the compare-and-set that records its connection and removes its own
     * document again.
     */
    static final Duration UNRECORDED_CLAIM_STALE_AFTER = Duration.ofMinutes(2);

    private final IConnectionStore connectionStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final ResourceAccessGuard resourceAccessGuard;
    private final IConnectionGrantStore grantStore;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final ConnectionRegistry connectionRegistry;
    private final ISecretProvider secretProvider;
    private final boolean authorizationEnabled;
    private final IConnectionNameClaimStore nameClaimStore;
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
            ResourceAccessGuard resourceAccessGuard, IConnectionNameClaimStore nameClaimStore) {
        this.restVersionInfo = new RestVersionInfo<>(resourceURI, connectionStore, documentDescriptorStore, resourceAccessGuard);
        this.connectionStore = connectionStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceAccessGuard = resourceAccessGuard;
        this.jsonSchemaCreator = jsonSchemaCreator;
        this.connectionRegistry = connectionRegistry;
        this.grantStore = grantStore;
        this.secretProvider = secretProvider;
        this.authorizationEnabled = authorizationEnabled;
        this.nameClaimStore = nameClaimStore;
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
        ConnectionConfiguration stored = restVersionInfo.read(id, version);
        // An administrator sees the document as stored, legacy literal included, so
        // it can be found and fixed. Anyone else admitted here (eddi-editor) gets a
        // copy with such literals redacted. isAdmin() is true for everyone when
        // authorization.enabled=false, which is also when @RolesAllowed is off.
        return resourceAccessGuard.isAdmin() ? stored : ConnectionReadRedactor.redactLegacyLiterals(stored);
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
     * The one path every new connection document takes, so that a name is held by
     * one connection only.
     * <p>
     * {@code ${connection:jira}} names ONE connection. Two creates of "jira" that
     * both land make it resolve by descriptor scan order — one system's credential
     * to another system's allowlisted origin, intermittently. The versioned
     * document store cannot carry a unique index on a field inside the document,
     * and the scans that used to stand in for one could not see across replicas:
     * two replicas that each saw only their own descriptor both kept their create.
     * The rule is therefore enforced by a durable, atomic claim on
     * {@code (tenant, name)} in {@link IConnectionNameClaimStore}, in four steps:
     * <ol>
     * <li><b>Claim</b> the name with a fresh token. Exactly one create, on any
     * replica, wins the insert. A loser reads the holder and answers 409 — unless
     * the holder is stale, in which case it takes the claim over with a
     * compare-and-set on the value it read ({@link #claimName}).</li>
     * <li><b>Create</b> the document.</li>
     * <li><b>Record</b> the connection id in the claim, conditional on still
     * holding the token. A create slow enough to have had its claim taken over
     * learns it here and removes its document again ({@link #recordHolder}).</li>
     * <li><b>Write the descriptor</b>, which is what a name lookup reads. Failing
     * that is a failed create ({@link #writeDescriptorOrRollBack}).</li>
     * </ol>
     * The JVM lock stays. It costs nothing, and it keeps two creates of one name on
     * one node from contending on the claim store at all.
     */
    private Response createUnderNameLock(ConnectionConfiguration connectionConfiguration) {
        if (connectionConfiguration == null) {
            // RestVersionInfo produces its own error for a missing body.
            return restVersionInfo.create(null);
        }
        String tenant = ConnectionConfiguration.effectiveTenant(connectionConfiguration);
        String name = connectionConfiguration.getName();
        synchronized (nameLock(tenant, name)) {
            // Last of the checks on the document itself, deliberately: it refuses a
            // document that is not wrong, only ahead of the feature, so anything
            // genuinely malformed gets to name its own field first. It still runs before
            // the claim, so a refused document never takes a name it must give back.
            requireDefaultTenant(connectionConfiguration);
            String token = UUID.randomUUID().toString();
            claimName(tenant, name, token);
            requireNoUnclaimedHolder(tenant, name, token);

            Response response;
            try {
                response = restVersionInfo.create(connectionConfiguration);
            } catch (Exception e) {
                releaseClaimQuietly(tenant, name, token);
                throw e;
            }
            URI createdUri = createdUriOf(response);
            if (createdUri == null) {
                releaseClaimQuietly(tenant, name, token);
                throw new InternalServerErrorException("Created connection '" + name + "' but could not read its id back, so it cannot be "
                        + "recorded as the holder of its name. Delete it by hand if it appears in the connection list, then retry.");
            }
            IResourceStore.IResourceId created = RestUtilities.extractResourceId(createdUri);
            recordHolder(tenant, name, token, created);
            writeDescriptorOrRollBack(tenant, name, token, createdUri, created);
            connectionRegistry.invalidate();
            return response;
        }
    }

    /**
     * Takes the durable claim on the name, or refuses the create with 409.
     * <p>
     * A claim somebody else holds is honoured when it names a connection that still
     * exists under that name. It is taken over when it is stale: no connection
     * recorded and older than {@link #UNRECORDED_CLAIM_STALE_AFTER} — a create that
     * crashed between claiming and creating — or a recorded connection that is
     * gone, which is what a delete that failed to release leaves behind, and what
     * an import rollback leaves too. The takeover is a compare-and-set on exactly
     * the value read, so two creates that both judged one claim stale cannot both
     * win it. Anything else — a fresh claim with no connection yet — is a create in
     * flight, and this one stands down.
     */
    private void claimName(String tenant, String name, String token) {
        if (claimStore(name, () -> nameClaimStore.claim(tenant, name, token))) {
            return;
        }
        Optional<NameClaim> holder = claimStore(name, () -> nameClaimStore.find(tenant, name));
        if (holder.isEmpty()) {
            // Released between our insert and our read. One more attempt; a name
            // contended that hard is answered as busy rather than looped on.
            if (claimStore(name, () -> nameClaimStore.claim(tenant, name, token))) {
                return;
            }
            throw createInProgress(name);
        }
        NameClaim current = holder.get();
        if (current.connectionId() != null && isLiveConnectionNamed(current.connectionId(), tenant, name)) {
            throw nameTaken(name, current.connectionId());
        }
        if (claimStore(name, () -> nameClaimStore.takeOver(current, token, UNRECORDED_CLAIM_STALE_AFTER))) {
            LOGGER.infof("Took over a stale claim on connection name '%s': %s", sanitize(name), current.connectionId() == null
                    ? "no connection was ever recorded against it"
                    : "connection " + sanitize(current.connectionId()) + " no longer exists");
            return;
        }
        throw createInProgress(name);
    }

    /**
     * Whether a connection id recorded in a claim still names a live connection of
     * that (tenant, name).
     * <p>
     * Read by id at the current version, not through the descriptor index: a
     * connection whose create is still between recording itself and writing its
     * descriptor exists, and must count. A store that cannot answer fails closed —
     * deciding "gone" from no evidence would hand the name to a second connection.
     */
    private boolean isLiveConnectionNamed(String connectionId, String tenant, String name) {
        try {
            IResourceStore.IResourceId current = connectionStore.getCurrentResourceId(connectionId);
            ConnectionConfiguration connection = connectionStore.read(connectionId, current.getVersion());
            return connection != null && name.equals(connection.getName()) && tenant.equals(ConnectionConfiguration.effectiveTenant(connection));
        } catch (IResourceStore.ResourceNotFoundException e) {
            return false;
        } catch (Exception e) {
            throw new BadRequestException("Could not check whether connection " + connectionId + ", which holds the name '" + name
                    + "', still exists (" + e.getClass().getSimpleName() + "). Retry once the configuration store is reachable.", e);
        }
    }

    /**
     * The legacy half of the rule, and the only reason a name scan is still made on
     * create: connections created before name claims existed hold their names
     * without one.
     * <p>
     * Winning the claim proves no other create that took a claim holds the name. It
     * says nothing about a connection that predates the claim store, and the
     * descriptor index does. When it finds one, the claim just taken is handed to
     * that connection — a lazy backfill, done in place as one compare-and-set so
     * the name is never unclaimed in between — and this create is answered 409. The
     * next create of the name is then refused by the claim alone.
     */
    private void requireNoUnclaimedHolder(String tenant, String name, String token) {
        String holder;
        try {
            holder = connectionStore.idOfName(tenant, name);
        } catch (Exception e) {
            releaseClaimQuietly(tenant, name, token);
            // A store that cannot be read must not silently permit a duplicate: the
            // damage from an ambiguous name is a credential sent to the wrong host.
            throw new BadRequestException("Could not verify that the connection name is unique (" + e.getClass().getSimpleName()
                    + "). Retry once the configuration store is reachable.", e);
        }
        if (holder == null) {
            return;
        }
        try {
            nameClaimStore.recordConnection(tenant, name, token, holder);
        } catch (RuntimeException e) {
            LOGGER.warnf("Connection '%s' (id %s) predates name claims, and its claim could not be backfilled (%s); the next create of the name "
                    + "repeats this check.", sanitize(name), sanitize(holder), e.getClass().getSimpleName());
            releaseClaimQuietly(tenant, name, token);
        }
        throw nameTaken(name, holder);
    }

    /**
     * Records the new document as the holder of its name — step three of
     * {@link #createUnderNameLock}.
     * <p>
     * Conditional on the claim still holding this create's token. If it does not,
     * the create outlived {@link #UNRECORDED_CLAIM_STALE_AFTER} and another create
     * took the name over; keeping this document would be the duplicate the claim
     * exists to prevent, so it is removed and the caller answered 409.
     */
    private void recordHolder(String tenant, String name, String token, IResourceStore.IResourceId created) {
        boolean recorded;
        try {
            recorded = nameClaimStore.recordConnection(tenant, name, token, created.getId());
        } catch (RuntimeException e) {
            removeCreatedConnection(created, name);
            releaseClaimQuietly(tenant, name, token);
            throw new BadRequestException("Created connection '" + name + "' but could not record it as the holder of its name ("
                    + e.getClass().getSimpleName() + "), so it was removed again. Retry once the configuration store is reachable.", e);
        }
        if (!recorded) {
            removeCreatedConnection(created, name);
            throw new ClientErrorException("The claim on connection name '" + name + "' was taken over while this create was in flight: it "
                    + "took longer than " + UNRECORDED_CLAIM_STALE_AFTER.toSeconds() + "s, so another create presumed it had crashed. This one "
                    + "has been removed again. If ${connection:" + name + "} resolves, the other create landed; otherwise retry.",
                    Response.Status.CONFLICT);
        }
    }

    /**
     * Writes the new document's descriptor, or undoes the create.
     * <p>
     * The same descriptor {@code DocumentDescriptorFilter} would write once the
     * response is on its way — it finds this one and leaves it alone — only
     * earlier, because a name lookup reads descriptors. A connection without one
     * resolves for nobody while its claim refuses the name to everybody else. That
     * used to be logged and left to the filter, which never runs for an in-process
     * caller such as the import, and the caller was told the create succeeded. It
     * is a failed create: the document is removed, the claim released, and the
     * caller gets the error.
     */
    private void writeDescriptorOrRollBack(String tenant, String name, String token, URI createdUri, IResourceStore.IResourceId created) {
        try {
            documentDescriptorStore.createDescriptor(created.getId(), created.getVersion(),
                    resourceAccessGuard.stampNewDescriptor(createDocumentDescriptor(createdUri)));
        } catch (Exception e) {
            removeCreatedConnection(created, name);
            releaseClaimQuietly(tenant, name, token);
            throw new BadRequestException("Created connection '" + name + "' but could not write its descriptor (" + e.getClass().getSimpleName()
                    + "), without which the name never resolves, so it was removed again. Retry once the configuration store is reachable.",
                    e);
        }
    }

    /**
     * Runs one claim-store call, turning a store failure into a refusal the author
     * can retry. Nothing has been created when these run, so there is nothing to
     * undo.
     */
    private static <T> T claimStore(String name, Supplier<T> call) {
        try {
            return call.get();
        } catch (WebApplicationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BadRequestException("Could not claim the connection name '" + name + "' (" + e.getClass().getSimpleName()
                    + "), so its uniqueness cannot be guaranteed and nothing was created. Retry once the configuration store is reachable.", e);
        }
    }

    /**
     * Best-effort: a claim left behind with no live connection is taken over by the
     * next create of the name once it is stale, so failing to release one delays
     * that create rather than blocking the name for good.
     */
    private void releaseClaimQuietly(String tenant, String name, String token) {
        try {
            nameClaimStore.release(tenant, name, token);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not release the claim on connection name '%s' (%s); the next create of the name takes it over once it is stale.",
                    sanitize(name), e.getClass().getSimpleName());
        }
    }

    private static ClientErrorException nameTaken(String name, String holderId) {
        return new ClientErrorException("A connection named '" + name + "' already exists in this tenant (" + holderId + "). Names are what "
                + "${connection:…} refers to, so they must be unique — reference that one, or choose another name.", Response.Status.CONFLICT);
    }

    private static ClientErrorException createInProgress(String name) {
        return new ClientErrorException("Another create of a connection named '" + name + "' is in progress. Names are what ${connection:…} "
                + "refers to, so they must be unique. If ${connection:" + name + "} resolves in a moment, that create landed; otherwise retry.",
                Response.Status.CONFLICT);
    }

    /**
     * Striped, so the lock table cannot grow with the number of names ever created.
     */
    private Object nameLock(String tenant, String name) {
        int stripe = Math.floorMod((tenant + "/" + name).hashCode(), nameLocks.length);
        return nameLocks[stripe];
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
     * Removes a create that did not complete — the document and any descriptor it
     * was given, or the name scan would go on finding a descriptor whose resource
     * is gone.
     */
    private void removeCreatedConnection(IResourceStore.IResourceId created, String name) {
        try {
            connectionStore.deleteAllPermanently(created.getId());
            connectionRegistry.invalidate();
        } catch (Exception e) {
            LOGGER.errorf(e, "Connection '%s' (id %s) did not complete its create but could not be removed; it must be deleted by hand.",
                    sanitize(name), sanitize(created.getId()));
        }
        try {
            documentDescriptorStore.deleteAllDescriptor(created.getId());
        } catch (Exception e) {
            LOGGER.warnf(e, "Connection '%s' (id %s) was removed after an incomplete create but its descriptor could not be; the dangling "
                    + "descriptor is skipped by name lookups and can be deleted by hand.", sanitize(name), sanitize(created.getId()));
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
        releaseNameClaim(identity, id);
        deleteOrphanedGrants(identity);
        return response;
    }

    /**
     * Gives the name back once its connection is deleted, soft or permanently.
     * <p>
     * Only a claim that names THIS connection: one held by anything else — a create
     * that already took a stale claim over, a connection that predates claims — is
     * not this delete's to release. No liveness check is needed first: a soft
     * delete succeeds only on the current version, so reaching this line means the
     * connection no longer resolves under the name either way.
     * <p>
     * Failure is logged, not propagated: the connection IS deleted, and a claim
     * naming a connection that no longer exists is taken over by the next create of
     * the name.
     */
    private void releaseNameClaim(ConnectionIdentity identity, String id) {
        if (identity == null) {
            return;
        }
        try {
            nameClaimStore.releaseConnection(identity.tenantId(), identity.name(), id);
        } catch (RuntimeException e) {
            LOGGER.warnf("Deleted connection '%s' but could not release its name claim (%s); the next create of the name takes it over.",
                    sanitize(identity.name()), e.getClass().getSimpleName());
        }
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
     * Refuses a name another connection already holds in the same tenant, on
     * update.
     * <p>
     * {@code ${connection:jira}} names ONE connection and has to keep naming the
     * same one. A rename is refused outright, so this only fires for a name that
     * was already duplicated before uniqueness was enforced — and refuses to let an
     * edit entrench it. Creates are guarded by the name claim instead; see
     * {@link #createUnderNameLock}.
     *
     * @param currentId
     *            the resource being updated, so a connection does not collide with
     *            itself
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
