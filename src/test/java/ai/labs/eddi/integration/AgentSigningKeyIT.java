/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The signing-key endpoints through the real HTTP stack, response filters
 * included.
 * <p>
 * The unit tests call {@code RestAgentStore} in process, so
 * {@code DocumentDescriptorFilter} never ran in them — and that filter is where
 * provisioning broke: generating a key answered a POST with 201, the filter
 * took it for a creation, tried to create a second descriptor for the agent,
 * and answered 500 with the agent already at the new version. Its descriptor
 * stayed behind, so every later save answered 404 and a conversation on a newer
 * version could not start. This walks the whole path: generate, save, turn
 * signing on, deploy, converse, revoke.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class AgentSigningKeyIT extends BaseIntegrationIT {

    private static final String AGENT_STORE = "/agentstore/agents/";
    private static final List<ResourceId> createdAgents = new ArrayList<>();

    @AfterAll
    static void cleanup() {
        for (ResourceId agent : createdAgents) {
            undeployAgentQuietly(agent.id(), agent.version());
        }
    }

    @Test
    @DisplayName("generate a key, save, turn signing on, deploy and converse, then revoke the key")
    void provisionSaveConverseRevoke() throws Exception {
        ResourceId agent = createMinimalAgent();
        String id = agent.id();

        // Generate: 200 (not 201) with Location at the new version.
        Response generated = given().post(AGENT_STORE + id + "/signing-keys" + VERSION_STRING + agent.version());
        generated.then().statusCode(200).header("location", endsWith(VERSION_STRING + 2));
        assertEquals(1, generated.jsonPath().getInt("version"));
        assertNotNull(generated.jsonPath().getString("publicKeyB64"));

        // The descriptor followed the agent: a save of the new version is answered,
        // not 404'd by the descriptor filter.
        Map<String, Object> config = readAgent(id, 2);
        Response saved = given().body(config).contentType(ContentType.JSON).put(AGENT_STORE + id + VERSION_STRING + 2);
        saved.then().statusCode(200).header("location", endsWith(VERSION_STRING + 3));

        // Signing on is accepted: the key is in the vault, and the identity the save
        // keeps is the stored one.
        config = readAgent(id, 3);
        config.put("security", Map.of("signInterAgentMessages", true));
        given().body(config).contentType(ContentType.JSON).put(AGENT_STORE + id + VERSION_STRING + 3)
                .then().statusCode(200).header("location", endsWith(VERSION_STRING + 4));
        createdAgents.add(new ResourceId(id, 4));

        // A conversation starts on the version reached through the key change.
        deployAgent(id, 4);
        assertNotNull(createConversation(id, "signing-key-it").id());

        // Revoke: 200, a new version recording it, and signing can no longer be kept
        // on.
        Response revoked = given().post(AGENT_STORE + id + "/signing-keys/1/revoke" + VERSION_STRING + 4);
        revoked.then().statusCode(200).header("location", endsWith(VERSION_STRING + 5));
        assertEquals(true, revoked.jsonPath().getBoolean("revoked"));
        config = readAgent(id, 5);
        given().body(config).contentType(ContentType.JSON).put(AGENT_STORE + id + VERSION_STRING + 5)
                .then().statusCode(400).body(containsString("Signing key v1 is revoked"));
    }

    private static Map<String, Object> readAgent(String id, int version) {
        JsonPath body = given().get(AGENT_STORE + id + VERSION_STRING + version).then().statusCode(200).extract().jsonPath();
        return body.getMap("");
    }

    private ResourceId createMinimalAgent() throws Exception {
        String dictionary = load("agentengine/dictionary.json");
        String behavior = load("agentengine/rules.json");
        String output = load("agentengine/output.json");

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationBehavior = createResource(behavior, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");

        String workflow = String.format("""
                {
                  "workflowSteps": [
                    {
                      "type": "eddi://ai.labs.parser",
                      "config": {},
                      "extensions": {
                        "dictionaries": [
                          {"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}
                        ],
                        "corrections": []
                      }
                    },
                    {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}}
                  ]
                }""", locationDictionary, locationBehavior, locationOutput);

        String locationWorkflow = createResource(workflow, "/workflowstore/workflows");
        String agentLocation = createResource(String.format("""
                {"workflows": ["%s"]}""", locationWorkflow), AGENT_STORE);
        return extractResourceId(agentLocation);
    }
}
