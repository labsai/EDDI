package ai.labs.rest.restinterfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Api(value = "Infrastructure Endpoints", authorizations = {@Authorization(value = "eddi_auth")})
@Path("health")
public interface IRestHealthCheck {
    @GET
    @ApiOperation(value = "Server health check.")
    Response checkHealth();
}
