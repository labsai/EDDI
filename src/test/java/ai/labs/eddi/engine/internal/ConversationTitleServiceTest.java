/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.TitleGeneration;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor.TitleSource;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.pricing.SpendEvent;
import ai.labs.eddi.engine.pricing.SpendSinks;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ConversationTitleService")
class ConversationTitleServiceTest {

    private IConversationDescriptorStore descriptorStore;
    private AgentConfigurationCache configurationCache;
    private SummarizationService summarizationService;
    private TenantQuotaService quotaService;
    private SpendSinks spendSinks;
    private SimpleMeterRegistry meters;
    private ConversationTitleService service;

    @BeforeEach
    void setUp() throws Exception {
        descriptorStore = mock(IConversationDescriptorStore.class);
        configurationCache = mock(AgentConfigurationCache.class);
        summarizationService = mock(SummarizationService.class);
        quotaService = mock(TenantQuotaService.class);
        spendSinks = mock(SpendSinks.class);
        meters = new SimpleMeterRegistry();
        when(quotaService.getDefaultTenantId()).thenReturn("tenant");
        when(quotaService.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(quotaService.recordCost(anyString(), anyDouble())).thenReturn(QuotaCheckResult.OK);
        when(descriptorStore.writeGeneratedTitle(anyString(), anyString(), any())).thenReturn(true);
        when(configurationCache.read("agent-1", 1)).thenReturn(new AgentConfiguration());
        // Inline executor: the background generation runs within afterTurn.
        service = new ConversationTitleService(descriptorStore, configurationCache, summarizationService, quotaService, spendSinks, meters, 80,
                Runnable::run);
    }

    private static ConversationMemory conversation() {
        return new ConversationMemory("conv-1", "agent-1", 1, "alice");
    }

    private static void plainTurn(ConversationMemory memory, String input, String answer) {
        memory.startNextStep();
        var step = memory.getCurrentStep();
        step.storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), input));
        step.addConversationOutputString(MemoryKeys.INPUT.key(), input);
        step.addConversationOutputList("output", List.of(Map.of("type", "text", "text", answer)));
    }

    private static void secretTurn(ConversationMemory memory) {
        memory.startNextStep();
        var step = memory.getCurrentStep();
        step.storeData(new Data<>(MemoryKeys.INPUT_INITIAL.key(), MemoryKeys.SECRET_INPUT_PLACEHOLDER));
        step.addConversationOutputString(MemoryKeys.INPUT.key(), MemoryKeys.SECRET_INPUT_PLACEHOLDER);
        step.addConversationOutputMap("context", Map.of("secretInput", "true"));
    }

    private void enableLlmTitles() throws Exception {
        var generation = new TitleGeneration();
        generation.setEnabled(true);
        generation.setLlmProvider("openai");
        generation.setLlmModel("gpt-4o-mini");
        generation.setInputPricePer1M(1.0);
        generation.setOutputPricePer1M(2.0);
        var configuration = new AgentConfiguration();
        configuration.setTitleGeneration(generation);
        when(configurationCache.read("agent-1", 1)).thenReturn(configuration);
    }

    @Nested
    @DisplayName("default title")
    class DefaultTitle {

        @Test
        @DisplayName("the turn of the first message writes it, as AUTO")
        void firstTurn() {
            var memory = conversation();
            plainTurn(memory, "What is the weather in Vienna?", "Sunny.");

            service.afterTurn(memory, false);

            verify(descriptorStore).writeGeneratedTitle("conv-1", "What is the weather in Vienna?", TitleSource.AUTO);
        }

        @Test
        @DisplayName("a later turn writes nothing")
        void laterTurn() {
            var memory = conversation();
            plainTurn(memory, "First", "a");
            plainTurn(memory, "Second", "b");

            service.afterTurn(memory, false);

            verifyNoInteractions(descriptorStore);
        }

        @Test
        @DisplayName("a secret first turn writes nothing; the next turn titles from its own message")
        void secretFirstTurn() {
            var memory = conversation();
            secretTurn(memory);

            service.afterTurn(memory, false);
            verifyNoInteractions(descriptorStore);

            plainTurn(memory, "Book the 9 am flight", "Done.");
            service.afterTurn(memory, false);
            verify(descriptorStore).writeGeneratedTitle("conv-1", "Book the 9 am flight", TitleSource.AUTO);
            verify(descriptorStore, never()).writeGeneratedTitle(eq("conv-1"), eq(MemoryKeys.SECRET_INPUT_PLACEHOLDER), any());
        }

        @Test
        @DisplayName("a rerun, or a cancelled turn, writes nothing")
        void rerunAndCancelled() {
            var memory = conversation();
            plainTurn(memory, "Hello", "Hi");

            service.afterTurn(memory, true);
            memory.setCancelled(true);
            service.afterTurn(memory, false);

            verifyNoInteractions(descriptorStore);
        }

        @Test
        @DisplayName("a store failure never reaches the turn")
        void failureSwallowed() {
            when(descriptorStore.writeGeneratedTitle(anyString(), anyString(), any())).thenThrow(new IllegalStateException("down"));
            var memory = conversation();
            plainTurn(memory, "Hello", "Hi");

            service.afterTurn(memory, false);

            assertEquals(1.0, meters.counter(ConversationTitleService.METRIC, "outcome", "error").count());
        }
    }

    @Nested
    @DisplayName("model-written title")
    class ModelTitle {

        @Test
        @DisplayName("off by default: no model call")
        void offByDefault() {
            var memory = conversation();
            plainTurn(memory, "Hello", "Hi");

            service.afterTurn(memory, false);

            verifyNoInteractions(summarizationService);
        }

        @Test
        @DisplayName("on: the first exchange goes to the model and its answer is written as LLM")
        void writesLlmTitle() throws Exception {
            enableLlmTitles();
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), eq("openai"), eq("gpt-4o-mini"), any()))
                    .thenReturn(new SummarizationService.SummarizationResult("\"Vienna weather\"", 1000, 500));
            var memory = conversation();
            plainTurn(memory, "What is the weather in Vienna?", "Sunny, 24 degrees.");

            service.afterTurn(memory, false);

            var content = ArgumentCaptor.forClass(String.class);
            verify(summarizationService).summarizeWithUsage(content.capture(), anyString(), eq("openai"), eq("gpt-4o-mini"), any());
            assertTrue(content.getValue().contains("User: What is the weather in Vienna?"), content.getValue());
            assertTrue(content.getValue().contains("Agent: Sunny, 24 degrees."), content.getValue());
            verify(descriptorStore).writeGeneratedTitle("conv-1", "Vienna weather", TitleSource.LLM);
        }

        @Test
        @DisplayName("its cost is charged to the tenant and reported as CONVERSATION_TITLE")
        void costTracked() throws Exception {
            enableLlmTitles();
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(new SummarizationService.SummarizationResult("Vienna weather", 1000, 500));
            var memory = conversation();
            plainTurn(memory, "What is the weather in Vienna?", "Sunny.");

            service.afterTurn(memory, false);

            // 1000 input tokens at $1/1M + 500 output tokens at $2/1M = $0.002
            verify(quotaService).recordCost(eq("tenant"), doubleThat(usd -> Math.abs(usd - 0.002) < 1e-9));
            var event = ArgumentCaptor.forClass(SpendEvent.class);
            verify(spendSinks).fire(event.capture());
            assertEquals(SpendEvent.Kind.CONVERSATION_TITLE, event.getValue().kind());
            assertEquals("conv-1", event.getValue().conversationId());
            assertEquals("agent-1", event.getValue().agentId());
            assertEquals("alice", event.getValue().userId());
            assertEquals(1000, event.getValue().inputTokens());
        }

        @Test
        @DisplayName("an owner's rename wins: the LLM write is refused by the store and the title stays")
        void renameWins() throws Exception {
            enableLlmTitles();
            when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(new SummarizationService.SummarizationResult("Vienna weather", 10, 5));
            // AUTO lands; by the time the model answers the owner has renamed it, so the
            // store's guard (titleSource USER) refuses the LLM write.
            when(descriptorStore.writeGeneratedTitle("conv-1", "Vienna weather", TitleSource.LLM)).thenReturn(false);
            var memory = conversation();
            plainTurn(memory, "What is the weather in Vienna?", "Sunny.");

            service.afterTurn(memory, false);

            verify(descriptorStore).writeGeneratedTitle("conv-1", "Vienna weather", TitleSource.LLM);
            assertEquals(1.0, meters.counter(ConversationTitleService.METRIC, "outcome", "llm_skipped").count());
            verify(descriptorStore, never()).writeOwnerEdit(anyString(), any(), any());
        }

        @Test
        @DisplayName("no model call when the default title could not be written (the owner titled it first)")
        void ownerTitledFirst() throws Exception {
            enableLlmTitles();
            when(descriptorStore.writeGeneratedTitle(anyString(), anyString(), eq(TitleSource.AUTO))).thenReturn(false);
            var memory = conversation();
            plainTurn(memory, "Hello", "Hi");

            service.afterTurn(memory, false);

            verifyNoInteractions(summarizationService);
        }

        @Test
        @DisplayName("a spent budget refuses the call")
        void budgetRefuses() throws Exception {
            enableLlmTitles();
            when(quotaService.checkCostBudget(anyString(), anyDouble())).thenReturn(QuotaCheckResult.denied("over budget"));
            var memory = conversation();
            plainTurn(memory, "Hello", "Hi");

            service.afterTurn(memory, false);

            verifyNoInteractions(summarizationService);
            assertEquals(1.0, meters.counter(ConversationTitleService.METRIC, "outcome", "llm_refused_budget").count());
        }
    }

    @Test
    @DisplayName("model answers are unquoted and unprefixed")
    void stripQuotes() {
        assertEquals("Vienna weather", ConversationTitleService.stripQuotes("Title: \"Vienna weather\""));
        assertEquals("Vienna weather", ConversationTitleService.stripQuotes("**Vienna weather**"));
    }
}
