/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

/**
 * A decision would approve a mandatorily gated call, but the approver is not
 * one its {@link ApproverRule} accepts. Nothing ran and the pause is untouched:
 * the call stays pending for someone who may decide it. REST answers 403.
 * <p>
 * The message is fixed text, safe to return to the caller.
 */
public class MandatoryApprovalRefusedException extends RuntimeException {

    public MandatoryApprovalRefusedException(String message) {
        super(message);
    }
}
