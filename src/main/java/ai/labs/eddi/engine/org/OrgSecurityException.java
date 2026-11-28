/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

/**
 * The base of the org layer's fail-closed defects:
 * {@link OrgContextMissingException} and {@link OrgBindingException}.
 * <p>
 * It extends {@link RuntimeException} directly, deliberately not
 * {@code IllegalStateException}. EDDI has many
 * {@code catch (IllegalStateException e)} blocks that degrade gracefully (an
 * unsequenced audit entry, a skipped step). A missing or wrong org binding
 * caught there would turn a fail-closed refusal into a silent fallback. No code
 * may catch this type except to log and rethrow.
 * <p>
 * No EDDI exception mapper handles it, so on a request it surfaces as a generic
 * 500, and its message is not returned to the client.
 */
public abstract class OrgSecurityException extends RuntimeException {

    protected OrgSecurityException(String message) {
        super("[SECURITY] " + message);
    }
}
