package io.sls.core.runtime.client.packages;

import io.sls.core.lifecycle.*;
import io.sls.core.runtime.IExecutablePackage;
import io.sls.core.runtime.service.IPackageStoreService;
import io.sls.core.runtime.service.ServiceException;
import io.sls.resources.rest.documentdescriptor.model.DocumentDescriptor;
import io.sls.resources.rest.packages.model.PackageConfiguration;

import javax.inject.Inject;
import javax.inject.Provider;
import java.net.URI;
import java.util.Map;

/**
 * User: jarisch
 * Date: 23.06.12
 * Time: 19:57
 */
public class PackageStoreClientLibrary implements IPackageStoreClientLibrary {
    private final IPackageStoreService packageStoreService;
    private final Provider<Map<String, ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String CORE = "core";

    @Inject
    public PackageStoreClientLibrary(IPackageStoreService packageStoreService,
                                     Provider<Map<String, ILifecycleTask>> lifecycleExtensionsProvider) {
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
            Map<String, ILifecycleTask> lifecycleExtensions = lifecycleExtensionsProvider.get();
            for (PackageConfiguration.PackageExtension packageExtension : packageConfiguration.getPackageExtensions()) {
                URI type = packageExtension.getType();
                if (CORE.equals(type.getScheme())) {
                    ILifecycleTask lifecycleTask = lifecycleExtensions.get(type.getHost());
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
