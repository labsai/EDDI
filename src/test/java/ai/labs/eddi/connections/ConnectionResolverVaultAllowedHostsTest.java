/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.ResolutionPrincipalContext;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A connection's {@code baseUrlAllowlist} decided where its credential went,
 * and the {@code ${vault:…}} secrets behind the credential — with
 * {@code allowedHosts} of their own — were turned into plaintext without that
 * list ever being consulted. A connection was therefore a way to send a
 * restricted secret to a host its owner had excluded.
 */
class ConnectionResolverVaultAllowedHostsTest {

    private static final URI TARGET = URI.create("https://partner.example.com/api/items");

    private ConnectionRegistry registry;
    private SecretResolver secretResolver;
    private ISecretProvider secretProvider;
    private ConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        secretResolver = mock(SecretResolver.class);
        secretProvider = mock(ISecretProvider.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString()))
                .thenAnswer(i -> i.<String>getArgument(0).replace("${vault:partner-token}", "live-token").replace("${vault:partner-pw}", "hunter2"));
        var credentials = new CredentialReferenceResolver(secretResolver, globalVariableResolver, new VaultHostAllowlist(secretProvider));
        resolver = new ConnectionResolver(registry, credentials, mock(CallerIdentityContext.class), new SimpleMeterRegistry(),
                mock(AccessTokenSupplier.class), false);
        resolver.resolutionPrincipalContext = new ResolutionPrincipalContext();
    }

    private void secretAllowedAt(String keyName, List<String> hosts) throws Exception {
        when(secretProvider.getMetadata(new SecretReference("default", keyName)))
                .thenReturn(new SecretMetadata("default", keyName, Instant.EPOCH, null, null, null, "partner", List.of("*"), hosts));
    }

    private void register(AuthType authType) {
        var connection = new ConnectionConfiguration();
        connection.setName("partner");
        connection.setAuthType(authType);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://partner.example.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        if (authType == AuthType.BASIC) {
            auth.setUsername("svc");
            auth.setPasswordRef("${vault:partner-pw}");
        } else {
            auth.setValueTemplate("Bearer ${vault:partner-token}");
        }
        connection.setStaticAuth(auth);
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
    }

    @Test
    @DisplayName("STATIC: an origin on the connection's allowlist but not on the secret's allowedHosts is refused before decryption")
    void staticSecretExcludedFromTargetIsRefused() throws Exception {
        register(AuthType.STATIC);
        secretAllowedAt("partner-token", List.of("other.example.com"));

        var error = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:partner}", TARGET, null));

        assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        assertTrue(error.getMessage().contains("${vault:partner-token}"), error.getMessage());
        assertTrue(error.getMessage().contains("partner.example.com"), error.getMessage());
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("BASIC: the password reference is held to its own allowedHosts too")
    void basicPasswordExcludedFromTargetIsRefused() throws Exception {
        register(AuthType.BASIC);
        secretAllowedAt("partner-pw", List.of("other.example.com"));

        var error = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:partner}", TARGET, null));

        assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("a secret whose allowedHosts names the target, or that has none, resolves as before")
    void allowedOrUnrestrictedSecretResolves() throws Exception {
        register(AuthType.STATIC);
        secretAllowedAt("partner-token", List.of("partner.example.com"));
        assertEquals("Bearer live-token", resolver.resolve("${connection:partner}", TARGET, null).headerValue());

        secretAllowedAt("partner-token", null);
        assertEquals("Bearer live-token", resolver.resolve("${connection:partner}", TARGET, null).headerValue());
    }
}
