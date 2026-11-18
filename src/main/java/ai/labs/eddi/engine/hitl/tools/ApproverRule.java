/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

/**
 * Who may approve a call that a {@link MandatoryApprovalRule} gated.
 * <p>
 * Declared from the least to the most demanding; when two rules gate the same
 * call, the stricter one wins ({@link #stricter}).
 */
public enum ApproverRule {

    /**
     * Oversight: whoever the agent's effective HITL config lets approve. The
     * existing approver rules apply (owner, administrator, {@code eddi-approver}
     * role, an approval channel), so the effective tool-approval config must have
     * an active gate — without one there is nobody it names, and the call is
     * refused instead of paused.
     */
    ANY_PERMITTED_APPROVER,

    /**
     * Consent: the conversation's own verified user, or an administrator acting on
     * their behalf with a reason (recorded as such). Any other approver — a
     * delegate, another member, someone the HITL config admits only as an approver
     * — is not enough, and their decision is refused while the call stays pending.
     */
    CONVERSATION_USER;

    /** The stricter of the two; {@code null} counts as absent. */
    public static ApproverRule stricter(ApproverRule a, ApproverRule b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Lenient parse of a persisted name; unknown or absent is {@code null}. */
    public static ApproverRule parse(String name) {
        if (name == null) {
            return null;
        }
        for (ApproverRule rule : values()) {
            if (rule.name().equals(name)) {
                return rule;
            }
        }
        return null;
    }
}
