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
 * limits its callers. Like {@link OrgContextMissingException}, it is a defect:
 * see {@link OrgSecurityException} for why it is not an
 * {@code IllegalStateException} and how it surfaces.
 */
public class OrgBindingException extends OrgSecurityException {

    public OrgBindingException(String message) {
        super(message);
    }
}
