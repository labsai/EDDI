package ai.labs.resources.rest.extensions;

import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (1) General", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/extensionstore/extensions")
public interface IRestExtensionStore {
    String resourceURI = "eddi://ai.labs.extensions/extensionstore/extensions/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read extension descriptors.")
    List<ExtensionDescriptor> readExtensionDescriptors(@QueryParam("filter") @DefaultValue("") String filter);
}
