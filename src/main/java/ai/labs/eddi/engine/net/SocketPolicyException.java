/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.net;

import java.io.IOException;

/**
 * A socket {@link SafeSocketConnector} refuses to open.
 * <p>
 * An {@link IOException} so that it surfaces unchanged through a mail library
 * that calls the connector's socket factory. The message names the host the
 * author configured, never a resolved address: which internal address a name
 * points at is exactly what a probing author must not learn.
 */
public class SocketPolicyException extends IOException {

    /** Why the socket was refused — a bounded category, fit for a metric tag. */
    public enum Reason {
        /** Not a syntactically valid DNS host name (IP literals included). */
        INVALID_HOST,
        /** Valid, but not on {@code eddi.email.allowed-hosts}. */
        HOST_NOT_ALLOWED,
        /** Not one of the protocol's permitted ports. */
        PORT_NOT_ALLOWED,
        /**
         * The host is, or resolves to, a private, loopback, link-local or metadata
         * address.
         */
        PRIVATE_ADDRESS,
        /** The host does not resolve. */
        UNRESOLVABLE,
        /**
         * A socket was asked to connect somewhere other than the endpoint it was vetted
         * for.
         */
        ENDPOINT_MISMATCH
    }

    private final Reason reason;

    public SocketPolicyException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
