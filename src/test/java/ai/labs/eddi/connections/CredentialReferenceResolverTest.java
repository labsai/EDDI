/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.secrets.SecretAccess;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        lenient().when(secretResolver.resolveValue(anyString(), any())).thenAnswer(i -> i.getArgument(0));
        resolver = new CredentialReferenceResolver(secretResolver, globalVariableResolver);
    }

    @Test
    @DisplayName("a fully resolved value comes back as-is")
    void resolvesCleanly() {
        when(secretResolver.resolveValue(eq("Bearer ${vault:jira}"), any())).thenReturn("Bearer live-token");

        assertEquals("Bearer live-token",
                resolver.resolveRequired("Bearer ${vault:jira}", "jira", "credential", SecretAccess.outsideAgentGrants("test")));
    }

    @Test
    @DisplayName("an unresolved ${vars:} is refused — this is the one the refresh path used to send")
    void refusesUnresolvedGlobalVariable() {
        // The copy on the refresh path checked only the vault forms, so a typo in a
        // global variable was sent to the token endpoint AS the client secret. The
        // provider answered invalid_client, which maps to GRANT_UNUSABLE, and every
        // grant on that connection was marked REFRESH_FAILED — terminally.
        var error = assertThrows(ConnectionException.class,
                () -> resolver.resolveRequired("${vars:client-secret}", "jira", "client secret", SecretAccess.outsideAgentGrants("test")));

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION, error.getReason());
        assertTrue(error.getMessage().contains("client secret"), error.getMessage());
        assertTrue(error.getMessage().contains("jira"), "the message has to name the connection to be actionable: " + error.getMessage());
    }

    @Test
    @DisplayName("an unresolved ${vault:} is refused too, in either spelling")
    void refusesUnresolvedVaultReference() {
        assertThrows(ConnectionException.class,
                () -> resolver.resolveRequired("${vault:missing}", "jira", "credential", SecretAccess.outsideAgentGrants("test")));
        assertThrows(ConnectionException.class,
                () -> resolver.resolveRequired("${eddivault:missing}", "jira", "credential", SecretAccess.outsideAgentGrants("test")));
    }

    @Test
    @DisplayName("a reference that resolves to nothing is refused rather than sent as an empty credential")
    void refusesEmptyResult() {
        when(secretResolver.resolveValue(eq("${vault:blank}"), any())).thenReturn("   ");

        assertEquals(ConnectionException.Reason.INVALID_CONFIGURATION,
                assertThrows(ConnectionException.class,
                        () -> resolver.resolveRequired("${vault:blank}", "jira", "credential", SecretAccess.outsideAgentGrants("test"))).getReason());
    }

    @Test
    @DisplayName("global variables expand before the vault does")
    void expandsVariablesBeforeSecrets() {
        // A variable may expand INTO a vault reference; the reverse is never intended.
        when(globalVariableResolver.resolveValue("${vars:secret-ref}")).thenReturn("${vault:jira}");
        when(secretResolver.resolveValue(eq("${vault:jira}"), any())).thenReturn("live-token");

        assertEquals("live-token", resolver.resolveRequired("${vars:secret-ref}", "jira", "credential", SecretAccess.outsideAgentGrants("test")));
    }

    @Test
    @DisplayName("the unresolved-reference check answers exactly what the regex it replaced answered")
    void unresolvedCheckMatchesTheRegex() {
        Pattern regex = Pattern.compile("\\$\\{(vault|eddivault|vars):[^}]*}");
        for (String value : List.of("${vault:k}", "${vault:}", "${eddivault:k}", "${vars:x}", "Bearer ${vars:x", "}${vault:x", "${vault:x}}",
                "a ${vault:b ${vars:c} d", "${vaul:x}", "plain-token", "${vars:x}${vault:", "x}${vault:", "${${vault:a}")) {
            assertEquals(regex.matcher(value).find(), CredentialReferenceResolver.hasUnresolvedReference(value), value);
        }
    }

    @Test
    @DisplayName("a decrypted value full of unterminated openers is checked in linear time")
    void unresolvedCheckIsLinear() {
        // A closing brace only in front of every opener: the regex rescanned the
        // rest of the value at each of the 300 000 openers before failing.
        String value = "}" + "${vault:".repeat(300_000);
        when(secretResolver.resolveValue(eq("${vault:big}"), any())).thenReturn(value);

        String resolved = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> resolver.resolveRequired("${vault:big}", "jira", "credential", SecretAccess.outsideAgentGrants("test")));

        assertEquals(value, resolved);
    }

    @Test
    @DisplayName("a null template is refused, not passed on as a credential")
    void refusesNull() {
        lenient().when(globalVariableResolver.resolveValue(null)).thenReturn(null);
        lenient().when(secretResolver.resolveValue(eq(null), any())).thenReturn(null);

        assertThrows(ConnectionException.class, () -> resolver.resolveRequired(null, "jira", "credential", SecretAccess.outsideAgentGrants("test")));
    }
}
