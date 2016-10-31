package io.sls.persistence.impl.output.mongo;

import com.mongodb.DB;
import io.sls.persistence.impl.ResultManipulator;
import io.sls.persistence.impl.mongo.HistorizedResourceStore;
import io.sls.persistence.impl.mongo.MongoResourceStorage;
import io.sls.resources.rest.output.IOutputStore;
import io.sls.resources.rest.output.model.OutputConfiguration;
import io.sls.resources.rest.output.model.OutputConfigurationSet;
import io.sls.serialization.IDocumentBuilder;
import io.sls.utilities.RuntimeUtilities;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class OutputStore implements IOutputStore {
    private final String collectionName = "outputs";
    private HistorizedResourceStore<OutputConfigurationSet> outputResourceStore;
    private static final OutputComparator OUTPUT_COMPARATOR = new OutputComparator();

    @Inject
    public OutputStore(DB database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        MongoResourceStorage<OutputConfigurationSet> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, OutputConfigurationSet.class);


        this.outputResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public IResourceId create(OutputConfigurationSet outputConfigurationSet) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(outputConfigurationSet.getOutputs(), "outputs");
        return outputResourceStore.create(outputConfigurationSet);
    }

    @Override
    public OutputConfigurationSet read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return outputResourceStore.read(id, version);
    }

    @Override
    public OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit) throws ResourceNotFoundException, ResourceStoreException {
        RuntimeUtilities.checkNotNull(filter, "filter");
        RuntimeUtilities.checkNotNull(order, "order");
        RuntimeUtilities.checkNotNull(index, "index");
        RuntimeUtilities.checkNotNull(limit, "limit");

        OutputConfigurationSet outputConfigurationSet = outputResourceStore.read(id, version);

        ResultManipulator<OutputConfiguration> outputManipulator;
        outputManipulator = new ResultManipulator<>(outputConfigurationSet.getOutputs(), OutputConfiguration.class);

        try {
            outputManipulator.filterEntities(filter);
        } catch (ResultManipulator.FilterEntriesException e) {
            throw new ResourceStoreException(e.getLocalizedMessage(), e);
        }

        outputManipulator.sortEntities(OUTPUT_COMPARATOR, order);
        outputManipulator.limitEntities(index, limit);

        return outputConfigurationSet;
    }

    @Override
    public List<String> readOutputKeys(String id, Integer version, String filter, String order, Integer limit) throws ResourceStoreException, ResourceNotFoundException {
        List<String> retOutputKeys = new LinkedList<>();
        OutputConfigurationSet outputSet = read(id, version);
        List<OutputConfiguration> outputs = outputSet.getOutputs();
        for (OutputConfiguration output : outputs) {
            String key = output.getKey();
            if (key.contains(filter)) {
                retOutputKeys.add(key);
                if (retOutputKeys.size() >= limit) {
                    break;
                }
            }
        }

        if ("asc".equals(order)) {
            Collections.sort(retOutputKeys);
        } else {
            Collections.sort(retOutputKeys, Collections.reverseOrder());
        }

        return retOutputKeys;
    }

    @Override
    public Integer update(String id, Integer version, OutputConfigurationSet outputConfigurationSet) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(outputConfigurationSet.getOutputs(), "outputs");
        return outputResourceStore.update(id, version, outputConfigurationSet);
    }

    @Override
    public void delete(String id, Integer version) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        outputResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        outputResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return outputResourceStore.getCurrentResourceId(id);
    }

    private static class OutputComparator implements Comparator<OutputConfiguration> {
        @Override
        public int compare(OutputConfiguration o1, OutputConfiguration o2) {
            return o1.getKey().compareTo(o2.getKey());
        }
    }
}
