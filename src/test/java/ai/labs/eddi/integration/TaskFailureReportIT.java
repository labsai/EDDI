/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A turn whose LLM call fails comes back with the reason, on the plain and the
 * streaming path.
 * <p>
 * Before, both answered {@code conversationState: ERROR} with an output holding
 * only the actions — no reply and no reason — and the Manager rendered an empty
 * bubble. The LLM here points at a closed local port, so the failure is
 * immediate and needs neither network nor a key.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class TaskFailureReportIT extends BaseIntegrationIT {

    private static final String LLM = """
            {
              "tasks": [{
                "actions": ["send_message"],
                "id": "unreachable",
                "type": "openai",
                "retry": { "maxAttempts": 1 },
                "parameters": {
                  "apiKey": "sk-test-not-real",
                  "baseUrl": "http://127.0.0.1:1/v1",
                  "modelName": "gpt-4o-mini",
                  "timeout": "5000",
                  "systemMessage": "Be brief.",
                  "addToOutput": "true"
                }
              }]
            }
            """;

    private static final String RULES = """
            {
              "expressionsAsActions": true,
              "behaviorGroups": [{ "rules": [{
                "name": "Send Message to LLM",
                "actions": ["send_message"],
                "conditions": [{ "type": "inputmatcher", "configs": { "expressions": "*" } }]
              }]}]
            }
            """;

    private static ResourceId agent;

    /**
     * Once per class, but from {@code @BeforeEach}: a static {@code @BeforeAll}
     * runs before Quarkus has pointed RestAssured at the test port.
     */
    @BeforeEach
    void deployOnce() throws Exception {
        if (agent != null) {
            return;
        }
        String llm = createResource(LLM, "/llmstore/llms");
        String rules = createResource(RULES, "/rulestore/rulesets");
        String workflow = createResource("""
                {
                  "workflowSteps": [
                    { "type": "eddi://ai.labs.parser", "config": {}, "extensions": {} },
                    { "type": "eddi://ai.labs.behavior", "config": { "uri": "%s" }, "extensions": {} },
                    { "type": "eddi://ai.labs.llm", "config": { "uri": "%s" }, "extensions": {} }
                  ]
                }""".formatted(rules, llm), "/workflowstore/workflows");
        agent = extractResourceId(createResource("""
                { "workflows": ["%s"] }""".formatted(workflow), "/agentstore/agents"));
        deployAgent(agent.id(), agent.version());
    }

    @AfterAll
    static void cleanup() {
        if (agent != null) {
            undeployAgentQuietly(agent.id(), agent.version());
        }
    }

    @Test
    @DisplayName("the plain path returns ERROR with the failure under taskErrors")
    @SuppressWarnings("unchecked")
    void plainPathCarriesTheReason() {
        String conversationId = createConversation(agent.id(), "failure-it-user").id();

        var body = sendUserInput(agent.id(), conversationId, "hello", false, true)
                .then().statusCode(200).extract().jsonPath();

        assertEquals("ERROR", body.getString("conversationState"));
        List<Map<String, Object>> outputs = body.getList("conversationOutputs");
        var taskErrors = (List<Map<String, Object>>) outputs.getLast().get("taskErrors");
        assertFalse(taskErrors == null || taskErrors.isEmpty(), "the failed turn must say why: " + outputs);
        String text = (String) taskErrors.getFirst().get("text");
        assertTrue(text.startsWith("Task 'eddi://ai.labs.llm' failed: "), text);
        assertFalse(text.contains("127.0.0.1:1/v1"), "URLs are stripped from what leaves the server: " + text);
    }

    @Test
    @DisplayName("the streaming path's done event carries it too")
    void streamingPathCarriesTheReason() {
        String conversationId = createConversation(agent.id(), "failure-it-user").id();

        String stream = given().contentType(ContentType.JSON).accept("text/event-stream")
                .body("{\"input\":\"hello\"}")
                .post("agents/" + conversationId + "/stream")
                .then().statusCode(200).extract().asString();

        assertTrue(stream.contains("event:task_failed") || stream.contains("event: task_failed"), stream);
        String done = stream.substring(stream.lastIndexOf("done"));
        assertTrue(done.contains("\"conversationState\":\"ERROR\""), done);
        assertTrue(done.contains("taskErrors"), "done must carry the reason: " + done);
    }
}
