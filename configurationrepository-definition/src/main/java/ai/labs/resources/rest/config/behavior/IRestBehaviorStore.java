package ai.labs.resources.rest.config.behavior;

import ai.labs.models.DocumentDescriptor;
import ai.labs.resources.rest.IRestVersionInfo;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
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
@Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (2) Behavior Rules", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/behaviorstore/behaviorsets")
public interface IRestBehaviorStore extends IRestVersionInfo {
    String resourceURI = "eddi://ai.labs.behavior/behaviorstore/behaviorsets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, response = Map.class, message = "JSON Schema (for validation).")
    @ApiOperation(value = "Read JSON Schema for behavior definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "filter", paramType = "query", dataType = "string", format = "string", example = "<name_of_behavior>"),
            @ApiImplicitParam(name = "index", paramType = "query", dataType = "integer", format = "integer", example = "0"),
            @ApiImplicitParam(name = "limit", paramType = "query", dataType = "integer", format = "integer", example = "20")})
    @ApiResponse(code = 200, response = DocumentDescriptor.class, responseContainer = "List",
            message = "Array of DocumentDescriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read list of behavior descriptors.")
    List<DocumentDescriptor> readBehaviorDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read behavior rule set.")
    BehaviorConfiguration readBehaviorRuleSet(@PathParam("id") String id,
                                              @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                              @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update behavior rule set.")
    Response updateBehaviorRuleSet(@PathParam("id") String id,
                                   @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                   @QueryParam("version") Integer version, BehaviorConfiguration behaviorConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create behavior rule set.")
    Response createBehaviorRuleSet(BehaviorConfiguration behaviorConfiguration);

    @POST
    @Path("/{id}")
    @ApiOperation(value = "Duplicate this behavior rule set.")
    Response duplicateBehaviorRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @ApiOperation(value = "Delete behavior rule set.")
    Response deleteBehaviorRuleSet(@PathParam("id") String id,
                                   @ApiParam(name = "version", required = true, format = "integer", example = "1")
                                   @QueryParam("version") Integer version);
}
