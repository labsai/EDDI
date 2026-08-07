/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link GroupAttachmentBinder}, extracted from
 * {@code GroupConversationServiceTest}'s {@code Attachments} nested class
 * during the Wave R (R1 step 1) refactor — same coverage, new home.
 *
 * @author tests
 */
class GroupAttachmentBinderTest {

    private static final String DEFAULT_TENANT = "default";

    private GroupConversation gc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        return gc;
    }

    @Test
    void materialize_base64_storesAndBinds() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        // eq(decoded bytes), not any(): with any() in the payload position, replacing
        // the Base64 decode with getBytes(UTF_8) — persisting every inline attachment
        // as its base64 TEXT rather than the file — passed this test.
        when(store.store(eq("png".getBytes(java.nio.charset.StandardCharsets.UTF_8)), eq("image/png"), eq("a.png"), eq("gc-1"),
                eq(DEFAULT_TENANT)))
                .thenReturn(new IAttachmentStore.Attachment("ref-1", "a.png", "image/png", 3, "gc-1"));

        var inline = new Attachment();
        inline.setMimeType("image/png");
        inline.setFileName("a.png");
        inline.setBase64Data(java.util.Base64.getEncoder().encodeToString("png".getBytes()));
        var gc = gc("gc-1");

        binder.materializeAttachments(gc, List.of(inline));

        assertEquals(1, gc.getAttachments().size());
        assertEquals("ref-1", gc.getAttachments().get(0).getStorageRef());
    }

    @Test
    void materialize_url_passesThrough() {
        var binder = new GroupAttachmentBinder(mock(IAttachmentStore.class), DEFAULT_TENANT);
        var url = new Attachment();
        url.setMimeType("image/png");
        url.setUrl("https://example.com/y.png");
        var gc = gc("gc-1");

        binder.materializeAttachments(gc, List.of(url));

        assertEquals("https://example.com/y.png", gc.getAttachments().get(0).getUrl());
    }

    @Test
    void materialize_noStore_dropsInlineButKeepsUrl() {
        var binder = new GroupAttachmentBinder(null, DEFAULT_TENANT);
        var inline = new Attachment();
        inline.setBase64Data("x");
        var url = new Attachment();
        url.setMimeType("image/png");
        url.setUrl("https://example.com/y.png");
        var gc = gc("gc-1");

        binder.materializeAttachments(gc, List.of(inline, url));

        // inline base64 dropped (no store), url kept (no store needed)
        assertEquals(1, gc.getAttachments().size());
        assertEquals("https://example.com/y.png", gc.getAttachments().get(0).getUrl());
    }

    @Test
    void materialize_nullOrEmpty_noop() {
        var binder = new GroupAttachmentBinder(mock(IAttachmentStore.class), DEFAULT_TENANT);
        var gc = gc("gc-1");
        binder.materializeAttachments(gc, null);
        binder.materializeAttachments(gc, List.of());
        assertNull(gc.getAttachments());
    }

    @Test
    void grantAndInject_storedRef_grantsAndInjects() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        var gc = gc("gc-1");
        gc.setAttachments(List.of(new Attachment("application/pdf", "doc.pdf", 10, "ref-1")));
        Map<String, Context> context = new LinkedHashMap<>();

        binder.grantAndInjectAttachments(gc, "member-conv", context);

        verify(store).grantAccess("ref-1", "member-conv");
        assertTrue(context.containsKey("attachment_0"));
        var value = (Map<?, ?>) context.get("attachment_0").getValue();
        assertEquals("ref-1", value.get("storageRef"));
        assertEquals("doc.pdf", value.get("fileName"));
    }

    @Test
    void grantAndInject_url_injectsWithoutGrant() {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        var gc = gc("gc-1");
        var url = new Attachment();
        url.setMimeType("image/png");
        url.setUrl("https://example.com/y.png");
        gc.setAttachments(List.of(url));
        Map<String, Context> context = new LinkedHashMap<>();

        binder.grantAndInjectAttachments(gc, "member-conv", context);

        verifyNoInteractions(store);
        var value = (Map<?, ?>) context.get("attachment_0").getValue();
        assertEquals("https://example.com/y.png", value.get("url"));
    }

    @Test
    void grantAndInject_grantFailure_skipsEntry() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        doThrow(new IAttachmentStore.AttachmentStoreException("nope")).when(store).grantAccess(any(), any());
        var gc = gc("gc-1");
        gc.setAttachments(List.of(new Attachment("application/pdf", "d.pdf", 1, "ref-1")));
        Map<String, Context> context = new LinkedHashMap<>();

        binder.grantAndInjectAttachments(gc, "m", context);
        assertFalse(context.containsKey("attachment_0"));
    }

    @Test
    void grantAndInject_noAttachments_noop() {
        var binder = new GroupAttachmentBinder(mock(IAttachmentStore.class), DEFAULT_TENANT);
        Map<String, Context> context = new LinkedHashMap<>();
        binder.grantAndInjectAttachments(gc("gc-1"), "m", context);
        assertTrue(context.isEmpty());
    }

    // rehydrateAttachmentsFromStore — recovers the transient attachments list
    // after a HITL resume reloads the GroupConversation (attachments are
    // @JsonIgnore transient, so a reloaded GC has none).

    @Test
    void rehydrate_nullAttachments_rebuildsFromStore() {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.listByConversation("gc-1")).thenReturn(
                List.of(new IAttachmentStore.Attachment("ref-1", "doc.pdf", "application/pdf", 10, "gc-1")));
        var gc = gc("gc-1");

        binder.rehydrateAttachmentsFromStore(gc);

        assertEquals(1, gc.getAttachments().size());
        assertEquals("ref-1", gc.getAttachments().get(0).getStorageRef());
        assertEquals("doc.pdf", gc.getAttachments().get(0).getFileName());
        assertEquals("application/pdf", gc.getAttachments().get(0).getMimeType());
        assertEquals(10L, gc.getAttachments().get(0).getSizeBytes());
    }

    @Test
    void rehydrate_attachmentsAlreadyPresent_noop() {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        var gc = gc("gc-1");
        gc.setAttachments(List.of(new Attachment("image/png", "keep.png", 5, "ref-keep")));

        binder.rehydrateAttachmentsFromStore(gc);

        // fresh discussion already has its list — the store must not be consulted
        verifyNoInteractions(store);
        assertEquals(1, gc.getAttachments().size());
        assertEquals("ref-keep", gc.getAttachments().get(0).getStorageRef());
    }

    @Test
    void rehydrate_emptyStore_leavesAttachmentsNull() {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.listByConversation("gc-1")).thenReturn(List.of());

        var gc = gc("gc-1");
        binder.rehydrateAttachmentsFromStore(gc);

        assertNull(gc.getAttachments());
    }

    @Test
    void rehydrate_noStore_noop() {
        var binder = new GroupAttachmentBinder(null, DEFAULT_TENANT);
        var gc = gc("gc-1");
        binder.rehydrateAttachmentsFromStore(gc);
        assertNull(gc.getAttachments());
    }
}
