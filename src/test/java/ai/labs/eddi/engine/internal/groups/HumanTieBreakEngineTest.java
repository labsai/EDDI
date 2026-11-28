/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.HumanMemberConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OptionsSource;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanTieBreakConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HumanTieBreakEngine — who decides, what they see, what their choice becomes")
class HumanTieBreakEngineTest {

    private static final List<String> OPTIONS = List.of("Double down on SEO", "Launch the paid pilot");

    private static VoteConfig vote(HumanTieBreakConfig tieBreak) {
        return new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5, Map.of(), false, TiePolicy.HUMAN_DECIDES,
                tieBreak);
    }

    private static DiscussionPhase phase(VoteConfig vote) {
        return new DiscussionPhase("Board vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1, false,
                null, false, vote);
    }

    private static TranscriptEntry ballot(String agent, String content) {
        return new TranscriptEntry(agent, agent, content, 1, "Board vote", TranscriptEntryType.VOTE, Instant.now(), null, null);
    }

    private static GroupConversation tiedConversation() {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getTranscript().add(new TranscriptEntry(null, "User", "Which way?", 0, "Question", TranscriptEntryType.QUESTION,
                Instant.now(), null, null));
        gc.getTranscript().add(ballot("a1", "{\"vote\":\"Double down on SEO\",\"statement\":\"cheaper\"}"));
        gc.getTranscript().add(ballot("a2", "{\"vote\":\"Launch the paid pilot\",\"statement\":\"faster\"}"));
        return gc;
    }

    @Test
    @DisplayName("onTie names the decider, falls back to the human-member timeout, and lists options with totals")
    void onTie() {
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(new GroupMember("founder@acme.com", "Founder", 1, null, MemberType.HUMAN)));
        config.setHumanMemberConfig(new HumanMemberConfig("PT4H", null));
        var gc = tiedConversation();
        var voteConfig = vote(null);
        var outcome = VoteTallyEngine.tally(gc.getTranscript().subList(1, 3), 2, OPTIONS, voteConfig, "Board vote");

        var pending = HumanTieBreakEngine.onTie(gc, config, phase(voteConfig), voteConfig, outcome, 1);

        assertEquals("founder@acme.com", pending.decider());
        assertEquals("Founder", pending.deciderDisplayName());
        assertEquals("PT4H", pending.timeout(), "no own timeout: the human-member turn timeout applies");
        assertEquals(OPTIONS, pending.options());
        assertTrue(pending.quorumReached());
        assertEquals(1, pending.transcriptFrom());
        assertTrue(pending.prompt().contains("is tied"), pending.prompt());
        assertTrue(pending.prompt().contains("- Double down on SEO (1.00 weighted votes)"), pending.prompt());
        assertFalse(pending.prompt().contains("cheaper"), "member statements are not part of the prompt");
    }

    @Test
    @DisplayName("onTie without a resolvable decider keeps NO_DECISION")
    void noDecider() {
        var gc = tiedConversation();
        var voteConfig = vote(null);
        var outcome = VoteTallyEngine.tally(gc.getTranscript().subList(1, 3), 2, OPTIONS, voteConfig, "Board vote");

        assertNull(HumanTieBreakEngine.onTie(gc, new AgentGroupConfiguration(), phase(voteConfig), voteConfig, outcome, 1));
    }

    @Test
    @DisplayName("a choice must name exactly one option")
    void matchChoice() {
        assertEquals("Launch the paid pilot", HumanTieBreakEngine.matchChoice("  Launch the paid pilot ", OPTIONS));
        assertEquals("Double down on SEO", HumanTieBreakEngine.matchChoice("double down on seo", OPTIONS),
                "falls back to the tiebreaker reply rule");
        assertNull(HumanTieBreakEngine.matchChoice("Something else", OPTIONS));
        assertNull(HumanTieBreakEngine.matchChoice("Double down on SEO or Launch the paid pilot", OPTIONS), "two options is no choice");
        assertNull(HumanTieBreakEngine.matchChoice(" ", OPTIONS));
        assertNull(HumanTieBreakEngine.matchChoice("A", List.of("A", "A")), "an ambiguous exact match is refused");
    }

    @Test
    @DisplayName("the decision is a VOTE by the decider, with the losing ballot as dissent and the original tally")
    void decisionFor() {
        var gc = tiedConversation();
        var phase = phase(vote(null));
        var original = VoteTallyEngine.tally(gc.getTranscript().subList(1, 3), 2, OPTIONS, phase.voteConfig(), "Board vote");
        gc.setDecision(original.decision());

        var rebuilt = HumanTieBreakEngine.rebuildOutcome(gc, phase, 1, 1, GroupSigningGuard.recordView(null, gc, List.of()));
        var decision = HumanTieBreakEngine.decisionFor("Launch the paid pilot", "Founder", rebuilt.quorumReached(), rebuilt,
                gc.getDecision(), "Board vote", "We need revenue this quarter");

        assertEquals(DecisionType.VOTE, decision.type());
        assertEquals("Launch the paid pilot", decision.winner());
        assertEquals(HumanTieBreakEngine.METHOD_HUMAN_TIEBREAK, decision.method());
        assertEquals("\"Launch the paid pilot\" chosen by Founder (tie break).", decision.outcome());
        assertEquals(1, decision.dissents().size());
        assertEquals("cheaper", decision.dissents().getFirst().position());
        assertEquals(original.decision().tally(), decision.tally());
        assertEquals("We need revenue this quarter", decision.raw());
    }

    @Test
    @DisplayName("rebuilding reads only the vote phase's ballots from where the repeat began")
    void rebuildIgnoresEarlierEntries() {
        var gc = tiedConversation();
        gc.getTranscript().add(1, ballot("old", "{\"vote\":\"Double down on SEO\"}"));

        var rebuilt = HumanTieBreakEngine.rebuildOutcome(gc, phase(vote(null)), 1, 2, GroupSigningGuard.recordView(null, gc, List.of()));

        assertEquals(2, rebuilt.ballots().size());
    }

    @Test
    @DisplayName("an unanswered tie keeps a NONE record naming who did not answer")
    void noDecision() {
        var none = HumanTieBreakEngine.noDecision("Founder", "PT24H", null, "Board vote");

        assertEquals(DecisionType.NONE, none.type());
        assertEquals("No decision from Founder within PT24H.", none.outcome());
    }
}
