/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.mongo;

import ai.labs.eddi.configs.ingestion.IRagIngestionSourceStore;
import ai.labs.eddi.configs.ingestion.model.RagIngestionSource;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB implementation of {@link IRagIngestionSourceStore}.
 * <p>
 * Stores ingestion source configurations in the {@code ingestionSources}
 * collection with full versioning support.
 */
@ApplicationScoped
public class MongoRagIngestionSourceStore extends AbstractResourceStore<RagIngestionSource>
        implements
            IRagIngestionSourceStore {

    private static final Logger LOGGER = Logger.getLogger(MongoRagIngestionSourceStore.class);

    @Inject
    public MongoRagIngestionSourceStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "ingestionSources", documentBuilder, RagIngestionSource.class, "name");
    }

    @Override
    public List<Map<String, Object>> findByRagConfigUri(String ragConfigUri, int index, int limit)
            throws IResourceStore.ResourceStoreException {
        var results = new ArrayList<Map<String, Object>>();
        try {
            var escaped = ragConfigUri.replace(".", "\\.").replace("?", "\\?");
            var filter = new IResourceFilter.QueryFilters(
                    List.of(new IResourceFilter.QueryFilter("ragConfigUri", "^" + escaped + "$")));
            var resourceIds = resourceStorage.findResources(
                    new IResourceFilter.QueryFilters[]{filter}, "name", index, limit);
            for (var resourceId : resourceIds) {
                try {
                    var resource = resourceStorage.read(resourceId.getId(), resourceId.getVersion());
                    if (resource != null) {
                        var data = resource.getData();
                        var map = new LinkedHashMap<String, Object>();
                        map.put("name", data.name());
                        map.put("description", data.description());
                        map.put("type", data.type());
                        map.put("sourceConfig", data.sourceConfig());
                        map.put("ragConfigUri", data.ragConfigUri());
                        map.put("ingestionSettings", data.ingestionSettings());
                        map.put("schedule", data.schedule());
                        map.put("resource", "eddi://ai.labs.rag/ragstore/ingestion-sources/"
                                + resourceId.getId() + "?version=" + resourceId.getVersion());
                        results.add(map);
                    }
                } catch (IOException e) {
                    LOGGER.warnf("Failed to read ingestion source %s: %s",
                            resourceId.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException(
                    "Failed to query by ragConfigUri: " + e.getMessage(), e);
        }
        return results;
    }
}
