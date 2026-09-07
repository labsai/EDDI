/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.StringUtilities;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Database-agnostic descriptor store. Uses {@link IResourceStorageFactory} to
 * obtain the underlying storage, and {@link IResourceStorage#findResources} for
 * filter/pagination queries.
 * <p>
 * <b>Collection sharing.</b> All descriptor types default to the single
 * {@value #COLLECTION_DESCRIPTORS} collection — config descriptors and one row
 * per conversation alike — so listing agents pages through a collection whose
 * size tracks conversation volume. The collection name is a constructor
 * argument so a caller can move a descriptor type onto its own collection;
 * doing so needs a data migration for existing deployments (rows already
 * written to {@value #COLLECTION_DESCRIPTORS} would otherwise become
 * invisible), which is why the default is unchanged.
 *
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    private static final Logger LOGGER = Logger.getLogger(DescriptorStore.class);

    /**
     * Default collection/table for descriptors.
     * <p>
     * Every descriptor type shares it today — including one row per conversation —
     * which is why {@link #INDEXED_FIELDS} matters so much here: without those
     * indexes, listing agents scans a collection that grows with conversation
     * volume. The collection name is a constructor argument so descriptor types can
     * be split apart; see the class javadoc.
     */
    public static final String COLLECTION_DESCRIPTORS = "descriptors";

    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_AGENT_NAME = "agentName";
    private static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_ORIGIN_ID = "originId";

    /**
     * The materialised access-control index on {@code DocumentDescriptor}, which
     * every owner-filtered listing ANDs a predicate on.
     *
     * <h3>What this index does and does not buy</h3> Declared here so both backends
     * create it, and so a future exact-match or prefix query on the field is
     * served. It does <b>not</b> make the current access predicate index-backed:
     * that predicate is an unanchored regex ({@code |token|} has to match
     * mid-string), and neither MongoDB nor PostgreSQL can use a btree index for
     * one. The scoped listing is therefore a scan.
     * <p>
     * That is not a regression — the type predicate this store has always applied
     * ({@code "eddi://" + type + ".*"}) is a regex scan too, so the access group
     * adds predicates to a scan rather than turning an indexed lookup into one —
     * but it is a real ceiling on a collection shared with conversation
     * descriptors. Making it index-backed means storing the tokens as an
     * <em>array</em> and querying with {@code $in} / a GIN index, which needs
     * {@link IResourceFilter} to grow an operator beyond "string means regex".
     * Worth doing; deliberately not done blind, since the PostgreSQL half cannot be
     * verified without a PostgreSQL to run it on.
     */
    public static final String FIELD_ACCESS_INDEX = "accessIndex";

    /**
     * Every field this store filters or sorts on. Passed to the storage factory so
     * BOTH backends index them — MongoDB as ascending single-field indexes,
     * PostgreSQL as expression indexes on the shared {@code resources} table.
     * <p>
     * These were declared by the (now dead) MongoDB-specific descriptor store and
     * were lost when the DB-agnostic store replaced it, leaving every descriptor
     * listing as a full scan with a regex and a text sort on top.
     */
    private static final String[] INDEXED_FIELDS = {FIELD_RESOURCE, FIELD_USER_ID, FIELD_NAME, FIELD_AGENT_NAME, FIELD_DESCRIPTION,
            FIELD_LAST_MODIFIED, FIELD_DELETED, FIELD_ORIGIN_ID, FIELD_ACCESS_INDEX};

    private final ModifiableHistorizedResourceStore<T> descriptorResourceStore;
    private final IResourceStorage<T> resourceStorage;

    public DescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, Class<T> documentType) {
        this(storageFactory, documentBuilder, documentType, COLLECTION_DESCRIPTORS);
    }

    public DescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, Class<T> documentType, String collectionName) {
        this.resourceStorage = storageFactory.create(collectionName, documentBuilder, documentType, INDEXED_FIELDS);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return readDescriptors(type, filter, index, limit, includeDeleted, null);
    }

    /**
     * As {@link #readDescriptors(String, String, Integer, Integer, boolean)}, with
     * an extra group of filters ANDed into the query.
     * <p>
     * Used to restrict a listing to what the caller may see. The restriction is
     * applied <em>in the query</em>, not to the returned page: filtering afterwards
     * would return short pages and force the kind of scan-budgeted back-fill
     * {@code RestConversationStore} has to do for conversations, where no such
     * predicate exists.
     * <p>
     * The parameter is a raw {@code QueryFilters} rather than the
     * {@code AccessScope} that produces it, so this package keeps knowing nothing
     * about the security model — {@code DocumentDescriptorStore} does the
     * conversion.
     *
     * @param accessRestriction
     *            an additional filter group, or {@code null} for no restriction
     */
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted,
                                   IResourceFilter.QueryFilters accessRestriction)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return readDescriptors(type, filter, index, limit, includeDeleted, accessRestriction, null);
    }

    /**
     * As above, with a further AND-ed group — used to narrow a listing to one
     * space.
     *
     * @param extraRestriction
     *            an additional filter group, or {@code null}
     */
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted,
                                   IResourceFilter.QueryFilters accessRestriction, IResourceFilter.QueryFilters extraRestriction)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<IResourceFilter.QueryFilter> queryFiltersRequired = new LinkedList<>();
        String filterURI = "eddi://" + type + ".*";
        queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filterURI));
        // includeDeleted is an INCLUSION flag, not an equality filter: true means "do
        // not constrain on `deleted` at all" (live AND soft-deleted), false means live
        // only. It previously added eq(deleted, includeDeleted), so includeDeleted=true
        // matched ONLY soft-deleted descriptors — making a scan and a purge that
        // differed on the flag operate on disjoint sets.
        if (!includeDeleted) {
            queryFiltersRequired.add(new IResourceFilter.QueryFilter(FIELD_DELETED, false));
        }
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFiltersRequired);

        List<IResourceFilter.QueryFilter> queryFiltersOptional = new LinkedList<>();
        if (filter != null) {
            filter = StringUtilities.convertToSearchString(filter);
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_USER_ID, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_AGENT_NAME, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_DESCRIPTION, filter));
            queryFiltersOptional.add(new IResourceFilter.QueryFilter(FIELD_RESOURCE, filter));
        }

        int effectiveLimit = IDescriptorStore.resolveDescriptorLimit(limit);
        int skip;
        if (index != null && index > 0) {
            long skipLong = (long) index * effectiveLimit;
            skip = (int) Math.min(skipLong, Integer.MAX_VALUE);
        } else {
            skip = 0;
        }

        // Groups are always ANDed together, so appending the access restriction as its
        // own group narrows the result without disturbing how the optional text filter
        // ORs within itself.
        List<IResourceFilter.QueryFilters> filterGroups = new LinkedList<>();
        filterGroups.add(required);
        if (!queryFiltersOptional.isEmpty()) {
            filterGroups.add(new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR, queryFiltersOptional));
        }
        if (accessRestriction != null && !accessRestriction.getQueryFilters().isEmpty()) {
            filterGroups.add(accessRestriction);
        }
        if (extraRestriction != null && !extraRestriction.getQueryFilters().isEmpty()) {
            filterGroups.add(extraRestriction);
        }
        IResourceFilter.QueryFilters[] allFilters = filterGroups.toArray(new IResourceFilter.QueryFilters[0]);

        // Use the storage-level findResources for database-agnostic querying
        List<IResourceStore.IResourceId> matchingIds = resourceStorage.findResources(allFilters, FIELD_LAST_MODIFIED, skip, effectiveLimit);

        if (matchingIds.size() >= IResourceStorage.MAX_RESULT_LIMIT) {
            // Never truncate silently — a short list that looks complete is
            // exactly the bug the explicit NO_LIMIT contract exists to prevent.
            // `type` reaches this method from a @QueryParam, so it is sanitized
            // before it is logged (CWE-117).
            LOGGER.warnv("Descriptor query for type ''{0}'' hit the internal {1}-result ceiling — the returned list is "
                    + "INCOMPLETE and features reading this type will silently miss entries. This query needs to page.",
                    sanitize(type), IResourceStorage.MAX_RESULT_LIMIT);
        }

        return readAll(matchingIds);
    }

    /**
     * Materialise the matched ids in one batch round trip.
     * <p>
     * This used to issue one {@code read()} per id — up to
     * {@link IResourceStorage#MAX_RESULT_LIMIT} of them for a single listing. Every
     * id here came out of a query against the CURRENT collection at its current
     * version, so a plain batch read of current rows returns the same documents the
     * per-id reads did.
     */
    private List<T> readAll(List<IResourceStore.IResourceId> resourceIds) throws IResourceStore.ResourceStoreException {
        List<T> ret = new LinkedList<>();
        if (resourceIds.isEmpty()) {
            return ret;
        }
        for (IResourceStorage.IResource<T> resource : resourceStorage.readMany(resourceIds)) {
            try {
                ret.add(resource.getData());
            } catch (IOException e) {
                String message = String.format("Unable to deserialize descriptor (id=%s, version=%s)", resource.getId(), resource.getVersion());
                throw new IResourceStore.ResourceStoreException(message, e);
            }
        }
        return ret;
    }

    @Override
    public T readDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.read(resourceId, version);
    }

    @Override
    public T readDescriptorWithHistory(String resourceId, Integer version)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.readIncludingDeleted(resourceId, version);
    }

    @Override
    public Integer updateDescriptor(String resourceId, Integer version, T documentDescriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.update(resourceId, version, documentDescriptor);
    }

    @Override
    public void setDescriptor(String resourceId, Integer version, T documentDescriptor)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        descriptorResourceStore.set(resourceId, version, documentDescriptor);
    }

    @Override
    public void createDescriptor(String resourceId, Integer version, T documentDescriptor) throws IResourceStore.ResourceStoreException {
        descriptorResourceStore.createNew(resourceId, version, documentDescriptor);
    }

    @Override
    public void deleteDescriptor(String resourceId, Integer version)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceModifiedException {
        descriptorResourceStore.delete(resourceId, version);
    }

    @Override
    public void deleteAllDescriptor(String resourceId) {
        descriptorResourceStore.deleteAllPermanently(resourceId);
    }

    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return descriptorResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<T> findByOriginId(String originId) throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<IResourceFilter.QueryFilter> queryFilters = new LinkedList<>();
        queryFilters.add(new IResourceFilter.QueryFilter(FIELD_ORIGIN_ID, originId));
        queryFilters.add(new IResourceFilter.QueryFilter(FIELD_DELETED, false));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFilters);

        List<IResourceStore.IResourceId> matchingIds = resourceStorage.findResources(new IResourceFilter.QueryFilters[]{required},
                FIELD_LAST_MODIFIED, 0, 10);

        return readAll(matchingIds);
    }
}
