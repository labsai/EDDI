package ai.labs.eddi.backup;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/backup/export")
@Tag(name = "Backup")
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{agentFilename}")
    Response getAgentZipArchive(@PathParam("agentFilename") String agentFilename);

    @POST
    @Path("{agentId}")
    Response exportAgent(@PathParam("agentId") String agentId, @QueryParam("agentVersion") @DefaultValue("1") Integer agentVersion);
}
