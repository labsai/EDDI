/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.StanceSummaryConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.MemberStance;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.CostUpdatedEvent;
import ai.labs.eddi.modules.llm.impl.SummarizationService;
import ai.labs.eddi.modules.llm.impl.SummarizationService.SummarizationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StanceSummaryEngine} — the overview dashboard's "who
 * thinks what" producer.
 * <p>
 * The behaviours worth guarding are the ones a reader would otherwise have to
 * take on trust: that the zero-cost extraction path really is the default
 * rather than a failure mode, that a summarizer failure degrades to it instead
 * of blanking the band, that a member with nothing new to say is not paid for
 * twice, and that stance spend reaches the same I1 ledger every other
 * discussion cost does.
 *
 * @author ginccc
 */
class StanceSummaryEngineTest {

    private static final String AGENT_A = "5f2e4c1a9b8d7e6f3a2b1c0d";
    private static final String AGENT_B = "6a3f5d2b0c9e8f7a4b3c2d1e";

    private static GroupConversation conversation() {
        var gc = new GroupConversation();
        gc.setId("7b4e6f3c1d0a9b8e5f4a3c2d");
        gc.addMemberDisplayName(AGENT_A, "Architect");
        gc.addMemberDisplayName(AGENT_B, "Security");
        return gc;
    }

    private static TranscriptEntry entry(String agentId, String content, TranscriptEntryType type) {
        return new TranscriptEntry(agentId, "n/a", content, 0, "Opinions", type, Instant.now(), null, null);
    }

    private static SummarizationService summarizerReturning(String summary, int inTokens, int outTokens) {
        var service = mock(SummarizationService.class);
        when(service.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new SummarizationResult(summary, inTokens, outTokens));
        return service;
    }

    /** A fully configured, priced summarizer. */
    private static StanceSummaryConfig pricedConfig() {
        return new StanceSummaryConfig(160, "openai", "gpt-4o-mini", 1.0, 2.0);
    }

    @Nested
    @DisplayName("extraction (the default producer)")
    class Extraction {

        @Test
        @DisplayName("a null config extracts the lead sentence in the member's own words")
        void nullConfigExtracts() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "We should adopt pgvector. It halves our operational surface.",
                    TranscriptEntryType.OPINION));

            var results = StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals(1, results.size());
            var stance = gc.getMemberStances().get(AGENT_A);
            assertNotNull(stance);
            assertEquals("We should adopt pgvector.", stance.text());
            assertFalse(stance.llmGenerated(), "extraction must not be advertised as generated — it is a quote");
        }

        @Test
        @DisplayName("extraction costs nothing — the ledger stays empty")
        void extractionIsFree() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Adopt it.", TranscriptEntryType.OPINION));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertTrue(gc.getMemberCosts().isEmpty(), "no LLM ran, so nothing may be billed");
            assertEquals(0.0, gc.getTotalCost());
        }

        @Test
        @DisplayName("the newest stance-bearing entry wins, not the first")
        void newestEntryWins() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Initially I favour Milvus.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_A, "On reflection pgvector is better.", TranscriptEntryType.REVISION));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals("On reflection pgvector is better.", gc.getMemberStances().get(AGENT_A).text());
        }

        @Test
        @DisplayName("a config with only half the provider/model pair still extracts")
        void halfConfiguredExtracts() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Adopt pgvector.", TranscriptEntryType.OPINION));
            var halfConfig = new StanceSummaryConfig(160, "openai", null, null, null);
            var service = summarizerReturning("generated", 10, 10);

            StanceSummaryEngine.updateStances(gc, halfConfig, null, service);

            // verifyNoInteractions, not verify(never()) with anyString(): a
            // half-configured summarizer passes a NULL model, which anyString()
            // does not match — the negative assertion would hold even if the
            // call were made.
            verifyNoInteractions(service);
            assertFalse(gc.getMemberStances().get(AGENT_A).llmGenerated());
        }
    }

    @Nested
    @DisplayName("entry-type selection")
    class EntryTypes {

        @Test
        @DisplayName("bookkeeping and other-voice entries never become a stance")
        void nonStanceBearingIgnored() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Should we migrate?", TranscriptEntryType.QUESTION));
            gc.getTranscript().add(entry(AGENT_A, "The group broadly agrees.", TranscriptEntryType.SYNTHESIS));
            gc.getTranscript().add(entry(AGENT_A, "Timed out.", TranscriptEntryType.ERROR));
            gc.getTranscript().add(entry(AGENT_A, "Skipped.", TranscriptEntryType.SKIPPED));
            gc.getTranscript().add(entry(AGENT_A, "Agreement score 0.8.", TranscriptEntryType.CONVERGENCE));

            var results = StanceSummaryEngine.updateStances(gc, null, null, null);

            assertTrue(results.isEmpty());
            assertTrue(gc.getMemberStances().isEmpty());
        }

        @Test
        @DisplayName("ABSTAINED does not overwrite the position the member actually took")
        void abstentionDoesNotReplaceRealStance() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "pgvector is the right call.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_A, "I have nothing to add.", TranscriptEntryType.ABSTAINED));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals("pgvector is the right call.", gc.getMemberStances().get(AGENT_A).text());
        }

        @Test
        @DisplayName("each member gets their own stance")
        void perMemberStances() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Adopt pgvector.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_B, "Reject it on security grounds.", TranscriptEntryType.CRITIQUE));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals("Adopt pgvector.", gc.getMemberStances().get(AGENT_A).text());
            assertEquals("Reject it on security grounds.", gc.getMemberStances().get(AGENT_B).text());
        }
    }

    @Nested
    @DisplayName("LLM summarization")
    class Summarization {

        @Test
        @DisplayName("a configured summarizer produces the stance and is marked as generated")
        void summarizerRuns() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A long argument about vector stores.", TranscriptEntryType.OPINION));
            var service = summarizerReturning("Favours pgvector, conditional on a dual-write window.", 100, 20);

            var results = StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            assertEquals(1, results.size());
            var stance = gc.getMemberStances().get(AGENT_A);
            assertEquals("Favours pgvector, conditional on a dual-write window.", stance.text());
            assertTrue(stance.llmGenerated());
        }

        @Test
        @DisplayName("stance spend lands in the same I1 ledger every other cost does")
        void costIsBilledToTheLedger() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "An argument.", TranscriptEntryType.OPINION));
            // 1M input @ $1 + 0.5M output @ $2 = $2.00
            var service = summarizerReturning("Favours pgvector.", 1_000_000, 500_000);

            var results = StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            assertEquals(2.0, gc.getTotalCost(), 1e-9);
            assertEquals(2.0, gc.getMemberCosts().get("system:stance:" + AGENT_A + ":1"), 1e-9);
            assertEquals(2.0, results.get(0).cost(), 1e-9);
        }

        @Test
        @DisplayName("an unpriced summarizer still runs, and bills nothing")
        void unpricedSummarizerIsFree() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "An argument.", TranscriptEntryType.OPINION));
            var config = new StanceSummaryConfig(160, "openai", "gpt-4o-mini", null, null);

            StanceSummaryEngine.updateStances(gc, config, null, summarizerReturning("Favours pgvector.", 1_000_000, 1_000_000));

            assertTrue(gc.getMemberStances().get(AGENT_A).llmGenerated());
            assertEquals(0.0, gc.getTotalCost());
        }
    }

    @Nested
    @DisplayName("failure degrades to extraction, never to nothing")
    class Degradation {

        @Test
        @DisplayName("a throwing summarizer falls back to the member's own words")
        void throwingSummarizerFallsBack() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "We should adopt pgvector. It is cheaper.", TranscriptEntryType.OPINION));
            var service = mock(SummarizationService.class);
            when(service.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("model unavailable"));

            var results = StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            assertEquals(1, results.size());
            var stance = gc.getMemberStances().get(AGENT_A);
            assertEquals("We should adopt pgvector.", stance.text());
            assertFalse(stance.llmGenerated(), "a fallback must not claim to be generated");
            assertEquals(0.0, gc.getTotalCost(), "a failed call bills nothing");
        }

        @Test
        @DisplayName("a blank summary falls back rather than storing an empty stance")
        void blankSummaryFallsBack() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "Adopt pgvector. Now.", TranscriptEntryType.OPINION));

            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, summarizerReturning("   ", 10, 1));

            assertEquals("Adopt pgvector.", gc.getMemberStances().get(AGENT_A).text());
            assertFalse(gc.getMemberStances().get(AGENT_A).llmGenerated());
        }

        @Test
        @DisplayName("a failure for one member does not stop the others")
        void oneFailureDoesNotStopTheRest() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "First position.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_B, "Second position.", TranscriptEntryType.OPINION));
            var service = mock(SummarizationService.class);
            when(service.summarizeWithUsage(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("boom"))
                    .thenReturn(new SummarizationResult("Opposes the migration.", 10, 5));

            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            assertEquals("First position.", gc.getMemberStances().get(AGENT_A).text());
            assertEquals("Opposes the migration.", gc.getMemberStances().get(AGENT_B).text());
        }
    }

    @Nested
    @DisplayName("recomputation is skipped when nothing changed")
    class Idempotence {

        @Test
        @DisplayName("a member with nothing new is not summarized again")
        void unchangedMemberNotResummarized() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position.", TranscriptEntryType.OPINION));
            var service = summarizerReturning("Favours pgvector.", 10, 5);

            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);
            var second = StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            verify(service, times(1)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString());
            assertTrue(second.isEmpty(), "no change means no stance_updated frame");
        }

        @Test
        @DisplayName("a new entry from anyone reopens every member whose coverage is stale")
        void newEntryReopensRecomputation() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position.", TranscriptEntryType.OPINION));
            StanceSummaryEngine.updateStances(gc, null, null, null);

            gc.getTranscript().add(entry(AGENT_A, "A revised position.", TranscriptEntryType.REVISION));
            var results = StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals(1, results.size());
            assertEquals("A revised position.", gc.getMemberStances().get(AGENT_A).text());
        }

        @Test
        @DisplayName("an unchanged stance text produces no event even when coverage moved")
        void unchangedTextProducesNoEvent() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position.", TranscriptEntryType.OPINION));
            StanceSummaryEngine.updateStances(gc, null, null, null);

            // Someone else speaks: A's coverage is stale, but A's own words did not change.
            gc.getTranscript().add(entry(AGENT_B, "Another position.", TranscriptEntryType.OPINION));
            var results = StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals(1, results.size(), "only B changed");
            assertEquals(AGENT_B, results.get(0).agentId());
        }

        @Test
        @DisplayName("a member is NOT re-summarized because somebody else spoke")
        void otherMemberSpeakingDoesNotResummarize() {
            // The property the whole cost story rests on. Keyed to the
            // transcript length instead of the member's own contributions, B
            // speaking invalidated A's stance and a six-member discussion paid
            // for six calls at every boundary.
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A's position.", TranscriptEntryType.OPINION));
            var service = summarizerReturning("Favours pgvector.", 10, 5);
            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            gc.getTranscript().add(entry(AGENT_B, "B's position.", TranscriptEntryType.OPINION));
            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            // Twice total: once for A, once for B — never a second time for A.
            verify(service, times(2)).summarizeWithUsage(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a re-summary that lands on the same wording still reports its cost")
        void unchangedTextStillReportsCost() {
            // Otherwise the spend reaches the ledger with no cost_updated frame
            // and the live total drifts below it — the drift the event exists
            // to prevent.
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "First.", TranscriptEntryType.OPINION));
            var service = summarizerReturning("Favours pgvector.", 1_000_000, 0);
            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            gc.getTranscript().add(entry(AGENT_A, "Second.", TranscriptEntryType.OPINION));
            var results = StanceSummaryEngine.updateStances(gc, pricedConfig(), null, service);

            assertEquals(1, results.size(), "same wording, but it was paid for");
            assertTrue(results.get(0).cost() > 0.0);
        }

        @Test
        @DisplayName("an empty transcript yields nothing at all")
        void emptyTranscript() {
            var gc = conversation();
            assertTrue(StanceSummaryEngine.updateStances(gc, null, null, null).isEmpty());
        }

        @Test
        @DisplayName("a null conversation is tolerated")
        void nullConversation() {
            assertTrue(StanceSummaryEngine.updateStances(null, null, null, null).isEmpty());
        }
    }

    @Nested
    @DisplayName("the cost ceiling bounds the summarizer too")
    class CostCeiling {

        private static ProtocolConfig ceiling(Double max) {
            return new ProtocolConfig(60, MemberFailurePolicy.SKIP, 2, MemberUnavailablePolicy.SKIP, 50, max,
                    ProtocolConfig.CostPolicy.SYNTHESIZE_NOW);
        }

        @Test
        @DisplayName("a blown budget downgrades to extraction instead of spending again")
        void blownBudgetExtracts() {
            // Every other optional spender (the I9 window summarizer, the
            // convergence judge, the dissent round) checks this. Without it the
            // boundary runs one priced call per member AFTER the budget is gone
            // and before the next phase's pre-wave check can fire.
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position. With detail.", TranscriptEntryType.OPINION));
            GroupCostLedger.recordSystemCost(gc, "earlier:spend", 5.0);
            var service = summarizerReturning("Generated.", 10, 5);

            StanceSummaryEngine.updateStances(gc, pricedConfig(), ceiling(1.0), service);

            verifyNoInteractions(service);
            assertEquals("A position.", gc.getMemberStances().get(AGENT_A).text());
            assertFalse(gc.getMemberStances().get(AGENT_A).llmGenerated());
        }

        @Test
        @DisplayName("a budget with room left still runs the summarizer")
        void budgetWithRoomRuns() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position.", TranscriptEntryType.OPINION));
            GroupCostLedger.recordSystemCost(gc, "earlier:spend", 0.5);

            StanceSummaryEngine.updateStances(gc, pricedConfig(), ceiling(10.0), summarizerReturning("Generated.", 10, 5));

            assertTrue(gc.getMemberStances().get(AGENT_A).llmGenerated());
        }

        @Test
        @DisplayName("a null protocol means unlimited, as it does everywhere else")
        void nullProtocolIsUnlimited() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "A position.", TranscriptEntryType.OPINION));

            StanceSummaryEngine.updateStances(gc, pricedConfig(), null, summarizerReturning("Generated.", 10, 5));

            assertTrue(gc.getMemberStances().get(AGENT_A).llmGenerated());
        }
    }

    @Nested
    @DisplayName("extraction never quotes a JSON contract")
    class JsonContracts {

        @Test
        @DisplayName("a ballot does not replace the member's prose position")
        void ballotDoesNotReplacePosition() {
            // VOTE/BID/RETRO/PLAN/TASK_RESULT/VERIFICATION carry JSON. Extracting
            // from the newest one showed the reader an opening brace as that
            // member's "own words", and wiped their real position after every
            // VOTE or RETRO phase.
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "pgvector is the right call.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_A,
                    "{\"choice\":\"pgvector\",\"confidence\":0.8,\"reasoning\":\"It is cheaper.\"}",
                    TranscriptEntryType.VOTE));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertEquals("pgvector is the right call.", gc.getMemberStances().get(AGENT_A).text());
        }

        @Test
        @DisplayName("a member with only JSON contributions gets no extracted stance")
        void onlyJsonYieldsNothing() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "{\"taskId\":\"t1\"}", TranscriptEntryType.BID));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            assertNull(gc.getMemberStances().get(AGENT_A));
        }
    }

    @Nested
    @DisplayName("leadSentence")
    class LeadSentence {

        @Test
        @DisplayName("stops at the first real terminator")
        void firstTerminator() {
            assertEquals("Adopt pgvector.", StanceSummaryEngine.leadSentence("Adopt pgvector. It is cheaper."));
            assertEquals("Why not?", StanceSummaryEngine.leadSentence("Why not? Because of cost."));
            assertEquals("No!", StanceSummaryEngine.leadSentence("No! Absolutely not."));
        }

        @Test
        @DisplayName("a decimal point is not a sentence end")
        void decimalsSurvive() {
            assertEquals("It costs $1.50 per seat.",
                    StanceSummaryEngine.leadSentence("It costs $1.50 per seat. That is fine."));
        }

        @Test
        @DisplayName("an abbreviation is not a sentence end")
        void abbreviationsSurvive() {
            assertEquals("Use a managed store, e.g. pgvector.",
                    StanceSummaryEngine.leadSentence("Use a managed store, e.g. pgvector. It is simpler."));
        }

        @Test
        @DisplayName("an ellipsis is not a sentence end")
        void ellipsisSurvives() {
            assertEquals("Well... it depends on the index.",
                    StanceSummaryEngine.leadSentence("Well... it depends on the index. Truly."));
        }

        @Test
        @DisplayName("a numbered-list marker is not a sentence")
        void listMarkerIsNotASentence() {
            // LLM replies open with "1." constantly; cutting there showed the
            // reader a stance reading literally "1.", attributed as the
            // member's own words.
            assertEquals("1. We should adopt pgvector.",
                    StanceSummaryEngine.leadSentence("1. We should adopt pgvector. It is cheaper."));
            assertEquals("2) Reject it.", StanceSummaryEngine.leadSentence("2) Reject it. On cost grounds."));
        }

        @Test
        @DisplayName("a standalone capital letter still ends a sentence")
        void standaloneCapitalEndsSentence() {
            // The abbreviation rule must not swallow this: "B." here is the end
            // of the sentence, not "e.g.".
            assertEquals("Weigh option B.",
                    StanceSummaryEngine.leadSentence("Weigh option B. Option A is worse."));
        }

        @Test
        @DisplayName("text with no terminator is returned whole")
        void noTerminator() {
            assertEquals("Adopt pgvector", StanceSummaryEngine.leadSentence("  Adopt pgvector  "));
        }

        @Test
        @DisplayName("null in, null out")
        void nullSafe() {
            assertNull(StanceSummaryEngine.leadSentence(null));
        }
    }

    @Nested
    @DisplayName("clean")
    class Clean {

        @Test
        @DisplayName("collapses whitespace and strips wrapping quotes a model adds")
        void normalises() {
            assertEquals("Favours pgvector.", StanceSummaryEngine.clean("\"Favours   pgvector.\"", 160));
            assertEquals("Favours pgvector.", StanceSummaryEngine.clean("  Favours\n\npgvector.  ", 160));
        }

        @Test
        @DisplayName("blank and null become null, never an empty stance")
        void blankIsNull() {
            assertNull(StanceSummaryEngine.clean(null, 160));
            assertNull(StanceSummaryEngine.clean("   ", 160));
            assertNull(StanceSummaryEngine.clean("\"\"", 160));
        }

        @Test
        @DisplayName("truncation never exceeds the cap the layout was sized to")
        void truncationRespectsCap() {
            String long_ = "word ".repeat(100);
            String cleaned = StanceSummaryEngine.clean(long_, 40);
            assertNotNull(cleaned);
            assertTrue(cleaned.length() <= 40, "got " + cleaned.length() + " for a cap of 40");
            assertTrue(cleaned.endsWith("…"));
        }

        @Test
        @DisplayName("text exactly at the cap is left alone")
        void exactCapUntouched() {
            String exact = "a".repeat(40);
            assertEquals(exact, StanceSummaryEngine.clean(exact, 40));
        }
    }

    @Nested
    @DisplayName("StanceSummaryConfig normalisation")
    class ConfigNormalisation {

        @Test
        @DisplayName("a non-positive maxChars falls back to the default")
        void maxCharsFloor() {
            assertEquals(StanceSummaryConfig.DEFAULT_MAX_CHARS, new StanceSummaryConfig(0, null, null, null, null).maxChars());
            assertEquals(StanceSummaryConfig.DEFAULT_MAX_CHARS, new StanceSummaryConfig(-5, null, null, null, null).maxChars());
        }

        @Test
        @DisplayName("blank identifiers are absent, so the extraction fallback is reached")
        void blanksAreAbsent() {
            var config = new StanceSummaryConfig(160, "   ", "", null, null);
            assertNull(config.llmProvider());
            assertNull(config.llmModel());
            assertFalse(config.hasSummarizer());
        }

        @Test
        @DisplayName("negative prices are treated as unpriced")
        void negativePricesDropped() {
            var config = new StanceSummaryConfig(160, "openai", "m", -1.0, -2.0);
            assertNull(config.inputPricePer1M());
            assertNull(config.outputPricePer1M());
        }

        @Test
        @DisplayName("hasSummarizer requires both halves")
        void hasSummarizerNeedsBoth() {
            assertTrue(new StanceSummaryConfig(160, "openai", "m", null, null).hasSummarizer());
            assertFalse(new StanceSummaryConfig(160, "openai", null, null, null).hasSummarizer());
            assertFalse(new StanceSummaryConfig(160, null, "m", null, null).hasSummarizer());
        }
    }

    @Nested
    @DisplayName("MemberStance storage")
    class Storage {

        @Test
        @DisplayName("setMemberStances(null) yields an empty map rather than a null field")
        void nullSetterIsSafe() {
            var gc = conversation();
            gc.setMemberStances(null);
            assertNotNull(gc.getMemberStances());
            assertTrue(gc.getMemberStances().isEmpty());
        }

        @Test
        @DisplayName("the getter hands out a read-only view, so no caller can mutate the field")
        void getterIsUnmodifiable() {
            var gc = conversation();
            assertThrows(UnsupportedOperationException.class,
                    () -> gc.getMemberStances().put(AGENT_A, new MemberStance("x", 1, false, Instant.now())));
        }

        @Test
        @DisplayName("putMemberStance is the write path, and ignores nulls")
        void putIsTheWritePath() {
            var gc = conversation();
            gc.putMemberStance(AGENT_A, new MemberStance("A position.", 1, false, Instant.now()));
            assertEquals("A position.", gc.getMemberStances().get(AGENT_A).text());

            gc.putMemberStance(null, new MemberStance("x", 1, false, Instant.now()));
            gc.putMemberStance(AGENT_B, null);
            assertEquals(1, gc.getMemberStances().size());
        }

        @Test
        @DisplayName("the view reflects later writes rather than freezing a snapshot")
        void viewIsLive() {
            var gc = conversation();
            var view = gc.getMemberStances();
            gc.putMemberStance(AGENT_A, new MemberStance("Later.", 1, false, Instant.now()));
            assertEquals("Later.", view.get(AGENT_A).text());
        }

        @Test
        @DisplayName("the stored coverage index is the transcript size it was computed from")
        void coverageIndexRecorded() {
            var gc = conversation();
            gc.getTranscript().add(entry(AGENT_A, "One.", TranscriptEntryType.OPINION));
            gc.getTranscript().add(entry(AGENT_B, "Two.", TranscriptEntryType.OPINION));

            StanceSummaryEngine.updateStances(gc, null, null, null);

            // One contribution each — NOT the transcript length of 2. Keyed to
            // the transcript, B speaking would invalidate A's stance and every
            // boundary would re-bill every member.
            assertEquals(1, gc.getMemberStances().get(AGENT_A).coveredContributions());
            assertEquals(1, gc.getMemberStances().get(AGENT_B).coveredContributions());
        }
    }

    @Nested
    @DisplayName("cost event payload")
    class CostEvent {

        @Test
        @DisplayName("carries the cumulative figure, so a replayed frame cannot double-count")
        void carriesCumulative() {
            var event = new CostUpdatedEvent(
                    AGENT_A, "Architect", 0.25, 0.80);
            assertEquals(AGENT_A, event.attributionKey());
            assertEquals(0.25, event.attributedCost());
            assertEquals(0.80, event.totalCost());
        }

        @Test
        @DisplayName("a system key names no member, which consumers must tolerate")
        void systemKeyHasNoDisplayName() {
            var event = new CostUpdatedEvent(
                    "system:stance:" + AGENT_A + ":4", null, 0.01, 0.81);
            assertNull(event.displayName());
            assertTrue(event.attributionKey().startsWith("system:"));
        }
    }

    @Nested
    @DisplayName("announceCost")
    class AnnounceCost {

        @Test
        @DisplayName("emits the key's cumulative cost once the ledger has recorded it")
        void emitsRecordedCost() {
            var gc = conversation();
            gc.getMemberCosts().put(AGENT_A, 0.42);
            gc.setTotalCost(0.42);
            var listener = mock(GroupDiscussionEventListener.class);

            MemberTurnExecutor.announceCost(gc, AGENT_A, "Architect", listener);

            var captor = ArgumentCaptor.forClass(CostUpdatedEvent.class);
            verify(listener).onCostUpdated(captor.capture());
            assertEquals(AGENT_A, captor.getValue().attributionKey());
            assertEquals(0.42, captor.getValue().attributedCost(), 1e-9);
            assertEquals("Architect", captor.getValue().displayName());
        }

        @Test
        @DisplayName("stays silent when no attribution landed — a $0 frame is noise")
        void silentWithoutAttribution() {
            var gc = conversation();
            var listener = mock(GroupDiscussionEventListener.class);

            MemberTurnExecutor.announceCost(gc, AGENT_A, "Architect", listener);

            verify(listener, never()).onCostUpdated(any());
        }

        @Test
        @DisplayName("a null listener or key is a no-op rather than a throw")
        void nullsTolerated() {
            var gc = conversation();
            gc.getMemberCosts().put(AGENT_A, 1.0);
            MemberTurnExecutor.announceCost(gc, AGENT_A, "Architect", null);
            var listener = mock(GroupDiscussionEventListener.class);
            MemberTurnExecutor.announceCost(gc, null, "Architect", listener);
            verify(listener, never()).onCostUpdated(any());
        }
    }
}
