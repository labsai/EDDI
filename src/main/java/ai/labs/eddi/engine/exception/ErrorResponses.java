/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.integrations.openai.OpenAiApiException;
import ai.labs.eddi.integrations.openai.model.OpenAiErrorResponse;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON error body shared by the client-error mappers: {@code {"error":
 * "<code>", "message": "<what was wrong>"}}.
 * <p>
 * The same two keys the quota, capacity and GDPR-restriction mappers already
 * use, so a client reads every 4xx the same way ({@code message} first — the
 * Manager's {@code extractErrorMessage} does exactly that). The message is
 * written for whoever sent the request: it names the parameter or JSON path and
 * what belongs there, never a Java class or a stack trace.
 */
public final class ErrorResponses {

    /** Error code of a malformed request. */
    public static final String BAD_REQUEST = "bad_request";

    private ErrorResponses() {
    }

    /** A 400 with a JSON body carrying {@code message}. */
    public static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body(BAD_REQUEST, message))
                .build();
    }

    /**
     * A 400 for the request {@code uriInfo} describes. The OpenAI-compatible
     * surface ({@code /v1/…}) keeps its own envelope, {@code {"error": {"message",
     * "type", "code"}}}: SDKs read {@code error.message}, and a flat EDDI body
     * would surface to them as an unexplained failure. Everything else, and a
     * {@code null} {@code uriInfo}, gets the EDDI body.
     */
    public static Response badRequest(String message, UriInfo uriInfo) {
        if (isOpenAiPath(uriInfo)) {
            OpenAiApiException openAi = OpenAiApiException.badRequest(OpenAiErrorResponse.CODE_INVALID_REQUEST_BODY,
                    message == null || message.isBlank() ? "The request is malformed." : message);
            return Response.status(openAi.getStatus()).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(openAi.toErrorResponse()).build();
        }
        return badRequest(message);
    }

    private static boolean isOpenAiPath(UriInfo uriInfo) {
        if (uriInfo == null) {
            return false;
        }
        String path = uriInfo.getPath();
        if (path == null) {
            return false;
        }
        path = path.startsWith("/") ? path.substring(1) : path;
        return path.equals("v1") || path.startsWith("v1/");
    }

    /** The error body itself, for callers that build their own response. */
    public static Map<String, String> body(String error, String message) {
        var body = new LinkedHashMap<String, String>();
        body.put("error", error);
        body.put("message", message == null || message.isBlank() ? "The request is malformed." : message);
        return body;
    }
}
