/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

/**
 * Who approved a mandatorily gated call, and in what capacity — established by
 * {@link MandatoryApproverCheck} on the resume path, never read from a request
 * body.
 * <p>
 * It travels with the decision to the resumed tool loop, which executes a
 * mandatory call only when one is present, and it is bound around that call's
 * execution ({@link ApprovedCallScope#attribution()}), so a record the call
 * creates (a library entry, a share, an egress) can store {@code approvedBy}
 * and {@code onBehalfOf}.
 *
 * @param approvedBy
 *            the user id of whoever approved
 * @param onBehalfOf
 *            the conversation's user when an administrator approved for them;
 *            null otherwise
 * @param actingAs
 *            the capacity the approval was accepted in
 */
public record MandatoryApprovalAttribution(String approvedBy, String onBehalfOf, ActingAs actingAs) {

    /** The capacity a mandatory approval was accepted in. */
    public enum ActingAs {
        /** The conversation's own verified user. */
        USER,
        /**
         * An approver the agent's HITL config admits ({@code ANY_PERMITTED_APPROVER}).
         */
        APPROVER,
        /** An administrator, for the conversation's user, with a reason. */
        ADMIN_ON_BEHALF
    }

    public MandatoryApprovalAttribution {
        if (actingAs == null) {
            throw new IllegalArgumentException("actingAs is required");
        }
    }
}
