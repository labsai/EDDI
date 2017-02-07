package ai.labs.server.exception;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * @author ginccc
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
