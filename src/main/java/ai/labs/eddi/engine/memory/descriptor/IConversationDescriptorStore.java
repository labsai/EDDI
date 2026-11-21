/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.descriptor;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor.TitleSource;

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

    /**
     * Writes a generated title — {@link TitleSource#AUTO} (the first non-secret
     * message) or {@link TitleSource#LLM} — onto the live descriptor, but only over
     * a title of lower precedence: {@code AUTO} only where there is no title yet,
     * {@code LLM} only over none or an {@code AUTO} one. A title the owner set is
     * never replaced. The check and the write are one atomic update.
     *
     * @return whether the title was written
     */
    boolean writeGeneratedTitle(String conversationId, String title, TitleSource source);

    /**
     * Writes the owner's edit onto the live descriptor, unconditionally: a non-null
     * {@code title} becomes the title with source {@link TitleSource#USER}, a
     * non-null {@code pinned} the pin. At least one must be given.
     *
     * @return {@code false} when there is no live descriptor (deleted, or never
     *         written)
     */
    boolean writeOwnerEdit(String conversationId, String title, Boolean pinned);

    /**
     * Sets {@code conversationState} to ENDED on the live descriptor, as a single
     * field update, so it cannot undo a concurrent rename or pin.
     *
     * @return {@code false} when there is no live descriptor (deleted, or never
     *         written)
     */
    boolean markEnded(String conversationId);
}
