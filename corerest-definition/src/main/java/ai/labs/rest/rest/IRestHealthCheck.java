package ai.labs.rest.rest;

import io.swagger.annotations.ApiOperation;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("health")
public interface IRestHealthCheck {
    @GET
    @ApiOperation(value = "Server health check.")
    Response checkHealth();
}
