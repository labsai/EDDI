package ai.labs.resources.rest.deployment;

import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Bot Administration", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/deploymentstore/deployments")
public interface IRestDeploymentStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read deployment infos.")
    List<DeploymentInfo> readDeploymentInfos();
}
