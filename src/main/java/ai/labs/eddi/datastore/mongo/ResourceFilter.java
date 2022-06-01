package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStore;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoCollection;
import io.reactivex.rxjava3.core.Observable;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class ResourceFilter<T> implements IResourceFilter<T> {
    private static final String FIELD_ID = "_id";
    private static final String FIELD_VERSION = "_version";

    private final MongoCollection<Document> collection;
    private final IResourceStore<T> resourceStore;

    public ResourceFilter(MongoCollection<Document> collection, IResourceStore<T> resourceStore) {
        this.collection = collection;
        this.resourceStore = resourceStore;
    }

    @Override
    public List<T> readResources(IResourceFilter.QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<T> ret = new LinkedList<>();

        BsonDocument query = createQuery(queryFilters);
        Document sort = createSortQuery(sortTypes);
        var publisher = collection.find(query).sort(sort);
        if (limit == null || limit < 1) {
            limit = 20;
        }
        publisher.limit(limit);

        if (index != null) {
            publisher.skip(index > 0 ? (index * limit) : 0);
        }

        Observable<Document> observable = Observable.fromPublisher(publisher);
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

        List<Bson> connectedFilters = new ArrayList<>();
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
                connectedFilters.add(Filters.and(filters));
            } else {
                connectedFilters.add(Filters.or(filters));
            }
        }

        return Filters.and(connectedFilters).toBsonDocument();
    }

    private Document createSortQuery(String... sortTypes) {
        Document document = new Document();
        for (String sortType : sortTypes) {
            document.put(sortType, -1);
        }

        return document;
    }
}
