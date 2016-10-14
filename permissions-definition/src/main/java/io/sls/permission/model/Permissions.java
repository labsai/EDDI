package io.sls.permission.model;

import io.sls.permission.IAuthorization;
import io.sls.permission.IPermissions;

import java.util.HashMap;
import java.util.Map;

/**
 * User: jarisch
 * Date: 28.08.12
 * Time: 12:13
 */
public class Permissions implements IPermissions {
    private Map<IAuthorization.Type, AuthorizedSubjects> permissions;

    public Permissions() {
        this.permissions = new HashMap<IAuthorization.Type, AuthorizedSubjects>();
    }

    @Override
    public Map<IAuthorization.Type, AuthorizedSubjects> getPermissions() {
        return permissions;
    }

    public void setPermissions(Map<IAuthorization.Type, AuthorizedSubjects> permissions) {
        this.permissions = permissions;
    }
}
