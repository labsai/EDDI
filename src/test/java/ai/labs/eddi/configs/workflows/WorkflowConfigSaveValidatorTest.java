/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.validation.ConfigValidationPolicy;
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
            "ai.labs.output", "ai.labs.property")::contains;

    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private WorkflowConfiguration read(String steps) throws Exception {
        return mapper.readValue("{\"workflowSteps\":[" + steps + "]}", WorkflowConfiguration.class);
    }

    @Test
    @DisplayName("an unknown step type is refused, naming the step (L11)")
    void unknownStepType() throws Exception {
        List<String> problems = WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.nope\",\"config\":{}}"), KNOWN);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("workflowSteps[0].type 'eddi://ai.labs.nope' is not a known workflow step"));
    }

    @Test
    @DisplayName("a step without a type, or with a non-eddi scheme, is refused")
    void missingOrForeignType() throws Exception {
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"config\":{}}"), KNOWN).getFirst().contains("type is required"));
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"http://ai.labs.llm\",\"config\":{}}"), KNOWN).getFirst()
                .contains("must be an eddi://"));
    }

    @Test
    @DisplayName("a step that needs a resource must name one; a malformed reference is refused")
    void configUri() throws Exception {
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.llm\",\"config\":{}}"), KNOWN).getFirst()
                .startsWith("workflowSteps[0].config.uri is required for ai.labs.llm"));
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"not a uri\"}}"), KNOWN)
                .getFirst().contains("is not a valid URI"));
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":\"eddi://ai.labs.llm\"}}"),
                KNOWN).getFirst().contains("does not name a resource"));
    }

    @Test
    @DisplayName("a reference to a resource that does not exist yet is fine: the config may be created next")
    void danglingReferenceIsNotChecked() throws Exception {
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.llm\",\"config\":{\"uri\":"
                + "\"eddi://ai.labs.llm/llmstore/llms/000000000000000000000000?version=1\"}}"), KNOWN).isEmpty());
    }

    @Test
    @DisplayName("two steps of one type are legal: the engine keys components by position")
    void duplicateStepsAreAllowed() throws Exception {
        String step = "{\"type\":\"eddi://ai.labs.httpcalls\",\"config\":{\"uri\":"
                + "\"eddi://ai.labs.apicalls/apicallstore/apicalls/000000000000000000000001?version=1\"}}";
        assertTrue(WorkflowConfigSaveValidator.problems(read(step + "," + step), KNOWN).isEmpty());
    }

    @Test
    @DisplayName("without a registry (no container) only the shape is checked")
    void shapeOnlyWithoutRegistry() throws Exception {
        assertTrue(WorkflowConfigSaveValidator.problems(read("{\"type\":\"eddi://ai.labs.whatever\",\"config\":{}}"), null).isEmpty());
    }

    @Test
    @DisplayName("interactive save refuses with 400-style exception; import only warns")
    void strictVersusLenient() throws Exception {
        WorkflowConfiguration workflow = read("{\"type\":\"eddi://ai.labs.nope\",\"config\":{}}");
        // No CDI container in a unit test: the type cannot be resolved, so use a shape
        // error.
        WorkflowConfiguration shape = read("{\"config\":{}}");
        assertThrows(IllegalArgumentException.class, () -> WorkflowConfigSaveValidator.validate(shape));
        try (var scope = ConfigValidationPolicy.lenient()) {
            assertDoesNotThrow(() -> WorkflowConfigSaveValidator.validate(shape));
            assertEquals(1, scope.warnings().size());
        }
        assertEquals(1, WorkflowConfigSaveValidator.problems(workflow, KNOWN).size());
    }

    @Test
    @DisplayName("the shipped reference workflow (written by an earlier release) passes unchanged")
    void shippedFixturePasses() throws Exception {
        Path fixture = Path.of("docs", "agent-configs", "rule-based-reference", "aaa000000000000000000001", "1",
                "aaa000000000000000000001.workflow.json");
        WorkflowConfiguration stored = mapper.readValue(Files.readString(fixture), WorkflowConfiguration.class);
        Predicate<String> everything = host -> true;
        assertTrue(WorkflowConfigSaveValidator.problems(stored, everything).isEmpty(),
                WorkflowConfigSaveValidator.problems(stored, everything).toString());
    }
}
