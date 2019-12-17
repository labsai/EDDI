package ai.labs.rest.restinterfaces;

import org.jboss.resteasy.annotations.cache.NoCache;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("/monitoring/prometheus")
public interface IRestPrometheusMonitoring {
    @GET
    @NoCache
    @Produces(MediaType.TEXT_PLAIN)
    Response readMonitoringStats();
}
