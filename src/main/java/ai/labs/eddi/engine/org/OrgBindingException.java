/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * A binding transition the contract forbids (plan §6.1):
 * <ul>
 * <li>org X to org Y;</li>
 * <li>in single mode, any org other than {@link OrgId#DEFAULT};</li>
 * <li>an {@link OrgPropagation} hand-off that would move an org-bound thread to
 * another org or to the platform plane.</li>
 * </ul>
 * Entering {@link OrgScope#platform} is not refused at runtime; the build guard
 * limits its callers. Like {@link OrgContextMissingException}, this is a
 * defect: no EDDI exception mapper handles {@code IllegalStateException}, so it
 * surfaces as a generic 500 and its message is not returned to the client.
 */
public class OrgBindingException extends IllegalStateException {

    public OrgBindingException(String message) {
        super("[SECURITY] " + message);
    }
}
