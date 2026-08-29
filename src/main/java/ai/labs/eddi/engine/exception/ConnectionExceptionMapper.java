/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.connections.ConnectionException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Turns a connection refusal into the status that describes it, message
 * included.
 * <p>
 * Without this every {@link ConnectionException} escaping a resource is a bare
 * 500 with an empty body, and the text that says exactly what to fix — "add
 * this origin to eddi.connections.credential-endpoint-allowlist", "connect your
 * account first" — reaches the server log only. The caller who has to act on it
 * is the one person who never sees it.
 * <p>
 * Copying the message is safe here in a way it is not for a store failure:
 * {@code ConnectionException} messages are written for the agent designer or
 * end user who has to act on them and deliberately never quote a credential.
 */
@Provider
public class ConnectionExceptionMapper implements ExceptionMapper<ConnectionException> {

    @Override
    public Response toResponse(ConnectionException exception) {
        return Response.status(statusOf(exception.getReason())).type(MediaType.TEXT_PLAIN).entity(exception.getLocalizedMessage()).build();
    }

    /**
     * The reason, as a status.
     * <p>
     * The three "you have not linked an account" reasons are 409 rather than 401 or
     * 403: the caller is authenticated and permitted, the resource simply is not in
     * a state that can serve the request yet, and the action that fixes it is
     * connecting or reconnecting rather than presenting a different token.
     * <p>
     * {@code NO_CALLER_CREDENTIAL} is 400 instead, and the difference is the point:
     * those three are fixed by a human linking an account, while this one says the
     * request itself was incomplete — a header the calling system was supposed to
     * attach is missing, and the same call with the same account will keep failing
     * until that system sends it. A 409 would tell an integrator to go looking for
     * an unconnected user who does not exist.
     */
    private static Response.Status statusOf(ConnectionException.Reason reason) {
        if (reason == null) {
            return Response.Status.INTERNAL_SERVER_ERROR;
        }
        return switch (reason) {
            case INVALID_CONFIGURATION, UNSUPPORTED_PLACEMENT, TARGET_NOT_ALLOWED, NO_CALLER_CREDENTIAL -> Response.Status.BAD_REQUEST;
            case NOT_FOUND -> Response.Status.NOT_FOUND;
            case NOT_CONNECTED, NO_VERIFIED_PRINCIPAL, GRANT_UNUSABLE -> Response.Status.CONFLICT;
            case TOKEN_ENDPOINT_UNAVAILABLE -> Response.Status.SERVICE_UNAVAILABLE;
        };
    }
}
