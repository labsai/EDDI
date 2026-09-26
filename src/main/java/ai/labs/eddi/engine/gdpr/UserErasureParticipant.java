/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

/**
 * Implemented by anything that runs work on a user's behalf in the background
 * of a request — a conversation turn, a group discussion — so a GDPR Art. 17
 * erasure can stop it before deleting the user's data.
 *
 * <h3>Why this exists</h3> The erasure cascade deletes store by store while
 * such work keeps running. A turn in flight writes its longTerm properties back
 * to user memory at teardown, and a running group discussion writes its
 * document on every phase, so an erasure that reported success was followed
 * seconds later by the data it had just removed. Stopping the work first is
 * what makes the deletes stick.
 *
 * <h3>Why an SPI rather than a direct call</h3> The services that own such work
 * depend on {@link GdprComplianceService} (they check the Art. 18 restriction
 * on every turn), so the dependency cannot point the other way. Participants
 * are discovered through CDI, the same way
 * {@code SealedDataRotationParticipant} joins key rotation.
 *
 * <h3>The contract implementations must honour</h3> {@link #stopInFlightWork}
 * is called <b>before</b> the cascade, synchronously, and must not wait for the
 * work to finish: it signals, and the work's own persistence path must then
 * decline to write. Its reach is this node — work running on another replica is
 * covered by the stores refusing to recreate a document the cascade deleted.
 * Throwing is reported as a failed cascade step named by
 * {@link #erasureStepName()}; the cascade still runs.
 */
public interface UserErasureParticipant {

    /**
     * The name reported in {@code GdprDeletionResult.failedSteps} if
     * {@link #stopInFlightWork} throws. Lower camel case, like the cascade's own
     * step names.
     */
    String erasureStepName();

    /**
     * Signals every piece of work this participant runs for the user on this node
     * to stop without persisting anything further.
     *
     * @param userId
     *            the user being erased
     * @return how many pieces of work were signalled
     */
    int stopInFlightWork(String userId);
}
