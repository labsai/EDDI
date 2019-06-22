package ai.labs.resources.rest.config.packages;

import ai.labs.resources.rest.config.packages.model.PackageConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (3) Packages", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/packagestore/extensions")
public interface IRestPackageExtensionStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<PackageConfiguration.PackageExtension> getBehaviorRuleExtensions();
}
