/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.llm.rest.RestLlmStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.llm.impl.LlmConfigSaveValidator;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The write-path table of the save-time validators: which writes of an LLM
 * config or a workflow refuse an invalid document (interactive saves) and which
 * only warn (the write of a configuration that exists already). The binding
 * rule:
 * <ul>
 * <li><b>STRICT</b> — REST create/update/patch, MCP create/update_resource, the
 * cascade-save ({@code AgentCascadeService}, MCP
 * {@code apply_agent_changes});</li>
 * <li><b>LENIENT</b> (inside {@link LenientConfigWrite}, in this process) —
 * import (create, merge, rollback), sync and upgrade, every duplicate (incl.
 * the agent deep copy, which reaches the REST beans in-process through
 * {@code ResourceClientLibrary}), the API-agent re-sync's rewrite of the stored
 * LLM config and workflow;</li>
 * <li><b>VALID BY CONSTRUCTION</b> — agent setup ({@code /agentsetup}, MCP
 * {@code setup_agent}/{@code create_api_agent}, the Operator bootstrap): it
 * writes through loopback HTTP clients the thread-bound marker cannot reach, so
 * what it generates must pass the strict checks
 * ({@code ConfigSaveValidatorRegressionNetTest}).</li>
 * </ul>
 * No request header or parameter makes a save lenient.
 * <p>
 * The inventory below fails when a new class starts writing LLM configs or
 * workflows: it has to be classified here first.
 */
@DisplayName("Config write paths — strict or lenient, per the table")
class ConfigWritePathPolicyTest {

    enum Mode {
        STRICT, LENIENT, VALID_BY_CONSTRUCTION,
        /** Calls a REST bean method that is itself lenient (a duplicate). */
        DELEGATES_TO_LENIENT_ENDPOINT
    }

    private static final Map<String, Mode> TABLE = new TreeMap<>(Map.of(
            "backup/impl/RestImportService.java", Mode.LENIENT,
            "backup/impl/UpgradeExecutor.java", Mode.LENIENT,
            "configs/agents/AgentCascadeService.java", Mode.STRICT,
            "configs/agents/rest/RestAgentStore.java", Mode.LENIENT,
            "engine/mcp/McpAdminTools.java", Mode.STRICT,
            "engine/runtime/client/configuration/ResourceClientLibrary.java", Mode.DELEGATES_TO_LENIENT_ENDPOINT,
            "engine/setup/AgentSetupService.java", Mode.VALID_BY_CONSTRUCTION,
            "engine/setup/ApiAgentSyncService.java", Mode.LENIENT));

    private static final Pattern WRITES = Pattern.compile(
            "(\\.|::)(createLlm|updateLlm|patchLlm|duplicateLlm|createWorkflow|updateWorkflow|duplicateWorkflow)\\b|(ILlmStore|IWorkflowStore)\\.class");

    private static final Path MAIN = Path.of("src", "main", "java", "ai", "labs", "eddi");

    @Test
    @DisplayName("every class that writes LLM configs or workflows is in the table")
    void inventoryMatchesTheTable() throws IOException {
        Set<String> writers = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (WRITES.matcher(Files.readString(file)).find()) {
                    writers.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(TABLE.keySet(), writers, "a class writes LLM configs or workflows and is not classified in the write-path table");
    }

    @Test
    @DisplayName("lenient paths enter LenientConfigWrite; strict and setup paths do not")
    void sourcesFollowTheTable() throws IOException {
        for (var entry : TABLE.entrySet()) {
            String source = Files.readString(MAIN.resolve(entry.getKey()));
            boolean marked = source.contains("LenientConfigWrite.call(");
            switch (entry.getValue()) {
                case LENIENT -> assertTrue(marked, entry.getKey() + " must write inside LenientConfigWrite.call");
                case STRICT, VALID_BY_CONSTRUCTION -> assertFalse(marked, entry.getKey() + " must stay strict");
                case DELEGATES_TO_LENIENT_ENDPOINT -> assertFalse(marked, entry.getKey());
            }
        }
        // The REST endpoints themselves: create/update/patch strict, duplicate lenient.
        for (String endpoint : List.of("configs/llm/rest/RestLlmStore.java", "configs/workflows/rest/RestWorkflowStore.java")) {
            String source = Files.readString(MAIN.resolve(endpoint));
            assertTrue(Pattern.compile("public Response duplicate\\w+\\([^)]*\\)\\s*\\{[^}]*LenientConfigWrite\\.call\\(", Pattern.DOTALL)
                    .matcher(source).find(), endpoint + ": the duplicate endpoint must be lenient");
        }
    }

    private ILlmStore store;
    private RestLlmStore rest;

    private static LlmConfiguration invalid() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("not-a-provider");
        task.setParameters(Map.of());
        return new LlmConfiguration(List.of(task));
    }

    @BeforeEach
    void setUp() throws Exception {
        // A store that runs the real save-time validator, as LlmStore does.
        store = mock(ILlmStore.class);
        IResourceStore.IResourceId created = mock(IResourceStore.IResourceId.class);
        when(created.getId()).thenReturn("000000000000000000000009");
        when(created.getVersion()).thenReturn(1);
        when(store.create(any())).thenAnswer(call -> {
            LlmConfigSaveValidator.validate(call.getArgument(0));
            return created;
        });
        when(store.update(anyString(), anyInt(), any())).thenAnswer(call -> {
            LlmConfigSaveValidator.validate(call.getArgument(2));
            return 2;
        });
        when(store.read(anyString(), anyInt())).thenReturn(invalid());
        rest = new RestLlmStore(store, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class), mock(ResourceAccessGuard.class));
    }

    @Test
    @DisplayName("REST create and update of an invalid LLM config are refused (400)")
    void interactiveSavesAreStrict() {
        assertThrows(IllegalArgumentException.class, () -> rest.createLlm(invalid()));
        assertThrows(IllegalArgumentException.class, () -> rest.updateLlm("000000000000000000000009", 1, invalid()));
    }

    @Test
    @DisplayName("duplicating a stored LLM config that no longer passes still works")
    void duplicateIsLenient() {
        assertDoesNotThrow(() -> rest.duplicateLlm("000000000000000000000009", 1));
    }
}
