/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.workflows.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;

import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.configs.rest.RestVersionInfo;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.ResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.utils.RestUtilities;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.configs.descriptors.ResourceUtilities.*;
import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

@ApplicationScoped
public class RestWorkflowStore implements IRestWorkflowStore {
    private static final String KEY_CONFIG = "config";
    private static final String KEY_URI = "uri";
    private final IWorkflowStore workflowStore;
    private final ResourceClientLibrary resourceClientLibrary;
    private final IJsonSchemaCreator jsonSchemaCreator;
    private final RestVersionInfo<WorkflowConfiguration> restVersionInfo;
    private final IDocumentDescriptorStore documentDescriptorStore;

    private static final Logger log = Logger.getLogger(RestWorkflowStore.class);

    @Inject
    public RestWorkflowStore(IWorkflowStore workflowStore, ResourceClientLibrary resourceClientLibrary,
            IDocumentDescriptorStore documentDescriptorStore, IJsonSchemaCreator jsonSchemaCreator) {
        restVersionInfo = new RestVersionInfo<>(resourceURI, workflowStore, documentDescriptorStore);
        this.documentDescriptorStore = documentDescriptorStore;
        this.workflowStore = workflowStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.jsonSchemaCreator = jsonSchemaCreator;
    }

    @Override
    public Response readJsonSchema() {
        try {
            return Response.ok(jsonSchemaCreator.generateSchema(WorkflowConfiguration.class)).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public List<DocumentDescriptor> readWorkflowDescriptors(String filter, Integer index, Integer limit) {
        return restVersionInfo.readDescriptors("ai.labs.workflow", filter, index, limit);
    }

    @Override
    public List<DocumentDescriptor> readWorkflowDescriptors(String filter, Integer index, Integer limit, String containingResourceUri,
                                                            Boolean includePreviousVersions) {

        if (validateUri(containingResourceUri) == null) {
            return createMalFormattedResourceUriException(containingResourceUri);
        }

        try {
            return workflowStore.getWorkflowDescriptorsContainingResource(containingResourceUri, includePreviousVersions);
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public WorkflowConfiguration readWorkflow(String id, Integer version) {
        return restVersionInfo.read(id, version);
    }

    @Override
    public Response updateWorkflow(String id, Integer version, WorkflowConfiguration workflowConfiguration) {
        return restVersionInfo.update(id, version, workflowConfiguration);
    }

    @Override
    public Response updateResourceInWorkflow(String id, Integer version, URI resourceURI) {
        String resourceURIString = resourceURI.toString();
        String resourceURIWithoutVersion = resourceURIString.substring(0, resourceURIString.lastIndexOf("?"));

        boolean updated = false;
        WorkflowConfiguration workflowConfig = readWorkflow(id, version);
        for (WorkflowStep workflowStep : workflowConfig.getWorkflowSteps()) {
            Map<String, Object> workflowStepConfig = workflowStep.getConfig();
            if (updateResourceURI(resourceURI, resourceURIWithoutVersion, workflowStepConfig)) {
                updated = true;
            }

            Map<String, Object> extensions = workflowStep.getExtensions();
            for (String extensionKey : extensions.keySet()) {
                @SuppressWarnings("unchecked")
                var extensionElements = (List<Map<String, Object>>) extensions.get(extensionKey);
                for (Map<String, Object> extensionElement : extensionElements) {
                    if (extensionElement.containsKey(KEY_CONFIG)) {
                        @SuppressWarnings("unchecked")
                        var config = (Map<String, Object>) extensionElement.get(KEY_CONFIG);
                        if (updateResourceURI(resourceURI, resourceURIWithoutVersion, config)) {
                            updated = true;
                        }
                    }
                }
            }
        }

        if (updated) {
            return updateWorkflow(id, version, workflowConfig);
        } else {
            URI uri = RestUtilities.createURI(RestWorkflowStore.resourceURI, id, versionQueryParam, version);
            return Response.status(BAD_REQUEST).entity(uri).type(MediaType.TEXT_PLAIN).build();
        }
    }

    private boolean updateResourceURI(URI resourceURI, String resourceURIWithoutVersion, Map<String, Object> config) {
        if (config.containsKey(KEY_URI)) {
            Object uri = config.get(KEY_URI);
            if (uri.toString().startsWith(resourceURIWithoutVersion)) {
                // found resource URI to update
                config.put(KEY_URI, resourceURI);
                return true;
            }
        }

        return false;
    }

    @Override
    public Response createWorkflow(WorkflowConfiguration workflowConfiguration) {
        return restVersionInfo.create(workflowConfiguration);
    }

    @Override
    public Response deleteWorkflow(String id, Integer version, Boolean permanent, Boolean cascade) {
        if (cascade) {
            try {
                WorkflowConfiguration workflowConfig = workflowStore.read(id, version);
                for (var workflowStep : workflowConfig.getWorkflowSteps()) {
                    // Delete parser dictionaries
                    URI type = workflowStep.getType();
                    if (type != null && "ai.labs.parser".equals(type.getHost())) {
                        deleteParserDictionaries(workflowStep, permanent);
                    }

                    // Delete main extension resource (via config.uri)
                    Map<String, Object> config = workflowStep.getConfig();
                    if (!isNullOrEmpty(config)) {
                        Object resourceUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(resourceUriObj)) {
                            deleteResourceSafely(URI.create(resourceUriObj.toString()), permanent);
                        }
                    }
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                log.warnf("Workflow %s (v%d) not found for cascade — deleting workflow only", id, version);
            } catch (IResourceStore.ResourceStoreException e) {
                log.warnf("Error reading workflow %s for cascade: %s", id, e.getMessage());
            }
        }
        return restVersionInfo.delete(id, version, permanent);
    }

    @SuppressWarnings("unchecked")
    private void deleteParserDictionaries(WorkflowStep workflowStep, boolean permanent) {
        var dictionaries = (List<Map<String, Object>>) workflowStep.getExtensions().get("dictionaries");
        if (!isNullOrEmpty(dictionaries)) {
            for (var dictionary : dictionaries) {
                var dictType = dictionary.get("type");
                if (dictType != null && "ai.labs.parser.dictionaries.regular".equals(URI.create(dictType.toString()).getHost())) {
                    var config = (Map<String, Object>) dictionary.get("config");
                    if (!isNullOrEmpty(config)) {
                        Object dictionaryUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(dictionaryUriObj)) {
                            deleteResourceSafely(URI.create(dictionaryUriObj.toString()), permanent);
                        }
                    }
                }
            }
        }
    }

    private void deleteResourceSafely(URI resourceUri, boolean permanent) {
        try {
            // Check if this resource is referenced by other workflows
            var referencingWorkflows = workflowStore.getWorkflowDescriptorsContainingResource(resourceUri.toString(), false);
            if (referencingWorkflows.size() > 1) {
                log.infof("Skipping cascade-delete of resource %s — " + "still referenced by %d other workflow(s)", resourceUri,
                        referencingWorkflows.size() - 1);
                return;
            }

            resourceClientLibrary.deleteResource(resourceUri, permanent);
            log.infof("Cascade-deleted resource %s", resourceUri);
        } catch (Exception e) {
            log.warnf("Failed to cascade-delete resource %s: %s", resourceUri, e.getMessage());
        }
    }

    @Override
    public Response duplicateWorkflow(String id, Integer version, Boolean deepCopy) {
        restVersionInfo.validateParameters(id, version);
        try {
            WorkflowConfiguration workflowConfig = workflowStore.read(id, version);
            if (deepCopy) {
                for (var workflowStep : workflowConfig.getWorkflowSteps()) {
                    URI type = workflowStep.getType();
                    if ("ai.labs.parser".equals(type.getHost())) {
                        duplicateDictionaryInParser(workflowStep);
                    }

                    Map<String, Object> config = workflowStep.getConfig();
                    if (!isNullOrEmpty(config)) {
                        Object resourceUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(resourceUriObj)) {
                            var newResourceLocation = duplicateResource(resourceUriObj);
                            config.put(KEY_URI, newResourceLocation);
                        }
                    }
                }
            }

            // Use createDocument() — bypasses broken Response.getLocation() for eddi://
            // URIs
            IResourceStore.IResourceId resourceId = restVersionInfo.createDocument(workflowConfig);
            URI createdUri = RestUtilities.createURI(resourceURI, resourceId.getId(), versionQueryParam, resourceId.getVersion());
            createDocumentDescriptorForDuplicate(documentDescriptorStore, id, version, createdUri);

            return Response.created(createdUri).location(createdUri)
                    .header("X-Resource-URI", createdUri.toString())
                    .entity(createdUri.toString()).build();
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    private void duplicateDictionaryInParser(WorkflowStep workflowStep) throws ServiceException {
        URI type;
        @SuppressWarnings("unchecked")
        var dictionaries = (List<Map<String, Object>>) workflowStep.getExtensions().get("dictionaries");
        if (!isNullOrEmpty(dictionaries)) {
            for (var dictionary : dictionaries) {
                type = URI.create(dictionary.get("type").toString());
                if ("ai.labs.parser.dictionaries.regular".equals(type.getHost())) {
                    @SuppressWarnings("unchecked")
                    var config = (Map<String, URI>) dictionary.get("config");
                    if (!isNullOrEmpty(config)) {
                        Object dictionaryUriObj = config.get(KEY_URI);
                        if (!isNullOrEmpty(dictionaryUriObj)) {
                            var newDictionaryLocation = duplicateResource(dictionaryUriObj);
                            config.put(KEY_URI, newDictionaryLocation);
                        }
                    }
                }
            }
        }
    }

    private URI duplicateResource(Object resourceUriObj) throws ServiceException {
        URI newResourceLocation = null;

        try {
            if (!isNullOrEmpty(resourceUriObj)) {
                URI oldResourceUri = URI.create(resourceUriObj.toString());

                Response duplicateResourceResponse = resourceClientLibrary.duplicateResource(oldResourceUri);

                newResourceLocation = duplicateResourceResponse.getLocation();

                var oldResourceId = RestUtilities.extractResourceId(oldResourceUri);
                createDocumentDescriptorForDuplicate(documentDescriptorStore, oldResourceId.getId(), oldResourceId.getVersion(), newResourceLocation);
            }
        } catch (Exception e) {
            throw new ServiceException(e.getLocalizedMessage(), e);
        }

        if (isNullOrEmpty(newResourceLocation)) {
            String errorMsg = String.format("New resource for %s could not be created. " + "Mission Location Header.", resourceUriObj);
            throw new ServiceException(errorMsg);
        }

        return newResourceLocation;
    }

    @Override
    public String getResourceURI() {
        return restVersionInfo.getResourceURI();
    }

    @Override
    public IResourceStore.IResourceId getCurrentResourceId(String id) throws IResourceStore.ResourceNotFoundException {
        return workflowStore.getCurrentResourceId(id);
    }
}
