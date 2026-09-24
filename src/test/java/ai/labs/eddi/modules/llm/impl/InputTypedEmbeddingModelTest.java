/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;
import dev.langchain4j.data.embedding.Embedding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Grades {@link InputTypedEmbeddingModel}, the fix for EDDI embedding its RAG
 * queries as documents.
 */
@DisplayName("InputTypedEmbeddingModel")
class InputTypedEmbeddingModelTest {

    /** Records the input type that actually reached the provider. */
    private static class RecordingModel implements EmbeddingModel {
        final AtomicReference<EmbeddingInputType> seen = new AtomicReference<>();
        private final Set<EmbeddingParameter<?>> supported;

        RecordingModel(Set<EmbeddingParameter<?>> supported) {
            this.supported = supported;
        }

        @Override
        public EmbeddingResponse doEmbed(EmbeddingRequest request) {
            seen.set(request.inputType());
            return EmbeddingResponse.builder()
                    .embeddings(List.of(Embedding.from(new float[]{0.1f, 0.2f})))
                    .build();
        }

        @Override
        public Set<EmbeddingParameter<?>> supportedParameters() {
            return supported;
        }

        @Override
        public int dimension() {
            return 2;
        }
    }

    private static RecordingModel asymmetric() {
        return new RecordingModel(Set.of(EmbeddingRequestParameters.INPUT_TYPE));
    }

    private static RecordingModel symmetric() {
        return new RecordingModel(Set.of());
    }

    /**
     * The defect, stated as a test. Without the decorator the provider sees
     * {@code null} and falls back to whatever it was <em>built</em> with — which
     * for Gemini is {@code RETRIEVAL_DOCUMENT}, applied to queries as well as
     * documents.
     */
    @Test
    @DisplayName("an undecorated model is told nothing, and falls back to its build-time default")
    void undecoratedModelIsToldNothing() {
        RecordingModel raw = asymmetric();

        raw.embed(TextSegment.from("what is the refund policy?"));

        assertNull(raw.seen.get(),
                "this is the defect: the provider gets no input type, so GoogleAiEmbeddingModel.toTaskType"
                        + " falls back to the taskType baked in at construction");
    }

    @Test
    @DisplayName("a query-tagged model tells the provider QUERY")
    void queryTaggedModelSaysQuery() {
        RecordingModel raw = asymmetric();

        EmbeddingModel wrapped = InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.QUERY);
        wrapped.embed(TextSegment.from("what is the refund policy?"));

        assertEquals(EmbeddingInputType.QUERY, raw.seen.get(),
                "retrieval must embed the search key as a QUERY, which is the whole point of the fix");
    }

    @Test
    @DisplayName("a document-tagged model tells the provider DOCUMENT")
    void documentTaggedModelSaysDocument() {
        RecordingModel raw = asymmetric();

        EmbeddingModel wrapped = InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.DOCUMENT);
        wrapped.embed(TextSegment.from("Refunds are issued within 30 days."));

        assertEquals(EmbeddingInputType.DOCUMENT, raw.seen.get());
    }

    /**
     * The check that keeps this from breaking six of the eight providers.
     * <p>
     * {@code EmbeddingModel.embed} validates against {@code supportedParameters()}
     * and throws {@code UnsupportedFeatureException} for anything unsupported
     * <em>rather than ignoring it</em>. Only Gemini and Cohere declare
     * {@code INPUT_TYPE} in langchain4j 1.20.0, so setting it unconditionally would
     * turn every OpenAI, Azure, Ollama, Bedrock, Vertex and Mistral knowledge base
     * into a hard failure.
     */
    @Test
    @DisplayName("a provider that does not accept an input type is returned unwrapped")
    void unsupportingProviderIsReturnedUnwrapped() {
        RecordingModel raw = symmetric();

        assertSame(raw, InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.QUERY),
                "wrapping a provider that rejects INPUT_TYPE would make embed() throw"
                        + " UnsupportedFeatureException on every call");
    }

    /**
     * Proves the previous test is not passing for a trivial reason: embedding
     * through an unwrapped symmetric model still works and still reports no input
     * type, rather than throwing.
     */
    @Test
    @DisplayName("an unsupporting provider still embeds normally")
    void unsupportingProviderStillEmbeds() {
        RecordingModel raw = symmetric();

        EmbeddingModel model = InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.QUERY);
        var response = model.embed(TextSegment.from("hello"));

        assertNotNull(response.content());
        assertNull(raw.seen.get(), "a symmetric model is never told a role, because it has no use for one");
    }

    @Test
    @DisplayName("a null input type leaves the model alone")
    void nullInputTypeLeavesModelAlone() {
        RecordingModel raw = asymmetric();
        assertSame(raw, InputTypedEmbeddingModel.wrapIfSupported(raw, null));
    }

    @Test
    @DisplayName("a null model is passed through rather than wrapped")
    void nullModelIsPassedThrough() {
        assertNull(InputTypedEmbeddingModel.wrapIfSupported(null, EmbeddingInputType.QUERY));
    }

    /**
     * The decorator must stay transparent: everything except the input type is the
     * delegate's business, and a caller inspecting the model — for a dimension,
     * say, when creating a vector-store column — must get the real answer.
     */
    @Test
    @DisplayName("everything other than the input type delegates")
    void everythingElseDelegates() {
        RecordingModel raw = asymmetric();
        var wrapped = assertInstanceOf(InputTypedEmbeddingModel.class,
                InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.QUERY));

        assertEquals(raw.dimension(), wrapped.dimension());
        assertEquals(raw.supportedParameters(), wrapped.supportedParameters());
        assertEquals(raw.supportedContentTypes(), wrapped.supportedContentTypes());
        assertEquals(raw.provider(), wrapped.provider());
        assertSame(raw, wrapped.delegate());
        assertEquals(EmbeddingInputType.QUERY, wrapped.inputType());
    }

    /**
     * The delegate's own default parameters must survive: a model built with an
     * explicit {@code modelName} or {@code dimensions} must not lose them because
     * EDDI attached a role.
     */
    @Test
    @DisplayName("the delegate's other default parameters are preserved")
    void delegateDefaultParametersArePreserved() {
        var raw = new RecordingModel(Set.of(EmbeddingRequestParameters.INPUT_TYPE)) {
            @Override
            public EmbeddingRequestParameters defaultRequestParameters() {
                return EmbeddingRequestParameters.builder().modelName("text-embedding-004").dimensions(768).build();
            }
        };

        EmbeddingModel wrapped = InputTypedEmbeddingModel.wrapIfSupported(raw, EmbeddingInputType.QUERY);
        EmbeddingRequestParameters merged = wrapped.defaultRequestParameters();

        assertEquals("text-embedding-004", merged.modelName(), "attaching a role must not drop the model name");
        assertEquals(768, merged.dimensions(), "nor the dimensions");
        assertEquals(EmbeddingInputType.QUERY, merged.inputType());
    }
}
