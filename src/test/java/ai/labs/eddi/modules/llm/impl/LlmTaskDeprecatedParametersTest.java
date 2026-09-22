/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code includeFirstAgentMessage} is deprecated, and the deprecation has to be
 * audible exactly once per configured task.
 * <p>
 * It exists to satisfy an Anthropic rule that no longer applies: the Messages
 * API no longer documents a first-message role requirement, and an
 * assistant-first history is accepted. The flag keeps working — agent behaviour
 * lives in stored JSON, and silently ignoring a parameter an author set
 * deliberately would start sending a greeting they chose to withhold, with no
 * diagnostic — but a config carrying it should say so.
 * <p>
 * Once per task, not once per turn: an LLM task runs on every message of every
 * conversation, so a per-turn WARN would be a log flood that operators learn to
 * filter out, which is the same as not warning at all.
 */
@DisplayName("LlmTask deprecated parameters")
class LlmTaskDeprecatedParametersTest {

    private static final String KEY = "includeFirstAgentMessage";

    /** Invokes the private warn helper and reports whether it logged. */
    private static boolean warnedFor(LlmTask task, String taskId, Map<String, String> params) throws Exception {
        Method method = LlmTask.class.getDeclaredMethod(
                "warnIfIncludeFirstAgentMessageIsSet", Map.class, LlmConfiguration.Task.class);
        method.setAccessible(true);

        var configTask = new LlmConfiguration.Task();
        configTask.setId(taskId);

        Set<String> warned = warnedSet(task);
        int before = warned.size();
        method.invoke(task, params, configTask);
        return warned.size() > before;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> warnedSet(LlmTask task) throws Exception {
        var field = LlmTask.class.getDeclaredField("includeFirstAgentMessageWarned");
        field.setAccessible(true);
        return (Set<String>) field.get(task);
    }

    /**
     * The warn helper touches no collaborator, so every dependency is null. The
     * 21-argument constructor is what CDI injects; calling it with nulls is cheaper
     * and more honest here than 21 mocks that are never exercised.
     */
    private static LlmTask newTask() {
        return new LlmTask(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("a task that sets the parameter is warned about, once")
    void warnsOncePerTask() throws Exception {
        LlmTask task = newTask();

        assertTrue(warnedFor(task, "answer", Map.of(KEY, "false")),
                "a deprecated parameter must not be silent");
        assertFalse(warnedFor(task, "answer", Map.of(KEY, "false")),
                "an LLM task runs every turn; a per-turn WARN is a flood operators learn to filter out");
    }

    @Test
    @DisplayName("setting it to true is deprecated too — the flag is, not the value")
    void warnsForTrueAsWell() throws Exception {
        assertTrue(warnedFor(newTask(), "answer", Map.of(KEY, "true")));
    }

    @Test
    @DisplayName("a task that does not set it is never warned about")
    void silentWhenAbsent() throws Exception {
        LlmTask task = newTask();

        assertFalse(warnedFor(task, "answer", Map.of()));
        assertFalse(warnedFor(task, "answer", Map.of(KEY, "")),
                "an empty value is not a setting");
        assertEquals(0, warnedSet(task).size());
    }

    @Test
    @DisplayName("each configured task gets its own warning")
    void warnsPerTaskId() throws Exception {
        LlmTask task = newTask();

        assertTrue(warnedFor(task, "answer", Map.of(KEY, "false")));
        assertTrue(warnedFor(task, "summarize", Map.of(KEY, "false")),
                "a second task carrying the same mistake is a second thing to fix");
        assertEquals(Set.of("answer", "summarize"), warnedSet(task));
    }

    @Test
    @DisplayName("a task with no id is attributed to 'default' rather than skipped")
    void nullTaskIdIsNamedDefault() throws Exception {
        LlmTask task = newTask();

        assertTrue(warnedFor(task, null, Map.of(KEY, "false")));
        assertEquals(Set.of("default"), warnedSet(task));
    }
}
