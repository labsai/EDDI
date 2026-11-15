/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.exception.ErrorResponses;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Error codes and responses of the oversight REST API ({@code /hitl/analytics},
 * {@code /hitl/samples}, {@code /hitl/autonomy}). Every refusal is
 * {@code {error, message}}.
 *
 * @since 6.7.0
 */
public final class OversightErrors {

    public static final String AGENT_ID_REQUIRED = "AGENT_ID_REQUIRED";
    public static final String NOT_PERMITTED = "NOT_PERMITTED";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String WINDOW_TOO_LONG = "WINDOW_TOO_LONG";
    public static final String SAMPLE_NOT_FOUND = "SAMPLE_NOT_FOUND";
    public static final String SAMPLE_ALREADY_REVIEWED = "SAMPLE_ALREADY_REVIEWED";
    public static final String UNKNOWN_REASON_CODE = "UNKNOWN_REASON_CODE";
    public static final String NOTE_TOO_LONG = "NOTE_TOO_LONG";
    public static final String NOTE_REQUIRED = "NOTE_REQUIRED";
    public static final String AGENT_ORIGINATED = "AGENT_ORIGINATED";
    public static final String STATE_NOT_FOUND = "STATE_NOT_FOUND";
    public static final String RECOMMENDATION_CHANGED = "RECOMMENDATION_CHANGED";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String AGENT_CHANGED = "AGENT_CHANGED";
    public static final String EVIDENCE_CHANGED = "EVIDENCE_CHANGED";
    public static final String NOT_SUSPENDED = "NOT_SUSPENDED";
    public static final String NOT_A_TOOL_CATEGORY = "NOT_A_TOOL_CATEGORY";
    public static final String INVALID_RESULT = "INVALID_RESULT";
    public static final String CONCURRENT_CHANGE = "CONCURRENT_CHANGE";
    public static final String REBUILD_RUNNING = "REBUILD_RUNNING";

    private OversightErrors() {
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

    /** The JSON response of a refusal. */
    public static Response response(Response.Status status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(ErrorResponses.body(code, message)).build();
    }
}
