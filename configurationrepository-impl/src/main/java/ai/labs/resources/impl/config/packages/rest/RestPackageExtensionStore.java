package ai.labs.resources.impl.config.packages.rest;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.resources.rest.config.packages.IRestPackageExtensionStore;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;

import javax.inject.Inject;
import javax.inject.Provider;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RestPackageExtensionStore implements IRestPackageExtensionStore {
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;

    @Inject
    public RestPackageExtensionStore(Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
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
