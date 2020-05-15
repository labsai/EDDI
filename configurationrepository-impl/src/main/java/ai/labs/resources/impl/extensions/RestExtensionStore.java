package ai.labs.resources.impl.extensions;

import ai.labs.resources.rest.config.packages.IRestPackageExtensionStore;
import ai.labs.resources.rest.extensions.IRestExtensionStore;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class RestExtensionStore implements IRestExtensionStore {
    private final IRestPackageExtensionStore restExtensionStore;

    @Inject
    public RestExtensionStore(IRestPackageExtensionStore restExtensionStore) {
        this.restExtensionStore = restExtensionStore;
    }

    @Override
    public List<ExtensionDescriptor> readExtensionDescriptors(String filter) {
        return this.restExtensionStore.getPackageExtensions(filter);
    }
}
