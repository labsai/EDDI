/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

/**
 * Fired (CDI, synchronously) after a rating was withdrawn — the counterpart of
 * {@link ConversationRatingSaved}. Observers must return at once: the person
 * who withdrew it is waiting for the response.
 *
 * @param stepIndex
 *            the withdrawn rating's step, {@code -1} for the conversation
 *            rating
 */
public record ConversationRatingWithdrawn(String conversationId, int stepIndex) {
}
