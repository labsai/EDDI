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
}
