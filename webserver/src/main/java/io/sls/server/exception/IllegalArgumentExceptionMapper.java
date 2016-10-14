package io.sls.server.exception;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * User: jarisch
 * Date: 17.08.12
 * Time: 10:26
 */
@Provider
public class IllegalArgumentExceptionMapper implements ExceptionMapper<IllegalArgumentException> {
    @Override
    public Response toResponse(IllegalArgumentException exception) {
        Response.ResponseBuilder response = Response.status(Response.Status.BAD_REQUEST);
        response.entity(exception.getLocalizedMessage());
        return response.build();
    }
}
