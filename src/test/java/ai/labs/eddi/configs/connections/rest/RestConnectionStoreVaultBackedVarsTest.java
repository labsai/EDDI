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
import ai.labs.eddi.configs.connections.names.InMemoryConnectionNameClaimStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 at the write boundary: a connection whose {@code oauth.clientSecret} is a
 * {@code ${vars:x}} is saved only if the variable holds a vault reference.
 */
class RestConnectionStoreVaultBackedVarsTest {

    private static final String PLAINTEXT_SECRET = "gocspx-very-real-client-secret-value";

    private IConnectionStore connectionStore;
    private GlobalVariableResolver globalVariableResolver;
    private RestConnectionStore rest;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        ISecretProvider secretProvider = mock(ISecretProvider.class);
        lenient().when(secretProvider.isAvailable()).thenReturn(true);
        rest = new RestConnectionStore(connectionStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ConnectionRegistry.class), mock(IConnectionGrantStore.class), secretProvider, true, mock(ResourceAccessGuard.class),
                new InMemoryConnectionNameClaimStore(), new CredentialReferenceResolver(mock(SecretResolver.class), globalVariableResolver));
    }

    private static ConnectionConfiguration oauthConnection(String clientSecret) {
        var connection = new ConnectionConfiguration();
        connection.setName("google-drive");
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setClientId("client-id");
        oauth.setClientSecret(clientSecret);
        connection.setOauth(oauth);
        return connection;
    }

    @Test
    @DisplayName("a ${vars:} whose variable holds the client secret in plaintext is a 400 naming the field — nothing is written")
    void plaintextVariableRefused() throws Exception {
        when(globalVariableResolver.resolveValue("${vars:google-secret}")).thenReturn(PLAINTEXT_SECRET);

        var error = assertThrows(BadRequestException.class, () -> rest.createConnection(oauthConnection("${vars:google-secret}")));

        assertTrue(error.getMessage().contains("oauth.clientSecret"), error.getMessage());
        assertFalse(error.getMessage().contains(PLAINTEXT_SECRET), error.getMessage());
        verify(connectionStore, never()).create(any());
    }

    @Test
    @DisplayName("a ${vars:} whose variable holds a vault reference, or does not exist yet, reaches the store")
    void vaultBackedVariablePasses() throws Exception {
        when(globalVariableResolver.resolveValue("${vars:google-secret}")).thenReturn("${vault:google-secret}");
        when(connectionStore.create(any())).thenReturn(new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return "68a1b2c3d4e5f60718293a4b";
            }

            @Override
            public Integer getVersion() {
                return 1;
            }
        });

        rest.createConnection(oauthConnection("${vars:google-secret}"));
        rest.createConnection(oauthConnectionNamed("drive-2", "${vars:not-yet-created}"));

        verify(connectionStore, times(2)).create(any());
    }

    @Test
    @DisplayName("a STATIC valueTemplate whose variable holds the key is a 400 naming the field")
    void plaintextTemplateVariableRefused() throws Exception {
        when(globalVariableResolver.resolveValue("Bearer ${vars:jira-token}")).thenReturn("Bearer " + PLAINTEXT_SECRET);
        var connection = new ConnectionConfiguration();
        connection.setName("jira");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vars:jira-token}");
        connection.setStaticAuth(auth);

        var error = assertThrows(BadRequestException.class, () -> rest.createConnection(connection));

        assertTrue(error.getMessage().contains("staticAuth.valueTemplate"), error.getMessage());
        assertFalse(error.getMessage().contains(PLAINTEXT_SECRET), error.getMessage());
        verify(connectionStore, never()).create(any());
    }

    private static ConnectionConfiguration oauthConnectionNamed(String name, String clientSecret) {
        var connection = oauthConnection(clientSecret);
        connection.setName(name);
        return connection;
    }
}
