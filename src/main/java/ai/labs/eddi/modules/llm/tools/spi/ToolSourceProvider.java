/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

/**
 * One source of tools for a turn (R2 step 1) — built-ins, dynamic-agent tools,
 * user memory, conversation recall, attachments, httpcalls, MCP, A2A.
 * <p>
 * <b>Not yet wired.</b> The intent is that
 * {@code AgentOrchestrator#buildToolSetup} iterate a fixed-order list of these
 * and merge their {@link ToolContribution}s, replacing the if-chain inside
 * {@code collectAllBuiltInTools} plus the three bespoke per-source discovery
 * methods. That rewiring has NOT happened: {@code buildToolSetup} still calls
 * {@code discoverHttpCallTools}/{@code discoverMcpCallTools} and the inline A2A
 * block by name, and no {@code contribute} implementation has a production
 * caller. Providers currently expose both shapes — a legacy method the facade
 * calls, and {@code contribute} for the future path.
 * <p>
 * Two obligations below are therefore <em>specifications for the rewiring
 * step</em>, not properties the current implementations already satisfy. Both
 * are called out inline. Do not assume either holds when consuming a
 * contribution today.
 * <p>
 * This SPI is why R2 gates Wave 2: I5 ({@code GroupTaskToolsProvider}), I7
 * ({@code RecruitAgentTool} inside the dynamic-agent provider), and I17
 * ({@code ArtifactToolsProvider}) become new providers instead of edits to an
 * if-chain.
 */
public interface ToolSourceProvider {

    /**
     * This provider's nominal provenance tag (e.g. {@code "builtin"},
     * {@code "http"}, {@code "mcp"}, {@code "a2a"}, {@code "dynamic"}).
     * <p>
     * <b>Not authoritative for per-tool tagging.</b> An earlier version of this
     * contract said this value is "used as the {@code toolSources} value for every
     * tool it contributes" — that is not how the object-producing providers work,
     * and following it would be a security regression. Sources whose tools are
     * beans ({@code ContextualToolsProvider}, {@code DynamicAgentToolsProvider})
     * derive per-tool tags from the tool's own class via
     * {@code ToolObjectReflector}, so one provider legitimately emits
     * {@code "memory"}, {@code "recall"} and {@code "builtin"} across its
     * contribution. {@code ToolApprovalGate} matches {@code source:name} globs, so
     * stamping a single {@code source()} over such a contribution would make a
     * {@code require: ["memory:*"]} pattern stop matching — an ungated persistent
     * memory write.
     * <p>
     * The rewiring step must therefore take {@code toolSources} from
     * {@link ToolContribution#toolSources()} and use this only as a fallback for
     * sources that leave it empty (the externally-discovered http/mcp/a2a ones,
     * which are tagged at merge time today). This value is also not unique —
     * several providers legitimately return {@code "builtin"}.
     */
    String source();

    /**
     * Contributes this source's tools for the turn described by {@code ctx}.
     * <p>
     * <b>Target contract, not yet enforced:</b> an implementation should never
     * throw — a provider that cannot contribute (misconfiguration, discovery
     * failure, disabled) should return {@link ToolContribution#empty()} or a
     * contribution carrying {@link ProviderFailure} entries, and log a WARN itself,
     * so one failing provider cannot abort tool assembly for every other source.
     * Today only {@code McpToolsProvider} and {@code HttpCallToolsProvider}
     * actually satisfy this (their delegated {@code discover()} wraps everything in
     * a try/catch); the others propagate. The rewiring step must either add the
     * guard per provider or wrap each {@code contribute} call at the iteration site
     * — the plan's post-condition test ({@code ToolSourceProviderTest}, "a provider
     * throwing yields an empty contribution and the loop continues") is what pins
     * this.
     */
    ToolContribution contribute(ToolAssemblyContext ctx);
}
