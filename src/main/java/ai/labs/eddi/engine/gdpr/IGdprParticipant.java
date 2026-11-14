/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import java.util.List;
import java.util.Map;

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

    /**
     * When this participant runs relative to the others, ascending; ties in no
     * particular order. Default {@code 0}.
     * <p>
     * It matters for one dependency: a participant that finds a person's records by
     * their email address (the email ledger) looks the address up in the user
     * directory, so the directory, which erases that address, runs last
     * ({@value #LAST}).
     */
    default int order() {
        return 0;
    }

    /**
     * What this participant matched {@code userId} by, for the compliance entry of
     * the erasure or export ({@code <name>Basis}) — so the ledger shows the basis
     * on which records were reached when it is not the id itself (an email address,
     * say). Sources and counts only, never identifiers: the ledger names the person
     * by pseudonym. Empty (the default) adds nothing. Called before {@link #erase};
     * a failure is logged and leaves the basis out.
     */
    default Map<String, Object> auditBasis(String userId) throws Exception {
        return Map.of();
    }

    /** The request a notice is about. */
    enum Operation {
        ERASURE, EXPORT
    }

    /**
     * What the caller of an erasure or export must know although no step failed —
     * data this participant deliberately did not reach, and what to do about it.
     * Returned to the caller in {@code notices} (prefixed with {@link #name()}) and
     * recorded in the compliance entry. Like {@link #auditBasis}, no identifiers:
     * the response must not carry personal data the request did not ask for. Called
     * before {@link #erase} / {@link #export}; a failure is logged and adds
     * nothing. Empty by default.
     */
    default List<String> notices(String userId, Operation operation) throws Exception {
        return List.of();
    }

    /** {@link #order()} of a participant others may still need to read. */
    int LAST = 1000;
}
