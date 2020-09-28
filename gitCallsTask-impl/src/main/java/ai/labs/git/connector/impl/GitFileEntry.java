package ai.labs.git.connector.impl;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GitFileEntry {
    private String directory;
    private String filename;
    private String content;
}
