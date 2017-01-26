package ai.labs.permission.model;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class AuthorizedGroup {
    private URI group;
    private List<Integer> versions;

    public AuthorizedGroup() {
        this.versions = new LinkedList<Integer>();
    }

    public URI getGroup() {
        return group;
    }

    public void setGroup(URI group) {
        this.group = group;
    }

    public List<Integer> getVersions() {
        return versions;
    }

    public void setVersions(List<Integer> versions) {
        this.versions = versions;
    }
}
