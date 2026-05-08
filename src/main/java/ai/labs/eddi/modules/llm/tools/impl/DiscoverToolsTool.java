/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;

import java.util.ArrayList;
import java.util.List;

/**
 * Meta-tool for lazy/dynamic tool loading. When the tool loading strategy is
 * {@code lazy} or {@code dynamic}, only this tool is initially presented to the
 * LLM. The LLM calls it to discover available tools by category or keyword, and
 * the matching tool schemas are then injected into the context for subsequent
 * turns.
 * <p>
 * This tool is NOT CDI-managed — it is constructed per-invocation by
 * {@code AgentOrchestrator} with the available tool specifications.
 *
 * @since 6.0.0
 */
public class DiscoverToolsTool {

    private final List<ToolSpecification> allToolSpecs;
    private final int maxToolsInContext;

    /**
     * @param allToolSpecs
     *            all available tool specifications for this agent
     * @param maxToolsInContext
     *            maximum number of tools to return per discovery call
     */
    public DiscoverToolsTool(List<ToolSpecification> allToolSpecs, int maxToolsInContext) {
        this.allToolSpecs = allToolSpecs != null ? List.copyOf(allToolSpecs) : List.of();
        this.maxToolsInContext = maxToolsInContext > 0 ? maxToolsInContext : 20;
    }

    @Tool(name = "discover_tools", value = "Discover available tools by category or keywords. " +
            "Returns matching tool names and descriptions. Use this to find the right tool before calling it.")
    public String discoverTools(
                                @P("Optional category to filter tools (e.g., 'web', 'data', 'math', 'memory')") String category,
                                @P("Optional keywords to search in tool names and descriptions") String keywords) {

        List<ToolSpecification> matches = new ArrayList<>();

        for (ToolSpecification spec : allToolSpecs) {
            // Skip the discover_tools meta-tool itself
            if ("discover_tools".equals(spec.name())) {
                continue;
            }

            boolean categoryMatch = category == null || category.isBlank() || matchesCategory(spec, category);
            boolean keywordMatch = keywords == null || keywords.isBlank() || matchesKeywords(spec, keywords);

            if (categoryMatch && keywordMatch) {
                matches.add(spec);
            }
        }

        // Cap results
        if (matches.size() > maxToolsInContext) {
            matches = matches.subList(0, maxToolsInContext);
        }

        if (matches.isEmpty()) {
            return "{\"tools\": [], \"message\": \"No tools found matching the criteria.\", \"totalAvailable\": " + allToolSpecs.size() + "}";
        }

        StringBuilder sb = new StringBuilder("{\"tools\": [");
        for (int i = 0; i < matches.size(); i++) {
            ToolSpecification spec = matches.get(i);
            if (i > 0)
                sb.append(", ");
            sb.append("{\"name\": ").append(escapeJson(spec.name()));
            if (spec.description() != null) {
                sb.append(", \"description\": ").append(escapeJson(spec.description()));
            }
            sb.append("}");
        }
        sb.append("], \"count\": ").append(matches.size());
        sb.append(", \"totalAvailable\": ").append(allToolSpecs.size()).append("}");

        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null)
            return "null";
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    private boolean matchesCategory(ToolSpecification spec, String category) {
        String lower = category.toLowerCase();
        String name = spec.name() != null ? spec.name().toLowerCase() : "";
        String desc = spec.description() != null ? spec.description().toLowerCase() : "";

        return name.contains(lower) || desc.contains(lower);
    }

    private boolean matchesKeywords(ToolSpecification spec, String keywords) {
        String[] terms = keywords.toLowerCase().split("\\s+");
        String name = spec.name() != null ? spec.name().toLowerCase() : "";
        String desc = spec.description() != null ? spec.description().toLowerCase() : "";

        for (String term : terms) {
            if (name.contains(term) || desc.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get the tool specifications for this meta-tool itself (used by
     * AgentOrchestrator).
     */
    public List<ToolSpecification> getOwnSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(DiscoverToolsTool.class);
    }
}
