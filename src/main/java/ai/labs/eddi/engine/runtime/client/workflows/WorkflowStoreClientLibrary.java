package ai.labs.eddi.engine.runtime.client.workflows;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.lifecycle.IComponentCache;
import ai.labs.eddi.engine.lifecycle.ILifecycleManager;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.bootstrap.LifecycleExtensions;
import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.lifecycle.exceptions.UnrecognizedExtensionException;
import ai.labs.eddi.engine.lifecycle.internal.LifecycleManager;
import ai.labs.eddi.engine.runtime.IExecutableWorkflow;
import ai.labs.eddi.engine.runtime.service.IWorkflowStoreService;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
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
public class WorkflowStoreClientLibrary implements IWorkflowStoreClientLibrary {
    private final IWorkflowStoreService workflowStoreService;
    private final Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider;
    private static final String URI_SCHEME_ID = "eddi";
    private final IComponentCache componentCache;

    @Inject
    public WorkflowStoreClientLibrary(IWorkflowStoreService workflowStoreService,
                                     IComponentCache componentCache,
                                     @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.workflowStoreService = workflowStoreService;
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;

        this.componentCache = componentCache;
    }

    @Override
    public IExecutableWorkflow getExecutableWorkflow(final String packageId, final Integer packageVersion) throws ServiceException {
        try {
            DocumentDescriptor packageDocumentDescriptor = workflowStoreService.getPackageDocumentDescriptor(packageId, packageVersion);
            WorkflowConfiguration knowledgePackage = workflowStoreService.getKnowledgePackage(packageId, packageVersion);
            return createExecutablePackage(packageDocumentDescriptor, knowledgePackage);
        } catch (PackageInitializationException e) {
            throw new ServiceException("Error while creating executableWorkflow!", e);
        } catch (WorkflowConfigurationException e) {
            throw new ServiceException("Error while configuring executableWorkflow!", e);
        }
    }

    private IExecutableWorkflow createExecutablePackage(final DocumentDescriptor documentDescriptor,
                                                       final WorkflowConfiguration workflowConfiguration)
            throws PackageInitializationException, WorkflowConfigurationException {

        final var packageId = extractResourceId(documentDescriptor.getResource());
        final var lifecycleManager = new LifecycleManager(componentCache, packageId);

        try {
            List<WorkflowConfiguration.WorkflowStep> workflowSteps = workflowConfiguration.getWorkflowSteps();
            for (int indexInPackage = 0; indexInPackage < workflowSteps.size(); indexInPackage++) {
                WorkflowConfiguration.WorkflowStep workflowStep = workflowSteps.get(indexInPackage);
                URI extensionType = workflowStep.getType();
                if (URI_SCHEME_ID.equals(extensionType.getScheme())) {
                    String type = extensionType.getHost();
                    if (!lifecycleExtensionsProvider.containsKey(type)) {
                        throw new UnrecognizedExtensionException(String.format("Extension '%s' not found", type));
                    }

                    var componentKey = createComponentKey(packageId.getId(), packageId.getVersion(), indexInPackage);
                    var lifecycleTask = lifecycleExtensionsProvider.get(type).get();
                    var component = lifecycleTask.
                            configure(workflowStep.getConfig(), workflowStep.getExtensions());

                    if (component != null) {
                        componentCache.put(type, componentKey, component);
                    }
                    lifecycleManager.addLifecycleTask(lifecycleTask);
                }
            }
        } catch (IllegalExtensionConfigurationException | UnrecognizedExtensionException e) {
            throw new PackageInitializationException(e.getMessage(), e);
        }

        return new IExecutableWorkflow() {
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

    public static class PackageInitializationException extends Exception {
        PackageInitializationException(String message, Throwable e) {
            super(message, e);
        }
    }
}
