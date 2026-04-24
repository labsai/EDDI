/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentGroupConfigurationTest {

    // ==================== Basic getters/setters ====================

    @Test
    void nameAndDescription() {
        var config = new AgentGroupConfiguration();
        config.setName("Test Group");
        config.setDescription("A test discussion group");

        assertEquals("Test Group", config.getName());
        assertEquals("A test discussion group", config.getDescription());
    }

    @Test
    void moderatorAgentId() {
        var config = new AgentGroupConfiguration();
        config.setModeratorAgentId("mod-agent");
        assertEquals("mod-agent", config.getModeratorAgentId());
    }

    @Test
    void maxRounds_default() {
        var config = new AgentGroupConfiguration();
        assertEquals(2, config.getMaxRounds());
    }

    @Test
    void maxRounds_setter() {
        var config = new AgentGroupConfiguration();
        config.setMaxRounds(5);
        assertEquals(5, config.getMaxRounds());
    }

    @Test
    void style_allValues() {
        assertEquals(6, DiscussionStyle.values().length);
        assertNotNull(DiscussionStyle.valueOf("ROUND_TABLE"));
        assertNotNull(DiscussionStyle.valueOf("PEER_REVIEW"));
        assertNotNull(DiscussionStyle.valueOf("DEVIL_ADVOCATE"));
        assertNotNull(DiscussionStyle.valueOf("DELPHI"));
        assertNotNull(DiscussionStyle.valueOf("DEBATE"));
        assertNotNull(DiscussionStyle.valueOf("CUSTOM"));
    }

    @Test
    void style_setAndGet() {
        var config = new AgentGroupConfiguration();
        config.setStyle(DiscussionStyle.DEBATE);
        assertEquals(DiscussionStyle.DEBATE, config.getStyle());
    }

    // ==================== GroupMember ====================

    @Test
    void groupMember_fullConstructor() {
        var member = new GroupMember("agent1", "Alice", 1, "PRO", MemberType.AGENT);
        assertEquals("agent1", member.agentId());
        assertEquals("Alice", member.displayName());
        assertEquals(1, member.speakingOrder());
        assertEquals("PRO", member.role());
        assertEquals(MemberType.AGENT, member.memberType());
    }

    @Test
    void groupMember_convenienceConstructor_defaultsToAgent() {
        var member = new GroupMember("agent1", "Alice", 1, "CON");
        assertEquals(MemberType.AGENT, member.memberType());
    }

    @Test
    void groupMember_groupType() {
        var member = new GroupMember("subgroup1", "Subgroup", null, null, MemberType.GROUP);
        assertEquals(MemberType.GROUP, member.memberType());
        assertNull(member.speakingOrder());
        assertNull(member.role());
    }

    @Test
    void members_setAndGet() {
        var config = new AgentGroupConfiguration();
        assertTrue(config.getMembers().isEmpty()); // default is empty list

        var members = List.of(
                new GroupMember("a1", "Agent 1", 1, null),
                new GroupMember("a2", "Agent 2", 2, "DEVIL_ADVOCATE"));
        config.setMembers(members);
        assertEquals(2, config.getMembers().size());
    }

    // ==================== DiscussionPhase ====================

    @Test
    void discussionPhase_fullConstructor() {
        var phase = new DiscussionPhase("opening", PhaseType.OPINION,
                "ALL", TurnOrder.PARALLEL, ContextScope.NONE,
                false, "Give your opinion on {{question}}", 2);

        assertEquals("opening", phase.name());
        assertEquals(PhaseType.OPINION, phase.type());
        assertEquals("ALL", phase.participants());
        assertEquals(TurnOrder.PARALLEL, phase.turnOrder());
        assertEquals(ContextScope.NONE, phase.contextScope());
        assertFalse(phase.targetEachPeer());
        assertEquals("Give your opinion on {{question}}", phase.inputTemplate());
        assertEquals(2, phase.repeats());
    }

    @Test
    void discussionPhase_convenienceConstructor_defaults() {
        var phase = new DiscussionPhase("synthesis", PhaseType.SYNTHESIS);

        assertEquals("synthesis", phase.name());
        assertEquals(PhaseType.SYNTHESIS, phase.type());
        assertEquals("ALL", phase.participants());
        assertEquals(TurnOrder.SEQUENTIAL, phase.turnOrder());
        assertEquals(ContextScope.FULL, phase.contextScope());
        assertFalse(phase.targetEachPeer());
        assertNull(phase.inputTemplate());
        assertEquals(1, phase.repeats());
    }

    @Test
    void phaseType_allValues() {
        assertEquals(8, PhaseType.values().length);
        assertNotNull(PhaseType.valueOf("OPINION"));
        assertNotNull(PhaseType.valueOf("CRITIQUE"));
        assertNotNull(PhaseType.valueOf("REVISION"));
        assertNotNull(PhaseType.valueOf("CHALLENGE"));
        assertNotNull(PhaseType.valueOf("DEFENSE"));
        assertNotNull(PhaseType.valueOf("ARGUE"));
        assertNotNull(PhaseType.valueOf("REBUTTAL"));
        assertNotNull(PhaseType.valueOf("SYNTHESIS"));
    }

    @Test
    void contextScope_allValues() {
        assertEquals(5, ContextScope.values().length);
        assertNotNull(ContextScope.valueOf("NONE"));
        assertNotNull(ContextScope.valueOf("FULL"));
        assertNotNull(ContextScope.valueOf("LAST_PHASE"));
        assertNotNull(ContextScope.valueOf("ANONYMOUS"));
        assertNotNull(ContextScope.valueOf("OWN_FEEDBACK"));
    }

    @Test
    void turnOrder_allValues() {
        assertEquals(2, TurnOrder.values().length);
        assertNotNull(TurnOrder.valueOf("SEQUENTIAL"));
        assertNotNull(TurnOrder.valueOf("PARALLEL"));
    }

    // ==================== ProtocolConfig ====================

    @Test
    void protocolConfig_fullConstructor() {
        var protocol = new ProtocolConfig(120,
                ProtocolConfig.MemberFailurePolicy.RETRY, 3,
                ProtocolConfig.MemberUnavailablePolicy.SKIP, 100);

        assertEquals(120, protocol.agentTimeoutSeconds());
        assertEquals(ProtocolConfig.MemberFailurePolicy.RETRY, protocol.onAgentFailure());
        assertEquals(3, protocol.maxRetries());
        assertEquals(ProtocolConfig.MemberUnavailablePolicy.SKIP, protocol.onMemberUnavailable());
        assertEquals(100, protocol.maxTurns());
    }

    @Test
    void protocolConfig_backwardCompatibleConstructor_defaultsMaxTurnsToZero() {
        var protocol = new ProtocolConfig(60,
                ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.FAIL);

        assertEquals(0, protocol.maxTurns());
    }

    @Test
    void memberFailurePolicy_allValues() {
        assertEquals(3, ProtocolConfig.MemberFailurePolicy.values().length);
        assertNotNull(ProtocolConfig.MemberFailurePolicy.valueOf("SKIP"));
        assertNotNull(ProtocolConfig.MemberFailurePolicy.valueOf("RETRY"));
        assertNotNull(ProtocolConfig.MemberFailurePolicy.valueOf("ABORT"));
    }

    @Test
    void memberUnavailablePolicy_allValues() {
        assertEquals(2, ProtocolConfig.MemberUnavailablePolicy.values().length);
        assertNotNull(ProtocolConfig.MemberUnavailablePolicy.valueOf("SKIP"));
        assertNotNull(ProtocolConfig.MemberUnavailablePolicy.valueOf("FAIL"));
    }

    @Test
    void phases_setAndGet() {
        var config = new AgentGroupConfiguration();
        assertNull(config.getPhases());

        var phases = List.of(
                new DiscussionPhase("opinion", PhaseType.OPINION),
                new DiscussionPhase("synthesis", PhaseType.SYNTHESIS));
        config.setPhases(phases);
        assertEquals(2, config.getPhases().size());
    }

    @Test
    void protocol_setAndGet() {
        var config = new AgentGroupConfiguration();
        assertNull(config.getProtocol());

        var protocol = new ProtocolConfig(60,
                ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.FAIL);
        config.setProtocol(protocol);
        assertEquals(60, config.getProtocol().agentTimeoutSeconds());
    }

    // ==================== MemberType ====================

    @Test
    void memberType_allValues() {
        assertEquals(2, MemberType.values().length);
        assertNotNull(MemberType.valueOf("AGENT"));
        assertNotNull(MemberType.valueOf("GROUP"));
    }
}
