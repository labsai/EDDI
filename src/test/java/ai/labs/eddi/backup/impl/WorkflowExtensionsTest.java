/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link WorkflowExtensions}, the single source of truth for how a
 * workflow points at its extension configs.
 * <p>
 * Before it existed the ZIP side keyed the extension map by resource-store
 * authority ({@code ai.labs.rules}) while the target side keyed it by the
 * workflow step type URI ({@code eddi://ai.labs.behavior}), so no extension
 * could ever match and every sync reported CREATE — duplicating every LLM
 * config, ruleset and output set in the target instead of updating in place.
 */
@DisplayName("WorkflowExtensions")
class WorkflowExtensionsTest {

    private static final String LLM_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HTTP_ID_A = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HTTP_ID_B = "cccccccccccccccccccccccc";
    private static final String DICT_ID = "dddddddddddddddddddddddd";

    @Test
    @DisplayName("reads the extension URI from the step's config, where the engine reads it")
    void readsUriFromConfig() {
        var config = workflow(step("eddi://ai.labs.llm",
                Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2"),
                Map.of()));

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(config);

        assertEquals(1, refs.size());
        assertEquals(LLM_ID, refs.getFirst().resourceId().getId());
        assertEquals(2, refs.getFirst().resourceId().getVersion());
        assertEquals("langchain", refs.getFirst().fileExtension());
        assertEquals("/llmstore/llms/", refs.getFirst().type().restPath());
    }

    @Test
    @DisplayName("finds a parser's nested dictionaries, which live under extensions")
    void findsNestedDictionaries() {
        var config = workflow(step("eddi://ai.labs.parser",
                Map.of(),
                Map.of("dictionaries", List.of(Map.of(
                        "config", Map.of("uri",
                                "eddi://ai.labs.dictionary/dictionarystore/dictionaries/" + DICT_ID + "?version=1"))))));

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(config);

        assertEquals(1, refs.size());
        assertEquals(DICT_ID, refs.getFirst().resourceId().getId());
        assertEquals("regulardictionary", refs.getFirst().fileExtension());
    }

    @Test
    @DisplayName("two steps of the same type keep two distinct keys")
    void twoStepsOfSameTypeDoNotCollapse() {
        var config = workflow(
                step("eddi://ai.labs.httpcalls",
                        Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTP_ID_A + "?version=1"),
                        Map.of()),
                step("eddi://ai.labs.httpcalls",
                        Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTP_ID_B + "?version=1"),
                        Map.of()));

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(config);

        // Keying by type alone synced only the second step, and then repointed BOTH
        // steps at it — the first step's config was silently replaced.
        assertEquals(2, refs.size());
        assertNotEquals(refs.get(0).key(), refs.get(1).key());
        assertEquals(HTTP_ID_A, refs.get(0).resourceId().getId());
        assertEquals(HTTP_ID_B, refs.get(1).resourceId().getId());
    }

    @Test
    @DisplayName("the same workflow shape always produces the same keys, so source and target join")
    void keysAreStableAcrossInstances() {
        String uri = "eddi://ai.labs.rules/rulestore/rulesets/" + LLM_ID + "?version=1";
        var source = workflow(step("eddi://ai.labs.behavior", Map.of("uri", uri), Map.of()));
        var target = workflow(step("eddi://ai.labs.behavior", Map.of("uri", uri), Map.of()));

        assertEquals(WorkflowExtensions.scan(source).getFirst().key(),
                WorkflowExtensions.scan(target).getFirst().key());
    }

    @Test
    @DisplayName("repointTo rewrites config.uri and leaves extensions untouched")
    void repointToWritesIntoConfig() {
        var step = step("eddi://ai.labs.llm",
                new HashMap<>(Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=2")),
                new HashMap<>());
        var config = workflow(step);

        WorkflowExtensions.scan(config).getFirst()
                .repointTo(URI.create("eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=3"));

        // The engine hands step.getConfig() to the lifecycle task; writing the new
        // version into extensions left the deployed pipeline on the OLD one.
        assertEquals("eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=3",
                step.getConfig().get("uri"));
        assertFalse(step.getExtensions().containsKey("uri"));
    }

    @Test
    @DisplayName("null-safe: a null config, step list, step or map contributes nothing")
    void nullSafe() {
        assertTrue(WorkflowExtensions.scan(null).isEmpty());

        var noSteps = new WorkflowConfiguration();
        noSteps.setWorkflowSteps(null);
        assertTrue(WorkflowExtensions.scan(noSteps).isEmpty());

        var nullStepType = new WorkflowConfiguration.WorkflowStep();
        nullStepType.setType(null);
        nullStepType.setConfig(null);
        nullStepType.setExtensions(null);
        assertTrue(WorkflowExtensions.scan(workflow(nullStepType)).isEmpty());
    }

    @Test
    @DisplayName("a URI of an unregistered type produces no reference")
    void unknownTypeIgnored() {
        var config = workflow(step("eddi://ai.labs.something",
                Map.of("uri", "eddi://ai.labs.unknown/store/things/" + LLM_ID + "?version=1"),
                Map.of()));

        assertTrue(WorkflowExtensions.scan(config).isEmpty());
    }

    // ==================== Helpers ====================

    private static WorkflowConfiguration.WorkflowStep step(String type,
                                                           Map<String, Object> stepConfig,
                                                           Map<String, Object> extensions) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create(type));
        step.setConfig(stepConfig);
        step.setExtensions(extensions);
        return step;
    }

    private static WorkflowConfiguration workflow(WorkflowConfiguration.WorkflowStep... steps) {
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(steps)));
        return config;
    }
}
