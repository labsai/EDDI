/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

/**
 * One source of tools for a turn (R2 step 1) — built-ins, dynamic-agent tools,
 * user memory, conversation recall, attachments, httpcalls, MCP, A2A.
 * <p>
 * {@code AgentOrchestrator#buildToolSetup} iterates a fixed-order list of these
 * and merges their {@link ToolContribution}s via {@link ToolSourceRegistry},
 * which is also where the isolation guarantee below is enforced.
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
     * {@link ToolSourceRegistry#assemble} therefore takes {@code toolSources} from
     * {@link ToolContribution#toolSources()} and uses this only as the fallback for
     * sources that leave it empty (the externally-discovered http/mcp/a2a ones,
     * which were tagged at merge time before the SPI existed). This value is also
     * not unique — several providers legitimately return {@code "builtin"}.
     */
    String source();

    /**
     * Contributes this source's tools for the turn described by {@code ctx}.
     * <p>
     * <b>Should not throw</b> — a provider that cannot contribute
     * (misconfiguration, discovery failure, disabled) should return
     * {@link ToolContribution#empty()} or a contribution carrying
     * {@link ProviderFailure} entries, and log a WARN itself.
     * <p>
     * That obligation is enforced structurally rather than trusted:
     * {@link ToolSourceRegistry#assemble} wraps every call, so a provider that
     * throws anyway yields an empty contribution and the remaining sources still
     * assemble. This was deliberately not left to per-provider discipline — only
     * two of the original five satisfied it, and the failure mode of getting it
     * wrong (one misconfigured MCP server costing an agent its calculator) is far
     * worse than the cost of one try/catch at the iteration site.
     */
    ToolContribution contribute(ToolAssemblyContext ctx);
}
