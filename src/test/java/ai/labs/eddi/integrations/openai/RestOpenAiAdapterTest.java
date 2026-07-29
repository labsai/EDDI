/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static ai.labs.eddi.integrations.openai.OpenAiTestFixtures.AGENT_ID_SUPPORT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the request-shaping logic in {@link RestOpenAiAdapter}.
 * <p>
 * Deliberately not a {@code @QuarkusTest} — these need no HTTP server, and
 * socket-binding tests are not runnable in the local sandbox. The wire-level
 * behaviour of the endpoint (notably that {@code stream:true} produces
 * {@code text/event-stream}) still needs an integration test in CI.
 */
class RestOpenAiAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentModelResolver.ResolvedModel stateful = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support",
            "support-a3f9c1", "support-a3f9c1", 0L, false);

    private final AgentModelResolver.ResolvedModel stateless = new AgentModelResolver.ResolvedModel(
            AGENT_ID_SUPPORT, Environment.production, "Support",
            "support-a3f9c1:stateless", "support-a3f9c1:stateless", 0L, true);

    private RestOpenAiAdapter adapter(OpenAiCompatConfig config) {
        return new RestOpenAiAdapter(mock(AgentModelResolver.class), mock(OpenAiConversationBridge.class),
                config, objectMapper);
    }

    private ChatCompletionRequest parse(String json) throws Exception {
        return objectMapper.readValue(json, ChatCompletionRequest.class);
    }

    // ─── binding ───

    @Test
    void statelessField_bindsFromTheBody() throws Exception {
        assertTrue(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"stateless":true}""").isStateless());
    }

    @Test
    void statelessField_defaultsToFalseWhenAbsentOrNull() throws Exception {
        assertFalse(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""").isStateless());
        assertFalse(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"stateless":null}""").isStateless());
        assertFalse(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"stateless":false}""").isStateless());
    }

    // ─── override semantics ───

    @Test
    void bodyFieldForcesStateless_onAStatefulModel() throws Exception {
        var request = parse("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}],"stateless":true}""");

        var result = adapter(OpenAiTestFixtures.enabledConfig()).applyStatelessOverride(stateful, request);

        assertTrue(result.stateless());
        assertEquals("support-a3f9c1:stateless", result.canonicalModelId());
    }

    @Test
    void absentBodyField_leavesTheModelUntouched() throws Exception {
        var request = parse("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}]}""");

        assertSame(stateful, adapter(OpenAiTestFixtures.enabledConfig()).applyStatelessOverride(stateful, request));
    }

    @Test
    void statelessFalse_doesNotUndoTheSuffix() throws Exception {
        // The combination is self-contradictory. Of the two readings, running
        // stateless only loses continuity, while running stateful would persist a
        // conversation the caller may not have wanted — so the flags are OR-ed.
        var request = parse("""
                {"model":"support-a3f9c1:stateless","messages":[{"role":"user","content":"Hi"}],
                 "stateless":false}""");

        var result = adapter(OpenAiTestFixtures.enabledConfig()).applyStatelessOverride(stateless, request);

        assertTrue(result.stateless());
    }

    @Test
    void bothRoutesProduceTheSameModel() throws Exception {
        var viaBody = parse("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}],"stateless":true}""");

        var fromBody = adapter(OpenAiTestFixtures.enabledConfig()).applyStatelessOverride(stateful, viaBody);

        assertEquals(stateless.agentId(), fromBody.agentId());
        assertEquals(stateless.canonicalModelId(), fromBody.canonicalModelId());
        assertEquals(stateless.stateless(), fromBody.stateless());
    }

    @Test
    void nullRequest_isTolerated() {
        assertSame(stateful, adapter(OpenAiTestFixtures.enabledConfig()).applyStatelessOverride(stateful, null));
    }

    // ─── the config gate applies to both routes ───

    @Test
    void bodyField_isRejectedWhenStatelessVariantsAreDisabled() throws Exception {
        // Otherwise the kill switch could be circumvented by moving the request
        // out of the model id and into the body.
        var config = OpenAiTestFixtures.config(b -> b.exposeStatelessVariants = false);
        var request = parse("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}],"stateless":true}""");

        var exception = assertThrows(OpenAiApiException.class,
                () -> adapter(config).applyStatelessOverride(stateful, request));

        assertEquals(400, exception.getStatus());
        assertTrue(exception.getMessage().contains("expose-stateless-variants"),
                "the error must name the setting that caused it");
    }

    @Test
    void disabledVariants_stillAllowOrdinaryStatefulRequests() throws Exception {
        var config = OpenAiTestFixtures.config(b -> b.exposeStatelessVariants = false);
        var request = parse("""
                {"model":"support-a3f9c1","messages":[{"role":"user","content":"Hi"}]}""");

        assertSame(stateful, adapter(config).applyStatelessOverride(stateful, request));
    }
}
