/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.lifecycle.model;

/**
 * A human's request to revise the answer under review: the persisted candidate,
 * the reviewer's note (human-written data, never template source) and which
 * revision of the turn this is (1-based).
 *
 * @since 6.6.0
 */
public record HitlRevisionRequest(HitlRevisionCandidate candidate, String note, int round) {
}
