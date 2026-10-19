/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.engine.model.InputData;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Reads the caller's {@value TurnDeadline#HEADER} header off the active HTTP
 * request and stamps it on the turn's {@link InputData}, together with the time
 * the request arrived ({@link RequestArrivalStamp}).
 * <p>
 * It is a separate bean so the REST resources do not take another constructor
 * argument, and so it can be read on the request thread — the turn itself runs
 * later on a coordinator thread, where the request is gone.
 */
@ApplicationScoped
public class TurnDeadlineHeaderReader {
    private static final Logger LOGGER = Logger.getLogger(TurnDeadlineHeaderReader.class);

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public TurnDeadlineHeaderReader(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    /**
     * Stamps the requested budget and the request's arrival on {@code inputData}. A
     * missing, malformed or non-positive header leaves the budget unset, and a
     * caller with no active request leaves both unset; this never fails the
     * request.
     */
    public void apply(InputData inputData) {
        if (inputData == null) {
            return;
        }
        RoutingContext current = currentRequest();
        inputData.setRequestedTurnDeadlineMs(TurnDeadline.parseHeader(current == null ? null : current.request().getHeader(TurnDeadline.HEADER)));
        inputData.setRequestArrivalEpochMs(RequestArrivalStamp.arrivalEpochMs(current));
    }

    private RoutingContext currentRequest() {
        try {
            return currentVertxRequest.getCurrent();
        } catch (RuntimeException e) {
            // No active request (a scheduled or internal caller): no header, no arrival.
            LOGGER.debugf("No active request to read %s from: %s", TurnDeadline.HEADER, e.getMessage());
            return null;
        }
    }
}
