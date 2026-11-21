/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.exception;

import ai.labs.eddi.secrets.SecretResolver;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A vault grant refusal that escapes a resource (#1179): 403 with the message,
 * which names the secret, the asker and the grant call that fixes it, instead
 * of a bare 500.
 * <p>
 * A {@link SecretResolver.SecretMissingException} is 400: the configuration
 * names a secret that does not exist, which no grant change can fix.
 * <p>
 * Copying the message is safe: it is built by {@link SecretResolver} from the
 * reference and the asker, never from a value.
 */
@Provider
public class SecretNotGrantedExceptionMapper implements ExceptionMapper<SecretResolver.SecretNotGrantedException> {

    @Override
    public Response toResponse(SecretResolver.SecretNotGrantedException exception) {
        return Response.status(statusOf(exception)).type(MediaType.TEXT_PLAIN).entity(exception.getMessage()).build();
    }

    /** 400 for a secret that does not exist, 403 for one the grant withholds. */
    public static Response.Status statusOf(SecretResolver.SecretNotGrantedException exception) {
        return exception instanceof SecretResolver.SecretMissingException ? Response.Status.BAD_REQUEST : Response.Status.FORBIDDEN;
    }
}
