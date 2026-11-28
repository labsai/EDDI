/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

/**
 * A conversation whose latest committed step decided to continue, as the
 * recovery sweep reads it: the identifiers it needs to arm the continuation,
 * the state that decides whether it still may, and the outbox record. A
 * projection — never the conversation's history.
 *
 * @param conversationId
 *            the conversation
 * @param agentId
 *            its agent
 * @param userId
 *            its owner, stamped on the schedule (the fire refuses any other)
 * @param state
 *            its stored state; {@code null} when the document carries none
 * @param pending
 *            the outbox record, never {@code null}
 */
public record PendingContinuationCandidate(String conversationId, String agentId, String userId, ConversationState state,
        PendingContinuation pending) {
}
