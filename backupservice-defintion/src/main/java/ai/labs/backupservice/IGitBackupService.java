package ai.labs.backupservice;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiResponse;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

@Api(value = "backup")
@Path("/backup/git")
public interface IGitBackupService {

    @GET
    @Path("/pull/{botid}")
    @ApiResponse(code = 200, message = "returns the gitmessage of the attempted pull into edde from the configured git repository")
    Response gitPull(@PathParam("botid") String botid,
                     @QueryParam("force") boolean force);

    @GET
    @Path("/commit/{botid}")
    @ApiResponse(code = 200, message = "returns the gitmessage of the attempted commit.")
    Response gitCommit(@PathParam("botid") String botid, @PathParam("commit_msg") String commitMessage);

    @GET
    @Path("/push/{botid}")
    @ApiResponse(code = 200, message = "returns the gitmessage of the attempted push into the configured git repository.")
    Response gitPush(@PathParam("botid") String botid);

}
