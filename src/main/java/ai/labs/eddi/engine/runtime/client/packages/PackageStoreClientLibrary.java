package ai.labs.eddi.engine.runtime.client.packages;

import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.engine.lifecycle.*;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.service.IPackageStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.engine.utilities.URIUtilities;
import ai.labs.eddi.models.DocumentDescriptor;

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
    private final IPackageStoreService packageStoreService;
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String URI_SCHEME_ID = "eddi";

    @Inject
    public PackageStoreClientLibrary(IPackageStoreService packageStoreService,
                                     @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
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
            public String getPackageId() {
                return URIUtilities.extractResourceId(documentDescriptor.getResource()).getId();
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
