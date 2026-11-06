/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code conversationSummary.citeSources} and {@code onValidationFailure}: the
 * prompt asks for citations, the answer is checked, and a failing answer is
 * kept, retried once or discarded.
 */
@DisplayName("ConversationSummarizer — cited summaries")
class ConversationSummarizerCitationTest {

    private static final String GOOD = "- Budget is 1,000 EUR [turn 1]";
    private static final String BAD = "- Budget is 1.000 EUR [turn 1]";

    private SummarizationService summarizationService;
    private SimpleMeterRegistry registry;
    private ConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizationService = mock(SummarizationService.class);
        registry = new SimpleMeterRegistry();
        summarizer = new ConversationSummarizer(summarizationService, registry);
    }

    private static ConversationSummaryConfig config(boolean citeSources, String policy) {
        var config = new ConversationSummaryConfig();
        config.setEnabled(true);
        config.setRecentWindowSteps(5);
        config.setLlmProvider("openai");
        config.setLlmModel("gpt-test");
        config.setExcludePropertiesFromSummary(false);
        config.setCiteSources(citeSources);
        config.setOnValidationFailure(policy);
        return config;
    }

    /** Seven turns: steps 0 and 1 are summarized, step 1 states the budget. */
    private static IConversationMemory memory() {
        var memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn("conv-cite");
        var outputs = new ArrayList<ConversationOutput>();
        for (int i = 0; i < 7; i++) {
            var output = new ConversationOutput();
            output.put("input", i == 1 ? "My budget is 1,000 EUR" : "Message " + i);
            output.put("output", List.of(Map.of("text", "Noted")));
            outputs.add(output);
        }
        when(memory.getConversationOutputs()).thenReturn(outputs);
        var propsMap = new LinkedHashMap<String, Property>();
        IConversationProperties props = mock(IConversationProperties.class);
        doAnswer(inv -> {
            propsMap.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(props).put(anyString(), any(Property.class));
        when(props.get(anyString())).thenAnswer(inv -> propsMap.get(inv.getArgument(0, String.class)));
        when(memory.getConversationProperties()).thenReturn(props);
        return memory;
    }

    private void answer(String... summaries) {
        var stub = when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()));
        for (String summary : summaries) {
            stub = stub.thenReturn(new SummarizationService.SummarizationResult(summary, 100, 20));
        }
    }

    private double count(String outcome) {
        var counter = registry.find("eddi.summary.citation.checks").tags("source", "conversation", "outcome", outcome).counter();
        return counter != null ? counter.count() : 0;
    }

    private Map<String, Object> run(IConversationMemory memory, ConversationSummaryConfig config, BooleanSupplier admit) {
        return summarizer.updateIfNeeded(memory, config, null, null, null, admit);
    }

    @Test
    void offByDefault_promptUnchangedAndNothingChecked() {
        var config = new ConversationSummaryConfig();
        assertFalse(config.isCiteSources());
        assertEquals("keep", config.getOnValidationFailure());

        var memory = memory();
        answer(BAD);
        run(memory, config(false, "fallback_to_previous"), null);

        var instructions = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarizeWithUsage(anyString(), instructions.capture(), anyString(), anyString(), any());
        assertFalse(instructions.getValue().contains("Cite your sources"));
        // stored although it would fail the check — the check never ran
        assertEquals(BAD, ConversationSummarizer.readSummary(memory));
        assertTrue(registry.find("eddi.summary.citation.checks").counters().isEmpty());
    }

    @Test
    void citeSources_addsTheCitationRulesToTheBuiltInAndACustomPrompt() {
        assertTrue(summarizer.buildPrompt(config(true, null), null).endsWith(SummaryCitations.CONVERSATION_CITATION_INSTRUCTIONS));
        var custom = config(true, null);
        custom.setSummarizationPrompt("Summarize briefly.");
        assertEquals("Summarize briefly.\n\n" + SummaryCitations.CONVERSATION_CITATION_INSTRUCTIONS, summarizer.buildPrompt(custom, null));
        var off = config(false, null);
        off.setSummarizationPrompt("Summarize briefly.");
        assertEquals("Summarize briefly.", summarizer.buildPrompt(off, null));
    }

    @Test
    void aPassingSummaryIsStoredAndCounted() {
        var memory = memory();
        answer(GOOD);
        run(memory, config(true, "fallback_to_previous"), null);
        assertEquals(GOOD, ConversationSummarizer.readSummary(memory));
        assertEquals(2, ConversationSummarizer.readSummaryThroughStep(memory));
        assertEquals(1, count("passed"));
    }

    @Test
    void keep_storesAFailingSummary() {
        var memory = memory();
        answer(BAD);
        run(memory, config(true, "keep"), null);
        assertEquals(BAD, ConversationSummarizer.readSummary(memory));
        assertEquals(1, count("kept"));
    }

    @Test
    void anUnknownPolicyActsAsKeep() {
        var memory = memory();
        answer(BAD);
        run(memory, config(true, "discard"), null);
        assertEquals(BAD, ConversationSummarizer.readSummary(memory));
        assertEquals(1, count("kept"));
    }

    @Test
    void fallback_keepsThePreviousSummaryAndBoundary_andStillReportsTheSpend() {
        var memory = memory();
        memory.getConversationProperties().put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, "- Opened [turn 0]", Scope.conversation));
        memory.getConversationProperties().put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, 1, Scope.conversation));
        answer(BAD);

        var usage = run(memory, config(true, "fallback_to_previous"), null);

        assertEquals("- Opened [turn 0]", ConversationSummarizer.readSummary(memory));
        assertEquals(1, ConversationSummarizer.readSummaryThroughStep(memory));
        assertEquals(120, ((Number) usage.get("totalTokens")).intValue());
        assertEquals(1, count("fallback"));
    }

    @Test
    void fallback_withoutAPreviousSummaryStoresNothing() {
        var memory = memory();
        answer(BAD);
        run(memory, config(true, "fallback_to_previous"), null);
        assertNull(ConversationSummarizer.readSummary(memory));
        assertEquals(0, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    @Test
    void retryOnce_secondAnswerPasses_storedAndBothCallsAccounted() {
        var memory = memory();
        answer(BAD, GOOD);
        int[] admissions = {0};

        var usage = run(memory, config(true, "retry_once"), () -> {
            admissions[0]++;
            return true;
        });

        assertEquals(GOOD, ConversationSummarizer.readSummary(memory));
        assertEquals(2, admissions[0], "the retry is asked of the spend check like the first call");
        assertEquals(200, ((Number) usage.get("inputTokens")).intValue());
        assertEquals(240, ((Number) usage.get("totalTokens")).intValue());
        assertEquals(1, count("retried"));
        assertEquals(0, count("fallback"));

        var contents = ArgumentCaptor.forClass(String.class);
        var instructions = ArgumentCaptor.forClass(String.class);
        verify(summarizationService, times(2)).summarizeWithUsage(contents.capture(), instructions.capture(), anyString(), anyString(), any());
        assertEquals(contents.getAllValues().get(0), contents.getAllValues().get(1), "the retry sends the same, already screened content");
        assertTrue(instructions.getAllValues().get(1).contains("number 1.000"), "the retry is told what failed");
    }

    @Test
    void retryOnce_secondAnswerFailsToo_fallsBack() {
        var memory = memory();
        answer(BAD, BAD);
        var usage = run(memory, config(true, "retry_once"), null);
        assertNull(ConversationSummarizer.readSummary(memory));
        assertEquals(240, ((Number) usage.get("totalTokens")).intValue());
        assertEquals(1, count("fallback"));
        assertEquals(0, count("retried"));
    }

    @Test
    void retryOnce_refusedBySpendCheck_noSecondCall() {
        var memory = memory();
        answer(BAD, GOOD);
        int[] admissions = {0};
        run(memory, config(true, "retry_once"), () -> ++admissions[0] == 1);
        verify(summarizationService, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
        assertNull(ConversationSummarizer.readSummary(memory));
        assertEquals(1, count("fallback"));
    }

    @Test
    void retryOnce_secondCallThrows_fallsBack() {
        var memory = memory();
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new SummarizationService.SummarizationResult(BAD, 100, 20)).thenThrow(new RuntimeException("boom"));
        var usage = run(memory, config(true, "retry_once"), null);
        assertNull(ConversationSummarizer.readSummary(memory));
        assertEquals(120, ((Number) usage.get("totalTokens")).intValue(), "the first call is still reported");
        assertEquals(1, count("fallback"));
    }

    @Test
    void theCheckReadsTheScreenedInput() {
        // A guardrail redacted the budget for the model: the summarizer never saw
        // 1,000, so a summary that states it cannot be backed by the turn.
        var memory = memory();
        answer(GOOD);
        summarizer.updateIfNeeded(memory, config(true, "fallback_to_previous"), null, null, input -> input.replace("1,000", "[redacted]"),
                null);
        verify(summarizationService).summarizeWithUsage(contains("[redacted]"), anyString(), anyString(), anyString(), any());
        assertNull(ConversationSummarizer.readSummary(memory));
        assertEquals(1, count("fallback"));
    }

    @Test
    void stepTextsLeaveOutTheTurnLabels() {
        var outputs = new ArrayList<ConversationOutput>();
        var output = new ConversationOutput();
        output.put("input", "hello");
        output.put("output", List.of(Map.of("text", "hi")));
        outputs.add(output);
        var texts = ConversationSummarizer.stepTexts(outputs, 0, 1, -1);
        assertEquals("hello\nhi", texts.get(0));
        assertEquals("hel", ConversationSummarizer.stepTexts(outputs, 0, 1, 3).get(0));
        verify(summarizationService, never()).summarizeWithUsage(anyString(), anyString(), anyString(), anyString(), any());
    }
}
