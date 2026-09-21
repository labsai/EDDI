/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void nullDynamicAgentConfig_isNormalizedToADisabledDefault() {
        // The record's Javadoc promises "never null" so providers can dereference it
        // without a guard; the compact constructor is what makes that true rather
        // than aspirational. Raised by Copilot on PR #626.
        var ctx = new ToolAssemblyContext(null, null, null, null, "user-1", "agent-1", null);

        assertNotNull(ctx.dynamicAgentConfig(), "a null argument must not reach a provider");
        assertFalse(ctx.dynamicAgentConfig().isEnabled(),
                "'no config supplied' must mean dynamic agents are OFF, never accidentally on");
    }

    @Test
    void suppliedDynamicAgentConfig_isPreserved() {
        var supplied = new DynamicAgentConfig();
        supplied.setEnabled(true);

        var ctx = new ToolAssemblyContext(null, null, null, supplied, "user-1", "agent-1", null);

        assertSame(supplied, ctx.dynamicAgentConfig(), "normalization must not replace a real config");
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
        var contribution = new ToolContribution(List.of(), Map.of(), Map.of(), Map.of());

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
