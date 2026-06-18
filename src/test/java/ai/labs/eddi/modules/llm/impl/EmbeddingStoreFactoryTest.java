/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("EmbeddingStoreFactory Tests")
class EmbeddingStoreFactoryTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingStoreFactory(globalVariableResolver, secretResolver);
    }

    // ==================== sanitizeTableName ====================

    @Nested
    @DisplayName("sanitizeTableName Tests")
    class SanitizeTableNameTests {

        @Test
        @DisplayName("simple kbId produces valid table name")
        void simpleKbId() {
            String result = EmbeddingStoreFactory.sanitizeTableName("kb123");
            assertEquals("eddi_kb_kb123", result);
        }

        @Test
        @DisplayName("kbId with special chars gets sanitized")
        void specialChars() {
            String result = EmbeddingStoreFactory.sanitizeTableName("my-KB.test");
            assertEquals("eddi_kb_my_kb_test", result);
        }

        @Test
        @DisplayName("very long kbId gets truncated to 63 chars")
        void longKbId() {
            String longId = "a".repeat(100);
            String result = EmbeddingStoreFactory.sanitizeTableName(longId);
            assertEquals(63, result.length());
            assertTrue(result.startsWith("eddi_kb_"));
        }

        @Test
        @DisplayName("kbId exactly at boundary is not truncated")
        void exactBoundary() {
            // "eddi_kb_" is 8 chars, so 55 chars of kbId = 63 total
            String id = "a".repeat(55);
            String result = EmbeddingStoreFactory.sanitizeTableName(id);
            assertEquals(63, result.length());
        }

        @Test
        @DisplayName("uppercase gets lowercased")
        void uppercaseKbId() {
            String result = EmbeddingStoreFactory.sanitizeTableName("MyKB");
            assertEquals("eddi_kb_mykb", result);
        }
    }

    // ==================== sanitizeCollection ====================

    @Nested
    @DisplayName("sanitizeCollection Tests")
    class SanitizeCollectionTests {

        @Test
        @DisplayName("simple kbId")
        void simpleKbId() {
            String result = EmbeddingStoreFactory.sanitizeCollection("kb123");
            assertEquals("eddi_kb_kb123", result);
        }

        @Test
        @DisplayName("kbId with trailing special chars gets cleaned")
        void trailingSpecialChars() {
            String result = EmbeddingStoreFactory.sanitizeCollection("test-kb-");
            assertEquals("eddi_kb_test_kb", result);
        }

        @Test
        @DisplayName("kbId with all special chars")
        void allSpecialChars() {
            String result = EmbeddingStoreFactory.sanitizeCollection("---");
            assertEquals("eddi_kb", result);
        }
    }

    // ==================== build — store types ====================

    @Nested
    @DisplayName("build — store type dispatch")
    class BuildStoreTypeTests {

        @Test
        @DisplayName("in-memory store type succeeds")
        void inMemoryStore() {
            var config = new RagConfiguration();
            config.setStoreType("in-memory");

            var store = factory.getOrCreate(config, "testKb");
            assertNotNull(store);
        }

        @Test
        @DisplayName("unsupported store type throws IllegalArgumentException")
        void unsupportedStore() {
            var config = new RagConfiguration();
            config.setStoreType("neo4j");

            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
        }

        @Test
        @DisplayName("pgvector without password throws IllegalArgumentException")
        void pgvectorMissingPassword() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("host", "localhost"));

            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
        }

        @Test
        @DisplayName("mongodb-atlas without connectionString throws IllegalArgumentException")
        void mongodbMissingConnectionString() {
            var config = new RagConfiguration();
            config.setStoreType("mongodb-atlas");
            config.setStoreParameters(Map.of());

            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
        }
    }

    // ==================== parseIntParam edge cases ====================

    @Nested
    @DisplayName("parseIntParam edge cases (via pgvector)")
    class ParseIntParamTests {

        @Test
        @DisplayName("invalid integer value throws IllegalArgumentException")
        void invalidInteger() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("password", "secret", "port", "not-a-number"));

            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
        }

        @Test
        @DisplayName("blank port uses default")
        void blankPort() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            var params = new HashMap<String, String>();
            params.put("password", "secret");
            params.put("port", "  ");
            config.setStoreParameters(params);

            // This will fail trying to connect, but should not fail parameter parsing
            try {
                factory.getOrCreate(config, "testKb");
            } catch (Exception e) {
                // Expected — can't connect to real PostgreSQL, but parsing succeeded
                assertFalse(e instanceof IllegalArgumentException, "Should not be IllegalArgumentException for blank port");
            }
        }
    }

    // ==================== parseChromaApiVersion ====================

    @Nested
    @DisplayName("Chroma API Version Tests")
    class ChromaApiVersionTests {

        @Test
        @DisplayName("invalid Chroma API version throws IllegalArgumentException")
        void invalidApiVersion() {
            var config = new RagConfiguration();
            config.setStoreType("chroma");
            config.setStoreParameters(Map.of("apiVersion", "INVALID_VERSION"));

            assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
        }
    }

    // ==================== clearCache ====================

    @Nested
    @DisplayName("clearCache Tests")
    class ClearCacheTests {

        @Test
        @DisplayName("clearCache does not throw")
        void clearCacheDoesNotThrow() {
            // First add something to cache
            var config = new RagConfiguration();
            config.setStoreType("in-memory");
            factory.getOrCreate(config, "kb1");

            assertDoesNotThrow(() -> factory.clearCache());
        }
    }

    // ==================== requireParam ====================

    @Nested
    @DisplayName("requireParam via various store types")
    class RequireParamTests {

        @Test
        @DisplayName("blank value treated as missing")
        void blankValueTreatedAsMissing() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            var params = new HashMap<String, String>();
            params.put("password", "   ");
            config.setStoreParameters(params);

            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "testKb"));
            assertTrue(ex.getMessage().contains("password"));
        }
    }

    // ==================== null storeParameters ====================

    @Nested
    @DisplayName("Null storeParameters Tests")
    class NullStoreParametersTests {

        @Test
        @DisplayName("null storeParameters on in-memory store succeeds")
        void nullParamsInMemory() {
            var config = new RagConfiguration();
            config.setStoreType("in-memory");
            config.setStoreParameters(null);

            var store = factory.getOrCreate(config, "kb1");
            assertNotNull(store);
        }
    }

    // ==================== getOrCreate caching ====================

    @Nested
    @DisplayName("Caching Tests")
    class CachingTests {

        @Test
        @DisplayName("same config returns cached store")
        void cachedStore() {
            var config = new RagConfiguration();
            config.setStoreType("in-memory");

            var store1 = factory.getOrCreate(config, "kb1");
            var store2 = factory.getOrCreate(config, "kb1");
            assertSame(store1, store2);
        }

        @Test
        @DisplayName("different kbId returns different store")
        void differentKbId() {
            var config = new RagConfiguration();
            config.setStoreType("in-memory");

            var store1 = factory.getOrCreate(config, "kb1");
            var store2 = factory.getOrCreate(config, "kb2");
            assertNotSame(store1, store2);
        }
    }
}
