/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.RetroConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupContextBuilder}, extracted from
 * {@code GroupConversationService} during the Wave R (R1 step 2) refactor. The
 * exhaustive per-phase-type / per-scope branch coverage already lives in
 * {@code GroupConversationServiceHitlCoverage3Test} and
 * {@code GroupConversationServiceUncoveredBranchTest} (reached via reflection
 * through the facade's thin delegators, kept for exactly that reason); these
 * tests exercise the extracted class directly.
 *
 * @author tests
 */
class GroupContextBuilderTest {

    @Mock
    private ITemplatingEngine templatingEngine;

    private GroupContextBuilder builder;

    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        builder = new GroupContextBuilder(templatingEngine);
    }

    private GroupMember member() {
        return new GroupMember(AGENT_A, "Agent A", 1, null);
    }

    private DiscussionPhase phase(PhaseType type, ContextScope scope) {
        return new DiscussionPhase("P-" + type, type, "ALL", TurnOrder.SEQUENTIAL, scope, false, null, 1, false);
    }

    private TranscriptEntry entry(String agentId, String name, String content, TranscriptEntryType type, String targetAgentId) {
        return new TranscriptEntry(agentId, name, content, 0, "P", type, Instant.now(), null, targetAgentId);
    }

    @Test
    void buildPhaseInput_rendersTemplate_andPassesPreviousResponses() throws Exception {
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");
        var transcript = List.of(entry("other", "O", "prev", TranscriptEntryType.OPINION, null));

        String result = builder.buildPhaseInput(phase(PhaseType.OPINION, ContextScope.FULL), member(), "Q?", transcript, 1, null);

        assertEquals("rendered", result);
        verify(templatingEngine).processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT));
    }

    @Test
    @DisplayName("the RETRO template quotes the CONFIGURED lesson cap — a group set above the default gets what it asked for")
    void buildPhaseInput_retro_quotesConfiguredCap() throws Exception {
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        builder.buildPhaseInput(phase(PhaseType.RETRO, ContextScope.FULL), member(), "Q?", List.of(), 1, null, null,
                null, null, new RetroConfig(7, 50));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(templatingEngine).processTemplate(any(), dataCaptor.capture(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals(7, dataCaptor.getValue().get("maxLessonsPerRun"));

        // The config-less overloads still quote the default.
        clearInvocations(templatingEngine);
        builder.buildPhaseInput(phase(PhaseType.RETRO, ContextScope.FULL), member(), "Q?", List.of(), 1, null);
        verify(templatingEngine).processTemplate(any(), dataCaptor.capture(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals(RetroConfig.DEFAULT_MAX_PER_RUN, dataCaptor.getValue().get("maxLessonsPerRun"));
    }

    // =================================================================
    // I4: the abstention instruction
    // =================================================================

    private DiscussionPhase abstainablePhase(PhaseType type) {
        return new DiscussionPhase("P-" + type, type, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL,
                false, null, 1, false, null, true);
    }

    @Test
    void buildPhaseInput_allowAbstention_appendsTheInstruction() throws Exception {
        // The only line that tells a model the PASS token exists. Without coverage
        // here the whole feature can be half-removed and every other I4 test — which
        // stubs the response directly — still passes.
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        String result = builder.buildPhaseInput(abstainablePhase(PhaseType.OPINION), member(), "Q?", List.of(), 1, null);

        assertTrue(result.startsWith("rendered"), "the rendered template must still lead");
        assertTrue(result.contains("PASS"), "the member is never told the token exists otherwise");
    }

    @Test
    void buildPhaseInput_allowAbstentionOff_appendsNothing() throws Exception {
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        String result = builder.buildPhaseInput(phase(PhaseType.OPINION, ContextScope.FULL), member(), "Q?", List.of(), 1, null);

        assertEquals("rendered", result);
    }

    @Test
    void buildPhaseInput_templateFailureWithAbstention_stillAppendsTheInstruction() throws Exception {
        // A template-engine failure would otherwise silently disable abstention for
        // exactly the turns already going wrong: the member answers normally and the
        // phase looks like it simply had nothing to abstain about.
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT)))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("boom", null));

        String result = builder.buildPhaseInput(abstainablePhase(PhaseType.OPINION), member(), "Q?", List.of(), 1, null);

        assertTrue(result.contains("Agent A"), "the plain-text fallback still renders");
        assertTrue(result.contains("PASS"), "and the instruction survives the fallback path");
    }

    @Test
    void buildPhaseInput_taskPhases_neverGetTheInstruction() throws Exception {
        // TaskForceEngine builds its own inputs, so a task phase that reached here
        // must still not advertise a token whose detection is deliberately disabled
        // for it — the two sides share AbstentionDetector.isEnabledFor precisely so
        // they cannot disagree.
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        assertEquals("rendered", builder.buildPhaseInput(abstainablePhase(PhaseType.VERIFY), member(), "Q?", List.of(), 1, null));
        assertEquals("rendered", builder.buildPhaseInput(abstainablePhase(PhaseType.EXECUTE), member(), "Q?", List.of(), 1, null));
    }

    @Test
    void buildPhaseInput_templateEngineException_fallsBackToPlainText() throws Exception {
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT)))
                .thenThrow(new ITemplatingEngine.TemplateEngineException("boom", null));

        String result = builder.buildPhaseInput(phase(PhaseType.OPINION, ContextScope.NONE), member(), "Q?", List.of(), 0, null);

        assertTrue(result.contains("Agent A"));
        assertTrue(result.contains("Q?"));
    }

    @Test
    void filterByScope_none_returnsEmpty() {
        var transcript = List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null));
        assertTrue(builder.filterByScope(transcript, ContextScope.NONE, 1, member()).isEmpty());
        assertTrue(builder.filterByScope(transcript, null, 1, member()).isEmpty());
    }

    @Test
    void filterByScope_full_includesAllNonMetaEntries() {
        var transcript = List.of(
                entry(AGENT_A, "A", "keep", TranscriptEntryType.OPINION, null),
                entry(AGENT_A, "A", "drop-error", TranscriptEntryType.ERROR, null),
                entry(AGENT_A, "A", "drop-skipped", TranscriptEntryType.SKIPPED, null),
                entry("user", "User", "drop-question", TranscriptEntryType.QUESTION, null));

        var result = builder.filterByScope(transcript, ContextScope.FULL, 5, member());

        assertEquals(1, result.size());
        assertEquals("keep", result.get(0).get("content"));
        assertEquals("A", result.get(0).get("speaker"));
    }

    @Test
    void filterByScope_anonymous_stripsAttribution() {
        var transcript = List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null));
        var result = builder.filterByScope(transcript, ContextScope.ANONYMOUS, 1, member());
        assertEquals("Anonymous", result.get(0).get("speaker"));
    }

    @Test
    void filterByScope_lastPhase_onlyRecentPhases() {
        var older = new TranscriptEntry(AGENT_A, "A", "old", 0, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);
        var recent = new TranscriptEntry(AGENT_A, "A", "new", 2, "P", TranscriptEntryType.OPINION, Instant.now(), null, null);

        var result = builder.filterByScope(List.of(older, recent), ContextScope.LAST_PHASE, 2, member());

        assertEquals(1, result.size());
        assertEquals("new", result.get(0).get("content"));
    }

    @Test
    void filterByScope_ownFeedback_onlyEntriesTargetingSpeaker() {
        var toMe = entry("other", "O", "for-me", TranscriptEntryType.CRITIQUE, AGENT_A);
        var toSomeoneElse = entry("other", "O", "not-for-me", TranscriptEntryType.CRITIQUE, "someone-else");

        var result = builder.filterByScope(List.of(toMe, toSomeoneElse), ContextScope.OWN_FEEDBACK, 1, member());

        assertEquals(1, result.size());
        assertEquals("for-me", result.get(0).get("content"));
    }

    @Test
    void filterByScope_taskScopes_alwaysEmpty() {
        var transcript = List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null));
        assertTrue(builder.filterByScope(transcript, ContextScope.TASK_ONLY, 1, member()).isEmpty());
        assertTrue(builder.filterByScope(transcript, ContextScope.TASK_WITH_DEPS, 1, member()).isEmpty());
    }

    // =================================================================
    // Peer-visibility matrix (Wave 0, F4) — table-driven; this IS the spec
    // =================================================================

    @Test
    void filterByScope_peerVisibilityMatrix_exactlyMatchesSpec() {
        final int currentPhaseIdx = 5;
        // Expected visibility for an entry belonging to the CURRENT
        // (still-running) phase — the strictest case for VOTE/BID, which are
        // only conditionally hidden (see the two dedicated tests below).
        var expected = new LinkedHashMap<TranscriptEntryType, Boolean>();
        expected.put(TranscriptEntryType.QUESTION, false);
        expected.put(TranscriptEntryType.OPINION, true);
        expected.put(TranscriptEntryType.CRITIQUE, true);
        expected.put(TranscriptEntryType.REVISION, true);
        expected.put(TranscriptEntryType.CHALLENGE, true);
        expected.put(TranscriptEntryType.DEFENSE, true);
        expected.put(TranscriptEntryType.ARGUMENT, true);
        expected.put(TranscriptEntryType.REBUTTAL, true);
        expected.put(TranscriptEntryType.SYNTHESIS, true);
        expected.put(TranscriptEntryType.ERROR, false);
        expected.put(TranscriptEntryType.SKIPPED, false);
        expected.put(TranscriptEntryType.PLAN, true);
        expected.put(TranscriptEntryType.TASK_RESULT, true);
        expected.put(TranscriptEntryType.VERIFICATION, true);
        expected.put(TranscriptEntryType.FOLLOW_UP, true);
        expected.put(TranscriptEntryType.ABSTAINED, false);
        expected.put(TranscriptEntryType.DISSENT, true);
        expected.put(TranscriptEntryType.CONVERGENCE, false);
        expected.put(TranscriptEntryType.FACILITATION, false);
        expected.put(TranscriptEntryType.VOTE, false); // still running its own phase
        expected.put(TranscriptEntryType.PROPOSAL, true);
        expected.put(TranscriptEntryType.BARGAIN, true);
        expected.put(TranscriptEntryType.HUMAN_INPUT, true);
        expected.put(TranscriptEntryType.RETRO, true);
        expected.put(TranscriptEntryType.BID, false); // still running its own phase

        assertEquals(TranscriptEntryType.values().length, expected.size(),
                "matrix must cover every TranscriptEntryType — a new type added without updating "
                        + "this table is exactly the silent gap this test exists to catch");

        for (var testCase : expected.entrySet()) {
            var transcriptEntry = new TranscriptEntry(AGENT_A, "A", "content", currentPhaseIdx, "P", testCase.getKey(), Instant.now(), null, null);
            var result = builder.filterByScope(List.of(transcriptEntry), ContextScope.FULL, currentPhaseIdx, member());
            assertEquals(testCase.getValue(), !result.isEmpty(),
                    () -> testCase.getKey() + ": expected visible=" + testCase.getValue() + " but was " + !result.isEmpty());
        }
    }

    @Test
    void filterByScope_vote_hiddenWhileOwnPhaseStillRunning_visibleOnceItCompletes() {
        var stillRunning = new TranscriptEntry(AGENT_A, "A", "ballot", 3, "Voting", TranscriptEntryType.VOTE, Instant.now(), null, null);
        var completed = new TranscriptEntry(AGENT_A, "A", "ballot", 2, "Voting", TranscriptEntryType.VOTE, Instant.now(), null, null);

        assertTrue(builder.filterByScope(List.of(stillRunning), ContextScope.FULL, 3, member()).isEmpty(),
                "a ballot cast so far in the still-running vote phase must be blind to peers");
        assertEquals(1, builder.filterByScope(List.of(completed), ContextScope.FULL, 3, member()).size(),
                "a ballot from a completed vote phase must become visible");
    }

    @Test
    void filterByScope_bid_hiddenWhileOwnPhaseStillRunning_visibleOnceItCompletes() {
        var stillRunning = new TranscriptEntry(AGENT_A, "A", "bid", 3, "Bidding", TranscriptEntryType.BID, Instant.now(), null, null);
        var completed = new TranscriptEntry(AGENT_A, "A", "bid", 2, "Bidding", TranscriptEntryType.BID, Instant.now(), null, null);

        assertTrue(builder.filterByScope(List.of(stillRunning), ContextScope.FULL, 3, member()).isEmpty(),
                "a bid placed so far in the still-running bidding phase must be blind to peers");
        assertEquals(1, builder.filterByScope(List.of(completed), ContextScope.FULL, 3, member()).size(),
                "a bid from a completed bidding phase must become visible");
    }

    @Test
    void findLatestResponse_returnsLastNonErrorMatch() {
        var transcript = List.of(
                entry(AGENT_A, "A", "first", TranscriptEntryType.OPINION, null),
                entry(AGENT_A, "A", "second", TranscriptEntryType.OPINION, null),
                entry(AGENT_A, "A", "bad", TranscriptEntryType.ERROR, null));
        assertEquals("second", builder.findLatestResponse(transcript, AGENT_A));
    }

    @Test
    void findLatestResponse_noMatch_returnsNull() {
        assertNull(builder.findLatestResponse(List.of(), AGENT_A));
    }

    @Test
    void mapPhaseToEntryType_everyPhaseType() {
        assertEquals(TranscriptEntryType.OPINION, builder.mapPhaseToEntryType(PhaseType.OPINION));
        assertEquals(TranscriptEntryType.CRITIQUE, builder.mapPhaseToEntryType(PhaseType.CRITIQUE));
        assertEquals(TranscriptEntryType.REVISION, builder.mapPhaseToEntryType(PhaseType.REVISION));
        assertEquals(TranscriptEntryType.CHALLENGE, builder.mapPhaseToEntryType(PhaseType.CHALLENGE));
        assertEquals(TranscriptEntryType.DEFENSE, builder.mapPhaseToEntryType(PhaseType.DEFENSE));
        assertEquals(TranscriptEntryType.ARGUMENT, builder.mapPhaseToEntryType(PhaseType.ARGUE));
        assertEquals(TranscriptEntryType.REBUTTAL, builder.mapPhaseToEntryType(PhaseType.REBUTTAL));
        assertEquals(TranscriptEntryType.SYNTHESIS, builder.mapPhaseToEntryType(PhaseType.SYNTHESIS));
        assertEquals(TranscriptEntryType.PLAN, builder.mapPhaseToEntryType(PhaseType.PLAN));
        assertEquals(TranscriptEntryType.TASK_RESULT, builder.mapPhaseToEntryType(PhaseType.EXECUTE));
        assertEquals(TranscriptEntryType.VERIFICATION, builder.mapPhaseToEntryType(PhaseType.VERIFY));
    }

    @Test
    void extractResponse_nullSnapshot_returnsEmptyStringNotNull() {
        assertEquals("", builder.extractResponse(null));
    }

    @Test
    void selectDefaultTemplate_opinionNone_independentTemplate() {
        assertNotNull(builder.selectDefaultTemplate(phase(PhaseType.OPINION, ContextScope.NONE), List.of(), 0));
    }

    @Test
    void selectDefaultTemplate_opinionAnonymous_anonymousTemplate() {
        assertNotNull(builder.selectDefaultTemplate(phase(PhaseType.OPINION, ContextScope.ANONYMOUS), List.of(), 0));
    }

    @Test
    void selectDefaultTemplate_opinionFull_withContextTemplate() {
        assertNotNull(builder.selectDefaultTemplate(phase(PhaseType.OPINION, ContextScope.FULL), List.of(), 0));
    }

    @Test
    void selectDefaultTemplate_nonOpinion_usesPresetDefault() {
        assertNotNull(builder.selectDefaultTemplate(phase(PhaseType.CRITIQUE, ContextScope.FULL), List.of(), 0));
    }

    // =================================================================
    // I3 — SYNTHESIS after a debate asks for a verdict, not a summary
    // =================================================================

    private TranscriptEntry entry(TranscriptEntryType type, int phaseIdx) {
        return new TranscriptEntry(AGENT_A, "Agent A", "said something", phaseIdx, "P" + phaseIdx,
                type, Instant.now(), null, null);
    }

    private DiscussionPhase synthesis(String inputTemplate) {
        return new DiscussionPhase("Judgment", PhaseType.SYNTHESIS, "MODERATOR", TurnOrder.SEQUENTIAL,
                ContextScope.FULL, false, inputTemplate, 1, false);
    }

    private GroupMember judge() {
        return new GroupMember("mod", "Moderator", 0, "MODERATOR");
    }

    private List<GroupMember> twoSidedRoster() {
        return List.of(new GroupMember("pro", "Pro", 1, "PRO"), new GroupMember("con", "Con", 2, "CON"));
    }

    @Test
    void isDebateJudgment_impartialJudgeAfterArguments_isTrue() {
        assertTrue(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, twoSidedRoster()));
    }

    @Test
    void isDebateJudgment_rebuttalsAlsoCount() {
        assertTrue(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.REBUTTAL, 0)), 1, twoSidedRoster()));
    }

    @Test
    void isDebateJudgment_withoutADebate_isFalse() {
        // A brainstorm's synthesis must keep summarizing. Asking it for a PRO/CON
        // verdict would produce a winner for a discussion that had no sides, and
        // DebateVerdictParser would then dutifully record it.
        assertFalse(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.OPINION, 0), entry(TranscriptEntryType.CRITIQUE, 0)), 1, twoSidedRoster()));
    }

    @Test
    void isDebateJudgment_argumentsFromThisPhaseOrLater_doNotCount() {
        // A phase cannot judge arguments it has not seen. On a resume the
        // transcript can already carry entries from later phases of an earlier leg,
        // which must not retroactively turn a synthesis into a judgment.
        assertFalse(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 1), entry(TranscriptEntryType.REBUTTAL, 5)), 1, twoSidedRoster()));
    }

    @Test
    void isDebateJudgment_rosterWithNoSides_isFalse() {
        // create_group(style="DEBATE") without memberRoles: resolveParticipants
        // falls back to ALL for "ROLE:PRO", every speaker is mapped to the same
        // side, and nobody ever argued PRO. Scoring PRO against CON there would
        // fabricate a winner out of a one-sided discussion.
        var noRoles = List.of(new GroupMember("a", "A", 1, null), new GroupMember("b", "B", 2, null));

        assertFalse(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, noRoles));
    }

    @Test
    void isDebateJudgment_oneSidedRoster_isFalse() {
        var allPro = List.of(new GroupMember("a", "A", 1, "PRO"), new GroupMember("b", "B", 2, "pro"));

        assertFalse(builder.isDebateJudgment(synthesis(null), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, allPro),
                "case variants of one role are still one side");
    }

    @Test
    void isDebateJudgment_speakerIsADebater_isFalse() {
        // A moderator-less debate makes a debater the sole synthesizer. Its own
        // conversation holds "argue the FOR side"; asking it to score PRO vs CON
        // and stamping the answer as DecisionRecord.winner would present one
        // side's opinion as the group's finding.
        var partisan = new GroupMember("pro", "Pro", 1, "PRO");

        assertFalse(builder.isDebateJudgment(synthesis(null), partisan,
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, twoSidedRoster()));
        assertFalse(builder.isDebateJudgment(synthesis(null), new GroupMember("con", "Con", 2, "con"),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, twoSidedRoster()),
                "role matching must not be case-sensitive, or a lowercase role reads as impartial");
    }

    @Test
    void isDebateJudgment_explicitInputTemplate_isFalse() {
        assertFalse(builder.isDebateJudgment(synthesis("My own template"), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, twoSidedRoster()),
                "the config author's own template is their instruction and always wins");
    }

    @Test
    void isDebateJudgment_nonSynthesisPhase_isFalse() {
        assertFalse(builder.isDebateJudgment(phase(PhaseType.REBUTTAL, ContextScope.FULL), judge(),
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, twoSidedRoster()));
    }

    @Test
    void buildPhaseInput_debateJudgment_rendersTheJudgmentTemplate() throws Exception {
        // The only test at any layer that asserts what the judge is actually
        // PROMPTED with: PhaseExecutionEngineTest mocks this whole class, and the
        // end-to-end test mocks ITemplatingEngine. Without it, wiring that never
        // selects the judgment template would leave every other I3 test green.
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        builder.buildPhaseInput(synthesis(null), judge(), "Q?", List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, null, twoSidedRoster());

        var captor = ArgumentCaptor.forClass(String.class);
        verify(templatingEngine).processTemplate(captor.capture(), any(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals(DiscussionStylePresets.TEMPLATE_DEBATE_JUDGMENT, captor.getValue());
    }

    @Test
    void buildPhaseInput_plainSynthesis_rendersTheProseTemplate() throws Exception {
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        builder.buildPhaseInput(synthesis(null), judge(), "Q?", List.of(entry(TranscriptEntryType.OPINION, 0)), 1, null, twoSidedRoster());

        var captor = ArgumentCaptor.forClass(String.class);
        verify(templatingEngine).processTemplate(captor.capture(), any(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals(DiscussionStylePresets.defaultTemplate(PhaseType.SYNTHESIS), captor.getValue());
    }

    @Test
    void buildPhaseInput_explicitTemplate_winsOverTheJudgmentDefault() throws Exception {
        // The escape hatch for anyone who wants a debate to end in prose.
        when(templatingEngine.processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        builder.buildPhaseInput(synthesis("My own template"), judge(), "Q?",
                List.of(entry(TranscriptEntryType.ARGUMENT, 0)), 1, null, twoSidedRoster());

        var captor = ArgumentCaptor.forClass(String.class);
        verify(templatingEngine).processTemplate(captor.capture(), any(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals("My own template", captor.getValue());
    }

    @Test
    void buildPlainTextFallback_mentionsPhaseQuestionAndSpeaker() {
        String result = builder.buildPlainTextFallback(phase(PhaseType.OPINION, ContextScope.FULL), member(), "What now?", List.of());
        assertTrue(result.contains("P-OPINION"));
        assertTrue(result.contains("What now?"));
        assertTrue(result.contains("Agent A"));
    }

    // =================================================================
    // ARGUE/REBUTTAL opposingArguments — team-based filtering (V6(a) fix)
    //
    // DEBATE's phases select participants via "ROLE:PRO"/"ROLE:CON", resolved
    // against every member sharing that role with no cap of one per side (see
    // GroupConversationService#resolveParticipants). The pre-fix filter excluded
    // only the speaker's own agentId, so with 2+ members per side a PRO speaker
    // saw their own PRO teammate's arguments folded into "opposingArguments" too.
    // =================================================================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> opposingArgumentsFrom(GroupMember speaker, List<TranscriptEntry> transcript,
                                                            List<GroupMember> allMembers)
            throws Exception {
        var captor = ArgumentCaptor.forClass(Map.class);
        when(templatingEngine.processTemplate(any(), captor.capture(), eq(ITemplatingEngine.TemplateMode.TEXT))).thenReturn("rendered");

        builder.buildPhaseInput(phase(PhaseType.ARGUE, ContextScope.FULL), speaker, "Q?", transcript, 1, null, allMembers);

        return (List<Map<String, Object>>) captor.getValue().get("opposingArguments");
    }

    @Test
    void opposingArguments_multiMemberTeams_excludesOwnTeammate_includesOpposingTeam() throws Exception {
        var proA = new GroupMember("pro-a", "Pro A", 1, "PRO");
        var proB = new GroupMember("pro-b", "Pro B", 2, "PRO");
        var conA = new GroupMember("con-a", "Con A", 3, "CON");
        var conB = new GroupMember("con-b", "Con B", 4, "CON");
        var roster = List.of(proA, proB, conA, conB);

        var transcript = List.of(
                entry("pro-b", "Pro B", "teammate's argument", TranscriptEntryType.ARGUMENT, null),
                entry("con-a", "Con A", "opposing argument 1", TranscriptEntryType.ARGUMENT, null),
                entry("con-b", "Con B", "opposing argument 2", TranscriptEntryType.REBUTTAL, null));

        var opposing = opposingArgumentsFrom(proA, transcript, roster);

        var speakers = opposing.stream().map(m -> m.get("speaker")).toList();
        assertEquals(2, opposing.size(), "must include exactly the two CON entries: " + speakers);
        assertTrue(speakers.contains("Con A"));
        assertTrue(speakers.contains("Con B"));
        assertFalse(speakers.contains("Pro B"), "a same-team entry must never appear as an opposing argument");
    }

    @Test
    void opposingArguments_noRoster_fallsBackToNotMeFilter() throws Exception {
        var pro = new GroupMember("pro-a", "Pro A", 1, "PRO");
        var transcript = List.of(entry("con-a", "Con A", "against", TranscriptEntryType.ARGUMENT, null));

        var opposing = opposingArgumentsFrom(pro, transcript, null);

        assertEquals(1, opposing.size());
        assertEquals("Con A", opposing.get(0).get("speaker"));
    }

    @Test
    void opposingArguments_speakerWithNullRole_fallsBackToNotMeFilter() throws Exception {
        var noRole = new GroupMember("x", "X", 1, null);
        var roster = List.of(noRole, new GroupMember("y", "Y", 2, null));
        var transcript = List.of(entry("y", "Y", "something", TranscriptEntryType.ARGUMENT, null));

        var opposing = opposingArgumentsFrom(noRole, transcript, roster);

        assertEquals(1, opposing.size(), "a null-role speaker has no resolvable team, so every other entry counts as opposing");
    }

    @Test
    void teamSide_derivedFromRole_caseInsensitive() throws Exception {
        var pro = new GroupMember("p", "P", 1, "pro");
        opposingArgumentsFrom(pro, List.of(), List.of(pro));

        var captor = ArgumentCaptor.forClass(Map.class);
        verify(templatingEngine).processTemplate(any(), captor.capture(), eq(ITemplatingEngine.TemplateMode.TEXT));
        assertEquals("FOR", captor.getValue().get("teamSide"));
    }
}
