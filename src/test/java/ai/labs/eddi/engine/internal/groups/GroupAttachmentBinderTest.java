/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import java.util.Base64;
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
        // The userId argument is the M4 fix: with the 5-argument overload the blob had
        // no owner, so the per-user quota never counted a group upload.
        when(store.store(eq("png".getBytes(java.nio.charset.StandardCharsets.UTF_8)), eq("image/png"), eq("a.png"), eq("gc-1"),
                eq(DEFAULT_TENANT), eq("user-1")))
                .thenReturn(new IAttachmentStore.Attachment("ref-1", "a.png", "image/png", 3, "gc-1"));

        var inline = new Attachment();
        inline.setMimeType("image/png");
        inline.setFileName("a.png");
        inline.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
        var gc = gc("gc-1");
        gc.setUserId("user-1");

        binder.materializeAttachments(gc, List.of(inline));

        assertEquals(1, gc.getAttachments().size());
        assertEquals("ref-1", gc.getAttachments().get(0).getStorageRef());
    }

    @Test
    void materialize_inlineFile_isCountedAgainstTheDiscussionsUser() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.store(any(), any(), any(), any(), any(), any()))
                .thenReturn(new IAttachmentStore.Attachment("ref-1", "a.png", "image/png", 3, "gc-1"));
        var gc = gc("gc-1");
        gc.setUserId("alice");

        binder.materializeAttachments(gc, List.of(inline("a.png")));

        verify(store).store(any(), eq("image/png"), eq("a.png"), eq("gc-1"), eq(DEFAULT_TENANT), eq("alice"));
        // The ownerless overload must not be used at all — it skips the per-user quota.
        verify(store, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    void materialize_quotaExceeded_rollsBackWhatThisCallStoredAndRethrows() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.store(any(), any(), eq("first.png"), any(), any(), any()))
                .thenReturn(new IAttachmentStore.Attachment("ref-1", "first.png", "image/png", 3, "gc-1"));
        when(store.store(any(), any(), eq("second.png"), any(), any(), any()))
                .thenThrow(new IAttachmentStore.AttachmentQuotaExceededException(
                        IAttachmentStore.AttachmentQuotaExceededException.SCOPE_USER, "user quota"));
        var gc = gc("gc-1");
        gc.setUserId("alice");

        var thrown = assertThrows(IAttachmentStore.AttachmentQuotaExceededException.class,
                () -> binder.materializeAttachments(gc, List.of(inline("first.png"), inline("second.png"))));

        assertEquals(IAttachmentStore.AttachmentQuotaExceededException.SCOPE_USER, thrown.getScope());
        // A refused request leaves nothing behind: the blob stored before the refusal
        // is removed again, and nothing is bound to the discussion.
        verify(store).delete("ref-1", "gc-1");
        assertNull(gc.getAttachments());
    }

    @Test
    void materialize_quotaExceeded_rollbackFails_stillRefusesAndBindsNothing() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.store(any(), any(), eq("first.png"), any(), any(), any()))
                .thenReturn(new IAttachmentStore.Attachment("ref-1", "first.png", "image/png", 3, "gc-1"));
        when(store.store(any(), any(), eq("second.png"), any(), any(), any()))
                .thenThrow(new IAttachmentStore.AttachmentQuotaExceededException(
                        IAttachmentStore.AttachmentQuotaExceededException.SCOPE_USER, "user quota"));
        when(store.delete("ref-1", "gc-1")).thenThrow(new IAttachmentStore.AttachmentStoreException("store down"));
        var gc = gc("gc-1");
        gc.setUserId("alice");

        // The refusal stands and nothing is bound; the leftover blob stays owned by
        // the discussion (which the service marks FAILED and keeps), so its delete
        // and the user's erasure still reach it. A rollback failure must not turn
        // into a swallowed quota error that lets the discussion run.
        var thrown = assertThrows(IAttachmentStore.AttachmentQuotaExceededException.class,
                () -> binder.materializeAttachments(gc, List.of(inline("first.png"), inline("second.png"))));

        assertEquals(IAttachmentStore.AttachmentQuotaExceededException.SCOPE_USER, thrown.getScope());
        verify(store).delete("ref-1", "gc-1");
        assertNull(gc.getAttachments());
    }

    @Test
    void materialize_clientSuppliedStorageRef_isRefused() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        var gc = gc("gc-1");
        gc.setUserId("mallory");

        // A storageRef naming someone else's blob, with no server-side source: passed
        // through, grantAndInjectAttachments would have granted every member access.
        binder.materializeAttachments(gc, List.of(new Attachment("application/pdf", "victim.pdf", 10, "victims-ref")));

        assertNull(gc.getAttachments());
        verify(store, never()).grantAccess(any(), any());
    }

    @Test
    void materialize_storageRefFromTrustedSource_thatCanReadIt_passesAndIsGrantedToTheChild() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.getMetadata("parent-ref", "parent-gc"))
                .thenReturn(new IAttachmentStore.Attachment("parent-ref", "doc.pdf", "application/pdf", 10, "parent-gc"));
        var child = gc("child-gc");

        binder.materializeAttachments(child, List.of(new Attachment("application/pdf", "doc.pdf", 10, "parent-ref")), "parent-gc");

        assertEquals(1, child.getAttachments().size());
        assertEquals("parent-ref", child.getAttachments().get(0).getStorageRef());
        verify(store).grantAccess("parent-ref", "child-gc");
    }

    @Test
    void materialize_storageRefTheSourceCannotRead_isRefused() throws Exception {
        var store = mock(IAttachmentStore.class);
        var binder = new GroupAttachmentBinder(store, DEFAULT_TENANT);
        when(store.getMetadata("foreign-ref", "parent-gc"))
                .thenThrow(new IAttachmentStore.AttachmentAccessDeniedException("not owner, no grant"));
        var child = gc("child-gc");

        binder.materializeAttachments(child, List.of(new Attachment("application/pdf", "x.pdf", 10, "foreign-ref")), "parent-gc");

        assertNull(child.getAttachments());
        verify(store, never()).grantAccess(any(), any());
    }

    private static Attachment inline(String fileName) {
        var a = new Attachment();
        a.setMimeType("image/png");
        a.setFileName(fileName);
        a.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
        return a;
    }

    @Test
    void materialize_url_passesThrough() throws Exception {
        var binder = new GroupAttachmentBinder(mock(IAttachmentStore.class), DEFAULT_TENANT);
        var url = new Attachment();
        url.setMimeType("image/png");
        url.setUrl("https://example.com/y.png");
        var gc = gc("gc-1");

        binder.materializeAttachments(gc, List.of(url));

        assertEquals("https://example.com/y.png", gc.getAttachments().get(0).getUrl());
    }

    @Test
    void materialize_noStore_dropsInlineButKeepsUrl() throws Exception {
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
    void materialize_nullOrEmpty_noop() throws Exception {
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
