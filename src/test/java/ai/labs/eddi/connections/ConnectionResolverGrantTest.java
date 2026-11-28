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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private SecretResolver secrets;
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
        when(provider.getMetadata(new SecretReference("default", "rotated-secret")))
                .thenReturn(new SecretMetadata("default", "rotated-secret", Instant.now(), null, null, "c", null, List.of(AGENT_B)));
        for (String key : List.of("client-secret", "token", "signing-key")) {
            var ref = new SecretReference("default", key);
            when(provider.resolve(ref)).thenReturn("plain-" + key);
            when(provider.getMetadata(ref)).thenReturn(new SecretMetadata("default", key, Instant.now(), null, null, "c", null, List.of(AGENT_A)));
        }
        secrets = new SecretResolver(provider, new SimpleMeterRegistry(), 5, 100, "enforce");
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
    @DisplayName("a STATIC connection is checked on the field it sends, not on a retained passwordRef it never resolves")
    void staticChecksOnlyItsOwnField() {
        var connection = staticHeader();
        connection.getStaticAuth().setPasswordRef("${vault:rotated-secret}"); // granted to B only, and unused by STATIC
        register(connection);

        assertEquals("Bearer plain-token", resolver.resolve("${connection:crm}", GRAPH, null, SecretAccess.agent(AGENT_A)).headerValue());

        connection.setAuthType(AuthType.BASIC);
        connection.getStaticAuth().setUsername("svc");
        var e = assertThrows(ConnectionException.class,
                () -> resolver.resolve("${connection:crm}", GRAPH, null, SecretAccess.agent(AGENT_A)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason(), "BASIC sends the password, so its grant applies");
    }

    @Test
    @DisplayName("a credential variable repointed to another secret changes the binding fingerprint, so a cached card is not reused")
    void fingerprintFollowsTheVariable() {
        var connection = staticHeader();
        connection.getStaticAuth().setValueTemplate("Bearer ${vars:crm-token}");
        register(connection);
        var pointsAt = new AtomicReference<>("${vault:token}");
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveValue(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value.replace("${vars:crm-token}", pointsAt.get());
        });
        var repointable = new ConnectionResolver(registry, new CredentialReferenceResolver(secrets, variables), mock(CallerIdentityContext.class),
                new SimpleMeterRegistry(), tokens, true);

        String before = repointable.bindingFingerprint("${connection:crm}");
        pointsAt.set("${vault:rotated-secret}");
        String after = repointable.bindingFingerprint("${connection:crm}");

        assertTrue(before.contains("${vault:token}"), before);
        assertTrue(after.contains("${vault:rotated-secret}"), after);
        assertTrue(!before.equals(after), "the same template, another secret: another key");
    }

    @Test
    void bareTokenPathChecksToo() {
        register(oauthService());
        var e = assertThrows(ConnectionException.class,
                () -> resolver.resolveAccessToken("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_B)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason());
        verify(tokens, never()).accessToken(any(), any());
    }

    @Test
    @DisplayName("a JWT bearer connection is checked against its private key, the credential that mints its token")
    void jwtBearerChecksThePrivateKey() {
        var connection = oauthService();
        connection.setAuthType(AuthType.OAUTH2_JWT_BEARER);
        connection.getOauth().setClientSecret(null);
        connection.getOauth().setPrivateKey("${vault:signing-key}");
        register(connection);
        var e = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_B)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason());
        assertTrue(e.getMessage().contains("signing-key"), e.getMessage());
        verify(tokens, never()).accessToken(any(), any());
    }

    @Test
    @DisplayName("a deleted client secret refuses the cached token as a configuration error, not a grant refusal")
    void missingSecretIsAConfigurationError() throws Exception {
        var ref = new SecretReference("default", "client-secret");
        when(provider.getMetadata(ref)).thenThrow(new ISecretProvider.SecretNotFoundException("gone"));
        register(oauthService());
        var e = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_A)));
        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, e.getReason());
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
        var e = assertThrows(ConnectionException.class, () -> resolver.requireGranted("${connection:exo}", SecretAccess.agent(AGENT_B)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, e.getReason());
        assertDoesNotThrow(() -> resolver.requireGranted("${connection:exo}", SecretAccess.agent(AGENT_A)));
        verify(provider, never()).resolve(any());
    }

    @Test
    @DisplayName("a token minted after the credential variable was repointed is not released to an agent the new secret leaves out")
    void credentialRepointedDuringTheMintIsCheckedAgain() {
        var connection = oauthService();
        connection.getOauth().setClientSecret("${vars:exo-secret}");
        register(connection);
        var pointsAt = new AtomicReference<>("${vault:client-secret}");
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveValue(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return value.equals("${vars:exo-secret}") ? pointsAt.get() : value;
        });
        var repointing = new ConnectionResolver(registry, new CredentialReferenceResolver(secrets, variables), mock(CallerIdentityContext.class),
                new SimpleMeterRegistry(), tokens, true);
        repointing.resolutionPrincipalContext = principals;
        // The administrator repoints the variable while the token is being minted:
        // the check before the lookup read the old secret, the mint the new one.
        when(tokens.accessToken(any(), any())).thenAnswer(inv -> {
            pointsAt.set("${vault:rotated-secret}");
            return "minted-with-rotated-secret";
        });

        var header = assertThrows(ConnectionException.class,
                () -> repointing.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_A)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, header.getReason());
        assertTrue(header.getMessage().contains("rotated-secret"), header.getMessage());

        pointsAt.set("${vault:client-secret}");
        var bare = assertThrows(ConnectionException.class,
                () -> repointing.resolveAccessToken("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_A)));
        assertEquals(ConnectionException.Reason.SECRET_NOT_GRANTED, bare.getReason());

        // The agent the new secret admits gets the token.
        assertEquals("Bearer minted-with-rotated-secret",
                repointing.resolve("${connection:exo}", GRAPH, null, SecretAccess.agent(AGENT_B)).headerValue());
    }
}
