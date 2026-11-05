/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The handoff config rides the stored documents and agent ZIPs, so it must
 * survive the production serializer, and a document written before it existed
 * must read back exactly as it did (absent means off).
 */
class HandoffConfigRoundTripTest {

    private final JsonSerialization json = new JsonSerialization(SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false));

    @Test
    void anLlmTaskKeepsItsTeamHandoffThroughTheProductionSerializer() throws Exception {
        LlmConfiguration config = json.deserialize("""
                {"tasks":[{"actions":["go"],"enableBuiltInTools":true,
                 "teamHandoff":{"targets":[{"alias":"account-desk","groupId":"g1","description":"warm leads","defaultPriority":7}],"maxPerTurn":9}}]}
                """, LlmConfiguration.class);
        var handoff = config.tasks().get(0).getTeamHandoff();
        assertNotNull(handoff);
        assertEquals("account-desk", handoff.targets().get(0).alias());
        assertEquals(7, handoff.targets().get(0).defaultPriority());
        assertEquals(5, handoff.maxPerTurn(), "9 is clamped to the hard ceiling of 5");
        assertEquals(5, handoff.maxPerConversation(), "unset caps take their defaults");

        LlmConfiguration again = json.deserialize(json.serialize(config), LlmConfiguration.class);
        assertEquals(handoff, again.tasks().get(0).getTeamHandoff());
    }

    @Test
    void anOldLlmDocumentHasNoTeamHandoff() throws Exception {
        LlmConfiguration config = json.deserialize("{\"tasks\":[{\"actions\":[\"go\"]}]}", LlmConfiguration.class);
        assertNull(config.tasks().get(0).getTeamHandoff());
    }

    @Test
    void aGroupKeepsItsHandoffConfigThroughTheProductionSerializer() throws Exception {
        AgentGroupConfiguration group = json.deserialize("""
                {"name":"Radar","handoffConfig":{
                  "targets":[{"alias":"desk","groupId":"g-desk"}],
                  "onDecision":[{"target":"desk","decisionTypes":["VOTE"],"subjectTemplate":"Research {decision.winner}"}],
                  "accept":{"from":[{"source":"agent:abc"},{"source":"group:def","mode":"AUTO","maxPerDay":3}]}}}
                """, AgentGroupConfiguration.class);
        var handoff = group.getHandoffConfig();
        assertEquals("MODERATOR", handoff.toolParticipants());
        assertTrue(handoff.onDecision().get(0).requireWinner());
        assertEquals(IntakeMode.REVIEW, handoff.accept().ruleFor("agent:abc").mode());
        assertEquals(IntakeMode.AUTO, handoff.accept().ruleFor("group:def").mode());

        AgentGroupConfiguration again = json.deserialize(json.serialize(group), AgentGroupConfiguration.class);
        assertEquals(handoff, again.getHandoffConfig());
    }

    @Test
    void anOldGroupDocumentNeitherSendsNorReceives() throws Exception {
        AgentGroupConfiguration group = json.deserialize("{\"name\":\"Old\"}", AgentGroupConfiguration.class);
        assertNull(group.getHandoffConfig());
    }
}
