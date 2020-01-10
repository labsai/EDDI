package ai.labs.resources.rest.backup;


import ai.labs.resources.rest.backup.model.GitBackupSettings;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Api(value = "Backup -> GIT Settings", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/backup/gitsettings")
public interface IGitBackupStore {

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Read git settings - username and password is not shown in response")
    GitBackupSettings readSettings();

    @PUT
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Write git settings")
    void storeSettings(GitBackupSettings settings);

    GitBackupSettings readSettingsInternal();

}
