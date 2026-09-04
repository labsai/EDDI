/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
@Tag(name = "Operations / Backup", description = "Import and export agents as zip files")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{agentFilename}")
    Response getAgentZipArchive(@PathParam("agentFilename") String agentFilename);

    @POST
    @Path("{agentId}")
    @Operation(description = "Export an agent as a ZIP file. When selectedResources is provided, "
            + "only those extension resource IDs are included in the ZIP (agent + workflow skeletons "
            + "are always included). Snippets and schedules are selected through their own parameters, "
            + "because their preview rows are newer than selectedResources: omit a parameter and every "
            + "referenced snippet / every schedule of the agent is exported, pass it (even empty) and "
            + "only the listed IDs are. "
            + "The Location header names the finished archive, which is kept for "
            + "eddi.backup.export.retention-minutes and then deleted.")
    Response exportAgent(@PathParam("agentId") String agentId,
                         @QueryParam("agentVersion")
                         @DefaultValue("1") Integer agentVersion,
                         @QueryParam("selectedResources") String selectedResourceIds,
                         @QueryParam("selectedSnippets") String selectedSnippetIds,
                         @QueryParam("selectedSchedules") String selectedScheduleIds);

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
