/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.junit.jupiter.api.BeforeEach;
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
