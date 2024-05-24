package ai.labs.eddi.backup;

import ai.labs.eddi.engine.model.BotDeploymentStatus;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import java.io.InputStream;
import java.util.List;

/**
 * @author ginccc
 */
@Path("backup/import")
@Tag(name = "10. Backup Bots", description = "Import & Export Bots as Zip Files")
public interface IRestImportService {
    @POST
    @Path("/initialBots")
    @Produces(MediaType.APPLICATION_JSON)
    List<BotDeploymentStatus> importInitialBots();

    @POST
    @Consumes("application/zip")
    void importBot(InputStream zippedBotConfigFiles, @Suspended AsyncResponse response);
}
