package ai.labs.eddi.configs.workflows;

import ai.labs.eddi.configs.IRestVersionInfo;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
// @Api(value = "Configurations -> (3) Workflows", authorizations =
// {@Authorization(value = "eddi_auth")})
@Path("/workflowstore/workflows")
@Tag(name = "06. Workflows", description = "workflow pipelines for agents")
public interface IRestWorkflowStore extends IRestVersionInfo {
        String resourceURI = "eddi://ai.labs.workflow/workflowstore/workflows/";

        @GET
        @Path("/jsonSchema")
        @Produces(MediaType.APPLICATION_JSON)
        @APIResponse(responseCode = "200", description = "JSON Schema (for validation).")
        @Operation(description = "Read JSON Schema for package definition.")
        Response readJsonSchema();

        @GET
        @Path("descriptors")
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read list of package descriptors.")
        List<DocumentDescriptor> readWorkflowDescriptors(@QueryParam("filter") @DefaultValue("") String filter,
                        @QueryParam("index") @DefaultValue("0") Integer index,
                        @QueryParam("limit") @DefaultValue("20") Integer limit);

        @POST
        @Path("descriptors")
        @Consumes(MediaType.TEXT_PLAIN)
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read list of package descriptors including a given resourceUri.")
        List<DocumentDescriptor> readWorkflowDescriptors(
                        @QueryParam("filter") @DefaultValue("") String filter,
                        @QueryParam("index") @DefaultValue("0") Integer index,
                        @QueryParam("limit") @DefaultValue("20") Integer limit,
                        @Parameter(name = "body", description = "eddi://ai.labs.TYPE/PATH/ID?version=VERSION") @DefaultValue("") String containingResourceUri,
                        @QueryParam("includePreviousVersions") @DefaultValue("false") Boolean includePreviousVersions);

        @GET
        @Path("/{id}")
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(description = "Read package.")
        WorkflowConfiguration readWorkflow(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version);

        @PUT
        @Path("/{id}")
        @Consumes(MediaType.APPLICATION_JSON)
        @Operation(description = "Update package.")
        Response updateWorkflow(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
                        WorkflowConfiguration workflowConfiguration);

        @PUT
        @Path("/{id}/updateResourceUri")
        @Consumes(MediaType.TEXT_PLAIN)
        @Operation(description = "Update references to other resources within this package resource.")
        Response updateResourceInWorkflow(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1") @QueryParam("version") Integer version,
                        URI resourceURI);

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Operation(description = "Create package.")
        Response createWorkflow(WorkflowConfiguration workflowConfiguration);

        @POST
        @Path("/{id}")
        @Operation(description = "Duplicate this package.")
        Response duplicateWorkflow(@PathParam("id") String id,
                        @QueryParam("version") Integer version,
                        @QueryParam("deepCopy") @DefaultValue("false") Boolean deepCopy);

        @DELETE
        @Path("/{id}")
        @Operation(summary = "Delete package", description = "Delete a workflow configuration. When cascade=true, also deletes extension "
                        + "resources referenced by this package: behavior sets, HTTP calls, output sets, "
                        + "LLM configs, property setters, and parser dictionaries. "
                        + "Shared resources (used by other packages) are skipped. "
                        + "Partial failures are logged but do not prevent the workflow from being deleted.")
        @APIResponse(responseCode = "200", description = "Workflow deleted successfully.")
        @APIResponse(responseCode = "404", description = "Workflow not found.")
        Response deleteWorkflow(@PathParam("id") String id,
                        @Parameter(name = "version", required = true, example = "1", description = "Version of the workflow to delete.") @QueryParam("version") Integer version,
                        @Parameter(description = "If true, permanently remove from database. "
                                        + "If false (default), soft-delete only.") @QueryParam("permanent") @DefaultValue("false") Boolean permanent,
                        @Parameter(description = "If true, also delete all extension resources "
                                        + "referenced by this package (behavior, httpcalls, output, langchain, "
                                        + "propertysetter, parser dictionaries).") @QueryParam("cascade") @DefaultValue("false") Boolean cascade);
}
