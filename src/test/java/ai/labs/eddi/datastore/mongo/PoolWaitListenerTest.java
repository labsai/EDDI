/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.mongo;

import com.mongodb.ServerAddress;
import com.mongodb.connection.ClusterId;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerId;
import com.mongodb.event.ConnectionCheckOutFailedEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PoolWaitListenerTest {

    @Test
    @DisplayName("records every checkout's wait, and counts only the checkouts that timed out")
    void recordsWaitsAndTimeouts() {
        var meterRegistry = new SimpleMeterRegistry();
        var listener = new PoolWaitListener(meterRegistry);
        var serverId = new ServerId(new ClusterId(), new ServerAddress("localhost"));

        listener.connectionCheckedOut(new ConnectionCheckedOutEvent(new ConnectionId(serverId), 1, TimeUnit.MILLISECONDS.toNanos(7)));
        listener.connectionCheckedOut(new ConnectionCheckedOutEvent(new ConnectionId(serverId), 2, TimeUnit.MILLISECONDS.toNanos(13)));
        listener.connectionCheckOutFailed(new ConnectionCheckOutFailedEvent(serverId, 3, ConnectionCheckOutFailedEvent.Reason.TIMEOUT, 0));
        listener.connectionCheckOutFailed(new ConnectionCheckOutFailedEvent(serverId, 4, ConnectionCheckOutFailedEvent.Reason.POOL_CLOSED, 0));

        Timer timer = meterRegistry.get("eddi.datastore.pool.wait").timer();
        assertEquals(2, timer.count());
        assertEquals(20.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
        assertEquals(1.0, meterRegistry.get("eddi.datastore.pool.wait.timeouts").counter().count());
    }
}
