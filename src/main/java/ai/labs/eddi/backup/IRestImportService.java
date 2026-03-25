package ai.labs.eddi.backup;

import ai.labs.eddi.backup.model.ImportPreview;
import ai.labs.eddi.engine.model.AgentDeploymentStatus;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import java.io.InputStream;
import java.util.List;

/**
 * @author ginccc
 */
@Path("backup/import")
@Tag(name = "10. Backup Agents", description = "Import & Export Agents as Zip Files")
public interface IRestImportService {
    @POST
    @Path("/initialAgents")
    @Produces(MediaType.APPLICATION_JSON)
    List<AgentDeploymentStatus> importInitialAgents();

    @POST
    @Consumes("application/zip")
    @Operation(description = "Import a Agent from a zip file. " + "strategy=create (default) always creates new resources. "
            + "strategy=merge looks up existing resources by origin ID and updates them.")
    void importAgent(InputStream zippedAgentConfigFiles, @QueryParam("strategy") @DefaultValue("create") String strategy,
            @QueryParam("selectedResources") String selectedOriginIds, @Suspended AsyncResponse response);

    @POST
    @Path("/preview")
    @Consumes("application/zip")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Preview what a merge import would do: which resources would be created, "
            + "updated, or skipped. Does not modify any data.")
    ImportPreview previewImport(InputStream zippedAgentConfigFiles);
}
