package ai.labs.permission;

import ai.labs.permission.model.AuthorizedSubjects;

import java.util.Map;

/**
 * @author ginccc
 */
public interface IPermissions {
    Map<IAuthorization.Type, AuthorizedSubjects> getPermissions();
}
