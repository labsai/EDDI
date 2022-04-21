package ai.labs.eddi.backup;

import ai.labs.eddi.models.BotDeploymentStatus;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.util.List;

/**
 * @author ginccc
 */
@Path("backup/import")
public interface IRestImportService {
    @POST
    @Path("/examples")
    @Produces(MediaType.APPLICATION_JSON)
    List<BotDeploymentStatus> importBotExamples();

    @POST
    @Consumes("application/zip")
    void importBot(InputStream zippedBotConfigFiles, @Suspended AsyncResponse response);
}
