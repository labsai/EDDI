package ai.labs.resources.rest.deployment;

import ai.labs.resources.rest.deployment.model.DeploymentInfo;
import io.swagger.annotations.Api;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "bot administration")
@Path("/deploymentstore/deployments")
public interface IRestDeploymentStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<DeploymentInfo> readDeploymentInfos();
}
