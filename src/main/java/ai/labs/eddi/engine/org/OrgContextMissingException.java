/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * Org data was touched without an org binding, or under a binding that may not
 * touch it. A missed propagation is an error, never a silent wrong org (plan
 * §4). It maps to a 500: it is a defect in EDDI, not a caller mistake.
 */
public class OrgContextMissingException extends OrgSecurityException {

    public OrgContextMissingException(String message) {
        super(message);
    }
}
