package ai.labs.eddi.configs.extensions;

import ai.labs.eddi.configs.extensions.model.ExtensionDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (1) General", authorizations = {@Authorization(value = "eddi_auth")})
@Deprecated
@Path("/extensionstore/extensions")
public interface IRestExtensionStore {
    String resourceURI = "eddi://ai.labs.extensions/extensionstore/extensions/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read extension descriptors. (deprecated. use /packagestore/extensions instead.)")
    List<ExtensionDescriptor> readExtensionDescriptors(@QueryParam("filter") @DefaultValue("") String filter);
}
