/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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
    public WorkflowStoreClientLibrary(IWorkflowStoreService workflowStoreService, IComponentCache componentCache,
            @LifecycleExtensions Map<String, Provider<ILifecycleTask>> lifecycleExtensionsProvider) {
        this.workflowStoreService = workflowStoreService;
        this.lifecycleExtensionsProvider = lifecycleExtensionsProvider;

        this.componentCache = componentCache;
    }

    @Override
    public IExecutableWorkflow getExecutableWorkflow(final String workflowId, final Integer workflowVersion) throws ServiceException {
        try {
            DocumentDescriptor workflowDocumentDescriptor = workflowStoreService.getWorkflowDocumentDescriptor(workflowId, workflowVersion);
            WorkflowConfiguration knowledgeWorkflow = workflowStoreService.getKnowledgeWorkflow(workflowId, workflowVersion);
            return createExecutableWorkflow(workflowDocumentDescriptor, knowledgeWorkflow);
        } catch (WorkflowInitializationException e) {
            throw new ServiceException("Error while creating executableWorkflow!", e);
        } catch (WorkflowConfigurationException e) {
            throw new ServiceException("Error while configuring executableWorkflow!", e);
        }
    }

    private IExecutableWorkflow createExecutableWorkflow(final DocumentDescriptor documentDescriptor,
                                                         final WorkflowConfiguration workflowConfiguration)
            throws WorkflowInitializationException, WorkflowConfigurationException {

        final var workflowId = extractResourceId(documentDescriptor.getResource());
        final var lifecycleManager = new LifecycleManager(componentCache, workflowId);

        try {
            List<WorkflowConfiguration.WorkflowStep> workflowSteps = workflowConfiguration.getWorkflowSteps();
            for (int indexInWorkflow = 0; indexInWorkflow < workflowSteps.size(); indexInWorkflow++) {
                WorkflowConfiguration.WorkflowStep workflowStep = workflowSteps.get(indexInWorkflow);
                URI extensionType = workflowStep.getType();
                if (URI_SCHEME_ID.equals(extensionType.getScheme())) {
                    String type = extensionType.getHost();
                    if (!lifecycleExtensionsProvider.containsKey(type)) {
                        throw new UnrecognizedExtensionException(String.format("Extension '%s' not found", type));
                    }

                    var componentKey = createComponentKey(workflowId.getId(), workflowId.getVersion(), indexInWorkflow);
                    var lifecycleTask = lifecycleExtensionsProvider.get(type).get();
                    var component = lifecycleTask.configure(workflowStep.getConfig(), workflowStep.getExtensions());

                    if (component != null) {
                        componentCache.put(lifecycleTask.getId(), componentKey, component);
                    }
                    lifecycleManager.addLifecycleTask(lifecycleTask);
                }
            }
        } catch (IllegalExtensionConfigurationException | UnrecognizedExtensionException e) {
            throw new WorkflowInitializationException(e.getMessage(), e);
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
            public String getWorkflowId() {
                return RestUtilities.extractResourceId(documentDescriptor.getResource()).getId();
            }

            @Override
            public ILifecycleManager getLifecycleManager() {
                return lifecycleManager;
            }
        };
    }

    public static class WorkflowInitializationException extends Exception {
        WorkflowInitializationException(String message, Throwable e) {
            super(message, e);
        }
    }
}
