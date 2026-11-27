/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthAgentCleanup — a deleted agent's links, profile and alert states")
class HealthAgentCleanupTest {

    private final IHealthReportLinkStore links = mock(IHealthReportLinkStore.class);
    private final IHealthProfileStore profiles = mock(IHealthProfileStore.class);
    private final IHealthAlertStateStore states = mock(IHealthAlertStateStore.class);
    private final HealthProfiles cache = mock(HealthProfiles.class);
    private final HealthAgentCleanup cleanup = new HealthAgentCleanup(links, profiles, states, cache);

    @Test
    @DisplayName("a soft delete revokes the links and keeps the profile; a permanent one removes all three")
    void softAndPermanent() {
        cleanup.agentDeleted("a", false);
        verify(links).deleteByAgent("a");
        verify(profiles, never()).delete(anyString());
        verify(states, never()).deleteByAgent(anyString());

        cleanup.agentDeleted("b", true);
        verify(links).deleteByAgent("b");
        verify(profiles).delete("b");
        verify(cache).invalidate("b");
        verify(states).deleteByAgent("b");
    }

    @Test
    @DisplayName("a failing store never fails the delete")
    void neverThrows() {
        when(links.deleteByAgent("a")).thenThrow(new HealthStoreException("down", null));
        when(profiles.delete("a")).thenThrow(new HealthStoreException("down", null));
        cleanup.agentDeleted("a", true);
        cleanup.agentDeleted(null, true);
    }
}
