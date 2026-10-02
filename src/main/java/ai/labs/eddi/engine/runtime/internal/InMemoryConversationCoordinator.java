/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * In-memory implementation of {@link IConversationCoordinator}: in-process
 * per-conversation queues, no external dependencies — the single-node default
 * ({@code eddi.messaging.type=in-memory}).
 * <p>
 * All of the queueing, failure handling and metrics live in
 * {@link AbstractQueuedConversationCoordinator}, extracted unchanged from this
 * class; the head task is handed to the runtime synchronously, so a rejected
 * submission still throws to the submitting caller (C10).
 * <p>
 * For horizontal scaling, {@code eddi.messaging.type=nats} selects
 * {@link ClusterConversationCoordinator} at runtime (see
 * {@code ClusterProducers}); the setting used to be read by nothing.
 *
 * <h3>Hardening (v6.0.2)</h3>
 * <ul>
 * <li><b>Eager cleanup</b>: Queues are removed from the map as soon as they
 * become empty, preventing memory leaks from abandoned conversations.</li>
 * <li><b>Max-size limit</b>: Configurable cap on active conversations
 * ({@code eddi.coordinator.max-active-conversations}). Follow-up messages to
 * currently-queued conversations are always accepted; conversations that have
 * fully drained are treated as new.</li>
 * <li><b>Micrometer metrics</b>: {@code eddi.coordinator.active_conversations},
 * {@code eddi.coordinator.queue_depth},
 * {@code eddi.coordinator.total_processed},
 * {@code eddi.coordinator.total_dead_lettered},
 * {@code eddi.coordinator.dead_letters}.</li>
 * </ul>
 *
 * @author ginccc
 * @see ai.labs.eddi.engine.runtime.IEventBus
 */
@ApplicationScoped
@Typed(InMemoryConversationCoordinator.class)
public class InMemoryConversationCoordinator extends AbstractQueuedConversationCoordinator {

    @Inject
    public InMemoryConversationCoordinator(IRuntime runtime, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.coordinator.max-active-conversations", defaultValue = "10000") int maxActiveConversations,
            @ConfigProperty(name = "eddi.coordinator.max-dead-letters", defaultValue = "1000") int maxDeadLetters) {
        super(runtime, meterRegistry, maxActiveConversations, maxDeadLetters);
    }

    @Override
    public String getCoordinatorType() {
        return "in-memory";
    }
}
