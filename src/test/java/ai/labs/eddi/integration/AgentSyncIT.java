/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Promotion between instances, end to end, against the real stores.
 * <p>
 * The unit suite for {@code backup} is large and mocks every store, and that is
 * exactly why four defects shipped together: each one lived in the seam between
 * the executor and something it does not own — the historized descriptor store,
 * the JAX-RS response filter, the deployment's own reader. Mocks answered
 * whatever the test asked them to, and every mocked target happened to sit at
 * version 1, the one version the broken version-resolution answered correctly.
 * <p>
 * This test runs the promotion an operator actually performs, repeatedly:
 *
 * <ol>
 * <li>a first promotion of an agent the target does not have</li>
 * <li>a promotion of a change onto that agent</li>
 * <li><b>another</b> promotion of another change — the run that used to fail
 * for good, with "the store did not accept the update"</li>
 * <li>a promotion with nothing to carry, which must write nothing</li>
 * <li>deploying what was promoted, which is the whole point of promoting
 * it</li>
 * </ol>
 *
 * The source instance is this instance: the sync endpoints read it over real
 * HTTP, through {@code RemoteApiResourceSource}, exactly as they would read a
 * separate deployment. That needs {@code allow-private-targets}, which is what
 * a self-hosted deployment on one network needs too — see
 * {@link SyncTestProfile}.
 */
@QuarkusTest
@TestProfile(AgentSyncIT.SyncTestProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Agent Sync — promotion between instances")
public class AgentSyncIT extends BaseIntegrationIT {

    /** Where the sync endpoints read the "other" instance from. */
    private static final String SOURCE_URL = "http://localhost:8081";

    private String sourceAgentId;
    private String sourceBehaviorId;
    private String targetAgentId;

    public static class SyncTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(new IntegrationTestProfile().getConfigOverrides());
            // Loopback is a private address, so the shipped policy refuses it. A
            // deployment whose instances are on one internal network turns exactly
            // this on — see docs/agent-sync-guide.md.
            overrides.put("eddi.backup.sync.allow-private-targets", "true");
            overrides.put("eddi.backup.sync.require-https", "false");
            return overrides;
        }
    }

    // ==================== 1. First promotion ====================

    @Test
    @Order(1)
    @DisplayName("an agent the target does not have is created, not refused")
    void firstPromotionCreatesTheAgent() throws Exception {
        createSourceAgent();

        // No targetAgentId: "create new". This used to answer 500 and leave the
        // workflow it had already written behind as an orphan.
        Response response = sync(null);
        response.then().statusCode(201);

        targetAgentId = agentIdOf(response.jsonPath().getString("agentUri"));
        assertNotNull(targetAgentId, "the create must name the agent it created");
        assertNotEquals(sourceAgentId, targetAgentId,
                "a created agent gets its own id — the source's id belongs to the source instance");
        assertTrue(response.jsonPath().getInt("created") > 0, "a create writes documents");
        assertEquals(0, response.jsonPath().getList("failures").size());

        // The provenance that lets a later sync find this agent again.
        given().get("/agentstore/agents/descriptors?index=0&limit=100")
                .then().statusCode(200)
                .body("find { it.resource.contains('" + targetAgentId + "') }.originId", equalTo(sourceAgentId));
    }

    // ==================== 2 & 3. Repeated promotion ====================

    @Test
    @Order(2)
    @DisplayName("a change is carried onto the existing agent")
    void secondPromotionUpdates() throws Exception {
        changeSourceBehavior("second");

        Response response = sync(targetAgentId);

        response.then().statusCode(201);
        assertEquals(0, response.jsonPath().getList("failures").size(),
                "nothing should have failed: " + response.jsonPath().getList("failures"));
        assertTrue(response.jsonPath().getInt("updated") > 0, "the changed resource must be written");
    }

    @Test
    @Order(3)
    @DisplayName("and so is the next one — the run that used to fail for good")
    void thirdPromotionAlsoUpdates() throws Exception {
        // This is the regression. Version resolution fell back to 1 whatever the
        // target was actually at, so from here on every sync diffed against the
        // pre-sync content and then wrote against a version the store had already
        // moved past: 207 Multi-Status, "the store did not accept the update",
        // nothing written, for ever.
        changeSourceBehavior("third");

        Response response = sync(targetAgentId);

        response.then().statusCode(201);
        assertEquals(0, response.jsonPath().getList("failures").size(),
                "a second update must be accepted too, got: " + response.jsonPath().getList("failures"));

        // And the preview agrees about where the target is, rather than reporting
        // the version it started at.
        Response preview = given().post("/backup/import/sync/preview?sourceUrl=" + SOURCE_URL
                + "&sourceAgentId=" + sourceAgentId + "&targetAgentId=" + targetAgentId);
        preview.then().statusCode(200);
        Integer behaviorTargetVersion = preview.jsonPath()
                .getInt("resources.find { it.resourceType == 'behavior' }.targetVersion");
        assertTrue(behaviorTargetVersion != null && behaviorTargetVersion > 1,
                "the preview must read the target's current version, got " + behaviorTargetVersion);
    }

    // ==================== 4. Nothing to do ====================

    @Test
    @Order(4)
    @DisplayName("a promotion with nothing to carry writes nothing and burns no version")
    void identicalPromotionWritesNothing() {
        Response response = sync(targetAgentId);

        response.then().statusCode(200);
        assertEquals(0, response.jsonPath().getInt("updated"));
        assertEquals(0, response.jsonPath().getInt("created"));
        assertEquals(false, response.jsonPath().getBoolean("agentUpdated"));
        assertEquals(0, response.jsonPath().getList("failures").size());
    }

    // ==================== 5. The point of all this ====================

    @Test
    @Order(5)
    @DisplayName("what was promoted can be deployed")
    void promotedAgentDeploys() throws Exception {
        int version = currentVersionOf(targetAgentId);

        // Before the descriptors were kept in step, this failed with "Resource not
        // found" for a workflow that was demonstrably in the database: the
        // deployment reads the DESCRIPTOR for the version it was asked for.
        deployAgent(targetAgentId, version);

        given().get("/administration/production/deploymentstatus/" + targetAgentId + "?version=" + version + "&format=text")
                .then().statusCode(200).body(containsString("READY"));

        undeployAgentQuietly(targetAgentId, version);
    }

    // ==================== 6. The source policy ====================

    @Test
    @Order(6)
    @DisplayName("a source address the policy refuses is a 400 that names the setting")
    void refusedSourceIsActionable() {
        // Not in the allow-list and, with require-https on for this one call's
        // sake, not something this profile permits either. The message has to say
        // which setting would allow it — "Internal Server Error" is where every
        // internal-network deployment got stuck.
        Response response = given().get("/backup/import/sync/agents?sourceUrl=ftp://example.com");

        response.then().statusCode(400);
        assertTrue(response.body().asString().contains("HTTP"),
                "the refusal should explain itself, got: " + response.body().asString());
    }

    // ==================== Fixtures ====================

    private Response sync(String targetId) {
        String url = "/backup/import/sync?sourceUrl=" + SOURCE_URL + "&sourceAgentId=" + sourceAgentId
                + (targetId != null ? "&targetAgentId=" + targetId : "");
        return given().post(url);
    }

    /**
     * A minimal but complete agent: one behavior rule set, one output set, one
     * workflow wiring them, and the agent. Deliberately not the parser-heavy
     * fixture the engine tests use — this test is about moving documents, and the
     * behavior set is the one whose content it edits.
     */
    private void createSourceAgent() {
        sourceBehaviorId = idOf(create("""
                {"behaviorGroups":[{"name":"main","behaviorRules":[
                  {"name":"greeting","actions":["greet"],"conditions":[]}]}]}""",
                "/rulestore/rulesets"));
        String outputUri = create("""
                {"outputSet":[{"action":"greet","timesOccurred":0,"outputs":[
                  {"type":"text","valueAlternatives":["Hello"]}]}]}""",
                "/outputstore/outputsets");

        String workflowUri = create(String.format("""
                {"workflowSteps":[
                  {"type":"eddi://ai.labs.behavior","config":{"uri":"%s"}},
                  {"type":"eddi://ai.labs.output","config":{"uri":"%s"}}
                ]}""", behaviorUri(), outputUri), "/workflowstore/workflows");

        sourceAgentId = idOf(create(String.format("""
                {"workflows":["%s"]}""", workflowUri), "/agentstore/agents"));
    }

    /**
     * Rewrites the source behavior set and moves the workflow and agent onto it.
     */
    private void changeSourceBehavior(String marker) {
        int behaviorVersion = currentVersionOf(sourceBehaviorId);
        given().body(String.format("""
                {"behaviorGroups":[{"name":"main","behaviorRules":[
                  {"name":"greeting_%s","actions":["greet"],"conditions":[]}]}]}""", marker))
                .contentType(ContentType.JSON)
                .put("/rulestore/rulesets/" + sourceBehaviorId + VERSION_STRING + behaviorVersion)
                .then().statusCode(200);

        // The agent runs what its workflow points at, so the reference has to move
        // with the content — the same two writes the Manager makes on an edit.
        int agentVersion = currentVersionOf(sourceAgentId);
        String workflowUri = given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + agentVersion)
                .jsonPath().getString("workflows[0]");
        String workflowId = agentIdOf(workflowUri);
        int workflowVersion = versionOf(workflowUri);

        String workflow = given().get("/workflowstore/workflows/" + workflowId + VERSION_STRING + workflowVersion)
                .body().asString()
                .replace(behaviorUri(behaviorVersion), behaviorUri(behaviorVersion + 1));
        given().body(workflow).contentType(ContentType.JSON)
                .put("/workflowstore/workflows/" + workflowId + VERSION_STRING + workflowVersion)
                .then().statusCode(200);

        given().body(String.format("""
                {"workflows":["%s"]}""",
                workflowUri.replace(VERSION_STRING + workflowVersion, VERSION_STRING + (workflowVersion + 1))))
                .contentType(ContentType.JSON)
                .put("/agentstore/agents/" + sourceAgentId + VERSION_STRING + agentVersion)
                .then().statusCode(200);
    }

    private String behaviorUri() {
        return behaviorUri(1);
    }

    private String behaviorUri(int version) {
        return "eddi://ai.labs.rules/rulestore/rulesets/" + sourceBehaviorId + VERSION_STRING + version;
    }

    private String create(String body, String path) {
        Response response = given().body(body).contentType(ContentType.JSON).post(path);
        response.then().statusCode(201);
        return response.getHeader("location");
    }

    /** The version a resource is currently at, per its descriptor. */
    private int currentVersionOf(String resourceId) {
        String resource = given().get("/agentstore/agents/descriptors?index=0&limit=100")
                .jsonPath().getString("find { it.resource.contains('" + resourceId + "') }.resource");
        if (resource == null) {
            // Not an agent — ask the store that owns it via its own descriptor listing.
            resource = given().get("/rulestore/rulesets/descriptors?index=0&limit=100")
                    .jsonPath().getString("find { it.resource.contains('" + resourceId + "') }.resource");
        }
        assertNotNull(resource, "no descriptor for " + resourceId);
        return versionOf(resource);
    }

    private static String idOf(String uri) {
        return agentIdOf(uri);
    }

    private static String agentIdOf(String uri) {
        if (uri == null) {
            return null;
        }
        String withoutQuery = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        return withoutQuery.substring(withoutQuery.lastIndexOf('/') + 1);
    }

    private static int versionOf(String uri) {
        return Integer.parseInt(uri.substring(uri.lastIndexOf('=') + 1));
    }
}
