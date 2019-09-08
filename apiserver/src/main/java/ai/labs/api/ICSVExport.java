package ai.labs.api;

import ai.labs.persistence.IResourceStore;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.util.Date;

@Path("csvexport")
public interface ICSVExport {
    @GET
    @Path("{botId}")
    Response export(@PathParam("{botId}") String botId,
                    @QueryParam("index") @DefaultValue("0") Integer index,
                    @QueryParam("limit") @DefaultValue("20") Integer limit,
                    @QueryParam("apiServerUri") String apiServerUri,
                    @QueryParam("dateSince") Date date,
                    @QueryParam("addAnswerTimestamp") @DefaultValue("true") Boolean addAnswerTimestamp)
            throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
