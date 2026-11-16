/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.events.GroupHitlPauseCommittedEvent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GroupConversationService#firePauseCommitted}: the group pause
 * announcement.
 */
@DisplayName("GroupConversationService — group pause announcement")
class GroupConversationServicePauseEventTest {

    private GroupConversationService service;
    private Event<GroupHitlPauseCommittedEvent> event;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new GroupConversationService(mock(IAgentGroupStore.class), mock(IGroupConversationStore.class), mock(IConversationService.class),
                mock(IAgentFactory.class), mock(ITemplatingEngine.class), mock(IJsonSerialization.class), new SimpleMeterRegistry(),
                mock(AgentSigningService.class), mock(IAgentStore.class), mock(IScheduleStore.class), null,
                new CallerIdentityContext(null, null), "default", 3);
        event = mock(Event.class);
        service.groupHitlPauseCommittedEvent = event;
    }

    @Test
    @DisplayName("fires asynchronously with the pause id derived from the pause time")
    void firesWithPauseId() {
        Instant pausedAt = Instant.parse("2026-11-15T08:00:00Z");

        service.firePauseCommitted("gc-1", pausedAt);

        verify(event).fireAsync(new GroupHitlPauseCommittedEvent("gc-1", Long.toString(pausedAt.toEpochMilli())));
    }

    @Test
    @DisplayName("no pause time or no id: nothing an observer could match, nothing fired")
    void nothingToAnnounce() {
        service.firePauseCommitted("gc-1", null);
        service.firePauseCommitted(null, Instant.now());

        verify(event, never()).fireAsync(any());
    }

    @Test
    @DisplayName("a failing event bus never fails the pause, and a directly built service fires nothing")
    void neverThrows() {
        when(event.fireAsync(any())).thenThrow(new IllegalStateException("bus down"));
        assertDoesNotThrow(() -> service.firePauseCommitted("gc-1", Instant.now()));

        service.groupHitlPauseCommittedEvent = null;
        assertDoesNotThrow(() -> service.firePauseCommitted("gc-1", Instant.now()));
    }
}
