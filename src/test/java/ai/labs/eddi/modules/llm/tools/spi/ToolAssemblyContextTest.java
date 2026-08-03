/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the R2 step 1 SPI's small helper types: {@link ToolAssemblyContext}
 * (whitelist predicates) and {@link ToolContribution} (the empty/no-failures
 * convenience constructors). Both are now used throughout
 * {@code AgentOrchestrator#buildToolSetup} and every {@code ToolSourceProvider}
 * implementation; these tests cover the helper semantics directly, which the
 * provider-level suites exercise only incidentally.
 *
 * @author tests
 */
class ToolAssemblyContextTest {

    private ToolAssemblyContext context(List<String> whitelist) {
        return new ToolAssemblyContext(null, null, whitelist, new DynamicAgentConfig(), "user-1", "agent-1", null);
    }

    @Test
    void isWhitelisted_nullWhitelist_falseForAnyKey() {
        assertFalse(context(null).isWhitelisted("calculator"));
    }

    @Test
    void isWhitelisted_configuredWhitelist_trueOnlyForListedKey() {
        var ctx = context(List.of("calculator", "websearch"));

        assertTrue(ctx.isWhitelisted("calculator"));
        assertFalse(ctx.isWhitelisted("weather"));
    }

    @Test
    void hasNoWhitelist_nullOrEmpty_true() {
        assertTrue(context(null).hasNoWhitelist());
        assertTrue(context(List.of()).hasNoWhitelist());
    }

    @Test
    void hasNoWhitelist_configuredWhitelist_false() {
        assertFalse(context(List.of("calculator")).hasNoWhitelist());
    }

    @Test
    void toolContribution_empty_hasNoSpecsExecutorsOrFailures() {
        var contribution = ToolContribution.empty();

        assertTrue(contribution.specs().isEmpty());
        assertTrue(contribution.executors().isEmpty());
        assertTrue(contribution.toolSources().isEmpty());
        assertTrue(contribution.toolEndpoints().isEmpty());
        assertTrue(contribution.failures().isEmpty());
    }

    @Test
    void toolContribution_fourArgConstructor_defaultsFailuresToEmpty() {
        var contribution = new ToolContribution(List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());

        assertTrue(contribution.failures().isEmpty());
    }

    @Test
    void providerFailure_carriesEveryField() {
        var failure = new ProviderFailure("mcp", "server-1", ProviderFailure.Kind.CONNECTION_FAILURE, "timed out");

        assertEquals("mcp", failure.source());
        assertEquals("server-1", failure.identifier());
        assertEquals(ProviderFailure.Kind.CONNECTION_FAILURE, failure.kind());
        assertEquals("timed out", failure.message());
    }
}
