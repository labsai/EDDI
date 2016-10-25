package io.sls.group.model;

import io.sls.group.IGroup;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class Group implements IGroup {
    private String name;
    private List<URI> users;

    public Group() {
        this.users = new LinkedList<URI>();
    }

    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public List<URI> getUsers() {
        return users;
    }

    public void setUsers(List<URI> users) {
        this.users = users;
    }
}
