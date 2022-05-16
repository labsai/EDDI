package ai.labs.eddi.backup;

import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

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
    @Path("{botId}")
    Response exportBot(@PathParam("botId") String botId,
                       @QueryParam("botVersion") @DefaultValue("1") Integer botVersion);
}
