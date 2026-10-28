/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings;

import ai.labs.eddi.engine.ratings.model.ConversationRating;

/**
 * Fired (CDI, synchronously) after a rating was stored. Observers must return
 * at once: the person who rated is waiting for the response.
 */
public record ConversationRatingSaved(ConversationRating rating) {
}
