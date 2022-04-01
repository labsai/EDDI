package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
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

public class ResourceFilter<T> implements IResourceFilter<T> {
    private static final String FIELD_ID = "_id";
    private static final String FIELD_VERSION = "_version";

    private final MongoCollection<Document> collection;
    private final IResourceStore<T> resourceStore;
    private final Class<T> documentType;
    private final Map<String, Pattern> regexCache;
    private final IDocumentBuilder documentBuilder;

    public ResourceFilter(MongoCollection<Document> collection, IResourceStore<T> resourceStore,
                          IDocumentBuilder documentBuilder, Class<T> documentType) {
        this.collection = collection;
        this.resourceStore = resourceStore;
        this.documentType = documentType;
        this.documentBuilder = documentBuilder;
        this.regexCache = new HashMap<>();
    }

    @Override
    public List<T> readResources(IResourceFilter.QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes)
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


                    Object versionField = result.get(FIELD_VERSION);

                    Integer currentVersion = Integer.parseInt(versionField.toString());
                    model = resourceStore.read(id, currentVersion);
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

            DBObject[] dbObjectArray = dbObjects.toArray(new DBObject[0]);

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

    private Pattern getPatternForRegex(String regex) {
        return regexCache.computeIfAbsent(regex, k -> Pattern.compile(regex));
    }
}
