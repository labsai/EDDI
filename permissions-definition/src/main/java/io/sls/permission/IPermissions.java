package io.sls.permission;

import io.sls.permission.model.AuthorizedSubjects;
import java.util.Map;

/**
 * User: jarisch
 * Date: 29.08.12
 * Time: 14:46
 */
public interface IPermissions {
    Map<IAuthorization.Type, AuthorizedSubjects> getPermissions();
}
