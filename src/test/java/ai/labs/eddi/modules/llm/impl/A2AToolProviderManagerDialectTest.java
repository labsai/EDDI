/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.impl.A2AToolProviderManager.RemoteDialect;
import ai.labs.eddi.modules.llm.impl.A2AToolProviderManager.RemoteEndpoint;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * The outbound A2A client speaks the dialect a peer's Agent Card announces, and
 * reads results in all three shapes — against the fixtures in
 * {@code src/test/resources/tests/a2a/client-responses.json}, two of which were
 * captured from a stock EDDI 6.5.0.
 */
@DisplayName("A2AToolProviderManager — protocol dialects")
class A2AToolProviderManagerDialectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture(String key) throws IOException {
        try (InputStream in = A2AToolProviderManagerDialectTest.class.getResourceAsStream("/tests/a2a/client-responses.json")) {
            return (Map<String, Object>) MAPPER.readValue(in, Map.class).get(key);
        }
    }

    @Nested
    @DisplayName("reading results")
    class ReadResult {

        @Test
        void v10Completed_returnsTheArtifactText() throws Exception {
            assertEquals("Sunny, 75°F", A2AToolProviderManager.readResult(fixture("v1_0-completed")));
        }

        @Test
        @DisplayName("a failed task is reported as failed, never as the answer")
        void v10Failed_isReportedAsFailed() throws Exception {
            assertEquals("A2A agent task failed: upstream model unavailable", A2AToolProviderManager.readResult(fixture("v1_0-failed")));
        }

        @Test
        void v10DirectMessage_returnsItsText() throws Exception {
            assertEquals("a direct reply", A2AToolProviderManager.readResult(fixture("v1_0-message")));
        }

        @Test
        void v10Working_saysThereIsNoResultYet() throws Exception {
            String text = A2AToolProviderManager.readResult(fixture("v1_0-working"));
            assertTrue(text.startsWith("A2A agent task is still working"), text);
        }

        @Test
        void v03Completed_returnsTheArtifactText() throws Exception {
            assertEquals("Order shipped", A2AToolProviderManager.readResult(fixture("v0_3-completed")));
        }

        @Test
        void v03InputRequired_saysSo() throws Exception {
            assertEquals("A2A agent task needs more input: Which order?", A2AToolProviderManager.readResult(fixture("v0_3-input-required")));
        }

        @Test
        @DisplayName("a stock EDDI 6.5.0 result — bare-enum status, type parts — still reads")
        void legacyEddi65_stillReads() throws Exception {
            assertEquals("hello back", A2AToolProviderManager.readResult(fixture("legacy-eddi-6.5")));
        }

        @Test
        void normalizeState() {
            assertEquals("input_required", A2AToolProviderManager.normalizeState("TASK_STATE_INPUT_REQUIRED"));
            assertEquals("input_required", A2AToolProviderManager.normalizeState("input-required"));
            assertEquals("canceled", A2AToolProviderManager.normalizeState("cancelled"));
            assertEquals("", A2AToolProviderManager.normalizeState(null));
        }
    }

    @Nested
    @DisplayName("choosing the dialect")
    class ResolveEndpoint {

        @Test
        void aCardListingA10InterfaceIsCalledIn10() throws Exception {
            assertEquals(new RemoteEndpoint("http://peer/a2a", RemoteDialect.V1_0),
                    A2AToolProviderManager.resolveEndpoint("http://peer/a2a", fixture("card-v1_0")));
        }

        @Test
        void aCardWithAProtocolVersionIsCalledIn03() throws Exception {
            assertEquals(RemoteDialect.V0_3, A2AToolProviderManager.resolveEndpoint("http://peer/a2a", fixture("card-v0_3")).dialect());
        }

        @Test
        @DisplayName("a stock EDDI 6.5.0 card is called with the legacy tasks/send")
        void anEddi65CardIsLegacy() throws Exception {
            assertEquals(RemoteDialect.LEGACY, A2AToolProviderManager.resolveEndpoint("http://peer/a2a", fixture("card-eddi-6.5")).dialect());
        }

        @Test
        @DisplayName("the configured URL stays the endpoint — a card cannot redirect the call")
        void configuredUrlWins() throws Exception {
            assertEquals("http://configured/a2a",
                    A2AToolProviderManager.resolveEndpoint("http://configured/a2a", fixture("card-v1_0")).url());
        }

        @Test
        @DisplayName("…unless the configured URL is the card document itself")
        void cardUrlConfigured_usesTheCardsInterface() throws Exception {
            assertEquals("https://georoute-agent.example.com/a2a/v1",
                    A2AToolProviderManager.resolveEndpoint("https://georoute-agent.example.com/.well-known/agent-card.json", fixture("card-v1_0"))
                            .url());
        }

        @Test
        @DisplayName("a card document on one origin cannot route the call (and the credential) to another")
        void cardUrlConfigured_refusesAForeignOrigin() throws Exception {
            assertThrows(IllegalArgumentException.class,
                    () -> A2AToolProviderManager.resolveEndpoint("https://trusted.example.com/.well-known/agent-card.json", fixture("card-v1_0")));
        }

        @Test
        void cardCandidates() {
            assertEquals(List.of("http://p/a/agent.json", "http://p/a/.well-known/agent-card.json", "http://p/a/.well-known/agent.json"),
                    A2AToolProviderManager.cardUrlCandidates("http://p/a"));
            assertEquals(List.of("http://p/card.json"), A2AToolProviderManager.cardUrlCandidates("http://p/card.json"));
        }
    }

    @Nested
    @DisplayName("writing requests")
    class BuildRequest {

        @Test
        @SuppressWarnings("unchecked")
        void v10() {
            var request = A2AToolProviderManager.buildSendRequest(RemoteDialect.V1_0, "hi");
            assertEquals("SendMessage", request.get("method"));
            var message = (Map<String, Object>) ((Map<String, Object>) request.get("params")).get("message");
            assertEquals("ROLE_USER", message.get("role"));
            assertEquals(List.of(Map.of("text", "hi")), message.get("parts"));
            assertTrue(message.containsKey("messageId"));
            assertFalse(message.containsKey("kind"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void v03() {
            var request = A2AToolProviderManager.buildSendRequest(RemoteDialect.V0_3, "hi");
            assertEquals("message/send", request.get("method"));
            var message = (Map<String, Object>) ((Map<String, Object>) request.get("params")).get("message");
            assertEquals("message", message.get("kind"));
            assertEquals(List.of(Map.of("kind", "text", "text", "hi")), message.get("parts"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void legacy() {
            var request = A2AToolProviderManager.buildSendRequest(RemoteDialect.LEGACY, "hi");
            assertEquals("tasks/send", request.get("method"));
            var params = (Map<String, Object>) request.get("params");
            assertTrue(params.containsKey("id"));
            assertEquals(List.of(Map.of("type", "text", "text", "hi")), ((Map<String, Object>) params.get("message")).get("parts"));
        }
    }

    @Nested
    @DisplayName("over HTTP")
    class OverHttp {

        private HttpServer server;
        private A2AToolProviderManager manager;
        private final List<String> methods = new CopyOnWriteArrayList<>();
        private final List<String> versionHeaders = new CopyOnWriteArrayList<>();
        private final List<String> cardPaths = new CopyOnWriteArrayList<>();

        @BeforeEach
        void setUp() throws IOException {
            var globals = mock(GlobalVariableResolver.class);
            var secrets = mock(SecretResolver.class);
            doReturn("k").when(globals).resolveValue(anyString());
            doReturn("k").when(secrets).resolveValue(anyString());
            manager = new A2AToolProviderManager(globals, secrets, false);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }

        @AfterEach
        void tearDown() {
            server.stop(0);
            manager.shutdown();
        }

        private void serve(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(body);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        /**
         * A peer that publishes its card only at the well-known path and answers in its
         * own dialect.
         */
        @SuppressWarnings("unchecked")
        private String peer(String cardFixture, String resultFixture, boolean wellKnownOnly) throws IOException {
            Map<String, Object> card = fixture(cardFixture);
            Map<String, Object> result = fixture(resultFixture);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(exchange.getRequestMethod())) {
                    cardPaths.add(path);
                    boolean isCard = wellKnownOnly ? path.endsWith("/.well-known/agent-card.json") : path.endsWith("/agent.json");
                    if (isCard) {
                        serve(exchange, 200, card);
                    } else {
                        serve(exchange, 404, Map.of());
                    }
                    return;
                }
                var request = MAPPER.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
                methods.add((String) request.get("method"));
                versionHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("A2A-Version")));
                serve(exchange, 200, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            });
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
        }

        private String call(String url) throws Exception {
            var config = new A2AAgentConfig();
            config.setUrl(url);
            config.setName("peer");
            config.setTimeoutMs(5000L);
            var tools = manager.discoverTools(List.of(config));
            assertFalse(tools.toolSpecs().isEmpty(), "no tool discovered");
            String name = tools.toolSpecs().getFirst().name();
            return tools.executors().get(name).execute(ToolExecutionRequest.builder().name(name).arguments("{\"message\":\"hi\"}").build(), null);
        }

        @Test
        @DisplayName("a 1.0 peer found at the well-known path is called with SendMessage and A2A-Version: 1.0")
        void v10Peer() throws Exception {
            String answer = call(peer("card-v1_0", "v1_0-completed", true));

            assertEquals("Sunny, 75°F", answer);
            assertEquals(List.of("SendMessage"), methods);
            assertEquals(List.of("1.0"), versionHeaders);
            assertTrue(cardPaths.contains("/a2a/.well-known/agent-card.json"), cardPaths.toString());
        }

        @Test
        @DisplayName("a stock EDDI 6.5.0 peer is called with tasks/send and no version header")
        void legacyPeer() throws Exception {
            String answer = call(peer("card-eddi-6.5", "legacy-eddi-6.5", false));

            assertEquals("hello back", answer);
            assertEquals(List.of("tasks/send"), methods);
            assertEquals(List.of("null"), versionHeaders);
        }

        @Test
        @DisplayName("a failed task from a 0.3 peer reaches the model as a failure")
        void failedTask() throws Exception {
            String answer = call(peer("card-v0_3", "v1_0-failed", false));

            assertEquals(List.of("message/send"), methods);
            assertTrue(answer.startsWith("A2A agent task failed"), answer);
            assertTrue(answer.contains("upstream model unavailable"), answer);
        }
    }
}
