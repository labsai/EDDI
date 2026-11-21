/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * A part of a group's file reaches each member as that part — pages, unit and
 * split — and survives the member's context parsing.
 */
class GroupAttachmentBinderPartsTest {

    private static final String GC = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String MEMBER = "64b7f0c2a1d3e4f5a6b7c8da";
    private static final String REF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String SPLIT = "0123456789abcdef";

    @Test
    @SuppressWarnings("unchecked")
    void aPart_isInjectedWithItsPages_andParsedBackAsTheSamePart() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, "default");
        var gc = new GroupConversation();
        gc.setId(GC);
        Attachment part = new Attachment("application/pdf", "report.pdf", 10, REF);
        part.getMetadata().putAll(new AttachmentSelector(REF, "12-19", "u3", SPLIT).toMetadata());
        gc.setAttachments(List.of(part));
        Map<String, Context> context = new LinkedHashMap<>();

        binder.grantAndInjectAttachments(gc, MEMBER, context);

        verify(store).grantAccess(REF, MEMBER);
        Map<String, Object> value = (Map<String, Object>) context.get("attachment_0").getValue();
        assertEquals("12-19", value.get("pages"));
        assertEquals("u3", value.get("unit"));
        assertEquals(SPLIT, value.get("split"));
        Attachment atMember = AttachmentContextExtractor.extractAttachments(context).get(0);
        assertEquals(new AttachmentSelector(REF, "12-19", "u3", SPLIT), AttachmentSelector.fromAttachment(atMember));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWholeFile_carriesNoPartFields() {
        var binder = new GroupAttachmentBinder(mock(IAttachmentStore.class), "default");
        var gc = new GroupConversation();
        gc.setId(GC);
        gc.setAttachments(List.of(new Attachment("application/pdf", "report.pdf", 10, REF)));
        Map<String, Context> context = new LinkedHashMap<>();

        binder.grantAndInjectAttachments(gc, MEMBER, context);

        Map<String, Object> value = (Map<String, Object>) context.get("attachment_0").getValue();
        assertFalse(value.containsKey("pages") || value.containsKey("unit") || value.containsKey("split"));
    }
}
