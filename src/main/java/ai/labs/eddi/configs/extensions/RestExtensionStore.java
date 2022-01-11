package ai.labs.eddi.configs.extensions;

import ai.labs.eddi.configs.extensions.model.ExtensionDescriptor;
import ai.labs.eddi.configs.packages.IRestPackageExtensionStore;
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
