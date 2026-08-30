/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.configs.descriptors.rest.RestDocumentDescriptorStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.AccessScope;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.SpaceContext;
import ai.labs.eddi.engine.security.spaces.Subjects;
import ai.labs.eddi.engine.security.spaces.WorkspaceSettings;
import io.quarkus.security.identity.SecurityIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.security.Principal;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the endpoints actually CALL the guard, not merely that the guard works.
 *
 * <h3>The gap this closes</h3> {@code ResourceAccessGuardTest} proves
 * {@code redactForCaller} strips what it should, and {@code AccessScopeTest}
 * proves a space predicate narrows. Neither notices if a listing simply stops
 * invoking them: every other test in this area hands the store a
 * <em>mocked</em> guard, so deleting
 * {@code descriptors.forEach(accessGuard::redactForCaller)} or replacing
 * {@code listingScope().withinSpace(space)} with {@code listingScope()} left
 * the whole suite green. That is the same shape as a mix-in test that
 * registered its own mix-in — the unit under test was the collaborator, not the
 * wiring.
 *
 * <p>
 * So these use a <b>real</b> guard with a restrictive identity, and assert on
 * what actually comes back.
 */
class ListingRedactionWiringTest {

    private static final String TYPE = "ai.labs.agent";
    private static final String RESOURCE_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private IDocumentDescriptorStore store;

    @BeforeEach
    void setUp() {
        store = mock(IDocumentDescriptorStore.class);
    }

    /**
     * {@code validate()} is package-private to the spaces package, and nothing here
     * exercises the legacy path it configures — every descriptor below has a
     * recorded owner — so the constructor alone is enough.
     */
    private static WorkspaceSettings enforcing() {
        return new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty());
    }

    private static SecurityIdentity identity(String principal) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        lenient().when(identity.isAnonymous()).thenReturn(false);
        Principal p = mock(Principal.class);
        lenient().when(p.getName()).thenReturn(principal);
        lenient().when(identity.getPrincipal()).thenReturn(p);
        return identity;
    }

    /** A real guard, seeing the world as somebody who owns nothing here. */
    private static ResourceAccessGuard realGuardFor(String principal, IDocumentDescriptorStore store) {
        var settings = enforcing();
        var identity = identity(principal);
        return new ResourceAccessGuard(identity, new OwnershipValidator(settings.isStampingOwnership()),
                new SpaceContext(identity, settings), settings, store);
    }

    /**
     * A {@code RestVersionInfo} whose type argument is explicit.
     * <p>
     * Written as
     * {@code new RestVersionInfo<>(..., mock(IResourceStore.class), ...)} the
     * diamond binds against a RAW mock, which makes the whole instance raw and
     * erases every generic signature on it — including
     * {@code List<DocumentDescriptor> readDescriptors(...)}, which then returns a
     * raw List and hands back Object.
     */
    private RestVersionInfo<Object> versionInfoFor(ResourceAccessGuard guard) {
        @SuppressWarnings("unchecked")
        IResourceStore<Object> resourceStore = mock(IResourceStore.class);
        return new RestVersionInfo<>("eddi://ai.labs.agent/agentstore/agents/", resourceStore, store, guard);
    }

    /** Published by alice, with a grant list a stranger must never receive. */
    private static DocumentDescriptor publishedByAliceWithGrants() {
        var d = new DocumentDescriptor();
        d.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + RESOURCE_ID + "?version=1"));
        d.setName("Support Agent");
        d.setOwnerId("alice");
        d.setSpaceId(Subjects.personalSpace("alice"));
        d.setVisibility(ResourceVisibility.published.wireName());
        d.setGrants(List.of(new ResourceGrant(Subjects.user("bob"), AccessLevel.USE.name(), "alice", new Date(0))));
        return DescriptorAccess.rebuildIndex(d);
    }

    @Test
    @DisplayName("a listing redacts what the caller may not read, through the real guard")
    void listingRedacts() throws Exception {
        var guard = realGuardFor("carol", store);
        var restVersionInfo = versionInfoFor(guard);
        when(store.readDescriptors(eq(TYPE), anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(publishedByAliceWithGrants()));

        var listed = restVersionInfo.readDescriptors("", 0, 20);

        // Carol may SEE a published resource, and must not receive its grant
        // audience — real principal and team names — merely by listing it.
        assertEquals(1, listed.size());
        assertNull(listed.getFirst().getGrants(), "the listing must strip grants for a non-owner");
        assertNull(listed.getFirst().getAccessIndex());
        assertEquals(AccessLevel.VIEW.name(), listed.getFirst().getCallerLevel(), "and must say what she may do");
    }

    @Test
    @DisplayName("a listing hands the store a scope built from the caller, not an unrestricted one")
    void listingPassesCallerScope() throws Exception {
        var guard = realGuardFor("carol", store);
        var restVersionInfo = versionInfoFor(guard);
        when(store.readDescriptors(eq(TYPE), anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        restVersionInfo.readDescriptors("", 0, 20);

        var scope = ArgumentCaptor.forClass(AccessScope.class);
        verify(store).readDescriptors(eq(TYPE), anyString(), anyInt(), anyInt(), anyBoolean(), scope.capture());
        // Not unrestricted: carol is not an administrator and enforcement is on.
        // An endpoint that stopped passing a scope would return everybody's rows.
        assertFalse(scope.getValue().isUnrestricted());
        assertTrue(
                scope.getValue().admittingTokens().contains(Subjects.personalSpace("carol")));
    }

    @Test
    @DisplayName("?space= actually reaches the scope handed to the store")
    void spaceNarrowingReachesTheStore() throws Exception {
        // Replacing `listingScope().withinSpace(space)` with `listingScope()`
        // left every other test green, and the switcher would have gone back to
        // changing the URL and nothing else.
        var guard = realGuardFor("carol", store);
        var restVersionInfo = versionInfoFor(guard);
        when(store.readDescriptors(eq(TYPE), anyString(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(List.of());

        restVersionInfo.readDescriptors("", 0, 20, "team:engineering");

        var scope = ArgumentCaptor.forClass(AccessScope.class);
        verify(store).readDescriptors(eq(TYPE), anyString(), anyInt(), anyInt(), anyBoolean(), scope.capture());
        assertEquals("team:engineering", scope.getValue().spaceId());
    }

    @Test
    @DisplayName("the descriptor endpoint redacts a versioned read through the real guard too")
    void versionedReadRedacts() throws Exception {
        var guard = realGuardFor("carol", store);
        var restStore = new RestDocumentDescriptorStore(store, guard);
        var current = publishedByAliceWithGrants();
        when(store.readCurrentDescriptor(RESOURCE_ID)).thenReturn(current);
        when(store.readDescriptor(RESOURCE_ID, 1)).thenReturn(publishedByAliceWithGrants());

        var read = restStore.readDescriptor(RESOURCE_ID, 1);

        assertNull(read.getGrants());
        assertNull(read.getAccessIndex());
        assertEquals(AccessLevel.VIEW.name(), read.getCallerLevel());
    }
}
