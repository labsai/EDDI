package ai.labs.backupservice;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ResponseHeader;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.net.URI;

/**
 * @author ginccc
 */
@Api(value = "backup")
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
