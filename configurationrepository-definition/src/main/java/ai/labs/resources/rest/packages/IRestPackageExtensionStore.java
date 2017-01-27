package ai.labs.resources.rest.packages;

import ai.labs.resources.rest.packages.model.PackageConfiguration;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Path("/packagestore/extensions")
public interface IRestPackageExtensionStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<PackageConfiguration.PackageExtension> getBehaviorRuleExtensions();
}
