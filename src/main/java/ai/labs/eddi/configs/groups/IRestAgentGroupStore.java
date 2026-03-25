package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * JAX-RS interface for group configuration CRUD.
 *
 * @author ginccc
 */
@Path("/groupstore/groups")
@Tag(name = "10. Groups", description = "agent group configuration for multi-agent debates")
public interface IRestAgentGroupStore extends IRestVersionInfo {
    String resourceBaseType = "eddi://ai.labs.group";
    String resourceURI = resourceBaseType + "/groupstore/groups/";

    @GET
    @Path("/jsonSchema")
    @Produces(MediaType.APPLICATION_JSON)
    @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
    @Operation(description = "Read JSON Schema for group definition.")
    Response readJsonSchema();

    @GET
    @Path("descriptors")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read list of group descriptors.")
    List<DocumentDescriptor> readGroupDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
            @QueryParam("index") @DefaultValue("0") Integer index, @QueryParam("limit") @DefaultValue("20") Integer limit);

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read group configuration.")
    AgentGroupConfiguration readGroup(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Update group configuration.")
    Response updateGroup(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            AgentGroupConfiguration groupConfiguration);

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Create group configuration.")
    Response createGroup(AgentGroupConfiguration groupConfiguration);

    @POST
    @Path("/{id}")
    @Operation(description = "Duplicate this group configuration.")
    Response duplicateGroup(@PathParam("id") String id, @QueryParam("version") Integer version);

    @DELETE
    @Path("/{id}")
    @Operation(description = "Delete group configuration.")
    Response deleteGroup(@PathParam("id") String id,
            @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
            @QueryParam("permanent") @DefaultValue("false") Boolean permanent);
}
