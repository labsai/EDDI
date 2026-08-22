/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import java.util.function.UnaryOperator;

/**
 * Implemented by anything that stores data sealed with a tenant's DEK, so DEK
 * rotation re-seals it instead of destroying it.
 *
 * <h3>Why this exists</h3> {@link ISecretProvider#seal} deliberately encrypts
 * with the same per-tenant DEK the vault uses for named secrets — a second key
 * hierarchy for OAuth refresh tokens would mean a second key to rotate and a
 * second place for the crypto to be subtly wrong. The consequence nobody wired
 * up: DEK rotation re-encrypted the secret collection and nothing else, then
 * replaced the DEK. Every sealed value outside that collection became
 * permanently undecryptable, so an operator performing a routine, documented,
 * compliance-driven key rotation silently disconnected every user's linked SaaS
 * account — and found out one {@code invalid_grant} at a time.
 *
 * <h3>Why an SPI rather than a direct call</h3> {@code ai.labs.eddi.secrets} is
 * a leaf package on purpose: the vault must not depend on the things that use
 * it, or every future consumer of {@code seal} adds an edge back into the
 * crypto. Rotation discovers participants through CDI instead, so a new
 * sealed-data owner joins by implementing this interface and nothing in the
 * vault changes.
 *
 * <h3>The contract implementations must honour</h3> {@link #resealAll} is
 * called <b>after</b> the new DEK generation has been committed, and it is a
 * migration sweep rather than an all-or-nothing switch. Every older generation
 * still exists and still decrypts, so a row this method fails to move is
 * <b>not</b> lost — it keeps naming a key that works, and the next rotation
 * picks it up. Write row by row, each write guarded on the state the row was
 * read in, so a concurrent writer is never clobbered.
 * <p>
 * Throwing does not roll anything back: the new generation is already active.
 * Rotation catches it, counts the tenant as incompletely migrated, and reports
 * that the operation is safe to re-run. Reporting outstanding rows through the
 * return value is preferred, because a count survives where an exception ends
 * the sweep.
 */
public interface SealedDataRotationParticipant {

    /**
     * What this participant stores, for the rotation log line. Something an
     * operator can act on ("OAuth connection grants"), not a class name.
     */
    String sealedDataDescription();

    /**
     * Moves every value this participant holds for the tenant onto the active DEK
     * generation, skipping whatever is already there.
     *
     * @param tenantId
     *            the tenant being rotated
     * @param activeDekId
     *            the dekId of the generation to end up on. A row already naming it
     *            needs nothing done and must be left alone — a concurrent writer
     *            sealed it under the new key while this sweep was running
     * @param resealer
     *            re-seals one value under the active generation, opening it with
     *            whichever generation its own
     *            {@link ISecretProvider.SealedValue#dekId()} names. Valid only for
     *            the duration of this call, and only for values belonging to
     *            {@code tenantId}
     * @return how many rows are still NOT on the active generation when this
     *         returns — zero means the tenant is fully migrated
     */
    int resealAll(String tenantId, String activeDekId, UnaryOperator<ISecretProvider.SealedValue> resealer);
}
