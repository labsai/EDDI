/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

/**
 * One source of tools for a turn (R2 step 1) — built-ins, dynamic-agent tools,
 * user memory, conversation recall, attachments, httpcalls, MCP, A2A.
 * {@code AgentOrchestrator#buildToolSetup} iterates a fixed-order list of these
 * and merges their {@link ToolContribution}s, replacing what used to be an
 * if-chain inside {@code collectAllBuiltInTools} plus three bespoke per-source
 * discovery methods.
 * <p>
 * This SPI is why R2 gates Wave 2: I5 ({@code GroupTaskToolsProvider}), I7
 * ({@code RecruitAgentTool} inside the dynamic-agent provider), and I17
 * ({@code ArtifactToolsProvider}) become new providers instead of edits to an
 * if-chain.
 */
public interface ToolSourceProvider {

    /**
     * @return this provider's provenance tag, used as the {@code toolSources} value
     *         for every tool it contributes (e.g. {@code "builtin"},
     *         {@code "http"}, {@code "mcp"}, {@code "a2a"}, {@code "dynamic"},
     *         {@code "memory"}, {@code "recall"})
     */
    String source();

    /**
     * Contributes this source's tools for the turn described by {@code ctx}.
     * <p>
     * Must never throw — a provider that cannot contribute (misconfiguration,
     * discovery failure, disabled) returns {@link ToolContribution#empty()} or a
     * contribution carrying {@link ProviderFailure} entries, and logs a WARN
     * itself. One failing provider must never abort tool assembly for every other
     * source.
     */
    ToolContribution contribute(ToolAssemblyContext ctx);
}
