/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.SmimeSigningConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code SMIME_SIGNING} connection type (v3 plan §6.2, §14 item 5): its
 * save-time rules, and that only the email transport's path resolves it — every
 * header-producing path refuses, so the key can never leave as a credential.
 */
class ConnectionResolverSmimeSigningTest {

    static final URI RELAY = URI.create("smtp://smtp.contoso.com:587");

    ConnectionRegistry registry;
    SecretResolver secretResolver;
    GlobalVariableResolver globals;
    ConnectionResolver resolver;

    static ConnectionConfiguration smime() {
        var connection = new ConnectionConfiguration();
        connection.setName("cert");
        connection.setAuthType(AuthType.SMIME_SIGNING);
        connection.setBaseUrlAllowlist(new ArrayList<>(List.of("smtp://smtp.contoso.com:587")));
        var block = new SmimeSigningConfig();
        block.setCertificate("${vault:smime-cert}");
        block.setPassword("${vault:smime-password}");
        connection.setSmimeSigning(block);
        return connection;
    }

    @BeforeEach
    void setUp() {
        registry = mock(ConnectionRegistry.class);
        secretResolver = mock(SecretResolver.class);
        globals = mock(GlobalVariableResolver.class);
        when(globals.resolveValue(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(secretResolver.resolveValue("${vault:smime-cert}")).thenReturn("-----BEGIN PRIVATE KEY-----fixturefixture");
        when(secretResolver.resolveValue("${vault:smime-password}")).thenReturn("fixturefixture");
        resolver = new ConnectionResolver(registry, new CredentialReferenceResolver(secretResolver, globals), mock(CallerIdentityContext.class),
                new SimpleMeterRegistry(), mock(AccessTokenSupplier.class), true);
    }

    void register(ConnectionConfiguration connection) {
        when(registry.require(any(ConnectionReference.class))).thenReturn(connection);
        when(registry.find(any(ConnectionReference.class))).thenReturn(Optional.of(connection));
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void aCompleteConnectionValidates() {
            assertDoesNotThrow(() -> smime().validate());
        }

        @Test
        void thePasswordIsOptional() {
            var connection = smime();
            connection.getSmimeSigning().setPassword(null);
            assertDoesNotThrow(connection::validate);
        }

        @Test
        void aLiteralCertificateIsRefused() {
            var connection = smime();
            connection.getSmimeSigning().setCertificate("-----BEGIN PRIVATE KEY-----MIIEvQ");
            var e = assertThrows(IllegalArgumentException.class, connection::validate);
            assertTrue(e.getMessage().contains("smimeSigning.certificate must be a ${vault:"), e.getMessage());
        }

        @Test
        void aLiteralPasswordIsRefused() {
            var connection = smime();
            connection.getSmimeSigning().setPassword("hunter2hunter2");
            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        void theBlockIsRequired() {
            var connection = smime();
            connection.setSmimeSigning(null);
            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        void onlyServiceBinding() {
            var connection = smime();
            connection.setBinding(Binding.PER_USER);
            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        void theAllowlistNamesSmtpRelaysOnly() {
            var connection = smime();
            connection.setBaseUrlAllowlist(new ArrayList<>(List.of("https://api.contoso.com")));
            var e = assertThrows(IllegalArgumentException.class, connection::validate);
            assertTrue(e.getMessage().contains("SMTP relays"), e.getMessage());
        }

        @Test
        void aHeaderBlockBesideItIsRefused() {
            var connection = smime();
            connection.setStaticAuth(new StaticAuth());
            assertThrows(IllegalArgumentException.class, connection::validate);
        }

        @Test
        void anotherTypeCannotCarryTheBlock() {
            var connection = smime();
            connection.setAuthType(AuthType.STATIC);
            var staticAuth = new StaticAuth();
            staticAuth.setValueTemplate("Bearer ${vault:k}");
            connection.setStaticAuth(staticAuth);
            connection.setBaseUrlAllowlist(new ArrayList<>(List.of("https://api.contoso.com")));
            var e = assertThrows(IllegalArgumentException.class, connection::validate);
            assertTrue(e.getMessage().contains("smimeSigning belongs to authType SMIME_SIGNING"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        @Test
        @DisplayName("the email transport's path resolves the certificate and password from the vault")
        void transportResolves() {
            register(smime());
            var material = resolver.resolveSmimeSigning("${connection:cert}", RELAY);
            assertEquals("-----BEGIN PRIVATE KEY-----fixturefixture", material.certificate());
            assertEquals("fixturefixture", material.password());
            assertTrue(!material.toString().contains("fixture"), "no material in toString");
        }

        @Test
        @DisplayName("a relay not on the allowlist is refused before any secret is read")
        void otherRelay() {
            register(smime());
            var e = assertThrows(ConnectionException.class,
                    () -> resolver.resolveSmimeSigning("${connection:cert}", URI.create("smtps://smtp.evil.example")));
            assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, e.getReason());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("never as an HTTP header: resolve() refuses")
        void notAHeader() {
            register(smime());
            var e = assertThrows(ConnectionException.class, () -> resolver.resolve("${connection:cert}", RELAY, null));
            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, e.getReason());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("never as a mail login or an XOAUTH2 token")
        void notALogin() {
            register(smime());
            assertThrows(ConnectionException.class, () -> resolver.resolveBasicLogin("${connection:cert}", RELAY));
            assertThrows(ConnectionException.class, () -> resolver.resolveAccessToken("${connection:cert}", RELAY, null));
            assertThrows(ConnectionException.class, () -> resolver.requireMailLoginTarget("${connection:cert}", RELAY));
        }

        @Test
        @DisplayName("another type is not a signing connection")
        void wrongType() {
            var connection = smime();
            connection.setAuthType(AuthType.BASIC);
            register(connection);
            var e = assertThrows(ConnectionException.class, () -> resolver.resolveSmimeSigning("${connection:cert}", RELAY));
            assertEquals(ConnectionException.Reason.UNSUPPORTED_PLACEMENT, e.getReason());
        }

        @Test
        @DisplayName("the save-time check needs no secret")
        void saveTimeCheck() {
            var connection = smime();
            connection.getSmimeSigning().setPassword(null);
            register(connection);
            assertDoesNotThrow(() -> resolver.requireSmimeSigningTarget("${connection:cert}", RELAY));
            verify(secretResolver, never()).resolveValue(anyString());
            assertNull(connection.getSmimeSigning().getPassword());
        }
    }
}
