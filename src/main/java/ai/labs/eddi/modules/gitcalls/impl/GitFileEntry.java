package ai.labs.eddi.modules.gitcalls.impl;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Deprecated
public class GitFileEntry {
    private String directory;
    private String filename;
    private String content;
}
