package ai.labs.eddi.configs.behavior;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.models.DocumentDescriptor;
import io.swagger.v3.oas.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (2) Conversation LifeCycle Tasks -> (2) Behavior Rules", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/behaviorstore/behaviorsets")
@Tag(name = "02. Behavior Rules", description = "lifecycle extension for package")
public interface IRestBehaviorStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.behavior";
    String resourceURI = resourceBaseType + "/behaviorstore/behaviorsets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for behavior definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    /*@Parameters({
            @Parameter(name = "filter", paramType = "query", dataType = "string", example = "<name_of_behavior>"),
            @ApiImplicitParam(name = "index", paramType = "query", dataType = "integer", example = "0"),
            @ApiImplicitParam(name = "limit", paramType = "query", dataType = "integer", example = "20")})*/
    @APIResponse(responseCode = "200", description = "Array of DocumentDescriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of behavior descriptors.")
    List<DocumentDescriptor> readBehaviorDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                                                     @QueryParam("index") @DefaultValue("0") Integer index,
                                                     @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read behavior rule set.")
    BehaviorConfiguration readBehaviorRuleSet(@PathParam("id") String id,
                                              @Parameter(name = "version", required = true, example = "1")
                                              @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update behavior rule set.")
    Response updateBehaviorRuleSet(@PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version, BehaviorConfiguration behaviorConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create behavior rule set.")
    Response createBehaviorRuleSet(BehaviorConfiguration behaviorConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this behavior rule set.")
    Response duplicateBehaviorRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete behavior rule set.")
    Response deleteBehaviorRuleSet(@PathParam("id") String id,
                                   @Parameter(name = "version", required = true, example = "1")
                                   @QueryParam("version") Integer version);
}
