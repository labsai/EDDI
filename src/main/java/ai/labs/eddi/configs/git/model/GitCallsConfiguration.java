package ai.labs.eddi.configs.git.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author rpi
 */

@Getter
@Setter
public class GitCallsConfiguration {

    private String url;
    private String username;
    private String password;
    private List<GitCall> gitCalls;

}
