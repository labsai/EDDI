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

        assertThrows(IllegalArgumentException.class, () -> factory.getOrCreate(config, "kb"));
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
    }
}
