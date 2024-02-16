package ai.labs.eddi.configs.packages.rest;

import ai.labs.eddi.configs.packages.IRestPackageExtensionStore;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.configs.packages.model.ExtensionDescriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class RestPackageExtensionStore implements IRestPackageExtensionStore {
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;

    @Inject
    public RestPackageExtensionStore(
            @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {

        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public List<ExtensionDescriptor> getPackageExtensions(String filter) {
        return lifecycleExtensionsProvider.keySet().stream().
                filter(type -> filter.isEmpty() || type.contains(filter)).
                map(type -> {
                    Provider<ILifecycleTask> taskProvider = lifecycleExtensionsProvider.get(type);
                    return taskProvider.get().getExtensionDescriptor();
                }).collect(Collectors.toList());
    }
}
