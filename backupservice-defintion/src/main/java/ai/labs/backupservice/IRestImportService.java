package ai.labs.backupservice;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.io.InputStream;

/**
 * @author ginccc
 */
@Path("backup/import")
public interface IRestImportService {
    @POST
    @Consumes("application/zip")
    Response exportBot(InputStream zippedBotConfigFiles);
}
