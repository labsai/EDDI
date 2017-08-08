package ai.labs.rest.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/health")
public interface IRestHealthCheck {
    @GET
    Response healthCheck();
}
