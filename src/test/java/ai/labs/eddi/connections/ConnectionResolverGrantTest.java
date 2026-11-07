/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretAccess;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Connection credentials are checked against the asking agent's grant before
 * anything is resolved or looked up — an OAuth token in the grant store was
 * minted with a client secret possibly resolved for another agent (#1179).
 */
class ConnectionResolverGrantTest {

    private static final String AGENT_A = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final URI GRAPH = URI.create("https://graph.microsoft.com/v1.0/me");

    private ConnectionRegistry registry;
    private AccessTokenSupplier tokens;
    private ISecretProvider provider;
    private ResolutionPrincipalContext principals;
    private ConnectionResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(ConnectionRegistry.class);
        tokens = mock(AccessTokenSupplier.class);
        when(tokens.accessToken(any(), any())).thenReturn("cached-access-token");
        provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        for (String key : List.of("client-secret", "token")) {
            var ref = new SecretReference("default", key);
            when(provider.resolve(ref)).thenReturn("plain-" + key);
            when(provider.getMetadata(ref)).thenReturn(new SecretMetadata("default", key, Instant.now(), null, null, "c", null, List.of(AGENT_A)));
        }
        var secrets = new SecretResolver(provider, new SimpleMeterRegistry(), 5, 100, "enforce");
        secrets.init();
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        principals = new ResolutionPrincipalContext();
        resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secrets, variables), mock(CallerIdentityContext.class),
                new SimpleMeterRegistry(), tokens, true);
        resolver.resolutionPrincipalContext = principals;
    }

    @AfterEach
    void unbind() {
        principals.clear();
    }

    private void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    private static ConnectionConfiguration oauthService() {
        var connection = new ConnectionConfiguration();
        connection.setName("exo");
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://login.microsoftonline.com/tenant/oauth2/v2.0/token");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    private static ConnectionConfiguration staticHeader() {
        var connection = new ConnectionConfiguration();
        connection.setName("crm");
        connection.setAuthType(AuthType.STATIC);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://graph.microsoft.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate("Bearer ${vault:token}");
        connection.setStaticAuth(auth);
        return connection;
    }

    @Test
    @DisplayName("a cached OAuth token is not handed to an agent the client secret's grant leaves out")
    void cachedTokenRefusedToUngrantedAgent() {
        register(oauthService());
        var e = assertThrows(ConnectionException.class,
                () -> resolver.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_B)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason());
        verify(tokens, never()).accessToken(any(), any());

        assertEquals("Bearer cached-access-token", resolver.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_A)).headerValue());
    }

    @Test
    void bareTokenPathChecksToo() {
        register(oauthService());
        assertThrows(ConnectionException.class, () -> resolver.resolveAccessToken("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_B)));
        verify(tokens, never()).accessToken(any(), any());
    }

    @Test
    void staticHeaderResolvesOnlyForTheGrantedAgent() throws Exception {
        register(staticHeader());
        assertEquals("Bearer plain-token", resolver.resolve("${connection:crm}", GRAPH, null, SecretAccess.agent(AGENT_A)).headerValue());
        var e = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:crm}", GRAPH, null, SecretAccess.agent(AGENT_B)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason());
    }

    @Test
    @DisplayName("requireGranted, for an MCP or A2A session cached per server")
    void requireGrantedWithoutResolving() throws Exception {
        register(oauthService());
        assertThrows(ConnectionException.class, () -> resolver.requireGranted("${connection:exo}", SecretAccess.agent(AGENT_B)));
        assertDoesNotThrow(() -> resolver.requireGranted("${connection:exo}", SecretAccess.agent(AGENT_A)));
        verify(provider, never()).resolve(any());
    }
}
