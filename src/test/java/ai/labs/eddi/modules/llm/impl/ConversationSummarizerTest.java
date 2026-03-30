package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

    private IConversationMemory createMemoryWithOutputs(int numOutputs) {
        var memory = new ConversationMemory("agent1", 1, "user1");
        // First step is created automatically with the constructor
        for (int i = 1; i < numOutputs; i++) {
            var output = new ConversationOutput();
            output.put("input", "User message " + (i + 1));
            output.put("output", List.of(Map.of("text", "Agent response " + (i + 1))));
            memory.startNextStep(output);
        }
        // Set input/output on the first step as well
        var firstOutput = memory.getConversationOutputs().get(0);
        firstOutput.put("input", "User message 1");
        firstOutput.put("output", List.of(Map.of("text", "Agent response 1")));
        return memory;
    }

    @Test
    void updateIfNeeded_notEnoughTurns_doesNotSummarize() {
        // Given: 3 turns with recentWindow=5 — not enough to summarize
        var memory = createMemoryWithOutputs(3);
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
        var memory = createMemoryWithOutputs(5);
        var config = createConfig(5);

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verifyNoInteractions(summarizationService);
    }

    @Test
    void updateIfNeeded_firstSummarization_createsNewSummary() {
        // Given: 7 turns with recentWindow=5 → should summarize turns 1-2
        var memory = createMemoryWithOutputs(7);
        var config = createConfig(5);

        when(summarizationService.summarize(anyString(), anyString(), eq("anthropic"), eq("claude-sonnet-4-6"))).thenReturn("Summary of turns 1-2");

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verify(summarizationService).summarize(anyString(), anyString(), eq("anthropic"), eq("claude-sonnet-4-6"));
        assertEquals("Summary of turns 1-2", ConversationSummarizer.readSummary(memory));
        assertEquals(2, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    @Test
    void updateIfNeeded_incrementalUpdate_includesPreviousSummary() {
        // Given: Start with summary covering steps 1-2
        var memory = createMemoryWithOutputs(8);
        var config = createConfig(5);

        memory.getConversationProperties().put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, "Previous summary", Scope.conversation));
        memory.getConversationProperties().put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, 2, Scope.conversation));

        when(summarizationService.summarize(contains("Previous summary"), anyString(), anyString(), anyString()))
                .thenReturn("Updated summary of turns 1-3");

        // When: 8 turns - 5 recent = summarize through step 3
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verify(summarizationService).summarize(contains("Previous summary"), anyString(), anyString(), anyString());
        assertEquals("Updated summary of turns 1-3", ConversationSummarizer.readSummary(memory));
        assertEquals(3, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    @Test
    void updateIfNeeded_alreadySummarized_skips() {
        // Given: 7 turns with window=5, already summarized through step 2
        var memory = createMemoryWithOutputs(7);
        var config = createConfig(5);

        memory.getConversationProperties().put(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP,
                new Property(ConversationSummarizer.PROP_SUMMARY_THROUGH_STEP, 2, Scope.conversation));
        memory.getConversationProperties().put(ConversationSummarizer.PROP_RUNNING_SUMMARY,
                new Property(ConversationSummarizer.PROP_RUNNING_SUMMARY, "existing", Scope.conversation));

        // When: summarizeThroughStep = 7-5 = 2, same as already summarized
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        verifyNoInteractions(summarizationService);
    }

    @Test
    void updateIfNeeded_summarizationFails_doesNotStoreSummary() {
        // Given: 7 turns, summarization returns empty
        var memory = createMemoryWithOutputs(7);
        var config = createConfig(5);

        when(summarizationService.summarize(anyString(), anyString(), anyString(), anyString())).thenReturn("");

        // When
        summarizer.updateIfNeeded(memory, config, null);

        // Then
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    @Test
    void updateIfNeeded_withPropertiesExclusion_includesPropertiesInPrompt() {
        // Given
        var memory = createMemoryWithOutputs(7);
        var config = createConfig(5);
        config.setExcludePropertiesFromSummary(true);

        when(summarizationService.summarize(anyString(), contains("ALREADY stored"), anyString(), anyString()))
                .thenReturn("Summary without properties");

        // When
        summarizer.updateIfNeeded(memory, config, "name = John\nlanguage = English");

        // Then
        verify(summarizationService).summarize(anyString(), contains("ALREADY stored"), anyString(), anyString());
    }

    @Test
    void readSummary_noProperty_returnsNull() {
        var memory = createMemoryWithOutputs(1);
        assertNull(ConversationSummarizer.readSummary(memory));
    }

    @Test
    void readSummaryThroughStep_noProperty_returnsZero() {
        var memory = createMemoryWithOutputs(1);
        assertEquals(0, ConversationSummarizer.readSummaryThroughStep(memory));
    }

    @Test
    void renderTurns_producesReadableOutput() {
        var output1 = new ConversationOutput();
        output1.put("input", "Hello");
        output1.put("output", List.of(Map.of("text", "Hi there!")));

        var output2 = new ConversationOutput();
        output2.put("input", "How are you?");
        output2.put("output", List.of(Map.of("text", "I'm doing well.")));

        var outputs = List.of(output1, output2);

        String rendered = ConversationSummarizer.renderTurns(outputs, 0, 2);

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
        assertTrue(rendered.contains("Turn 2 — User: Second"));
        assertTrue(rendered.contains("Turn 3 — User: Third"));
    }
}
