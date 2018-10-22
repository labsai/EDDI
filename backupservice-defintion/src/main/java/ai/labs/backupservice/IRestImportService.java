package ai.labs.backupservice;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ResponseHeader;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import java.io.InputStream;
import java.net.URI;

/**
 * @author ginccc
 */
@Api(value = "backup")
@Path("backup/import")
public interface IRestImportService {
    @POST
    @Consumes("application/zip")
    @ApiImplicitParam(type = "body", paramType = "body", required = true)
    @ApiResponse(code = 200, responseHeaders = {
            @ResponseHeader(name = "location", response = URI.class)
    }, message = "returns reference to the newly created (imported) bot")
    void importBot(InputStream zippedBotConfigFiles, @Suspended AsyncResponse response);
}
