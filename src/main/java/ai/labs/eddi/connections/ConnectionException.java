/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

/**
 * A connection could not be resolved, and the call must not proceed.
 * <p>
 * Every failure here is a refusal rather than a degradation. The alternatives —
 * sending no credential, or falling back to the service grant when the user's
 * is missing — are both worse than failing: the first produces an opaque 401
 * far from its cause, and the second sends the <em>wrong authority</em>, which
 * is how one user reads another's data. {@code CallerIdentityResolver} made the
 * same choice for {@code ${caller:token}} and it is the reason that feature is
 * safe.
 * <p>
 * The message is written for the agent designer or the end user who has to act
 * on it, and never quotes a credential.
 */
public class ConnectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** What went wrong, as a bounded categorical suitable for a metric tag. */
    public enum Reason {
        /** No connection with that name in this tenant. */
        NOT_FOUND,
        /** The connection exists but its document cannot be honoured. */
        INVALID_CONFIGURATION,
        /** {@code PER_USER} with no verified principal — see the class comment. */
        NO_VERIFIED_PRINCIPAL,
        /** {@code PER_USER} and this user has not connected yet. */
        NOT_CONNECTED,
        /**
         * {@code CALLER_SUPPLIED} and this request carried no credential for the
         * connection. Distinct from {@link #NOT_CONNECTED}, which says a stored grant
         * is missing and is fixed by sending the user through a consent screen; this
         * one says the calling system did not attach a credential it was expected to,
         * and is fixed in that system.
         */
        NO_CALLER_CREDENTIAL,
        /** The stored grant is revoked or its refresh token is dead. */
        GRANT_UNUSABLE,
        /** The target origin is not on the connection's allowlist. */
        TARGET_NOT_ALLOWED,
        /** The token endpoint failed transiently; retrying is reasonable. */
        TOKEN_ENDPOINT_UNAVAILABLE,
        /** The reference appeared somewhere a credential may not be placed. */
        UNSUPPORTED_PLACEMENT
    }

    private final Reason reason;

    public ConnectionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ConnectionException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
