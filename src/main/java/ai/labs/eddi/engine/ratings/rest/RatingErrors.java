/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.ratings.rest;

import ai.labs.eddi.engine.exception.ErrorResponses;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The client errors of the rating endpoints, in EDDI's usual JSON error body
 * ({@code {"error": "<code>", "message": "…"}}, see {@link ErrorResponses})
 * with a stable code per cause, so a client can tell "ratings are off" from
 * "too late" from "bad score" without parsing the message.
 */
public final class RatingErrors {

    /** The agent version the conversation runs on does not accept this rating. */
    public static final String RATING_DISABLED = "RATING_DISABLED";
    /** The conversation's rating window has closed. */
    public static final String RATING_WINDOW_CLOSED = "RATING_WINDOW_CLOSED";
    /** The step does not exist or holds no agent output. */
    public static final String INVALID_STEP = "INVALID_STEP";
    /** The score does not fit the scale. */
    public static final String INVALID_SCORE = "INVALID_SCORE";
    /** A tag is not in the agent's allow-list. */
    public static final String INVALID_TAG = "INVALID_TAG";
    /** The agent does not accept comments. */
    public static final String COMMENT_NOT_ALLOWED = "COMMENT_NOT_ALLOWED";
    /** The comment is longer than the agent allows. */
    public static final String COMMENT_TOO_LONG = "COMMENT_TOO_LONG";
    /** Only the person who had the conversation may rate it. */
    public static final String NOT_CONVERSATION_OWNER = "NOT_CONVERSATION_OWNER";
    /** The conversation does not exist (any more). */
    public static final String CONVERSATION_NOT_FOUND = "CONVERSATION_NOT_FOUND";
    /** A non-administrator listed ratings without naming an agent. */
    public static final String AGENT_ID_REQUIRED = "AGENT_ID_REQUIRED";
    /** No rating has this id. */
    public static final String RATING_NOT_FOUND = "RATING_NOT_FOUND";
    /** The person re-rated or withdrew the rating while it was being reviewed. */
    public static final String RATING_CHANGED = "RATING_CHANGED";
    /** The review note is longer than allowed. */
    public static final String NOTE_TOO_LONG = "NOTE_TOO_LONG";
    /** The caller may not act on this rating. */
    public static final String NOT_PERMITTED = "NOT_PERMITTED";
    /** A query parameter could not be read. */
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";

    private RatingErrors() {
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
