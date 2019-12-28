package ai.labs.resources.rest.backup.model;

import lombok.Getter;
import lombok.Setter;

import java.net.URI;

@Getter
@Setter
public class GitBackupSettings {

    private String repositoryUrl;
    private String branch;
    private String username;
    private String password;
    private String committerEmail;
    private String committerName;
    private String description;
    private boolean isAutomatic = false;
}
