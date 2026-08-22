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
 * called <b>while the old DEK is still the tenant's DEK</b>. Prepare every
 * re-sealed value in memory first and only then write — the same
 * prepare-then-commit shape the secret loop uses — so a failure partway through
 * leaves rows readable rather than half-migrated. Throwing aborts the rotation
 * before the DEK is replaced, which is the safe outcome.
 */
public interface SealedDataRotationParticipant {

    /**
     * What this participant stores, for the rotation log line. Something an
     * operator can act on ("OAuth connection grants"), not a class name.
     */
    String sealedDataDescription();

    /**
     * Re-seals every value this participant holds for the tenant.
     *
     * @param tenantId
     *            the tenant being rotated
     * @param resealer
     *            turns a value sealed with the OLD DEK into one sealed with the NEW
     *            DEK. Valid only for the duration of this call, and only for values
     *            belonging to {@code tenantId}
     * @return how many values were re-sealed, for the rotation log
     */
    int resealAll(String tenantId, UnaryOperator<ISecretProvider.SealedValue> resealer);
}
