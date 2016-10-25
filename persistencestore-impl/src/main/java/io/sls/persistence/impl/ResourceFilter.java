package io.sls.persistence.impl;

import com.mongodb.*;
import io.sls.group.IGroupStore;
import io.sls.permission.IAuthorization;
import io.sls.permission.IPermissionStore;
import io.sls.permission.model.AuthorizedSubjects;
import io.sls.permission.model.AuthorizedUser;
import io.sls.permission.model.Permissions;
import io.sls.permission.utilities.PermissionUtilities;
import io.sls.persistence.IResourceFilter;
import io.sls.persistence.IResourceStore;
import io.sls.runtime.ThreadContext;
import io.sls.serialization.IDocumentBuilder;
import io.sls.user.IUserStore;
import io.sls.utilities.RuntimeUtilities;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * User: jarisch
 * Date: 11.09.12
 * Time: 12:35
 */
@Slf4j
public class ResourceFilter<T> implements IResourceFilter<T> {
    private static final String FIELD_ID = "_id";
    private static final String FIELD_VERSION = "_version";

    private DBCollection collection;
    private IResourceStore<T> resourceStore;
    private IPermissionStore permissionStore;
    private Map<String, Pattern> regexCache;
    private final IDocumentBuilder documentBuilder;
    private IGroupStore groupStore;
    private final IUserStore userStore;

    public ResourceFilter(DBCollection collection, IResourceStore<T> resourceStore, IPermissionStore permissionStore, IUserStore userStore, IGroupStore groupStore, IDocumentBuilder documentBuilder) {
        this.collection = collection;
        this.resourceStore = resourceStore;
        this.permissionStore = permissionStore;
        this.regexCache = new HashMap<>();
        this.documentBuilder = documentBuilder;
        this.userStore = userStore;
        this.groupStore = groupStore;
    }

    @Override
    public List<T> readResources(QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<T> ret = new LinkedList<>();

        DBCursor dbCursor = null;
        try {
            DBObject query = createQuery(queryFilters);
            dbCursor = collection.find(query);
            DBObject sort = createSortQuery(sortTypes);
            dbCursor = dbCursor.sort(sort);
            if (limit > 0) {
                dbCursor = dbCursor.skip(index * limit);
            } else {
                dbCursor = dbCursor.skip(index);
            }
            while (dbCursor.hasNext()) {
                if (limit > 0 && ret.size() >= limit) {
                    break;
                }

                DBObject next = dbCursor.next();
                String id = next.get(FIELD_ID).toString();
                T document = buildDocument(next);
                Permissions permissions = null;
                try {
                    permissions = getPermissions(id);
                } catch (IResourceStore.ResourceNotFoundException e) {
                    log.warn("Missing Permission with Resource id: %s , access has been granted.");
                }

                if (permissions != null && permissions.getPermissions().values().isEmpty()) {
                    continue;
                }

                Object versionField = next.get(FIELD_VERSION);
                if (versionField != null && permissions != null) {
                    Integer currentVersion = Integer.parseInt(versionField.toString());
                    Integer highestPermittedVersion = getHighestPermittedVersion(currentVersion, permissions.getPermissions());
                    if (highestPermittedVersion < currentVersion) {
                        document = resourceStore.read(id, highestPermittedVersion);
                    }
                }

                ret.add(document);
            }
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        } finally {
            if (dbCursor != null) {
                dbCursor.close();
            }
        }

        return ret;
    }

    private DBObject createQuery(QueryFilters[] allQueryFilters) {
        QueryBuilder retQuery = new QueryBuilder();

        for (QueryFilters queryFilters : allQueryFilters) {
            List<DBObject> dbObjects = new LinkedList<DBObject>();
            for (QueryFilter queryFilter : queryFilters.getQueryFilters()) {
                if (queryFilter.getFilter() instanceof String) {
                    Pattern resourcePattern = getPatternForRegex((String) queryFilter.getFilter());
                    dbObjects.add(new QueryBuilder().put(queryFilter.getField()).regex(resourcePattern).get());
                } else {
                    dbObjects.add(new QueryBuilder().put(queryFilter.getField()).is(queryFilter.getFilter()).get());
                }
            }

            DBObject[] dbObjectArray = dbObjects.toArray(new DBObject[dbObjects.size()]);

            DBObject filterQuery;
            if (dbObjectArray.length > 0) {
                if (queryFilters.getConnectingType() == QueryFilters.ConnectingType.AND) {
                    filterQuery = new QueryBuilder().and(dbObjectArray).get();
                } else {
                    filterQuery = new QueryBuilder().or(dbObjectArray).get();
                }

                retQuery.and(filterQuery);
            }

        }

        return retQuery.get();
    }

    private DBObject createSortQuery(String... sortTypes) {
        BasicDBObjectBuilder objectBuilder = new BasicDBObjectBuilder();
        for (String sortType : sortTypes) {
            objectBuilder.add(sortType, -1);
        }

        return objectBuilder.get();
    }

    private T buildDocument(DBObject descriptor) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException, IOException {
        descriptor.removeField("_id");
        T document = (T) documentBuilder.build(descriptor.toString());

        return document;
    }

    private Permissions getPermissions(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Permissions permissions = permissionStore.readPermissions(id);
        if (!RuntimeUtilities.isNullOrEmpty(ThreadContext.getSubject())) {
            PermissionUtilities.keepOwnPermissionsOnly(userStore, groupStore, permissions);
        }
        return permissions;
    }

    private Integer getHighestPermittedVersion(Integer latestVersion, Map<IAuthorization.Type, AuthorizedSubjects> permissions) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        int highestVersion = Integer.MIN_VALUE;

        for (IAuthorization.Type type : IAuthorization.Type.values()) {
            AuthorizedSubjects authorizedSubjects = permissions.get(type);
            if (authorizedSubjects == null) {
                continue;
            }
            List<AuthorizedUser> authorizedUsers = authorizedSubjects.getUsers();
            for (AuthorizedUser authorizedUser : authorizedUsers) {
                if (authorizedUser.getVersions() == null) {
                    return latestVersion;
                }

                Integer version = authorizedUser.getVersions().get(0);
                if (highestVersion < version) {
                    highestVersion = version;
                }
            }
        }

        return highestVersion;
    }

    private Pattern getPatternForRegex(String regex) {
        Pattern pattern = regexCache.get(regex);
        if (pattern == null) {
            pattern = Pattern.compile(regex);
            regexCache.put(regex, pattern);
        }
        return pattern;
    }
}
