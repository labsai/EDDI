package ai.labs.eddi.backup;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * @author rpi
 */

@Path("/backup/git")
public interface IRestGitBackupService {

    @POST
    @Path("/init/{botId}")
    Response gitInit(@PathParam("botId") String botId);


    @GET
    @Path("/pull/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    Response gitPull(@PathParam("botId") String botId,
                     @QueryParam("force") boolean force);

    @POST
    @Path("/commit/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    Response gitCommit(@PathParam("botId") String botId, @QueryParam("commit_msg") String commitMessage);

    @POST
    @Path("/push/{botId}")
    @Produces(MediaType.TEXT_PLAIN)
    Response gitPush(@PathParam("botId") String botId);

    boolean isGitAutomatic();

    boolean isGitInitialised(String botId);
}
