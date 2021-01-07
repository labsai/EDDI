package ai.labs.resources.rest.config.p2p;


import ai.labs.models.DocumentDescriptor;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.p2p.model.P2PConfiguration;
import io.swagger.annotations.*;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * @author rpi
 */
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (5) P2P", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/p2pstore/p2p")
public interface IRestP2PStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.p2p/p2pstore/p2p/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for regular p2p definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of p2p descriptors.")
    List<DocumentDescriptor> readP2PCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read p2p")
    P2PConfiguration readP2PCalls(@PathParam("id") String id,
                                  @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                       @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update p2p.")
    Response updateP2PCalls(@PathParam("id") String id,
                            @ApiParam(name = "version", required = true, format = "integer", example = "1")
                            @QueryParam("version") Integer version, P2PConfiguration p2PConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create p2p.")
    Response createP2PCalls(P2PConfiguration p2PConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this p2p.")
    Response duplicateP2PCalls(@PathParam("id") String id, @QueryParam("version") Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete p2p.")
    Response deleteP2PCalls(@PathParam("id") String id,
                            @ApiParam(name = "version", required = true, format = "integer", example = "1")
                            @QueryParam("version") Integer version);

}
