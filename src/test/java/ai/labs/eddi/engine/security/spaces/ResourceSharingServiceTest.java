/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.datastore.IResourceStore;
import io.quarkus.security.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sharing has two properties worth defending: it reaches the whole graph
 * beneath an agent, and it stops at resources the sharer does not own.
 */
class ResourceSharingServiceTest {

    private static final String AGENT = "agent0000000000000000";
    private static final String OWNED_CHILD = "child0000000000000000";
    private static final String BORROWED_CHILD = "borrow000000000000000";

    private IDocumentDescriptorStore store;
    private ResourceAccessGuard accessGuard;
    private ConfigGraphResolver graphResolver;
    private ResourceSharingService service;
    private Map<String, DocumentDescriptor> descriptors;

    private static DocumentDescriptor descriptor(String owner) {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setOwnerId(owner);
        d.setSpaceId(Subjects.personalSpace(owner));
        d.setVisibility(ResourceVisibility.space.wireName());
        return DescriptorAccess.rebuildIndex(d);
    }

    @BeforeEach
    void setUp() throws Exception {
        descriptors = new HashMap<>();
        descriptors.put(AGENT, descriptor("alice"));
        descriptors.put(OWNED_CHILD, descriptor("alice"));
        descriptors.put(BORROWED_CHILD, descriptor("bob"));

        store = mock(IDocumentDescriptorStore.class);
        // describe() resolves through readCurrentDescriptor; the mutating paths resolve
        // the version explicitly so they can write back to the version they read.
        when(store.readCurrentDescriptor(anyString())).thenAnswer(i -> {
            var d = descriptors.get(i.<String>getArgument(0));
            if (d == null) {
                throw new IResourceStore.ResourceNotFoundException("none");
            }
            return d;
        });
        when(store.readDescriptor(anyString(), anyInt())).thenAnswer(i -> {
            var d = descriptors.get(i.<String>getArgument(0));
            if (d == null) {
                throw new IResourceStore.ResourceNotFoundException("none");
            }
            return d;
        });
        when(store.getCurrentResourceId(anyString())).thenAnswer(i -> {
            if (!descriptors.containsKey(i.<String>getArgument(0))) {
                throw new IResourceStore.ResourceNotFoundException("none");
            }
            return new IResourceStore.IResourceId() {
                @Override
                public String getId() {
                    return i.getArgument(0);
                }

                @Override
                public Integer getVersion() {
                    return 1;
                }
            };
        });

        accessGuard = mock(ResourceAccessGuard.class);
        when(accessGuard.currentPrincipal()).thenReturn("alice");
        // Alice owns the agent and one child; the other child is only lent to her.
        when(accessGuard.canAccess(any(), eq(AccessLevel.OWN)))
                .thenAnswer(i -> "alice".equals(i.<DocumentDescriptor>getArgument(0).getOwnerId()));
        doAnswer(i -> {
            DocumentDescriptor d = descriptors.get(i.<String>getArgument(0));
            if (d != null && !"alice".equals(d.getOwnerId())) {
                throw new ForbiddenException("not owner");
            }
            return null;
        }).when(accessGuard).requireAccess(anyString(), eq(AccessLevel.OWN), anyString());

        graphResolver = mock(ConfigGraphResolver.class);
        when(graphResolver.referencedResourceIds(AGENT)).thenReturn(Set.of(OWNED_CHILD, BORROWED_CHILD));

        service = new ResourceSharingService(store, accessGuard, graphResolver);
    }

    @Test
    @DisplayName("sharing an agent reaches the resources it references")
    void sharingCascades() {
        var result = service.share(AGENT, Subjects.user("carol"), AccessLevel.VIEW, true);

        assertTrue(result.updatedIds().contains(AGENT));
        assertTrue(result.updatedIds().contains(OWNED_CHILD),
                "an agent shared without its config graph is a name pointing at documents the recipient cannot open");
        assertEquals(AccessLevel.VIEW, grantFor(descriptors.get(OWNED_CHILD), Subjects.user("carol")));
    }

    @Test
    @DisplayName("a referenced resource the sharer does not own is skipped, not silently widened")
    void doesNotResharePassOnBorrowedAccess() {
        var result = service.share(AGENT, Subjects.user("carol"), AccessLevel.VIEW, true);

        assertTrue(result.skippedIds().contains(BORROWED_CHILD));
        assertFalse(result.updatedIds().contains(BORROWED_CHILD));
        assertEquals(null, grantFor(descriptors.get(BORROWED_CHILD), Subjects.user("carol")),
                "you cannot pass on access you were only lent");
    }

    @Test
    @DisplayName("cascade=false touches exactly the named resource")
    void noCascade() {
        var result = service.share(AGENT, Subjects.user("carol"), AccessLevel.USE, false);

        assertEquals(List.of(AGENT), result.updatedIds());
        verify(graphResolver, org.mockito.Mockito.never()).referencedResourceIds(anyString());
    }

    @Test
    @DisplayName("re-sharing replaces the level rather than accumulating a second grant")
    void regrantReplaces() {
        service.share(AGENT, Subjects.user("carol"), AccessLevel.VIEW, false);
        service.share(AGENT, Subjects.user("carol"), AccessLevel.USE, false);

        List<ResourceGrant> grants = descriptors.get(AGENT).getGrants();
        assertEquals(1, grants.stream().filter(g -> Subjects.user("carol").equals(g.getSubject())).count(),
                "a duplicate grant would make one revoke insufficient to actually revoke");
        assertEquals(AccessLevel.USE, grantFor(descriptors.get(AGENT), Subjects.user("carol")));
    }

    @Test
    @DisplayName("revoking removes the grant across the graph")
    void revokeCascades() {
        service.share(AGENT, Subjects.user("carol"), AccessLevel.VIEW, true);
        service.revoke(AGENT, Subjects.user("carol"), true);

        assertEquals(null, grantFor(descriptors.get(AGENT), Subjects.user("carol")));
        assertEquals(null, grantFor(descriptors.get(OWNED_CHILD), Subjects.user("carol")));
    }

    @Test
    @DisplayName("only the owner may share; EDIT is deliberately not enough")
    void nonOwnerCannotShare() {
        assertThrows(ForbiddenException.class,
                () -> service.share(BORROWED_CHILD, Subjects.user("carol"), AccessLevel.VIEW, false));
    }

    @Test
    @DisplayName("every write rebuilds the access index, or the change never reaches a listing")
    void writesRebuildTheIndex() throws Exception {
        service.share(AGENT, Subjects.user("carol"), AccessLevel.VIEW, false);

        verify(accessGuard).stampModification(descriptors.get(AGENT));
        verify(store).setDescriptor(eq(AGENT), anyInt(), eq(descriptors.get(AGENT)));
    }

    @Test
    @DisplayName("publishing cascades, so a published agent is actually usable")
    void publishCascades() {
        var result = service.setVisibility(AGENT, ResourceVisibility.published, true);

        assertEquals(ResourceVisibility.published.wireName(), descriptors.get(AGENT).getVisibility());
        assertEquals(ResourceVisibility.published.wireName(), descriptors.get(OWNED_CHILD).getVisibility());
        assertTrue(result.skippedIds().contains(BORROWED_CHILD));
    }

    @Test
    @DisplayName("ownership transfer is administrators only")
    void transferIsAdminOnly() {
        when(accessGuard.isAdmin()).thenReturn(false);
        assertThrows(ForbiddenException.class, () -> service.transferOwnership(AGENT, "dave", null, false));

        when(accessGuard.isAdmin()).thenReturn(true);
        service.transferOwnership(AGENT, "dave", null, false);
        assertEquals("dave", descriptors.get(AGENT).getOwnerId());
        assertEquals(Subjects.personalSpace("dave"), descriptors.get(AGENT).getSpaceId());
    }

    private static AccessLevel grantFor(DocumentDescriptor d, String subject) {
        if (d.getGrants() == null) {
            return null;
        }
        return d.getGrants().stream().filter(g -> subject.equals(g.getSubject())).map(ResourceGrant::accessLevel).findFirst().orElse(null);
    }
}
