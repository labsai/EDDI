package io.sls.permission.model;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 07.09.12
 * Time: 15:21
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
