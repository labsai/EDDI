/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.engine.internal.TaskListParser.ParsedTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TaskListParser}.
 *
 * @author ginccc
 */
class TaskListParserTest {

    private static final List<GroupMember> MEMBERS = List.of(
            new GroupMember("agent-1", "Analyst", 0, "RESEARCHER"),
            new GroupMember("agent-2", "Writer", 1, "AUTHOR"));

    // --- Tier 1: JSON parsing ---

    @Test
    @DisplayName("Tier 1 — JSON array input parses into structured tasks")
    void parseValidJson_array() {
        String json = """
                [{"subject":"Task A","description":"Do A","assignedTo":"agent-1","priority":0}]
                """;

        List<ParsedTask> tasks = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, tasks.size());
        ParsedTask task = tasks.getFirst();
        assertEquals("Task A", task.subject());
        assertEquals("Do A", task.description());
        assertEquals("agent-1", task.assignedTo());
        assertEquals(0, task.priority());
    }

    @Test
    @DisplayName("Tier 1 — unknown JSON fields are silently ignored")
    void parseValidJson_withExtraFields() {
        String json = """
                [{"subject":"A","description":"B","foo":"bar","baz":42}]
                """;

        List<ParsedTask> tasks = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, tasks.size());
        assertEquals("A", tasks.getFirst().subject());
        assertEquals("B", tasks.getFirst().description());
    }

    @Test
    @DisplayName("Tier 1 — JSON object wrapping an array property is unwrapped")
    void parseJsonObject_wrappedInProperty() {
        String json = """
                {"tasks":[{"subject":"A","description":"B"}]}
                """;

        List<ParsedTask> tasks = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, tasks.size());
        assertEquals("A", tasks.getFirst().subject());
        assertEquals("B", tasks.getFirst().description());
    }

    // --- Tier 2: Markdown parsing ---

    @Test
    @DisplayName("Tier 2 — malformed JSON falls through to markdown parsing")
    void parseMalformedJson_fallsToMarkdown() {
        String markdown = "1. Task A: description A\n2. Task B: description B";

        List<ParsedTask> tasks = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(2, tasks.size());
        assertEquals("Task A", tasks.get(0).subject());
        assertEquals("description A", tasks.get(0).description());
        assertEquals("Task B", tasks.get(1).subject());
        assertEquals("description B", tasks.get(1).description());
    }

    @Test
    @DisplayName("Tier 2 — numbered list items are parsed")
    void parseMarkdown_numberedList() {
        String markdown = "1. Research competitors\n2. Draft strategy\n3. Review plan";

        List<ParsedTask> tasks = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(3, tasks.size());
        assertEquals("Research competitors", tasks.get(0).subject());
        assertEquals("Draft strategy", tasks.get(1).subject());
        assertEquals("Review plan", tasks.get(2).subject());
    }

    @Test
    @DisplayName("Tier 2 — bulleted list items are parsed")
    void parseMarkdown_bulletedList() {
        String markdown = "* Analyze data\n* Draft report";

        List<ParsedTask> tasks = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(2, tasks.size());
        assertEquals("Analyze data", tasks.get(0).subject());
        assertEquals("Draft report", tasks.get(1).subject());
    }

    @Test
    @DisplayName("Tier 2 — inline (assigned to ...) annotations are extracted")
    void parseMarkdown_withAssignments() {
        String markdown = "1. Research (assigned to Analyst)\n2. Draft (assigned to Writer)";

        List<ParsedTask> tasks = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(2, tasks.size());
        assertEquals("Research", tasks.get(0).subject());
        assertEquals("Analyst", tasks.get(0).assignedTo());
        assertEquals("Draft", tasks.get(1).subject());
        assertEquals("Writer", tasks.get(1).assignedTo());
    }

    // --- Tier 3: Fallback ---

    @Test
    @DisplayName("Tier 3 — unrecognizable text falls back to a single task")
    void parseGarbage_fallsToSingleTask() {
        String garbage = "This is not a task list at all";

        List<ParsedTask> tasks = TaskListParser.parse(garbage, MEMBERS);

        assertEquals(1, tasks.size());
        assertNotNull(tasks.getFirst().subject());
    }

    @Test
    @DisplayName("Tier 3 — null input falls back to a single task")
    void parseEmpty_fallsToSingleTask() {
        List<ParsedTask> tasks = TaskListParser.parse(null, MEMBERS);

        assertEquals(1, tasks.size());
        assertNotNull(tasks.getFirst().subject());
    }

    // --- Agent resolution ---

    @Test
    @DisplayName("resolveAgent matches by exact agentId")
    void resolveAgent_byId() {
        String resolved = TaskListParser.resolveAgent("agent-1", MEMBERS);

        assertEquals("agent-1", resolved);
    }

    @Test
    @DisplayName("resolveAgent matches by displayName (case-insensitive)")
    void resolveAgent_byDisplayName() {
        String resolved = TaskListParser.resolveAgent("analyst", MEMBERS);

        assertEquals("agent-1", resolved);
    }

    @Test
    @DisplayName("resolveAgent returns null for unknown references")
    void resolveAgent_unknown_returnsNull() {
        String resolved = TaskListParser.resolveAgent("unknown-agent", MEMBERS);

        assertNull(resolved);
    }

    // --- Edge cases (code review gap coverage) ---

    @Test
    @DisplayName("Empty JSON array falls back to single task")
    void emptyJsonArray_fallsToSingleTask() {
        var result = TaskListParser.parse("[]", MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Complete goal", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON wrapped in markdown code fences is parsed")
    void jsonInCodeFences() {
        String input = """
                Here are the tasks:
                ```json
                [{"subject": "Task A", "description": "Do A"}]
                ```
                """;
        var result = TaskListParser.parse(input, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Task A", result.getFirst().subject());
    }

    @Test
    @DisplayName("Empty string input falls back to single task")
    void emptyString_fallsToSingleTask() {
        var result = TaskListParser.parse("", MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Complete goal", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON with missing required fields is parsed with null subject")
    void jsonMissingSubject() {
        String input = "[{\"foo\": \"bar\", \"description\": \"some desc\"}]";
        var result = TaskListParser.parse(input, MEMBERS);

        // Should parse but subject will be null from JSON
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    @DisplayName("roundRobinAssign with empty members returns null")
    void roundRobinAssign_emptyMembers() {
        String result = TaskListParser.roundRobinAssign(0, List.of());
        assertNull(result);
    }

    @Test
    @DisplayName("roundRobinAssign distributes evenly")
    void roundRobinAssign_distributes() {
        assertEquals("agent-1", TaskListParser.roundRobinAssign(0, MEMBERS));
        assertEquals("agent-2", TaskListParser.roundRobinAssign(1, MEMBERS));
        assertEquals("agent-1", TaskListParser.roundRobinAssign(2, MEMBERS));
    }

    @Test
    @DisplayName("Tier 3 fallback preserves LLM output as description")
    void tier3Fallback_preservesOutput() {
        String freeformText = "Just talk to the user about their preferences and find a suitable hotel.";
        var result = TaskListParser.parse(freeformText, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Complete goal", result.getFirst().subject());
        assertTrue(result.getFirst().description().contains("hotel"),
                "Fallback should preserve LLM output as task description");
    }

    // --- Additional branch coverage tests ---

    @Test
    @DisplayName("JSON object without array field returns null (falls to markdown/fallback)")
    void jsonObject_noArrayField() {
        String input = "{\"message\": \"hello\", \"count\": 42}";
        var result = TaskListParser.parse(input, MEMBERS);

        // No array field found, no markdown items → falls to single task fallback
        assertEquals(1, result.size());
        assertEquals("Complete goal", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON with 'title' alias for subject field")
    void jsonAlternativeKey_title() {
        String json = """
                [{"title":"Task via Title","description":"Using title key"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Task via Title", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON with 'name' alias for subject field")
    void jsonAlternativeKey_name() {
        String json = """
                [{"name":"Task via Name","desc":"Using desc key"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Task via Name", result.getFirst().subject());
        assertEquals("Using desc key", result.getFirst().description());
    }

    @Test
    @DisplayName("JSON with 'assignee' alias for assignedTo")
    void jsonAlternativeKey_assignee() {
        String json = """
                [{"subject":"Task","description":"D","assignee":"agent-2"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("agent-2", result.getFirst().assignedTo());
    }

    @Test
    @DisplayName("JSON with 'assigned_to' alias for assignedTo")
    void jsonAlternativeKey_assigned_to() {
        String json = """
                [{"subject":"Task","description":"D","assigned_to":"agent-1"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("agent-1", result.getFirst().assignedTo());
    }

    @Test
    @DisplayName("JSON with missing description uses subject as description")
    void jsonMissingDescription_usesSubject() {
        String json = """
                [{"subject":"Only Subject"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Only Subject", result.getFirst().subject());
        assertEquals("Only Subject", result.getFirst().description());
    }

    @Test
    @DisplayName("JSON with priority as number")
    void jsonPriority_number() {
        String json = """
                [{"subject":"Task","description":"D","priority":3}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals(3, result.getFirst().priority());
    }

    @Test
    @DisplayName("JSON with priority as string (non-number) uses default 0")
    void jsonPriority_stringFallback() {
        String json = """
                [{"subject":"Task","description":"D","priority":"high"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals(0, result.getFirst().priority());
    }

    @Test
    @DisplayName("Markdown with dash-separated title and description")
    void markdownDashSeparator() {
        String markdown = "- Plan — create a detailed plan";
        var result = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Plan", result.getFirst().subject());
        assertEquals("create a detailed plan", result.getFirst().description());
    }

    @Test
    @DisplayName("Markdown item without description uses subject as description")
    void markdownItemWithoutDescription() {
        String markdown = "1. Research competitors";
        var result = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Research competitors", result.getFirst().subject());
        // When no description part, description equals subject
        assertEquals("Research competitors", result.getFirst().description());
    }

    @Test
    @DisplayName("Markdown with assignedTo= format extracts assignment")
    void markdownAssignment_equalsFormat() {
        String markdown = "- Research (assignedTo=agent-1) — find references";
        var result = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("agent-1", result.getFirst().assignedTo());
    }

    @Test
    @DisplayName("resolveAgent with null assignedTo returns null")
    void resolveAgent_nullAssignedTo() {
        assertNull(TaskListParser.resolveAgent(null, MEMBERS));
    }

    @Test
    @DisplayName("resolveAgent with blank assignedTo returns null")
    void resolveAgent_blankAssignedTo() {
        assertNull(TaskListParser.resolveAgent("  ", MEMBERS));
    }

    @Test
    @DisplayName("resolveAgent with null members returns null")
    void resolveAgent_nullMembers() {
        assertNull(TaskListParser.resolveAgent("agent-1", null));
    }

    @Test
    @DisplayName("resolveAgent with empty members returns null")
    void resolveAgent_emptyMembers() {
        assertNull(TaskListParser.resolveAgent("agent-1", List.of()));
    }

    @Test
    @DisplayName("resolveAgent matches display name with extra whitespace")
    void resolveAgent_displayNameWithWhitespace() {
        assertEquals("agent-1", TaskListParser.resolveAgent("  Analyst  ", MEMBERS));
    }

    @Test
    @DisplayName("roundRobinAssign with null members returns null")
    void roundRobinAssign_nullMembers() {
        assertNull(TaskListParser.roundRobinAssign(0, null));
    }

    @Test
    @DisplayName("Tier 3 fallback truncates very long text to 2000 chars")
    void tier3Fallback_truncatesLongText() {
        String longText = "A".repeat(5000);
        var result = TaskListParser.parse(longText, MEMBERS);

        assertEquals(1, result.size());
        assertEquals(2000, result.getFirst().description().length());
    }

    @Test
    @DisplayName("JSON with 'items' wrapper object is unwrapped")
    void jsonObjectWithItemsKey() {
        String json = """
                {"items":[{"subject":"Task A","description":"Do A"}]}
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Task A", result.getFirst().subject());
    }

    @Test
    @DisplayName("Markdown with plus sign bullet is parsed")
    void markdownPlusBullet() {
        String markdown = "+ Review code";
        var result = TaskListParser.parse(markdown, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Review code", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON entries with blank subject are skipped")
    void jsonBlankSubject_skipped() {
        String json = """
                [{"subject":"","description":"should be skipped"},{"subject":"Valid","description":"keep"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Valid", result.getFirst().subject());
    }

    @Test
    @DisplayName("JSON with 'details' alias for description")
    void jsonAlternativeKey_details() {
        String json = """
                [{"subject":"Task","details":"Using details key"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Using details key", result.getFirst().description());
    }

    @Test
    @DisplayName("JSON with 'instructions' alias for description")
    void jsonAlternativeKey_instructions() {
        String json = """
                [{"subject":"Task","instructions":"Using instructions key"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("Using instructions key", result.getFirst().description());
    }

    @Test
    @DisplayName("JSON with 'agent' alias for assignedTo")
    void jsonAlternativeKey_agent() {
        String json = """
                [{"subject":"Task","description":"D","agent":"agent-1"}]
                """;
        var result = TaskListParser.parse(json, MEMBERS);

        assertEquals(1, result.size());
        assertEquals("agent-1", result.getFirst().assignedTo());
    }

    @Test
    @DisplayName("resolveAgent with member having null displayName does not crash")
    void resolveAgent_memberWithNullDisplayName() {
        var membersWithNullName = List.of(
                new GroupMember("agent-1", null, 0, "ROLE"),
                new GroupMember("agent-2", "Writer", 1, "ROLE"));

        // Should still match by agentId
        assertEquals("agent-1", TaskListParser.resolveAgent("agent-1", membersWithNullName));
        // Should not crash when iterating through null displayNames
        assertEquals("agent-2", TaskListParser.resolveAgent("Writer", membersWithNullName));
        // Should return null for unmatched
        assertNull(TaskListParser.resolveAgent("unknown", membersWithNullName));
    }
}
