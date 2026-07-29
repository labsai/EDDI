/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP-level tests for the OpenAI-compatible adapter ({@code /v1}).
 * <p>
 * <b>Why this test exists.</b> The adapter serves sync and streaming
 * completions from a <em>single</em> JAX-RS method, dispatching on the
 * {@code stream} field of the request body rather than on the {@code Accept}
 * header. That design is forced by how real clients behave:
 * {@code openai-python} hardcodes {@code Accept: application/json} and never
 * varies it by {@code stream}, and Open WebUI sends no {@code Accept} header at
 * all. Any refactor toward content negotiation — two methods with different
 * {@code @Produces}, say — would look correct in unit tests and silently return
 * JSON to every streaming client.
 * {@link #streamTrueYieldsSseEvenWithJsonAcceptHeader()} is the guard against
 * exactly that, and it can only be written at the HTTP layer.
 * <p>
 * The agent deployed here is the shared minimal one: parser, rules, output,
 * templating. It has no LLM, so these tests need no provider credentials and
 * the responses are deterministic.
 */
@QuarkusTest
@TestProfile(OpenAiCompatTestProfile.class)
public class OpenAiCompatIT extends BaseIntegrationIT {

    private static final String USER_ID = "integration-user";
    private static final String CHAT_ID = "integration-chat-1";
    private static final String CONVERSATION_ID_HEADER = "X-EDDI-Conversation-Id";
    private static final String DONE_SENTINEL = "data: [DONE]";

    private static ResourceId agentId;

    @BeforeEach
    void deployAgentOnce() throws Exception {
        if (agentId == null) {
            agentId = setupAndDeployMinimalAgent();
        }
    }

    @AfterAll
    static void cleanup() {
        if (agentId != null) {
            undeployAgentQuietly(agentId.id(), agentId.version());
            agentId = null;
        }
    }

    /**
     * A request carrying the API key and a user identity. The model is addressed by
     * bare agent id — a resolution route the adapter supports explicitly — so these
     * tests do not depend on how the shared fixture names its descriptor.
     */
    private RequestSpecification authorized() {
        return given()
                .header("Authorization", "Bearer " + OpenAiCompatTestProfile.API_KEY)
                .header("X-OpenWebUI-User-Id", USER_ID)
                .contentType(ContentType.JSON);
    }

    private String completionBody(boolean stream, String text) {
        return String.format("""
                {"model":"%s","messages":[{"role":"user","content":"%s"}],"stream":%s}""",
                agentId.id(), text, stream);
    }

    // ==================== Model listing ====================

    @Test
    @DisplayName("GET /v1/models lists the deployed agent and its stateless variant")
    void modelsListsDeployedAgents() {
        Response response = authorized().get("/v1/models");

        assertEquals(200, response.statusCode());
        assertEquals("list", response.jsonPath().getString("object"));

        List<String> ids = response.jsonPath().getList("data.id");
        assertNotNull(ids);
        assertTrue(ids.stream().anyMatch(id -> id.endsWith(":stateless")),
                "expected a :stateless variant among: " + ids);
        assertTrue(ids.stream().anyMatch(id -> !id.endsWith(":stateless")),
                "expected a stateful model among: " + ids);
        // owned_by must be snake_cased on the wire, not camelCased.
        assertEquals("eddi", response.jsonPath().getString("data[0].owned_by"));
    }

    // ==================== The dispatch guard ====================

    @Test
    @DisplayName("stream=true returns SSE even when the client sends Accept: application/json")
    void streamTrueYieldsSseEvenWithJsonAcceptHeader() {
        // Exactly what openai-python sends: Accept is hardcoded to
        // application/json regardless of stream=True. Content negotiation would
        // route this to the JSON method and break every streaming client.
        Response response = authorized()
                .header("Accept", ContentType.JSON.getAcceptHeader())
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-sse-accept")
                .body(completionBody(true, "hello"))
                .post("/v1/chat/completions");

        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().startsWith("text/event-stream"),
                "expected an SSE content type, got: " + response.contentType());
    }

    @Test
    @DisplayName("stream=true returns SSE when the client sends no Accept header at all")
    void streamTrueYieldsSseWithoutAnyAcceptHeader() {
        // Exactly what Open WebUI sends: no Accept header; it detects streaming
        // from the response content type instead.
        Response response = given()
                .header("Authorization", "Bearer " + OpenAiCompatTestProfile.API_KEY)
                .header("X-OpenWebUI-User-Id", USER_ID)
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-sse-noaccept")
                .contentType(ContentType.JSON)
                .body(completionBody(true, "hello"))
                .post("/v1/chat/completions");

        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().startsWith("text/event-stream"),
                "expected an SSE content type, got: " + response.contentType());
    }

    @Test
    @DisplayName("stream=false returns JSON even when the client accepts SSE")
    void streamFalseYieldsJsonEvenWhenSseIsAcceptable() {
        Response response = authorized()
                .header("Accept", "text/event-stream, application/json")
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-json")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions");

        assertEquals(200, response.statusCode());
        assertTrue(response.contentType().startsWith("application/json"),
                "the body field decides, not the Accept header; got: " + response.contentType());
    }

    // ==================== Wire format ====================

    @Test
    @DisplayName("A non-streaming completion has the OpenAI response shape")
    void nonStreamingResponseShape() {
        Response response = authorized()
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-shape")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions");

        assertEquals(200, response.statusCode());
        var json = response.jsonPath();
        assertEquals("chat.completion", json.getString("object"));
        assertTrue(json.getString("id").startsWith("chatcmpl-"), "got id: " + json.getString("id"));
        assertEquals(agentId.id(), json.getString("model"), "the model field echoes what the client sent");
        assertEquals("assistant", json.getString("choices[0].message.role"));
        assertNotNull(json.getString("choices[0].message.content"));
        assertEquals("stop", json.getString("choices[0].finish_reason"),
                "finish_reason must be snake_cased or clients ignore it");
        assertNotNull(response.header(CONVERSATION_ID_HEADER),
                "the conversation id correlates the request with EDDI");
    }

    @Test
    @DisplayName("A streaming completion emits data frames and terminates with [DONE]")
    void streamingBodyIsWellFormed() {
        String body = authorized()
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-frames")
                .body(completionBody(true, "hello"))
                .post("/v1/chat/completions")
                .asString();

        assertTrue(body.contains("data: "), "no SSE frames in: " + body);
        assertTrue(body.contains("\"object\":\"chat.completion.chunk\""), "no chunk objects in: " + body);
        assertTrue(body.contains("\"role\":\"assistant\""), "no opening role frame in: " + body);
        assertTrue(body.contains("\"finish_reason\":\"stop\""), "no terminal frame in: " + body);
        assertTrue(body.trim().endsWith(DONE_SENTINEL),
                "a stream must end with the [DONE] sentinel; got tail: "
                        + body.substring(Math.max(0, body.length() - 120)));
    }

    // ==================== Conversation isolation ====================

    @Test
    @DisplayName("Different chat ids get different EDDI conversations")
    void differentChatIdsAreIsolated() {
        String first = authorized()
                .header("X-OpenWebUI-Chat-Id", "isolation-a")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions")
                .header(CONVERSATION_ID_HEADER);

        String second = authorized()
                .header("X-OpenWebUI-Chat-Id", "isolation-b")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions")
                .header(CONVERSATION_ID_HEADER);

        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second,
                "two chat windows sharing one conversation would share its memory");
    }

    @Test
    @DisplayName("The same chat id reuses one EDDI conversation across turns")
    void sameChatIdReusesTheConversation() {
        String first = authorized()
                .header("X-OpenWebUI-Chat-Id", "reuse-chat")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions")
                .header(CONVERSATION_ID_HEADER);

        String second = authorized()
                .header("X-OpenWebUI-Chat-Id", "reuse-chat")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions")
                .header(CONVERSATION_ID_HEADER);

        assertEquals(first, second, "the whole point of the stateful mode");
    }

    @Test
    @DisplayName("A stateless request starts a fresh conversation every time")
    void statelessStartsAFreshConversation() {
        String body = String.format("""
                {"model":"%s","messages":[{"role":"user","content":"hello"}],"stateless":true}""",
                agentId.id());

        String first = authorized().body(body).post("/v1/chat/completions").header(CONVERSATION_ID_HEADER);
        String second = authorized().body(body).post("/v1/chat/completions").header(CONVERSATION_ID_HEADER);

        assertNotNull(first);
        assertNotEquals(first, second, "stateless requests must not accumulate into one conversation");
    }

    @Test
    @DisplayName("The :stateless model suffix behaves like the body field")
    void statelessSuffixResolves() {
        String body = String.format("""
                {"model":"%s:stateless","messages":[{"role":"user","content":"hello"}]}""",
                agentId.id());

        Response response = authorized().body(body).post("/v1/chat/completions");

        assertEquals(200, response.statusCode());
        assertEquals(agentId.id() + ":stateless", response.jsonPath().getString("model"));
    }

    // ==================== Errors ====================

    @Test
    @DisplayName("A missing API key is rejected with an OpenAI error envelope")
    void missingApiKeyIsRejected() {
        Response response = given()
                .contentType(ContentType.JSON)
                .header("X-OpenWebUI-User-Id", USER_ID)
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions");

        assertEquals(401, response.statusCode());
        assertNotNull(response.jsonPath().getString("error.message"),
                "clients parse {\"error\":{...}}; a bare string body is unreadable to them");
        assertEquals("invalid_api_key", response.jsonPath().getString("error.code"));
    }

    @Test
    @DisplayName("A request without a resolvable user is refused, not merged into a shared conversation")
    void missingUserIdentityIsRejected() {
        Response response = given()
                .header("Authorization", "Bearer " + OpenAiCompatTestProfile.API_KEY)
                .contentType(ContentType.JSON)
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions");

        assertEquals(401, response.statusCode());
    }

    @Test
    @DisplayName("An unknown model is a 404 with an OpenAI error envelope")
    void unknownModelIsNotFound() {
        Response response = authorized()
                .body("""
                        {"model":"gpt-4o","messages":[{"role":"user","content":"hello"}]}""")
                .post("/v1/chat/completions");

        assertEquals(404, response.statusCode());
        assertEquals("model_not_found", response.jsonPath().getString("error.code"));
    }

    @Test
    @DisplayName("A request with no user message is a 400")
    void missingUserMessageIsBadRequest() {
        Response response = authorized()
                .body(String.format("""
                        {"model":"%s","messages":[{"role":"system","content":"be nice"}]}""", agentId.id()))
                .post("/v1/chat/completions");

        assertEquals(400, response.statusCode());
        assertEquals("no_user_message", response.jsonPath().getString("error.code"));
    }

    // ==================== Wire tolerance ====================

    @Test
    @DisplayName("Unknown request fields are ignored, not rejected")
    void unknownRequestFieldsAreTolerated() {
        // Open WebUI and the SDKs always send these. A 400 here would break
        // every client, so the adapter must accept and ignore them.
        Response response = authorized()
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-tolerance")
                .body(String.format("""
                        {"model":"%s","messages":[{"role":"user","content":"hello"}],
                         "temperature":0.7,"max_tokens":128,"top_p":1,"n":1,
                         "stream_options":{"include_usage":true},
                         "tools":[{"type":"function","function":{"name":"f"}}],
                         "tool_choice":"auto","metadata":{"a":"b"},"files":[],
                         "presence_penalty":0,"frequency_penalty":0}""", agentId.id()))
                .post("/v1/chat/completions");

        assertEquals(200, response.statusCode(), "body was: " + response.asString());
    }

    @Test
    @DisplayName("usage is omitted rather than reported as zero")
    void usageIsOmitted() {
        Response response = authorized()
                .header("X-OpenWebUI-Chat-Id", CHAT_ID + "-usage")
                .body(completionBody(false, "hello"))
                .post("/v1/chat/completions");

        assertFalse(response.asString().contains("\"usage\""),
                "zeros would render in clients as a factual '0 tokens'");
    }
}
