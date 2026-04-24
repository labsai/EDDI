/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.util.concurrent.Callable;

/**
 * Abstraction for conversation message ordering and delivery.
 *
 * <p>
 * Provides a pluggable event bus that guarantees sequential processing per
 * conversation while allowing concurrent processing across conversations.
 * </p>
 *
 * <h2>Implementations</h2>
 * <ul>
 * <li><strong>InMemoryConversationCoordinator</strong> (default) — In-process
 * queue, zero dependencies</li>
 * <li><strong>NatsConversationCoordinator</strong> — NATS JetStream-backed,
 * durable, scalable</li>
 * </ul>
 *
 * <p>
 * Selected via config property {@code eddi.messaging.type} (default:
 * {@code in-memory}).
 * </p>
 *
 * @author ginccc
 * @since 6.0.0
 */
public interface IEventBus {

    /**
     * Submit a task for ordered processing within a conversation. Guarantees
     * sequential execution per conversationId — messages for the same conversation
     * are never processed concurrently.
     *
     * @param conversationId
     *            the unique identifier of the conversation
     * @param callable
     *            the task to execute (typically lifecycle workflow execution)
     */
    void submitInOrder(String conversationId, Callable<Void> callable);

    /**
     * Start the event bus. For in-memory implementations this is a no-op. For
     * external brokers (NATS, etc.) this establishes the connection.
     */
    default void start() {
        // No-op by default
    }

    /**
     * Shutdown the event bus gracefully, draining any pending messages. For
     * in-memory implementations this is a no-op.
     */
    default void shutdown() {
        // No-op by default
    }
}
