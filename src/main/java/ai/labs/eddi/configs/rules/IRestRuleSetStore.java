package ai.labs.eddi.configs.rules;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
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
@Path("/rulestore/rulesets")
@Tag(name = "Behavior Rules")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestRuleSetStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.rules";
    String resourceURI = resourceBaseType + "/rulestore/rulesets/";
    String versionQueryParam = "?version=";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(operationId = "readRuleSetJsonSchema", description = "Read JSON Schema for behavior definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    /*
     * @Parameters({
     *
     * @Parameter(name = "filter", paramType = "query", dataType = "string", example
     * = "<name_of_behavior>"),
     *
     * @ApiImplicitParam(name = "index", paramType = "query", dataType = "integer",
     * example = "0"),
     *
     * @ApiImplicitParam(name = "limit", paramType = "query", dataType = "integer",
     * example = "20")})
     */
    @APIResponse(responseCode = "200", description = "Array of DocumentDescriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of behavior descriptors.")
    List<DocumentDescriptor> readBehaviorDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index, @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read behavior rule set.")
    RuleSetConfiguration readRuleSet(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update behavior rule set.")
    Response updateRuleSet(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            RuleSetConfiguration behaviorConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create behavior rule set.")
    Response createRuleSet(RuleSetConfiguration behaviorConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this behavior rule set.")
    Response duplicateRuleSet(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete behavior rule set.")
    Response deleteRuleSet(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("permanent") @DefaultValue("false") Boolean permanent);
}
