package io.sls.permission;

import io.sls.permission.model.AuthorizedSubjects;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IPermissions {
    Map<IAuthorization.Type, AuthorizedSubjects> getPermissions();
}
