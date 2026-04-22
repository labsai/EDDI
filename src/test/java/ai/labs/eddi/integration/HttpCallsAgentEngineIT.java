package ai.labs.eddi.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.hamcrest.Matchers.*;

/**
 * Integration test exercising the ApiCallsTask pipeline via WireMock-backed
 * HTTP endpoints.
 * <p>
 * Covers: {@code ApiCallsTask}, {@code ApiCallExecutor}, {@code PrePostUtils},
 * {@code HttpClientWrapper}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpCallsAgentEngineIT extends BaseIntegrationIT {

    private static final String TEST_USER_ID = "httpCallsTestUser";

    private static WireMockServer wireMock;
    private static ResourceId agentResourceId;
    private static boolean agentDeployed = false;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        // Stub: JSON API endpoint
        wireMock.stubFor(get(urlPathEqualTo("/api/data"))
                .willReturn(okJson("""
                        {"message": "Hello from WireMock", "status": "ok", "count": 42}
                        """)
                        .withHeader("Content-Type", "application/json")));

        // Stub: API endpoint with custom header check
        wireMock.stubFor(get(urlPathEqualTo("/api/secure"))
                .withHeader("X-Custom-Auth", equalTo("test-token"))
                .willReturn(okJson("""
                        {"secure": true, "data": "protected-content"}
                        """)
                        .withHeader("Content-Type", "application/json")));
    }

    @AfterAll
    static void stopWireMock() {
        if (agentResourceId != null) {
            undeployAgentQuietly(agentResourceId.id(), agentResourceId.version());
        }
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        if (!agentDeployed) {
            agentResourceId = setupHttpCallsAgent();
            agentDeployed = true;
        }
    }

    // ==================== Test 1: Basic API Call ====================

    @Test
    @Order(1)
    @DisplayName("ApiCallsTask should call WireMock and store response in memory")
    void testApiCallWithJsonResponse() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "fetch", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));

        // Verify WireMock received the request
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/data")));
    }

    // ==================== Test 2: API Call with Headers ====================

    @Test
    @Order(2)
    @DisplayName("ApiCallsTask should send custom headers to WireMock")
    void testApiCallWithHeaders() throws Exception {
        ResourceId conversationId = createConversation(agentResourceId.id(), TEST_USER_ID);
        waitForConversationReady(agentResourceId.id(), conversationId.id());

        Response response = sendUserInput(agentResourceId.id(), conversationId.id(), "secure", true, false);

        response.then().assertThat()
                .statusCode(200)
                .body("conversationSteps", hasSize(greaterThanOrEqualTo(2)));

        // Verify WireMock received the request with correct header
        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/secure"))
                .withHeader("X-Custom-Auth", equalTo("test-token")));
    }

    // ==================== Agent Setup ====================

    /**
     * Sets up an agent with ApiCallsTask (httpcalls) step in the workflow.
     * Pipeline: parser → rules → apicalls → output → templating → property
     */
    private ResourceId setupHttpCallsAgent() throws Exception {
        // Dictionary: "fetch" → fetch_data, "secure" → secure_data
        String dictionary = """
                {
                  "language": "en",
                  "words": [
                    {"word": "fetch", "expressions": "fetch_data(fetch)", "frequency": 0},
                    {"word": "secure", "expressions": "secure_data(secure)", "frequency": 0},
                    {"word": "hello", "expressions": "greeting(hello)", "frequency": 0}
                  ],
                  "phrases": []
                }
                """;

        // Rules: fetch_data → action "api_get_data", secure_data → action
        // "api_get_secure"
        String rules = """
                {
                  "behaviorGroups": [{
                    "name": "ApiGroup",
                    "behaviorRules": [
                      {
                        "name": "Welcome",
                        "actions": ["welcome"],
                        "conditions": [{
                          "type": "occurrence",
                          "configs": {"maxTimesOccurred": "0", "behaviorRuleName": "Welcome"}
                        }]
                      },
                      {
                        "name": "FetchData",
                        "actions": ["api_get_data"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "fetch_data(*)", "occurrence": "currentStep"}
                        }]
                      },
                      {
                        "name": "SecureData",
                        "actions": ["api_get_secure"],
                        "conditions": [{
                          "type": "inputmatcher",
                          "configs": {"expressions": "secure_data(*)", "occurrence": "currentStep"}
                        }]
                      }
                    ]
                  }]
                }
                """;

        // Output: welcome message + API response output
        String output = """
                {
                  "outputSet": [
                    {
                      "action": "welcome",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Welcome to API test"}]}]
                    },
                    {
                      "action": "api_get_data",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "API response received"}]}]
                    },
                    {
                      "action": "api_get_secure",
                      "timesOccurred": 0,
                      "outputs": [{"type": "text", "valueAlternatives": [{"type": "text", "text": "Secure API response received"}]}]
                    }
                  ]
                }
                """;

        // HttpCalls config: two API calls pointing at WireMock
        String httpCallsConfig = String.format("""
                {
                  "targetServerUrl": "http://localhost:%d",
                  "httpCalls": [
                    {
                      "name": "getData",
                      "actions": ["api_get_data"],
                      "saveResponse": true,
                      "responseObjectName": "apiResponse",
                      "request": {
                        "path": "/api/data",
                        "method": "get",
                        "headers": {"Accept": "application/json"},
                        "queryParams": {},
                        "contentType": "",
                        "body": ""
                      }
                    },
                    {
                      "name": "getSecure",
                      "actions": ["api_get_secure"],
                      "saveResponse": true,
                      "responseObjectName": "secureResponse",
                      "request": {
                        "path": "/api/secure",
                        "method": "get",
                        "headers": {
                          "Accept": "application/json",
                          "X-Custom-Auth": "test-token"
                        },
                        "queryParams": {},
                        "contentType": "",
                        "body": ""
                      }
                    }
                  ]
                }
                """, wireMock.port());

        String locationDictionary = createResource(dictionary, "/dictionarystore/dictionaries");
        String locationRules = createResource(rules, "/rulestore/rulesets");
        String locationOutput = createResource(output, "/outputstore/outputsets");
        String locationHttpCalls = createResource(httpCallsConfig, "/apicallstore/apicalls");

        // Workflow: parser → rules → apicalls → output → templating → property
        String workflowBody = String.format("""
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
                    {"type": "eddi://ai.labs.apicalls", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.output", "config": {"uri": "%s"}},
                    {"type": "eddi://ai.labs.templating", "config": {}},
                    {"type": "eddi://ai.labs.property", "config": {}}
                  ]
                }
                """, locationDictionary, locationRules, locationHttpCalls, locationOutput);

        String locationWorkflow = createResource(workflowBody, "/workflowstore/workflows");
        String agentBody = String.format("""
                {"packages": ["%s"]}""", locationWorkflow);
        String agentLocation = createResource(agentBody, "/agentstore/agents");

        ResourceId agentId = extractResourceId(agentLocation);
        deployAgent(agentId.id(), agentId.version());
        return agentId;
    }
}
