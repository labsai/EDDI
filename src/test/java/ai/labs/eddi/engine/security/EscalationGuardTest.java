/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escalation guard of email plan v3 §5.3: what it guards, and that it
 * refuses only in a turn a mail started.
 */
@DisplayName("EscalationGuard")
class EscalationGuardTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    @ParameterizedTest(name = "{0} is guarded")
    @ValueSource(strings = {"update_email_senders", "create_channel_integration", "update_channel_integration", "delete_channel_integration",
            "resume_conversation", "approve_group_phase", "submit_group_human_input", "update_email_roles", "update_email_send_out",
            "decide_held_email", "UPDATE_EMAIL_SENDERS", "eddi_update_email_senders", "eddi.resume_conversation", "mcp/update_channel_integration"})
    void guardedTools(String name) {
        assertTrue(EscalationGuard.isGuardedTool(name));
    }

    @ParameterizedTest(name = "{0} is not guarded")
    @ValueSource(strings = {"list_email_senders", "read_channel_integration", "list_channel_integrations", "list_pending_approvals",
            "notupdate_email_senders", "update_email_senders_v2", "calculator", ""})
    void unguardedTools(String name) {
        assertFalse(EscalationGuard.isGuardedTool(name));
    }

    @ParameterizedTest(name = "{0} {1} is guarded")
    @CsvSource({"POST, /channelstore/channels/abc/email-senders", "POST, /channelstore/channels/abc/email-held/row-1/decision",
            "PUT, /channelstore/channels/abc", "DELETE, /channelstore/channels/abc",
            "POST, /channelstore/channels", "POST, /eddi/root/channelstore/channels/abc/email-suppressions/clear",
            "POST, /channelstore//channels/abc", "POST, /channelstore/channels;jsessionid=1/abc", "POST, /x/../channelstore/channels/abc",
            "POST, /channelstore%2Fchannels/abc", "POST, /channelstore%252Fchannels/abc", "POST, /CHANNELSTORE/Channels/abc",
            "POST, /agents/conv-1/resume", "POST, /agents/conv-1/resume/stream", "POST, /groups/g/conversations/c/approve",
            "POST, /groups/g/conversations/c/approve/stream", "POST, /groups/g/conversations/c/human-input",
            "POST, /administration/spend-control-changes/x/approve", "PATCH, /channelstore/channels/abc"})
    void guardedRequests(String method, String path) {
        assertTrue(EscalationGuard.isGuardedRequest(method, path));
    }

    @ParameterizedTest(name = "{0} {1} is not guarded")
    @CsvSource({"GET, /channelstore/channels/abc/email-senders", "HEAD, /channelstore/channels/abc", "POST, /channelstore/descriptors",
            "POST, /agents/agent-1/start", "POST, /agents/conv-1", "GET, /agents/conv-1/resume", "POST, /groups/g/conversations",
            "POST, /api/v2/orders"})
    void unguardedRequests(String method, String path) {
        assertFalse(EscalationGuard.isGuardedRequest(method, path));
    }

    @Test
    @DisplayName("nothing is refused outside a tainted turn")
    void untaintedTurnIsNotRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED));

        assertTrue(EscalationGuard.refuseTool("update_email_senders").isEmpty());
        assertTrue(EscalationGuard.refuseRequest("POST", URI.create("https://eddi.example.com/channelstore/channels/abc")).isEmpty());
    }

    @Test
    @DisplayName("a turn a mail started is refused a guarded tool and a guarded request, and nothing else")
    void taintedTurnIsRefused() {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));

        assertEquals(EscalationGuard.REFUSAL, EscalationGuard.refuseTool("update_email_senders").orElseThrow());
        assertTrue(EscalationGuard.refuseRequest("POST", URI.create("https://eddi.example.com/channelstore/channels/abc")).isPresent());
        assertTrue(EscalationGuard.refuseTool("calculator").isEmpty());
        assertTrue(EscalationGuard.refuseRequest("GET", URI.create("https://eddi.example.com/channelstore/channels/abc")).isEmpty());
    }

    @Test
    @DisplayName("a conversation that predates the stored taint is still recognised by its email user id")
    void legacyEmailIdentityIsTainted() {
        principals.bind(new ResolutionPrincipal("email:aabbccddee1122334455aabb:alice@partner.com", Provenance.SELF_ASSERTED));
        assertTrue(EscalationGuard.refuseTool("update_email_senders").isPresent());

        principals.bind(new ResolutionPrincipal("email-anon:0123456789abcdef", Provenance.SELF_ASSERTED));
        assertTrue(EscalationGuard.refuseTool("resume_conversation").isPresent());
    }

    @Test
    @DisplayName("a taint bound without a principal refuses too")
    void boundOriginRefuses() throws Exception {
        assertTrue(TurnOrigin.callWith(TurnOrigin.EMAIL, () -> EscalationGuard.refuseTool("update_email_senders")).isPresent());
        assertTrue(EscalationGuard.refuseTool("update_email_senders").isEmpty(), "the binding ends with the call");
    }
}
