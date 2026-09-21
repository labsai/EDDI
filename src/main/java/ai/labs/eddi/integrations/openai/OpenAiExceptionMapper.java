/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Renders {@link OpenAiApiException} as the OpenAI error envelope.
 * <p>
 * Scoped to this one exception type so it cannot alter error handling for the
 * rest of EDDI's REST surface — a broader mapper registered as a
 * {@code @Provider} would apply application-wide.
 *
 * @since 6.1.0
 */
@Provider
public class OpenAiExceptionMapper implements ExceptionMapper<OpenAiApiException> {

    @Override
    public Response toResponse(OpenAiApiException exception) {
        return Response.status(exception.getStatus())
                .entity(exception.toErrorResponse())
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
