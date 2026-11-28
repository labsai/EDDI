/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The run-time backstop: a {@code ${vars:...}} provider type must never reach a
 * provider that authenticates with the server's own cloud identity.
 */
@DisplayName("ProviderTypes")
class ProviderTypesTest {

    @Test
    @DisplayName("a variable that resolves to a restricted provider is left unresolved, so the registry refuses it")
    void variableToRestrictedIsRefused() {
        var resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue("${vars:provider}")).thenReturn("bedrock");
        when(resolver.resolveValue("${vars:other}")).thenReturn(" Oracle-GenAI ");

        assertEquals("${vars:provider}", ProviderTypes.resolve(resolver, "${vars:provider}"));
        assertEquals("${vars:other}", ProviderTypes.resolve(resolver, "${vars:other}"));
    }

    @Test
    @DisplayName("a variable to an ordinary provider resolves; a literal restricted type is untouched")
    void ordinaryAndLiteralPass() {
        var resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue("${vars:provider}")).thenReturn("openai");
        when(resolver.resolveValue("bedrock")).thenReturn("bedrock");

        assertEquals("openai", ProviderTypes.resolve(resolver, "${vars:provider}"));
        assertEquals("bedrock", ProviderTypes.resolve(resolver, "bedrock"));
    }

    @Test
    @DisplayName("the default list names the four ambient-credential and in-JVM providers")
    void defaults() {
        assertTrue(ProviderTypes.isRestricted("bedrock"));
        assertTrue(ProviderTypes.isRestricted(" Gemini-Vertex "));
        assertTrue(ProviderTypes.isRestricted("oracle-genai"));
        assertTrue(ProviderTypes.isRestricted("jlama"));
        assertFalse(ProviderTypes.isRestricted("openai"));
        assertFalse(ProviderTypes.isRestricted(null));
    }
}
