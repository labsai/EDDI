package ai.labs.resources.rest.config.http;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (3) HttpCalls", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/httpcallsstore/httpcalls")
public interface IRestHttpCallsStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for regular httpCalls definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of httpCalls descriptors.")
    List<DocumentDescriptor> readHttpCallsDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                      @QueryParam("index") @DefaultValue("0") Integer index,
                                                      @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read httpCalls.")
    HttpCallsConfiguration readHttpCalls(@PathParam("id") String id,
                                         @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                         @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update httpCalls.")
    Response updateHttpCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version, HttpCallsConfiguration httpCallsConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create httpCalls.")
    Response createHttpCalls(HttpCallsConfiguration httpCallsConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this httpCalls.")
    Response duplicateHttpCalls(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete httpCalls.")
    Response deleteHttpCalls(@PathParam("id") String id,
                             @ApiParam(name = "version", required = true, format = "integer", example = "1")
                             @QueryParam("version") Integer version);
}
