package ai.labs.eddi.datastore;


import java.util.List;

/**
 * @author ginccc
 */
public interface IResourceFilter<T> {
    List<T> readResources(QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    class QueryFilters {
        public enum ConnectingType {
            AND,
            OR
        }

        private ConnectingType connectingType;
        private List<QueryFilter> queryFilters;

        public QueryFilters(List<QueryFilter> queryFilters) {
            this(ConnectingType.AND, queryFilters);
        }

        public QueryFilters(ConnectingType connectingType, List<QueryFilter> queryFilters) {
            this.connectingType = connectingType;
            this.queryFilters = queryFilters;
        }

        public ConnectingType getConnectingType() {
            return connectingType;
        }

        public List<QueryFilter> getQueryFilters() {
            return queryFilters;
        }
    }

    class QueryFilter {
        private String field;
        private Object filter;

        public QueryFilter(String field, Object filter) {
            this.field = field;
            this.filter = filter;
        }

        public String getField() {
            return field;
        }

        public Object getFilter() {
            return filter;
        }
    }
}
