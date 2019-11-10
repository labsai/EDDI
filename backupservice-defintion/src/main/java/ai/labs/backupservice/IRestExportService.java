package ai.labs.backupservice;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.Authorization;
import io.swagger.annotations.ResponseHeader;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.net.URI;

/**
 * @author ginccc
 */
@Api(value = "Backup", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/backup/export")
public interface IRestExportService {
    @GET
    @Produces("application/zip")
    @Path("{botFilename}")
    @ApiResponse(code = 200, response = InputStream.class, message = "returns bot zip file as application/zip")
    Response getBotZipArchive(@PathParam("botFilename") String botFilename);

    @POST
    @Path("{botId}")
    @ApiResponse(code = 200, responseHeaders = {
            @ResponseHeader(name = "location", response = URI.class)
    }, message = "returns location of the exported ZIP file to be downloaded")
    Response exportBot(@PathParam("botId") String botId,
                       @ApiParam(name = "botVersion", required = true, format = "integer", example = "1")
                       @QueryParam("botVersion") @DefaultValue("1") Integer botVersion);
}
