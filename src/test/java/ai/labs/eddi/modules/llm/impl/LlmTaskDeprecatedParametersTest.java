/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code includeFirstAgentMessage} is deprecated, and saying so must not cost
 * {@link LlmTask} its statelessness.
 * <p>
 * The flag exists to satisfy an Anthropic rule that no longer applies: the
 * Messages API no longer documents a first-message role requirement, and an
 * assistant-first history is accepted. It keeps working — agent behaviour lives
 * in stored JSON, and silently ignoring a parameter an author set deliberately
 * would start sending a greeting they chose to withhold, with no diagnostic —
 * but a config carrying it should say so.
 * <p>
 * The first version of this warned from {@code execute} and remembered which
 * task ids it had already warned about, in a field on the task. That was wrong
 * twice: an {@code ILifecycleTask} is an application-scoped singleton shared by
 * every conversation and MUST be stateless (AGENTS.md §4.1 rule 2), and keying
 * the memo on the task id meant two tasks that both omit an id collapsed to one
 * key, so the second never warned at all. The warning now happens once per
 * configuration load, from {@code configure}, with nothing remembered.
 */
@DisplayName("LlmTask deprecated parameters")
class LlmTaskDeprecatedParametersTest {

    private static final String KEY = "includeFirstAgentMessage";

    private static LlmConfiguration.Task task(String id, Map<String, String> parameters) {
        var task = new LlmConfiguration.Task();
        task.setId(id);
        task.setParameters(parameters);
        return task;
    }

    /**
     * The point of the rewrite: no mutable instance state may creep back in.
     * Asserted structurally, because a field is exactly what a future "warn only
     * once" change would reach for, and no behavioural test would notice it until
     * two conversations interfered with each other in production.
     */
    @Test
    @DisplayName("LlmTask keeps no mutable state for the warning")
    void taskStaysStateless() {
        for (Field field : LlmTask.class.getDeclaredFields()) {
            assertTrue(field.getName().toLowerCase().contains("warn") == false
                    || java.lang.reflect.Modifier.isStatic(field.getModifiers()),
                    "an ILifecycleTask is a singleton shared by every conversation and must be stateless "
                            + "(AGENTS.md §4.1 rule 2); found instance field '" + field.getName() + "'");
        }
    }

    @Test
    @DisplayName("a configuration that sets the parameter is reported")
    void warnsForAConfiguredTask() {
        var config = new LlmConfiguration(List.of(task("answer", Map.of(KEY, "false"))));

        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(config));
    }

    /**
     * The id-collision the instance field caused. Both tasks omit an id, so a memo
     * keyed on the id would warn once and swallow the second — which is the one a
     * reader would be least likely to find by hand.
     */
    @Test
    @DisplayName("two tasks that both omit an id are each visited")
    void visitsEveryTaskEvenWithoutIds() {
        var config = new LlmConfiguration(List.of(
                task(null, Map.of(KEY, "false")),
                task(null, Map.of(KEY, "true"))));

        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(config));
        assertEquals(2, config.tasks().size(), "both tasks are in the document and both are walked");
    }

    @Test
    @DisplayName("a configuration that does not set it is left alone")
    void silentWhenAbsent() {
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(
                new LlmConfiguration(List.of(task("answer", Map.of())))));
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(
                new LlmConfiguration(List.of(task("answer", Map.of(KEY, ""))))));
    }

    /**
     * A malformed document must not take down configuration loading: this runs
     * inside {@code configure}, so a thrown NPE here is a workflow that will not
     * deploy — an advisory note breaking the thing it is advising about.
     */
    @Test
    @DisplayName("a null, empty or ragged configuration is tolerated")
    void tolerantOfMalformedConfigurations() {
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(null));
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(new LlmConfiguration(null)));
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(new LlmConfiguration(List.of())));

        var ragged = new java.util.ArrayList<LlmConfiguration.Task>();
        ragged.add(null);
        ragged.add(task("answer", null));
        ragged.add(task("answer", Map.of(KEY, "false")));
        assertDoesNotThrow(() -> LlmTask.warnOnDeprecatedParameters(new LlmConfiguration(ragged)));
    }
}
