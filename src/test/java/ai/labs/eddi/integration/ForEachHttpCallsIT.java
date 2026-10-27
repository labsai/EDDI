/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end proof of an httpcall's {@code forEach} through the running
 * application: a deployed agent, a real conversation over REST, WireMock as the
 * external API, and the collected results read back from the stored
 * conversation.
 * <p>
 * Every scenario is one user word that triggers two calls in order: a "source"
 * call that fetches the list ({@code /<scenario>/list}) and a {@code forEach}
 * call that sends one request per element of it
 * ({@code /<scenario>/item/<id>}). Scenarios use their own URL prefix, so
 * WireMock verifications never see each other's traffic.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
public class ForEachHttpCallsIT extends BaseIntegrationIT {

    private static final String USER = "forEachUser";
    private static final String[] SCENARIOS = {"basic", "empty", "toomany", "bytes", "partial", "failmode", "timeout", "seq", "conc", "filter"};

    /** Non-ASCII payload: 21 characters, 63 bytes in UTF-8. */
    private static final String CJK = "日本語日本語日本語日本語日本語日本語日本語";

    private static WireMockServer wireMock;
    private static ResourceId agent;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void tearDown() {
        if (agent != null) {
            undeployAgentQuietly(agent.id(), agent.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void deployOnce() throws Exception {
        if (agent == null) {
            agent = deployAgentWithForEachCalls();
        }
        wireMock.resetAll();
    }

    // ==================== 1. one request per element ====================

    @Test
    @DisplayName("forEach sends exactly one templated request per element and collects every result in source order")
    void oneRequestPerElementAndCollected() throws Exception {
        listOf("basic", "a1", "a2", "a3");
        for (String id : List.of("a1", "a2", "a3")) {
            wireMock.stubFor(post(urlPathEqualTo("/basic/item/" + id)).willReturn(okJson("{\"name\":\"item-" + id + "\"}")));
        }

        String conversationId = newConversation();
        say(conversationId, "basic");

        for (String id : List.of("a1", "a2", "a3")) {
            wireMock.verify(exactly(1), postRequestedFor(urlPathEqualTo("/basic/item/" + id))
                    .withRequestBody(equalToJson("{\"sku\":\"" + id + "\"}")));
        }
        wireMock.verify(exactly(3), postRequestedFor(urlPathMatching("/basic/item/.*")));
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/basic/list")));

        // read back from the STORED conversation, not the POST response
        JsonPath stored = storedConversation(conversationId);
        List<Map<String, Object>> results = results(stored, "basicResults");
        assertEquals(3, results.size(), String.valueOf(results));
        for (int i = 0; i < 3; i++) {
            Map<String, Object> item = results.get(i);
            assertEquals(i, item.get("index"));
            assertEquals(Boolean.TRUE, item.get("ok"));
            assertEquals(200, item.get("httpCode"));
            assertEquals(1, item.get("attempts"));
            assertEquals("item-a" + (i + 1), ((Map<?, ?>) item.get("body")).get("name"), "source order, own body");
        }
        // a postResponse property sees the collected array under its name
        assertEquals("count=3", stored.getString("conversationProperties.basicCount.valueString"));
    }

    @Test
    @DisplayName("templateFilterExpression skips elements: no request for them, and their indexes leave gaps")
    void filterSkipsElements() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/filter/list")).willReturn(okJson(
                "{\"ids\":[{\"id\":\"f1\",\"active\":true},{\"id\":\"f2\",\"active\":false},{\"id\":\"f3\",\"active\":true}]}")));
        wireMock.stubFor(get(urlPathMatching("/filter/item/.*")).willReturn(okJson("{\"ok\":true}")));

        String conversationId = newConversation();
        say(conversationId, "filter");

        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/filter/item/f1")));
        wireMock.verify(exactly(0), getRequestedFor(urlPathEqualTo("/filter/item/f2")));
        wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/filter/item/f3")));
        List<Map<String, Object>> results = results(storedConversation(conversationId), "filterResults");
        assertEquals(List.of(0, 2), results.stream().map(r -> r.get("index")).toList(), String.valueOf(results));
    }

    // ==================== 2. bounds ====================

    @Test
    @DisplayName("more elements than maxItems refuses the whole call: nothing is sent and the turn reports why")
    void tooManyElementsRefusedBeforeAnythingIsSent() throws Exception {
        listOf("toomany", "t1", "t2", "t3", "t4", "t5");
        wireMock.stubFor(get(urlPathMatching("/toomany/item/.*")).willReturn(okJson("{}")));

        String conversationId = newConversation();
        JsonPath turn = say(conversationId, "toomany");

        wireMock.verify(exactly(0), getRequestedFor(urlPathMatching("/toomany/item/.*")));
        assertEquals("ERROR", turn.getString("conversationState"), "the refusal fails the turn, it does not truncate");
        assertNull(findHttpCallsValue(storedConversation(conversationId), "toomanyResults"), "no partial result is stored");
        String errors = String.valueOf(turn.getList("conversationOutputs"));
        assertTrue(errors.contains("5 requests") && errors.contains("limit of 3"), "the reason names the limit: " + errors);
    }

    @Test
    @DisplayName("maxTotalResponseSizeInBytes counts UTF-8 bytes: a non-ASCII body past the budget is dropped, its status kept")
    void totalSizeBudgetCountsUtf8Bytes() throws Exception {
        listOf("bytes", "b1", "b2", "b3");
        for (String id : List.of("b1", "b2", "b3")) {
            wireMock.stubFor(get(urlPathEqualTo("/bytes/item/" + id)).willReturn(okJson(bodyWith(CJK))));
        }

        String conversationId = newConversation();
        say(conversationId, "bytes");

        // every request is still sent: the budget only limits what is stored
        wireMock.verify(exactly(3), getRequestedFor(urlPathMatching("/bytes/item/.*")));
        List<Map<String, Object>> results = results(storedConversation(conversationId), "bytesResults");
        assertEquals(3, results.size());
        assertNotNull(results.get(0).get("body"));
        assertNotNull(results.get(1).get("body"), "two bodies fit the byte budget");
        assertNull(results.get(2).get("body"), "the third exceeds it in bytes (in characters it would have fit): " + results.get(2));
        assertEquals(Boolean.TRUE, results.get(2).get("bodyDropped"));
        assertEquals(200, results.get(2).get("httpCode"), "the status is kept");
        assertNull(results.get(1).get("bodyDropped"));
    }

    @Test
    @DisplayName("an empty list sends no request and stores an empty result")
    void emptyListSendsNothing() throws Exception {
        listOf("empty");
        wireMock.stubFor(get(urlPathMatching("/empty/item/.*")).willReturn(okJson("{}")));

        String conversationId = newConversation();
        JsonPath turn = say(conversationId, "empty");

        wireMock.verify(exactly(0), getRequestedFor(urlPathMatching("/empty/item/.*")));
        assertEquals("READY", turn.getString("conversationState"));
        List<Map<String, Object>> results = results(storedConversation(conversationId), "emptyResults");
        assertTrue(results.isEmpty(), String.valueOf(results));
    }

    // ==================== 3. partial failure ====================

    @Test
    @DisplayName("a 500 on one element is recorded; its siblings still run; postResponse sees 207")
    void failingElementDoesNotStopSiblings() throws Exception {
        listOf("partial", "p1", "p2", "p3");
        wireMock.stubFor(get(urlPathEqualTo("/partial/item/p1")).willReturn(okJson("{\"v\":1}")));
        wireMock.stubFor(get(urlPathEqualTo("/partial/item/p2")).willReturn(serverError().withBody("boom")));
        wireMock.stubFor(get(urlPathEqualTo("/partial/item/p3")).willReturn(okJson("{\"v\":3}")));

        String conversationId = newConversation();
        JsonPath turn = say(conversationId, "partial");

        for (String id : List.of("p1", "p2", "p3")) {
            wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/partial/item/" + id)));
        }
        assertEquals("READY", turn.getString("conversationState"), "onItemFailure defaults to continue");
        JsonPath stored = storedConversation(conversationId);
        List<Map<String, Object>> results = results(stored, "partialResults");
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE, Boolean.TRUE), results.stream().map(r -> r.get("ok")).toList());
        assertEquals(500, results.get(1).get("httpCode"));
        assertEquals(3, ((Map<?, ?>) results.get(2).get("body")).get("v"), "the sibling after the failure is collected");
        assertEquals("1 of 3 forEach items failed", findHttpCallsValue(stored, "partialResultsError"));
        assertEquals("only207", stored.getString("conversationProperties.partialOn207.valueString"),
                "opting in with runOnHttpCode 207 processes the partial result");
        assertNull(stored.getString("conversationProperties.partialOn200.valueString"),
                "the default-gated instruction only runs on a complete result");
    }

    @Test
    @DisplayName("onItemFailure fail fails the step only after every item ran")
    void failPolicyRunsEveryItemThenFailsTheStep() throws Exception {
        listOf("failmode", "q1", "q2", "q3");
        wireMock.stubFor(get(urlPathEqualTo("/failmode/item/q1")).willReturn(okJson("{}")));
        wireMock.stubFor(get(urlPathEqualTo("/failmode/item/q2")).willReturn(serverError()));
        wireMock.stubFor(get(urlPathEqualTo("/failmode/item/q3")).willReturn(okJson("{}")));

        String conversationId = newConversation();
        JsonPath turn = say(conversationId, "failmode");

        for (String id : List.of("q1", "q2", "q3")) {
            wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/failmode/item/" + id)));
        }
        assertEquals("ERROR", turn.getString("conversationState"));
        assertTrue(String.valueOf(turn.getList("conversationOutputs")).contains("1 of 3 forEach items failed"),
                String.valueOf(turn.getList("conversationOutputs")));
    }

    @Test
    @DisplayName("a timed-out element is marked timedOut and is never retried, although the retry policy covers 500")
    void timeoutIsRecordedAndNeverRetried() throws Exception {
        listOf("timeout", "w1", "w2", "w3");
        wireMock.stubFor(get(urlPathEqualTo("/timeout/item/w1")).willReturn(okJson("{}")));
        wireMock.stubFor(get(urlPathEqualTo("/timeout/item/w2")).willReturn(okJson("{}").withFixedDelay(5_000)));
        wireMock.stubFor(get(urlPathEqualTo("/timeout/item/w3")).willReturn(okJson("{}")));

        String conversationId = newConversation();
        say(conversationId, "timeout");

        for (String id : List.of("w1", "w2", "w3")) {
            wireMock.verify(exactly(1), getRequestedFor(urlPathEqualTo("/timeout/item/" + id)));
        }
        List<Map<String, Object>> results = results(storedConversation(conversationId), "timeoutResults");
        assertEquals(3, results.size());
        assertEquals(Boolean.TRUE, results.get(0).get("ok"));
        assertEquals(Boolean.FALSE, results.get(1).get("ok"));
        assertEquals(Boolean.TRUE, results.get(1).get("timedOut"), String.valueOf(results.get(1)));
        assertEquals(1, results.get(1).get("attempts"));
        assertEquals(Boolean.TRUE, results.get(2).get("ok"));
    }

    // ==================== 4. concurrency ====================

    @Test
    @DisplayName("default maxConcurrency is 1: the requests never overlap")
    void defaultConcurrencyIsSequential() throws Exception {
        listOf("seq", "c1", "c2", "c3");
        slowItems("seq", 700, "c1", "c2", "c3");

        say(newConversation(), "seq");

        assertEquals(3, requestsTo("/seq/item/.*").size());
        assertEquals(1, peakOverlap("/seq/item/.*", 700));
    }

    @Test
    @DisplayName("maxConcurrency 2 runs two items at once and never more")
    void concurrencyIsBoundedByMaxConcurrency() throws Exception {
        listOf("conc", "d1", "d2", "d3", "d4");
        slowItems("conc", 700, "d1", "d2", "d3", "d4");

        String conversationId = newConversation();
        say(conversationId, "conc");

        assertEquals(4, requestsTo("/conc/item/.*").size());
        assertEquals(2, peakOverlap("/conc/item/.*", 700), "two in flight, never three");
        List<Map<String, Object>> results = results(storedConversation(conversationId), "concResults");
        assertEquals(List.of(0, 1, 2, 3), results.stream().map(r -> r.get("index")).toList(), "source order whatever the finish order");
    }

    // ==================== helpers ====================

    private static void listOf(String scenario, String... ids) {
        StringBuilder list = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.length; i++) {
            list.append(i > 0 ? "," : "").append("{\"id\":\"").append(ids[i]).append("\"}");
        }
        list.append("]}");
        wireMock.stubFor(get(urlPathEqualTo("/" + scenario + "/list")).willReturn(okJson(list.toString())));
    }

    private static void slowItems(String scenario, int delayMs, String... ids) {
        for (String id : ids) {
            wireMock.stubFor(get(urlPathEqualTo("/" + scenario + "/item/" + id)).willReturn(okJson("{}").withFixedDelay(delayMs)));
        }
    }

    private static String bodyWith(String text) {
        return "{\"t\":\"" + text + "\"}";
    }

    private static List<LoggedRequest> requestsTo(String urlRegex) {
        return wireMock.findAll(getRequestedFor(urlPathMatching(urlRegex)));
    }

    /** Absorbs timestamp jitter in WireMock's logged arrival times. */
    private static final long TOLERANCE_MS = 50;

    /**
     * Most requests in flight at once, from WireMock's own arrival times. Each
     * request is assumed to be open for {@code delayMs}, so the requests in flight
     * when one arrives are those that started at or before it and less than
     * {@code delayMs} (less a tolerance for jitter) earlier.
     */
    private static int peakOverlap(String urlRegex, int delayMs) {
        List<Long> starts = new ArrayList<>();
        requestsTo(urlRegex).forEach(r -> starts.add(r.getLoggedDate().getTime()));
        int peak = 0;
        for (long start : starts) {
            int overlapping = 0;
            for (long other : starts) {
                if (other <= start && other > start - delayMs + TOLERANCE_MS) {
                    overlapping++;
                }
            }
            peak = Math.max(peak, overlapping);
        }
        return peak;
    }

    private String newConversation() throws InterruptedException {
        ResourceId conversation = createConversation(agent.id(), USER);
        waitForConversationReady(agent.id(), conversation.id());
        return conversation.id();
    }

    private JsonPath say(String conversationId, String word) {
        return sendUserInput(agent.id(), conversationId, word, true, false).then().statusCode(200).extract().jsonPath();
    }

    /**
     * The conversation as a fresh GET returns it — waiting until the turn that was
     * just said (the second output, after the welcome) has been persisted.
     */
    private JsonPath storedConversation(String conversationId) throws InterruptedException {
        JsonPath stored = null;
        for (int i = 0; i < 40; i++) {
            stored = getConversationLog(agent.id(), conversationId, true).then().statusCode(200).extract().jsonPath();
            List<?> outputs = stored.getList("conversationOutputs");
            if (outputs != null && outputs.size() >= 2) {
                return stored;
            }
            Thread.sleep(250);
        }
        return stored;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(JsonPath conversation, String name) {
        Object value = findHttpCallsValue(conversation, name);
        assertInstanceOf(List.class, value, "no '" + name + "' in the stored conversation: " + conversation.getList("conversationOutputs"));
        return (List<Map<String, Object>>) value;
    }

    /** The named entry of the last turn's {@code httpCalls} output. */
    private static Object findHttpCallsValue(JsonPath conversation, String name) {
        List<Map<String, Object>> outputs = conversation.getList("conversationOutputs");
        if (outputs == null || outputs.isEmpty()) {
            return null;
        }
        Object httpCalls = outputs.getLast().get("httpCalls");
        if (httpCalls instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (httpCalls instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> map && map.containsKey(name)) {
                    return map.get(name);
                }
            }
        }
        return null;
    }

    // ==================== agent ====================

    private ResourceId deployAgentWithForEachCalls() throws Exception {
        StringBuilder words = new StringBuilder();
        StringBuilder behavior = new StringBuilder();
        StringBuilder outputs = new StringBuilder();
        for (String s : SCENARIOS) {
            words.append("{\"word\":\"%s\",\"expressions\":\"go_%s(%s)\",\"frequency\":0},".formatted(s, s, s));
            behavior.append("""
                    ,{"name":"Go_%s","actions":["go_%s"],"conditions":[{"type":"inputmatcher",
                      "configs":{"expressions":"go_%s(*)","occurrence":"currentStep"}}]}""".formatted(s, s, s));
            outputs.append("""
                    ,{"action":"go_%s","timesOccurred":0,"outputs":[{"valueAlternatives":[{"type":"text","text":"done %s"}]}]}"""
                    .formatted(s, s));
        }
        String dictionary = "{\"words\":[" + words + "{\"word\":\"hello\",\"expressions\":\"greeting(hello)\",\"frequency\":0}],\"phrases\":[]}";
        String rules = """
                {"behaviorGroups":[{"name":"ForEachGroup","behaviorRules":[
                  {"name":"Welcome","actions":["welcome"],"conditions":[{"type":"occurrence",
                    "configs":{"maxTimesOccurred":"0","behaviorRuleName":"Welcome"}}]}%s
                ]}]}""".formatted(behavior);
        String output = """
                {"outputSet":[{"action":"welcome","timesOccurred":0,
                  "outputs":[{"valueAlternatives":[{"type":"text","text":"Welcome"}]}]}%s]}""".formatted(outputs);

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationHttpCalls = createResource(httpCallsConfig(), "/apicallstore/apicalls");

        String workflowBody = """
                {"workflowSteps":[
                  {"type":"eddi://ai.labs.parser","config":{},"extensions":{
                    "dictionaries":[{"type":"eddi://ai.labs.parser.dictionaries.regular","config":{"uri":"%s"}}],
                    "corrections":[]}},
                  {"type":"eddi://ai.labs.rules","config":{"uri":"%s"}},
                  {"type":"eddi://ai.labs.apicalls","config":{"uri":"%s"}},
                  {"type":"eddi://ai.labs.output","config":{"uri":"%s"}},
                  {"type":"eddi://ai.labs.templating","config":{}},
                  {"type":"eddi://ai.labs.property","config":{}}
                ]}""".formatted(locationDictionary, locationRules, locationHttpCalls, locationOutput);
        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentLocation = createResource("{\"packages\":[\"" + locationWorkflow + "\"]}", "/agentstore/agents");
        ResourceId id = extractResourceId(agentLocation);
        deployAgent(id.id(), id.version());
        return id;
    }

    private static String httpCallsConfig() {
        List<String> calls = new ArrayList<>();
        for (String s : SCENARIOS) {
            calls.add(sourceCall(s));
        }
        // 1. basic: POST with the element templated into the path and the body
        calls.add(forEachCall("basic", "", "", "post", "{\\\"sku\\\":\\\"{el.id}\\\"}", """
                "postResponse":{"propertyInstructions":[
                  {"name":"basicCount","valueString":"count={basicResults.size}","scope":"conversation"}]}"""));
        calls.add(forEachCall("empty", "", "", "get", "", ""));
        calls.add(forEachCall("filter", "\"templateFilterExpression\":\"el.active\"", "", "get", "", ""));
        // 2. bounds
        calls.add(forEachCall("toomany", "\"maxItems\":3", "", "get", "", ""));
        int itemBytes = bodyWith(CJK).getBytes(StandardCharsets.UTF_8).length;
        calls.add(forEachCall("bytes", "\"maxTotalResponseSizeInBytes\":" + (2 * itemBytes + 5), "", "get", "", ""));
        // 3. partial failure
        calls.add(forEachCall("partial", "", "", "get", "", """
                "postResponse":{"propertyInstructions":[
                  {"name":"partialOn207","valueString":"only207","scope":"conversation",
                   "httpCodeValidator":{"runOnHttpCode":[207]}},
                  {"name":"partialOn200","valueString":"complete","scope":"conversation"}]}"""));
        calls.add(forEachCall("failmode", "\"onItemFailure\":\"fail\"", "", "get", "", ""));
        calls.add(forEachCall("timeout", "", "\"timeoutInMillis\":1000", "get", "", """
                "postResponse":{"retryApiCallInstruction":{"maxRetries":2,"exponentialBackoffDelayInMillis":50,
                  "retryOnHttpCodes":[500,502,503]}}"""));
        // 4. concurrency
        calls.add(forEachCall("seq", "", "", "get", "", ""));
        calls.add(forEachCall("conc", "\"maxConcurrency\":2", "", "get", "", ""));
        return "{\"targetServerUrl\":\"http://localhost:" + wireMock.port() + "\",\"httpCalls\":[" + String.join(",", calls) + "]}";
    }

    private static String sourceCall(String s) {
        return """
                {"name":"%sList","actions":["go_%s"],"saveResponse":true,"responseObjectName":"%sList",
                 "request":{"path":"/%s/list","method":"get","headers":{"Accept":"application/json"},
                 "queryParams":{},"contentType":"","body":""}}""".formatted(s, s, s, s);
    }

    private static String forEachCall(String s, String forEachExtra, String callExtra, String method, String body, String postResponse) {
        return """
                {"name":"%sItems","actions":["go_%s"],"saveResponse":true,"responseObjectName":"%sResults"%s%s,
                 "forEach":{"pathToTargetArray":"%sList.ids","iterationObjectName":"el"%s},
                 "request":{"path":"/%s/item/{el.id}","method":"%s","headers":{"Accept":"application/json"},
                 "queryParams":{},"contentType":"%s","body":"%s"}}"""
                .formatted(s, s, s, callExtra.isEmpty() ? "" : "," + callExtra, postResponse.isEmpty() ? "" : "," + postResponse, s,
                        forEachExtra.isEmpty() ? "" : "," + forEachExtra, s, method, body.isEmpty() ? "" : "application/json", body);
    }
}
