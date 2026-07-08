/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store interface for group conversation transcripts. DB-agnostic, uses
 * {@code IResourceStorageFactory} under the hood.
 *
 * @author ginccc
 */
public interface IGroupConversationStore {

    String create(GroupConversation conversation) throws IResourceStore.ResourceStoreException;

    GroupConversation read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    void update(GroupConversation conversation) throws IResourceStore.ResourceStoreException;

    void delete(String id) throws IResourceStore.ResourceStoreException;

    List<GroupConversation> listByGroupId(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException;

    /**
     * Atomically transition a group conversation from expectedState to newState.
     * Returns true if the transition succeeded (state matched), false if the state
     * had already changed (concurrent modification).
     */
    boolean compareAndSetState(String id, GroupConversation.GroupConversationState expectedState,
                               GroupConversation.GroupConversationState newState)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
