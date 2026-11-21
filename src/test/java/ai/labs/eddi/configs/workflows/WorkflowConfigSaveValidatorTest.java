/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.LenientConfigWrite;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorkflowConfigSaveValidator")
class WorkflowConfigSaveValidatorTest {

    private static final Predicate<String> KNOWN = Set.of("ai.labs.parser", "ai.labs.behavior", "ai.labs.llm", "ai.labs.httpcalls",
            "ai.labs.output", "ai.labs.property", "ai.labs.rag", "ai.labs.templating")::contains;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkflowConfiguration read(String steps) throws Exception {
        return mapper.readValue("{\"workflowSteps\":[" + steps + "]}", WorkflowConfiguration.class);
    }

    private List<String> problems(String steps) throws Exception {
        return WorkflowConfigSaveValidator.findProblems(read(steps), KNOWN);
    }

    @Test
    @DisplayName("an unknown step type is refused (UnrecognizedExtensionException at deploy), naming the step")
    void unknownStepType() throws Exception {
        List<String> problems = problems("{\"type\":\"eddi://ai.labs.nope\",\"config\":{}}");
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("workflowSteps[0].type 'eddi://ai.labs.nope' is not a known workflow step"));
    }

    @Test
    @DisplayName("a step without a type is refused (deploy dereferences it); another scheme is skipped at deploy and passes")
    void missingOrForeignType() throws Exception {
        assertTrue(problems("{\"config\":{}}").getFirst().contains("type is required"));
        assertTrue(problems("{\"type\":\"http://ai.labs.llm\",\"config\":{}}").isEmpty());
    }

    @Test
    @DisplayName("a step whose task needs a resource must name one; a malformed reference is refused")
    void configUri() throws Exception {
        assertTrue(problems("{\"type\":\"eddi://ai.labs.llm\",\"config\":{}}").getFirst()
                .startsWith("workflowSteps[0].config.uri is required for ai.labs.llm"));
        assertTrue(problems("{\"type\":\"eddi://ai.labs.rag\",\"config\":{}}").getFirst()
                .startsWith("workflowSteps[0].config.uri is required for ai.labs.rag"), "RagTask.configure throws too");
        assertTrue(problems("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"not a uri\"}}").getFirst().contains("is not a valid URI"));
        assertTrue(problems("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"eddi://ai.labs.llm\"}}").getFirst()
                .contains("does not name a resource"));
    }

    @Test
    @DisplayName("M5: an output step without a uri is a context-only output step and passes; a blank uri is refused")
    void contextOnlyOutputStep() throws Exception {
        assertTrue(problems("{\"type\":\"eddi://ai.labs.output\",\"config\":{}}").isEmpty());
        assertTrue(problems("{\"type\":\"eddi://ai.labs.output\"}").isEmpty());
        assertTrue(problems("{\"type\":\"eddi://ai.labs.output\",\"config\":{\"uri\":\" \"}}").getFirst()
                .startsWith("workflowSteps[0].config.uri is required for ai.labs.output"));
    }

    @Test
    @DisplayName("M5: a uri with surrounding whitespace passes — ResourceUris.require trims it")
    void uriIsTrimmed() throws Exception {
        assertTrue(problems("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                + "\" eddi://ai.labs.llm/llmstore/llms/000000000000000000000000?version=1 \"}}").isEmpty());
    }

    @Test
    @DisplayName("a step whose task does not read config.uri is not checked for it")
    void unusedUriIsNotChecked() throws Exception {
        assertTrue(problems("{\"type\":\"eddi://ai.labs.templating\",\"config\":{\"uri\":\"whatever\"}}").isEmpty());
    }

    @Test
    @DisplayName("a reference to a resource that does not exist yet is fine: the config may be created next")
    void danglingReferenceIsNotChecked() throws Exception {
        assertTrue(problems("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                + "\"eddi://ai.labs.llm/llmstore/llms/000000000000000000000000?version=1\"}}").isEmpty());
    }

    @Test
    @DisplayName("two steps of one type are legal: the engine keys components by position")
    void duplicateStepsAreAllowed() throws Exception {
        String step = "{\"type\":\"eddi://ai.labs.httpcalls\",\"config\":{\"uri\":"
                + "\"eddi://ai.labs.apicalls/apicallstore/apicalls/000000000000000000000001?version=1\"}}";
        assertTrue(problems(step + "," + step).isEmpty());
    }

    @Test
    @DisplayName("without a registry (no container) only the shape is checked")
    void shapeOnlyWithoutRegistry() throws Exception {
        assertTrue(WorkflowConfigSaveValidator.findProblems(read("{\"type\":\"eddi://ai.labs.whatever\",\"config\":{}}"), null).isEmpty());
    }

    @Test
    @DisplayName("interactive save refuses with a 400-style exception; inside LenientConfigWrite it only logs")
    void strictVersusLenient() throws Exception {
        // No CDI container in a unit test: the type cannot be resolved, so use a shape
        // error.
        WorkflowConfiguration shape = read("{\"config\":{}}");
        assertThrows(IllegalArgumentException.class, () -> WorkflowConfigSaveValidator.validate(shape));
        assertDoesNotThrow(() -> LenientConfigWrite.call(() -> {
            WorkflowConfigSaveValidator.validate(shape);
            return null;
        }));
        assertEquals(1, WorkflowConfigSaveValidator.problems(shape).size());
        assertTrue(WorkflowConfigSaveValidator.problems(shape).getFirst().startsWith("workflow: "));
    }

    @Test
    @DisplayName("the shipped reference workflow (written by an earlier release) passes unchanged")
    void shippedFixturePasses() throws Exception {
        Path fixture = Path.of("docs", "agent-configs", "rule-based-reference", "aaa000000000000000000001", "1",
                "aaa000000000000000000001.workflow.json");
        WorkflowConfiguration stored = mapper.readValue(Files.readString(fixture), WorkflowConfiguration.class);
        Predicate<String> everything = host -> true;
        assertTrue(WorkflowConfigSaveValidator.findProblems(stored, everything).isEmpty(),
                WorkflowConfigSaveValidator.findProblems(stored, everything).toString());
    }
}
