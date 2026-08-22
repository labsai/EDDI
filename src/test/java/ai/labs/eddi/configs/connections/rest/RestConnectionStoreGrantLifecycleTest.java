/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A connection's name and tenant are what its grants are filed under, so these
 * are tests about refresh tokens rather than about CRUD.
 */
class RestConnectionStoreGrantLifecycleTest {

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
                grantStore, secretProvider, true);
    }

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
        when(connectionStore.getCurrentResourceId(ID)).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return ID;
            }

            @Override
            public Integer getVersion() {
                return currentVersion;
            }
        });
        when(connectionStore.read(ID, currentVersion)).thenReturn(connection);
    }

    @Test
    @DisplayName("a rename is refused — the old name's grants would be inherited by whatever claims it next")
    void refusesRename() throws Exception {
        storedAs(connection("jira", "acme"), 3);

        var error = assertThrows(BadRequestException.class, () -> rest().updateConnection(ID, 3, connection("jira-old", "acme")));

        assertTrue(error.getMessage().contains("jira"), error.getMessage());
        assertTrue(error.getMessage().contains("grant"), "the message must say WHY, or it reads as gratuitous: " + error.getMessage());
    }

    @Test
    @DisplayName("a PER_USER connection cannot be created where no identity is verified")
    void refusesPerUserWithoutAuthorization() {
        var rest = new RestConnectionStore(connectionStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                connectionRegistry, grantStore, secretProvider, false);
        var connection = connection("drive", "acme");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setStaticAuth(null);
        connection.setOauth(oauth());

        // Previously this saved happily and then took down the next boot of every
        // replica. Now it is a 400 while the administrator is still looking at it.
        var error = assertThrows(BadRequestException.class, () -> rest.createConnection(connection));

        assertTrue(error.getMessage().contains("authorization.enabled"), error.getMessage());
    }

    @Test
    @DisplayName("an OAuth connection cannot be created without an active vault")
    void refusesOAuthWithoutVault() {
        when(secretProvider.isAvailable()).thenReturn(false);
        var connection = connection("analytics", "acme");
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setStaticAuth(null);
        connection.setOauth(oauth());

        var error = assertThrows(BadRequestException.class, () -> rest().createConnection(connection));

        assertTrue(error.getMessage().contains("Vault") || error.getMessage().contains("VAULT"), error.getMessage());
    }

    @Test
    @DisplayName("deleting a connection cleans up grants in ITS tenant, not the default one")
    void deletesGrantsInTheConnectionsOwnTenant() throws Exception {
        storedAs(connection("jira", "acme"), 2);
        when(connectionStore.readByName("acme", "jira")).thenReturn(null);

        // Version 1 is named in the request; the identity must still be read at the
        // CURRENT version, and under the connection's own tenant.
        rest().deleteConnection(ID, 1, false);

        verify(grantStore).deleteByConnection("acme", "jira");
        verify(grantStore, never()).deleteByConnection(eq("default"), anyString());
    }

    @Test
    @DisplayName("deleting one version of a still-live connection revokes nobody")
    void keepsGrantsWhileTheNameStillResolves() throws Exception {
        storedAs(connection("jira", "acme"), 2);
        when(connectionStore.readByName("acme", "jira")).thenReturn(connection("jira", "acme"));

        rest().deleteConnection(ID, 1, false);

        // Asserted explicitly: without it this passes just as well when the identity
        // could not be resolved at all, which is a different bug wearing the same
        // green tick.
        verify(connectionStore).readByName("acme", "jira");
        verify(grantStore, never()).deleteByConnection(anyString(), anyString());
    }

    private static OAuthConfig oauth() {
        var oauth = new OAuthConfig();
        oauth.setAuthorizationUrl("https://auth.example.com/authorize");
        oauth.setTokenUrl("https://auth.example.com/token");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        return oauth;
    }
}
