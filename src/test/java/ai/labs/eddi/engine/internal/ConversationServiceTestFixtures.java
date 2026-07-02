/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import jakarta.enterprise.event.Event;

import static org.mockito.Mockito.mock;

/**
 * Shared test fixtures for {@link ConversationService} unit tests. Centralizes
 * the no-op HITL resume-completed {@link Event} mock so the many constructor
 * call sites don't each repeat an unchecked cast.
 */
final class ConversationServiceTestFixtures {

    private ConversationServiceTestFixtures() {
    }

    /**
     * A no-op mock of the {@code Event<HitlResumeCompletedEvent>} the service fires
     * on resume completion. {@code fireAsync} on a plain mock returns a null future
     * and does nothing — sufficient for tests that don't assert on the event.
     */
    @SuppressWarnings("unchecked")
    static Event<HitlResumeCompletedEvent> hitlResumeEvent() {
        return (Event<HitlResumeCompletedEvent>) mock(Event.class);
    }
}
