/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.model.Context;

import java.net.URI;

public interface IConversationSetup {
    void createConversationDescriptor(String agentId, IAgent latestAgent, String userId, String conversationId, URI conversationUri)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    String computeAnonymousUserIdIfEmpty(String userId, Context userIdContext);
}
