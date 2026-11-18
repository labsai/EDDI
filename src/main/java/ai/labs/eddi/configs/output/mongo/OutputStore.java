/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.output.mongo;

import ai.labs.eddi.configs.output.IOutputStore;
import ai.labs.eddi.configs.output.model.OutputConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.datastore.AbstractResourceStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.mongo.ResultManipulator;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;

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
public class OutputStore extends AbstractResourceStore<OutputConfigurationSet> implements IOutputStore {

    private static final OutputComparator OUTPUT_COMPARATOR = new OutputComparator();

    @Inject
    public OutputStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        super(storageFactory, "outputs", documentBuilder, OutputConfigurationSet.class);
    }

    @Override
    public IResourceStore.IResourceId create(OutputConfigurationSet outputConfigurationSet) throws IResourceStore.ResourceStoreException {
        checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
        requireValidTimesOccurred(outputConfigurationSet);
        return super.create(outputConfigurationSet);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, OutputConfigurationSet outputConfigurationSet)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException, IResourceStore.ResourceNotFoundException {

        checkCollectionNoNullElements(outputConfigurationSet.getOutputSet(), "outputSets");
        requireValidTimesOccurred(outputConfigurationSet);
        return super.update(id, version, outputConfigurationSet);
    }

    /**
     * {@code timesOccurred} counts from 0 (the first time an action occurred); -1
     * is accepted as the lowest. A smaller value can never be selected — the output
     * generation used to loop forever on an action whose entries were all below -1
     * — so it is refused on save, naming the entry.
     */
    private static void requireValidTimesOccurred(OutputConfigurationSet outputConfigurationSet) {
        var outputSet = outputConfigurationSet.getOutputSet();
        if (outputSet == null) {
            return;
        }
        for (int i = 0; i < outputSet.size(); i++) {
            var entry = outputSet.get(i);
            if (entry != null && entry.getTimesOccurred() < -1) {
                throw new IllegalArgumentException("Invalid output set: outputSet[" + i + "] (action '" + entry.getAction()
                        + "') has timesOccurred " + entry.getTimesOccurred() + "; it must not be below -1 (0 is the first time the action occurs).");
            }
        }
    }

    @Override
    public OutputConfigurationSet read(String id, Integer version, String filter, String order, Integer index, Integer limit)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {

        OutputConfigurationSet outputConfigurationSet = read(id, version);

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

        List<String> actions = read(id, version).getOutputSet().stream().map(OutputConfiguration::getAction).collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, Math.min(limit, actions.size())) : actions;
    }

    private static class OutputComparator implements Comparator<OutputConfiguration> {
        @Override
        public int compare(OutputConfiguration o1, OutputConfiguration o2) {
            return o1.getAction().compareTo(o2.getAction());
        }
    }
}
