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
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TiePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.VoteMethod;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

@DisplayName("PhaseExecutionEngine — where a human tie-break's transcript slice starts")
class PhaseExecutionEngineTieBreakOffsetTest {

    private static final List<String> OPTIONS = List.of("Double down on SEO", "Launch the paid pilot");

    private static TranscriptEntry entry(String agent, String content, TranscriptEntryType type) {
        return new TranscriptEntry(agent, agent, content, 1, "Board vote", type, Instant.now(), null, null);
    }

    @Test
    @DisplayName("an entry appended after the repeat was sliced (a convergence note) does not push the offset past the first ballot")
    void offsetIsTheRepeatsFirstEntry() {
        var engine = new PhaseExecutionEngine(mock(MemberTurnExecutor.class), mock(GroupContextBuilder.class),
                Executors.newVirtualThreadPerTaskExecutor(), new CallerIdentityContext(null, null));
        var config = new AgentGroupConfiguration();
        config.setMembers(List.of(new GroupMember("founder@acme.com", "Founder", 1, null, MemberType.HUMAN)));
        config.setHumanMemberConfig(new HumanMemberConfig("PT4H", null));
        var voteConfig = new VoteConfig(VoteMethod.MAJORITY, OptionsSource.EXPLICIT, OPTIONS, 0.5, Map.of(), false, TiePolicy.HUMAN_DECIDES,
                null);
        var phase = new DiscussionPhase("Board vote", PhaseType.VOTE, "ALL", TurnOrder.PARALLEL, ContextScope.NONE, false, null, 1, false,
                null, false, voteConfig);

        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.getTranscript().add(entry(null, "Which way?", TranscriptEntryType.QUESTION));
        var first = entry("a1", "{\"vote\":\"Double down on SEO\"}", TranscriptEntryType.VOTE);
        var second = entry("a2", "{\"vote\":\"Launch the paid pilot\"}", TranscriptEntryType.VOTE);
        gc.getTranscript().add(first);
        gc.getTranscript().add(second);
        List<TranscriptEntry> repeatEntries = List.of(first, second);
        // the convergence check and the facilitator checkpoint append after the slice
        gc.getTranscript().add(entry("system", "converging", TranscriptEntryType.CONVERGENCE));

        var pending = engine.recordVoteDecision(gc, config, phase,
                new ProtocolConfig(120, ProtocolConfig.MemberFailurePolicy.RETRY, 3, ProtocolConfig.MemberUnavailablePolicy.SKIP, 100), 0,
                repeatEntries, List.of(), null, null, 0);

        assertNotNull(pending, "a tied vote with a resolvable decider pauses");
        assertEquals(1, pending.transcriptFrom(), "the repeat starts at its first ballot, not one entry later");
    }
}
