/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.deployment.mongo;

import ai.labs.eddi.configs.deployment.IDeploymentStorage;
import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import ai.labs.eddi.engine.cluster.events.RecordingEventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("DeploymentStore cluster announcements")
class DeploymentStoreTest {

    @Test
    @DisplayName("an unrecorded deploy is announced to the other nodes without touching the store")
    void announcesTransientDeployment() {
        IDeploymentStorage storage = mock(IDeploymentStorage.class);
        DeploymentStore store = new DeploymentStore(storage);
        RecordingEventBus events = new RecordingEventBus();
        store.clusterEvents = events;

        store.announceTransientDeployment("production", "agent7", 3);

        assertEquals(1, events.published.size());
        var published = events.published.get(0);
        assertEquals(ClusterEvent.DEPLOYMENT_CHANGED, published.type());
        assertEquals(DeploymentStore.TRANSIENT, published.payload().get("status"));
        assertEquals("agent7", published.payload().get("agentId"));
        assertEquals(3, published.payload().get("version"));
        verifyNoInteractions(storage);
    }
}
