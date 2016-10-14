package io.sls.permission.utilities;

import io.sls.group.IGroupStore;
import io.sls.group.model.Group;
import io.sls.permission.IAuthorization;
import io.sls.permission.model.AuthorizedGroup;
import io.sls.permission.model.AuthorizedSubjects;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.model.Permissions;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.ThreadContext;
import io.sls.user.IUserStore;
import io.sls.user.rest.IRestUserStore;
import io.sls.utilities.RestUtilities;
import io.sls.utilities.RuntimeUtilities;
import io.sls.utilities.SecurityUtilities;

import javax.security.auth.Subject;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 31.08.12
 * Time: 12:14
 */
public final class PermissionUtilities {
    private PermissionUtilities() {
        //not implemented
    }

    public static Permissions createDefaultPermissions(URI user) {
        Permissions permissions = new Permissions();

        AuthorizedSubjects authorizedSubjects = new AuthorizedSubjects();
        authorizedSubjects.getUsers().add(new AuthorizedUser(user, null));
        permissions.getPermissions().put(IAuthorization.Type.ADMINISTRATION, authorizedSubjects);

        return permissions;
    }

    public static void filterAuthorizedSubjectsByUser(URI user, List<AuthorizedUser> authorizedUsers) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        for (int i = 0; i < authorizedUsers.size(); i++) {
            AuthorizedUser authorizedUser = authorizedUsers.get(i);
            if (authorizedUser.getUser() != null && !authorizedUser.getUser().equals(user)) {
                authorizedUsers.remove(i);
            } else {
                i++;
            }
        }

        optimizeAuthorizedUsers(authorizedUsers);
    }

    public static List<AuthorizedUser> mergeAuthorizedSubjects(IGroupStore groupStore, AuthorizedSubjects authorizedSubjects) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<AuthorizedUser> authorizedUsers = new LinkedList<AuthorizedUser>(authorizedSubjects.getUsers());

        List<AuthorizedGroup> groups = authorizedSubjects.getGroups();
        for (AuthorizedGroup authorizedGroup : groups) {
            URI group = authorizedGroup.getGroup();
            if(group == null) {
                authorizedUsers.add(new AuthorizedUser(null, null));
                continue;
            }
            Group userGroup = groupStore.readGroup(RestUtilities.extractResourceId(group).getId());
            for (URI userURI : userGroup.getUsers()) {
                authorizedUsers.add(new AuthorizedUser(userURI, authorizedGroup.getVersions()));
            }
        }

        return authorizedUsers;
    }

    public static void keepOwnPermissionsOnly(IUserStore userstore, IGroupStore groupStore, Permissions permissions) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Subject subject = ThreadContext.getSubject();
        if (subject != null) {

            String username = SecurityUtilities.getPrincipal(subject).getName();
            URI currentUser = URI.create(IRestUserStore.resourceURI + userstore.searchUser(username));
            AuthorizedSubjects authorizedSubjects;
            for (IAuthorization.Type type : IAuthorization.Type.values()) {
                authorizedSubjects = permissions.getPermissions().get(type);
                if (authorizedSubjects != null) {
                    List<AuthorizedUser> authorizedUsers = PermissionUtilities.mergeAuthorizedSubjects(groupStore, authorizedSubjects);
                    authorizedSubjects.getUsers().clear();
                    authorizedSubjects.getUsers().addAll(authorizedUsers);
                    PermissionUtilities.filterAuthorizedSubjectsByUser(currentUser, authorizedSubjects.getUsers());
                    if ((authorizedSubjects.getUsers() == null || authorizedSubjects.getUsers().isEmpty()) &&
                            (authorizedSubjects.getGroups() == null || authorizedSubjects.getGroups().isEmpty())) {
                        permissions.getPermissions().remove(type);
                    }
                }
            }
        }
    }

    public static boolean containsUser(List<AuthorizedUser> authorizedUsers, URI user) {
        RuntimeUtilities.checkNotNull(authorizedUsers, "authorizedUsers");

        for (AuthorizedUser authorizedUser : authorizedUsers) {
            if (authorizedUser.getUser().equals(user)) {
                return true;
            }
        }

        return false;
    }

    private static List<AuthorizedUser> optimizeAuthorizedUsers(List<AuthorizedUser> authorizedUsers) {
        List<AuthorizedUser> ret = new LinkedList<AuthorizedUser>();

        for (AuthorizedUser authorizedUser : authorizedUsers) {
            int index = ret.indexOf(authorizedUser);
            AuthorizedUser user;
            if (index > -1) {
                user = ret.get(index);
            } else {
                user = authorizedUser;
                ret.add(authorizedUser);
            }

            if (user.getVersions() != null && authorizedUser.getVersions() != null) {
                user.setVersions(mergeVersions(user.getVersions(), authorizedUser.getVersions()));
            } else {
                user.setVersions(null);
            }
        }

        return ret;
    }

    private static List<Integer> mergeVersions(List<Integer> base, List<Integer> toBeAdded) {
        for (Integer version : toBeAdded) {
            if (!base.contains(version)) {
                base.add(version);
            }
        }

        Collections.sort(base, Collections.reverseOrder());

        return base;
    }

    public static void addAuthorizedUser(Permissions permissions, IAuthorization.Type type, AuthorizedUser user) {
        if(!permissions.getPermissions().containsKey(type)) {
            permissions.getPermissions().put(type, new AuthorizedSubjects());
        }

        AuthorizedSubjects authorizedSubjects = permissions.getPermissions().get(type);
        if(!authorizedSubjects.getUsers().contains(user)) {
            authorizedSubjects.getUsers().add(user);
        }
    }
}
