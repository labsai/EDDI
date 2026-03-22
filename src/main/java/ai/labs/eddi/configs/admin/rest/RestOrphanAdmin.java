package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.IRestOrphanAdmin;
import ai.labs.eddi.configs.admin.model.OrphanInfo;
import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Implementation of the orphan detection and cleanup endpoint.
 *
 * <p>Algorithm:</p>
 * <ol>
 *   <li>Enumerate all bots → collect referenced package URIs</li>
 *   <li>Enumerate all packages → collect referenced extension resource URIs</li>
 *   <li>For each store type, enumerate all resources via document descriptors</li>
 *   <li>Any resource whose URI is NOT in the referenced set = orphan</li>
 * </ol>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestOrphanAdmin implements IRestOrphanAdmin {

    private static final Logger log = Logger.getLogger(RestOrphanAdmin.class);

    /**
     * Store types to scan for orphans. Each entry is {descriptorType, descriptorTypeLabel}.
     * The descriptor type is used to query IDocumentDescriptorStore.readDescriptors().
     */
    private static final String[][] SCANNABLE_STORE_TYPES = {
            {"ai.labs.package", "Package"},
            {"ai.labs.behavior", "Behavior Set"},
            {"ai.labs.httpcalls", "HTTP Calls"},
            {"ai.labs.output", "Output Set"},
            {"ai.labs.langchain", "LangChain"},
            {"ai.labs.property", "Property Setter"},
            {"ai.labs.regulardictionary", "Regular Dictionary"},
            {"ai.labs.parser", "Parser"},
    };

    private static final int BATCH_SIZE = 200;

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IResourceClientLibrary resourceClientLibrary;

    @Inject
    public RestOrphanAdmin(IAgentStore agentStore,
                           IWorkflowStore workflowStore,
                           IDocumentDescriptorStore documentDescriptorStore,
                           IResourceClientLibrary resourceClientLibrary) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceClientLibrary = resourceClientLibrary;
    }

    @Override
    public OrphanReport scanOrphans(Boolean includeDeleted) {
        List<OrphanInfo> orphans = findOrphans(includeDeleted);
        return new OrphanReport(orphans.size(), 0, orphans);
    }

    @Override
    public OrphanReport purgeOrphans(Boolean includeDeleted) {
        List<OrphanInfo> orphans = findOrphans(includeDeleted);
        int deletedCount = 0;

        for (OrphanInfo orphan : orphans) {
            try {
                resourceClientLibrary.deleteResource(orphan.getResourceUri(), true);
                deletedCount++;
                log.infof("Purged orphan: %s [%s]", orphan.getResourceUri(), orphan.getType());
            } catch (Exception e) {
                log.warnf("Failed to purge orphan %s: %s", orphan.getResourceUri(), e.getMessage());
            }
        }

        log.infof("Orphan purge complete: %d/%d deleted", deletedCount, orphans.size());
        return new OrphanReport(orphans.size(), deletedCount, orphans);
    }

    private List<OrphanInfo> findOrphans(boolean includeDeleted) {
        // Step 1: Build the set of all referenced resource URIs
        Set<String> referencedUris = buildReferencedUrisSet();
        log.infof("Orphan scan: found %d referenced resource URIs", referencedUris.size());

        // Step 2: Scan all store types and find unreferenced resources
        List<OrphanInfo> orphans = new ArrayList<>();

        for (String[] storeType : SCANNABLE_STORE_TYPES) {
            String type = storeType[0];

            try {
                List<DocumentDescriptor> descriptors = readAllDescriptors(type, includeDeleted);
                for (DocumentDescriptor descriptor : descriptors) {
                    URI resourceUri = descriptor.getResource();
                    if (resourceUri != null && !referencedUris.contains(resourceUri.toString())) {
                        orphans.add(new OrphanInfo(
                                resourceUri,
                                type,
                                descriptor.getName() != null ? descriptor.getName() : "(unnamed)",
                                descriptor.isDeleted()
                        ));
                    }
                }
            } catch (Exception e) {
                log.warnf("Error scanning store type %s: %s", type, e.getMessage());
            }
        }

        log.infof("Orphan scan complete: %d orphans found", orphans.size());
        return orphans;
    }

    /**
     * Build the complete set of all URIs that are referenced by at least one Agent or package.
     */
    private Set<String> buildReferencedUrisSet() {
        Set<String> referencedUris = new HashSet<>();

        try {
            // Step 1a: Get all bots and collect their package URIs
            List<DocumentDescriptor> botDescriptors = readAllDescriptors("ai.labs.bot", false);
            for (DocumentDescriptor botDescriptor : botDescriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(botDescriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null) continue;

                    AgentConfiguration botConfig = agentStore.read(
                            resourceId.getId(), resourceId.getVersion());
                    if (botConfig.getWorkflows() != null) {
                        for (URI workflowUri : botConfig.getWorkflows()) {
                            referencedUris.add(workflowUri.toString());
                        }
                    }
                } catch (IResourceStore.ResourceNotFoundException e) {
                    // Agent descriptor exists but resource doesn't — skip
                } catch (Exception e) {
                    log.debugf("Error reading Agent %s: %s", botDescriptor.getResource(), e.getMessage());
                }
            }

            // Step 1b: Get all packages and collect their extension resource URIs
            List<DocumentDescriptor> packageDescriptors = readAllDescriptors("ai.labs.package", false);
            for (DocumentDescriptor pkgDescriptor : packageDescriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(pkgDescriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null) continue;

                    WorkflowConfiguration pkgConfig = workflowStore.read(
                            resourceId.getId(), resourceId.getVersion());
                    collectExtensionUris(pkgConfig, referencedUris);
                } catch (IResourceStore.ResourceNotFoundException e) {
                    // Package descriptor exists but resource doesn't — skip
                } catch (Exception e) {
                    log.debugf("Error reading package %s: %s", pkgDescriptor.getResource(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.errorf("Error building referenced URIs set: %s", e.getMessage());
        }

        return referencedUris;
    }

    /**
     * Extract all extension resource URIs from a package configuration.
     * Follows the same traversal as RestWorkflowStore.deletePackageCascade().
     */
    private void collectExtensionUris(WorkflowConfiguration pkgConfig, Set<String> referencedUris) {
        for (WorkflowStep ext : pkgConfig.getWorkflowSteps()) {
            // Main extension resource URI (config.uri)
            Map<String, Object> config = ext.getConfig();
            if (config != null) {
                Object uriObj = config.get("uri");
                if (uriObj != null && !isNullOrEmpty(uriObj.toString())) {
                    referencedUris.add(uriObj.toString());
                }
            }

            // Nested resources (e.g., parser → dictionaries)
            Map<String, Object> extensions = ext.getExtensions();
            if (extensions != null && extensions.containsKey("dictionaries")) {
                Object dictObj = extensions.get("dictionaries");
                if (dictObj instanceof List<?> dictionaries) {
                    for (Object entry : dictionaries) {
                        if (entry instanceof Map<?, ?> dictMap) {
                            Object dictConfig = dictMap.get("config");
                            if (dictConfig instanceof Map<?, ?> dictConfigMap) {
                                Object dictUri = dictConfigMap.get("uri");
                                if (dictUri != null && !isNullOrEmpty(dictUri.toString())) {
                                    referencedUris.add(dictUri.toString());
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Read all descriptors for a given type, paging through all results.
     */
    private List<DocumentDescriptor> readAllDescriptors(String type, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<DocumentDescriptor> all = new ArrayList<>();
        int index = 0;
        List<DocumentDescriptor> batch;

        do {
            batch = documentDescriptorStore.readDescriptors(type, "", index, BATCH_SIZE, includeDeleted);
            all.addAll(batch);
            index += batch.size();
        } while (batch.size() == BATCH_SIZE);

        return all;
    }
}
