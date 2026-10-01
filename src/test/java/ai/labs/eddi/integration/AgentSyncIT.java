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
import java.util.function.UnaryOperator;

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

    /**
     * Where the source's documents are, tracked rather than looked up: every write
     * below bumps a version by exactly one, and reading it back through the very
     * descriptors this change repairs would make the fixture depend on the thing
     * under test.
     */
    private int sourceAgentVersion = 1;
    private int sourceBehaviorVersion = 1;

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
        int behaviorTargetVersion = preview.jsonPath()
                .getInt("resources.find { it.resourceType == 'behavior' }.targetVersion");
        assertTrue(behaviorTargetVersion > 1,
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

    // ==================== 7. Repeating a sync never duplicates
    // ====================

    @Test
    @Order(7)
    @DisplayName("a sync that names no target finds the agent it promoted, instead of creating another")
    void syncWithoutTargetFindsThePromotedAgent() {
        Response response = sync(null);

        // "No target means create" made a copy on every call that did not remember
        // the id the first one created — every CI job, every re-run.
        response.then().statusCode(200);
        assertEquals(targetAgentId, agentIdOf(response.jsonPath().getString("agentUri")));
        assertEquals(1, agentsPromotedFrom(sourceAgentId), "exactly one local copy of the source agent");
    }

    // ==================== 8. A step added on the source ====================

    @Test
    @Order(8)
    @DisplayName("a step added on the source is created on the target, with no credential of the source's")
    void addedStepIsPromotedWithoutTheSourcesSecret() throws Exception {
        String apiCallsUri = create("""
                {"targetServerUrl":"https://api.example.com","httpCalls":[{"name":"lookup","actions":["never"],
                  "request":{"path":"/l","method":"get","headers":{"Authorization":"Bearer plaintext-source-token"}}}]}""",
                "/apicallstore/apicalls");
        addSourceStep("{\"type\":\"eddi://ai.labs.httpcalls\",\"config\":{\"uri\":\"" + apiCallsUri + "\"}}");

        Response preview = given().post("/backup/import/sync/preview?sourceUrl=" + SOURCE_URL
                + "&sourceAgentId=" + sourceAgentId + "&targetAgentId=" + targetAgentId);
        preview.then().statusCode(200);
        assertTrue(!preview.body().asString().contains("plaintext-source-token"),
                "the source's credential must not reach the preview: " + preview.body().asString());

        // This used to be refused with 207, telling the operator to hand-edit the
        // target — adding a step on the source could not be promoted at all.
        Response response = sync(targetAgentId);
        response.then().statusCode(201);
        assertEquals(0, response.jsonPath().getList("failures").size(), response.body().asString());

        String targetApiCalls = targetExtensionUri("eddi://ai.labs.httpcalls");
        assertNotNull(targetApiCalls, "the target workflow must have the step");
        assertNotEquals(idOf(apiCallsUri), idOf(targetApiCalls), "the step must name the target's own copy");
        String header = given().get("/apicallstore/apicalls/" + idOf(targetApiCalls) + VERSION_STRING + versionOf(targetApiCalls))
                .jsonPath().getString("httpCalls[0].request.headers.Authorization");
        assertTrue(!header.contains("plaintext-source-token"), "the source's credential must not be written: " + header);

        int version = currentVersionOf(targetAgentId);
        deployAgent(targetAgentId, version);
        given().get("/administration/production/deploymentstatus/" + targetAgentId + "?version=" + version + "&format=text")
                .then().statusCode(200).body(containsString("READY"));
        undeployAgentQuietly(targetAgentId, version);
    }

    // ==================== 9. The target's own secrets stay ====================

    @Test
    @Order(9)
    @DisplayName("the target keeps its own vault reference when the source changes the same config")
    void targetKeepsItsOwnVaultReference() {
        // The operator points the target at its own vault entry...
        String targetApiCalls = targetExtensionUri("eddi://ai.labs.httpcalls");
        String withOwnSecret = given().get("/apicallstore/apicalls/" + idOf(targetApiCalls) + VERSION_STRING + versionOf(targetApiCalls))
                .body().asString().replace("${vault:REDACTED}", "Bearer ${vault:target-token}");
        String written = given().body(withOwnSecret).contentType(ContentType.JSON)
                .put("/apicallstore/apicalls/" + idOf(targetApiCalls) + VERSION_STRING + versionOf(targetApiCalls))
                .then().statusCode(200).extract().header("location");
        repointTargetStep(targetApiCalls, written);

        // ...and the source then changes something else in the same config.
        String sourceApiCalls = sourceExtensionUri("eddi://ai.labs.httpcalls");
        String changed = given().get("/apicallstore/apicalls/" + idOf(sourceApiCalls) + VERSION_STRING + versionOf(sourceApiCalls))
                .body().asString().replace("\"/l\"", "\"/lookup-v2\"");
        String sourceWritten = given().body(changed).contentType(ContentType.JSON)
                .put("/apicallstore/apicalls/" + idOf(sourceApiCalls) + VERSION_STRING + versionOf(sourceApiCalls))
                .then().statusCode(200).extract().header("location");
        repointSourceStep(sourceApiCalls, sourceWritten);

        Response response = sync(targetAgentId);
        response.then().statusCode(201);
        assertEquals(0, response.jsonPath().getList("failures").size(),
                "setting the target's own secret is not a conflict: " + response.body().asString());

        String now = targetExtensionUri("eddi://ai.labs.httpcalls");
        var request = given().get("/apicallstore/apicalls/" + idOf(now) + VERSION_STRING + versionOf(now)).jsonPath();
        assertEquals("/lookup-v2", request.getString("httpCalls[0].request.path"), "the source's change arrives");
        assertEquals("Bearer ${vault:target-token}", request.getString("httpCalls[0].request.headers.Authorization"),
                "the target's own vault reference stays");
    }

    // ==================== 10. A workflow added on the source ====================

    @Test
    @Order(10)
    @DisplayName("a workflow added on the source arrives with its own resources and deploys")
    void addedWorkflowDeploys() throws Exception {
        String outputUri = create("""
                {"outputSet":[{"action":"faq","timesOccurred":0,"outputs":[
                  {"valueAlternatives":[{"type":"text","text":"FAQ"}]}]}]}""", "/outputstore/outputsets");
        String workflowUri = create(String.format("""
                {"workflowSteps":[{"type":"eddi://ai.labs.output","config":{"uri":"%s"}}]}""", outputUri),
                "/workflowstore/workflows");
        String mainWorkflow = given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion)
                .jsonPath().getString("workflows[0]");
        String agent = given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion).body().asString()
                .replace("\"" + mainWorkflow + "\"", "\"" + mainWorkflow + "\",\"" + workflowUri + "\"");
        given().body(agent).contentType(ContentType.JSON)
                .put("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion)
                .then().statusCode(200);
        sourceAgentVersion++;

        // It used to be stored as the source had it — naming resource ids only the
        // source has — and the sync answered 201 for an agent that could not deploy.
        Response response = sync(targetAgentId);
        response.then().statusCode(201);
        assertEquals(0, response.jsonPath().getList("failures").size(), response.body().asString());

        int version = currentVersionOf(targetAgentId);
        var workflows = given().get("/agentstore/agents/" + targetAgentId + VERSION_STRING + version)
                .jsonPath().getList("workflows", String.class);
        assertEquals(2, workflows.size());
        String added = given().get("/workflowstore/workflows/" + idOf(workflows.get(1)) + VERSION_STRING + versionOf(workflows.get(1)))
                .body().asString();
        assertTrue(!added.contains(idOf(outputUri)), "the added workflow must name the target's own output: " + added);

        deployAgent(targetAgentId, version);
        given().get("/administration/production/deploymentstatus/" + targetAgentId + "?version=" + version + "&format=text")
                .then().statusCode(200).body(containsString("READY"));
        undeployAgentQuietly(targetAgentId, version);
    }

    // ==================== 11. A hotfix on the target ====================

    @Test
    @Order(11)
    @DisplayName("a change made on the target is not overwritten unless named")
    void localChangeIsAConflict() {
        // The target is hotfixed...
        String targetRules = targetExtensionUri("eddi://ai.labs.behavior");
        String hotfixed = given().body(behaviorDocument("hotfix")).contentType(ContentType.JSON)
                .put("/rulestore/rulesets/" + idOf(targetRules) + VERSION_STRING + versionOf(targetRules))
                .then().statusCode(200).extract().header("location");
        repointTargetStep(targetRules, hotfixed);
        // ...and the source changes the same rule set.
        changeSourceBehavior("fourth");

        Response preview = given().post("/backup/import/sync/preview?sourceUrl=" + SOURCE_URL
                + "&sourceAgentId=" + sourceAgentId + "&targetAgentId=" + targetAgentId);
        assertEquals("CONFLICT", preview.jsonPath().getString("resources.find { it.resourceType == 'behavior' }.action"));

        Response response = sync(targetAgentId);
        response.then().statusCode(207);
        String now = targetExtensionUri("eddi://ai.labs.behavior");
        assertTrue(given().get("/rulestore/rulesets/" + idOf(now) + VERSION_STRING + versionOf(now)).body().asString()
                .contains("hotfix"), "the hotfix must survive a sync of everything");
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
        sourceBehaviorId = idOf(create(behaviorDocument("greeting"), "/rulestore/rulesets"));
        String outputUri = create("""
                {"outputSet":[{"action":"greet","timesOccurred":0,"outputs":[
                  {"valueAlternatives":[{"type":"text","text":"Hello"}]}]}]}""",
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
        int behaviorVersion = sourceBehaviorVersion;
        given().body(behaviorDocument("greeting_" + marker))
                .contentType(ContentType.JSON)
                .put("/rulestore/rulesets/" + sourceBehaviorId + VERSION_STRING + behaviorVersion)
                .then().statusCode(200);
        sourceBehaviorVersion++;

        // The agent runs what its workflow points at, so the reference has to move
        // with the content — the same two writes the Manager makes on an edit.
        int agentVersion = sourceAgentVersion;
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

        // The agent document edited in place, so a workflow added to it later is not
        // dropped by a change to the first one.
        given().body(given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + agentVersion).body().asString()
                .replace(workflowUri, workflowUri.replace(VERSION_STRING + workflowVersion, VERSION_STRING + (workflowVersion + 1))))
                .contentType(ContentType.JSON)
                .put("/agentstore/agents/" + sourceAgentId + VERSION_STRING + agentVersion)
                .then().statusCode(200);
        sourceAgentVersion++;
    }

    /**
     * A rule set naming one rule. The occurrence condition is the fixture the
     * engine tests use — a rule with no condition at all is not what this test is
     * about, and not what an agent looks like.
     */
    private static String behaviorDocument(String ruleName) {
        return String.format("""
                {"behaviorGroups":[{"name":"main","behaviorRules":[
                  {"name":"%s","actions":["greet"],"conditions":[
                    {"type":"occurrence","configs":{"maxTimesOccurred":"0","behaviorRuleName":"%s"}}]}]}]}""",
                ruleName, ruleName);
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

    /**
     * The version the TARGET agent is at, per its descriptor — read deliberately
     * rather than tracked. The descriptor is what a deployment resolves, and a sync
     * that writes a version its descriptor does not name is exactly the failure
     * this test exists to catch.
     */
    private int currentVersionOf(String agentId) {
        String resource = given().get("/agentstore/agents/descriptors?index=0&limit=100")
                .jsonPath().getString("find { it.resource.contains('" + agentId + "') }.resource");
        assertNotNull(resource, "no descriptor for agent " + agentId);
        return versionOf(resource);
    }

    /** How many live local agents record {@code sourceId} as their origin. */
    private long agentsPromotedFrom(String sourceId) {
        return given().get("/agentstore/agents/descriptors?index=0&limit=100")
                .jsonPath().getList("findAll { it.originId == '" + sourceId + "' && !it.deleted }").size();
    }

    /**
     * The URI the source's first workflow names for the first step of this type.
     */
    private String sourceExtensionUri(String stepType) {
        return extensionUri(sourceAgentId, sourceAgentVersion, stepType);
    }

    private String targetExtensionUri(String stepType) {
        return extensionUri(targetAgentId, currentVersionOf(targetAgentId), stepType);
    }

    private String extensionUri(String agentId, int agentVersion, String stepType) {
        String workflowUri = given().get("/agentstore/agents/" + agentId + VERSION_STRING + agentVersion)
                .jsonPath().getString("workflows[0]");
        return given().get("/workflowstore/workflows/" + idOf(workflowUri) + VERSION_STRING + versionOf(workflowUri))
                .jsonPath().getString("workflowSteps.find { it.type == '" + stepType + "' }.config.uri");
    }

    /**
     * Appends a step to the source's first workflow and moves the agent onto it.
     */
    private void addSourceStep(String stepJson) {
        editSourceWorkflow(workflow -> workflow.replaceFirst("\\]\\s*}\\s*$", "," + stepJson + "]}"));
    }

    private void repointSourceStep(String from, String to) {
        editSourceWorkflow(workflow -> workflow.replace(from, to));
    }

    private void editSourceWorkflow(UnaryOperator<String> edit) {
        String workflowUri = given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion)
                .jsonPath().getString("workflows[0]");
        String workflow = given().get("/workflowstore/workflows/" + idOf(workflowUri) + VERSION_STRING + versionOf(workflowUri))
                .body().asString();
        String written = given().body(edit.apply(workflow)).contentType(ContentType.JSON)
                .put("/workflowstore/workflows/" + idOf(workflowUri) + VERSION_STRING + versionOf(workflowUri))
                .then().statusCode(200).extract().header("location");
        String agent = given().get("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion).body().asString()
                .replace(workflowUri, written);
        given().body(agent).contentType(ContentType.JSON)
                .put("/agentstore/agents/" + sourceAgentId + VERSION_STRING + sourceAgentVersion)
                .then().statusCode(200);
        sourceAgentVersion++;
    }

    /**
     * Moves the target's first workflow — and the agent — onto a hand-edited
     * resource, as the Manager does.
     */
    private void repointTargetStep(String from, String to) {
        int agentVersion = currentVersionOf(targetAgentId);
        String workflowUri = given().get("/agentstore/agents/" + targetAgentId + VERSION_STRING + agentVersion)
                .jsonPath().getString("workflows[0]");
        String workflow = given().get("/workflowstore/workflows/" + idOf(workflowUri) + VERSION_STRING + versionOf(workflowUri))
                .body().asString().replace(from, to);
        String written = given().body(workflow).contentType(ContentType.JSON)
                .put("/workflowstore/workflows/" + idOf(workflowUri) + VERSION_STRING + versionOf(workflowUri))
                .then().statusCode(200).extract().header("location");
        String agent = given().get("/agentstore/agents/" + targetAgentId + VERSION_STRING + agentVersion).body().asString()
                .replace(workflowUri, written);
        given().body(agent).contentType(ContentType.JSON)
                .put("/agentstore/agents/" + targetAgentId + VERSION_STRING + agentVersion)
                .then().statusCode(200);
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

    /**
     * The version a resource URI ends with.
     * <p>
     * A URI that carries none is a failure of this test's own fixtures, and saying
     * so beats an unexplained {@link NumberFormatException} from inside an
     * assertion helper.
     */
    private static int versionOf(String uri) {
        assertNotNull(uri, "no resource URI to read a version from");
        int marker = uri.lastIndexOf('=');
        assertTrue(marker >= 0 && marker < uri.length() - 1, "resource URI carries no version: " + uri);
        String version = uri.substring(marker + 1);
        try {
            return Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("resource URI ends in '" + version + "', which is not a version: " + uri, e);
        }
    }
}
