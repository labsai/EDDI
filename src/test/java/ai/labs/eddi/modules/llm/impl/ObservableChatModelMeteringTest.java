/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.pricing.ModelPriceCatalogs;
import ai.labs.eddi.engine.pricing.PriceCatalogSnapshot;
import ai.labs.eddi.engine.pricing.model.ModelPriceCatalog;
import ai.labs.eddi.engine.pricing.model.ModelPriceEntry;
import ai.labs.eddi.engine.pricing.model.UnpricedPolicy;
import ai.labs.eddi.modules.llm.pricing.MeteredTokenUsage;
import ai.labs.eddi.modules.llm.pricing.UsageMetering;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The decorators replace each response's provider token usage with a
 * {@link MeteredTokenUsage}, keeping the provider's metadata subclass.
 */
class ObservableChatModelMeteringTest {

    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hi"))
            .parameters(ChatRequestParameters.builder().modelName("claude-sonnet-4-5").build()).build();

    @AfterEach
    void reset() {
        ModelPriceCatalogs.publish(null);
        UsageMetering.setNormalize(true);
    }

    private static ChatModel returning(ChatResponse response) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(response);
        when(model.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(model.listeners()).thenReturn(List.of());
        return model;
    }

    @Test
    void syncResponseUsageIsNormalizedAndCatalogued() {
        ModelPriceCatalogs.publish(PriceCatalogSnapshot.of(new ModelPriceCatalog(1, UnpricedPolicy.ALLOW, null, null,
                List.of(ModelPriceEntry.of("anthropic", "claude-sonnet-4-5*", 3.0, 15.0))), UnpricedPolicy.ALLOW));
        var usage = AnthropicTokenUsage.builder().inputTokenCount(100).outputTokenCount(10).cacheReadInputTokens(900).build();
        var raw = ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(usage).modelName("claude-sonnet-4-5-20250929").build();

        ChatResponse response = ObservableChatModel.wrap(returning(raw), "anthropic", null, null, null, null).chat(REQUEST);

        var metered = assertInstanceOf(MeteredTokenUsage.class, response.metadata().tokenUsage());
        assertEquals(1000, metered.inputTokenCount());
        assertEquals(900, metered.cacheReadTokens());
        assertEquals("anthropic/claude-sonnet-4-5-20250929", metered.pricingModel());
        assertEquals(0, metered.uncataloguedCalls());
        assertEquals("ok", response.aiMessage().text());
    }

    @Test
    void providerMetadataSubclassIsReplacedKeepingTheFieldsEddiReads() {
        // OpenAiChatResponseMetadata narrows tokenUsage() to OpenAiTokenUsage, so it
        // cannot carry a metered usage; a plain metadata keeps id, model and finish
        var usage = OpenAiTokenUsage.builder().inputTokenCount(10).outputTokenCount(5).totalTokenCount(15)
                .inputTokensDetails(OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(4).build()).build();
        ChatResponseMetadata md = OpenAiChatResponseMetadata.builder().id("r1").tokenUsage(usage).modelName("gpt-4o")
                .finishReason(FinishReason.LENGTH).serviceTier("default").build();
        var raw = ChatResponse.builder().aiMessage(AiMessage.from("ok")).metadata(md).build();

        ChatResponse response = ObservableChatModel.wrap(returning(raw), "openai", null, null, null, null).chat(REQUEST);

        var metered = assertInstanceOf(MeteredTokenUsage.class, response.metadata().tokenUsage());
        assertEquals(4, metered.cacheReadTokens());
        assertEquals("r1", response.metadata().id());
        assertEquals("gpt-4o", response.metadata().modelName());
        assertEquals(FinishReason.LENGTH, response.metadata().finishReason());
    }

    @Test
    void aResponseWithoutUsageIsReturnedUnchanged() {
        var raw = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        assertSame(raw, UsageMetering.meter(raw, "openai", REQUEST));
    }

    @Test
    void streamingCompleteResponseIsMeteredAndOtherCallbacksForwarded() {
        var usage = AnthropicTokenUsage.builder().inputTokenCount(1).outputTokenCount(1).cacheCreationInputTokens(4).build();
        var raw = ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(usage).build();
        StreamingChatModel delegate = mock(StreamingChatModel.class);
        when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(delegate.listeners()).thenReturn(List.of());
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialThinking(new PartialThinking("hmm"));
            h.onCompleteResponse(raw);
            return null;
        }).when(delegate).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        List<String> thinking = new ArrayList<>();
        ObservableStreamingChatModel.wrap(delegate, "anthropic", null, null, null).chat(REQUEST, new StreamingChatResponseHandler() {
            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                thinking.add(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                completed.set(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        var metered = assertInstanceOf(MeteredTokenUsage.class, completed.get().metadata().tokenUsage());
        assertEquals(5, metered.inputTokenCount());
        assertEquals(4, metered.cacheWriteTokens());
        assertEquals(List.of("hmm"), thinking);
    }
}
