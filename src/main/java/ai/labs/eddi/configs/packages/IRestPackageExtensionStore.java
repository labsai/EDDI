package ai.labs.eddi.configs.packages;

import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (3) Packages", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/extensionstore/extensions")
@Tag(name = "06. Packages", description = "packages for bots")
public interface IRestPackageExtensionStore {
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ExtensionDescriptor> getPackageExtensions(@QueryParam("filter") @DefaultValue("") String filter);
}
