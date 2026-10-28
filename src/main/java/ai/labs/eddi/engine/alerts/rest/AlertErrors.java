/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.rest;

import ai.labs.eddi.engine.exception.ErrorResponses;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The client errors of the alert endpoints, in EDDI's JSON error body
 * ({@code {"error": "<code>", "message": "…"}}) with a stable code per cause.
 */
public final class AlertErrors {

    /** A non-administrator listed or counted without naming an agent. */
    public static final String AGENT_ID_REQUIRED = "AGENT_ID_REQUIRED";
    /** The rule does not pass save-time validation; the message names the field. */
    public static final String INVALID_RULE = "INVALID_RULE";
    /** A rule over every agent, or several, is an administrator's. */
    public static final String ADMIN_SCOPE_REQUIRED = "ADMIN_SCOPE_REQUIRED";
    /** Webhook targets are an administrator's. */
    public static final String WEBHOOK_ADMIN_ONLY = "WEBHOOK_ADMIN_ONLY";
    public static final String RULE_NOT_FOUND = "RULE_NOT_FOUND";
    public static final String EVENT_NOT_FOUND = "EVENT_NOT_FOUND";
    /**
     * The caller may not act on this event (an agent-less digest is an
     * administrator's).
     */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";
    /** A query parameter could not be read. */
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    /** Nothing to retry: no delivery of the event has failed. */
    public static final String NOTHING_TO_RETRY = "NOTHING_TO_RETRY";

    private AlertErrors() {
    }

    public static BadRequestException badRequest(String code, String message) {
        return new BadRequestException(message, response(Response.Status.BAD_REQUEST, code, message));
    }

    public static ClientErrorException conflict(String code, String message) {
        return new ClientErrorException(message, response(Response.Status.CONFLICT, code, message));
    }

    public static ForbiddenException forbidden(String code, String message) {
        return new ForbiddenException(message, response(Response.Status.FORBIDDEN, code, message));
    }

    public static NotFoundException notFound(String code, String message) {
        return new NotFoundException(message, response(Response.Status.NOT_FOUND, code, message));
    }

    private static Response response(Response.Status status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(ErrorResponses.body(code, message)).build();
    }
}
