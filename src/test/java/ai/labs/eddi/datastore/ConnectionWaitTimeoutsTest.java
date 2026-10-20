/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConnectionWaitTimeouts")
class ConnectionWaitTimeoutsTest {

    @Test
    @DisplayName("a MongoDB pool wait timeout, directly or as a cause, is recognised")
    void mongoTimeout() {
        var timeout = new MongoTimeoutException("Timed out after 10000 ms while waiting for a connection to server mongodb:27017.");
        assertTrue(ConnectionWaitTimeouts.isConnectionWaitTimeout(timeout));
        assertTrue(ConnectionWaitTimeouts.isConnectionWaitTimeout(
                new IResourceStore.ResourceStoreException("load failed", new IllegalStateException("wrapped", timeout))));
    }

    @Test
    @DisplayName("Agroal's acquisition timeout is recognised by its message")
    void agroalTimeout() {
        assertTrue(ConnectionWaitTimeouts.isConnectionWaitTimeout(new RuntimeException(new SQLException("Sorry, acquisition timeout!"))));
    }

    @Test
    @DisplayName("other failures are not")
    void otherFailures() {
        assertFalse(ConnectionWaitTimeouts.isConnectionWaitTimeout(null));
        assertFalse(ConnectionWaitTimeouts.isConnectionWaitTimeout(new IllegalStateException("boom")));
        assertFalse(ConnectionWaitTimeouts.isConnectionWaitTimeout(new SQLException("duplicate key")));
        assertFalse(ConnectionWaitTimeouts.isConnectionWaitTimeout(new SQLException((String) null)));
    }
}
