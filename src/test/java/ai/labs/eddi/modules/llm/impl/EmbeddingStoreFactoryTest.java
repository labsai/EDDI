/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingStoreFactoryTest {

    private EmbeddingStoreFactory factory;
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveSecrets(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingStoreFactory(secretResolver);
    }

    @Test
    void createInMemoryStore_shouldSucceed() {
        var config = new RagConfiguration();
        config.setStoreType("in-memory");

        EmbeddingStore<TextSegment> store = factory.getOrCreate(config, "test-kb");

        assertNotNull(store);
    }

    @Test
    void sameKb_shouldReturnCachedStore() {
        var config = new RagConfiguration();
        config.setStoreType("in-memory");

        EmbeddingStore<TextSegment> store1 = factory.getOrCreate(config, "kb-1");
        EmbeddingStore<TextSegment> store2 = factory.getOrCreate(config, "kb-1");

        assertSame(store1, store2, "Same KB should return cached store");
    }

    @Test
    void differentKbs_shouldReturnDifferentStores() {
        var config = new RagConfiguration();
        config.setStoreType("in-memory");

        EmbeddingStore<TextSegment> store1 = factory.getOrCreate(config, "kb-1");
        EmbeddingStore<TextSegment> store2 = factory.getOrCreate(config, "kb-2");

        assertNotSame(store1, store2, "Different KBs should get isolated stores");
    }

    @Test
    void unsupportedStoreType_shouldThrow() {
        var config = new RagConfiguration();
        config.setStoreType("unsupported");

        var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "kb"));
        assertTrue(ex.getMessage().contains("Supported:"), "Error should list supported types");
    }

    @Test
    void clearCache_shouldEvictEntries() {
        var config = new RagConfiguration();
        config.setStoreType("in-memory");

        EmbeddingStore<TextSegment> before = factory.getOrCreate(config, "kb-1");
        factory.clearCache();
        EmbeddingStore<TextSegment> after = factory.getOrCreate(config, "kb-1");

        assertNotSame(before, after, "After clearing cache, a new store should be created");
    }

    @Nested
    @DisplayName("Cache Key Tests")
    class CacheKeyTests {

        @Test
        @DisplayName("Different storeParameters should produce different stores")
        void differentStoreParams_shouldNotCollide() {
            var config1 = new RagConfiguration();
            config1.setStoreType("in-memory");
            config1.setStoreParameters(Map.of("host", "host-a"));

            var config2 = new RagConfiguration();
            config2.setStoreType("in-memory");
            config2.setStoreParameters(Map.of("host", "host-b"));

            EmbeddingStore<TextSegment> store1 = factory.getOrCreate(config1, "kb-1");
            EmbeddingStore<TextSegment> store2 = factory.getOrCreate(config2, "kb-1");

            assertNotSame(store1, store2, "Different storeParameters should produce different stores");
        }

        @Test
        @DisplayName("Same storeParameters should return cached store")
        void sameStoreParams_shouldCache() {
            var config1 = new RagConfiguration();
            config1.setStoreType("in-memory");
            config1.setStoreParameters(Map.of("host", "host-a"));

            var config2 = new RagConfiguration();
            config2.setStoreType("in-memory");
            config2.setStoreParameters(Map.of("host", "host-a"));

            EmbeddingStore<TextSegment> store1 = factory.getOrCreate(config1, "kb-1");
            EmbeddingStore<TextSegment> store2 = factory.getOrCreate(config2, "kb-1");

            assertSame(store1, store2, "Same storeParameters should return cached store");
        }
    }

    @Nested
    @DisplayName("Table Name Sanitization Tests")
    class TableNameTests {

        @Test
        void sanitizeTableName_standard() {
            assertEquals("eddi_kb_product_docs", EmbeddingStoreFactory.sanitizeTableName("product-docs"));
        }

        @Test
        void sanitizeTableName_specialChars() {
            assertEquals("eddi_kb_my_kb_v2_", EmbeddingStoreFactory.sanitizeTableName("My KB v2!"));
        }

        @Test
        void sanitizeTableName_alreadySafe() {
            assertEquals("eddi_kb_simple_name", EmbeddingStoreFactory.sanitizeTableName("simple_name"));
        }

        @Test
        @DisplayName("Long KB names should be truncated to 63 chars")
        void sanitizeTableName_longName_shouldTruncate() {
            String longName = "a".repeat(100);
            String result = EmbeddingStoreFactory.sanitizeTableName(longName);
            assertTrue(result.length() <= 63, "Table name should be at most 63 chars, got: " + result.length());
            assertTrue(result.startsWith("eddi_kb_"));
        }
    }

    @Nested
    @DisplayName("Fail-Fast Validation Tests")
    class ValidationTests {

        @Test
        @DisplayName("pgvector without password should throw with clear message")
        void pgvector_noPassword_shouldThrow() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("host", "localhost"));

            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "kb"));
            assertTrue(ex.getMessage().contains("password"), "Error should mention missing password");
            assertTrue(ex.getMessage().contains("eddivault"), "Error should mention vault reference");
        }

        @Test
        @DisplayName("Invalid port value should throw with clear message")
        void pgvector_invalidPort_shouldThrow() {
            var config = new RagConfiguration();
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("password", "secret", "port", "not-a-number"));

            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "kb"));
            assertTrue(ex.getMessage().contains("port"), "Error should mention the invalid param");
        }

        @Test
        @DisplayName("mongodb-atlas without connectionString should throw")
        void mongoDbAtlas_noConnectionString_shouldThrow() {
            var config = new RagConfiguration();
            config.setStoreType("mongodb-atlas");
            config.setStoreParameters(Map.of("databaseName", "eddi"));

            var ex = assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "kb"));
            assertTrue(ex.getMessage().contains("connectionString"));
        }
    }
}
