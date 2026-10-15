/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L1 of the 2026-10-15 security review: {@code ${vars:x}} was accepted wherever
 * a vault reference is required, so a client secret could live in plaintext in
 * the global variable store, which every editor can read. A variable in a
 * credential field may now only hold a {@code ${vault:…}} reference.
 */
class CredentialReferenceResolverVaultBackedTest {

    private static final String PLAINTEXT_SECRET = "gocspx-very-real-client-secret-value";

    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private CredentialReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        resolver = new CredentialReferenceResolver(secretResolver, globalVariableResolver);
    }

    @Nested
    @DisplayName("resolveVaultBacked — at use")
    class AtUse {

        @Test
        @DisplayName("a variable holding a vault reference resolves")
        void variableHoldingAVaultReferenceResolves() {
            when(globalVariableResolver.resolveValue("${vars:client-secret}")).thenReturn("${vault:google-secret}");
            when(secretResolver.resolveValue("${vault:google-secret}")).thenReturn(PLAINTEXT_SECRET);

            assertEquals(PLAINTEXT_SECRET, resolver.resolveVaultBacked("${vars:client-secret}", "google", "client secret"));
        }

        @Test
        @DisplayName("a variable holding the secret itself is refused, and the secret is neither sent nor quoted")
        void variableHoldingPlaintextRefused() {
            when(globalVariableResolver.resolveValue("${vars:client-secret}")).thenReturn(PLAINTEXT_SECRET);

            var error = assertThrows(ConnectionException.class,
                    () -> resolver.resolveVaultBacked("${vars:client-secret}", "google", "client secret"));

            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
            assertTrue(error.getMessage().contains("${vars:client-secret}"), error.getMessage());
            assertTrue(error.getMessage().contains("google"), error.getMessage());
            assertFalse(error.getMessage().contains(PLAINTEXT_SECRET), "the refusal must not quote the credential: " + error.getMessage());
            verify(secretResolver, never()).resolveValue(PLAINTEXT_SECRET);
        }

        @Test
        @DisplayName("a direct vault reference, a legacy spelling, and a missing variable behave as before")
        void directReferencesAndMissingVariables() {
            when(secretResolver.resolveValue("${vault:k}")).thenReturn("v");
            assertEquals("v", resolver.resolveVaultBacked("${vault:k}", "google", "client secret"));
            when(secretResolver.resolveValue("${eddivault:k}")).thenReturn("v");
            assertEquals("v", resolver.resolveVaultBacked("${eddivault:k}", "google", "client secret"));
            // Unresolved: GlobalVariableResolver leaves it in place, which is not a vault
            // reference.
            assertThrows(ConnectionException.class, () -> resolver.resolveVaultBacked("${vars:missing}", "google", "client secret"));
            assertThrows(ConnectionException.class, () -> resolver.resolveVaultBacked(null, "google", "client secret"));
            assertThrows(ConnectionException.class, () -> resolver.resolveVaultBacked("literal-secret", "google", "client secret"));
        }
    }

    @Nested
    @DisplayName("requireVaultBacked — at save")
    class AtSave {

        @Test
        @DisplayName("a variable holding a vault reference passes, and so does one that does not exist yet")
        void vaultBackedOrMissingPasses() {
            when(globalVariableResolver.resolveValue("${vars:client-secret}")).thenReturn("${vault:google-secret}");
            assertDoesNotThrow(() -> resolver.requireVaultBacked("${vars:client-secret}", "oauth.clientSecret"));
            assertDoesNotThrow(() -> resolver.requireVaultBacked("${vars:not-yet-created}", "oauth.clientSecret"));
            assertDoesNotThrow(() -> resolver.requireVaultBacked("${vault:direct}", "oauth.clientSecret"));
            assertDoesNotThrow(() -> resolver.requireVaultBacked(null, "oauth.clientSecret"));
        }

        @Test
        @DisplayName("a variable holding a literal is refused with the field and the fix, never the value")
        void plaintextVariableRefused() {
            when(globalVariableResolver.resolveValue("${vars:client-secret}")).thenReturn(PLAINTEXT_SECRET);

            var error = assertThrows(IllegalArgumentException.class,
                    () -> resolver.requireVaultBacked("${vars:client-secret}", "oauth.clientSecret"));

            assertTrue(error.getMessage().startsWith("oauth.clientSecret "), error.getMessage());
            assertTrue(error.getMessage().contains("PUT /secretstore/secrets"), error.getMessage());
            assertFalse(error.getMessage().contains(PLAINTEXT_SECRET), error.getMessage());
        }
    }

    @Nested
    @DisplayName("resolveRequired — a header template")
    class HeaderTemplate {

        @Test
        @DisplayName("a variable may expand to a vault reference or to scaffolding around one")
        void referenceOrScaffoldingPasses() {
            when(globalVariableResolver.resolveValue("${vars:scheme} ${vault:k}")).thenReturn("Bearer ${vault:k}");
            when(secretResolver.resolveValue("Bearer ${vault:k}")).thenReturn("Bearer live");
            assertEquals("Bearer live", resolver.resolveRequired("${vars:scheme} ${vault:k}", "jira", "credential"));

            when(globalVariableResolver.resolveValue("${vars:ref}")).thenReturn("${vault:k}");
            when(secretResolver.resolveValue("${vault:k}")).thenReturn("live");
            assertEquals("live", resolver.resolveRequired("${vars:ref}", "jira", "credential"));
        }

        @Test
        @DisplayName("a variable may not expand to the credential itself")
        void plaintextExpansionRefused() {
            when(globalVariableResolver.resolveValue("Bearer ${vars:token}")).thenReturn("Bearer " + PLAINTEXT_SECRET);

            var error = assertThrows(ConnectionException.class, () -> resolver.resolveRequired("Bearer ${vars:token}", "jira", "credential"));

            assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
            assertFalse(error.getMessage().contains(PLAINTEXT_SECRET), error.getMessage());
            verify(secretResolver, never()).resolveValue("Bearer " + PLAINTEXT_SECRET);
        }
    }
}
