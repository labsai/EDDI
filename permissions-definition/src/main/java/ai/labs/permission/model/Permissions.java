package ai.labs.permission.model;

import ai.labs.permission.IAuthorization;
import ai.labs.permission.IPermissions;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class Permissions implements IPermissions {
    private Map<IAuthorization.Type, AuthorizedSubjects> permissions;

    public Permissions() {
        this.permissions = new HashMap<>();
    }

    @Override
    public Map<IAuthorization.Type, AuthorizedSubjects> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<IAuthorization.Type, AuthorizedSubjects> permissions) {
        this.permissions = permissions;
    }
}
