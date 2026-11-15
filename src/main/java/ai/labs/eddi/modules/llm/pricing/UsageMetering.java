/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pricing;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.jboss.logging.Logger;

/**
 * The hook the chat-model decorators call on every response: replaces the
 * provider's token usage with a {@link MeteredTokenUsage}. The decorator around
 * every model {@code ChatModelRegistry} builds is the one point where each
 * call's real usage subclass, EDDI model type and response model name are all
 * known — so normalizing there means every pricing site downstream sees the
 * same convention.
 * <p>
 * Metering never fails a call: any problem returns the response unchanged.
 */
public final class UsageMetering {

    private static final Logger LOGGER = Logger.getLogger(UsageMetering.class);

    /**
     * {@code eddi.llm.pricing.provider-normalization}; published by the catalog
     * service. True by default.
     */
    private static volatile boolean normalize = true;

    private UsageMetering() {
    }

    /** Sets the provider-normalization switch (the escape hatch). */
    public static void setNormalize(boolean value) {
        normalize = value;
    }

    public static boolean normalize() {
        return normalize;
    }

    /** {@code response} with its usage metered against the current catalog. */
    public static ChatResponse meter(ChatResponse response, String eddiType, ChatRequest request) {
        return meter(response, eddiType, request, ModelPriceCatalogs.current(), normalize);
    }

    /** As {@link #meter(ChatResponse, String, ChatRequest)}, explicitly. */
    public static ChatResponse meter(ChatResponse response, String eddiType, ChatRequest request, PriceCatalogSnapshot catalog,
                                     boolean normalizeUsage) {
        try {
            if (response == null || response.metadata() == null) {
                return response;
            }
            ChatResponseMetadata metadata = response.metadata();
            TokenUsage raw = metadata.tokenUsage();
            if (raw == null || raw instanceof MeteredTokenUsage) {
                return response;
            }
            TokenUsage metered = UsageNormalizer.normalize(raw, eddiType, metadata.modelName(), requestModel(request), catalog, normalizeUsage);
            return response.toBuilder().metadata(withUsage(metadata, metered)).build();
        } catch (RuntimeException e) {
            LOGGER.debugf("Token usage metering skipped: %s", e.getMessage());
            return response;
        }
    }

    /**
     * The metadata with {@code usage} in place of the provider's. A provider
     * subclass ({@code OpenAiChatResponseMetadata},
     * {@code AnthropicChatResponseMetadata}, ...) narrows {@code tokenUsage()} to
     * its own usage type and would throw on a metered one, so it is replaced by a
     * plain {@link ChatResponseMetadata} carrying the fields EDDI reads — id, model
     * name, finish reason. The provider-only extras (service tier, log-probs, the
     * raw HTTP response) are read nowhere in EDDI.
     */
    static ChatResponseMetadata withUsage(ChatResponseMetadata metadata, TokenUsage usage) {
        if (metadata.getClass() == ChatResponseMetadata.class) {
            return metadata.toBuilder().tokenUsage(usage).build();
        }
        return ChatResponseMetadata.builder().id(metadata.id()).modelName(metadata.modelName()).finishReason(metadata.finishReason())
                .tokenUsage(usage).build();
    }

    private static String requestModel(ChatRequest request) {
        if (request == null) {
            return null;
        }
        if (request.parameters() != null && request.parameters().modelName() != null) {
            return request.parameters().modelName();
        }
        return request.modelName();
    }
}
