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
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private IConnectionGrantStore grantStore;
    private ConnectionRegistry connectionRegistry;
    private ISecretProvider secretProvider;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        grantStore = mock(IConnectionGrantStore.class);
        connectionRegistry = mock(ConnectionRegistry.class);
        secretProvider = mock(ISecretProvider.class);
        lenient().when(secretProvider.isAvailable()).thenReturn(true);
    }

    private RestConnectionStore rest() {
        return new RestConnectionStore(connectionStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), connectionRegistry,
                grantStore, secretProvider, true, mock(ResourceAccessGuard.class));
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
