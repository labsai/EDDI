/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM output into a structured task list with three-tier fallback.
 * <ul>
 * <li>Tier 1: JSON array (strict schema)</li>
 * <li>Tier 2: Markdown numbered/bulleted list</li>
 * <li>Tier 3: Single task with entire goal as description</li>
 * </ul>
 *
 * @author ginccc
 */
public final class TaskListParser {

    private static final Logger LOGGER = Logger.getLogger(TaskListParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // Matches: 1. Title: description OR - Title: description OR * Title:
    // description
    private static final Pattern MARKDOWN_ITEM = Pattern.compile(
            "^\\s*(?:\\d+[.)\\]]|[-*+])\\s+(.+?)(?:\\s*[:—–-]\\s+(.+))?$",
            Pattern.MULTILINE);

    // Matches: (assigned to AgentName) or (assignedTo: agent-id)
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "\\(\\s*(?:assigned\\s+to|assignedTo)\\s*[:=]?\\s*(.+?)\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private TaskListParser() {
    }

    /**
     * A parsed task item from LLM output.
     */
    public record ParsedTask(String subject, String description, String assignedTo, int priority) {
    }

    /**
     * Parse LLM output into task items with three-tier fallback.
     *
     * @param llmOutput
     *            the raw LLM response text
     * @param members
     *            group members (unused by parse itself; accepted for API
     *            consistency — use {@link #resolveAgent} and
     *            {@link #roundRobinAssign} separately for assignment resolution)
     * @return non-empty list of parsed tasks
     */
    public static List<ParsedTask> parse(String llmOutput, List<GroupMember> members) {
        if (llmOutput == null || llmOutput.isBlank()) {
            LOGGER.debug("Empty LLM output, falling back to single task");
            return singleTaskFallback(null);
        }

        // Tier 1: Try JSON
        List<ParsedTask> result = tryParseJson(llmOutput);
        if (result != null && !result.isEmpty()) {
            LOGGER.debugf("Tier 1 (JSON): parsed %d tasks", result.size());
            return result;
        }

        // Tier 2: Try Markdown
        result = tryParseMarkdown(llmOutput);
        if (result != null && !result.isEmpty()) {
            LOGGER.debugf("Tier 2 (Markdown): parsed %d tasks", result.size());
            return result;
        }

        // Tier 3: Single task fallback
        LOGGER.debug("Tier 3 (Fallback): treating entire output as single task");
        return singleTaskFallback(llmOutput);
    }

    /**
     * Resolve an assignedTo reference against the member list. Matches by agentId
     * (exact) or displayName (case-insensitive).
     *
     * @return the agentId if found, or null if unresolvable
     */
    public static String resolveAgent(String assignedTo, List<GroupMember> members) {
        if (assignedTo == null || assignedTo.isBlank() || members == null || members.isEmpty()) {
            return null;
        }

        // Exact ID match
        for (GroupMember m : members) {
            if (assignedTo.equals(m.agentId())) {
                return m.agentId();
            }
        }

        // Case-insensitive display name match
        for (GroupMember m : members) {
            if (m.displayName() != null && m.displayName().equalsIgnoreCase(assignedTo.trim())) {
                return m.agentId();
            }
        }

        return null;
    }

    /**
     * Assign tasks round-robin to members when the LLM's assignment can't be
     * resolved.
     */
    public static String roundRobinAssign(int taskIndex, List<GroupMember> members) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        return members.get(taskIndex % members.size()).agentId();
    }

    // --- Tier 1: JSON parsing ---

    private static List<ParsedTask> tryParseJson(String text) {
        try {
            // Strip markdown code fence if present
            String json = text;
            int jsonStart = text.indexOf('[');
            int jsonEnd = text.lastIndexOf(']');

            // Also try to find JSON wrapped in an object like {"tasks": [...]}
            if (jsonStart < 0) {
                int objStart = text.indexOf('{');
                int objEnd = text.lastIndexOf('}');
                if (objStart >= 0 && objEnd > objStart) {
                    String objJson = text.substring(objStart, objEnd + 1);
                    JsonNode node = MAPPER.readTree(objJson);
                    // Look for an array field ("tasks", "items", etc.)
                    for (var it = node.fields(); it.hasNext();) {
                        var entry = it.next();
                        if (entry.getValue().isArray()) {
                            return parseJsonArray(entry.getValue().toString());
                        }
                    }
                }
                return null;
            }

            if (jsonEnd > jsonStart) {
                json = text.substring(jsonStart, jsonEnd + 1);
            }

            return parseJsonArray(json);
        } catch (Exception e) {
            LOGGER.debugf("JSON parse failed: %s", e.getMessage());
            return null;
        }
    }

    private static List<ParsedTask> parseJsonArray(String json) throws Exception {
        List<Map<String, Object>> items = MAPPER.readValue(json, new TypeReference<>() {
        });
        List<ParsedTask> tasks = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String subject = stringVal(item, "subject", "title", "name");
            String desc = stringVal(item, "description", "desc", "details", "instructions");
            String assignedTo = stringVal(item, "assignedTo", "assigned_to", "assignee", "agent");
            int priority = intVal(item, "priority", 0);

            if (subject == null || subject.isBlank()) {
                continue; // Skip entries without a subject
            }
            if (desc == null) {
                desc = subject; // Use subject as description if missing
            }
            tasks.add(new ParsedTask(subject, desc, assignedTo, priority));
        }
        return tasks.isEmpty() ? null : tasks;
    }

    // --- Tier 2: Markdown parsing ---

    private static List<ParsedTask> tryParseMarkdown(String text) {
        Matcher matcher = MARKDOWN_ITEM.matcher(text);
        List<ParsedTask> tasks = new ArrayList<>();
        int index = 0;

        while (matcher.find()) {
            String titlePart = matcher.group(1).trim();
            String descPart = matcher.group(2);

            // Check for inline assignment
            String assignedTo = null;
            Matcher assignMatcher = ASSIGNMENT.matcher(titlePart);
            if (assignMatcher.find()) {
                assignedTo = assignMatcher.group(1).trim();
                titlePart = titlePart.substring(0, assignMatcher.start()).trim();
            }

            String subject = titlePart;
            String description = descPart != null ? descPart.trim() : titlePart;

            tasks.add(new ParsedTask(subject, description, assignedTo, index));
            index++;
        }

        return tasks.isEmpty() ? null : tasks;
    }

    // --- Tier 3: Single task fallback ---

    private static List<ParsedTask> singleTaskFallback(String llmOutput) {
        String description = llmOutput != null && !llmOutput.isBlank()
                ? llmOutput.substring(0, Math.min(llmOutput.length(), 2000))
                : "Complete the assigned goal";
        return List.of(new ParsedTask("Complete goal", description, null, 0));
    }

    // --- Helpers ---

    private static String stringVal(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object val = map.get(key);
            if (val != null) {
                return val.toString();
            }
        }
        return null;
    }

    private static int intVal(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        return defaultVal;
    }
}
