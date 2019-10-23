package ai.labs.persistence;

import ai.labs.group.IGroupStore;
import ai.labs.permission.IAuthorization;
import ai.labs.permission.IPermissionStore;
import ai.labs.permission.model.AuthorizedSubjects;
import ai.labs.permission.model.AuthorizedUser;
import ai.labs.permission.model.Permissions;
import ai.labs.permission.utilities.PermissionUtilities;
import ai.labs.runtime.ThreadContext;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.user.IUserStore;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * @author ginccc
 */
@Slf4j
public class ResourceFilter<T> implements IResourceFilter<T> {
    private static final String FIELD_ID = "_id";
    private static final String FIELD_VERSION = "_version";

    private MongoCollection<Document> collection;
    private IResourceStore<T> resourceStore;
    private IPermissionStore permissionStore;
    private Class<T> documentType;
    private Map<String, Pattern> regexCache;
    private final IDocumentBuilder documentBuilder;
    private IGroupStore groupStore;
    private final IUserStore userStore;

    public ResourceFilter(MongoCollection<Document> collection, IResourceStore<T> resourceStore,
                          IPermissionStore permissionStore, IUserStore userStore,
                          IGroupStore groupStore, IDocumentBuilder documentBuilder, Class<T> documentType) {
        this.collection = collection;
        this.resourceStore = resourceStore;
        this.permissionStore = permissionStore;
        this.documentType = documentType;
        this.regexCache = new HashMap<>();

        this.documentBuilder = documentBuilder;
        this.userStore = userStore;
        this.groupStore = groupStore;
    }

    @Override
    public List<T> readResources(QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<T> ret = new LinkedList<>();

        try {
            Document query = createQuery(queryFilters);
            FindIterable<Document> results = collection.find(query);
            Document sort = createSortQuery(sortTypes);
            results = results.sort(sort);

            boolean queryHadResults;
            do {
                if (limit > 0) {
                    results = results.skip(index * limit);
                } else {
                    results = results.skip(index);
                }

                queryHadResults = false;
                for (Document result : results) {
                    queryHadResults = true;
                    if (limit > 0 && ret.size() >= limit) {
                        break;
                    }

                    String id = result.get(FIELD_ID).toString();
                    T model = buildDocument(result);
                    Permissions permissions = null;
                    try {
                        permissions = getPermissions(id);
                    } catch (IResourceStore.ResourceNotFoundException e) {
                        log.warn("Missing Permission with Resource id: %s , access has been granted.");
                    }

                    if (permissions != null && permissions.getPermissions().values().isEmpty()) {
                        permissions = null;
                    }

                    Object versionField = result.get(FIELD_VERSION);
                    if (permissions != null && versionField != null) {
                        Integer currentVersion = Integer.parseInt(versionField.toString());
                        Integer highestPermittedVersion = getHighestPermittedVersion(currentVersion, permissions.getPermissions());
                        if (highestPermittedVersion < currentVersion) {
                            model = resourceStore.read(id, highestPermittedVersion);
                        }
                    }

                    ret.add(model);
                }
                index++;
            } while (ret.size() < limit && queryHadResults);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

        return ret;
    }

    private Document createQuery(QueryFilters[] allQueryFilters) {
        QueryBuilder retQuery = new QueryBuilder();

        for (QueryFilters queryFilters : allQueryFilters) {
            List<DBObject> dbObjects = new LinkedList<>();
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

        return new Document(retQuery.get().toMap());
    }

    private Document createSortQuery(String... sortTypes) {
        Document document = new Document();
        for (String sortType : sortTypes) {
            document.put(sortType, -1);
        }

        return document;
    }

    private T buildDocument(Document descriptor) throws IOException {
        descriptor.remove("_id");
        return documentBuilder.build(descriptor, documentType);
    }

    private Permissions getPermissions(String id) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        Permissions permissions = permissionStore.readPermissions(id);
        if (!RuntimeUtilities.isNullOrEmpty(ThreadContext.getSubject())) {
            PermissionUtilities.keepOwnPermissionsOnly(userStore, groupStore, permissions);
        }
        return permissions;
    }

    private Integer getHighestPermittedVersion(Integer latestVersion,
                                               Map<IAuthorization.Type, AuthorizedSubjects> permissions) {
        int highestVersion = 1;

        boolean isOverruledByPermission = false;
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
                    isOverruledByPermission = true;
                }
            }
        }

        if (!isOverruledByPermission) {
            highestVersion = latestVersion;
        }

        return highestVersion;
    }

    private Pattern getPatternForRegex(String regex) {
        return regexCache.computeIfAbsent(regex, k -> Pattern.compile(regex));
    }
}
