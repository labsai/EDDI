package io.sls.permission.model;

import java.net.URI;
import java.util.List;

/**
 * User: jarisch
 * Date: 07.09.12
 * Time: 15:21
 */
public class AuthorizedUser {
    private URI user;
    private List<Integer> versions;

    public AuthorizedUser() {
    }

    public AuthorizedUser(URI user, List<Integer> versions) {
        this.user = user;
        this.versions = versions;
    }

    public URI getUser() {
        return user;
    }

    public void setUser(URI user) {
        this.user = user;
    }

    public List<Integer> getVersions() {
        return versions;
    }

    public void setVersions(List<Integer> versions) {
        this.versions = versions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AuthorizedUser that = (AuthorizedUser) o;

        return user.equals(that.user);

    }

    @Override
    public int hashCode() {
        return user.hashCode();
    }
}
