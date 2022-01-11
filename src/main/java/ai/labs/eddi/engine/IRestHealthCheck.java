package ai.labs.eddi.engine;

import org.eclipse.microprofile.openapi.annotations.Operation;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
// @Api(value = "Infrastructure Endpoints", authorizations = {@Authorization(value = "eddi_auth")})
@Path("health")
public interface IRestHealthCheck {
    @GET
    @Operation(description = "Server health check.")
    Response checkHealth();
}
