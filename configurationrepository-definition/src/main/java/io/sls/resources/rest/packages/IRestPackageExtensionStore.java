package io.sls.resources.rest.packages;

import io.sls.resources.rest.packages.model.PackageConfiguration;
import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * User: jarisch
 * Date: 11.09.12
 * Time: 09:32
 */
@Path("/packagestore/extensions")
public interface IRestPackageExtensionStore {
    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<PackageConfiguration.PackageExtension> getBehaviorRuleExtensions();
}
