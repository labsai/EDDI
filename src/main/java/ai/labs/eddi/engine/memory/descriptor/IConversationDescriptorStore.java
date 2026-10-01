/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.descriptor;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;

import java.util.List;

/**
 * @author ginccc
 */
public interface IConversationDescriptorStore extends IDescriptorStore<ConversationDescriptor> {
    String resourceUri = "eddi://ai.labs.conversation/conversationstore/conversations/";

    void updateTimeStamp(String conversationId);

    /**
     * As {@link #readDescriptors(String, String, Integer, Integer, boolean)}, with
     * further filter groups ANDed into the query, so that {@code index} and
     * {@code limit} page through the descriptors that match them.
     *
     * @param restrictions
     *            additional filter groups; {@code null} or empty means none
     */
    List<ConversationDescriptor> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted,
                                                 List<IResourceFilter.QueryFilters> restrictions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
