package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingStoreFactoryTest {

    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        factory = new EmbeddingStoreFactory();
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
}
