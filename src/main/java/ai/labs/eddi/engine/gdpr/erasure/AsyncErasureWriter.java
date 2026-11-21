/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import java.time.Duration;

/**
 * Implemented by anything that writes per-user or per-conversation rows from a
 * background queue — work captured during a turn or a request and written
 * later. Discovered through CDI; every bean of this type takes part in the
 * erasure guarantee.
 *
 * <h3>Why</h3> A row captured before a GDPR erasure (or an explicit
 * conversation delete) can reach the store after the deletion ran, and an
 * erasure that reported success would be followed by the data it had just
 * removed. The guarantee is built from three pieces, and a writer must take
 * part in all three:
 * <ol>
 * <li><b>Guarded writes.</b> Before writing a row, call
 * {@link ErasureGuard#beforeWrite} and do not write when it says no (or write
 * the row without its personal part). After the row is stored, call
 * {@link ErasureGuard#afterWrite} with a remover of that one row: if an erasure
 * started in between, the guard removes it — or records a durable pending
 * removal that is retried, and that keeps the erasure failed until it is
 * gone.</li>
 * <li><b>A drain barrier</b> ({@link #awaitDrained}): every node, during the
 * erasure's stop handshake and an explicit conversation delete, waits until the
 * work this writer had queued before the barrier has finished. Work queued
 * after it runs after the tombstone was written, so
 * {@link ErasureGuard#beforeWrite} refuses it.</li>
 * <li><b>Delete and verify.</b> For a user erasure, the writer's own
 * {@code IGdprParticipant} deletes the user's rows and then calls
 * {@link ErasureGuard#verifyUserErased}. For a conversation delete the guard
 * calls {@link #eraseConversation} and {@link #countForConversation}
 * itself.</li>
 * </ol>
 * Implementations must be thread-safe; {@link #awaitDrained} is called from
 * request threads and cluster RPC handlers while the writer's queue runs.
 */
public interface AsyncErasureWriter {

    /**
     * Stable lower-camel-case name: recorded in pending removals (so it must not
     * change between releases) and reported in failed steps.
     */
    String name();

    /**
     * Waits until every piece of work this writer accepted before the call has
     * finished — written, skipped or failed. Work accepted after the call is not
     * waited for.
     *
     * @return {@code false} when that did not happen within {@code timeout} (the
     *         caller reports the erasure as incomplete)
     */
    boolean awaitDrained(Duration timeout) throws InterruptedException;

    /**
     * Removes the personal content of one row this writer wrote — the retry of a
     * failed {@link ErasureGuard#afterWrite} removal. Idempotent: a row that no
     * longer exists, or no longer holds anything personal, is a success.
     *
     * @throws Exception
     *             when the row could not be removed (the removal stays pending)
     */
    void removeRow(String rowId) throws Exception;

    /**
     * Removes the personal content of every row of {@code conversationId} — an
     * explicit conversation delete.
     *
     * @return how many rows changed
     */
    long eraseConversation(String conversationId) throws Exception;

    /**
     * How many rows of {@code conversationId} still hold personal content; the
     * delete succeeds only at 0.
     */
    long countForConversation(String conversationId) throws Exception;
}
