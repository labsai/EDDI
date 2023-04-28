package ai.labs.eddi.configs.backup;


import ai.labs.eddi.configs.backup.model.GitBackupSettings;
import io.swagger.v3.oas.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

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
