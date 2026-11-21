/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestAgentGroupStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.templates.GroupTemplateService;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupAttachmentRejectedException;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The {@code attachments} argument of {@code discuss_with_group}: inline and
 * URL files reach the service's attachment overload, held to the limits REST
 * holds them to; a {@code storageRef} is refused, because the group binder
 * admits one only from a trusted source conversation and an MCP call has none.
 */
class McpGroupToolsAttachmentsTest {

    private static final String GROUP_ID = "0123456789abcdef01234567";
    private static final String STORAGE_REF = "fedcba9876543210fedcba98";

    private IGroupConversationService groupConversationService;
    private McpGroupTools tools;

    @BeforeEach
    void setUp() throws Exception {
        groupConversationService = mock(IGroupConversationService.class);
        var jsonSerialization = mock(IJsonSerialization.class);
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{\"id\":\"gc1\"}");
        var identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(true);
        tools = new McpGroupTools(mock(IRestAgentGroupStore.class), groupConversationService, jsonSerialization,
                mock(StrictConfigurationParser.class), identity, new OwnershipValidator(false), mock(IGroupWorkspaceStore.class),
                mock(GroupTemplateService.class), mock(ResourceAccessGuard.class), false);
    }

    @Test
    void aStorageRefIsRefusedAndNothingRuns() {
        String result = tools.discuss_with_group(GROUP_ID, "Review this", null, "[{\"storageRef\":\"" + STORAGE_REF + "\"}]");

        assertTrue(result.contains("BAD_REQUEST"), result);
        assertTrue(result.contains("storageRef") && result.contains("inline"), result);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void aStorageRefNextToAnInlineFileStillRefusesTheCall() {
        String result = tools.discuss_with_group(GROUP_ID, "Review this", null,
                "[{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\"},{\"storageRef\":\"" + STORAGE_REF + "\"}]");

        assertTrue(result.contains("BAD_REQUEST") && result.contains("attachments[1]"), result);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void inlineAndUrlFilesReachTheAttachmentOverload() throws Exception {
        when(groupConversationService.discuss(eq(GROUP_ID), eq("Review this"), eq("mcp-client"), eq(0), isNull(), anyList()))
                .thenReturn(new GroupConversation());

        String result = tools.discuss_with_group(GROUP_ID, "Review this", null,
                "[{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\",\"fileName\":\"a.pdf\"},"
                        + "{\"url\":\"https://example.com/b.png\",\"mimeType\":\"image/png\"}]");

        assertEquals("{\"id\":\"gc1\"}", result);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Attachment>> shared = ArgumentCaptor.forClass(List.class);
        verify(groupConversationService).discuss(eq(GROUP_ID), eq("Review this"), eq("mcp-client"), eq(0), isNull(), shared.capture());
        verify(groupConversationService, never()).discuss(any(), any(), any(), anyInt());

        List<Attachment> attachments = shared.getValue();
        assertEquals(2, attachments.size());
        assertEquals("QUJDRA==", attachments.get(0).getBase64Data());
        assertEquals("application/pdf", attachments.get(0).getMimeType());
        assertEquals("a.pdf", attachments.get(0).getFileName());
        assertEquals("https://example.com/b.png", attachments.get(1).getUrl());
        attachments.forEach(a -> assertNull(a.getStorageRef()));
    }

    @Test
    void withoutFilesThePlainCallIsUsed() throws Exception {
        when(groupConversationService.discuss(GROUP_ID, "Q?", "mcp-client", 0)).thenReturn(new GroupConversation());

        tools.discuss_with_group(GROUP_ID, "Q?", null, "[]");

        verify(groupConversationService).discuss(GROUP_ID, "Q?", "mcp-client", 0);
        verify(groupConversationService, never()).discuss(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void theDiscussionsInlineLimitsAreTheOnesRestApplies() {
        tools.maxAttachmentsPerConversation = 1;

        String result = tools.discuss_with_group(GROUP_ID, "Q?", null,
                "[{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\"},{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\"}]");

        assertTrue(result.contains("BAD_REQUEST") && result.contains("at most 1 inline"), result);
        verifyNoInteractions(groupConversationService);
    }

    @Test
    void aQuotaRefusalIsReportedWithRestsText() throws Exception {
        when(groupConversationService.discuss(any(), any(), any(), anyInt(), any(), anyList()))
                .thenThrow(new GroupAttachmentRejectedException("user", "store detail that must not leak", null));

        String result = tools.discuss_with_group(GROUP_ID, "Q?", null, "[{\"data\":\"QUJDRA==\",\"mimeType\":\"application/pdf\"}]");

        assertTrue(result.contains("BAD_REQUEST") && result.contains("per-user attachment quota"), result);
        assertFalse(result.contains("must not leak"), result);
    }
}
