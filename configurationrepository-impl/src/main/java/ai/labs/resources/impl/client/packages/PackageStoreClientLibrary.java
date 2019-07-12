package ai.labs.resources.impl.client.packages;

import ai.labs.exception.ServiceException;
import ai.labs.lifecycle.*;
import ai.labs.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.models.DocumentDescriptor;
import ai.labs.models.PackageConfiguration;
import ai.labs.resources.rest.config.packages.IRestPackageStore;
import ai.labs.resources.rest.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.client.packages.IPackageStoreClientLibrary;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Provider;
import java.net.URI;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageStoreClientLibrary implements IPackageStoreClientLibrary {
    private final IRestPackageStore restPackageStore;
    private final IRestDocumentDescriptorStore restDocumentDescriptorStore;
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String URI_SCHEME_ID = "eddi";

    @Inject
    public PackageStoreClientLibrary(IRestPackageStore restPackageStore,
                                     IRestDocumentDescriptorStore restDocumentDescriptorStore,
                                     @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.restPackageStore = restPackageStore;
        this.restDocumentDescriptorStore = restDocumentDescriptorStore;
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public IExecutablePackage getExecutablePackage(final String packageId, final Integer packageVersion) throws ServiceException {
        try {
            DocumentDescriptor packageDocumentDescriptor = restDocumentDescriptorStore.readDescriptor(packageId, packageVersion);
            PackageConfiguration knowledgePackage = restPackageStore.readPackage(packageId, packageVersion);
            return createExecutablePackage(packageDocumentDescriptor, knowledgePackage);
        } catch (PackageInitializationException e) {
            throw new ServiceException("Error while creating ExecutablePackage!", e);
        } catch (PackageConfigurationException e) {
            throw new ServiceException("Error while configuring ExecutablePackage!", e);
        }
    }

    private IExecutablePackage createExecutablePackage(final DocumentDescriptor documentDescriptor, final PackageConfiguration packageConfiguration) throws PackageInitializationException, PackageConfigurationException {
        final ILifecycleManager lifecycleManager = new LifecycleManager();

        try {
            for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
                URI type = packageExtension.getType();
                if (URI_SCHEME_ID.equals(type.getScheme())) {
                    String host = type.getHost();
                    if (!lifecycleExtensionsProvider.containsKey(host)) {
                        throw new UnrecognizedExtensionException(String.format("Extension '%s' not found", host));
                    }

                    ILifecycleTask lifecycleTask = lifecycleExtensionsProvider.get(host).get();
                    lifecycleTask.setExtensions(packageExtension.getExtensions());
                    lifecycleTask.configure(packageExtension.getConfig());
                    lifecycleTask.init();
                    lifecycleManager.addLifecycleTask(lifecycleTask);
                }
            }
        } catch (IllegalExtensionConfigurationException |
                UnrecognizedExtensionException e) {
            throw new PackageInitializationException(e.getMessage(), e);
        }

        return new IExecutablePackage() {
            @Override
            public String getName() {
                return documentDescriptor.getName();
            }

            @Override
            public String getDescription() {
                return documentDescriptor.getDescription();
            }

            @Override
            public ILifecycleManager getLifecycleManager() {
                return lifecycleManager;
            }
        };
    }

    public class PackageInitializationException extends Exception {
        PackageInitializationException(String message, Throwable e) {
            super(message, e);
        }
    }
}
