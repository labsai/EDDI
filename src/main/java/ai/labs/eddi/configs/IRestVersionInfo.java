package ai.labs.eddi.configs;

import ai.labs.eddi.datastore.IResourceStore;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
public interface IRestVersionInfo {
    String versionQueryParam = "?version=";

    @POST
    @Path("/{id}/currentversion")
    @Operation(description = "Redirect to latest version.")
    default Response redirectToLatestVersion(@PathParam("id") String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            String path = URI.create(getResourceURI()).getPath();
            return Response.seeOther(URI.create(path + id + versionQueryParam + currentResourceId.getVersion())).build();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage());
        }
    }

    @GET
    @Path("/{id}/currentversion")
    @Produces(MediaType.TEXT_PLAIN)
    @Operation(description = "Get current version of this resource.")
    default Integer getCurrentVersion(@PathParam("id") String id) {
        try {
            IResourceStore.IResourceId currentResourceId = getCurrentResourceId(id);
            return currentResourceId.getVersion();
        } catch (IResourceStore.ResourceNotFoundException e) {
            throw new NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    String getResourceURI();

    default IResourceStore.IResourceId getCurrentResourceId(String id)
            throws IResourceStore.ResourceNotFoundException {
        throw new IllegalStateException("Method getCurrentVersion of interface IRestVersionInfo needs to be implemented");
    }
}
