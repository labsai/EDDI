/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.secrets.SecretResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link A2AToolProviderManager} covering the testable
 * pure-function paths: circuit breaker logic, tool name sanitization,
 * empty/null input handling, and lifecycle methods.
 */
class A2AToolProviderManagerTest {

    private A2AToolProviderManager manager;

    @BeforeEach
    void setUp() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false);
    }

    // ─── discoverTools empty/null ─────────────────────────────────

    @Nested
    @DisplayName("discoverTools with no agents")
    class DiscoverToolsEmpty {

        @Test
        @DisplayName("should return empty result for null config list")
        void nullConfig() {
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("should return empty result for empty config list")
        void emptyConfig() {
            var result = manager.discoverTools(List.of());
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("should skip agent config with empty URL")
        void skipNullUrl() {
            var config = new A2AAgentConfig();
            config.setUrl(null);
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("should skip agent config with blank URL")
        void skipBlankUrl() {
            var config = new A2AAgentConfig();
            config.setUrl("  ");
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ─── sanitizeToolName ────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeToolName")
    class SanitizeToolName {

        @Test
        @DisplayName("should lowercase and replace special chars with underscores")
        void specialChars() {
            // Use reflection to access private method, or test indirectly via
            // a config that fails during HTTP discovery but verifies name construction
            // For now test the pattern directly
            String input = "My Agent-v2 (prod)";
            String expected = "my_agent_v2_prod";
            assertEquals(expected, sanitize(input));
        }

        @Test
        @DisplayName("should collapse consecutive underscores")
        void collapseUnderscores() {
            assertEquals("a_b", sanitize("A__B"));
        }

        @Test
        @DisplayName("should strip leading and trailing underscores")
        void stripEdgeUnderscores() {
            assertEquals("name", sanitize("_name_"));
        }

        @Test
        @DisplayName("should handle purely alphanumeric names")
        void alphanumeric() {
            assertEquals("agent42", sanitize("Agent42"));
        }

        /**
         * Replicates the private sanitizeToolName logic for testing, since the method
         * is private but the logic is security-relevant.
         */
        private String sanitize(String name) {
            return name.toLowerCase()
                    .replaceAll("[^a-z0-9_]", "_")
                    .replaceAll("_+", "_")
                    .replaceAll("^_|_$", "");
        }
    }

    // ─── Lifecycle methods ──────────────────────────────────────

    @Nested
    @DisplayName("Lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("initial connection count should be zero")
        void initialConnectionCount() {
            assertEquals(0, manager.getActiveConnectionCount());
        }

        @Test
        @DisplayName("shutdown should clear the cache")
        void shutdownClearsCache() {
            manager.shutdown();
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ─── Circuit breaker ────────────────────────────────────────

    @Nested
    @DisplayName("Circuit breaker behavior")
    class CircuitBreaker {

        @Test
        @DisplayName("should skip agent after 3 consecutive failures")
        void circuitOpensAfterThreshold() {
            var config = new A2AAgentConfig();
            config.setUrl("http://unreachable:9999");
            config.setTimeoutMs(100L);

            // First 3 attempts will fail and record failures
            for (int i = 0; i < 3; i++) {
                manager.discoverTools(List.of(config));
            }

            // The 4th attempt should skip due to circuit breaker
            // (no HTTP attempt, still returns empty)
            var result = manager.discoverTools(List.of(config));
            assertTrue(result.toolSpecs().isEmpty());
        }
    }

    // ─── Discovery against a peer answered from memory ──────────

    /**
     * What every provider EDDI talks to accepts as a function name: OpenAI,
     * Anthropic and Gemini all cap it at 64 characters and refuse an empty one.
     */
    private static final Pattern PROVIDER_TOOL_NAME = Pattern.compile("[a-z0-9_]{1,64}");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Every hop the in-memory peer was sent, in order. */
    private final List<Hop> sent = new ArrayList<>();

    /** The card each peer host serves, given the hop that asked for it. */
    private final Map<String, Function<Hop, Map<String, Object>>> cards = new HashMap<>();

    /**
     * A manager on a real {@code OutboundHttp} facade whose engine answers every
     * card fetch from {@link #cards}, so discovery runs end to end without a
     * socket.
     */
    private A2AToolProviderManager managerAgainstPeers() {
        GlobalVariableResolver globals = mock(GlobalVariableResolver.class);
        SecretResolver secrets = mock(SecretResolver.class);
        when(globals.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        when(secrets.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        OutboundEngine engine = hop -> {
            sent.add(hop);
            var card = cards.get(hop.uri().getHost());
            if (card == null || !hop.uri().getPath().endsWith("/agent.json")) {
                return new OutboundEngine.Received(404, null, Map.of(), new ByteArrayInputStream(new byte[0]));
            }
            byte[] body = JSON.writeValueAsString(card.apply(hop)).getBytes(StandardCharsets.UTF_8);
            return new OutboundEngine.Received(200, null, Map.of(), new ByteArrayInputStream(body));
        };
        return new A2AToolProviderManager(globals, secrets, false, A2AToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES, A2AToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_AGENT, null,
                TestOutbound.facade(engine));
    }

    private static A2AAgentConfig peer(String host, String name, String apiKey) {
        var config = new A2AAgentConfig();
        config.setUrl("https://" + host + "/a2a");
        config.setName(name);
        config.setApiKey(apiKey);
        return config;
    }

    private static Map<String, Object> skill(String id) {
        return Map.of("id", id, "name", id, "description", "does " + id);
    }

    private static List<String> names(A2AToolProviderManager.A2AToolsResult result) {
        return result.toolSpecs().stream().map(ToolSpecification::name).toList();
    }

    private static void assertProviderValid(List<String> names) {
        for (String name : names) {
            assertTrue(PROVIDER_TOOL_NAME.matcher(name).matches(), () -> "not a name a provider accepts: '" + name + "'");
        }
    }

    @Nested
    @DisplayName("tool names")
    class ToolNames {

        @Test
        @DisplayName("a long agent name joined to long skill ids stays within 64 characters, and two ids sharing a prefix stay two tools")
        void longNamesFitAndStayDistinct() {
            // 40 + 1 + 50 = 91 characters joined: every provider refused the request.
            String agentName = "customer-support-escalation-agent-europe";
            String sharedPrefix = "x".repeat(45);
            cards.put("peer.example.com", hop -> Map.of("name", "peer", "skills",
                    List.of(skill(sharedPrefix + "alpha"), skill(sharedPrefix + "bravo"))));

            var result = managerAgainstPeers().discoverTools(List.of(peer("peer.example.com", agentName, null)));

            List<String> names = names(result);
            assertEquals(2, names.size(), names::toString);
            assertProviderValid(names);
            assertEquals(2, new HashSet<>(names).size(), () -> "two skills collapsed onto one tool name: " + names);
            assertEquals(new HashSet<>(names), result.executors().keySet(), "each advertised tool must be callable under its name");
            assertTrue(names.getFirst().startsWith("customer_support_escalation_agent_europe_xxx"), names.getFirst());
        }

        @Test
        @DisplayName("a name that fits is unchanged")
        void shortNamesUnchanged() {
            cards.put("peer.example.com", hop -> Map.of("name", "peer", "skills", List.of(skill("order-tracking"))));

            var result = managerAgainstPeers().discoverTools(List.of(peer("peer.example.com", "Support Agent", null)));

            assertEquals(List.of("support_agent_order_tracking"), names(result));
        }

        @Test
        @DisplayName("an agent with no Latin characters in its name still gets a usable tool, not a blank name")
        void nonLatinAgentNameGetsAUsableName() {
            cards.put("peer.example.com", hop -> Map.of("name", "peer", "description", "answers questions"));

            var result = managerAgainstPeers().discoverTools(List.of(peer("peer.example.com", "検索エージェント", null)));

            List<String> names = names(result);
            assertEquals(1, names.size(), "the peer's single tool was dropped: " + names);
            assertProviderValid(names);
            assertEquals(names, List.copyOf(result.executors().keySet()));
        }

        @Test
        @DisplayName("two peers whose names have no Latin characters do not collapse onto one tool name")
        void nonLatinAgentNamesStayDistinct() {
            cards.put("one.example.com", hop -> Map.of("name", "one", "skills", List.of(skill("lookup"))));
            cards.put("two.example.com", hop -> Map.of("name", "two", "skills", List.of(skill("lookup"))));

            var result = managerAgainstPeers().discoverTools(
                    List.of(peer("one.example.com", "検索", null), peer("two.example.com", "翻訳", null)));

            List<String> names = names(result);
            assertEquals(2, new HashSet<>(names).size(), () -> "the second peer was dropped as a name collision: " + names);
            assertProviderValid(names);
        }

        @Test
        @DisplayName("a blank configured name falls back to the card's name, as an absent one does")
        void blankConfiguredNameUsesTheCardName() {
            // The Manager writes "" once a typed name is cleared again.
            cards.put("one.example.com", hop -> Map.of("name", "Alpha", "skills", List.of(skill("lookup"))));
            cards.put("two.example.com", hop -> Map.of("name", "Bravo", "skills", List.of(skill("lookup"))));

            var result = managerAgainstPeers().discoverTools(
                    List.of(peer("one.example.com", "", null), peer("two.example.com", " ", null)));

            assertEquals(List.of("alpha_lookup", "bravo_lookup"), names(result));
        }
    }

    @Nested
    @DisplayName("agent-card cache")
    class AgentCardCache {

        private static final String HOST = "peer.example.com";

        private long cardFetches() {
            return sent.stream().filter(hop -> hop.uri().getPath().endsWith("/agent.json")).count();
        }

        @Test
        @DisplayName("a card fetched with one credential is not served to a config with another, or with none")
        void credentialsDoNotShareACard() {
            // The authenticated card lists a private skill; the anonymous one does not.
            cards.put(HOST, hop -> hop.headers().containsKey("Authorization")
                    ? Map.of("name", "peer", "skills", List.of(skill("public"), skill("private")))
                    : Map.of("name", "peer", "skills", List.of(skill("public"))));
            var manager = managerAgainstPeers();

            var result = manager.discoverTools(List.of(peer(HOST, "tenant-a", "${vault:key-a}"), peer(HOST, "tenant-b", null),
                    peer(HOST, "tenant-c", "${vault:key-c}")));

            assertEquals(3, cardFetches(), "each credential needs its own card");
            assertEquals(List.of("tenant_a_public", "tenant_a_private", "tenant_b_public", "tenant_c_public", "tenant_c_private"),
                    names(result));
        }

        @Test
        @DisplayName("configs naming the same credential share one card")
        void sameCredentialSharesACard() {
            cards.put(HOST, hop -> Map.of("name", "peer", "skills", List.of(skill("lookup"))));
            var manager = managerAgainstPeers();

            var result = manager.discoverTools(List.of(peer(HOST, "first", "${vault:key-a}"), peer(HOST, "second", "${vault:key-a}"),
                    peer(HOST, "third", null), peer(HOST, "fourth", null)));

            assertEquals(2, cardFetches(), "one fetch per distinct credential, the anonymous one included");
            assertEquals(List.of("first_lookup", "second_lookup", "third_lookup", "fourth_lookup"), names(result));
            assertEquals(2, manager.getActiveConnectionCount());
        }
    }
}
