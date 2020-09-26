package ai.labs.resources.rest.config.git.model;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @author rpi
 */

@Getter
@Setter
public class GitCall {
    private String name;
    private List<String> actions;
    private GitCommand command;
    private String message;
    private String directory;
    private String filename;
    private String content;
}
