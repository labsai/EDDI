/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.model.InputData;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The request's arrival is stamped by the router's first route and travels with
 * the turn's input, where the caller's budgets are measured from it.
 */
@DisplayName("RequestArrivalStamp and TurnDeadlineHeaderReader")
class RequestArrivalStampTest {

    @Test
    @DisplayName("the stamping route runs before every other route")
    void routeRunsFirst() {
        Router router = mock(Router.class);
        Route route = mock(Route.class);
        when(router.route()).thenReturn(route);
        when(route.order(Integer.MIN_VALUE)).thenReturn(route);

        new RequestArrivalStamp().stampArrivals(router);

        verify(route).order(Integer.MIN_VALUE);
        verify(route).handler(any());
    }

    @Test
    @DisplayName("a request is stamped with the current time and passed on")
    void stampsAndContinues() {
        RoutingContext routingContext = mock(RoutingContext.class);
        long before = System.currentTimeMillis();

        RequestArrivalStamp.stamp(routingContext);

        var order = inOrder(routingContext);
        order.verify(routingContext).put(eq(RequestArrivalStamp.ARRIVAL_EPOCH_MS), any(Long.class));
        order.verify(routingContext).next();
        var stamped = ArgumentCaptor.forClass(Object.class);
        verify(routingContext).put(eq(RequestArrivalStamp.ARRIVAL_EPOCH_MS), stamped.capture());
        long at = (Long) stamped.getValue();
        assertTrue(at >= before && at <= System.currentTimeMillis());
    }

    @Test
    @DisplayName("no routing context, or none stamped: no arrival")
    void missingArrival() {
        RoutingContext unstamped = mock(RoutingContext.class);
        assertNull(RequestArrivalStamp.arrivalEpochMs(null));
        assertNull(RequestArrivalStamp.arrivalEpochMs(unstamped));
    }

    @Test
    @DisplayName("the header reader puts the arrival and the requested deadline on the turn's input")
    void headerReaderCarriesTheArrival() {
        RoutingContext routingContext = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(routingContext.request()).thenReturn(request);
        when(request.getHeader(TurnDeadline.HEADER)).thenReturn("5000");
        when(routingContext.get(RequestArrivalStamp.ARRIVAL_EPOCH_MS)).thenReturn(1_234L);
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenReturn(routingContext);
        var inputData = new InputData("hi", Map.of());

        new TurnDeadlineHeaderReader(current).apply(inputData);

        assertEquals(1_234L, inputData.getRequestArrivalEpochMs());
        assertEquals(5_000L, inputData.getRequestedTurnDeadlineMs());
        assertEquals(1_234L, new TurnDeadlineHeaderReader(current).requestArrivalEpochMs());
    }

    @Test
    @DisplayName("no active request (an internal caller): neither is set")
    void noActiveRequest() {
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        when(current.getCurrent()).thenThrow(new IllegalStateException("no request"));
        var inputData = new InputData("hi", Map.of());

        new TurnDeadlineHeaderReader(current).apply(inputData);

        assertNull(inputData.getRequestArrivalEpochMs());
        assertNull(inputData.getRequestedTurnDeadlineMs());
        assertNull(new TurnDeadlineHeaderReader(current).requestArrivalEpochMs());
    }
}
