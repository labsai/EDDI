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
 * @param failures
 *            structured per-server/per-tool discovery failures (MCP-style);
 *            empty when the source has nothing to report
 */
public record ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
        Map<String, String> toolSources, Map<String, String> toolEndpoints, List<ProviderFailure> failures) {

    /** Convenience for a source that never reports failures. */
    public ToolContribution(List<ToolSpecification> specs, Map<String, ToolExecutor> executors,
            Map<String, String> toolSources, Map<String, String> toolEndpoints) {
        this(specs, executors, toolSources, toolEndpoints, List.of());
    }

    /**
     * The empty contribution — returned by a provider that found nothing, or
     * failed.
     */
    public static ToolContribution empty() {
        return new ToolContribution(List.of(), Map.of(), Map.of(), Map.of(), List.of());
    }
}
