/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import java.util.List;

/**
 * Query surface for listing resources by field, on top of the id-addressed
 * reads {@link IResourceStore} provides.
 * <p>
 * A caller passes {@link QueryFilters} groups; filters inside a group combine
 * under that group's {@link QueryFilters.ConnectingType} (AND or OR), and the
 * groups themselves are then combined. Paging is index/limit, with the same
 * ceiling {@link IResourceStorage} enforces.
 * <p>
 * Used by the REST layer to back list endpoints and their search parameters.
 *
 * @param <T>
 *            the configuration model being queried
 */
public interface IResourceFilter<T> {
    List<T> readResources(QueryFilters[] queryFilters, Integer index, Integer limit, String... sortTypes)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException;

    class QueryFilters {
        public enum ConnectingType {
            AND, OR
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
