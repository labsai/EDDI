package ai.labs.runtime.client.packages;

import ai.labs.lifecycle.*;
import ai.labs.resources.rest.documentdescriptor.model.DocumentDescriptor;
import ai.labs.resources.rest.packages.model.PackageConfiguration;
import ai.labs.runtime.IExecutablePackage;
import ai.labs.runtime.service.IPackageStoreService;
import ai.labs.runtime.service.ServiceException;

import javax.inject.Inject;
import javax.inject.Provider;
import java.net.URI;
import java.util.Map;

/**
 * @author ginccc
 */
public class PackageStoreClientLibrary implements IPackageStoreClientLibrary {
    private final IPackageStoreService packageStoreService;
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String URI_SCHEME_ID = "eddi";

    @Inject
    public PackageStoreClientLibrary(IPackageStoreService packageStoreService,
                                     Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.packageStoreService = packageStoreService;
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;
    }

    @Override
    public IExecutablePackage getExecutablePackage(final String packageId, final Integer packageVersion) throws ServiceException {
        try {
            DocumentDescriptor packageDocumentDescriptor = packageStoreService.getPackageDocumentDescriptor(packageId, packageVersion);
            PackageConfiguration knowledgePackage = packageStoreService.getKnowledgePackage(packageId, packageVersion);
            return createExecutablePackage(packageDocumentDescriptor, knowledgePackage);
        } catch (PackageInitializationException e) {
            throw new ServiceException("Error while creating ExecuablePackage!", e);
        } catch (PackageConfigurationException e) {
            throw new ServiceException("Error while configuring ExecuablePackage!", e);
        }
    }

    private IExecutablePackage createExecutablePackage(final DocumentDescriptor documentDescriptor, final PackageConfiguration packageConfiguration) throws PackageInitializationException, PackageConfigurationException {
        final ILifecycleManager lifecycleManager = new LifecycleManager();

        try {
            for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
                URI type = packageExtension.getType();
                if (URI_SCHEME_ID.equals(type.getScheme())) {
                    ILifecycleTask lifecycleTask = lifecycleExtensionsProvider.get(type.getHost()).get();
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
            public String getContext() {
                return documentDescriptor.getName();
            }

            @Override
            public ILifecycleManager getLifecycleManager() {
                return lifecycleManager;
            }
        };
    }

    public class PackageInitializationException extends Exception {
        public PackageInitializationException(String message, Throwable e) {
            super(message, e);
        }
    }
}
