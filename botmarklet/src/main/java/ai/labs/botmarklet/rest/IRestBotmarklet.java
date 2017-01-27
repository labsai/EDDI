package ai.labs.botmarklet.rest;

import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

/**
 * @author ginccc
 */
@Path("/botmarklet")
public interface IRestBotmarklet {
    @GET
    @NoCache
    String read(@QueryParam("environment") String environment,
                @QueryParam("botId") String botId);
}
