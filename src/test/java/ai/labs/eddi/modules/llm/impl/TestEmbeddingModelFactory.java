/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.quarkus.test.Mock;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Mock
@Singleton
public class TestEmbeddingModelFactory extends EmbeddingModelFactory {

    private static final EmbeddingModel FIXED_MODEL = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            var embeddings = IntStream.range(0, textSegments.size())
                    .mapToObj(i -> new Embedding(new float[384]))
                    .collect(Collectors.toList());
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return 384;
        }
    };

    @Inject
    public TestEmbeddingModelFactory(
            ai.labs.eddi.configs.variables.GlobalVariableResolver globalVariableResolver,
            ai.labs.eddi.secrets.SecretResolver secretResolver) {
        super(globalVariableResolver, secretResolver);
    }

    @Override
    public EmbeddingModel getOrCreate(RagConfiguration config) {
        return FIXED_MODEL;
    }

    @Override
    public void clearCache() {
        // no-op
    }
}
