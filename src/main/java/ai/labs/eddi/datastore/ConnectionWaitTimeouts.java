/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import com.mongodb.MongoTimeoutException;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Recognises the failure "the database connection pool had no connection to
 * give within its wait limit", which means the node is overloaded rather than
 * broken: the caller should retry shortly or elsewhere ({@code 503} with
 * {@code Retry-After}), not be told of an internal error.
 * <p>
 * MongoDB reports it as a {@link MongoTimeoutException} (after
 * {@code maxWaitTime}; the same type also means no server could be selected in
 * time, which is just as much a reason to retry later), Agroal, PostgreSQL's
 * pool, as an {@link SQLException} whose message names the acquisition timeout.
 */
public final class ConnectionWaitTimeouts {

    /** Agroal's message; it has no dedicated exception type. */
    static final String AGROAL_ACQUISITION_TIMEOUT = "acquisition timeout";

    private static final int MAX_CAUSE_DEPTH = 10;

    private ConnectionWaitTimeouts() {
    }

    /**
     * Whether {@code failure}, or one of its causes, is a connection-pool wait
     * timeout.
     */
    public static boolean isConnectionWaitTimeout(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MongoTimeoutException) {
                return true;
            }
            if (current instanceof SQLException && current.getMessage() != null
                    && current.getMessage().toLowerCase(Locale.ROOT).contains(AGROAL_ACQUISITION_TIMEOUT)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
