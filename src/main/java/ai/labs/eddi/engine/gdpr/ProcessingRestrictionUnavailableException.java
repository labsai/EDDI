/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

/**
 * Thrown when the GDPR Art. 18 restriction flag could not be read at all —
 * store failover, pool exhaustion, timeout.
 * <p>
 * This is still fail-closed: the turn does not proceed, because a user whose
 * processing <em>is</em> restricted must not be processed just because the
 * lookup failed. What changes is what the caller is told. The check used to
 * return {@code true} on any exception, so a transient store error turned every
 * turn for every user into {@code 403 processing_restricted} with the body
 * "Processing is restricted for this user (GDPR Art. 18)" — a legal statement
 * about the user that was simply false, and a status code that hid an outage
 * from any monitoring keyed on 5xx. A distinct exception mapped to 503 says the
 * honest thing: the service cannot answer right now, try again.
 *
 * @author ginccc
 * @since 6.0.0
 */
public class ProcessingRestrictionUnavailableException extends RuntimeException {

    public ProcessingRestrictionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
