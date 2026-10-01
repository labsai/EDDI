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
 * A caller passes {@link QueryFilters} groups. Filters inside a group combine
 * under that group's {@link QueryFilters.ConnectingType} (AND or OR); the
 * groups themselves are always combined with AND, so a group's connector never
 * affects how it joins the other groups. Paging is index/limit, with the same
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

    /**
     * A filter value that matches when the field does <em>not</em> match
     * {@code pattern}.
     * <p>
     * A plain {@code String} filter is a regular expression on both backends, and
     * neither regex dialect they share can express a negation — POSIX ERE, which
     * PostgreSQL uses, has no lookahead. So "everything except X" needs its own
     * value type, which each backend renders as its native negation.
     * <p>
     * A document that does not carry the field at all <em>matches</em>: absent is
     * "not X". Both backends are written to agree on that, because a descriptor
     * without an owner is exactly the row a "not mine" listing must include.
     *
     * @param pattern
     *            a regular expression in the dialect both backends accept — see
     *            {@code Subjects.escapeRegex}
     */
    record NotMatching(String pattern) {
    }

    /**
     * A filter value that matches when the field contains {@code text} as a
     * literal, case-sensitive substring. A document without the field does not
     * match.
     * <p>
     * A search box used to send {@code .*<escaped text>.*} as a regex. That selects
     * the same rows, and costs a regex evaluation per field per row. With this
     * value type each backend uses its cheapest native form: MongoDB an unwrapped
     * escaped regex (the {@code .*} wrapping doubled the time of a selective
     * search), PostgreSQL an escaped {@code LIKE '%…%'}, which is no regex at all,
     * 1.3–1.6× faster than {@code ~} on the same scan, and the form a
     * {@code pg_trgm} index can serve.
     *
     * @param text
     *            the literal text to find; not a pattern
     */
    record Contains(String text) {
    }

    /**
     * One condition on one field. A {@code String} filter is a regular expression
     * on both backends; any other value is compared for equality. Use
     * {@link #exact(String, String)} to compare a string for equality instead.
     */
    class QueryFilter {
        private String field;
        private Object filter;
        private boolean exact;

        public QueryFilter(String field, Object filter) {
            this.field = field;
            this.filter = filter;
        }

        /**
         * The field equals {@code value}, character for character - not a pattern.
         * <p>
         * An escaped, anchored regex is not the same thing: MongoDB's {@code $} also
         * matches before a final newline, so {@code ^name$} selects {@code "name\n"}
         * too. Nor is there one strict end anchor for both backends - MongoDB's is
         * {@code \z}, which PostgreSQL rejects, and PostgreSQL's is {@code \Z}, which
         * MongoDB treats like {@code $}. Equality is exact on both, and can use an
         * index.
         */
        public static QueryFilter exact(String field, String value) {
            QueryFilter queryFilter = new QueryFilter(field, value);
            queryFilter.exact = true;
            return queryFilter;
        }

        public boolean isExact() {
            return exact;
        }

        public String getField() {
            return field;
        }

        public Object getFilter() {
            return filter;
        }
    }
}
