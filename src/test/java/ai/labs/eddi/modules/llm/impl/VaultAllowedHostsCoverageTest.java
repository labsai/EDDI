/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultHostAllowlist;
import ai.labs.eddi.secrets.VaultHostAllowlist.HostNotAllowedException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The paths the first allowedHosts change left open: a {@code ${vault:…}}
 * reference resolved for an embedding model, a vector store, an MCP server or
 * an A2A peer is checked against the host the request actually goes to, before
 * the plaintext is resolved (or, for a vector store, before anything connects).
 * <p>
 * Each test uses the real {@link VaultHostAllowlist} over a mocked
 * {@link ISecretProvider}, so the match itself is exercised, not a stub of it.
 */
@DisplayName("allowedHosts on the embedding, vector-store, MCP and A2A resolution paths")
class VaultAllowedHostsCoverageTest {

    private static final String REF = "${vault:k}";
    private static final String ALLOWED = "api.good.example";

    private GlobalVariableResolver globalVariableResolver;
    private SecretResolver secretResolver;
    private ISecretProvider secretProvider;
    private VaultHostAllowlist allowlist;

    @BeforeEach
    void setUp() throws Exception {
        globalVariableResolver = mock(GlobalVariableResolver.class);
        secretResolver = mock(SecretResolver.class);
        secretProvider = mock(ISecretProvider.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> ((String) inv.getArgument(0)).replace(REF, "live-key"));
        when(secretProvider.getMetadata(any(SecretReference.class)))
                .thenReturn(new SecretMetadata("default", "k", Instant.EPOCH, null, null, "cs", "d", List.of("*"), List.of(ALLOWED)));
        allowlist = new VaultHostAllowlist(secretProvider);
    }

    @Nested
    @DisplayName("EmbeddingModelFactory")
    class EmbeddingModel {

        private EmbeddingModelFactory factory;

        @BeforeEach
        void setUp() {
            factory = new EmbeddingModelFactory(globalVariableResolver, secretResolver);
            factory.vaultHostAllowlist = allowlist;
            // Reaching resolution is the observable "the check let it through"; building
            // a live provider client is not this test's business.
            when(secretResolver.resolveSecrets(any())).thenThrow(new IllegalStateException("resolved"));
        }

        private RagConfiguration config(String provider, Map<String, String> params) {
            var config = new RagConfiguration();
            config.setEmbeddingProvider(provider);
            config.setEmbeddingParameters(params);
            return config;
        }

        @Test
        @DisplayName("a baseUrl off the list is refused before the key is resolved")
        void baseUrlOffListRefused() {
            var config = config("openai", Map.of("apiKey", REF, "baseUrl", "https://collector.attacker.example/v1"));

            var e = assertThrows(HostNotAllowedException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
            assertTrue(e.getMessage().contains("embedding model 'openai'"), e.getMessage());
            verify(secretResolver, never()).resolveSecrets(any());
        }

        @Test
        @DisplayName("an allowed baseUrl does not vouch for an off-list endpoint the Azure builder actually reads")
        void everyEndpointKeyChecked() {
            var config = config("azure-openai",
                    Map.of("apiKey", REF, "baseUrl", "https://" + ALLOWED, "endpoint", "https://azure.attacker.example/openai"));

            var e = assertThrows(HostNotAllowedException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
            assertTrue(e.getMessage().contains("azure.attacker.example"), e.getMessage());
            verify(secretResolver, never()).resolveSecrets(any());
        }

        @Test
        @DisplayName("an Azure endpoint on the list reaches resolution")
        void endpointOnListAllowed() {
            var config = config("azure-openai", Map.of("apiKey", REF, "endpoint", "https://" + ALLOWED + "/openai"));

            var e = assertThrows(IllegalStateException.class, () -> factory.getOrCreate(config, EmbeddingInputType.DOCUMENT));
            assertEquals("resolved", e.getMessage());
        }

        @Test
        @DisplayName("no endpoint parameter means the vendor's own endpoint: nothing to check")
        void vendorDefaultNotChecked() throws Exception {
            var config = config("openai", Map.of("apiKey", REF));

            var e = assertThrows(IllegalStateException.class, () -> factory.getOrCreate(config, EmbeddingInputType.QUERY));
            assertEquals("resolved", e.getMessage());
            verify(secretProvider, never()).getMetadata(any());
        }
    }

    @Nested
    @DisplayName("EmbeddingStoreFactory")
    class EmbeddingStore {

        private EmbeddingStoreFactory factory;

        @BeforeEach
        void setUp() {
            factory = new EmbeddingStoreFactory(globalVariableResolver, secretResolver);
            factory.vaultHostAllowlist = allowlist;
        }

        private RagConfiguration config(String storeType, Map<String, String> params) {
            var config = new RagConfiguration();
            config.setName("kb");
            config.setStoreType(storeType);
            config.setStoreParameters(params);
            return config;
        }

        private void resolvingTo(String plaintext) {
            when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> {
                Map<String, String> resolved = new HashMap<>(inv.getArgument(0));
                resolved.replaceAll((key, value) -> value.replace(REF, plaintext));
                return resolved;
            });
        }

        @Test
        @DisplayName("a pgvector host off the list is refused before the store connects")
        void pgvectorHostOffListRefused() {
            resolvingTo("db-password");
            var config = config("pgvector", Map.of("host", "db.attacker.example", "password", REF));

            var e = assertThrows(HostNotAllowedException.class, () -> factory.getOrCreate("0123456789abcdef01234567", config));
            assertTrue(e.getMessage().contains("db.attacker.example:5432"), e.getMessage());
        }

        @Test
        @DisplayName("every host of a vault-held MongoDB connection string is checked, once it is decrypted")
        void everyMongoHostChecked() {
            resolvingTo("mongodb://" + ALLOWED + ":27017,replica.attacker.example:27018/eddi");
            var config = config("mongodb-atlas", Map.of("connectionString", REF));

            var e = assertThrows(HostNotAllowedException.class, () -> factory.getOrCreate("0123456789abcdef01234567", config));
            assertTrue(e.getMessage().contains("replica.attacker.example:27018"), e.getMessage());
        }

        @Test
        @DisplayName("a configuration without vault references is not looked up")
        void noReferencesNoLookup() throws Exception {
            when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
            factory.getOrCreate("0123456789abcdef01234567", config("in-memory", Map.of()));
            verify(secretProvider, never()).getMetadata(any());
        }

        @Test
        @DisplayName("destinationsOf names what each builder connects to, defaults included")
        void destinations() {
            assertEquals(List.of("localhost:5432"), EmbeddingStoreFactory.destinationsOf("pgvector", Map.of()));
            assertEquals(List.of("q.example:7000"), EmbeddingStoreFactory.destinationsOf("qdrant", Map.of("host", "q.example", "port", "7000")));
            assertEquals(List.of("http://localhost:9200"), EmbeddingStoreFactory.destinationsOf("elasticsearch", Map.of()));
            assertEquals(List.of("https://c.example"), EmbeddingStoreFactory.destinationsOf("chroma", Map.of("baseUrl", "https://c.example")));
            assertEquals(List.of("a.example:27017", "b.example:27018"),
                    EmbeddingStoreFactory.destinationsOf("mongodb-atlas", Map.of("connectionString", "mongodb://a.example:27017,b.example:27018/x")));
            assertEquals(List.of(""), EmbeddingStoreFactory.destinationsOf("mongodb-atlas", Map.of("connectionString", "not a uri")));
            assertEquals(List.of(), EmbeddingStoreFactory.destinationsOf("in-memory", Map.of()));
        }
    }

    @Nested
    @DisplayName("McpToolProviderManager")
    class Mcp {

        private McpToolProviderManager manager;

        @BeforeEach
        void setUp() {
            manager = new McpToolProviderManager(globalVariableResolver, secretResolver);
            manager.vaultHostAllowlist = allowlist;
        }

        private McpServerConfig server(String url, String apiKey) {
            var config = new McpServerConfig();
            config.setName("tools");
            config.setUrl(url);
            config.setApiKey(apiKey);
            return config;
        }

        @Test
        @DisplayName("a server off the list is a configuration error, and the key is never resolved")
        void serverOffListRefused() {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> manager.validateSecretDestination(server("https://mcp.attacker.example/mcp", REF)));
            assertInstanceOf(HostNotAllowedException.class, e.getCause());
            assertTrue(e.getMessage().contains("MCP server 'tools'"), e.getMessage());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("discovery reports it as INVALID_CONFIGURATION and never contacts the server")
        void discoveryReportsInvalidConfiguration() {
            var result = manager.discoverTools(List.of(server("https://mcp.attacker.example/mcp", REF)));

            assertEquals(1, result.failures().size());
            assertEquals(McpToolProviderManager.McpFailureKind.INVALID_CONFIGURATION, result.failures().getFirst().kind());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("a server on the list passes")
        void serverOnListAllowed() {
            assertDoesNotThrow(() -> manager.validateSecretDestination(server("https://" + ALLOWED + "/mcp", REF)));
        }

        @Test
        @DisplayName("a caller-bound key is not a vault reference and is not looked up")
        void callerBoundKeyIgnored() throws Exception {
            manager.validateSecretDestination(server("https://mcp.attacker.example/mcp", "{caller.token}"));
            manager.validateSecretDestination(server("https://mcp.attacker.example/mcp", "${caller:token}"));
            verify(secretProvider, never()).getMetadata(any());
        }
    }

    @Nested
    @DisplayName("A2AToolProviderManager")
    class A2A {

        private A2AToolProviderManager manager;

        @BeforeEach
        void setUp() {
            manager = new A2AToolProviderManager(globalVariableResolver, secretResolver, false);
            manager.vaultHostAllowlist = allowlist;
        }

        private A2AAgentConfig peer(String apiKey) {
            var config = new A2AAgentConfig();
            config.setUrl("https://" + ALLOWED);
            config.setApiKey(apiKey);
            return config;
        }

        @Test
        @DisplayName("a task call to an endpoint the agent card moved off the list is refused before the key is resolved")
        void announcedEndpointOffListRefused() {
            String announced = "https://peer.attacker.example/a2a";
            var builder = OutboundRequest.get(Profile.DEFAULT, URI.create(announced));

            var e = assertThrows(HostNotAllowedException.class, () -> manager.applyCredential(builder, peer(REF), announced, false));
            assertTrue(e.getMessage().contains("peer.attacker.example"), e.getMessage());
            verify(secretResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("a peer on the list gets the resolved key")
        void peerOnListAllowed() {
            String url = "https://" + ALLOWED + "/a2a";
            var builder = OutboundRequest.get(Profile.DEFAULT, URI.create(url));

            manager.applyCredential(builder, peer(REF), url, true);

            assertEquals(List.of("Bearer live-key"), builder.build().headers().get("Authorization"));
        }
    }
}
