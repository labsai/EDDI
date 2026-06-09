/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.quarkus.test.Mock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Mock
@Singleton
public class TestEmbeddingStoreFactory extends EmbeddingStoreFactory {

    private final ConcurrentMap<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    @Inject
    public TestEmbeddingStoreFactory(
            ai.labs.eddi.configs.variables.GlobalVariableResolver globalVariableResolver,
            ai.labs.eddi.secrets.SecretResolver secretResolver) {
        super(globalVariableResolver, secretResolver);
    }

    @Override
    public EmbeddingStore<TextSegment> getOrCreate(RagConfiguration config, String kbId) {
        return stores.computeIfAbsent(kbId, k -> new InMemoryEmbeddingStore<>());
    }

    @Override
    public void clearCache() {
        stores.clear();
    }
}
