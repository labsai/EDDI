package ai.labs.eddi.engine.exception;

import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class ResourceStoreExceptionMapper implements ExceptionMapper<IResourceStore.ResourceStoreException> {
    private static final Logger log = Logger.getLogger(ResourceStoreExceptionMapper.class);

    @Override
    public Response toResponse(IResourceStore.ResourceStoreException exception) {
        log.error(exception.getLocalizedMessage(), exception);
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).type(MediaType.TEXT_PLAIN).entity(exception.getLocalizedMessage()).build();
    }
}
