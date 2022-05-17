package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.DBObject;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.reactivex.rxjava3.core.Observable;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.IOException;
import java.util.*;
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

        BsonDocument query = createQuery(queryFilters);
        Document sort = createSortQuery(sortTypes);
        Observable<Document> observable = Observable.fromPublisher(collection.find(query).sort(sort).skip(index).limit(limit));
        Iterable<Document> iterable = observable.blockingIterable();

        for (Document result : iterable) {
            String id = result.get(FIELD_ID).toString();
            Object versionField = result.get(FIELD_VERSION);

            Integer currentVersion = Integer.parseInt(versionField.toString());
            ret.add(resourceStore.read(id, currentVersion));
        }
        return ret;
    }

    private BsonDocument createQuery(QueryFilters[] allQueryFilters) {
        BsonDocument filter = new BsonDocument();

        for (QueryFilters queryFilters : allQueryFilters) {
            List<Bson> filters = new ArrayList<>();
            for (QueryFilter queryFilter : queryFilters.getQueryFilters()) {
                if (queryFilter.getFilter() instanceof String) {
                    filters.add(Filters.regex(queryFilter.getField(), queryFilter.getFilter().toString()));
                } else {
                    filters.add(Filters.eq(queryFilter.getField(), queryFilter.getFilter()));
                }
            }

            if (queryFilters.getConnectingType() == QueryFilters.ConnectingType.AND) {
                filter = Filters.and(filters).toBsonDocument();
            } else {
                filter = Filters.or(filters).toBsonDocument();
            }
        }

        return filter;
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
