/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.model;

import ai.labs.eddi.engine.model.Deployment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserConversationTest {

    @Test
    void defaultConstructor() {
        var uc = new UserConversation();
        assertNull(uc.getIntent());
        assertNull(uc.getUserId());
        assertNull(uc.getEnvironment());
        assertNull(uc.getAgentId());
        assertNull(uc.getConversationId());
    }

    @Test
    void fullConstructor() {
        var uc = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-123");
        assertEquals("greeting", uc.getIntent());
        assertEquals("user-1", uc.getUserId());
        assertEquals(Deployment.Environment.production, uc.getEnvironment());
        assertEquals("agent-1", uc.getAgentId());
        assertEquals("conv-123", uc.getConversationId());
    }

    @Test
    void setters() {
        var uc = new UserConversation();
        uc.setIntent("farewell");
        uc.setUserId("user-2");
        uc.setEnvironment(Deployment.Environment.test);
        uc.setAgentId("agent-2");
        uc.setConversationId("conv-456");

        assertEquals("farewell", uc.getIntent());
        assertEquals("user-2", uc.getUserId());
        assertEquals(Deployment.Environment.test, uc.getEnvironment());
        assertEquals("agent-2", uc.getAgentId());
        assertEquals("conv-456", uc.getConversationId());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var uc = new UserConversation("greeting", "user-1",
                Deployment.Environment.production, "agent-1", "conv-123");
        var json = mapper.writeValueAsString(uc);
        var deserialized = mapper.readValue(json, UserConversation.class);
        assertEquals("greeting", deserialized.getIntent());
        assertEquals("user-1", deserialized.getUserId());
        assertEquals("agent-1", deserialized.getAgentId());
    }

    @Test
    void channelSignerRoundTrips() throws Exception {
        var mapper = new ObjectMapper();
        var uc = new UserConversation("channel:slack:D1:agent-1:main", "slack:T1:U1",
                Deployment.Environment.production, "agent-1", "conv-123");
        uc.setChannelSigner("integration:app-a");

        var deserialized = mapper.readValue(mapper.writeValueAsString(uc), UserConversation.class);

        assertEquals("integration:app-a", deserialized.getChannelSigner());
    }

    /**
     * A mapping stored by an earlier release — in MongoDB or in PostgreSQL's JSONB
     * column, both of which hold the serialized object — has no channelSigner: it
     * reads as unknown.
     */
    @Test
    void aMappingStoredWithoutTheSignerReadsAsUnknown() throws Exception {
        var json = "{\"intent\":\"channel:slack:D1:agent-1:main\",\"userId\":\"slack:T1:U1\","
                + "\"environment\":\"production\",\"agentId\":\"agent-1\",\"conversationId\":\"conv-1\"}";

        var deserialized = new ObjectMapper().readValue(json, UserConversation.class);

        assertNull(deserialized.getChannelSigner());
        assertEquals("conv-1", deserialized.getConversationId());
    }
}
