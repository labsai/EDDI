package ai.labs.backupservice;

import io.swagger.annotations.Api;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import java.io.InputStream;

/**
 * @author ginccc
 */
@Api(value = "backup")
@Path("backup/import")
public interface IRestImportService {
    @POST
    @Consumes("application/zip")
    void importBot(InputStream zippedBotConfigFiles, @Suspended AsyncResponse response);
}
