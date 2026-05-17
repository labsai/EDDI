/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import jakarta.enterprise.inject.Vetoed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
@Vetoed
public class DiscoverToolsTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        // Build response using Jackson for proper JSON escaping
        Map<String, Object> response = new LinkedHashMap<>();
        List<Map<String, String>> toolList = new ArrayList<>();
        for (ToolSpecification spec : matches) {
            Map<String, String> toolEntry = new LinkedHashMap<>();
            toolEntry.put("name", spec.name());
            if (spec.description() != null) {
                toolEntry.put("description", spec.description());
            }
            toolList.add(toolEntry);
        }
        response.put("tools", toolList);
        if (matches.isEmpty()) {
            response.put("message", "No tools found matching the criteria.");
        }
        response.put("count", matches.size());
        response.put("totalAvailable", allToolSpecs.size());

        try {
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            // Fallback: should never happen with simple maps
            return "{\"tools\":[],\"error\":\"JSON serialization failed\",\"totalAvailable\":" + allToolSpecs.size() + "}";
        }
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
