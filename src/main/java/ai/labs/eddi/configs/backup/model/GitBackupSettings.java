package ai.labs.eddi.configs.backup.model;

import lombok.Getter;
import lombok.Setter;


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
