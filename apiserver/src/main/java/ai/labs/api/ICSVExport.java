package ai.labs.api;

import ai.labs.persistence.IResourceStore;
import ai.labs.rest.restinterfaces.RestInterfaceFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import java.util.Date;

@Path("csvexport")
public interface ICSVExport {
    @GET
    void export(@QueryParam("apiServerUri") String apiServerUri, @QueryParam("dateSince") Date date) throws RestInterfaceFactory.RestInterfaceFactoryException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;
}
