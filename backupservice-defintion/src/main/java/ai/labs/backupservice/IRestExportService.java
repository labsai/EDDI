package ai.labs.backupservice;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/backup/export")
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{botId}")
    Response exportBot(@PathParam("botId") String botId, @QueryParam("botVersion") Integer botVersion);
}
