package ai.labs.backupservice;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;

@Api(value = "Backup", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/backup/git")
public interface IRestGitBackupService {

    @POST
    @Path("/init/{botId}")
    @ApiResponse(code = 200, message = "returns if the repository could be cloned. Deletes the tmp repository first")
    Response gitInit(@PathParam("botId") String botId);


    @GET
    @Path("/pull/{botId}")
    @ApiResponse(code = 200, message = "returns the git message of the attempted pull into eddi from the configured git repository")
    Response gitPull(@PathParam("botId") String botId,
                     @QueryParam("force") boolean force);

    @POST
    @Path("/commit/{botId}")
    @ApiResponse(code = 200, message = "returns the git message of the attempted commit.")
    Response gitCommit(@PathParam("botId") String botId, @QueryParam("commit_msg") String commitMessage);

    @POST
    @Path("/push/{botId}")
    @ApiResponse(code = 200, message = "returns the git message of the attempted push into the configured git repository.")
    Response gitPush(@PathParam("botId") String botId);

}
