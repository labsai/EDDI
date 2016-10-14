package io.sls.botmarklet.rest;

import org.jboss.resteasy.annotations.GZIP;
import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;

/**
 * User: jarisch
 * Date: 20.01.13
 * Time: 18:09
 */
@Path("/botmarklet")
public interface IRestBotmarklet {
    @GET
    @GZIP
    @NoCache
    String read(@QueryParam("environment") String environment,
                @QueryParam("botId") String botId);
}
