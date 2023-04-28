package ai.labs.eddi.configs.output.mongo;

import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.mongo.HistorizedResourceStore;
import ai.labs.eddi.datastore.mongo.MongoResourceStorage;
import ai.labs.eddi.datastore.mongo.ResultManipulator;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import com.mongodb.reactivestreams.client.MongoDatabase;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.*;

/**
 * @author ginccc
 */
@ApplicationScoped
public class OutputStore implements IOutputStore {
    private HistorizedResourceStore<OutputConfigurationSet> outputResourceStore;
    private static final OutputComparator OUTPUT_COMPARATOR = new OutputComparator();

    @Inject
    public OutputStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        checkNotNull(database, "database");
        final String collectionName = "outputs";
        MongoResourceStorage<OutputConfigurationSet> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, OutputConfigurationSet.class);


        this.outputResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public OutputConfigurationSet readIncludingDeleted(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return outputResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceStore.IResourceId create(OutputConfigurationSet outputConfigurationSet) throws IResourceStore.ResourceStoreException {
        checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
        return outputResourceStore.create(outputConfigurationSet);
    }

    @Override
    public OutputConfigurationSet read(String id, Integer version) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return outputResourceStore.read(id, version);
    }

    @Override
    public OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        OutputConfigurationSet outputConfigurationSet = outputResourceStore.read(id, version);

        ResultManipulator<OutputConfiguration> outputManipulator;
        outputManipulator = new ResultManipulator<>(outputConfigurationSet.getOutputSet(), OutputConfiguration.class);

        try {
            if (!isNullOrEmpty(filter)) {
                outputManipulator.filterEntities(filter);
            }
        } catch (ResultManipulator.FilterEntriesException e) {
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }

        if (!isNullOrEmpty(order)) {
            outputManipulator.sortEntities(OUTPUT_COMPARATOR, order);
        }
        if (!isNullOrEmpty(index) && !isNullOrEmpty(limit)) {
            outputManipulator.limitEntities(index, limit);
        }

        return outputConfigurationSet;
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        List<String> actions = read(id, version).
                getOutputSet().stream().
                map(OutputConfiguration::getAction).
                collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, OutputConfigurationSet outputConfigurationSet)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
        return outputResourceStore.update(id, version, outputConfigurationSet);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {
        outputResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        outputResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return outputResourceStore.getCurrentResourceId(id);
    }

    private static class OutputComparator implements Comparator<OutputConfiguration> {
        @Override
        public int compare(OutputConfiguration o1, OutputConfiguration o2) {
            return o1.getAction().compareTo(o2.getAction());
        }
    }
}
