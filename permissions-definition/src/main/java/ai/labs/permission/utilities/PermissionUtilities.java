package ai.labs.permission.utilities;

import ai.labs.group.IGroupStore;
import ai.labs.group.model.Group;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.model.AuthorizedGroup;
import ai.labs.permission.model.AuthorizedSubjects;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.model.Permissions;
import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.ThreadContext;
import ai.labs.user.IUserStore;
import ai.labs.user.rest.IRestUserStore;
import ai.labs.utilities.RestUtilities;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.SecurityUtilities;

import javax.security.auth.Subject;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public final class PermissionUtilities {
    private PermissionUtilities() {
        //not implemented
    }

    public static Permissions createDefaultPermissions(URI user) {
        Permissions permissions = new Permissions();

        AuthorizedSubjects authorizedSubjects = new AuthorizedSubjects();
        if (user != null) {
            authorizedSubjects.getUsers().add(new AuthorizedUser(user, null));
        }
        permissions.getPermissions().put(IAuthorization.Type.ADMINISTRATION, authorizedSubjects);

        return permissions;
    }

    public static void filterAuthorizedSubjectsByUser(URI user, List<AuthorizedUser> authorizedUsers) {
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

    public static List<AuthorizedUser> mergeAuthorizedSubjects(IGroupStore groupStore, AuthorizedSubjects authorizedSubjects)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<AuthorizedUser> authorizedUsers = new LinkedList<>(authorizedSubjects.getUsers());

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

    public static void keepOwnPermissionsOnly(IUserStore userStore, IGroupStore groupStore, Permissions permissions)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Subject subject = ThreadContext.getSubject();
        if (subject != null) {

            String username = SecurityUtilities.getPrincipal(subject).getName();
            URI currentUser = URI.create(IRestUserStore.resourceURI + userStore.searchUser(username));
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

    private static void optimizeAuthorizedUsers(List<AuthorizedUser> authorizedUsers) {
        List<AuthorizedUser> ret = new LinkedList<>();

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

    }

    private static List<Integer> mergeVersions(List<Integer> base, List<Integer> toBeAdded) {
        for (Integer version : toBeAdded) {
            if (!base.contains(version)) {
                base.add(version);
            }
        }

        base.sort(Collections.reverseOrder());

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
