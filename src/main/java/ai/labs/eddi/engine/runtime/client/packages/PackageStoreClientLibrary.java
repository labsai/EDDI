package ai.labs.eddi.engine.runtime.client.packages;

import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.PackageConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.runtime.IExecutablePackage;
import ai.labs.eddi.engine.runtime.service.IPackageStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LifecycleUtilities.createComponentKey;
import static ai.labs.eddi.utils.RestUtilities.extractResourceId;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PackageStoreClientLibrary implements IPackageStoreClientLibrary {
    private final IPackageStoreService packageStoreService;
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String URI_SCHEME_ID = "eddi";
    private final IComponentCache componentCache;

    @Inject
    public PackageStoreClientLibrary(IPackageStoreService packageStoreService,
                                     IComponentCache componentCache,
                                     @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.packageStoreService = packageStoreService;
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;

        this.componentCache = componentCache;
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

    private IExecutablePackage createExecutablePackage(final DocumentDescriptor documentDescriptor,
                                                       final PackageConfiguration packageConfiguration)
            throws PackageInitializationException, PackageConfigurationException {

        final var packageId = extractResourceId(documentDescriptor.getResource());
        final var lifecycleManager = new LifecycleManager(componentCache, packageId);

        try {
            List<PackageConfiguration.PackageExtension> packageExtensions = packageConfiguration.getPackageExtensions();
            for (int indexInPackage = 0; indexInPackage < packageExtensions.size(); indexInPackage++) {
                PackageConfiguration.PackageExtension packageExtension = packageExtensions.get(indexInPackage);
                URI extensionType = packageExtension.getType();
                if (URI_SCHEME_ID.equals(extensionType.getScheme())) {
                    String type = extensionType.getHost();
                    if (!lifecycleExtensionsProvider.containsKey(type)) {
                        throw new UnrecognizedExtensionException(String.format("Extension '%s' not found", type));
                    }

                    var componentKey = createComponentKey(packageId.getId(), packageId.getVersion(), indexInPackage);
                    var lifecycleTask = lifecycleExtensionsProvider.get(type).get();
                    var component = lifecycleTask.
                            configure(packageExtension.getConfig(), packageExtension.getExtensions());

                    if (component != null) {
                        componentCache.put(type, componentKey, component);
                    }
                    lifecycleManager.addLifecycleTask(lifecycleTask);
                }
            }
        } catch (IllegalExtensionConfigurationException | UnrecognizedExtensionException e) {
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
                return RestUtilities.extractResourceId(documentDescriptor.getResource()).getId();
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
