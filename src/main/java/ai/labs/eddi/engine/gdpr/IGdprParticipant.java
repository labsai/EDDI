/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

/**
 * A store of personal data that joins GDPR erasure and export without
 * {@link GdprComplianceService} having to know about it.
 * <p>
 * The service names every conversation-era store explicitly, because each needs
 * its own ordering and its own failure handling. Stores added since — the user
 * directory, workspace notifications — hold small, self-contained records keyed
 * by the principal, and need neither. Registering them as beans is also what
 * keeps a new store from being forgotten: a participant that exists is erased.
 */
public interface IGdprParticipant {

    /**
     * The step name reported in {@link GdprDeletionResult#failedSteps()} and the
     * key of this participant's section in an export.
     */
    String name();

    /**
     * Deletes everything held about {@code userId}.
     *
     * @return how many records were removed
     * @throws Exception
     *             on failure; the erasure carries on and names this step as
     *             incomplete
     */
    long erase(String userId) throws Exception;

    /**
     * What is held about {@code userId}, in a form that serialises to JSON, or
     * {@code null} when nothing is.
     */
    Object export(String userId) throws Exception;
}
