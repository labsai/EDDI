/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import java.time.Instant;

/**
 * A conversation with turn rows written in a time range, and the newest
 * {@code updatedAt} among them in that range — what the outcome evaluation
 * pages through, oldest activity first.
 */
public record ConversationActivity(String conversationId, Instant lastUpdatedAt) {
}
