package ai.labs.resources.impl.extensions;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.models.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.IRestExtensionStore;
import lombok.extern.slf4j.Slf4j;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Provider;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@Slf4j
@ApplicationScoped
public class RestExtensionStore implements IRestExtensionStore {
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;

    @Inject
    public RestExtensionStore(@LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public List<ExtensionDescriptor> readExtensionDescriptors(String filter) {
        return lifecycleExtensionsProvider.keySet().stream().filter(type -> filter.isEmpty() || type.contains(filter)).
                map(type -> {
                    Provider<ILifecycleTask> taskProvider = lifecycleExtensionsProvider.get(type);
                    return taskProvider.get().getExtensionDescriptor();
                }).collect(Collectors.toList());
    }
}
