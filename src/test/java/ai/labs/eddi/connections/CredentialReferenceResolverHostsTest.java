/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A secret's {@code allowedHosts} applies to a vault reference held inside a
 * connection, not only to one written directly into an httpcall: the
 * connection's own allowlist is its author's list, not the secret owner's.
 */
class CredentialReferenceResolverHostsTest {

    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private VaultHostAllowlist allowlist;
    private CredentialReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenReturn("plaintext");
        allowlist = mock(VaultHostAllowlist.class);
        resolver = new CredentialReferenceResolver(secretResolver, globalVariableResolver);
        resolver.vaultHostAllowlist = allowlist;
    }

    @Test
    @DisplayName("a destination the secret does not allow is refused as TARGET_NOT_ALLOWED, before anything is resolved")
    void refusedDestination() {
        doThrow(new VaultHostAllowlist.HostNotAllowedException("not on allowedHosts")).when(allowlist)
                .requireAllowed(eq(Set.of("${vault:jira-token}")), eq("https://attacker.example/api"), anyString());

        var error = assertThrows(ConnectionException.class,
                () -> resolver.resolveVaultBackedFor("${vault:jira-token}", "https://attacker.example/api", "jira", "client secret"));

        assertEquals(ConnectionException.Reason.TARGET_NOT_ALLOWED, error.getReason());
        verify(secretResolver, never()).resolveValue(anyString());
    }

    @Test
    @DisplayName("a reference behind a ${vars:…} is checked in its expanded form")
    void expandedVariableIsChecked() {
        when(globalVariableResolver.resolveValue("Bearer ${vars:jira}")).thenReturn("Bearer ${vault:jira-token}");

        resolver.requireHostsAllowed("Bearer ${vars:jira}", "https://jira.example.com", "jira", "credential");

        verify(allowlist).requireAllowed(eq(Set.of("${vault:jira-token}")), eq("https://jira.example.com"), anyString());
    }

    @Test
    @DisplayName("a template with no vault reference asks nothing")
    void noReferenceNoCheck() {
        assertDoesNotThrow(() -> resolver.requireHostsAllowed("alice", "https://jira.example.com", "jira", "username"));
        verify(allowlist, never()).requireAllowed(any(Iterable.class), anyString(), anyString());
    }
}
