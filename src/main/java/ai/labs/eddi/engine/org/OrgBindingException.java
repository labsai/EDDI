/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * A binding transition the contract forbids (plan §6.1): org X to org Y, org to
 * platform outside the allow-listed callers, or, in single mode, any org other
 * than {@link OrgId#DEFAULT}. Like {@link OrgContextMissingException}, it is a
 * defect and maps to a 500.
 */
public class OrgBindingException extends IllegalStateException {

    public OrgBindingException(String message) {
        super("[SECURITY] " + message);
    }
}
