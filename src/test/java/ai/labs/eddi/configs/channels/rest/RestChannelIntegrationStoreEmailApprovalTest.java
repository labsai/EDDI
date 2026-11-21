/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.rest;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ChannelTarget.ApprovalPolicy;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The email approval gate's per-target fields (email plan v3 §8.1): email only,
 * never on GROUP targets, {@code readOnlyTools} in the pattern language, and a
 * stored configuration without them reads as {@code none}.
 */
class RestChannelIntegrationStoreEmailApprovalTest {

    private static ChannelTarget target(ChannelTarget.TargetType type) {
        var target = new ChannelTarget();
        target.setName("support");
        target.setTargetId("agent-abc");
        target.setType(type);
        return target;
    }

    @Test
    @DisplayName("refused on a non-email integration")
    void refusedOnSlack() {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("Slack hub");
        cfg.setChannelType("slack");
        cfg.setDefaultTargetName("support");
        var target = target(ChannelTarget.TargetType.AGENT);
        target.setApprovalPolicy(ApprovalPolicy.ACTIONS);
        cfg.setTargets(List.of(target));
        var store = new RestChannelIntegrationStore(null, null, mock(ResourceAccessGuard.class));

        var ex = assertThrows(BadRequestException.class, () -> store.validateConfiguration(cfg));
        assertTrue(ex.getMessage().contains("only supported for email"), ex.getMessage());
    }

    @Test
    @DisplayName("each field alone is refused on a non-email integration")
    void eachFieldRefusedOffEmail() {
        var readOnly = target(ChannelTarget.TargetType.AGENT);
        readOnly.setReadOnlyTools(List.of("lookup"));
        assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(readOnly, false));
        var reply = target(ChannelTarget.TargetType.AGENT);
        reply.setRejectedReply("no");
        assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(reply, false));
    }

    @Test
    @DisplayName("refused on a GROUP target")
    void refusedOnGroup() {
        var group = target(ChannelTarget.TargetType.GROUP);
        group.setApprovalPolicy(ApprovalPolicy.EVERY_TURN);

        var ex = assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(group, true));
        assertTrue(ex.getMessage().contains("GROUP"), ex.getMessage());
    }

    @Test
    @DisplayName("a GROUP target may say none explicitly")
    void groupMaySayNone() {
        var group = target(ChannelTarget.TargetType.GROUP);
        group.setApprovalPolicy(ApprovalPolicy.NONE);
        assertDoesNotThrow(() -> RestChannelIntegrationStore.validateEmailApprovalFields(group, true));
    }

    @Test
    @DisplayName("readOnlyTools are validated as tool-approval patterns")
    void readOnlyToolsValidated() {
        var agent = target(ChannelTarget.TargetType.AGENT);
        agent.setApprovalPolicy(ApprovalPolicy.ACTIONS);
        agent.setReadOnlyTools(List.of("http.get:*", "lookup_*"));
        assertDoesNotThrow(() -> RestChannelIntegrationStore.validateEmailApprovalFields(agent, true));

        agent.setReadOnlyTools(List.of("bogus:thing"));
        assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(agent, true));
        agent.setReadOnlyTools(Collections.nCopies(RestChannelIntegrationStore.MAX_READ_ONLY_TOOLS + 1, "x"));
        assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(agent, true));
    }

    @Test
    @DisplayName("rejectedReply is capped")
    void rejectedReplyCapped() {
        var agent = target(ChannelTarget.TargetType.AGENT);
        agent.setRejectedReply("x".repeat(RestChannelIntegrationStore.MAX_REJECTED_REPLY_CHARS + 1));
        assertThrows(BadRequestException.class, () -> RestChannelIntegrationStore.validateEmailApprovalFields(agent, true));
    }

    @Test
    @DisplayName("JSON: the policy round-trips by its JSON name, and an old document reads as none")
    void jsonRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var agent = target(ChannelTarget.TargetType.AGENT);
        agent.setApprovalPolicy(ApprovalPolicy.FIRST_CONTACT);
        agent.setReadOnlyTools(List.of("lookup"));
        agent.setRejectedReply("No.");
        String json = mapper.writeValueAsString(agent);
        assertTrue(json.contains("\"approvalPolicy\":\"firstContact\""), json);

        var read = mapper.readValue(json, ChannelTarget.class);
        assertEquals(ApprovalPolicy.FIRST_CONTACT, read.getApprovalPolicy());
        assertEquals(List.of("lookup"), read.getReadOnlyTools());
        assertEquals("No.", read.getRejectedReply());

        var old = mapper.readValue("{\"name\":\"a\",\"targetId\":\"x\",\"type\":\"AGENT\"}", ChannelTarget.class);
        assertNull(old.getApprovalPolicy());
        assertEquals(ApprovalPolicy.NONE, old.effectiveApprovalPolicy());
        assertFalse(mapper.writeValueAsString(old).contains("approvalPolicy"), "an unset policy is not written back");
        assertEquals(ApprovalPolicy.EVERY_TURN, ApprovalPolicy.parse("EVERY_TURN"));
        assertThrows(IllegalArgumentException.class, () -> ApprovalPolicy.parse("sometimes"));
    }

    @Test
    @DisplayName("strictness order: none < actions < firstContact < everyTurn")
    void strictnessOrder() {
        assertTrue(ApprovalPolicy.NONE.compareTo(ApprovalPolicy.ACTIONS) < 0);
        assertTrue(ApprovalPolicy.ACTIONS.compareTo(ApprovalPolicy.FIRST_CONTACT) < 0);
        assertTrue(ApprovalPolicy.FIRST_CONTACT.compareTo(ApprovalPolicy.EVERY_TURN) < 0);
        assertFalse(ApprovalPolicy.NONE.gatesTools());
        assertFalse(ApprovalPolicy.ACTIONS.pausesBeforeRun());
        assertTrue(ApprovalPolicy.FIRST_CONTACT.pausesBeforeRun());
    }
}
