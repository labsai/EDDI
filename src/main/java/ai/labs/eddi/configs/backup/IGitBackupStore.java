package ai.labs.eddi.configs.backup;


import ai.labs.eddi.configs.backup.model.GitBackupSettings;
import io.swagger.v3.oas.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

// @Api(value = "Backup -> GIT Settings", authorizations = {@Authorization(value = "eddi_auth")})
@Path("/backup/gitsettings")
@Tag(name = "10. Backup Bots", description = "Import & Export Bots as Zip Files")
public interface IGitBackupStore {

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Read git settings - username and password is not shown in response")
    GitBackupSettings readSettings();

    @PUT
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(description = "Write git settings")
    void storeSettings(GitBackupSettings settings);

    GitBackupSettings readSettingsInternal();

}
