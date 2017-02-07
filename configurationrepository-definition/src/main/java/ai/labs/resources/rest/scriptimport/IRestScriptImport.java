package ai.labs.resources.rest.scriptimport;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@Path("/scriptimport")
public interface IRestScriptImport {
    @POST
    Response createBot(@QueryParam("language") String language,
                       @QueryParam("botId") String botId,
                       @QueryParam("botVersion") Integer botVersion,
                       String script);
}
