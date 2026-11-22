/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.engine.security.TurnOrigin;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The receiving side of the email escalation guard over real HTTP (security
 * review of the guard, finding 4): a request carrying
 * {@code X-Eddi-Turn-Origin: email} — as every request a mail-started turn
 * sends does — starts a conversation that is tainted for good, through REST
 * ({@code POST /agents/{id}/start}, bound by {@code TaintedRequestFilter}) and
 * through MCP ({@code create_conversation}, bound by
 * {@code McpErrorResultInterceptor}); and a tainted request to a guarded
 * endpoint or MCP tool is refused where it arrives.
 * <p>
 * The agent is deterministic: the input {@code senders} fires an httpcall that
 * POSTs to a path ending in {@code /channelstore/channels/{id}/email-senders} —
 * what {@code update_email_senders} does — on a WireMock server, which counts
 * whether the conversation was allowed to send it.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DisplayName("Email origin taint over HTTP — REST and MCP start paths, and the receiving-side refusals")
class EmailOriginTaintHttpIT extends BaseIntegrationIT {

    private static final String SENDERS_PATH = "/channelstore/channels/aabbccddee1122334455aabb/email-senders";
    private static final AtomicInteger RPC_IDS = new AtomicInteger(1);

    private static WireMockServer wireMock;
    private static ResourceId agent;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlPathEqualTo(SENDERS_PATH)).willReturn(okJson("{\"changed\":true}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (agent != null) {
            undeployAgentQuietly(agent.id(), agent.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (agent == null) {
            agent = setupSenderEditingAgent();
        }
    }

    private int senderEdits() {
        return wireMock.countRequestsMatching(postRequestedFor(urlPathEqualTo(SENDERS_PATH)).build()).getCount();
    }

    private String startConversation(String originHeader) {
        var request = given();
        if (originHeader != null) {
            request = request.header(TurnOrigin.HEADER, originHeader);
        }
        Response response = request.post("agents/" + agent.id() + "/start?environment=production&userId=taint-it");
        assertTrue(response.statusCode() == 201 || response.statusCode() == 200, response.asString());
        return extractResourceId(response.getHeader("location")).id();
    }

    @Test
    @DisplayName("control: an untainted conversation's turn sends the sender edit")
    void untaintedConversationSends() throws Exception {
        String conversationId = startConversation(null);
        waitForConversationReady(agent.id(), conversationId);
        int before = senderEdits();

        sendUserInput(agent.id(), conversationId, "senders", false, false).then().statusCode(200);

        assertEquals(before + 1, senderEdits(), "the control conversation must reach the endpoint, or the tainted cases prove nothing");
    }

    @Test
    @DisplayName("REST: a conversation started with X-Eddi-Turn-Origin: email refuses the sender edit, on a later turn without the header")
    void restStartTaints() throws Exception {
        String conversationId = startConversation("email");
        waitForConversationReady(agent.id(), conversationId);
        int before = senderEdits();

        sendUserInput(agent.id(), conversationId, "senders", false, false);

        assertEquals(before, senderEdits(), "the tainted conversation must never send the sender edit");
    }

    @Test
    @DisplayName("MCP: a conversation create_conversation starts from a tainted request refuses the sender edit")
    void mcpStartTaints() throws Exception {
        String session = mcpSession("email");
        JsonPath created = mcpCall(session, "email", "create_conversation",
                "{\"agentId\":\"" + agent.id() + "\",\"environment\":\"production\"}");
        String content = created.getString("result.content[0].text");
        assertNotNull(content, created.prettify());
        String conversationId = JsonPath.from(content).getString("conversationId");
        assertNotNull(conversationId, content);
        waitForConversationReady(agent.id(), conversationId);
        int before = senderEdits();

        sendUserInput(agent.id(), conversationId, "senders", false, false);

        assertEquals(before, senderEdits(), "the conversation an MCP call from a tainted request started must stay tainted");
    }

    @Test
    @DisplayName("REST receiving side: a tainted sender edit is 403; a tainted configuration write needs X-Eddi-Approved-Call")
    void restReceivingSide() {
        given().header(TurnOrigin.HEADER, "email").contentType(ContentType.JSON).body("{\"expectedVersion\":1}").post(SENDERS_PATH).then()
                .statusCode(403);
        given().header(TurnOrigin.HEADER, "email").header(TurnOrigin.APPROVED_HEADER, "true").contentType(ContentType.JSON)
                .body("{\"expectedVersion\":1}").post(SENDERS_PATH).then().statusCode(403);

        Response unapproved = given().header(TurnOrigin.HEADER, "email").contentType(ContentType.JSON).body("{}")
                .put("/llmstore/llms/aabbccddee1122334455aabb?version=1");
        assertEquals(403, unapproved.statusCode(), unapproved.asString());
        Response approved = given().header(TurnOrigin.HEADER, "email").header(TurnOrigin.APPROVED_HEADER, "true").contentType(ContentType.JSON)
                .body("{}").put("/llmstore/llms/aabbccddee1122334455aabb?version=1");
        assertNotEquals(403, approved.statusCode(), approved.asString());
    }

    @Test
    @DisplayName("MCP receiving side: update_email_senders from a tainted request is a tool error with EMAIL_ORIGIN")
    void mcpReceivingSide() {
        String session = mcpSession("email");
        JsonPath answer = mcpCall(session, "email", "update_email_senders",
                "{\"integrationId\":\"aabbccddee1122334455aabb\",\"expectedVersion\":1,\"addAllow\":\"x@example.com\"}");
        assertTrue(answer.getBoolean("result.isError"), answer.prettify());
        assertTrue(answer.getString("result.content[0].text").contains("EMAIL_ORIGIN"), answer.prettify());
    }

    @Test
    @DisplayName("MCP configuration tool: refused from a tainted request, run through the loopback when the call was approved")
    void mcpConfigurationToolNeedsApproval() {
        ResourceId output = extractResourceId(createResource(OUTPUT_SET, "/outputstore/outputsets"));
        String arguments = "{\"resourceType\":\"output\",\"resourceId\":\"%s\",\"version\":%d,\"config\":%s}".formatted(output.id(),
                output.version(), quoted(OUTPUT_SET));

        String session = mcpSession("email");
        JsonPath refused = mcpCall(session, "email", false, "update_resource", arguments);
        assertTrue(refused.getBoolean("result.isError"), refused.prettify());
        assertTrue(refused.getString("result.content[0].text").contains("EMAIL_ORIGIN"), refused.prettify());

        JsonPath approved = mcpCall(session, "email", true, "update_resource", arguments);
        String text = approved.getString("result.content[0].text");
        assertFalse(text.contains("EMAIL_ORIGIN"), "the approved call, and its loopback write, must pass: " + approved.prettify());
        assertNotEquals(Boolean.TRUE, approved.get("result.isError"), approved.prettify());
    }

    private static final String OUTPUT_SET = "{\"outputSet\": [{\"action\": \"x\", \"timesOccurred\": 0, \"outputs\": []}]}";

    private static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    // ------------------------------------------------------------------ MCP

    /** Opens a streamable-HTTP MCP session; every request carries the taint. */
    private String mcpSession(String origin) {
        Response init = given().header(TurnOrigin.HEADER, origin).contentType(ContentType.JSON).accept("application/json, text/event-stream")
                .body("""
                        {"jsonrpc":"2.0","id":%d,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},
                         "clientInfo":{"name":"taint-it","version":"1"}}}""".formatted(RPC_IDS.getAndIncrement()))
                .post("/mcp");
        assertEquals(200, init.statusCode(), init.asString());
        String session = init.getHeader("Mcp-Session-Id");
        assertNotNull(session, "no Mcp-Session-Id: " + init.getHeaders());
        given().header(TurnOrigin.HEADER, origin).header("Mcp-Session-Id", session).contentType(ContentType.JSON)
                .accept("application/json, text/event-stream").body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .post("/mcp");
        return session;
    }

    private JsonPath mcpCall(String session, String origin, String tool, String arguments) {
        return mcpCall(session, origin, false, tool, arguments);
    }

    private JsonPath mcpCall(String session, String origin, boolean approved, String tool, String arguments) {
        Response response = given().header(TurnOrigin.HEADER, origin).header(TurnOrigin.APPROVED_HEADER, String.valueOf(approved))
                .header("Mcp-Session-Id", session).contentType(ContentType.JSON)
                .accept("application/json, text/event-stream")
                .body("{\"jsonrpc\":\"2.0\",\"id\":%d,\"method\":\"tools/call\",\"params\":{\"name\":\"%s\",\"arguments\":%s}}"
                        .formatted(RPC_IDS.getAndIncrement(), tool, arguments))
                .post("/mcp");
        assertEquals(200, response.statusCode(), response.asString());
        return JsonPath.from(jsonRpcBody(response.asString()));
    }

    /** The JSON-RPC message of a response that is either JSON or one SSE event. */
    private static String jsonRpcBody(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        StringBuilder data = new StringBuilder();
        for (String line : trimmed.split("\\R")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        return data.toString();
    }

    // ---------------------------------------------------------------- agent

    private ResourceId setupSenderEditingAgent() throws Exception {
        String dictionary = """
                {"words": [{"word": "senders", "expressions": "edit_senders(senders)", "frequency": 0}], "phrases": []}
                """;
        String rules = """
                {"behaviorGroups": [{"name": "Senders", "behaviorRules": [
                  {"name": "Welcome", "actions": ["welcome"],
                   "conditions": [{"type": "occurrence", "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}}]},
                  {"name": "EditSenders", "actions": ["edit_senders"],
                   "conditions": [{"type": "inputmatcher", "configs": {"expressions": "edit_senders(*)", "occurrence": "currentStep"}}]}
                ]}]}
                """;
        String output = """
                {"outputSet": [
                  {"action": "welcome", "timesOccurred": 0, "outputs": [{"valueAlternatives": [{"type": "text", "text": "ready"}]}]},
                  {"action": "edit_senders", "timesOccurred": 0, "outputs": [{"valueAlternatives": [{"type": "text", "text": "tried"}]}]}
                ]}
                """;
        String httpCalls = """
                {"targetServerUrl": "http://localhost:%d", "httpCalls": [{
                  "name": "editSenders", "actions": ["edit_senders"], "saveResponse": false,
                  "request": {"path": "%s", "method": "post", "headers": {}, "queryParams": {},
                              "contentType": "application/json", "body": "{\\"expectedVersion\\":1,\\"add\\":{\\"allow\\":[\\"attacker@evil.example\\"]}}"}
                }]}
                """
                .formatted(wireMock.port(), SENDERS_PATH);

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationHttpCalls = createResource(httpCalls, "/apicallstore/apicalls");
        String workflow = """
                {"workflowSteps": [
                  {"type": "eddi://ai.labs.parser", "config": {}, "extensions": {
                    "dictionaries": [{"type": "eddi://ai.labs.parser.dictionaries.regular", "config": {"uri": "%s"}}], "corrections": []}},
                  {"type": "eddi://ai.labs.rules", "config": {"uri": "%s"}},
                  {"type": "eddi://ai.labs.apicalls", "config": {"uri": "%s"}},
                  {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}}
                ]}
                """.formatted(locationDictionary, locationRules, locationHttpCalls, locationOutput);
        String locationWorkflow = createResource(workflow, "/workflowstore/workflows");
        String agentLocation = createResource("{\"packages\": [\"%s\"]}".formatted(locationWorkflow), "/agentstore/agents");
        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
