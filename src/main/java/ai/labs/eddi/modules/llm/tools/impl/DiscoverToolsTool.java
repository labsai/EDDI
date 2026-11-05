/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.spi.ToolGroup;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import jakarta.enterprise.inject.Vetoed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    // ─── Grouped discovery: LAZY over http/mcp/a2a sources (lazySources) ───

    /** The name of the meta-tool, whichever form it takes. */
    public static final String NAME = "discover_tools";

    /** Most groups listed in the grouped spec's description. */
    static final int MAX_GROUPS_LISTED = 50;

    /** Tool names quoted for a group that has no description of its own. */
    private static final int GROUP_SAMPLE_NAMES = 4;

    /**
     * Catalog for grouped discovery: the lazily-loaded specs, in the order they
     * were assembled, and the group each belongs to. Fixed at construction.
     */
    private Map<String, ToolGroup> groupsByTool = Map.of();

    /**
     * Grouped discovery over a large external tool surface.
     *
     * @param catalogSpecs
     *            the specs {@code discover_tools} can activate
     * @param maxToolsInContext
     *            maximum number of tools returned per call
     * @param groupsByTool
     *            tool name → its group; a tool absent here is listed under no group
     *            and is found by keyword only
     */
    public DiscoverToolsTool(List<ToolSpecification> catalogSpecs, int maxToolsInContext, Map<String, ToolGroup> groupsByTool) {
        this(catalogSpecs, maxToolsInContext);
        this.groupsByTool = groupsByTool != null ? Map.copyOf(groupsByTool) : Map.of();
    }

    /**
     * The grouped form's own spec. Its description lists every group with a
     * one-line summary, so the model knows what exists without being sent every
     * tool.
     */
    public ToolSpecification groupedSpec() {
        var description = new StringBuilder("Find the tools you need before you call them: only tools this returns can be called, "
                + "and they stay callable afterwards. Ask for a whole group, or search by keywords (or both).");
        var groups = groupSummaries();
        if (!groups.isEmpty()) {
            description.append(" Groups:");
            int listed = 0;
            for (var group : groups.entrySet()) {
                if (listed++ == MAX_GROUPS_LISTED) {
                    description.append("\n- … and ").append(groups.size() - MAX_GROUPS_LISTED).append(" more (search by keywords)");
                    break;
                }
                description.append("\n- ").append(group.getKey()).append(" (").append(group.getValue().size()).append(" tools): ")
                        .append(summaryOf(group.getKey(), group.getValue()));
            }
        }
        return ToolSpecification.builder().name(NAME).description(description.toString())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("group", "Optional: a group name from the list above, to get its tools")
                        .addStringProperty("keywords", "Optional: words to search for in tool names and descriptions")
                        .build())
                .build();
    }

    /** The grouped form's executor: parses {@code group} and {@code keywords}. */
    public ToolExecutor groupedExecutor() {
        return (request, memoryId) -> {
            String group = null;
            String keywords = null;
            try {
                JsonNode args = request.arguments() == null || request.arguments().isBlank() ? null : MAPPER.readTree(request.arguments());
                if (args != null && args.isObject()) {
                    group = args.hasNonNull("group") ? args.get("group").asText() : null;
                    keywords = args.hasNonNull("keywords") ? args.get("keywords").asText() : null;
                }
            } catch (JsonProcessingException e) {
                // Arguments that are not JSON search for nothing in particular: the
                // result then lists the groups, which is what the model needs next.
            }
            return discoverGrouped(group, keywords);
        };
    }

    /**
     * One grouped discovery. A group narrows to its tools (matched by exact id,
     * else by a case-insensitive substring of id or description); keywords rank by
     * how many terms match, name matches first. Without either, the result lists
     * the groups and no tools — asking for everything would defeat lazy loading.
     */
    public String discoverGrouped(String group, String keywords) {
        Map<String, Object> response = new LinkedHashMap<>();
        boolean hasGroup = group != null && !group.isBlank();
        boolean hasKeywords = keywords != null && !keywords.isBlank();

        List<ToolSpecification> candidates = new ArrayList<>();
        for (ToolSpecification spec : allToolSpecs) {
            if (!NAME.equals(spec.name())) {
                candidates.add(spec);
            }
        }
        if (hasGroup) {
            Set<String> matchedGroups = matchGroups(group.strip());
            if (matchedGroups.isEmpty()) {
                response.put("tools", List.of());
                response.put("message", "No group named '" + group.strip() + "'. Use one of the groups listed, or search by keywords.");
                response.put("groups", groupListing());
                return write(response);
            }
            candidates.removeIf(spec -> {
                ToolGroup g = groupsByTool.get(spec.name());
                return g == null || !matchedGroups.contains(g.id());
            });
        }
        if (hasKeywords) {
            candidates = rankByKeywords(candidates, keywords);
        }
        if (!hasGroup && !hasKeywords) {
            response.put("tools", List.of());
            response.put("message", "Ask for a group or give keywords.");
            response.put("groups", groupListing());
            return write(response);
        }

        int found = candidates.size();
        List<ToolSpecification> matches = found > maxToolsInContext ? candidates.subList(0, maxToolsInContext) : candidates;
        List<Map<String, String>> toolList = new ArrayList<>();
        for (ToolSpecification spec : matches) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", spec.name());
            if (spec.description() != null) {
                entry.put("description", spec.description());
            }
            ToolGroup g = groupsByTool.get(spec.name());
            if (g != null) {
                entry.put("group", g.id());
            }
            toolList.add(entry);
        }
        response.put("tools", toolList);
        response.put("count", matches.size());
        if (found > matches.size()) {
            response.put("message", found + " tools matched; only the first " + matches.size()
                    + " are activated. Narrow the search with keywords to reach the others.");
        } else if (matches.isEmpty()) {
            response.put("message", "No tools found matching the criteria.");
        } else {
            response.put("message", "These tools can be called now.");
        }
        response.put("totalAvailable", candidatesTotal());
        return write(response);
    }

    private int candidatesTotal() {
        int total = 0;
        for (ToolSpecification spec : allToolSpecs) {
            if (!NAME.equals(spec.name())) {
                total++;
            }
        }
        return total;
    }

    private Set<String> matchGroups(String requested) {
        Set<String> exact = new LinkedHashSet<>();
        Set<String> partial = new LinkedHashSet<>();
        String lower = requested.toLowerCase(Locale.ROOT);
        for (ToolGroup g : groupsByTool.values()) {
            if (g.id().equals(requested) || g.id().equalsIgnoreCase(requested)) {
                exact.add(g.id());
            } else if (g.id().toLowerCase(Locale.ROOT).contains(lower)
                    || (g.description() != null && g.description().toLowerCase(Locale.ROOT).contains(lower))) {
                partial.add(g.id());
            }
        }
        return exact.isEmpty() ? partial : exact;
    }

    private static List<ToolSpecification> rankByKeywords(List<ToolSpecification> specs, String keywords) {
        String[] terms = keywords.toLowerCase(Locale.ROOT).split("[\\s,]+");
        List<Map.Entry<ToolSpecification, Integer>> scored = new ArrayList<>();
        for (ToolSpecification spec : specs) {
            String name = spec.name() != null ? spec.name().toLowerCase(Locale.ROOT) : "";
            String desc = spec.description() != null ? spec.description().toLowerCase(Locale.ROOT) : "";
            int score = 0;
            for (String term : terms) {
                if (term.isBlank()) {
                    continue;
                }
                if (name.contains(term)) {
                    score += 2;
                } else if (desc.contains(term)) {
                    score += 1;
                }
            }
            if (score > 0) {
                scored.add(Map.entry(spec, score));
            }
        }
        // Stable: equal scores keep the order the tools were assembled in.
        scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<ToolSpecification> ranked = new ArrayList<>();
        for (var entry : scored) {
            ranked.add(entry.getKey());
        }
        return ranked;
    }

    /** Group id → its tools' names, in assembly order. */
    private Map<String, List<String>> groupSummaries() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (ToolSpecification spec : allToolSpecs) {
            ToolGroup g = groupsByTool.get(spec.name());
            if (g != null) {
                groups.computeIfAbsent(g.id(), k -> new ArrayList<>()).add(spec.name());
            }
        }
        return groups;
    }

    private List<Map<String, Object>> groupListing() {
        List<Map<String, Object>> listing = new ArrayList<>();
        for (var group : groupSummaries().entrySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("group", group.getKey());
            entry.put("tools", group.getValue().size());
            entry.put("description", summaryOf(group.getKey(), group.getValue()));
            listing.add(entry);
        }
        return listing;
    }

    private String summaryOf(String groupId, List<String> toolNames) {
        for (ToolGroup g : groupsByTool.values()) {
            if (g.id().equals(groupId) && g.description() != null && !g.description().isBlank()) {
                return g.description().strip();
            }
        }
        var sample = toolNames.size() > GROUP_SAMPLE_NAMES ? toolNames.subList(0, GROUP_SAMPLE_NAMES) : toolNames;
        return "tools such as " + String.join(", ", sample) + (toolNames.size() > GROUP_SAMPLE_NAMES ? ", …" : "");
    }

    private static String write(Map<String, Object> response) {
        try {
            return MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return "{\"tools\":[],\"error\":\"JSON serialization failed\"}";
        }
    }

    /**
     * Get the tool specifications for this meta-tool itself (used by
     * AgentOrchestrator).
     */
    public List<ToolSpecification> getOwnSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(DiscoverToolsTool.class);
    }
}
