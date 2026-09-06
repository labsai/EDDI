/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.ExtensionSourceData;
import ai.labs.eddi.backup.IResourceSource.WorkflowSourceData;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * What the upgrade path does with an archive that references an extension
 * config it does not carry — the shape EDDI's own selective export produces.
 * <p>
 * Skipping the reference rather than failing is what lets a sync run from a
 * partial archive at all: the matcher then reports nothing for that extension
 * and the target's own copy is left alone. Failing here would make every
 * selective export un-syncable.
 */
@DisplayName("ZipResourceSource — extensions the archive does not carry")
class ZipResourceSourceMissingExtensionTest {

    private static final String WORKFLOW_ID = "aabbccddeeff112233445566";
    private static final String PRESENT_LLM_ID = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String MISSING_LLM_ID = "cccccccccccccccccccccccc";

    private IJsonSerialization jsonSerialization;
    private Path tempDir;
    private Path versionDir;

    @BeforeEach
    void setUp() throws Exception {
        jsonSerialization = mock(IJsonSerialization.class);
        tempDir = Files.createTempDirectory("zrs-missing-ext");
        versionDir = Files.createDirectories(
                tempDir.resolve("agentDir").resolve(WORKFLOW_ID).resolve("1"));
        Files.writeString(tempDir.resolve("agent1.agent.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(versionDir.resolve(WORKFLOW_ID + ".workflow.json"), "{}", StandardCharsets.UTF_8);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create(
                "eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        }
    }

    /**
     * The reference the archive cannot satisfy contributes nothing, and — the part
     * that matters — the one beside it is still read. An early failure would have
     * cost the whole workflow's extensions, not just the absent one.
     */
    @Test
    @DisplayName("a referenced config the archive lacks is skipped, its neighbour is still read")
    void missingExtensionIsSkippedWithoutLosingTheOthers() throws Exception {
        Files.writeString(versionDir.resolve(PRESENT_LLM_ID + ".langchain.json"),
                "{\"model\":\"gpt-4\"}", StandardCharsets.UTF_8);
        stubWorkflow(llmStep(PRESENT_LLM_ID), llmStep(MISSING_LLM_ID));

        var source = new ZipResourceSource(tempDir, jsonSerialization);
        List<WorkflowSourceData> workflows = source.readWorkflows();

        assertEquals(1, workflows.size());
        Map<String, ExtensionSourceData> extensions = workflows.getFirst().extensions();
        assertEquals(1, extensions.size(), "only the readable extension may appear, got " + extensions.keySet());
        var present = extensions.get("eddi://ai.labs.llm#0/config");
        assertNotNull(present, "the present extension must keep its canonical key, got " + extensions.keySet());
        assertEquals("{\"model\":\"gpt-4\"}", present.contentJson());
    }

    /**
     * A path that exists but cannot be read as a file — a directory where a config
     * belongs, which is what a mis-packed ZIP produces — must be reported as an
     * unreadable extension, not propagated as an IOException that kills the sync.
     */
    @Test
    @DisplayName("a referenced config that cannot be read is skipped rather than thrown")
    void unreadableExtensionIsSkipped() throws Exception {
        // A directory in the place of the config file: Files.exists says yes, reading
        // it does not.
        Files.createDirectories(versionDir.resolve(PRESENT_LLM_ID + ".langchain.json"));
        stubWorkflow(llmStep(PRESENT_LLM_ID));

        var source = new ZipResourceSource(tempDir, jsonSerialization);
        var workflows = source.readWorkflows();

        assertEquals(1, workflows.size());
        assertTrue(workflows.getFirst().extensions().isEmpty(),
                "an unreadable config must contribute nothing: " + workflows.getFirst().extensions().keySet());
    }

    private void stubWorkflow(WorkflowConfiguration.WorkflowStep... steps) throws Exception {
        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(new ArrayList<>(List.of(steps)));
        doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(WorkflowConfiguration.class));
    }

    private static WorkflowConfiguration.WorkflowStep llmStep(String llmId) {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setConfig(new HashMap<>(Map.of("uri",
                "eddi://ai.labs.llm/llmstore/llms/" + llmId + "?version=1")));
        step.setExtensions(new HashMap<>());
        return step;
    }
}
