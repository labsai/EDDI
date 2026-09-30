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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationSummarizerTest {

    private SummarizationService summarizationService;
    private ConversationSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizationService = mock(SummarizationService.class);
        summarizer = new ConversationSummarizer(summarizationService);
    }

    private ConversationSummaryConfig createConfig(int recentWindowSteps) {
        var config = new ConversationSummaryConfig();
        config.setEnabled(true);
        config.setRecentWindowSteps(recentWindowSteps);
        config.setLlmProvider("anthropic");
        config.setLlmModel("claude-sonnet-4-6");
        config.setMaxSummaryTokens(800);
        config.setExcludePropertiesFromSummary(false);
        return config;
    }

    /**
     * Create a mock IConversationMemory with the specified number of conversation
     * outputs. Each output has "input" and "output" keys populated with test data.
     */
    private IConversationMemory createMockMemory(int numOutputs) {
        var memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn("conv-test-001");

        // Build conversation outputs
        var outputs = new ArrayList<ConversationOutput>();
        for (int i = 0; i < numOutputs; i++) {
            var output = new ConversationOutput();
            output.put("input", "User message " + (i + 1));
            output.put("output", List.of(Map.of("text", "Agent response " + (i + 1))));
            outputs.add(output);
        }
        when(memory.getConversationOutputs()).thenReturn(outputs);

        // Use a real map-backed properties implementation
        var propsMap = new LinkedHashMap<String, Property>();

        IConversationProperties props = mock(IConversationProperties.class);
        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Property val = inv.getArgument(1);
            propsMap.put(key, val);
            return null;
        }).when(props).put(anyString(), any(Property.class));

        when(props.get(anyString())).thenAnswer(inv -> propsMap.get(inv.getArgument(0, String.class)));
        when(props.isEmpty()).thenAnswer(inv -> propsMap.isEmpty());
        when(props.entrySet()).thenAnswer(inv -> propsMap.entrySet());

        when(memory.getConversationProperties()).thenReturn(props);

        return memory;
    }

    @Test
    void updateIfNeeded_notEnoughTurns_doesNotSummarize() {
        // Given: 3 turns with recentWindow=5 — not enough to summarize
        var memory = createMockMemory(3);
        var config = createConfig(5);

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verifyNoInteractions(summarizationService);
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    @Test
    void updateIfNeeded_exactlyAtWindow_doesNotSummarize() {
        // Given: 5 turns with recentWindow=5 — summarizeThroughStep=0
        var memory = createMockMemory(5);
        var config = createConfig(5);

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verifyNoInteractions(summarizationService);
    }

    @Test
    void updateIfNeeded_firstSummarization_createsNewSummary() {
        // Given: 7 turns with recentWindow=5 → should summarize turns 1-2
        var memory = createMockMemory(7);
        var config = createConfig(5);

        when(summarizationService.summarize(anyString(), anyString(), eq("anthropic"), eq("claude-sonnet-4-6"), any()))
                .thenReturn("Summary of turns 1-2");

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verify(summarizationService).summarize(anyString(), anyString(), eq("anthropic"), eq("claude-sonnet-4-6"), any());
        assertEquals("Summary of turns 1-2", ConversationSummarizer.readSummary(memory));
        assertEquals(2, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    @Test
    void updateIfNeeded_incrementalUpdate_includesPreviousSummary() {
        // Given: 8 turns with recentWindow=5, summary already covers steps 1-2
        var memory = createMockMemory(8);
        var config = createConfig(5);

        // Pre-set existing summary
        var props = memory.getConversationProperties();
        props.put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, "Previous summary", Scope.conversation));
        props.put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, 2, Scope.conversation));

        when(summarizationService.summarize(contains("Previous summary"), anyString(), anyString(), anyString(), any()))
                .thenReturn("Updated summary of turns 1-3");

        // When: 8 turns - 5 recent = summarize through step 3
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verify(summarizationService).summarize(contains("Previous summary"), anyString(), anyString(), anyString(), any());
        assertEquals("Updated summary of turns 1-3", ConversationSummarizer.readSummary(memory));
        assertEquals(3, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    /**
     * Finding F13: the summarizer could never authenticate, because the parameter
     * map reaching {@link SummarizationService} carried only {@code modelName} — no
     * {@code apiKey}, no {@code baseUrl}. The call then threw, the exception was
     * swallowed as a WARN, and the rolling summary silently never materialised.
     * <p>
     * Resolving the provider and model is only half the fix; the parent task's
     * resolved parameters have to survive the hop through this class. Pin that,
     * because a summarizer that quietly produces nothing looks identical to one
     * that is simply not enabled.
     */
    @Test
    void updateIfNeeded_passesTheParentTasksCredentialsThrough() {
        var memory = createMockMemory(7);
        var config = createConfig(5);
        var inherited = Map.of("apiKey", "parent-key", "baseUrl", "https://parent.example/v1");

        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("Summary of turns 1-2");

        summarizer.updateIfNeeded(memory, config, null, inherited);

        var captured = ArgumentCaptor.forClass(Map.class);
        verify(summarizationService).summarize(anyString(), anyString(), anyString(), anyString(), captured.capture());
        assertNotNull(captured.getValue(), "inherited parameters must not be dropped on the way to the service");
        assertEquals("parent-key", captured.getValue().get("apiKey"));
        assertEquals("https://parent.example/v1", captured.getValue().get("baseUrl"));
    }

    @Test
    void updateIfNeeded_alreadySummarized_skips() {
        // Given: 7 turns with window=5, already summarized through step 2
        var memory = createMockMemory(7);
        var config = createConfig(5);

        var props = memory.getConversationProperties();
        props.put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, 2, Scope.conversation));
        props.put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, "existing", Scope.conversation));

        // When: summarizeThroughStep = 7-5 = 2, same as already summarized
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verifyNoInteractions(summarizationService);
    }

    @Test
    void updateIfNeeded_summarizationFails_doesNotStoreSummary() {
        // Given: 7 turns, summarization returns empty
        var memory = createMockMemory(7);
        var config = createConfig(5);

        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("");

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then: no summary stored
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    @Test
    void updateIfNeeded_withPropertiesExclusion_includesPropertiesInPrompt() {
        // Given
        var memory = createMockMemory(7);
        var config = createConfig(5);
        config.setExcludePropertiesFromSummary(true);

        when(summarizationService.summarize(anyString(), contains("ALREADY stored"), anyString(), anyString(), any()))
                .thenReturn("Summary without properties");

        // When
        summarizer.updateIfNeeded(memory, config, "name = John\nlanguage = English");

        // Then
        verify(summarizationService).summarize(anyString(), contains("ALREADY stored"), anyString(), anyString(), any());
    }

    @Test
    void readSummary_noProperty_returnsNull() {
        var memory = createMockMemory(1);
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    @Test
    void readSummaryThroughStep_noProperty_returnsZero() {
        var memory = createMockMemory(1);
        assertEquals(0, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    /**
     * Turns are labelled by step, as the recall tool reads them: turn 0 is the
     * opening step (no user input), turn 1 the user's first message. Labels used to
     * be step + 1, so the user's first message was "turn 2".
     */
    @Test
    void renderTurns_producesReadableOutput() {
        var greeting = new ConversationOutput();
        greeting.put("input", "");
        greeting.put("output", List.of(Map.of("text", "Welcome!")));

        var output1 = new ConversationOutput();
        output1.put("input", "Hello");
        output1.put("output", List.of(Map.of("text", "Hi there!")));

        var output2 = new ConversationOutput();
        output2.put("input", "How are you?");
        output2.put("output", List.of(Map.of("text", "I'm doing well.")));

        String rendered = ConversationSummarizer.renderTurns(List.of(greeting, output1, output2), 0, 3);

        assertTrue(rendered.contains("Turn 0 — Agent: Welcome!"));
        assertFalse(rendered.contains("Turn 0 — User:"), "the opening step has no user input");
        assertTrue(rendered.contains("Turn 1 — User: Hello"));
        assertTrue(rendered.contains("Turn 1 — Agent: Hi there!"));
        assertTrue(rendered.contains("Turn 2 — User: How are you?"));
        assertTrue(rendered.contains("Turn 2 — Agent: I'm doing well."));
    }

    @Test
    void renderTurns_handlesPartialRange() {
        var output1 = new ConversationOutput();
        output1.put("input", "First");
        output1.put("output", List.of(Map.of("text", "Reply 1")));

        var output2 = new ConversationOutput();
        output2.put("input", "Second");
        output2.put("output", List.of(Map.of("text", "Reply 2")));

        var output3 = new ConversationOutput();
        output3.put("input", "Third");
        output3.put("output", List.of(Map.of("text", "Reply 3")));

        String rendered = ConversationSummarizer.renderTurns(List.of(output1, output2, output3), 1, 3);

        assertFalse(rendered.contains("First")); // skipped
        assertTrue(rendered.contains("Turn 1 — User: Second"));
        assertTrue(rendered.contains("Turn 2 — User: Third"));
    }

    @Test
    void updateIfNeeded_idempotent_noDoubleSummarization() {
        // Given: 7 turns with recentWindow=5
        var memory = createMockMemory(7);
        var config = createConfig(5);

        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Summary text");

        // When: call twice
        summarizer.updateIfNeeded(memory, config, null);
        summarizer.updateIfNeeded(memory, config, null);

        // Then: summarization service called only once (second call skips because
        // already summarized)
        verify(summarizationService, times(1)).summarize(anyString(), anyString(), anyString(), anyString(), any());
    }
    /**
     * M-L3: a backlog is caught up in bounded batches. Sending all of it at once
     * eventually exceeded the summarizer's context window, after which every turn
     * made a failing, paid summarizer call.
     */
    @Test
    void updateIfNeeded_largeBacklog_summarizesAtMostMaxTurnsPerUpdate() {
        var memory = createMockMemory(105);
        var config = createConfig(5);
        config.setMaxTurnsPerUpdate(20);
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Batch summary");

        summarizer.updateIfNeeded(memory, config, null);

        var content = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarize(content.capture(), anyString(), anyString(), anyString(), any());
        assertTrue(content.getValue().contains("User message 20"));
        assertFalse(content.getValue().contains("User message 21"), "turn 21 belongs to the next batch");
        assertEquals(20, ConversationSummarizer.readSummaryThroughStep(memory),
                "the summary records what it actually covers, so the next update continues from turn 21");
    }

    /**
     * M-L3: the batch is shortened turn by turn until it fits the character
     * ceiling, and a single turn larger than the ceiling is cut.
     */
    @Test
    void updateIfNeeded_oversizedBatch_isShortenedToFitMaxChars() {
        var memory = createMockMemory(12);
        var config = createConfig(2);
        config.setMaxCharsPerUpdate(1000);
        memory.getConversationOutputs().get(1).put("input", "x".repeat(880));
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Batch summary");

        summarizer.updateIfNeeded(memory, config, null);

        var content = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarize(content.capture(), anyString(), anyString(), anyString(), any());
        assertTrue(content.getValue().length() <= 1000, "batch of " + content.getValue().length() + " chars");
        assertEquals(2, ConversationSummarizer.readSummaryThroughStep(memory),
                "turns 1-2 render to ~990 chars; adding turn 3 would pass 1000, so it waits for the next update");

        // A first turn larger than the whole budget is cut rather than blocking
        // forever.
        var hugeFirst = createMockMemory(12);
        hugeFirst.getConversationOutputs().getFirst().put("input", "y".repeat(5000));
        summarizer.updateIfNeeded(hugeFirst, config, null);
        assertEquals(1, ConversationSummarizer.readSummaryThroughStep(hugeFirst));

        // ...and the cut-notice fits inside the budget as well.
        var inputs = ArgumentCaptor.forClass(String.class);
        verify(summarizationService, times(2)).summarize(inputs.capture(), anyString(), anyString(), anyString(), any());
        String cut = inputs.getAllValues().get(1);
        assertTrue(cut.length() <= 1000, "oversized first-turn input was " + cut.length() + " chars");
        assertTrue(cut.endsWith("input budget ...]"), "the cut is announced to the summarizer");
    }
    /**
     * A window with nothing to summarize is stepped over (no LLM call), so a
     * bounded batch cannot get stuck re-reading it on every turn.
     */
    @Test
    void updateIfNeeded_blankWindow_advancesWithoutCallingTheLlm() {
        var memory = createMockMemory(10);
        for (var output : memory.getConversationOutputs().subList(0, 3)) {
            output.remove("input");
            output.remove("output");
        }
        var config = createConfig(7);

        summarizer.updateIfNeeded(memory, config, null);

        verifyNoInteractions(summarizationService);
        assertEquals(3, ConversationSummarizer.readSummaryThroughStep(memory));
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    private static void presetSummary(IConversationMemory memory, String summary, int throughStep) {
        var props = memory.getConversationProperties();
        props.put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, summary, Scope.conversation));
        props.put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, throughStep, Scope.conversation));
    }

    /**
     * M-L3 follow-up: {@code maxCharsPerUpdate} bounds the whole request. The
     * previous summary and its headings used to be added after the new turns had
     * already been fitted to the full budget, so an incremental update could exceed
     * it by the size of the summary.
     */
    @Test
    void updateIfNeeded_withPreviousSummary_wholeRequestFitsMaxChars() {
        String previous = "p".repeat(400);
        var memory = createMockMemory(30);
        presetSummary(memory, previous, 2);
        var config = createConfig(2);
        config.setMaxCharsPerUpdate(1000);
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("Batch summary");

        summarizer.updateIfNeeded(memory, config, null);

        var content = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarize(content.capture(), anyString(), anyString(), anyString(), any());
        assertTrue(content.getValue().length() <= 1000, "request of " + content.getValue().length() + " chars");
        assertTrue(content.getValue().contains(previous), "the previous summary is sent whole, never cut");
        assertTrue(ConversationSummarizer.readSummaryThroughStep(memory) > 2, "the update still makes progress");

        // The cut of a single oversized turn is sized to what the summary leaves.
        var hugeTurn = createMockMemory(30);
        presetSummary(hugeTurn, previous, 2);
        hugeTurn.getConversationOutputs().get(2).put("input", "y".repeat(5000));
        summarizer.updateIfNeeded(hugeTurn, config, null);

        var inputs = ArgumentCaptor.forClass(String.class);
        verify(summarizationService, times(2)).summarize(inputs.capture(), anyString(), anyString(), anyString(), any());
        String cut = inputs.getAllValues().get(1);
        assertTrue(cut.length() <= 1000, "request with a cut turn was " + cut.length() + " chars");
        assertTrue(cut.contains(previous));
        assertTrue(cut.endsWith("input budget ...]"));
        assertEquals(3, ConversationSummarizer.readSummaryThroughStep(hugeTurn));
    }

    /**
     * A previous summary that leaves the new turns less than a quarter of the
     * budget is neither cut nor sent over budget: the update is skipped, with no
     * model call, and the boundary stays put so those turns keep reaching the model
     * verbatim.
     */
    @Test
    void updateIfNeeded_previousSummaryFillsTheBudget_skipsWithoutCallingTheLlm() {
        String previous = "p".repeat(900);
        var memory = createMockMemory(30);
        presetSummary(memory, previous, 2);
        var config = createConfig(2);
        config.setMaxCharsPerUpdate(1000);
        // An empty reply is the failure mode that made the retry loop: stub it so a
        // call, if one is made, is caught by the interaction check below.
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("");

        summarizer.updateIfNeeded(memory, config, null);

        verifyNoInteractions(summarizationService);
        assertEquals(2, ConversationSummarizer.readSummaryThroughStep(memory));
        assertEquals(previous, ConversationSummarizer.readSummary(memory));
    }

    /**
     * A blank window must be stepped over even when a running summary exists.
     * Combined with the summary the content was never blank, so the guard did not
     * fire: the model was paid to re-summarize an unchanged summary, and an empty
     * reply left the boundary in place to retry the same blank window next turn.
     */
    @Test
    void updateIfNeeded_blankWindowWithPreviousSummary_advancesWithoutCallingTheLlm() {
        var memory = createMockMemory(10);
        presetSummary(memory, "Previous summary", 2);
        for (var output : memory.getConversationOutputs().subList(2, 5)) {
            output.remove("input");
            output.remove("output");
        }
        var config = createConfig(5);
        // An empty reply is the failure mode that made the retry loop: stub it so a
        // call, if one is made, is caught by the interaction check below.
        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString(), any())).thenReturn("");

        summarizer.updateIfNeeded(memory, config, null);

        verifyNoInteractions(summarizationService);
        assertEquals(5, ConversationSummarizer.readSummaryThroughStep(memory));
        assertEquals("Previous summary", ConversationSummarizer.readSummary(memory));
    }
}
