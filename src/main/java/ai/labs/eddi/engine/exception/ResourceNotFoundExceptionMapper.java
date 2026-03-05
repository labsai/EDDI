package ai.labs.eddi.engine.exception;

import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ResourceNotFoundExceptionMapper implements ExceptionMapper<IResourceStore.ResourceNotFoundException> {
    @Override
    public Response toResponse(IResourceStore.ResourceNotFoundException exception) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.TEXT_PLAIN)
                .entity(exception.getLocalizedMessage())
                .build();
    }
}
