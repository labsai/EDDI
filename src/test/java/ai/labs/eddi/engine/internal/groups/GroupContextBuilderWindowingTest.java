/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextWindowConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.llm.impl.SummarizationService.SummarizationResult;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * I9 — transcript windowing in {@link GroupContextBuilder}: the windowed
 * {@code filterByScope} rendering (summary pseudo-entry, truncation fallback,
 * ANONYMOUS label preservation) and the phase-boundary
 * {@code updateWindowSummary} extension (incremental slices, failure fallback,
 * I1 cost attribution).
 *
 * @author tests
 */
class GroupContextBuilderWindowingTest {

    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private SummarizationService summarizationService;

    private GroupContextBuilder builder;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        builder = new GroupContextBuilder(templatingEngine);
    }

    private GroupMember member() {
        return new GroupMember("agent-a", "Agent A", 1, null);
    }

    private DiscussionPhase opinionPhase(ContextScope scope) {
        return new DiscussionPhase("Discussion", PhaseType.OPINION, "ALL", TurnOrder.SEQUENTIAL, scope, false, null, 1, false);
    }

    private TranscriptEntry entry(String name, String content) {
        return new TranscriptEntry("id-" + name, name, content, 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
    }

    /** enabled, cap 3, summarize on, summarizer model configured, unpriced. */
    private ContextWindowConfig window() {
        return new ContextWindowConfig(true, 3, true, "openai", "gpt-4o-mini", null, null);
    }

    private GroupConversation gcWith(TranscriptEntry... entries) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        for (var e : entries) {
            gc.getTranscript().add(e);
        }
        return gc;
    }

    // =================================================================
    // windowed filterByScope — rendering
    // =================================================================

    @Test
    @DisplayName("at exactly maxRecentEntries the context is untouched — the cap must be exceeded, not met")
    void atExactlyTheCap_noWindowing() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"));

        var result = builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), window(), gc);

        assertEquals(3, result.size());
        assertTrue(result.stream().noneMatch(e -> "System".equals(e.get("speaker"))), "no synthetic entry at the boundary");
    }

    @Test
    @DisplayName("over the cap with no summary yet: truncation marker + the recent entries verbatim")
    void overTheCap_noSummary_truncationMarker() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));

        var result = builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), window(), gc);

        assertEquals(4, result.size(), "marker + the 3 recent entries");
        assertEquals("System", result.get(0).get("speaker"));
        assertEquals("[2 earlier entries omitted]", result.get(0).get("content"));
        assertEquals("c3", result.get(1).get("content"));
        assertEquals("c5", result.get(3).get("content"));
    }

    @Test
    @DisplayName("over the cap with a summary: summary pseudo-entry + everything past its boundary verbatim")
    void overTheCap_withSummary_summaryPlusTail() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));
        gc.setTranscriptSummary("A and B agreed on X");
        gc.setSummaryUpToIndex(2);

        var result = builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), window(), gc);

        assertEquals(4, result.size(), "summary + the 3 entries past its boundary");
        assertEquals("System", result.get(0).get("speaker"));
        assertTrue(result.get(0).get("content").toString().contains("A and B agreed on X"));
        assertEquals("c3", result.get(1).get("content"), "the split is the summary's raw boundary — no gap, no duplication");
        assertTrue(result.stream().skip(1).noneMatch(e -> "c1".equals(e.get("content")) || "c2".equals(e.get("content"))),
                "summarized entries must not also render verbatim");
    }

    @Test
    @DisplayName("ANONYMOUS scope windows with the anonymous summary and keeps Anonymous labels")
    void anonymousScope_usesAnonymousSummaryAndLabels() {
        var gc = gcWith(entry("Alice", "c1"), entry("Bob", "c2"), entry("Carol", "c3"), entry("Dave", "c4"), entry("Eve", "c5"));
        gc.setTranscriptSummary("Alice said c1, Bob said c2"); // named — must NOT surface
        gc.setSummaryUpToIndex(2);
        gc.setAnonymousTranscriptSummary("Two participants proposed X");
        gc.setAnonymousSummaryUpToIndex(2);

        var result = builder.filterByScope(gc.getTranscript(), ContextScope.ANONYMOUS, 1, member(), window(), gc);

        assertTrue(result.get(0).get("content").toString().contains("Two participants proposed X"));
        assertFalse(result.get(0).get("content").toString().contains("Alice"),
                "an ANONYMOUS phase must never render the named FULL summary — that would de-anonymize");
        assertTrue(result.stream().skip(1).allMatch(e -> "Anonymous".equals(e.get("speaker"))));
    }

    @Test
    @DisplayName("a null or disabled window renders exactly as before the feature existed")
    void disabledWindow_fullList() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));

        assertEquals(5, builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), null, gc).size());
        var disabled = new ContextWindowConfig(false, 3, true, null, null, null, null);
        assertEquals(5, builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), disabled, gc).size());
    }

    // =================================================================
    // updateWindowSummary — the phase-boundary extension
    // =================================================================

    @Test
    @DisplayName("first boundary summarizes exactly the overflow slice; the next extends with only the new slice")
    void incrementalExtension_summarizerSeesOnlyTheNewSlice() {
        var gc = gcWith(entry("A", "aaa-1"), entry("B", "bbb-2"), entry("C", "ccc-3"), entry("D", "ddd-4"), entry("E", "eee-5"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(new SummarizationResult("SUMMARY-1", 10, 5));

        builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), window(), summarizationService);

        var content = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarizeWithUsage(content.capture(), anyString(), eq("openai"), eq("gpt-4o-mini"));
        assertTrue(content.getValue().contains("aaa-1") && content.getValue().contains("bbb-2"),
                "the overflow slice [0, size-cap) is what gets summarized");
        assertFalse(content.getValue().contains("ccc-3"), "entries inside the recent window must not be summarized");
        assertEquals("SUMMARY-1", gc.getTranscriptSummary());
        assertEquals(2, gc.getSummaryUpToIndex());

        // Discussion grows; the next boundary extends incrementally.
        gc.getTranscript().add(entry("F", "fff-6"));
        gc.getTranscript().add(entry("G", "ggg-7"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), eq("openai"), eq("gpt-4o-mini")))
                .thenReturn(new SummarizationResult("SUMMARY-2", 10, 5));

        builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), window(), summarizationService);

        verify(summarizationService, times(2)).summarizeWithUsage(content.capture(), anyString(), eq("openai"), eq("gpt-4o-mini"));
        String secondContent = content.getValue();
        assertTrue(secondContent.contains("SUMMARY-1"), "the previous summary is the compressed form of the old slice");
        assertTrue(secondContent.contains("ccc-3") && secondContent.contains("ddd-4"), "only the new slice rides along verbatim");
        assertFalse(secondContent.contains("aaa-1"), "already-summarized entries must not be re-fed — that is the incremental contract");
        assertEquals("SUMMARY-2", gc.getTranscriptSummary());
        assertEquals(4, gc.getSummaryUpToIndex());
    }

    @Test
    @DisplayName("a summarizer failure leaves the stored state untouched — rendering falls back to truncation")
    void summarizerFailure_stateUntouched() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("provider down"));

        assertDoesNotThrow(() -> builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), window(), summarizationService),
                "a summarization failure must never block the discussion");
        assertNull(gc.getTranscriptSummary());
        assertEquals(0, gc.getSummaryUpToIndex());

        // And rendering degrades to the plain truncation marker, not an error.
        var rendered = builder.filterByScope(gc.getTranscript(), ContextScope.FULL, 1, member(), window(), gc);
        assertEquals("[2 earlier entries omitted]", rendered.get(0).get("content"));
    }

    @Test
    @DisplayName("an empty summarizer answer is not adopted — the boundary index must not advance past unsummarized entries")
    void emptySummary_notAdopted() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SummarizationResult("", 10, 5));

        builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), window(), summarizationService);

        assertNull(gc.getTranscriptSummary());
        assertEquals(0, gc.getSummaryUpToIndex());
    }

    @Test
    @DisplayName("ANONYMOUS boundary: the summarizer input carries Anonymous labels, never real names")
    void anonymousBoundary_inputIsAnonymized() {
        var gc = gcWith(entry("Alice", "c1"), entry("Bob", "c2"), entry("Carol", "c3"), entry("Dave", "c4"), entry("Eve", "c5"));
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SummarizationResult("anon summary", 10, 5));

        builder.updateWindowSummary(gc, opinionPhase(ContextScope.ANONYMOUS), window(), summarizationService);

        var content = ArgumentCaptor.forClass(String.class);
        verify(summarizationService).summarizeWithUsage(content.capture(), anyString(), anyString(), anyString());
        assertFalse(content.getValue().contains("Alice") || content.getValue().contains("Bob"),
                "no de-anonymization: the summarizer must not even SEE the names");
        assertTrue(content.getValue().contains("Anonymous"));
        assertEquals("anon summary", gc.getAnonymousTranscriptSummary());
        assertNull(gc.getTranscriptSummary(), "the FULL variant is a separate summary and must stay untouched");
    }

    @Test
    @DisplayName("a priced window attributes the summarizer's own spend to the discussion's I1 ledger")
    void pricedSummarization_costReachesTheLedger() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));
        var priced = new ContextWindowConfig(true, 3, true, "openai", "gpt-4o-mini", 1.0, 2.0);
        when(summarizationService.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SummarizationResult("S", 1000, 500));

        builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), priced, summarizationService);

        // 1000/1M * $1.00 + 500/1M * $2.00
        assertEquals(0.002, gc.getTotalCost(), 1e-9, "summarization cost counts toward the discussion (I1)");
    }

    @Test
    @DisplayName("no boundary work outside its remit: wrong phase type, wrong scope, below cap, or summarization off")
    void noCallOutsideItsRemit() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));

        var critique = new DiscussionPhase("Critique", PhaseType.CRITIQUE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        builder.updateWindowSummary(gc, critique, window(), summarizationService);
        builder.updateWindowSummary(gc, opinionPhase(ContextScope.LAST_PHASE), window(), summarizationService);
        var noSummarize = new ContextWindowConfig(true, 3, false, "openai", "gpt-4o-mini", null, null);
        builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), noSummarize, summarizationService);
        var smallGc = gcWith(entry("A", "c1"), entry("B", "c2"));
        builder.updateWindowSummary(smallGc, opinionPhase(ContextScope.FULL), window(), summarizationService);

        verifyNoInteractions(summarizationService);
    }

    @Test
    @DisplayName("no summarizer service (unit-test wiring) degrades to truncation instead of throwing")
    void nullService_noThrow() {
        var gc = gcWith(entry("A", "c1"), entry("B", "c2"), entry("C", "c3"), entry("D", "c4"), entry("E", "c5"));

        assertDoesNotThrow(() -> builder.updateWindowSummary(gc, opinionPhase(ContextScope.FULL), window(), null));
        assertNull(gc.getTranscriptSummary());
    }

    // =================================================================
    // config normalization
    // =================================================================

    @Test
    @DisplayName("config normalization: non-positive cap falls back to the default, omitted summarizeOverflow means true")
    void configNormalization() {
        var window = new ContextWindowConfig(true, 0, null, null, null, -1.0, null);

        assertEquals(ContextWindowConfig.DEFAULT_MAX_RECENT_ENTRIES, window.maxRecentEntries(),
                "an accidental 0 must not blank the whole context");
        assertEquals(Boolean.TRUE, window.summarizeOverflow(), "summarization is the default overflow behaviour");
        assertNull(window.inputPricePer1M(), "negative prices normalize to unpriced");
    }
}
