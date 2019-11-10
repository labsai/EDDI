package ai.labs.backupservice;

import ai.labs.models.BotDeploymentStatus;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.ResponseHeader;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * @author ginccc
 */
@Api(value = "Backup", authorizations = {@Authorization(value = "eddi_auth")})
@Path("backup/import")
public interface IRestImportService {
    @POST
    @Path("/examples")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiResponse(code = 200, message = "bot examples have been deployed")
    List<BotDeploymentStatus> importBotExamples();

    @POST
    @Consumes("application/zip")
    @ApiImplicitParam(type = "body", paramType = "body", required = true)
    @ApiResponse(code = 200, responseHeaders = {
            @ResponseHeader(name = "location", response = URI.class)
    }, message = "returns reference to the newly created (imported) bot")
    void importBot(InputStream zippedBotConfigFiles, @Suspended AsyncResponse response);
}
