/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.descriptors;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.descriptors.model.ResourceGrant;
import ai.labs.eddi.configs.descriptors.model.ResourceVisibility;
import ai.labs.eddi.engine.security.spaces.DescriptorAccess;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.security.spaces.Subjects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Duplicating a resource must produce something owned by whoever duplicated it.
 * <p>
 * Copying the source descriptor verbatim — which is what this did before —
 * files the copy under the <em>source owner's</em> name and space. Since
 * duplicating a {@code published} agent is something anyone may do, that let
 * any user inject resources into someone else's workspace, attributed to them,
 * while leaving themselves unable to edit or delete what they had just created.
 */
class ResourceUtilitiesDuplicateOwnershipTest {

    private static final String OLD_ID = "aaaaaaaaaaaaaaaaaaaaaa";
    private static final URI NEW_LOCATION = URI.create("eddi://ai.labs.agent/agentstore/agents/bbbbbbbbbbbbbbbbbbbbbb?version=1");

    @Test
    @DisplayName("the copy carries the duplicator's stamp, not the source's ownership")
    void doesNotInheritOwnership() throws Exception {
        DocumentDescriptor source = new DocumentDescriptor();
        source.setName("Alice's agent");
        source.setDescription("hers");
        source.setOwnerId("alice");
        source.setSpaceId(Subjects.personalSpace("alice"));
        source.setVisibility(ResourceVisibility.published.wireName());
        source.setGrants(List.of(new ResourceGrant(Subjects.user("dave"), "OWN", "alice", new Date(0))));
        DescriptorAccess.rebuildIndex(source);

        var store = mock(IDocumentDescriptorStore.class);
        when(store.readDescriptor(anyString(), anyInt())).thenReturn(source);

        // A guard standing in for Bob: stamps him as owner, as the real one would.
        var guard = mock(ResourceAccessGuard.class);
        when(guard.stampNewDescriptor(any())).thenAnswer(i -> {
            DocumentDescriptor d = i.getArgument(0);
            d.setOwnerId("bob");
            d.setSpaceId(Subjects.personalSpace("bob"));
            d.setVisibility(ResourceVisibility.space.wireName());
            return DescriptorAccess.rebuildIndex(d);
        });

        ResourceUtilities.createDocumentDescriptorForDuplicate(store, guard, OLD_ID, 1, NEW_LOCATION);

        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(store).createDescriptor(anyString(), anyInt(), captor.capture());
        DocumentDescriptor copy = captor.getValue();

        assertEquals("bob", copy.getOwnerId(), "the copy belongs to whoever made it");
        assertEquals(Subjects.personalSpace("bob"), copy.getSpaceId());
        assertEquals(ResourceVisibility.space.wireName(), copy.getVisibility(),
                "a copy of a published resource is not itself published");
        assertNull(copy.getGrants(), "the source's grants are not carried over to a new resource");
    }

    @Test
    @DisplayName("name and description are carried over, with the copy suffix")
    void carriesNameAndDescription() throws Exception {
        DocumentDescriptor source = new DocumentDescriptor();
        source.setName("Alice's agent");
        source.setDescription("hers");
        source.setOwnerId("alice");

        var store = mock(IDocumentDescriptorStore.class);
        when(store.readDescriptor(anyString(), anyInt())).thenReturn(source);
        var guard = mock(ResourceAccessGuard.class);
        when(guard.stampNewDescriptor(any())).thenAnswer(i -> i.getArgument(0));

        ResourceUtilities.createDocumentDescriptorForDuplicate(store, guard, OLD_ID, 1, NEW_LOCATION);

        var captor = ArgumentCaptor.forClass(DocumentDescriptor.class);
        verify(store).createDescriptor(anyString(), anyInt(), captor.capture());
        assertEquals("Alice's agent - Copy", captor.getValue().getName());
        assertEquals("hers", captor.getValue().getDescription());
    }

    @Test
    @DisplayName("the source descriptor is left untouched")
    void doesNotMutateTheSource() throws Exception {
        DocumentDescriptor source = new DocumentDescriptor();
        source.setName("Alice's agent");
        source.setOwnerId("alice");
        source.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + OLD_ID + "?version=1"));

        var store = mock(IDocumentDescriptorStore.class);
        when(store.readDescriptor(anyString(), anyInt())).thenReturn(source);
        var guard = mock(ResourceAccessGuard.class);
        when(guard.stampNewDescriptor(any())).thenAnswer(i -> i.getArgument(0));

        ResourceUtilities.createDocumentDescriptorForDuplicate(store, guard, OLD_ID, 1, NEW_LOCATION);

        // The previous implementation mutated the object it read and stored that: the
        // source's own name gained " - Copy" and its resource URI pointed at the copy.
        assertEquals("Alice's agent", source.getName());
        assertEquals("alice", source.getOwnerId());
        assertEquals(URI.create("eddi://ai.labs.agent/agentstore/agents/" + OLD_ID + "?version=1"), source.getResource());
    }
}
