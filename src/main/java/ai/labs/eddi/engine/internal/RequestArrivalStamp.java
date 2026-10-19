/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records when each HTTP request reached the server, so a caller's time budget
 * can be measured from there.
 * <p>
 * The stamp is taken by the first route of the router, on the Vert.x event loop
 * that parsed the request, before the request waits for any thread. Measured
 * from the resource method instead, a request that queued for a thread had
 * already used part of its caller's patience without anything noticing: in a
 * 5,000-user burst against a node held to two CPUs, requests waited up to 63 s
 * for a worker thread before the 60 s turn timeout was even armed.
 */
@ApplicationScoped
public class RequestArrivalStamp {

    /** Key of the arrival time (epoch milliseconds) in the routing context. */
    static final String ARRIVAL_EPOCH_MS = "eddi.request.arrivalEpochMs";

    void stampArrivals(@Observes Router router) {
        // Integer.MIN_VALUE: Vert.x runs routes in ascending order, so this runs
        // before every other route, the CSP filters' negative orders included.
        router.route().order(Integer.MIN_VALUE).handler(RequestArrivalStamp::stamp);
    }

    static void stamp(RoutingContext routingContext) {
        routingContext.put(ARRIVAL_EPOCH_MS, System.currentTimeMillis());
        routingContext.next();
    }

    /**
     * The arrival time of the request {@code routingContext} belongs to, or
     * {@code null} when it carries none (a request that bypassed the router).
     */
    static Long arrivalEpochMs(RoutingContext routingContext) {
        if (routingContext == null) {
            return null;
        }
        Object arrival = routingContext.get(ARRIVAL_EPOCH_MS);
        return arrival instanceof Long epochMs ? epochMs : null;
    }
}
