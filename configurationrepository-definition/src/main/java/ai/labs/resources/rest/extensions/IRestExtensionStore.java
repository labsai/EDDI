package ai.labs.resources.rest.extensions;

import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import io.swagger.annotations.Api;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "configurations")
@Path("/extensionstore/extensions")
public interface IRestExtensionStore {
    String resourceURI = "eddi://ai.labs.extensions/extensionstore/extensions/";

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    List<ExtensionDescriptor> readExtensionDescriptors(@QueryParam("filter") @DefaultValue("") String filter);
}
