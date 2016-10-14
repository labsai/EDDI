package io.sls.permission.impl;

import io.sls.group.IGroupStore;
import io.sls.permission.IAuthorization;
import io.sls.permission.IAuthorizationManager;
import io.sls.permission.IPermissionStore;
import io.sls.permission.IPermissions;
import io.sls.permission.model.AuthorizedSubjects;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.utilities.PermissionUtilities;
import io.sls.persistence.IResourceStore;

import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 28.08.12
 * Time: 12:11
 */
public class AuthorizationManager implements IAuthorizationManager {
    private final IGroupStore groupStore;
    private final IPermissionStore permissionStore;

    @Inject
    public AuthorizationManager(IGroupStore groupStore, IPermissionStore permissionStore) {
        this.groupStore = groupStore;
        this.permissionStore = permissionStore;
    }

    @Override
    public boolean isUserAuthorized(String resourceId, Integer version, URI user, IAuthorization.Type authorizationType) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        try {
            IPermissions permissions = permissionStore.readFilteredPermissions(resourceId);
            List<IAuthorization.Type> allowedAuthorizationTypes = getImplicitAuthorizationTypes(authorizationType);
            for (IAuthorization.Type type : allowedAuthorizationTypes) {
                AuthorizedSubjects authorizedSubjects = permissions.getPermissions().get(type);
                if (authorizedSubjects != null) {
                    List<AuthorizedUser> authorizedUsers = PermissionUtilities.mergeAuthorizedSubjects(groupStore, authorizedSubjects);
                    authorizedSubjects.getUsers().clear();
                    authorizedSubjects.getUsers().addAll(authorizedUsers);
                    PermissionUtilities.filterAuthorizedSubjectsByUser(user, authorizedSubjects.getUsers());
                    for (AuthorizedUser authorizedUser : authorizedSubjects.getUsers()) {
                        List<Integer> versions = authorizedUser.getVersions();
                        if (versions == null || versions.contains(version)) {
                            return true;
                        }
                    }
                }
            }

            return false;
        } catch (IAuthorization.UnrecognizedAuthorizationTypeException e) {
            throw new IResourceStore.ResourceStoreException(e.getMessage(), e);
        }
    }

    public List<IAuthorization.Type> getImplicitAuthorizationTypes(IAuthorization.Type type) throws IAuthorization.UnrecognizedAuthorizationTypeException {
        List<IAuthorization.Type> authorizationTypes = new LinkedList<IAuthorization.Type>();

        switch (type) {
            case VIEW:
                authorizationTypes.add(IAuthorization.Type.VIEW);
            case USE:
                authorizationTypes.add(IAuthorization.Type.USE);
            case READ:
                authorizationTypes.add(IAuthorization.Type.READ);
            case WRITE:
                authorizationTypes.add(IAuthorization.Type.WRITE);
            case ADMINISTRATION:
                authorizationTypes.add(IAuthorization.Type.ADMINISTRATION);
                break;
            default:
                String message = "Cannot handle AuthorizationType: %s";
                message = String.format(message, type);
                throw new IAuthorization.UnrecognizedAuthorizationTypeException(message);
        }

        return authorizationTypes;
    }
}
