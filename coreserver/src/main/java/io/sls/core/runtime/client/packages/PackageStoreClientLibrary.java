package io.sls.core.runtime.client.packages;

import io.sls.core.lifecycle.ILifecycleManager;
import io.sls.core.lifecycle.LifecycleManager;
import io.sls.core.runtime.IExecutablePackage;
import io.sls.core.runtime.service.IPackageStoreService;
import io.sls.core.runtime.service.ServiceException;
import io.sls.lifecycle.ILifecycleTask;
import io.sls.lifecycle.IllegalExtensionConfigurationException;
import io.sls.lifecycle.PackageConfigurationException;
import io.sls.lifecycle.UnrecognizedExtensionException;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.model.PackageConfiguration;

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
    private static final String CORE = "core";

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
                if (CORE.equals(type.getScheme())) {
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
