package ai.labs.resources.rest.extensions;

import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDefinition;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "extensionstore")
@Path("/extensionstore/extensions")
public interface IRestExtensionStore {
    String resourceURI = "eddi://ai.labs.extensions/extensionstore/extensions/";
    String versionQueryParam = "?version=";

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    List<DocumentDescriptor> readExtensionDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    Response readExtension(@PathParam("id") String id,
                           @ApiParam(name = "version", required = true, format = "integer", example = "1")
                           @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    URI updateExtension(@PathParam("id") String id,
                        @ApiParam(name = "version", required = true, format = "integer", example = "1")
                        @QueryParam("version") Integer version, ExtensionDefinition extension);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    Response createExtension(ExtensionDefinition extension);

    @DELETE
    @Path("/{id}")
    void deleteExtension(@PathParam("id") String id,
                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                         @QueryParam("version") Integer version);
}
