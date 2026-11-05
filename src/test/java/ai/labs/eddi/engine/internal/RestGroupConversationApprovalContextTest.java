/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The group {@code approval-status} summary serves the context to the owner, an
 * admin and an approver inside the APPROVAL pause, redacted again at serve
 * time, and to nobody once the pause is over.
 */
@DisplayName("RestGroupConversation approval-status approvalContext")
class RestGroupConversationApprovalContextTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-ctx";

    private IGroupConversationService groupService;
    private SecurityIdentity identity;
    private RestGroupConversation rest;

    @BeforeEach
    void setUp() {
        groupService = mock(IGroupConversationService.class);
        identity = mock(SecurityIdentity.class);
        var ownershipValidator = new OwnershipValidator(true);
        when(identity.isAnonymous()).thenReturn(false);
        var guard = new HitlAccessGuard(identity, ownershipValidator, mock(IConversationDescriptorStore.class), mock(IConversationService.class),
                groupService);
        rest = new RestGroupConversation(groupService, mock(IJsonSerialization.class), identity, ownershipValidator, guard,
                mock(ResourceAccessGuard.class));
    }

    private void as(String userId, boolean admin, boolean approver) {
        var principal = mock(Principal.class);
        when(principal.getName()).thenReturn(userId);
        when(identity.getPrincipal()).thenReturn(principal);
        when(identity.hasRole("eddi-admin")).thenReturn(admin);
        when(identity.hasRole("eddi-approver")).thenReturn(approver);
    }

    private GroupConversation gc(GroupConversationState state, String text) throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setUserId("owner");
        gc.setState(state);
        gc.setPausedAt(Instant.parse("2026-11-02T10:00:00Z"));
        gc.setHitlApprovalContext(RenderedApprovalContext.of(List.of(new Item("Draft", text, false, "group", null, null)), false));
        when(groupService.readGroupConversation(GC_ID)).thenReturn(gc);
        return gc;
    }

    @SuppressWarnings("unchecked")
    private Object contextOf(Response response) {
        return ((Map<String, Object>) response.getEntity()).get("approvalContext");
    }

    @Test
    @DisplayName("the owner reads the context of an approval pause, re-redacted")
    void ownerReadsIt() throws Exception {
        String key = "sk-ant-" + "fixture".repeat(5);
        gc(GroupConversationState.AWAITING_APPROVAL, "the draft " + key);
        as("owner", false, false);

        Object view = contextOf(rest.getGroupApprovalStatus(GROUP_ID, GC_ID, null));

        assertNotNull(view);
        assertTrue(view.toString().contains("the draft"));
        assertFalse(view.toString().contains(key));
    }

    @Test
    @DisplayName("an approver reads it while the discussion awaits approval")
    void approverReadsIt() throws Exception {
        gc(GroupConversationState.AWAITING_APPROVAL, "the draft");
        as("reviewer", false, true);

        assertNotNull(contextOf(rest.getGroupApprovalStatus(GROUP_ID, GC_ID, null)));
    }

    @Test
    @DisplayName("not awaiting approval: null")
    void nullOutsideTheApprovalPause() throws Exception {
        gc(GroupConversationState.COMPLETED, "stale");
        as("owner", false, false);

        assertNull(contextOf(rest.getGroupApprovalStatus(GROUP_ID, GC_ID, null)));
    }

    @Test
    @DisplayName("the raw group read (owner/admin) serves the field re-redacted too")
    void rawReadRedacted() throws Exception {
        String key = "sk-ant-" + "fixture".repeat(5);
        gc(GroupConversationState.AWAITING_APPROVAL, "the draft " + key);
        as("owner", false, false);

        GroupConversation body = rest.readGroupConversation(GROUP_ID, GC_ID);

        assertFalse(body.getHitlApprovalContext().getItems().get(0).getText().contains(key));
        assertTrue(body.getHitlApprovalContext().getItems().get(0).getText().contains("the draft"));
    }

    @Test
    @DisplayName("detail=full serves the field re-redacted")
    void fullViewRedacted() throws Exception {
        String key = "sk-ant-" + "fixture".repeat(5);
        gc(GroupConversationState.AWAITING_APPROVAL, "the draft " + key);
        as("owner", false, false);

        var body = (GroupConversation) rest.getGroupApprovalStatus(GROUP_ID, GC_ID, "full").getEntity();

        assertFalse(body.getHitlApprovalContext().getItems().get(0).getText().contains(key));
    }
}
