package io.sls.resources.rest.expression;

import org.jboss.resteasy.annotations.GZIP;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * User: jarisch
 * Date: 26.11.12
 * Time: 14:57
 */
@Path("/expressions")
public interface IRestExpressions {
    @GET
    @GZIP
    @Produces(MediaType.APPLICATION_JSON)
    List<String> readExpressions(@QueryParam("packageId") String packageId,
                                 @QueryParam("packageVersion") Integer packageVersion,
                                 @QueryParam("filter") @DefaultValue("") String filter,
                                 @QueryParam("limit") @DefaultValue("20") Integer limit);
}
