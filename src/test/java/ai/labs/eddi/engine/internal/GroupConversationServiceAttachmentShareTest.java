/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.AttachmentShareService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupConversationService} hands its injected
 * {@link AttachmentShareService} to the attachment binder, so a nested group's
 * {@code storageRef} pass-through is decided by the one share rule, not by a
 * check the binder builds for itself.
 */
class GroupConversationServiceAttachmentShareTest {

    private static final String PARENT = "65f0a1b2c3d4e5f6a7b8c901";
    private static final String CHILD = "65f0a1b2c3d4e5f6a7b8c902";
    private static final String REF = "6560a1b2c3d4e5f6a7b8c9a0";

    private GroupConversationService service;
    private IAttachmentStore store;

    @BeforeEach
    void setUp() throws Exception {
        service = new GroupConversationService(mock(IAgentGroupStore.class), mock(IGroupConversationStore.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(ITemplatingEngine.class),
                mock(IJsonSerialization.class), new SimpleMeterRegistry(), mock(AgentSigningService.class), mock(IAgentStore.class),
                mock(IScheduleStore.class), null, new CallerIdentityContext(null, null), "default", 3);
        store = mock(IAttachmentStore.class);
        // The store alone would let the parent read the blob.
        when(store.getMetadata(REF, PARENT)).thenReturn(new IAttachmentStore.Attachment(REF, "a.pdf", "application/pdf", 1, PARENT));
        service.attachmentStore = store;
    }

    private GroupConversation child() {
        var gc = new GroupConversation();
        gc.setId(CHILD);
        return gc;
    }

    private static Attachment reference() {
        var a = new Attachment();
        a.setStorageRef(REF);
        a.setFileName("a.pdf");
        return a;
    }

    @Test
    void injectedShareService_decidesThePassThrough() throws Exception {
        var shareService = mock(AttachmentShareService.class);
        when(shareService.mayRead(REF, PARENT)).thenReturn(false);
        service.attachmentShareService = shareService;
        var gc = child();

        service.materializeAttachments(gc, List.of(reference()), PARENT);

        verify(shareService).mayRead(REF, PARENT);
        verify(store, never()).grantAccess(any(), any());
        assertNull(gc.getAttachments());
    }

    @Test
    void injectedShareService_allowing_grantsTheChild() throws Exception {
        var shareService = mock(AttachmentShareService.class);
        when(shareService.mayRead(REF, PARENT)).thenReturn(true);
        service.attachmentShareService = shareService;
        var gc = child();

        service.materializeAttachments(gc, List.of(reference()), PARENT);

        verify(store).grantAccess(REF, CHILD);
        assertEquals(1, gc.getAttachments().size());
    }

    @Test
    void withoutAnInjectedService_theBinderChecksTheStoreItself() throws Exception {
        var gc = child();

        service.materializeAttachments(gc, List.of(reference()), PARENT);

        verify(store).getMetadata(REF, PARENT);
        verify(store).grantAccess(REF, CHILD);
    }
}
