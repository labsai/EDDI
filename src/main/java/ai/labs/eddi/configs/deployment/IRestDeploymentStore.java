package ai.labs.eddi.configs.deployment;

import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Bot Administration", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/deploymentstore/deployments")
@Tag(name = "08. Bot Administration", description = "Deploy & Undeploy Bots")
public interface IRestDeploymentStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read deployment infos.")
    List<DeploymentInfo> readDeploymentInfos();
}
