package ai.labs.permission.impl;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.IAuthorizationManager;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.IPermissions;
import ai.labs.permission.model.AuthorizedSubjects;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.persistence.IResourceStore;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@ApplicationScoped
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
