package ai.labs.eddi.backup;

import ai.labs.eddi.backup.model.ExportPreview;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/backup/export")
@Tag(name = "Backup")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{agentFilename}")
    Response getAgentZipArchive(@PathParam("agentFilename") String agentFilename);

    @POST
    @Path("{agentId}")
    @Operation(description = "Export a Agent as a ZIP file. When selectedResources is provided, "
            + "only those resource IDs are included in the ZIP (agent + workflow skeletons are always included).")
    Response exportAgent(@PathParam("agentId") String agentId,
                         @QueryParam("agentVersion")
                         @DefaultValue("1") Integer agentVersion,
                         @QueryParam("selectedResources") String selectedResourceIds);

    @POST
    @Path("{agentId}/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Preview the resource tree of an agent for selective export. "
            + "Returns all resources contained in the agent, organized by workflow, "
            + "with selectability flags (agent + workflow skeletons are always required).")
    ExportPreview previewExport(@PathParam("agentId") String agentId,
                                @QueryParam("agentVersion")
                                @DefaultValue("1") Integer agentVersion);
}
