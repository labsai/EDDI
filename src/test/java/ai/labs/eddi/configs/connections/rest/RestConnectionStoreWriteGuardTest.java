/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore;
import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore.NameClaim;
import ai.labs.eddi.configs.connections.names.InMemoryConnectionNameClaimStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refusals on the connection write path, each of which exists because
 * letting the write through hands one connection's stored refresh tokens — or
 * one system's credential — to a different one.
 * <p>
 * Every refusal here is paired with the write that must still succeed. A guard
 * tested only by what it rejects passes just as well when it rejects
 * everything, and a connection store nobody can write to fails in a way no
 * refusal message explains.
 */
class RestConnectionStoreWriteGuardTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";
    private static final String TENANT = "default";

    private IConnectionStore connectionStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IConnectionGrantStore grantStore;
    private ConnectionRegistry connectionRegistry;
    private ISecretProvider secretProvider;
    private InMemoryConnectionNameClaimStore claims;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        grantStore = mock(IConnectionGrantStore.class);
        connectionRegistry = mock(ConnectionRegistry.class);
        secretProvider = mock(ISecretProvider.class);
        claims = new InMemoryConnectionNameClaimStore();
        lenient().when(secretProvider.isAvailable()).thenReturn(true);
    }

    private RestConnectionStore rest() {
        return rest(claims);
    }

    private RestConnectionStore rest(IConnectionNameClaimStore nameClaimStore) {
        return new RestConnectionStore(connectionStore, documentDescriptorStore, mock(IJsonSchemaCreator.class), connectionRegistry, grantStore,
                secretProvider, true, mock(ResourceAccessGuard.class), nameClaimStore);
    }

    /** A document that passes every OTHER check, so a refusal is attributable. */
    private static ConnectionConfiguration connection(String name, String tenantId) {
        var connection = new ConnectionConfiguration();
        connection.setName(name);
        connection.setTenantId(tenantId);
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:jira-token}");
        connection.setStaticAuth(auth);
        return connection;
    }

    private void storedAs(ConnectionConfiguration connection, int currentVersion) throws Exception {
        when(connectionStore.getCurrentResourceId(ID)).thenReturn(resourceId(currentVersion));
        when(connectionStore.read(ID, currentVersion)).thenReturn(connection);
    }

    private static IResourceStore.IResourceId resourceId(int version) {
        return resourceId(ID, version);
    }

    private static IResourceStore.IResourceId resourceId(String id, int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    @Nested
    @DisplayName("the identity guard on update")
    class IdentityGuard {

        @Test
        @DisplayName("a tenant move with an unchanged name is refused, because the identity is the pair")
        void refusesATenantMove() throws Exception {
            // Checking the name alone left this half of the hole open: every grant is
            // filed under (tenant, name), so moving "jira" from acme to globex orphans
            // acme's tokens and hands them to whatever is created under acme/jira next.
            storedAs(connection("jira", "acme"), 3);

            var error = assertThrows(BadRequestException.class, () -> rest().updateConnection(ID, 3, connection("jira", "globex")));

            // Both pairs, spelled out: a looser assertion would also be satisfied by
            // the tenant-not-supported refusal further down the same method, which is a
            // different rule failing for a different reason.
            assertTrue(error.getMessage().contains("acme/jira"), "the refusal must name the identity being moved FROM: " + error.getMessage());
            assertTrue(error.getMessage().contains("globex/jira"), "and the one being moved TO: " + error.getMessage());
            verify(connectionStore, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("an update is refused outright when the current identity cannot be read")
        void failsClosedWhenTheIdentityCannotBeRead() throws Exception {
            // Permitting it would be deciding "not a rename" from no evidence at all,
            // and the cost of being wrong is the grant inheritance above.
            when(connectionStore.getCurrentResourceId(ID)).thenThrow(new IResourceStore.ResourceNotFoundException("store unreachable"));

            var error = assertThrows(BadRequestException.class, () -> rest().updateConnection(ID, 3, connection("jira", "default")));

            // "identity" rather than the shared "retry" sentence: the name-uniqueness
            // check fails closed with a similar tail, and a test that matched only that
            // would pass on the wrong refusal.
            assertTrue(error.getMessage().contains("identity"),
                    "the refusal must say WHICH check could not run, or a different failure satisfies this test: " + error.getMessage());
            assertTrue(error.getMessage().contains("Retry once the configuration store is reachable"), error.getMessage());
            verify(connectionStore, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("an update that keeps both halves of the identity is written and invalidates the registry")
        void permitsAnUpdateThatKeepsTheIdentity() throws Exception {
            storedAs(connection("jira", "default"), 3);
            when(connectionStore.idOfName("default", "jira")).thenReturn(ID);
            when(connectionStore.update(eq(ID), eq(3), any())).thenReturn(4);
            var edited = connection("jira", "default");
            edited.setDescription("edited in place, identity untouched");

            rest().updateConnection(ID, 3, edited);

            verify(connectionStore).update(ID, 3, edited);
            // Not on a TTL: a connection whose allowlist just narrowed must stop
            // resolving to the old one immediately.
            verify(connectionRegistry).invalidate();
        }
    }

    @Nested
    @DisplayName("name uniqueness, enforced by a durable claim on (tenant, name)")
    class NameUniqueness {

        private static final String OTHER_ID = "68a1b2c3d4e5f60718293a4c";

        private void liveConnection(String id, String name) throws Exception {
            when(connectionStore.getCurrentResourceId(id)).thenReturn(resourceId(id, 1));
            when(connectionStore.read(id, 1)).thenReturn(connection(name, null));
        }

        private NameClaim claimOnJira() {
            return claims.find(TENANT, "jira").orElseThrow(() -> new AssertionError("expected a claim on 'jira'"));
        }

        @Test
        @DisplayName("a name whose claim names a live connection is refused 409, before any document is written")
        void refusesANameALiveConnectionHolds() throws Exception {
            // The cross-replica case the descriptor scans could not close: the other
            // replica's connection may not be visible to any scan yet, but its claim is.
            claims.seed(TENANT, "jira", "other-replicas-token", OTHER_ID);
            liveConnection(OTHER_ID, "jira");

            var error = assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));

            assertEquals(409, error.getResponse().getStatus());
            assertTrue(error.getMessage().contains(OTHER_ID), "the refusal must name the holder: " + error.getMessage());
            verify(connectionStore, never()).create(any());
            assertEquals(new NameClaim(TENANT, "jira", "other-replicas-token", OTHER_ID), claimOnJira(), "the holder's claim is untouched");
        }

        @Test
        @DisplayName("a claim with no connection that has gone stale — a create that crashed — is taken over, and the create completes")
        void takesOverAClaimACrashedCreateLeftBehind() throws Exception {
            claims.seed(TENANT, "jira", "crashed-token", null);
            claims.markStale(TENANT, "jira");
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            assertEquals(201, rest().createConnection(connection("jira", null)).getStatus());

            NameClaim claim = claimOnJira();
            assertNotEquals("crashed-token", claim.token(), "the takeover must install this create's own token");
            assertEquals(ID, claim.connectionId(), "and the create must record itself as the holder");
        }

        @Test
        @DisplayName("a fresh claim with no connection is a create in flight: refused 409, nothing written, claim untouched")
        void refusesWhileAnotherCreateIsInFlight() throws Exception {
            claims.seed(TENANT, "jira", "in-flight-token", null);

            var error = assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));

            assertEquals(409, error.getResponse().getStatus());
            assertTrue(error.getMessage().contains("in progress"), error.getMessage());
            verify(connectionStore, never()).create(any());
            assertEquals("in-flight-token", claimOnJira().token(), "a live create must not have its claim taken from under it");
        }

        @Test
        @DisplayName("a claim naming a connection that no longer exists is taken over at once")
        void takesOverAClaimWhoseConnectionIsGone() throws Exception {
            // What a delete that failed to release, or an import rollback, leaves behind.
            claims.seed(TENANT, "jira", "departed-token", OTHER_ID);
            when(connectionStore.getCurrentResourceId(OTHER_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            assertEquals(201, rest().createConnection(connection("jira", null)).getStatus());

            assertEquals(ID, claimOnJira().connectionId());
        }

        @Test
        @DisplayName("a store that cannot say whether the holder still exists refuses the create rather than guessing")
        void failsClosedWhenTheHoldersLivenessCannotBeRead() throws Exception {
            claims.seed(TENANT, "jira", "other-token", OTHER_ID);
            when(connectionStore.getCurrentResourceId(OTHER_ID)).thenReturn(resourceId(OTHER_ID, 1));
            when(connectionStore.read(OTHER_ID, 1)).thenThrow(new IResourceStore.ResourceStoreException("blinked"));

            var error = assertThrows(BadRequestException.class, () -> rest().createConnection(connection("jira", null)));

            assertTrue(error.getMessage().contains("Retry"), error.getMessage());
            verify(connectionStore, never()).create(any());
            assertEquals(OTHER_ID, claimOnJira().connectionId(), "deciding 'gone' from no evidence would hand the name to a second connection");
        }

        @Test
        @DisplayName("a soft delete releases the claim that names the deleted connection")
        void softDeleteReleasesTheClaim() throws Exception {
            deleteReleasesTheClaim(false);
        }

        @Test
        @DisplayName("a permanent delete releases the claim that names the deleted connection")
        void permanentDeleteReleasesTheClaim() throws Exception {
            deleteReleasesTheClaim(true);
        }

        private void deleteReleasesTheClaim(boolean permanent) throws Exception {
            storedAs(connection("jira", null), 1);
            claims.seed(TENANT, "jira", "token", ID);

            rest().deleteConnection(ID, 1, permanent);

            assertTrue(claims.find(TENANT, "jira").isEmpty(), "the name must be free to create again once its connection is gone");
        }

        @Test
        @DisplayName("a delete never releases a claim that names another connection")
        void deleteLeavesAnotherConnectionsClaimAlone() throws Exception {
            storedAs(connection("jira", null), 1);
            claims.seed(TENANT, "jira", "token", OTHER_ID);

            rest().deleteConnection(ID, 1, false);

            assertEquals(OTHER_ID, claimOnJira().connectionId());
        }

        @Test
        @DisplayName("a descriptor that cannot be written fails the create: document removed, claim released, error to the caller")
        void aDescriptorThatCannotBeWrittenFailsTheCreate() throws Exception {
            // It used to be logged and the create reported as a success: a connection
            // no name lookup could see, holding a name nobody else could create.
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            doThrow(new IllegalStateException("descriptor store unreachable")).when(documentDescriptorStore).createDescriptor(anyString(), any(),
                    any());

            var error = assertThrows(BadRequestException.class, () -> rest().createConnection(connection("jira", null)));

            assertTrue(error.getMessage().contains("removed again"), error.getMessage());
            verify(connectionStore).deleteAllPermanently(ID);
            verify(documentDescriptorStore).deleteAllDescriptor(ID);
            assertTrue(claims.find(TENANT, "jira").isEmpty(), "the claim must be released with the document, or the name stays blocked");
        }

        @Test
        @DisplayName("a connection that predates claims still holds its name: 409, and the claim is backfilled for it")
        void refusesALegacyNameAndBackfillsItsClaim() throws Exception {
            when(connectionStore.idOfName(TENANT, "jira")).thenReturn(OTHER_ID);

            var first = assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));

            assertEquals(409, first.getResponse().getStatus());
            assertTrue(first.getMessage().contains(OTHER_ID), first.getMessage());
            verify(connectionStore, never()).create(any());
            assertEquals(OTHER_ID, claimOnJira().connectionId(), "the claim must now name the connection that already held the name");

            // Backfilled, so the next create is refused by the claim alone.
            liveConnection(OTHER_ID, "jira");
            assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));
            verify(connectionStore, times(1)).idOfName(TENANT, "jira");
        }

        @Test
        @DisplayName("a create whose claim was taken over while it was in flight removes its own document and answers 409")
        void aCreateThatLostItsClaimMidFlightRemovesItself() throws Exception {
            when(connectionStore.create(any())).thenAnswer(invocation -> {
                // Slower than the stale bound: another replica took the claim over.
                claims.seed(TENANT, "jira", "usurping-token", null);
                return resourceId(1);
            });

            var error = assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));

            assertEquals(409, error.getResponse().getStatus());
            verify(connectionStore).deleteAllPermanently(ID);
            verify(documentDescriptorStore, never()).createDescriptor(anyString(), any(), any());
            assertEquals("usurping-token", claimOnJira().token(), "the loser must not disturb the winner's claim");
        }

        @Test
        @DisplayName("a claim store that cannot be reached refuses the create before anything is written")
        void failsClosedWhenTheClaimStoreIsUnreachable() throws Exception {
            IConnectionNameClaimStore unreachable = mock(IConnectionNameClaimStore.class);
            when(unreachable.claim(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("connection refused"));

            var error = assertThrows(BadRequestException.class, () -> rest(unreachable).createConnection(connection("jira", null)));

            assertTrue(error.getMessage().contains("Retry once the configuration store is reachable"), error.getMessage());
            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("a second create of the same name on the same node is refused by the first one's claim")
        void aSecondCreateOnTheSameNodeSeesTheFirst() throws Exception {
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            liveConnection(ID, "jira");
            var rest = rest();

            assertEquals(201, rest.createConnection(connection("jira", null)).getStatus());
            var error = assertThrows(ClientErrorException.class, () -> rest.createConnection(connection("jira", null)));

            assertEquals(409, error.getResponse().getStatus());
            verify(connectionStore, times(1)).create(any());
            verify(connectionStore, never()).deleteAllPermanently(any());
        }

        @Test
        @DisplayName("creates of one name never overlap inside a node, even against a claim store that would let them")
        void serialisesConcurrentCreatesOfOneName() throws Exception {
            // A claim store that grants everything, so what is measured is the JVM lock
            // alone rather than the claim doing the same job.
            IConnectionNameClaimStore permissive = mock(IConnectionNameClaimStore.class);
            when(permissive.claim(anyString(), anyString(), anyString())).thenReturn(true);
            when(permissive.recordConnection(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
            var inCreate = new AtomicInteger();
            var mostConcurrent = new AtomicInteger();
            when(connectionStore.create(any())).thenAnswer(invocation -> {
                int now = inCreate.incrementAndGet();
                mostConcurrent.accumulateAndGet(now, Math::max);
                Thread.sleep(30);
                inCreate.decrementAndGet();
                return resourceId(1);
            });
            var rest = rest(permissive);

            var workers = new ArrayList<Thread>();
            for (int worker = 0; worker < 4; worker++) {
                var thread = new Thread(() -> rest.createConnection(connection("jira", null)));
                workers.add(thread);
                thread.start();
            }
            for (Thread thread : workers) {
                thread.join(5_000);
            }

            assertEquals(1, mostConcurrent.get(), "two creates of the same name were inside the store at once");
        }
    }

    @Nested
    @DisplayName("the deployment guard")
    class DeploymentGuard {

        private RestConnectionStore restWithoutAuthorization() {
            return new RestConnectionStore(connectionStore, documentDescriptorStore, mock(IJsonSchemaCreator.class), connectionRegistry,
                    grantStore, secretProvider, false, mock(ResourceAccessGuard.class), claims);
        }

        private ConnectionConfiguration callerSupplied() {
            var connection = connection("gnowbe", null);
            connection.setBinding(Binding.CALLER_SUPPLIED);
            var auth = new StaticAuth();
            auth.setHeaderName("x-api-key");
            connection.setStaticAuth(auth);
            return connection;
        }

        @Test
        @DisplayName("a CALLER_SUPPLIED connection cannot be created where no caller is ever authenticated")
        void refusesCallerSuppliedWithoutAuthorization() throws Exception {
            // CallerIdentityContext drops the credential header for an anonymous
            // identity, and with authorization off every identity is anonymous: the
            // connection saved and then refused every call as NO_CALLER_CREDENTIAL.
            var error = assertThrows(BadRequestException.class, () -> restWithoutAuthorization().createConnection(callerSupplied()));

            assertTrue(error.getMessage().contains("authorization.enabled"), "the refusal must name the setting: " + error.getMessage());
            assertTrue(error.getMessage().contains("CALLER_SUPPLIED"), error.getMessage());
            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("the same refusal applies on update")
        void refusesCallerSuppliedWithoutAuthorizationOnUpdate() throws Exception {
            storedAs(connection("gnowbe", null), 1);

            assertThrows(BadRequestException.class, () -> restWithoutAuthorization().updateConnection(ID, 1, callerSupplied()));

            verify(connectionStore, never()).update(any(), any(), any());
        }

        @Test
        @DisplayName("a CALLER_SUPPLIED connection is created once callers are authenticated")
        void createsCallerSuppliedWithAuthorization() throws Exception {
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            rest().createConnection(callerSupplied());

            verify(connectionStore).create(any());
        }

        private ConnectionConfiguration withOrigin(String origin) {
            var connection = connection("internal", null);
            connection.setBaseUrlAllowlist(List.of(origin));
            return connection;
        }

        @Test
        @DisplayName("a remote plaintext http origin is refused by default, naming the property")
        void refusesPlaintextRemoteOriginByDefault() throws Exception {
            var error = assertThrows(BadRequestException.class, () -> rest().createConnection(withOrigin("http://api.internal.example:8080")));

            assertTrue(error.getMessage().contains("eddi.connections.allow-plaintext-remote-origins"), "the refusal must name the setting: "
                    + error.getMessage());
            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("a remote plaintext http origin is created once the deployment allows it")
        void createsPlaintextRemoteOriginWhenAllowed() throws Exception {
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            var store = rest();
            var connectionsConfig = mock(ConnectionsConfig.class);
            when(connectionsConfig.isAllowPlaintextRemoteOrigins()).thenReturn(true);
            store.connectionsConfig = connectionsConfig;

            store.createConnection(withOrigin("http://api.internal.example:8080"));

            verify(connectionStore).create(any());
        }

        @Test
        @DisplayName("a loopback http origin is created with the property at its default")
        void createsLoopbackHttpOriginByDefault() throws Exception {
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            rest().createConnection(withOrigin("http://localhost:7070"));

            verify(connectionStore).create(any());
        }

        private ConnectionConfiguration serviceOAuth() {
            var connection = connection("analytics", null);
            connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
            connection.setStaticAuth(null);
            var oauth = new OAuthConfig();
            oauth.setTokenUrl("https://auth.example.com/token");
            oauth.setClientId("client");
            oauth.setClientSecret("${vault:client-secret}");
            connection.setOauth(oauth);
            return connection;
        }

        @Test
        @DisplayName("duplicating an OAuth connection faces the vault check the create did")
        void refusesDuplicatingOAuthWithoutVault() throws Exception {
            // The duplicate endpoint skipped validateForWrite entirely, so a document
            // that predated the vault being switched off could be copied into a second
            // connection that saved and then failed every call.
            when(connectionStore.read(ID, 1)).thenReturn(serviceOAuth());
            when(secretProvider.isAvailable()).thenReturn(false);

            var error = assertThrows(BadRequestException.class, () -> rest().duplicateConnection(ID, 1));

            assertTrue(error.getMessage().contains("EDDI_VAULT_MASTER_KEY"), error.getMessage());
            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("duplicating a PER_USER connection faces the identity check the create did")
        void refusesDuplicatingPerUserWithoutAuthorization() throws Exception {
            var perUser = serviceOAuth();
            perUser.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
            perUser.setBinding(Binding.PER_USER);
            perUser.getOauth().setAuthorizationUrl("https://auth.example.com/authorize");
            when(connectionStore.read(ID, 1)).thenReturn(perUser);

            var error = assertThrows(BadRequestException.class, () -> restWithoutAuthorization().duplicateConnection(ID, 1));

            assertTrue(error.getMessage().contains("authorization.enabled"), error.getMessage());
            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("a duplicate the deployment can honour is still created")
        void duplicatesWhatTheDeploymentCanHonour() throws Exception {
            when(connectionStore.read(ID, 1)).thenReturn(serviceOAuth());
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            rest().duplicateConnection(ID, 1);

            verify(connectionStore).create(any());
        }
    }

    @Nested
    @DisplayName("the tenant guard")
    class TenantGuard {

        @Test
        @DisplayName("a connection filed under a non-default tenant is refused on create")
        void refusesANonDefaultTenantOnCreate() throws Exception {
            // The document would resolve and mint a grant under "acme", but listMine and
            // disconnect are still pinned to the default tenant — a live refresh token
            // with no revoke button.
            var error = assertThrows(BadRequestException.class, () -> rest().createConnection(connection("jira", "acme")));

            assertTrue(error.getMessage().contains("tenantId 'acme'"), "the refusal must quote the offending value: " + error.getMessage());
            assertTrue(error.getMessage().contains("disconnected"),
                    "and say what would be impossible about the grant it would produce: " + error.getMessage());
            verify(connectionStore, never()).create(any());
            assertTrue(claims.find("acme", "jira").isEmpty(), "a refused document must not take a name it would have to give back");
        }

        @Test
        @DisplayName("a duplicate of a non-default-tenant connection is refused too, rather than minting a second one")
        void refusesANonDefaultTenantOnDuplicate() throws Exception {
            // Reachable for a document that predates the guard: without this check,
            // duplicating it produces a second connection nobody can link or unlink.
            when(connectionStore.read(ID, 1)).thenReturn(connection("jira", "acme"));

            assertThrows(BadRequestException.class, () -> rest().duplicateConnection(ID, 1));

            verify(connectionStore, never()).create(any());
        }

        @Test
        @DisplayName("a connection left on the default tenant is created")
        void createsOnTheDefaultTenant() throws Exception {
            // tenantId unset, which is what an author writes and what effectiveTenant
            // has to read as the default.
            var created = connection("jira", null);
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            rest().createConnection(created);

            verify(connectionStore).create(created);
            verify(connectionRegistry).invalidate();
        }

        @Test
        @DisplayName("a duplicate on the default tenant is created under a suffixed name")
        void duplicatesOnTheDefaultTenant() throws Exception {
            // Two connections called "jira" would make ${connection:jira} resolve by
            // scan order, so the copy is renamed rather than the duplicate refused.
            when(connectionStore.read(ID, 1)).thenReturn(connection("jira", null));
            when(connectionStore.create(any())).thenReturn(resourceId(1));

            rest().duplicateConnection(ID, 1);

            var written = ArgumentCaptor.forClass(ConnectionConfiguration.class);
            verify(connectionStore).create(written.capture());
            assertEquals("jira-copy", written.getValue().getName(), "the copy must not keep the name the original's references point at");
        }
    }
}
