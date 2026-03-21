package ai.labs.eddi.backup;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/backup/export")
@Tag(name = "10. Backup Bots", description = "Import & Export Bots as Zip Files")
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{botFilename}")
    Response getBotZipArchive(@PathParam("botFilename") String botFilename);

    @POST
    @Path("{agentId}")
    Response exportBot(@PathParam("agentId") String agentId,
                       @QueryParam("agentVersion") @DefaultValue("1") Integer agentVersion);
}
