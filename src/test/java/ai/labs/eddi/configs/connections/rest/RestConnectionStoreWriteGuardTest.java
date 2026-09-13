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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three refusals on the connection write path, each of which exists because
 * letting the write through hands one connection's stored refresh tokens to a
 * different one.
 * <p>
 * Every refusal here is paired with the write that must still succeed. A guard
 * tested only by what it rejects passes just as well when it rejects
 * everything, and a connection store nobody can write to fails in a way no
 * refusal message explains.
 */
class RestConnectionStoreWriteGuardTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IConnectionStore connectionStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private IConnectionGrantStore grantStore;
    private ConnectionRegistry connectionRegistry;
    private ISecretProvider secretProvider;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        grantStore = mock(IConnectionGrantStore.class);
        connectionRegistry = mock(ConnectionRegistry.class);
        secretProvider = mock(ISecretProvider.class);
        lenient().when(secretProvider.isAvailable()).thenReturn(true);
    }

    private RestConnectionStore rest() {
        return new RestConnectionStore(connectionStore, documentDescriptorStore, mock(IJsonSchemaCreator.class), connectionRegistry, grantStore,
                secretProvider, true, mock(ResourceAccessGuard.class));
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
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return ID;
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
    @DisplayName("name uniqueness across replicas")
    class NameUniqueness {

        private static final String OTHER_REPLICAS_ID = "68a1b2c3d4e5f60718293a4c";

        @Test
        @DisplayName("a create that finds another holder of the name after landing is rolled back and answered 409 naming the winner")
        void losesToAConcurrentCreateOnAnotherReplica() throws Exception {
            // The pre-create check is a check-then-act: both replicas found "jira" free.
            // Ours landed second, and the other's descriptor is visible by the time we
            // look again — so ours is the duplicate, and it must not survive to make
            // ${connection:jira} resolve by scan order.
            when(connectionStore.idOfName("default", "jira")).thenReturn(null);
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            when(connectionStore.idsOfName("default", "jira")).thenReturn(List.of(OTHER_REPLICAS_ID));

            var error = assertThrows(ClientErrorException.class, () -> rest().createConnection(connection("jira", null)));

            assertEquals(409, error.getResponse().getStatus());
            assertTrue(error.getMessage().contains(OTHER_REPLICAS_ID), "the refusal must name the other holder: " + error.getMessage());
            verify(connectionStore).deleteAllPermanently(ID);
            // The descriptor written inside the lock goes with it, or the name scan
            // keeps finding a descriptor whose resource is gone.
            verify(documentDescriptorStore).deleteAllDescriptor(ID);
        }

        @Test
        @DisplayName("the descriptor is written inside the name lock, before the post-create scan and before the method returns")
        void writesTheDescriptorBeforeReleasingTheLock() throws Exception {
            // A name scan reads descriptors. Leaving the descriptor to the response
            // filter meant the lock guarded nothing: the next create took it the moment
            // this one let go, scanned, saw no descriptor for this document yet, and
            // landed too.
            when(connectionStore.idOfName("default", "jira")).thenReturn(null);
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            when(connectionStore.idsOfName("default", "jira")).thenReturn(List.of(ID));

            rest().createConnection(connection("jira", null));

            var inOrder = inOrder(connectionStore, documentDescriptorStore);
            inOrder.verify(connectionStore).create(any());
            inOrder.verify(documentDescriptorStore).createDescriptor(eq(ID), eq(1), any());
            inOrder.verify(connectionStore).idsOfName("default", "jira");
        }

        @Test
        @DisplayName("a second create of the same name on the same node is refused even before any response filter has run")
        void aSecondCreateOnTheSameNodeSeesTheFirst() throws Exception {
            // The store answers name lookups from the descriptors it has been given —
            // which is exactly what the real store does — so this fails when the
            // descriptor is only written after createConnection returns.
            var descriptors = new ArrayList<String>();
            lenient().doAnswer(invocation -> {
                descriptors.add(invocation.getArgument(0));
                return null;
            }).when(documentDescriptorStore).createDescriptor(any(), any(), any());
            // The pre-check hands the store the document's raw tenantId (null here) and
            // the real store normalises it to the default, so the stub accepts either.
            when(connectionStore.idOfName(any(), eq("jira"))).thenAnswer(invocation -> descriptors.isEmpty() ? null : descriptors.get(0));
            when(connectionStore.idsOfName("default", "jira")).thenAnswer(invocation -> List.copyOf(descriptors));
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            var rest = rest();

            assertEquals(201, rest.createConnection(connection("jira", null)).getStatus());
            var error = assertThrows(BadRequestException.class, () -> rest.createConnection(connection("jira", null)));

            assertTrue(error.getMessage().contains("already exists"), error.getMessage());
            verify(connectionStore, times(1)).create(any());
            verify(connectionStore, never()).deleteAllPermanently(any());
        }

        @Test
        @DisplayName("a create nobody else raced is kept, and its own id in the scan does not count against it")
        void winsWhenNoOtherReplicaCreatedTheName() throws Exception {
            when(connectionStore.idOfName("default", "jira")).thenReturn(null);
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            when(connectionStore.idsOfName("default", "jira")).thenReturn(List.of(ID));

            var response = rest().createConnection(connection("jira", null));

            assertEquals(201, response.getStatus());
            verify(connectionStore, never()).deleteAllPermanently(any());
        }

        @Test
        @DisplayName("a post-create scan that cannot run removes the document again and asks for a retry")
        void failsClosedWhenThePostCreateScanCannotRun() throws Exception {
            // A connection that MAY be a duplicate is a credential that may go to the
            // wrong host; the pre-check fails closed on an unreadable store and this
            // does the same.
            when(connectionStore.idOfName("default", "jira")).thenReturn(null);
            when(connectionStore.create(any())).thenReturn(resourceId(1));
            when(connectionStore.idsOfName("default", "jira")).thenThrow(new IResourceStore.ResourceStoreException("blinked"));

            var error = assertThrows(BadRequestException.class, () -> rest().createConnection(connection("jira", null)));

            assertTrue(error.getMessage().contains("removed again"), error.getMessage());
            verify(connectionStore).deleteAllPermanently(ID);
        }

        @Test
        @DisplayName("creates of one name never overlap inside a node, so the single-node case cannot race at all")
        void serialisesConcurrentCreatesOfOneName() throws Exception {
            var inCreate = new AtomicInteger();
            var mostConcurrent = new AtomicInteger();
            when(connectionStore.idOfName("default", "jira")).thenReturn(null);
            when(connectionStore.create(any())).thenAnswer(invocation -> {
                int now = inCreate.incrementAndGet();
                mostConcurrent.accumulateAndGet(now, Math::max);
                Thread.sleep(30);
                inCreate.decrementAndGet();
                return resourceId(1);
            });
            var rest = rest();

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
                    grantStore, secretProvider, false, mock(ResourceAccessGuard.class));
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
