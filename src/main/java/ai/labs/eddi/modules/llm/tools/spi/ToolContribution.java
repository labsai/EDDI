/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.List;
import java.util.Map;

/**
 * One tool source's contribution to a turn's tool set (R2 step 1-2). Unifies
 * the three bespoke per-source result shapes {@code AgentOrchestrator} carried
 * before this — {@code HttpCallToolsResult} (specs, executors, endpoints),
 * {@code McpToolProviderManager.McpToolsResult} (specs, executors, failures),
 * {@code A2AToolProviderManager.A2AToolsResult} (specs, executors only) — into
 * one contract every {@link ToolSourceProvider} returns. A source that carries
 * nothing for a given component (e.g. a built-in provider has no endpoints)
 * passes an empty map/list, never null.
 *
 * @param specs
 *            the tool specifications this source contributes
 * @param executors
 *            dispatch name → executor for every spec above
 * @param toolSources
 *            dispatch name → provenance tag ({@code "builtin"|"http"|"mcp"
 *            |"a2a"|"dynamic"|"memory"|"recall"|...}), for gate-qualified
 *            {@code source:name} approval-pattern matching
 * @param toolEndpoints
 *            dispatch name → {@code "post:/path"} (http source only — feeds
 *            endpoint-qualified approval patterns); empty for every other
 *            source
 * @param toolRequestResolvers
 *            dispatch name → {@link ToolRequestResolver} (http source only —
 *            approval binds to the resolved request, not just the tool name);
 *            empty for every other source, exactly like {@code toolEndpoints}
 * @param failures
 *            structured per-server/per-tool discovery failures (MCP-style);
 *            empty when the source has nothing to report
 * @param toolCanonicalNames
 *            dispatch name → configuration slug
 *            ({@code searchWeb → websearch}), so the executor boundary prices a
 *            call and picks its cache TTL under the token the agent designer
 *            actually configured rather than the method name. Only the
 *            object-producing sources populate this — they get it from
 *            {@code ToolObjectReflector}; externally-discovered sources
 *            (http/mcp/a2a) have no separate configuration slug and leave it
 *            empty, which the merge treats as "dispatch name is the canonical
 *            name".
 */
public record ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
        Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ProviderFailure> failures,
        Map<String, String> toolCanonicalNames, Map<String, ToolRequestResolver> toolRequestResolvers) {

    /**
     * Defensively copies every component to an immutable view.
     * <p>
     * Without this, mutability varied per provider and per component — a discovery
     * method returning live {@code ArrayList}/{@code HashMap} for specs/executors
     * but {@code Map.of()} for {@code toolSources}, against {@link #empty()}'s
     * all-immutable. The rewiring step's natural implementation (merge into the
     * first contribution, or {@code toolSources().putAll(...)}) would then throw
     * {@code UnsupportedOperationException} for some sources and silently succeed
     * for others <em>depending on which source happened to be first in the
     * iteration order</em> — the worst possible failure shape. Uniformly immutable
     * makes that mistake fail fast and identically every time, and the merge target
     * is a fresh map the caller owns.
     */
    public ToolContribution {
        specs = specs == null ? List.of() : List.copyOf(specs);
        executors = executors == null ? Map.of() : Map.copyOf(executors);
        toolSources = toolSources == null ? Map.of() : Map.copyOf(toolSources);
        toolEndpoints = toolEndpoints == null ? Map.of() : Map.copyOf(toolEndpoints);
        failures = failures == null ? List.of() : List.copyOf(failures);
        toolCanonicalNames = toolCanonicalNames == null ? Map.of() : Map.copyOf(toolCanonicalNames);
        toolRequestResolvers = toolRequestResolvers == null ? Map.of() : Map.copyOf(toolRequestResolvers);
    }

    /**
     * Convenience for an externally-discovered source: no failures, and no
     * canonical names (http/mcp/a2a tools are configured under their dispatch name,
     * so there is no second slug to record). No resolvers either — only
     * {@code HttpCallToolsProvider} uses the overload that supplies them.
     */
    public ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints) {
        this(specs, executors, toolSources, toolEndpoints, List.of(), Map.of(), Map.of());
    }

    /**
     * Convenience for a source that reports failures but no canonical names or
     * resolvers.
     */
    public ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ProviderFailure> failures) {
        this(specs, executors, toolSources, toolEndpoints, failures, Map.of(), Map.of());
    }

    /**
     * Convenience for a source that supplies canonical names but no resolvers —
     * every object-producing source (builtin, contextual, dynamic, attachment,
     * group-task, and the LAZY {@code discover_tools} meta-tool). Only
     * {@code HttpCallToolsProvider} resolves to an HTTP request, so it is the sole
     * caller of the canonical constructor.
     */
    public ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ProviderFailure> failures,
            Map<String, String> toolCanonicalNames) {
        this(specs, executors, toolSources, toolEndpoints, failures, toolCanonicalNames, Map.of());
    }

    /**
     * The empty contribution — returned by a provider that found nothing, or
     * failed.
     */
    public static ToolContribution empty() {
        return new ToolContribution(List.of(), Map.of(), Map.of(), Map.of(), List.of(), Map.of(), Map.of());
    }
}
