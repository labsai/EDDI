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

    /**
     * {@code typeOf} is asked about URIs that came out of a foreign archive, so it
     * has to answer "not a resource I move" rather than throw. A null URI, and one
     * with no authority at all, are both such answers — a throw here would abort
     * the scan of an otherwise importable workflow.
     */
    @Test
    @DisplayName("typeOf answers null for a null URI and for one carrying no authority")
    void typeOfIsNullSafe() {
        assertNull(WorkflowExtensions.typeOf(null));
        assertNull(WorkflowExtensions.typeOf(URI.create("/llmstore/llms/" + LLM_ID)));
        assertNotNull(WorkflowExtensions.typeOf(URI.create("eddi://ai.labs.llm/llmstore/llms/" + LLM_ID)));
    }

    /**
     * A workflow list may legitimately hold a null entry — Jackson produces one for
     * a {@code null} element in the archived JSON. Scanning it must skip the hole
     * rather than fail the whole import with a NullPointerException.
     */
    @Test
    @DisplayName("a null entry in the step list is skipped, the steps around it are still scanned")
    void nullStepInListIsSkipped() {
        var config = new WorkflowConfiguration();
        var steps = new ArrayList<WorkflowConfiguration.WorkflowStep>();
        steps.add(null);
        steps.add(step("eddi://ai.labs.llm",
                Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1"),
                Map.of()));
        config.setWorkflowSteps(steps);

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(config);

        assertEquals(1, refs.size());
        assertEquals(LLM_ID, refs.getFirst().resourceId().getId());
    }

    /**
     * The depth guard exists so a pathological (or cyclic-looking) nested config
     * cannot make the scan recurse forever. It is a real limit, not decoration: a
     * reference buried deeper than it is simply not seen.
     */
    @Test
    @DisplayName("a reference nested deeper than the depth guard is not returned")
    void depthGuardStopsTheWalk() {
        Object nested = new HashMap<>(Map.of("uri",
                "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1"));
        for (int i = 0; i < 12; i++) {
            nested = new HashMap<>(Map.of("level" + i, nested));
        }
        @SuppressWarnings("unchecked")
        var deepConfig = (Map<String, Object>) nested;

        assertTrue(WorkflowExtensions.scan(workflow(step("eddi://ai.labs.llm", deepConfig, Map.of()))).isEmpty());
    }

    /**
     * Three shapes a {@code uri} entry can take that must all yield no reference
     * instead of an exception. Each one is reachable from a real archive: a blank
     * value from a half-written config, a non-string from a hand-edited ZIP, and a
     * space-bearing value from a name pasted into the id position.
     */
    @Test
    @DisplayName("a blank, non-string or unparseable uri value yields no reference")
    void unusableUriValuesYieldNoReference() {
        assertTrue(WorkflowExtensions.scan(workflow(
                step("eddi://ai.labs.llm", Map.of("uri", "   "), Map.of()))).isEmpty());

        assertTrue(WorkflowExtensions.scan(workflow(
                step("eddi://ai.labs.llm", Map.of("uri", 42), Map.of()))).isEmpty());

        // URI.create rejects the space, and the scan has to survive that.
        assertTrue(WorkflowExtensions.scan(workflow(
                step("eddi://ai.labs.llm",
                        Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/my llm?version=1"),
                        Map.of())))
                .isEmpty());
    }

    /**
     * {@code RestUtilities.extractResourceId} rejects a non-integer
     * {@code ?version=} with an IllegalArgumentException. A workflow carrying one
     * must contribute no reference rather than abort the scan — the other steps of
     * that workflow are still importable.
     */
    @Test
    @DisplayName("a non-integer ?version leaves the rest of the workflow scannable")
    void nonIntegerVersionYieldsNoReferenceButDoesNotAbortTheScan() {
        var config = workflow(
                step("eddi://ai.labs.llm",
                        Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=latest"),
                        Map.of()),
                step("eddi://ai.labs.httpcalls",
                        Map.of("uri", "eddi://ai.labs.apicalls/apicallstore/apicalls/" + HTTP_ID_A + "?version=1"),
                        Map.of()));

        List<WorkflowExtensions.ExtensionRef> refs = WorkflowExtensions.scan(config);

        assertEquals(1, refs.size());
        assertEquals(HTTP_ID_A, refs.getFirst().resourceId().getId());
    }

    /**
     * A URI of a known authority whose last segment is not a valid resource id
     * yields an id of null. Emitting a reference for it would send the sync to read
     * {@code /llmstore/llms/null}.
     */
    @Test
    @DisplayName("a known authority with no usable resource id yields no reference")
    void knownAuthorityWithoutIdYieldsNoReference() {
        var config = workflow(step("eddi://ai.labs.llm",
                Map.of("uri", "eddi://ai.labs.llm/llmstore/llms/nope?version=1"),
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
