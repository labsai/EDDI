/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.listener.EmbeddingModelListener;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.embedding.request.EmbeddingParameter;
import dev.langchain4j.model.embedding.request.EmbeddingRequest;
import dev.langchain4j.model.embedding.request.EmbeddingRequestParameters;
import dev.langchain4j.model.embedding.response.EmbeddingResponse;

import java.util.List;
import java.util.Set;

/**
 * Tells an embedding model whether it is embedding a <em>document</em> being
 * ingested or a <em>query</em> being searched with.
 *
 * <h2>The defect this fixes</h2>
 *
 * Several embedding models are <b>asymmetric</b>: they produce a different
 * vector for the same text depending on which side of the search it is on, and
 * the retrieval quality they advertise assumes you tell them which. Google's is
 * the clearest case — {@code GoogleAiEmbeddingModel.toTaskType} maps a
 * per-request {@link EmbeddingInputType#QUERY} to {@code RETRIEVAL_QUERY} and
 * {@link EmbeddingInputType#DOCUMENT} to {@code RETRIEVAL_DOCUMENT}, and when
 * no input type is given at all it falls back to whatever {@code taskType} the
 * model was <em>built</em> with.
 * <p>
 * EDDI built one model per knowledge base and handed the same instance to both
 * {@code RagIngestionService} and {@code RagContextProvider} — they call
 * {@code getOrCreate} with the same configuration, so they hit the same cache
 * entry. With {@code EmbeddingModelFactory} defaulting Gemini's
 * {@code taskType} to {@code RETRIEVAL_DOCUMENT}, every Gemini RAG deployment
 * has been embedding its <em>queries</em> as documents. Nothing fails; recall
 * is just quietly worse than the model is capable of.
 *
 * <h2>Why a decorator, and why it checks before it sets</h2>
 *
 * {@code EmbeddingStoreContentRetriever} calls
 * {@code embeddingModel.embed(text)} and gives EDDI no way to pass a per-call
 * parameter, so the input type has to travel with the model instance rather
 * than with the call. Hence one decorated instance per role, cached separately.
 * <p>
 * The capability check is not optional. {@code EmbeddingModel.embed} validates
 * the request against {@link EmbeddingModel#supportedParameters()} and throws
 * {@code UnsupportedFeatureException} for anything unsupported <em>rather than
 * ignoring it</em>. In langchain4j 1.20.0 only two of the eight providers EDDI
 * builds declare {@code INPUT_TYPE} — Google Gemini and Cohere — so setting it
 * unconditionally would turn every OpenAI, Azure, Ollama, Bedrock, Vertex and
 * Mistral knowledge base into a hard failure.
 * <p>
 * The check reads {@code supportedParameters()} on the live model rather than
 * consulting a table of provider names. A hand-maintained matrix would be one
 * more thing to keep in step with a dependency bump; this is the provider's own
 * declaration, so it cannot go stale.
 */
class InputTypedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final EmbeddingRequestParameters parameters;

    private InputTypedEmbeddingModel(EmbeddingModel delegate, EmbeddingRequestParameters parameters) {
        this.delegate = delegate;
        this.parameters = parameters;
    }

    /**
     * Returns {@code model} tagged with {@code inputType}, or {@code model}
     * unchanged when the provider does not accept an input type.
     * <p>
     * Returning the bare model for the unsupporting majority is deliberate: there
     * is nothing for a decorator to add there, and an extra delegating object on
     * every embed call would be pure overhead.
     *
     * @param model
     *            the provider's model
     * @param inputType
     *            the role this instance will be used for
     *
     * @return a decorated model, or {@code model} itself
     */
    static EmbeddingModel wrapIfSupported(EmbeddingModel model, EmbeddingInputType inputType) {
        if (model == null || inputType == null) {
            return model;
        }
        if (!model.supportedParameters().contains(EmbeddingRequestParameters.INPUT_TYPE)) {
            return model;
        }

        EmbeddingRequestParameters existing = model.defaultRequestParameters();
        var builder = EmbeddingRequestParameters.builder().inputType(inputType);
        if (existing != null) {
            builder.modelName(existing.modelName()).dimensions(existing.dimensions());
        }
        return new InputTypedEmbeddingModel(model, builder.build());
    }

    /**
     * The input type, merged over whatever the delegate already defaults to.
     * <p>
     * {@code EmbeddingModel.embed} merges these into every request before calling
     * {@code doEmbed}, which is what lets the role reach a provider through the
     * {@code embed(String)} convenience overload that
     * {@code EmbeddingStoreContentRetriever} uses.
     */
    @Override
    public EmbeddingRequestParameters defaultRequestParameters() {
        return parameters;
    }

    @Override
    public EmbeddingResponse doEmbed(EmbeddingRequest request) {
        return delegate.doEmbed(request);
    }

    @Override
    public Set<EmbeddingParameter<?>> supportedParameters() {
        return delegate.supportedParameters();
    }

    @Override
    public Set<ContentType> supportedContentTypes() {
        return delegate.supportedContentTypes();
    }

    @Override
    public List<EmbeddingModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    /** Test-visible: the role this instance carries. */
    EmbeddingInputType inputType() {
        return parameters.inputType();
    }

    /** Test-visible: the model underneath. */
    EmbeddingModel delegate() {
        return delegate;
    }
}
