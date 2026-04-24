/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup;

import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.backup.model.SyncMapping;
import ai.labs.eddi.backup.model.SyncRequest;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import jakarta.annotation.security.RolesAllowed;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import java.util.List;

/**
 * @author ginccc
 */
@Path("backup/import")
@Tag(name = "Backup")
@RolesAllowed({"eddi-admin", "eddi-editor"})
public interface IRestImportService {
    @POST
    @Path("/initialAgents")
    @Produces(MediaType.APPLICATION_JSON)
    List<AgentDeploymentStatus> importInitialAgents();

    @POST
    @Consumes("application/zip")
    @Operation(description = "Import a Agent from a zip file. "
            + "strategy=create (default) always creates new resources. "
            + "strategy=merge looks up existing resources by origin ID and updates them. "
            + "strategy=upgrade syncs content into the targetAgentId by structural matching.")
    Response importAgent(InputStream zippedAgentConfigFiles,
                         @QueryParam("strategy")
                         @DefaultValue("create") String strategy,
                         @QueryParam("selectedResources") String selectedOriginIds,
                         @QueryParam("targetAgentId") String targetAgentId,
                         @QueryParam("workflowOrder") String workflowOrder);

    @POST
    @Path("/preview")
    @Consumes("application/zip")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Preview what an import would do. When targetAgentId is provided, "
            + "uses structural matching with content diffs (upgrade preview). "
            + "Otherwise uses originId matching (merge preview).")
    ImportPreview previewImport(InputStream zippedAgentConfigFiles,
                                @QueryParam("targetAgentId") String targetAgentId);

    // ==================== Live Sync Endpoints ====================

    @GET
    @Path("/sync/agents")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "List agents on a remote EDDI instance. "
            + "Used by the UI to populate the source agent picker.")
    List<DocumentDescriptor> listRemoteAgents(@QueryParam("sourceUrl") String sourceUrl,
                                              @HeaderParam("X-Source-Authorization") String sourceAuth);

    @POST
    @Path("/sync/preview")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Preview a single-agent sync from a remote EDDI instance to a local target agent.")
    ImportPreview previewSync(@QueryParam("sourceUrl") String sourceUrl,
                              @QueryParam("sourceAgentId") String sourceAgentId,
                              @QueryParam("sourceAgentVersion") Integer sourceVersion,
                              @QueryParam("targetAgentId") String targetAgentId,
                              @HeaderParam("X-Source-Authorization") String sourceAuth);

    @POST
    @Path("/sync/preview/batch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Preview a multi-agent sync from a remote EDDI instance. "
            + "Each mapping specifies a source→target agent pair.")
    List<ImportPreview> previewSyncBatch(@QueryParam("sourceUrl") String sourceUrl,
                                         List<SyncMapping> mappings,
                                         @HeaderParam("X-Source-Authorization") String sourceAuth);

    @POST
    @Path("/sync")
    @Operation(description = "Execute a single-agent sync from a remote EDDI instance to a local target agent.")
    Response executeSync(@QueryParam("sourceUrl") String sourceUrl,
                         @QueryParam("sourceAgentId") String sourceAgentId,
                         @QueryParam("sourceAgentVersion") Integer sourceVersion,
                         @QueryParam("targetAgentId") String targetAgentId,
                         @QueryParam("selectedResources") String selectedResources,
                         @QueryParam("workflowOrder") String workflowOrder,
                         @HeaderParam("X-Source-Authorization") String sourceAuth);

    @POST
    @Path("/sync/batch")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(description = "Execute a multi-agent sync from a remote EDDI instance. "
            + "Each request specifies a source→target agent pair with selected resources and workflow order.")
    Response executeSyncBatch(@QueryParam("sourceUrl") String sourceUrl,
                              List<SyncRequest> requests,
                              @HeaderParam("X-Source-Authorization") String sourceAuth);
}
