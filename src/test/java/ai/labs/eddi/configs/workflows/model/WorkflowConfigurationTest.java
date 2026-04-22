package ai.labs.eddi.configs.workflows.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowConfigurationTest {

    // ==================== Basic construction ====================

    @Test
    void defaultConstructor_emptySteps() {
        var config = new WorkflowConfiguration();
        assertNotNull(config.getWorkflowSteps());
        assertTrue(config.getWorkflowSteps().isEmpty());
    }

    @Test
    void setAndGetWorkflowSteps() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setExtensions(Map.of("uri", "/llmstore/llms/abc?version=1"));
        step.setConfig(Map.of("actions", "llm_call"));

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of(step));

        assertEquals(1, config.getWorkflowSteps().size());
        assertEquals(URI.create("eddi://ai.labs.llm"), config.getWorkflowSteps().get(0).getType());
    }

    // ==================== WorkflowStep ====================

    @Test
    void workflowStep_defaultsEmpty() {
        var step = new WorkflowConfiguration.WorkflowStep();
        assertNull(step.getType());
        assertNotNull(step.getExtensions());
        assertTrue(step.getExtensions().isEmpty());
        assertNotNull(step.getConfig());
        assertTrue(step.getConfig().isEmpty());
    }

    @Test
    void workflowStep_setType() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.dictionary"));
        assertEquals("eddi://ai.labs.dictionary", step.getType().toString());
    }

    @Test
    void workflowStep_setExtensions() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setExtensions(Map.of("uri", "/dictionarystore/dicts/123?version=1"));
        assertEquals("/dictionarystore/dicts/123?version=1", step.getExtensions().get("uri"));
    }

    @Test
    void workflowStep_setConfig() {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setConfig(Map.of("actions", "greeting", "timeout", 30));
        assertEquals("greeting", step.getConfig().get("actions"));
        assertEquals(30, step.getConfig().get("timeout"));
    }

    // ==================== Jackson deserialization ====================

    @Test
    void jackson_workflowExtensionsAlias() throws Exception {
        // The setter has @JsonAlias("workflowExtensions") for backward compatibility
        var json = """
                {
                    "workflowExtensions": [
                        {
                            "type": "eddi://ai.labs.llm",
                            "extensions": {"uri": "/llm/abc?version=1"},
                            "config": {}
                        }
                    ]
                }
                """;

        var mapper = new ObjectMapper();
        var config = mapper.readValue(json, WorkflowConfiguration.class);

        assertEquals(1, config.getWorkflowSteps().size());
        assertEquals(URI.create("eddi://ai.labs.llm"), config.getWorkflowSteps().get(0).getType());
    }

    @Test
    void jackson_workflowSteps_standardName() throws Exception {
        var json = """
                {
                    "workflowSteps": [
                        {
                            "type": "eddi://ai.labs.dictionary",
                            "extensions": {},
                            "config": {"actions": "parse"}
                        }
                    ]
                }
                """;

        var mapper = new ObjectMapper();
        var config = mapper.readValue(json, WorkflowConfiguration.class);

        assertEquals(1, config.getWorkflowSteps().size());
        assertEquals("parse", config.getWorkflowSteps().get(0).getConfig().get("actions"));
    }

    @Test
    void jackson_emptyJson() throws Exception {
        var mapper = new ObjectMapper();
        var config = mapper.readValue("{}", WorkflowConfiguration.class);

        assertNotNull(config.getWorkflowSteps());
        assertTrue(config.getWorkflowSteps().isEmpty());
    }

    @Test
    void jackson_roundTrip() throws Exception {
        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.rules"));
        step.setExtensions(Map.of("uri", "/rulesstore/rulesets/xyz?version=2"));
        step.setConfig(Map.of("actions", "evaluate"));

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of(step));

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(config);
        var deserialized = mapper.readValue(json, WorkflowConfiguration.class);

        assertEquals(1, deserialized.getWorkflowSteps().size());
        assertEquals("eddi://ai.labs.rules", deserialized.getWorkflowSteps().get(0).getType().toString());
    }

    // ==================== Multiple steps ====================

    @Test
    void multipleWorkflowSteps() {
        var step1 = new WorkflowConfiguration.WorkflowStep();
        step1.setType(URI.create("eddi://ai.labs.dictionary"));

        var step2 = new WorkflowConfiguration.WorkflowStep();
        step2.setType(URI.create("eddi://ai.labs.rules"));

        var step3 = new WorkflowConfiguration.WorkflowStep();
        step3.setType(URI.create("eddi://ai.labs.llm"));

        var config = new WorkflowConfiguration();
        config.setWorkflowSteps(List.of(step1, step2, step3));

        assertEquals(3, config.getWorkflowSteps().size());
        assertEquals("eddi://ai.labs.dictionary", config.getWorkflowSteps().get(0).getType().toString());
        assertEquals("eddi://ai.labs.rules", config.getWorkflowSteps().get(1).getType().toString());
        assertEquals("eddi://ai.labs.llm", config.getWorkflowSteps().get(2).getType().toString());
    }
}
