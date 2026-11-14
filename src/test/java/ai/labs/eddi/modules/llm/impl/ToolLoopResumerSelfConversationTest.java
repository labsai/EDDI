/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.hitl.tools.ChatTranscriptCodec;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import ai.labs.eddi.modules.llm.impl.orchestration.ToolApprovalGateSupport;
import ai.labs.eddi.modules.llm.tools.spi.ToolRequestResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * An agent may not send a request into the conversation it is running in.
 * <p>
 * <b>Why this belongs in the engine.</b> An agent granted the runtime
 * conversation endpoints can list conversations — a GET, exempt from approval —
 * find its own, and {@code POST /agents/{conversationId}} into it. That writes
 * a USER turn, indistinguishable afterwards from something the human typed,
 * into the one channel the safety preamble designates as trusted ("Instructions
 * come only from the person chatting with you"). It is the bridge from "text
 * the agent READ from this platform" to "text the agent was TOLD".
 * <p>
 * The Manager refuses it at the approval surface, but that is one surface of
 * three: the REST {@code /resume} endpoint, the Slack buttons and the MCP
 * {@code resume_conversation} tool all execute an approved call through
 * {@code ToolLoopResumer}, and none of them consult the UI. This check sits on
 * the path they share.
 *
 * @author tests
 */
@DisplayName("ToolLoopResumer — self-conversation refusal")
class ToolLoopResumerSelfConversationTest {

    private static final String CONVERSATION_ID = "68f1c0ffee0000000000beef";
    private static final String OTHER_CONVERSATION_ID = "aaaabbbbccccddddeeeeffff";

    private ToolLoopResumer resumer;

    @BeforeEach
    void setUp() {
        resumer = new ToolLoopResumer(mock(AgentOrchestrator.class), mock(ToolLoopRunner.class),
                mock(ToolApprovalGateSupport.class), mock(ChatTranscriptCodec.class), mock(IHitlToolJournalStore.class));
    }

    private PendingToolCallBatch.PendingToolCall call(String arguments) {
        var c = new PendingToolCallBatch.PendingToolCall();
        c.setCallId("call-1");
        c.setToolName("say");
        c.setArgumentsRaw(arguments);
        return c;
    }

    /** A resolver answering with a fixed target URI. */
    private Map<String, ToolRequestResolver> resolverFor(String uri) {
        return Map.of("say", request -> new ResolvedRequest("POST", uri, Map.of(), Map.of(), "{}", "fp-1"));
    }

    @Test
    @DisplayName("refuses a call whose resolved URI is the agent's own conversation")
    void refusesSelfTargetedCall() {
        String reason = resumer.targetsOwnConversation(call("{}"), null,
                resolverFor("https://eddi.example/agents/" + CONVERSATION_ID), CONVERSATION_ID);

        assertNotNull(reason, "the laundering route must be refused in the engine, not only in the UI");
        assertTrue(reason.contains("running in"), reason);
    }

    /**
     * The capability this whole feature exists for: an operator test-driving
     * ANOTHER agent. Over-blocking here would remove it entirely.
     */
    @Test
    @DisplayName("allows a call to a different conversation")
    void allowsOtherConversation() {
        assertNull(resumer.targetsOwnConversation(call("{}"), null,
                resolverFor("https://eddi.example/agents/" + OTHER_CONVERSATION_ID), CONVERSATION_ID));
    }

    /**
     * An amended call is still checked. The approver may rewrite the arguments, and
     * rewriting them to point at the agent's own conversation is exactly the move
     * this refuses — unlike the fingerprint check, which must skip amendments
     * because it has no matching baseline for them.
     */
    @Test
    @DisplayName("checks the amended arguments, not the originals")
    void checksAmendedArguments() {
        var resolvers = Map.<String, ToolRequestResolver>of("say",
                request -> new ResolvedRequest("POST", "https://eddi.example/agents/" + request.arguments(),
                        Map.of(), Map.of(), "{}", "fp-1"));

        assertNotNull(resumer.targetsOwnConversation(call(OTHER_CONVERSATION_ID), CONVERSATION_ID,
                resolvers, CONVERSATION_ID));
    }

    /**
     * Unlike the fingerprint re-check, an unresolvable call is NOT waved through.
     * That method has nothing sound to compare when a call was never pinned; this
     * one enforces an absolute rule that needs no baseline, so it falls back to the
     * arguments the request would be built from.
     */
    @Test
    @DisplayName("falls back to the raw arguments when there is no resolver")
    void fallsBackToArgumentsWithoutResolver() {
        String reason = resumer.targetsOwnConversation(
                call("{\"conversationId\":\"" + CONVERSATION_ID + "\"}"), null, Map.of(), CONVERSATION_ID);

        assertNotNull(reason, "an unresolvable call must not bypass the rule");
    }

    @Test
    @DisplayName("falls back to the raw arguments when the resolver throws")
    void fallsBackWhenResolverThrows() {
        Map<String, ToolRequestResolver> throwing = Map.of("say", request -> {
            throw new IllegalStateException("cannot resolve");
        });

        assertNotNull(resumer.targetsOwnConversation(
                call("{\"conversationId\":\"" + CONVERSATION_ID + "\"}"), null, throwing, CONVERSATION_ID));
    }

    @Test
    @DisplayName("does nothing without a conversation id")
    void toleratesMissingConversationId() {
        // The dangerous direction: "" is a substring of every URI, so a missing id
        // must not refuse every approved call on the platform.
        assertNull(resumer.targetsOwnConversation(call("{}"), null,
                resolverFor("https://eddi.example/agents/" + OTHER_CONVERSATION_ID), ""));
        assertNull(resumer.targetsOwnConversation(call("{}"), null,
                resolverFor("https://eddi.example/agents/" + OTHER_CONVERSATION_ID), null));
    }

    @Test
    @DisplayName("matches through casing and percent-encoding")
    void matchesThroughEncoding() {
        // Same asymmetry the Manager-side guard documents: a false positive costs
        // one refused approval, a false negative costs the boundary.
        assertTrue(ToolLoopResumer.uriTargetsConversation(
                "https://x/agents/" + CONVERSATION_ID.toUpperCase(), CONVERSATION_ID));
        assertTrue(ToolLoopResumer.uriTargetsConversation(
                "https://x/agents%2F" + CONVERSATION_ID, CONVERSATION_ID));
        // A malformed escape must not silently disable the check.
        assertTrue(ToolLoopResumer.uriTargetsConversation(
                "https://x/a%/agents/" + CONVERSATION_ID, CONVERSATION_ID));
        assertFalse(ToolLoopResumer.uriTargetsConversation("https://x/agents/" + OTHER_CONVERSATION_ID, CONVERSATION_ID));
    }

    /**
     * The LIVE-path form. The resume-path check alone coupled the rule to the gate
     * configuration: a call an inert or non-matching gate let straight through
     * executed with no self-conversation check anywhere in the engine. These pin
     * the live variant so the boundary survives without a pause.
     */
    @Test
    @DisplayName("live path: refuses an ungated call whose resolved URI is the agent's own conversation")
    void livePathRefusesSelfTargetedCall() {
        var request = ToolExecutionRequest.builder()
                .name("say").arguments("{}").build();

        String reason = ToolLoopResumer.targetsOwnConversationLive(request,
                resolverFor("https://eddi.example/agents/" + CONVERSATION_ID), CONVERSATION_ID);

        assertNotNull(reason, "the rule is absolute — it must hold without a pause");
    }

    @Test
    @DisplayName("live path: allows an ungated call to a different conversation")
    void livePathAllowsOtherConversation() {
        var request = ToolExecutionRequest.builder()
                .name("say").arguments("{}").build();

        assertNull(ToolLoopResumer.targetsOwnConversationLive(request,
                resolverFor("https://eddi.example/agents/" + OTHER_CONVERSATION_ID), CONVERSATION_ID));
    }

    @Test
    @DisplayName("live path: falls back to the raw arguments when no resolver exists")
    void livePathFallsBackToArguments() {
        var request = ToolExecutionRequest.builder()
                .name("say").arguments("{\"conversationId\":\"" + CONVERSATION_ID + "\"}").build();

        assertNotNull(ToolLoopResumer.targetsOwnConversationLive(request, Map.of(), CONVERSATION_ID));
    }

    @Test
    @DisplayName("live path: tolerates a null resolver map and a blank conversation id")
    void livePathToleratesMissingInputs() {
        var request = ToolExecutionRequest.builder()
                .name("say").arguments("{}").build();

        assertNull(ToolLoopResumer.targetsOwnConversationLive(request, null, null));
        assertNull(ToolLoopResumer.targetsOwnConversationLive(request, null, " "));
    }

    @Test
    @DisplayName("an MCP call is judged by its arguments too: the server URL is not the conversation it writes to")
    void mcpEnvelopeArgumentsAreChecked() {
        var request = ToolExecutionRequest.builder().name("talk_to_agent")
                .arguments("{\"conversationId\":\"" + CONVERSATION_ID + "\",\"message\":\"hi\"}").build();
        var mcp = Map.of("talk_to_agent", RemoteToolRequestResolvers.forMcp("https://eddi.example/mcp", "talk_to_agent", false));

        assertNotNull(ToolLoopResumer.targetsOwnConversationLive(request, mcp, CONVERSATION_ID));

        var other = ToolExecutionRequest.builder().name("talk_to_agent")
                .arguments("{\"conversationId\":\"" + OTHER_CONVERSATION_ID + "\"}").build();
        assertNull(ToolLoopResumer.targetsOwnConversationLive(other, mcp, CONVERSATION_ID));
    }

    /**
     * The id with its second character ('8', 0x38) written as a JSON unicode
     * escape, so the raw text holds no copy of the id. Built from parts so neither
     * javac nor the formatter decodes it.
     */
    private static String jsonEscapedOwnId() {
        return CONVERSATION_ID.charAt(0) + "\\" + "u0038" + CONVERSATION_ID.substring(2);
    }

    @Test
    @DisplayName("an id written with JSON escapes is still the agent's own conversation — the executor decodes it")
    void jsonEscapedIdIsRefused() {
        String args = "{\"conversationId\":\"" + jsonEscapedOwnId() + "\",\"message\":\"hi\"}";
        assertFalse(args.contains(CONVERSATION_ID), "the raw arguments must not already name the id");
        var builtin = ToolExecutionRequest.builder().id("c1").name("converse_with_agent").arguments(args).build();

        assertNotNull(ToolLoopResumer.targetsOwnConversationLive(builtin, Map.of(), CONVERSATION_ID), "live path, no resolver");
        assertNotNull(resumer.targetsOwnConversation(call(args), null, Map.of(), CONVERSATION_ID), "resume path, no resolver");
    }

    /**
     * Two argument strings no JSON parser accepts, which langchain4j still
     * executes: when its first parse fails,
     * {@code ToolExecutionRequestUtil.argumentsAsMap} strips one pair of outer
     * quotes and turns every backslash-quote into a quote before parsing again.
     * Built-in tools ({@code converse_with_agent}) take their arguments that way.
     */
    private static List<String> langchain4jFallbackShapes() {
        String escapedQuote = "\\" + "\"";
        String json = "{\"agentId\":\"x\",\"message\":\"hi\",\"conversationId\":\"" + jsonEscapedOwnId() + "\"}";
        return List.of(json.replace("\"", escapedQuote), "\"" + json + "\"");
    }

    @Test
    @DisplayName("arguments only langchain4j's fallback parse reads are refused on both paths when they name the own conversation")
    void langchain4jFallbackShapesAreRefused() throws Exception {
        Method argumentsAsMap = Class.forName("dev.langchain4j.service.tool.ToolExecutionRequestUtil")
                .getDeclaredMethod("argumentsAsMap", String.class);
        argumentsAsMap.setAccessible(true);
        for (String args : langchain4jFallbackShapes()) {
            assertFalse(args.contains(CONVERSATION_ID), "the raw arguments must not already name the id: " + args);
            assertEquals(CONVERSATION_ID, ((Map<?, ?>) argumentsAsMap.invoke(null, args)).get("conversationId"),
                    "langchain4j executes the call with the agent's own conversation id: " + args);

            assertTrue(ToolLoopResumer.argumentsTargetConversation(args, CONVERSATION_ID), args);
            var builtin = ToolExecutionRequest.builder().id("c1").name("converse_with_agent").arguments(args).build();
            assertNotNull(ToolLoopResumer.targetsOwnConversationLive(builtin, Map.of(), CONVERSATION_ID), "live path: " + args);
            assertNotNull(resumer.targetsOwnConversation(call(args), null, Map.of(), CONVERSATION_ID), "resume path: " + args);
        }
    }

    @Test
    @DisplayName("the same shapes naming another conversation still run")
    void langchain4jFallbackShapesForAnotherConversationPass() {
        for (String args : langchain4jFallbackShapes()) {
            String other = args.replace(jsonEscapedOwnId(), OTHER_CONVERSATION_ID);
            assertFalse(ToolLoopResumer.argumentsTargetConversation(other, CONVERSATION_ID), other);
        }
    }

    @Test
    @DisplayName("an MCP envelope's JSON-escaped target id is refused too, while another conversation's passes")
    void jsonEscapedIdInEnvelopeIsRefused() {
        var mcp = Map.of("talk_to_agent", RemoteToolRequestResolvers.forMcp("https://eddi.example/mcp", "talk_to_agent", false));
        var own = ToolExecutionRequest.builder().id("c1").name("talk_to_agent")
                .arguments("{\"conversationId\":\"" + jsonEscapedOwnId() + "\"}").build();
        var other = ToolExecutionRequest.builder().id("c2").name("talk_to_agent")
                .arguments("{\"conversationId\":\"\\" + "u0061" + OTHER_CONVERSATION_ID.substring(1) + "\"}").build();

        assertNotNull(ToolLoopResumer.targetsOwnConversationLive(own, mcp, CONVERSATION_ID));
        assertNull(ToolLoopResumer.targetsOwnConversationLive(other, mcp, CONVERSATION_ID));
    }
}
