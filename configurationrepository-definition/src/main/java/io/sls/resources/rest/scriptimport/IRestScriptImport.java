package io.sls.resources.rest.scriptimport;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;

/**
 * User: jarisch
 * Date: 22.06.13
 * Time: 20:12
 */
@Path("/scriptimport")
public interface IRestScriptImport {
    @POST
    Response createBot(@QueryParam("language") String language,
                       @QueryParam("botId") String botId,
                       @QueryParam("botVersion") Integer botVersion,
                       String script);
}
