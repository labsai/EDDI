/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One rule, one place. The three copies this replaced each checked a different
 * subset of the reference forms, so the same misconfigured connection behaved
 * three ways depending on which code path reached it.
 */
class CredentialReferenceResolverTest {

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

    @Test
    @DisplayName("a fully resolved value comes back as-is")
    void resolvesCleanly() {
        when(secretResolver.resolveValue("Bearer ${vault:jira}")).thenReturn("Bearer live-token");

        assertEquals("Bearer live-token", resolver.resolveRequired("Bearer ${vault:jira}", "jira", "credential"));
    }

    @Test
    @DisplayName("an unresolved ${vars:} is refused — this is the one the refresh path used to send")
    void refusesUnresolvedGlobalVariable() {
        // The copy on the refresh path checked only the vault forms, so a typo in a
        // global variable was sent to the token endpoint AS the client secret. The
        // provider answered invalid_client, which maps to GRANT_UNUSABLE, and every
        // grant on that connection was marked REFRESH_FAILED — terminally.
        var error = assertThrows(ConnectionException.class,
                () -> resolver.resolveRequired("${vars:client-secret}", "jira", "client secret"));

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
        assertTrue(error.getMessage().contains("client secret"), error.getMessage());
        assertTrue(error.getMessage().contains("jira"), "the message has to name the connection to be actionable: " + error.getMessage());
    }

    @Test
    @DisplayName("an unresolved ${vault:} is refused too, in either spelling")
    void refusesUnresolvedVaultReference() {
        assertThrows(ConnectionException.class, () -> resolver.resolveRequired("${vault:missing}", "jira", "credential"));
        assertThrows(ConnectionException.class, () -> resolver.resolveRequired("${eddivault:missing}", "jira", "credential"));
    }

    @Test
    @DisplayName("a reference that resolves to nothing is refused rather than sent as an empty credential")
    void refusesEmptyResult() {
        when(secretResolver.resolveValue("${vault:blank}")).thenReturn("   ");

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION,
                assertThrows(ConnectionException.class, () -> resolver.resolveRequired("${vault:blank}", "jira", "credential")).getReason());
    }

    @Test
    @DisplayName("global variables expand before the vault does")
    void expandsVariablesBeforeSecrets() {
        // A variable may expand INTO a vault reference; the reverse is never intended.
        when(globalVariableResolver.resolveValue("${vars:secret-ref}")).thenReturn("${vault:jira}");
        when(secretResolver.resolveValue("${vault:jira}")).thenReturn("live-token");

        assertEquals("live-token", resolver.resolveRequired("${vars:secret-ref}", "jira", "credential"));
    }

    @Test
    @DisplayName("a null template is refused, not passed on as a credential")
    void refusesNull() {
        lenient().when(globalVariableResolver.resolveValue(null)).thenReturn(null);
        lenient().when(secretResolver.resolveValue(null)).thenReturn(null);

        assertThrows(ConnectionException.class, () -> resolver.resolveRequired(null, "jira", "credential"));
    }
}
