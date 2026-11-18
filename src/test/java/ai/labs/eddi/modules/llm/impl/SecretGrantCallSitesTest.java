/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.impl.builder.ILanguageModelBuilder;
import ai.labs.eddi.modules.rag.RagIngestionService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretAccess;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.service.tool.ToolExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every cache that holds a client built from a vault secret checks the asking
 * agent's grant BEFORE its lookup (#1179): a client one agent built must not
 * serve another the grant leaves out.
 */
class SecretGrantCallSitesTest {

    static final String AGENT_A = "aaaaaaaaaaaaaaaaaaaaaaaa";
    static final String AGENT_B = "bbbbbbbbbbbbbbbbbbbbbbbb";
    static final SecretReference KEY = new SecretReference("default", "openai-key");
    static final String REF = "${vault:openai-key}";

    private ISecretProvider provider;
    private SecretResolver secretResolver;
    private GlobalVariableResolver variables;

    @BeforeEach
    void setUp() throws Exception {
        provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.resolve(KEY)).thenReturn("sk-plaintext");
        when(provider.getMetadata(KEY))
                .thenReturn(new SecretMetadata("default", "openai-key", Instant.now(), null, null, "c", null, List.of(AGENT_A)));
        secretResolver = new SecretResolver(provider, new SimpleMeterRegistry(), 5, 100, "enforce");
        secretResolver.init();
        variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(variables.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("ChatModelRegistry")
    class ChatModels {

        private final AtomicInteger builds = new AtomicInteger();
        private ChatModelRegistry registry;

        @BeforeEach
        void setUp() {
            Map<String, Provider<ILanguageModelBuilder>> builders = new HashMap<>();
            builders.put("openai", () -> new ILanguageModelBuilder() {
                @Override
                public ChatModel build(Map<String, String> parameters) {
                    builds.incrementAndGet();
                    return new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest chatRequest) {
                            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                        }
                    };
                }

                @Override
                public StreamingChatModel buildStreaming(Map<String, String> parameters) {
                    builds.incrementAndGet();
                    return new StreamingChatModel() {
                        @Override
                        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                        }
                    };
                }
            });
            registry = new ChatModelRegistry(builders, variables, secretResolver, null, null);
        }

        @Test
        @DisplayName("a model cached for the granted agent is refused to an agent the grant leaves out")
        void cachedModelRefusedToUngrantedAgent() throws Exception {
            Map<String, String> params = Map.of("apiKey", REF, "modelName", "gpt-x");
            ChatModel forA = registry.getOrCreate("openai", params, SecretAccess.agent(AGENT_A));
            assertSame(forA, registry.getOrCreate("openai", params, SecretAccess.agent(AGENT_A)), "the cache is in use");

            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> registry.getOrCreate("openai", params, SecretAccess.agent(AGENT_B)));
            assertEquals(1, builds.get());
        }

        @Test
        void streamingModelChecksToo() throws Exception {
            Map<String, String> params = Map.of("apiKey", REF, "modelName", "gpt-x");
            assertNotNull(registry.getOrCreateStreaming("openai", params, SecretAccess.agent(AGENT_A)));
            assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> registry.getOrCreateStreaming("openai", params, SecretAccess.agent(AGENT_B)));
        }

        @Test
        @DisplayName("a ${vars:} that expands to a vault reference is checked as well")
        void variableIndirectionIsChecked() {
            when(variables.resolveAll(any())).thenAnswer(inv -> {
                Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
                resolved.replaceAll((k, v) -> "${vars:llm-key}".equals(v) ? REF : v);
                return resolved;
            });
            assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> registry.getOrCreate("openai", Map.of("apiKey", "${vars:llm-key}"), SecretAccess.agent(AGENT_B)));
        }

        @Test
        @DisplayName("a variable repointed to another secret is another cache key: the model built with the old one is not handed out")
        void repointedVariableMissesTheCache() throws Exception {
            var keyB = new SecretReference("default", "other-key");
            when(provider.resolve(keyB)).thenReturn("sk-other");
            when(provider.getMetadata(keyB))
                    .thenReturn(new SecretMetadata("default", "other-key", Instant.now(), null, null, "c", null, List.of(AGENT_A, AGENT_B)));
            Map<String, String> params = Map.of("apiKey", "${vars:llm-key}", "modelName", "gpt-x");
            var binding = new AtomicReference<>(REF);
            when(variables.resolveAll(any())).thenAnswer(inv -> {
                Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
                resolved.replaceAll((k, v) -> "${vars:llm-key}".equals(v) ? binding.get() : v);
                return resolved;
            });

            ChatModel builtWithA = registry.getOrCreate("openai", params, SecretAccess.agent(AGENT_A));
            binding.set("${vault:other-key}");
            ChatModel forB = registry.getOrCreate("openai", params, SecretAccess.agent(AGENT_B));

            assertNotSame(builtWithA, forB, "B is granted the new secret only, and must not get the model built with the old one");
            assertEquals(2, builds.get());
        }

        @Test
        void conversationMemoryNamesTheAgent() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getAgentId()).thenReturn(AGENT_B);
            assertEquals(SecretAccess.agent(AGENT_B), LlmTask.secretAccessOf(memory));
            assertEquals(SecretAccess.agent(null), LlmTask.secretAccessOf(null));
        }
    }

    @Nested
    @DisplayName("embedding model and store factories")
    class Embeddings {

        private RagConfiguration knowledgeBase() {
            var config = new RagConfiguration();
            config.setEmbeddingProvider("openai");
            config.setEmbeddingParameters(Map.of("apiKey", REF, "model", "text-embedding-3-small"));
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("host", "db.internal", "password", REF));
            return config;
        }

        @Test
        @DisplayName("an embedding model cached for the granted agent is refused to another")
        void embeddingModelCacheHitChecked() throws Exception {
            var factory = new EmbeddingModelFactory(variables, secretResolver);
            var config = knowledgeBase();
            assertSame(factory.getOrCreate(config, EmbeddingInputType.QUERY, SecretAccess.agent(AGENT_A)),
                    factory.getOrCreate(config, EmbeddingInputType.QUERY, SecretAccess.agent(AGENT_A)));
            assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> factory.getOrCreate(config, EmbeddingInputType.QUERY, SecretAccess.agent(AGENT_B)));
        }

        @Test
        @DisplayName("ingestion runs for no agent: a key granted to one agent only is refused")
        void ingestionNeedsTheWildcard() throws Exception {
            var factory = new EmbeddingModelFactory(variables, secretResolver);
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> factory.getOrCreate(knowledgeBase(), EmbeddingInputType.DOCUMENT, RagIngestionService.KNOWLEDGE_BASE_INGESTION));
            // Named by the pre-cache check, not by the resolution inside build().
            assertTrue(e.getMessage().contains("embedding model 'openai'"), e.getMessage());
            verify(provider, never()).resolve(KEY);
        }

        @Test
        @DisplayName("a vector store is refused before the cache, without opening anything")
        void vectorStoreRefusedBeforeTheCache() throws Exception {
            var factory = new EmbeddingStoreFactory(variables, secretResolver);
            var e = assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> factory.getOrCreate("0123456789abcdef01234567", knowledgeBase(), SecretAccess.agent(AGENT_B)));
            assertTrue(e.getMessage().contains("vector store 'pgvector'"), e.getMessage());
            var ingestion = assertThrows(SecretResolver.SecretNotGrantedException.class,
                    () -> factory.getOrCreateForIngestion("0123456789abcdef01234567", knowledgeBase(), SecretAccess.withoutAgent("ingestion")));
            assertTrue(ingestion.getMessage().contains("vector store 'pgvector'"), ingestion.getMessage());
            verify(provider, never()).resolve(KEY);
        }
    }

    @Nested
    @DisplayName("MCP and A2A tool assembly")
    class ToolClients {

        @Test
        @DisplayName("an MCP server whose key is not granted is refused at tool assembly, before the shared session")
        void mcpGrantChecked() {
            var manager = new McpToolProviderManager(variables, secretResolver);
            var config = new McpServerConfig();
            config.setName("weather");
            config.setUrl("https://mcp.example.com/mcp");
            config.setApiKey(REF);
            var e = assertThrows(IllegalArgumentException.class, () -> manager.validateSecretGrant(config, SecretAccess.agent(AGENT_B)));
            assertTrue(e.getMessage().contains("MCP server 'weather'"), e.getMessage());
            assertDoesNotThrow(() -> manager.validateSecretGrant(config, SecretAccess.agent(AGENT_A)));
        }

        @Test
        @DisplayName("discovery reports the refusal as a configuration failure and contacts nothing")
        void mcpDiscoveryRefuses() throws Exception {
            var manager = new McpToolProviderManager(variables, secretResolver);
            var config = new McpServerConfig();
            config.setName("weather");
            config.setUrl("https://mcp.example.com/mcp");
            config.setApiKey(REF);
            var result = manager.discoverTools(List.of(config), SecretAccess.agent(AGENT_B));
            assertTrue(result.toolSpecs().isEmpty());
            assertEquals(1, result.failures().size());
            assertEquals(McpToolProviderManager.McpFailureKind.INVALID_CONFIGURATION, result.failures().getFirst().kind());
            // The grant, not the URL (a sandbox without DNS fails the URL check the same
            // way).
            assertTrue(result.failures().getFirst().message().contains("allowedAgents"), result.failures().getFirst().message());
            verify(provider, never()).resolve(KEY);
        }

        @Test
        @DisplayName("an MCP tool handed to an agent reads that agent's grant again when it is called")
        void mcpToolCallChecksTheGrant() {
            var manager = new McpToolProviderManager(variables, secretResolver);
            var config = new McpServerConfig();
            config.setName("weather");
            config.setUrl("https://mcp.example.com/mcp");
            config.setApiKey(REF);
            var calls = new AtomicInteger();
            Map<String, ToolExecutor> executors = Map.of("weather_now", (request, memoryId) -> {
                calls.incrementAndGet();
                return "sunny";
            });
            var request = ToolExecutionRequest.builder().name("weather_now").arguments("{}").build();

            assertEquals("sunny", manager.grantChecked(executors, config, SecretAccess.agent(AGENT_A)).get("weather_now").execute(request, "m"));
            String refused = manager.grantChecked(executors, config, SecretAccess.agent(AGENT_B)).get("weather_now").execute(request, "m");

            assertTrue(refused.startsWith("Error:"), refused);
            assertEquals(1, calls.get(), "the shared executor never ran for the agent the grant leaves out");
        }

        @Test
        @DisplayName("a variable holding another variable is expanded to the final credential; one that does not resolve is refused")
        void nestedVariablesBindTheFinalCredential() {
            when(variables.resolveValue(anyString())).thenAnswer(inv -> {
                String value = inv.getArgument(0);
                return switch (value) {
                    case "${vars:outer}" -> "${vars:inner}";
                    case "${vars:inner}" -> REF;
                    default -> value;
                };
            });
            var manager = new McpToolProviderManager(variables, secretResolver);
            var config = new McpServerConfig();
            config.setName("weather");
            config.setUrl("https://mcp.example.com/mcp");
            config.setApiKey("${vars:outer}");

            assertEquals(REF, manager.bindCredential(config).getApiKey(), "bound to the secret, not to the inner variable");
            assertThrows(IllegalArgumentException.class, () -> manager.validateSecretGrant(config, SecretAccess.agent(AGENT_B)));

            var dangling = new McpServerConfig();
            dangling.setName("weather");
            dangling.setUrl("https://mcp.example.com/mcp");
            dangling.setApiKey("${vars:deleted}");
            var e = assertThrows(IllegalArgumentException.class, () -> manager.validateSecretGrant(dangling, SecretAccess.agent(AGENT_A)));
            assertTrue(e.getMessage().contains("does not resolve"), e.getMessage());

            var a2a = new A2AToolProviderManager(variables, secretResolver, false);
            var peer = new A2AAgentConfig();
            peer.setUrl("https://peer.example.com");
            peer.setApiKey("${vars:outer}");
            assertEquals(REF, a2a.bindCredential(peer).getApiKey());
        }

        @Test
        @DisplayName("a variable chain longer than the expansion limit is refused as bound, never expanded a second time")
        void chainPastTheLimitIsRefused() {
            // var1 -> var2 -> ... -> var10 -> the secret: more levels than expandFully
            // follows.
            when(variables.resolveValue(anyString())).thenAnswer(inv -> {
                String value = inv.getArgument(0);
                if (value.startsWith("${vars:var")) {
                    int level = Integer.parseInt(value.substring("${vars:var".length(), value.length() - 1));
                    return level < 10 ? "${vars:var" + (level + 1) + "}" : REF;
                }
                return value;
            });
            var manager = new McpToolProviderManager(variables, secretResolver);
            var config = new McpServerConfig();
            config.setName("weather");
            config.setUrl("https://mcp.example.com/mcp");
            config.setApiKey("${vars:var1}");
            var bound = manager.bindCredential(config);
            assertTrue(bound.getApiKey().startsWith("${vars:"), "the bound key stops at the limit: " + bound.getApiKey());
            // Agent A is granted the secret at the end of the chain, and is still refused:
            // the check must not expand the leftover variable to some other credential.
            assertThrows(IllegalArgumentException.class, () -> manager.validateSecretGrant(bound, SecretAccess.agent(AGENT_A)));

            var a2a = new A2AToolProviderManager(variables, secretResolver, false);
            var peer = new A2AAgentConfig();
            peer.setUrl("https://peer.example.com");
            peer.setApiKey("${vars:var1}");
            var boundPeer = a2a.bindCredential(peer);
            assertThrows(IllegalArgumentException.class, () -> a2a.requireSecretGranted(boundPeer, SecretAccess.agent(AGENT_A)));
        }

        @Test
        void a2aGrantChecked() {
            var manager = new A2AToolProviderManager(variables, secretResolver, false);
            var config = new A2AAgentConfig();
            config.setUrl("https://peer.example.com");
            config.setApiKey(REF);
            assertThrows(SecretResolver.SecretNotGrantedException.class, () -> manager.requireSecretGranted(config, SecretAccess.agent(AGENT_B)));
            assertDoesNotThrow(() -> manager.requireSecretGranted(config, SecretAccess.agent(AGENT_A)));
        }
    }
}
