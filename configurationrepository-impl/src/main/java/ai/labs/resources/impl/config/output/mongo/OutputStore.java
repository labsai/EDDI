package ai.labs.resources.impl.config.output.mongo;

import ai.labs.persistence.ResultManipulator;
import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.output.IOutputStore;
import ai.labs.resources.rest.config.output.model.OutputConfiguration;
import ai.labs.resources.rest.config.output.model.OutputConfigurationSet;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class OutputStore implements IOutputStore {
    private HistorizedResourceStore<OutputConfigurationSet> outputResourceStore;
    private static final OutputComparator OUTPUT_COMPARATOR = new OutputComparator();

    @Inject
    public OutputStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "outputs";
        MongoResourceStorage<OutputConfigurationSet> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, OutputConfigurationSet.class);


        this.outputResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public OutputConfigurationSet readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return outputResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(OutputConfigurationSet outputConfigurationSet) throws ResourceStoreException {
        RuntimeUtilities.checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
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
        outputManipulator = new ResultManipulator<>(outputConfigurationSet.getOutputSet(), OutputConfiguration.class);

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
    public List<String> readOutputActions(String id, Integer version, String filter, String order, Integer limit) throws ResourceStoreException, ResourceNotFoundException {
        List<String> retOutputKeys = new LinkedList<>();
        OutputConfigurationSet outputSet = read(id, version);
        List<OutputConfiguration> outputs = outputSet.getOutputSet();
        for (OutputConfiguration output : outputs) {
            String key = output.getAction();
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
            retOutputKeys.sort(Collections.reverseOrder());
        }

        return retOutputKeys;
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, OutputConfigurationSet outputConfigurationSet) throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {
        RuntimeUtilities.checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
        return outputResourceStore.update(id, version, outputConfigurationSet);
    }

    @Override
    @ConfigurationUpdate
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
            return o1.getAction().compareTo(o2.getAction());
        }
    }
}
