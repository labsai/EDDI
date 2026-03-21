package ai.labs.eddi.configs.pipelines.rest;

import ai.labs.eddi.configs.pipelines.IRestPipelineStepStore;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.configs.pipelines.model.ExtensionDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.List;
import java.util.Map;
@ApplicationScoped
public class RestPipelineStepStore implements IRestPipelineStepStore {
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;

    @Inject
    public RestPipelineStepStore(
            @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {

        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public List<ExtensionDescriptor> getPipelineSteps(String filter) {
        return lifecycleExtensionsProvider.keySet().stream().
                filter(type -> filter.isEmpty() || type.contains(filter)).
                map(type -> {
                    Provider<ILifecycleTask> taskProvider = lifecycleExtensionsProvider.get(type);
                    return taskProvider.get().getExtensionDescriptor();
                }).toList();
    }
}
