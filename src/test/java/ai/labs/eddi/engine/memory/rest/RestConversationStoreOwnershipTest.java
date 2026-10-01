/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.security.ForbiddenException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ownership tests for the conversation-listing REST endpoint
 * ({@code GET /conversationstore/conversations} →
 * {@link RestConversationStore#readConversationDescriptors}).
 * <p>
 * Before this, the endpoint carried no {@code @RolesAllowed} and no ownership
 * filter, so it fell through to the global {@code authenticated} policy — any
 * authenticated caller could enumerate <em>every</em> user's conversation
 * descriptors (ids, agent, state, owner). It is the REST twin of the MCP
 * {@code list_conversations} gap; the same {@link ConversationAccessGuard} now
 * enforces owner-or-admin visibility here.
 * <p>
 * Each test builds the store as a concrete caller (a real guard over a mocked
 * {@link SecurityIdentity} with {@code authorization.enabled=true}) and asserts
 * which descriptors survive the listing.
 */
class RestConversationStoreOwnershipTest {

    private static final String OWNER = "owner-user";
    private static final String INTRUDER = "intruder-user";

    private IDocumentDescriptorStore documentDescriptorStore;
    private IConversationDescriptorStore conversationDescriptorStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IConversationService conversationService;
    private IUserMemoryStore userMemoryStore;
    private IRuntime runtime;
    private Instance<IAttachmentStore> attachmentStorageInstance;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        conversationDescriptorStore = mock(IConversationDescriptorStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        // The listing reads a page of summaries at once; route it through the per-id
        // stubs.
        lenient().when(conversationMemoryStore.loadListingSummaries(any())).thenCallRealMethod();
        conversationService = mock(IConversationService.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        runtime = mock(IRuntime.class);
        attachmentStorageInstance = mock(Instance.class);

        // populateDataToDescriptor loads a snapshot for every descriptor; a non-null
        // snapshot with an empty step list keeps it on the normal (non-orphan) path.
        // Its userId is null so it never overrides the owner recorded on a descriptor
        // — the descriptors below carry their own owner, except the legacy case which
        // is deliberately left unowned all the way down.
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        snapshot.setConversationSteps(new ArrayList<>());
        lenient().when(conversationMemoryStore.loadConversationMemorySnapshot(anyString())).thenReturn(snapshot);
    }

    /**
     * A conversation descriptor already carrying its owner and agent name. The id
     * must be hex: a non-hex id resolves to a null resource id, the snapshot stub
     * above never matches it, and the listing then drops the row as orphaned.
     */
    private ConversationDescriptor descriptor(String conversationId, String ownerId) {
        var descriptor = new ConversationDescriptor();
        descriptor.setResource(URI.create(
                "eddi://ai.labs.conversation/conversationstore/conversations/" + conversationId + "?version=1"));
        descriptor.setUserId(ownerId);
        descriptor.setAgentName("Some Agent"); // non-empty → skip the documentDescriptor lookup
        descriptor.setLastModifiedOn(new Date());
        return descriptor;
    }

    /** The store as seen by {@code caller}, with authorization enabled. */
    private RestConversationStore storeAs(String caller, String... roles) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(caller);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        for (String role : roles) {
            lenient().when(identity.hasRole(role)).thenReturn(true);
        }
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true),
                mock(IConversationDescriptorStore.class));
        return new RestConversationStore(documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, conversationService, userMemoryStore, runtime, guard, mock(ResourceAccessGuard.class),
                30, 90, attachmentStorageInstance);
    }

    private RestConversationStore asOwner() {
        return storeAs(OWNER, "eddi-viewer");
    }

    private RestConversationStore asIntruder() {
        return storeAs(INTRUDER, "eddi-viewer");
    }

    private RestConversationStore asAdmin() {
        return storeAs("admin-user", "eddi-viewer", "eddi-admin");
    }

    /** First (and only) descriptor page the store hands back. */
    private void firstPage(ConversationDescriptor... descriptors) throws Exception {
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(descriptors));
    }

    @Test
    @DisplayName("a caller sees only their own conversations, never another user's")
    void filtersToOwnConversations() throws Exception {
        firstPage(descriptor("0a0a0a0a0a0a0a0a0a0a0a01", OWNER), descriptor("0a0a0a0a0a0a0a0a0a0a0a02", INTRUDER));

        List<ConversationDescriptor> result = asOwner().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(OWNER, result.get(0).getUserId());
    }

    @Test
    @DisplayName("a non-owner enumerating the store sees nothing of another user's conversations")
    void nonOwnerSeesNoForeignConversations() throws Exception {
        firstPage(descriptor("0a0a0a0a0a0a0a0a0a0a0a01", OWNER));

        List<ConversationDescriptor> result = asIntruder().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertTrue(result.isEmpty(), "an intruder must not enumerate the owner's conversation");
    }

    @Test
    @DisplayName("an admin sees every user's conversations")
    void adminSeesAllConversations() throws Exception {
        firstPage(descriptor("0a0a0a0a0a0a0a0a0a0a0a01", OWNER), descriptor("0a0a0a0a0a0a0a0a0a0a0a02", INTRUDER));

        List<ConversationDescriptor> result = asAdmin().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("an unowned (legacy) conversation stays visible — matching requireOwnerOrAdmin")
    void unownedLegacyConversationRemainsVisible() throws Exception {
        firstPage(descriptor("0a0a0a0a0a0a0a0a0a0a0a03", null));

        List<ConversationDescriptor> result = asIntruder().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("hitting the owner-scan budget increments the exhausted counter")
    void scanBudgetExhaustedIncrementsCounter() throws Exception {
        // Every page is full and foreign, so the non-admin scan never fills `limit`
        // and stops on the MAX_OWNER_SCAN budget — the truncation the List can't
        // signal.
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(descriptor("0a0a0a0a0a0a0a0a0a0a0a04", INTRUDER), descriptor("0a0a0a0a0a0a0a0a0a0a0a05", INTRUDER)));

        var registry = new SimpleMeterRegistry();
        var store = asOwner();
        store.meterRegistry = registry;

        store.readConversationDescriptors(0, 20, null, null, null, null, null, null);

        assertEquals(1.0, registry.counter("eddi.conversations.listing.owner_scan_exhausted").count());
    }

    @Test
    @DisplayName("a personal list is back-filled across foreign pages, not starved by them")
    void personalListNotStarvedAcrossForeignPages() throws Exception {
        // The newest page is entirely other users' conversations; the caller's own
        // appears only on the next page. A single-page filter would report "none" —
        // the do-while must page forward (index is a page number: skip = index*limit)
        // until the owner's conversation is found.
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(descriptor("0a0a0a0a0a0a0a0a0a0a0a06", INTRUDER), descriptor("0a0a0a0a0a0a0a0a0a0a0a07", INTRUDER)));
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(1), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(descriptor("0a0a0a0a0a0a0a0a0a0a0a01", OWNER)));
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(2), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of());

        List<ConversationDescriptor> result = asOwner().readConversationDescriptors(
                0, 20, null, null, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals(OWNER, result.get(0).getUserId());
    }

    @Test
    @DisplayName("a foreign conversation is skipped WITHOUT loading its (expensive) memory snapshot")
    void foreignRowSkippedWithoutSnapshotLoad() throws Exception {
        // The owner is already recorded on the descriptor (every conversation since
        // v5.1.6), so the ownership decision is made before populateDataToDescriptor —
        // a foreign row never triggers a full conversation-memory document load. This
        // is what keeps a non-admin listing from becoming O(store) document reads.
        // The id is a valid hex id so that IF the check were reordered after populate,
        // the resulting loadConversationMemorySnapshot(<hexId>) would be a non-null
        // String that anyString() matches — making the never() assertion load-bearing.
        firstPage(descriptor("bbbbbbbbbbbbbbbbbbbbbbbb", OWNER));

        assertTrue(asIntruder().readConversationDescriptors(0, 20, null, null, null, null, null, null).isEmpty());

        verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(anyString());
    }

    @Test
    @DisplayName("a legacy (null-userId) descriptor is filtered by the owner resolved from its snapshot")
    void legacyDescriptorFilteredBySnapshotResolvedOwner() throws Exception {
        // Pre-v5.1.6 rows carry no owner on the descriptor; populateDataToDescriptor
        // resolves it from the memory snapshot. The ownership check therefore MUST run
        // after that resolution for these rows — otherwise a foreign legacy
        // conversation
        // would leak, because canAccessConversation(null) admits everyone. A fresh
        // descriptor per call avoids populate's userId mutation bleeding across calls.
        // The conversationId must be a valid hex id, or extractResourceId yields a null
        // id, the snapshot lookup is skipped, and the owner is never resolved.
        String legacyId = "aaaaaaaaaaaaaaaaaaaaaaaa";
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), eq(0), anyInt(), anyBoolean(), any()))
                .thenAnswer(invocation -> List.of(descriptor(legacyId, null)));
        var ownerSnapshot = new ConversationMemorySnapshot();
        ownerSnapshot.setConversationState(ConversationState.READY);
        ownerSnapshot.setConversationSteps(new ArrayList<>());
        ownerSnapshot.setUserId(OWNER); // the snapshot supplies a (foreign-to-intruder) owner
        when(conversationMemoryStore.loadConversationMemorySnapshot(legacyId)).thenReturn(ownerSnapshot);

        // The intruder must NOT see OWNER's legacy conversation...
        assertTrue(asIntruder().readConversationDescriptors(0, 20, null, null, null, null, null, null).isEmpty(),
                "a legacy conversation whose snapshot resolves to another user must not leak");
        // ...but the owner does.
        assertEquals(1, asOwner().readConversationDescriptors(0, 20, null, null, null, null, null, null).size());
    }

    @Test
    @DisplayName("Finding 10: a legacy unowned conversation is NOT deletable by a non-admin (strict owner check)")
    void deleteUnownedConversationForbiddenForNonAdmin() throws Exception {
        var guardDescriptorStore = mock(IConversationDescriptorStore.class);
        var unowned = new ConversationDescriptor();
        unowned.setUserId(null); // legacy conversation with no recorded owner
        when(guardDescriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(unowned);

        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(INTRUDER);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        lenient().when(identity.hasRole("eddi-viewer")).thenReturn(true);
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true), guardDescriptorStore);
        var store = new RestConversationStore(documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, conversationService, userMemoryStore, runtime, guard, mock(ResourceAccessGuard.class),
                30, 90, attachmentStorageInstance);

        assertThrows(ForbiddenException.class, () -> store.deleteConversationLog("conv-legacy", false));
        verify(conversationDescriptorStore, never()).deleteDescriptor(anyString(), anyInt());
        verify(conversationMemoryStore, never()).deleteConversationMemorySnapshot(anyString());
    }

    /**
     * The store as {@code caller}, whose guard resolves descriptors through
     * {@code guardDescriptorStore}.
     */
    private RestConversationStore storeWithGuardDescriptors(IConversationDescriptorStore guardDescriptorStore,
                                                            String caller) {
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn(caller);
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        lenient().when(identity.hasRole("eddi-viewer")).thenReturn(true);
        var guard = new ConversationAccessGuard(identity, new OwnershipValidator(true), guardDescriptorStore);
        return new RestConversationStore(documentDescriptorStore, conversationDescriptorStore,
                conversationMemoryStore, conversationService, userMemoryStore, runtime, guard, mock(ResourceAccessGuard.class),
                30, 90, attachmentStorageInstance);
    }

    private void legacySnapshotOwnedBy(String conversationId, String ownerId) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setUserId(ownerId);
        snapshot.setConversationSteps(new ArrayList<>());
        when(conversationMemoryStore.loadConversationMemorySnapshot(conversationId)).thenReturn(snapshot);
    }

    @Test
    @DisplayName("a pre-v5.1.6 conversation (no descriptor owner) is deletable by the owner its snapshot records")
    void deleteLegacyConversation_snapshotOwnerMayDelete() throws Exception {
        var guardDescriptorStore = mock(IConversationDescriptorStore.class);
        when(guardDescriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(new ConversationDescriptor());
        legacySnapshotOwnedBy("conv-legacy", OWNER);

        storeWithGuardDescriptors(guardDescriptorStore, OWNER).deleteConversationLog("conv-legacy", false);

        verify(conversationDescriptorStore).deleteDescriptor("conv-legacy", 0);
    }

    @Test
    @DisplayName("a pre-v5.1.6 conversation whose snapshot names another owner stays forbidden to a non-admin")
    void deleteLegacyConversation_snapshotOwnedByOtherForbidden() throws Exception {
        var guardDescriptorStore = mock(IConversationDescriptorStore.class);
        when(guardDescriptorStore.readDescriptor(anyString(), anyInt())).thenReturn(new ConversationDescriptor());
        legacySnapshotOwnedBy("conv-legacy", OWNER);

        var store = storeWithGuardDescriptors(guardDescriptorStore, INTRUDER);

        assertThrows(ForbiddenException.class, () -> store.deleteConversationLog("conv-legacy", false));
        verify(conversationDescriptorStore, never()).deleteDescriptor(anyString(), anyInt());
    }

    @Test
    @DisplayName("an archived (soft-deleted) legacy descriptor also resolves its owner from the snapshot")
    void permanentDeleteArchivedLegacyConversation_snapshotOwnerMayDelete() throws Exception {
        var guardDescriptorStore = mock(IConversationDescriptorStore.class);
        when(guardDescriptorStore.readDescriptor(anyString(), anyInt()))
                .thenThrow(new ResourceNotFoundException("archived"));
        when(guardDescriptorStore.readDescriptorWithHistory("conv-legacy", 0)).thenReturn(new ConversationDescriptor());
        legacySnapshotOwnedBy("conv-legacy", OWNER);

        assertThrows(ForbiddenException.class,
                () -> storeWithGuardDescriptors(guardDescriptorStore, INTRUDER).deleteConversationLog("conv-legacy", true));
        verify(conversationMemoryStore, never()).deleteConversationMemorySnapshot(anyString());

        storeWithGuardDescriptors(guardDescriptorStore, OWNER).deleteConversationLog("conv-legacy", true);

        verify(conversationMemoryStore).deleteConversationMemorySnapshot("conv-legacy");
    }

    @Test
    @DisplayName("a sparse owner's listing is bounded by a scan budget, not a full-store scan")
    void scanIsBoundedForSparseOwner() throws Exception {
        // Every page is a full page of another user's conversations; the caller owns
        // none. Without a budget the loop would page the ENTIRE store (a DoS on a large
        // shared deployment). With MAX_OWNER_SCAN=500 and a 100-row page it stops after
        // 5 reads — and since the owner is recorded on each descriptor, no snapshot is
        // loaded for the discarded foreign rows. Valid hex ids so the never()-load
        // assertion stays load-bearing (a reordered populate would load a matchable
        // id).
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    int idx = invocation.getArgument(2);
                    int lim = invocation.getArgument(3);
                    var page = new ArrayList<ConversationDescriptor>();
                    for (int i = 0; i < lim; i++) {
                        page.add(descriptor(String.format("%024x", (long) (idx * lim + i)), INTRUDER));
                    }
                    return page;
                });

        List<ConversationDescriptor> result = asOwner().readConversationDescriptors(
                0, 100, null, null, null, null, null, null);

        assertTrue(result.isEmpty());
        // 5 pages of 100 = the 500-descriptor budget, then it stops (not the whole
        // store).
        verify(conversationDescriptorStore, times(5))
                .readDescriptors(anyString(), any(), anyInt(), eq(100), anyBoolean(), any());
        verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(anyString());
    }

    /**
     * A descriptor store that honours index/limit but ignores the pushed-down
     * restrictions, so every foreign row reaches the per-row owner check.
     */
    private void pagedStore(List<ConversationDescriptor> rows) throws Exception {
        when(conversationDescriptorStore.readDescriptors(anyString(), any(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenAnswer(invocation -> {
                    int index = invocation.getArgument(2);
                    int limit = invocation.getArgument(3);
                    int from = (int) Math.min((long) index * limit, rows.size());
                    return new ArrayList<>(rows.subList(from, Math.min(from + limit, rows.size())));
                });
    }

    private static List<String> resources(List<ConversationDescriptor> descriptors) {
        return descriptors.stream().map(descriptor -> descriptor.getResource().toString()).toList();
    }

    @Test
    @DisplayName("an owner's pages are exact and disjoint when other users' rows are interleaved")
    void ownerPagesAreExactAndDisjoint() throws Exception {
        // One row in three belongs to someone else. Paging by descriptor page made
        // page 0 run past `limit` and page 1 repeat rows page 0 had already listed.
        var rows = new ArrayList<ConversationDescriptor>();
        var owned = new ArrayList<String>();
        for (int i = 0; i < 45; i++) {
            String owner = i % 3 == 1 ? INTRUDER : OWNER;
            var descriptor = descriptor(String.format("%024x", 0xd000 + i), owner);
            rows.add(descriptor);
            if (OWNER.equals(owner)) {
                owned.add(descriptor.getResource().toString());
            }
        }
        pagedStore(rows);

        var store = asOwner();
        var listed = new ArrayList<String>();
        for (int index = 0; index < 10; index++) {
            List<ConversationDescriptor> page = store.readConversationDescriptors(index, 7, null, null, null, null, null, null);
            assertTrue(page.size() <= 7, "page " + index + " holds " + page.size() + " rows");
            if (page.isEmpty()) {
                break;
            }
            listed.addAll(resources(page));
        }

        assertEquals(owned, listed);
    }

    @Test
    @DisplayName("an owner's later pages are reachable: rows counted off for earlier pages do not spend the scan budget")
    void deepOwnerPageIsNotCutOffByTheScanBudget() throws Exception {
        // 650 of the owner's own conversations. Page 5 at limit 100 counts off 500
        // matches first; charged against MAX_OWNER_SCAN (500) they would leave
        // nothing for the page itself.
        var rows = new ArrayList<ConversationDescriptor>();
        for (int i = 0; i < 650; i++) {
            rows.add(descriptor(String.format("%024x", 0xe000 + i), OWNER));
        }
        pagedStore(rows);

        List<ConversationDescriptor> page = asOwner().readConversationDescriptors(5, 100, null, null, null, null, null, null);

        assertEquals(resources(rows.subList(500, 600)), resources(page));
    }

    @Test
    @DisplayName("a deep owner page between other users' rows has no gaps, though its batches run past the scan budget")
    void deepOwnerPageAmongForeignRowsHasNoGaps() throws Exception {
        // Every other row is someone else's. Page 3 at limit 100 counts off 300 of the
        // owner's rows and reads in batches of 400, each of which crosses the 500-row
        // budget part-way: stopping mid-batch would skip rows nobody examined.
        var rows = new ArrayList<ConversationDescriptor>();
        var owned = new ArrayList<ConversationDescriptor>();
        for (int i = 0; i < 1200; i++) {
            var descriptor = descriptor(String.format("%024x", 0xf000 + i), i % 2 == 0 ? OWNER : INTRUDER);
            rows.add(descriptor);
            if (i % 2 == 0) {
                owned.add(descriptor);
            }
        }
        pagedStore(rows);

        List<ConversationDescriptor> page = asOwner().readConversationDescriptors(3, 100, null, null, null, null, null, null);

        assertEquals(resources(owned.subList(300, 400)), resources(page));
    }

    @Test
    @DisplayName("a non-admin listing pushes the owner filter into the descriptor query")
    @SuppressWarnings("unchecked")
    void ownerFilterIsPushedDown() throws Exception {
        firstPage();

        asOwner().readConversationDescriptors(0, 20, null, null, null, null, null, null);

        ArgumentCaptor<List<IResourceFilter.QueryFilters>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean(), captor.capture());
        var owner = captor.getValue().getFirst();
        assertEquals(IResourceFilter.QueryFilters.ConnectingType.OR, owner.getConnectingType());
        var mine = owner.getQueryFilters().get(0);
        assertEquals("userId", mine.getField());
        assertEquals(OWNER, mine.getFilter());
        assertTrue(mine.isExact());
        // ...or no recorded owner: a pre-v5.1.6 row, decided from its memory.
        assertInstanceOf(IResourceFilter.NotMatching.class, owner.getQueryFilters().get(1).getFilter());
    }

    @Test
    @DisplayName("the owner filter is not pushed down when an agent is named — its reviewers may list others' conversations")
    @SuppressWarnings("unchecked")
    void ownerFilterIsNotPushedDownForAnAgentListing() throws Exception {
        firstPage();

        asOwner().readConversationDescriptors(0, 20, null, null, "0000000000000000000000a1", null, null, null);

        ArgumentCaptor<List<IResourceFilter.QueryFilters>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("agentResource", captor.getValue().getFirst().getQueryFilters().getFirst().getField());
    }

    @Test
    @DisplayName("an admin listing pushes no owner filter")
    @SuppressWarnings("unchecked")
    void adminListingIsNotOwnerFiltered() throws Exception {
        firstPage();

        asAdmin().readConversationDescriptors(0, 20, null, null, null, null, null, null);

        ArgumentCaptor<List<IResourceFilter.QueryFilters>> captor = ArgumentCaptor.forClass(List.class);
        verify(conversationDescriptorStore).readDescriptors(anyString(), any(), eq(0), eq(20), anyBoolean(), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }
}
