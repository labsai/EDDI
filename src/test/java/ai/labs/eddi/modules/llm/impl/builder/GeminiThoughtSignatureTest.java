/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.builder;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduction for the Gemini 3.x thought-signature defect: EDDI could not use
 * tools with any Gemini 3.x model, because the {@code thoughtSignature} Gemini
 * attaches to a {@code functionCall} part was never echoed back on the
 * follow-up request carrying the {@code functionResponse}.
 * <p>
 * <b>Why a fake HTTP client and not a unit assertion on the two builder
 * flags.</b> Asserting {@code returnThinking == true} would only restate
 * {@link GeminiLanguageModelBuilder}; it would pass whatever langchain4j then
 * did with the flag, and the defect lives precisely in what reaches the wire.
 * Substituting langchain4j's own pluggable
 * {@link dev.langchain4j.http.client.HttpClient} runs the real
 * {@code GoogleAiGeminiChatModel}, the real {@code PartsAndContentsMapper} in
 * both directions and the real JSON codec, with only the socket replaced — so
 * this exercises the complete round trip without a network or an API key.
 * <p>
 * <b>Why it cannot pass vacuously.</b> {@link GeminiApiStub} does not merely
 * record requests, it <em>enforces the same rule the live API does</em>: a
 * replayed {@code functionCall} part with no {@code thoughtSignature} is
 * answered with the same {@code 400 INVALID_ARGUMENT} Gemini sends. The
 * {@link #explicitOptOutStillReproducesTheLiveApi400()} case turns the fix off
 * through configuration and asserts that the 400 comes back, which is what
 * proves the stub's check is live rather than a no-op that would pass either
 * way.
 * <p>
 * Measured against {@code generativelanguage.googleapis.com}: both
 * {@code gemini-3.8-flash} and {@code gemini-3.5-flash} emit the signature and
 * reject a replay without it, with or without
 * {@code thinkingConfig.thinkingBudget = 0}; {@code gemini-2.5-flash} emits it
 * but tolerates its absence. That is why the fix is a default and not an
 * opt-in.
 */
@DisplayName("Gemini thought signatures")
class GeminiThoughtSignatureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The opaque value Gemini issues; it is echoed back byte for byte or not at
     * all.
     */
    private static final String SIGNATURE = "CtkBAcu98PBRSHOULDBEECHOEDBACKVERBATIM==";

    private static final String TOOL_NAME = "getStatus";

    /**
     * langchain4j's key for the captured signature. Hard-coded upstream with a "do
     * not change, will break backward compatibility" comment
     * ({@code PartsAndContentsMapper.THINKING_SIGNATURE_KEY}) and not public, so
     * this test restates it. If an upgrade ever renames it, this constant is the
     * thing that should fail.
     */
    private static final String THINKING_SIGNATURE_KEY = "thinking_signature";

    private final GeminiLanguageModelBuilder builder = new GeminiLanguageModelBuilder();

    private static Map<String, String> params() {
        return new HashMap<>(Map.of("apiKey", "test-key", "modelName", "gemini-3.8-flash"));
    }

    private static ChatRequest firstTurn() {
        return ChatRequest.builder()
                .messages(List.of(UserMessage.from("Is the platform healthy?")))
                .toolSpecifications(ToolSpecification.builder().name(TOOL_NAME).description("Reads platform status").build())
                .build();
    }

    private static ChatRequest followUpTurn(AiMessage modelTurn) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("Is the platform healthy?"));
        messages.add(modelTurn);
        messages.add(ToolExecutionResultMessage.from(modelTurn.toolExecutionRequests().getFirst(), "{\"status\":\"ok\"}"));
        return ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(ToolSpecification.builder().name(TOOL_NAME).description("Reads platform status").build())
                .build();
    }

    @Test
    @DisplayName("captures the signature off the functionCall part and echoes it back on the follow-up request")
    void signatureSurvivesTheToolRoundTrip() {
        GeminiApiStub stub = new GeminiApiStub();
        ChatModel model = builder.build(params(), stub);

        // Turn 1: the model asks for a tool, with a signature attached to the call.
        ChatResponse first = model.chat(firstTurn());
        AiMessage modelTurn = first.aiMessage();
        assertTrue(modelTurn.hasToolExecutionRequests(), "the stub returned a functionCall part");
        assertEquals(SIGNATURE, modelTurn.attribute(THINKING_SIGNATURE_KEY, String.class),
                "the signature must be captured off the response, or there is nothing to echo back");

        // Turn 2: replaying that model turn alongside the tool result is the request
        // the live API rejected. The stub rejects it on the same condition.
        ChatResponse second = model.chat(followUpTurn(modelTurn));
        assertEquals("The platform is healthy.", second.aiMessage().text());

        // …and assert it on the wire, not only on the absence of a rejection.
        assertEquals(SIGNATURE, stub.replayedSignature(),
                "the follow-up request's functionCall part must carry the signature verbatim");
    }

    @Test
    @DisplayName("an explicit opt-out still reproduces the live API's 400 — proving the stub enforces the rule")
    void explicitOptOutStillReproducesTheLiveApi400() {
        GeminiApiStub stub = new GeminiApiStub();
        Map<String, String> optedOut = params();
        optedOut.put("returnThinking", "false");
        optedOut.put("sendThinking", "false");
        ChatModel model = builder.build(optedOut, stub);

        ChatResponse first = model.chat(firstTurn());
        AiMessage modelTurn = first.aiMessage();
        assertTrue(modelTurn.attributes().isEmpty(), "returnThinking=false must not capture the signature");

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> model.chat(followUpTurn(modelTurn)),
                "replaying a functionCall with no signature is exactly what Gemini 3.x rejects");
        assertTrue(rootMessageOf(thrown).contains("missing a thought_signature"),
                "expected the live API's own complaint, got: " + rootMessageOf(thrown));
    }

    @Test
    @DisplayName("the streaming builder round-trips the signature too")
    void streamingCarriesTheSameFlags() throws Exception {
        // Streaming is a separate request path with its own response assembler
        // (GeminiStreamingResponseBuilder), reached through buildStreaming. A flag set
        // on build() and not here would break every SSE conversation — which is most of
        // them — while the non-streaming test stayed green, so this drives the real
        // thing rather than asserting the model is non-null.
        GeminiApiStub stub = new GeminiApiStub();
        StreamingChatModel model = builder.buildStreaming(params(), stub);
        assertNotNull(model);

        AiMessage modelTurn = collect(model, firstTurn());
        assertTrue(modelTurn.hasToolExecutionRequests());
        assertEquals("Checking the platform status.", modelTurn.text(),
                "precondition: the turn really was assembled from more than one frame");
        assertEquals(SIGNATURE, modelTurn.attribute(THINKING_SIGNATURE_KEY, String.class),
                "the streaming assembler must carry the signature through too");

        // The replay is the request the live API rejects; had the signature been
        // dropped, the stub's 400 would arrive through onError and fail collect().
        AiMessage second = collect(model, followUpTurn(modelTurn));
        assertEquals("The platform is healthy.", second.text());
        assertEquals(SIGNATURE, stub.replayedSignature(),
                "the streaming follow-up request must carry the signature verbatim");
    }

    /**
     * Drives a streaming request to completion and returns the assembled message.
     */
    private static AiMessage collect(StreamingChatModel model, ChatRequest request) throws Exception {
        var done = new CompletableFuture<ChatResponse>();
        model.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                // nothing to assert on the deltas; the assembled message is what matters
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                done.complete(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        return done.get(10, TimeUnit.SECONDS).aiMessage();
    }

    @Test
    @DisplayName("survives the HITL tool pause: gate normalisation, MongoDB persistence, and the resumed request")
    void signatureSurvivesAPauseAndResumeAcrossRequests() {
        // The only place in EDDI where a model turn carrying tool calls crosses a
        // request boundary and a MongoDB write is a HITL tool pause: the tool loop
        // stops before the gated call runs, the transcript is persisted on the
        // conversation, and a LATER HTTP request resumes it and replays that same
        // model turn to the provider. This drives the same pieces that path uses —
        // the gate's normalisation and the transcript codec — around a real model and
        // wire, though not the tool loop itself (AgentOrchestratorCoverageTest covers
        // the loop's pause and resume).
        GeminiApiStub stub = new GeminiApiStub();
        ChatModel model = builder.build(params(), stub);

        // Turn 1, live: the model asks for a tool and the gate normalises the batch.
        AiMessage live = model.chat(firstTurn()).aiMessage();
        var gateActive = new ToolApprovalsConfig();
        gateActive.setRequireApproval(List.of("*"));
        AiMessage gated = ToolApprovalGateSupport.normalizeToolCallIds(live, gateActive);
        assertEquals(SIGNATURE, gated.attribute(THINKING_SIGNATURE_KEY, String.class),
                "the gate must not strip the signature while giving the calls ids");

        // The pause: serialized exactly as PendingToolCallBatch persists it.
        var codec = new ChatTranscriptCodec();
        var persisted = codec.serialize(List.of(UserMessage.from("Is the platform healthy?"), gated),
                PendingToolCallBatch.TRANSCRIPT_MAX_BYTES_DEFAULT);
        assertFalse(persisted.omitted(), "the transcript must fit, or resume takes the fallback path");
        assertTrue(persisted.json().contains(SIGNATURE), "the persisted document must carry the signature");

        // The resume, on a later request: restore and replay.
        AiMessage restored;
        try {
            List<ChatMessage> messages = codec.deserialize(persisted.json());
            restored = (AiMessage) messages.get(1);
        } catch (ChatTranscriptCodec.TranscriptCodecException e) {
            throw new AssertionError("the persisted transcript must restore", e);
        }
        assertEquals(SIGNATURE, restored.attribute(THINKING_SIGNATURE_KEY, String.class),
                "the signature must survive the MongoDB round trip");

        ChatResponse resumed = model.chat(followUpTurn(restored));
        assertEquals("The platform is healthy.", resumed.aiMessage().text());
        assertEquals(SIGNATURE, stub.replayedSignature(),
                "the resumed request must carry the signature the paused turn was issued with");
    }

    @Test
    @DisplayName("both flags are declared, so neither is reported as an unrecognised parameter")
    void flagsAreDeclaredParameters() {
        assertTrue(builder.recognisedParameters().contains(GeminiLanguageModelBuilder.KEY_RETURN_THINKING));
        assertTrue(builder.recognisedParameters().contains(GeminiLanguageModelBuilder.KEY_SEND_THINKING));
    }

    private static String rootMessageOf(Throwable t) {
        Throwable cursor = t;
        StringBuilder all = new StringBuilder();
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                all.append(cursor.getMessage()).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }

    /**
     * A stand-in for {@code generativelanguage.googleapis.com} that answers a
     * two-turn function-calling exchange and applies Gemini 3.x's own validation:
     * any {@code functionCall} part in the request's {@code contents} that carries
     * no {@code thoughtSignature} is rejected with the API's real 400.
     * <p>
     * Doubles as its own {@link HttpClientBuilder} so it can be handed straight to
     * the builder seam.
     */
    private static final class GeminiApiStub implements HttpClient, HttpClientBuilder {

        private final List<String> requestBodies = new ArrayList<>();

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            requestBodies.add(request.body());
            String missing = firstFunctionCallWithoutSignature(request.body());
            if (missing != null) {
                // Verbatim shape of the live failure, including the doc link, so a
                // developer who sees this in a test failure recognises it in production.
                throw new HttpException(400, "{\"error\":{\"code\":400,\"status\":\"INVALID_ARGUMENT\",\"message\":"
                        + "\"Function call is missing a thought_signature in functionCall parts. This is required for "
                        + "tools to work correctly. Additional data, function call `default_api:" + missing + "`. "
                        + "https://ai.google.dev/gemini-api/docs/thought-signatures\"}}");
            }
            return SuccessfulHttpResponse.builder()
                    .statusCode(200)
                    .headers(Map.of("content-type", List.of("application/json")))
                    .body(requestBodies.size() == 1 ? toolCallResponse() : finalTextResponse())
                    .build();
        }

        /** The name of the first unsigned functionCall in the request, or null. */
        private String firstFunctionCallWithoutSignature(String body) {
            try {
                JsonNode contents = MAPPER.readTree(body).path("contents");
                for (JsonNode content : contents) {
                    for (JsonNode part : content.path("parts")) {
                        JsonNode functionCall = part.path("functionCall");
                        if (functionCall.isMissingNode()) {
                            continue;
                        }
                        String signature = part.path("thoughtSignature").asText(null);
                        if (signature == null || signature.isBlank()) {
                            return functionCall.path("name").asText(TOOL_NAME);
                        }
                    }
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException("stub could not parse the request body: " + body, e);
            }
        }

        /** The signature the follow-up request actually replayed, or null. */
        String replayedSignature() {
            if (requestBodies.size() < 2) {
                return null;
            }
            try {
                for (JsonNode content : MAPPER.readTree(requestBodies.get(1)).path("contents")) {
                    for (JsonNode part : content.path("parts")) {
                        if (!part.path("functionCall").isMissingNode()) {
                            return part.path("thoughtSignature").asText(null);
                        }
                    }
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException("stub could not parse the recorded request", e);
            }
        }

        private static String toolCallResponse() {
            return """
                    {"candidates":[{"content":{"role":"model","parts":[
                      {"functionCall":{"name":"%s","args":{}},"thoughtSignature":"%s"}
                    ]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5,"totalTokenCount":15}}
                    """.formatted(TOOL_NAME, SIGNATURE);
        }

        private static String finalTextResponse() {
            return """
                    {"candidates":[{"content":{"role":"model","parts":[
                      {"text":"The platform is healthy."}
                    ]},"finishReason":"STOP"}],
                    "usageMetadata":{"promptTokenCount":20,"candidatesTokenCount":6,"totalTokenCount":26}}
                    """;
        }

        // ── HttpClientBuilder: this stub is its own builder ──

        @Override
        public HttpClient build() {
            return this;
        }

        @Override
        public Duration connectTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder connectTimeout(Duration timeout) {
            return this;
        }

        @Override
        public Duration readTimeout() {
            return null;
        }

        @Override
        public HttpClientBuilder readTimeout(Duration timeout) {
            return this;
        }

        /**
         * The streaming counterpart: same recording and validation. The tool-call turn
         * is streamed as two frames — the model's narration, then the
         * {@code functionCall} carrying the signature — so langchain4j's
         * {@code GeminiStreamingResponseBuilder} really merges attributes across frames
         * (it keeps the last value per key). Other turns are one frame.
         * <p>
         * Not a recording of real Gemini 3 chunking: whether a live stream can carry a
         * second, different signature in a later frame (which last-wins would keep) has
         * not been measured.
         */
        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
            try {
                boolean toolCallTurn = requestBodies.isEmpty();
                SuccessfulHttpResponse response = execute(request);
                listener.onOpen(response);
                if (toolCallTurn) {
                    listener.onEvent(new ServerSentEvent("message", narrationFrame()));
                }
                listener.onEvent(new ServerSentEvent("message", response.body()));
                listener.onClose();
            } catch (Throwable t) {
                // A streaming 400 reaches the caller through onError, not as a throw.
                listener.onError(t);
            }
        }

        private static String narrationFrame() {
            return """
                    {"candidates":[{"content":{"role":"model","parts":[
                      {"text":"Checking the platform status."}
                    ]}}]}
                    """;
        }
    }
}
