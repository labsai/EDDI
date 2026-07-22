/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore;

import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.StringUtilities;
import org.jboss.logging.Logger;

import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Database-agnostic descriptor store. Uses {@link IResourceStorageFactory} to
 * obtain the underlying storage, and {@link IResourceStorage#findResources} for
 * filter/pagination queries.
 *
 * @author ginccc
 */
public class DescriptorStore<T> implements IDescriptorStore<T> {
    private static final Logger LOGGER = Logger.getLogger(DescriptorStore.class);

    private static final String FIELD_RESOURCE = "resource";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_AGENT_NAME = "agentName";
    private static final String FIELD_DESCRIPTION = "description";
    public static final String FIELD_LAST_MODIFIED = "lastModifiedOn";
    private static final String FIELD_DELETED = "deleted";
    private static final String FIELD_USER_ID = "userId";

    private final ModifiableHistorizedResourceStore<T> descriptorResourceStore;
    private final IResourceStorage<T> resourceStorage;

    public DescriptorStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder, Class<T> documentType) {
        this.resourceStorage = storageFactory.create("descriptors", documentBuilder, documentType);
        this.descriptorResourceStore = new ModifiableHistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public List<T> readDescriptors(String type, String filter, Integer index, Integer limit, boolean includeDeleted)
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

        IResourceFilter.QueryFilters[] allFilters;
        if (!queryFiltersOptional.isEmpty()) {
            IResourceFilter.QueryFilters optional = new IResourceFilter.QueryFilters(IResourceFilter.QueryFilters.ConnectingType.OR,
                    queryFiltersOptional);
            allFilters = new IResourceFilter.QueryFilters[]{required, optional};
        } else {
            allFilters = new IResourceFilter.QueryFilters[]{required};
        }

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

        // Read each matching resource
        List<T> ret = new LinkedList<>();
        for (IResourceStore.IResourceId resourceId : matchingIds) {
            ret.add(descriptorResourceStore.read(resourceId.getId(), resourceId.getVersion()));
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
        queryFilters.add(new IResourceFilter.QueryFilter("originId", originId));
        queryFilters.add(new IResourceFilter.QueryFilter(FIELD_DELETED, false));
        IResourceFilter.QueryFilters required = new IResourceFilter.QueryFilters(queryFilters);

        List<IResourceStore.IResourceId> matchingIds = resourceStorage.findResources(new IResourceFilter.QueryFilters[]{required},
                FIELD_LAST_MODIFIED, 0, 10);

        List<T> ret = new LinkedList<>();
        for (IResourceStore.IResourceId resourceId : matchingIds) {
            ret.add(descriptorResourceStore.read(resourceId.getId(), resourceId.getVersion()));
        }
        return ret;
    }
}
