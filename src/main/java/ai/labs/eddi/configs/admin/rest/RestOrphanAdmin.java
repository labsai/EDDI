/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.rest;

import ai.labs.eddi.configs.admin.IRestOrphanAdmin;
import ai.labs.eddi.configs.admin.model.OrphanInfo;
import ai.labs.eddi.configs.admin.model.OrphanReport;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.*;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Implementation of the orphan detection and cleanup endpoint.
 *
 * <p>
 * Algorithm:
 * </p>
 * <ol>
 * <li>Enumerate all agents → collect referenced workflow URIs</li>
 * <li>Enumerate all workflows → collect referenced extension resource URIs</li>
 * <li>For each store type, enumerate all resources via document
 * descriptors</li>
 * <li>Any resource whose URI is NOT in the referenced set = orphan</li>
 * </ol>
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestOrphanAdmin implements IRestOrphanAdmin {

    private static final Logger log = Logger.getLogger(RestOrphanAdmin.class);

    /**
     * Store types to scan for orphans. Each entry is {descriptorType,
     * descriptorTypeLabel}. The descriptor type is used to query
     * IDocumentDescriptorStore.readDescriptors().
     */
    private static final String[][] SCANNABLE_STORE_TYPES = {{"ai.labs.workflow", "Workflow"}, {"ai.labs.rules", "Rules"},
            {"ai.labs.apicalls", "API Calls"}, {"ai.labs.output", "Output Set"}, {"ai.labs.llm", "LLM"}, {"ai.labs.property", "Property Setter"},
            {"ai.labs.dictionary", "Dictionary"}, {"ai.labs.parser", "Parser"},};

    private static final int BATCH_SIZE = 200;

    /**
     * Hard ceiling on pages read per store type. The scan is a synchronous,
     * one-read-per-descriptor traversal, so an unbounded walk would hold a worker
     * thread for the whole collection. Hitting the ceiling is reported as an error
     * rather than silently truncating — a truncated scan cannot be used to decide
     * what to delete.
     */
    private static final int MAX_PAGES = 100;

    private static final String WORKFLOW_TYPE = "ai.labs.workflow";

    private final IAgentStore agentStore;
    private final IWorkflowStore workflowStore;
    private final IDocumentDescriptorStore documentDescriptorStore;
    private final IResourceClientLibrary resourceClientLibrary;
    private final IRestWorkflowStore restWorkflowStore;

    @Inject
    public RestOrphanAdmin(IAgentStore agentStore, IWorkflowStore workflowStore, IDocumentDescriptorStore documentDescriptorStore,
            IResourceClientLibrary resourceClientLibrary, IRestWorkflowStore restWorkflowStore) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.restWorkflowStore = restWorkflowStore;
    }

    @Override
    public OrphanReport scanOrphans(Boolean includeDeleted) {
        ReferenceScan scan = scanReferencedUris();
        List<OrphanInfo> orphans = collectOrphans(scan.referencedResources(), includeDeleted);
        return new OrphanReport(orphans.size(), 0, orphans, scan.complete(), scan.failureReason());
    }

    @Override
    public OrphanReport purgeOrphans(Boolean includeDeleted) {
        // The reference set decides what is NOT an orphan. Every way of building it
        // incompletely — a failed store read, a truncated page walk — makes MORE
        // resources look unreferenced, and the delete below is permanent and
        // irreversible. So an incomplete scan must refuse to purge rather than
        // proceed on a partial picture.
        ReferenceScan scan = scanReferencedUris();
        if (!scan.complete()) {
            log.errorf("Refusing to purge orphans: the reference scan was incomplete (%s)", scan.failureReason());
            // Build the Response explicitly rather than using the (String, Status)
            // constructor: that one sets no entity, so the caller would receive a bare
            // 409 and the reason would exist only in the server log.
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("error", "incomplete_scan", "message",
                            "Refusing to purge orphans: the reference scan was incomplete (" + scan.failureReason()
                                    + "). Purging on a partial reference set could permanently delete resources that are still in use."))
                    .type(MediaType.APPLICATION_JSON).build());
        }

        List<OrphanInfo> orphans = collectOrphans(scan.referencedResources(), includeDeleted);
        int deletedCount = 0;

        for (OrphanInfo orphan : orphans) {
            try {
                deleteOrphan(orphan);
                deletedCount++;
                log.infof("Purged orphan: %s [%s]", orphan.getResourceUri(), orphan.getType());
            } catch (Exception e) {
                log.warnf("Failed to purge orphan %s: %s", orphan.getResourceUri(), e.getMessage());
            }
        }

        log.infof("Orphan purge complete: %d/%d deleted", deletedCount, orphans.size());
        return new OrphanReport(orphans.size(), deletedCount, orphans, true, null);
    }

    /**
     * Deletes one orphan, or throws so the caller does not count it.
     *
     * <p>
     * Workflows go through {@link IRestWorkflowStore} directly:
     * {@code ResourceClientLibrary} registers no {@code ai.labs.workflow} proxy, so
     * routing them there deleted nothing. Cascade is deliberately off — every
     * resource a workflow references is itself enumerated by this scan, and is only
     * an orphan if nothing else points at it.
     * </p>
     */
    private void deleteOrphan(OrphanInfo orphan) throws Exception {
        if (!WORKFLOW_TYPE.equals(orphan.getType())) {
            resourceClientLibrary.deleteResource(orphan.getResourceUri(), true);
            return;
        }

        // deletedCount is the operator's only feedback for an irreversible operation,
        // so each way this can fail to delete anything has to raise rather than fall
        // through to the caller's deletedCount++.
        var resourceId = RestUtilities.extractResourceId(orphan.getResourceUri());
        if (resourceId == null || isNullOrEmpty(resourceId.getId())) {
            throw new IllegalStateException("Orphan workflow URI carries no resource id: " + orphan.getResourceUri());
        }

        Response response = restWorkflowStore.deleteWorkflow(resourceId.getId(), resourceId.getVersion(), true, false);
        if (response == null) {
            throw new IllegalStateException("Workflow delete answered -1 for " + orphan.getResourceUri());
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("Workflow delete answered " + response.getStatus() + " for " + orphan.getResourceUri());
        }
    }

    /**
     * Outcome of building the referenced-resource set.
     *
     * @param referencedResources
     *            every resource referenced by at least one Agent or workflow, keyed
     *            by {@link #resourceKey(URI)} — identity WITHOUT the pinned version
     * @param complete
     *            false when any part of the traversal failed, meaning the set may
     *            be missing references and is therefore unsafe to delete against
     * @param failureReason
     *            human-readable cause when {@code complete} is false
     */
    private record ReferenceScan(Set<String> referencedResources, boolean complete, String failureReason) {
    }

    /**
     * A resource's identity, independent of the version a reference pins.
     *
     * <p>
     * References are version-pinned by design, and {@code DocumentDescriptorFilter}
     * rewrites a descriptor's {@code resource} to the NEW version on every PUT. So
     * one edit of a rule set leaves the descriptor saying {@code ?version=2} while
     * every workflow that was not re-pointed still says {@code ?version=1}. Under a
     * literal string compare that rule set looked unreferenced, and the purge —
     * which deletes ALL versions — destroyed a config a live workflow was still
     * resolving. Comparing identity means any referenced version protects the
     * resource.
     * </p>
     *
     * <p>
     * Note the remaining, deliberate gap: only the CURRENT version of each agent
     * and workflow contributes references, so a resource referenced solely by a
     * superseded agent version is still reported. That is the documented meaning of
     * "orphan" here — history is not a reference — but it does mean rolling an
     * agent back to an older version after a purge can leave it unresolvable.
     * </p>
     */
    private static String resourceKey(URI uri) {
        if (uri == null) {
            return null;
        }
        String withoutVersion = RestUtilities.pathWithoutVersionQuery(uri);
        return withoutVersion != null ? withoutVersion : uri.toString();
    }

    private List<OrphanInfo> collectOrphans(Set<String> referencedResources, boolean includeDeleted) {
        log.infof("Orphan scan: found %d referenced resources", referencedResources.size());

        List<OrphanInfo> orphans = new ArrayList<>();

        for (String[] storeType : SCANNABLE_STORE_TYPES) {
            String type = storeType[0];

            try {
                List<DocumentDescriptor> descriptors = readAllDescriptors(type, includeDeleted);
                for (DocumentDescriptor descriptor : descriptors) {
                    URI resourceUri = descriptor.getResource();
                    if (resourceUri != null && !referencedResources.contains(resourceKey(resourceUri))) {
                        orphans.add(new OrphanInfo(resourceUri, type, descriptor.getName() != null ? descriptor.getName() : "(unnamed)",
                                descriptor.isDeleted()));
                    }
                }
            } catch (Exception e) {
                // Safe to continue: a type that cannot be enumerated contributes no
                // orphan CANDIDATES, so the purge under-deletes rather than over-
                // deletes. The opposite failure — an incomplete REFERENCE set — is the
                // dangerous one and is handled by ReferenceScan.complete().
                log.warnf("Error scanning store type %s: %s", type, e.getMessage());
            }
        }

        log.infof("Orphan scan complete: %d orphans found", orphans.size());
        return orphans;
    }

    /**
     * Build the set of all URIs that are referenced by at least one Agent or
     * workflow, tracking whether the traversal completed.
     *
     * <p>
     * Completeness is reported rather than assumed: every failure here removes
     * entries from the set, and a missing entry promotes a live resource to
     * "orphan". Read-only callers may use a partial set; the purge may not.
     * </p>
     */
    private ReferenceScan scanReferencedUris() {
        Set<String> referencedResources = new HashSet<>();
        String failureReason = null;

        try {
            // Step 1a: Get all agents and collect their workflow URIs
            List<DocumentDescriptor> agentDescriptors = readAllDescriptors("ai.labs.agent", false);
            for (DocumentDescriptor agentDescriptor : agentDescriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(agentDescriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null)
                        continue;

                    AgentConfiguration agentConfig = agentStore.read(resourceId.getId(), resourceId.getVersion());
                    if (agentConfig.getWorkflows() != null) {
                        for (URI workflowUri : agentConfig.getWorkflows()) {
                            referencedResources.add(resourceKey(workflowUri));
                        }
                    }
                } catch (IResourceStore.ResourceNotFoundException e) {
                    // Agent descriptor exists but resource doesn't — genuinely
                    // unreferenced, so this does not make the scan incomplete.
                } catch (Exception e) {
                    log.warnf("Error reading Agent %s: %s", agentDescriptor.getResource(), e.getMessage());
                    failureReason = "could not read Agent " + agentDescriptor.getResource() + ": " + e.getMessage();
                }
            }

            // Step 1b: Get all workflows and collect their extension resource URIs
            List<DocumentDescriptor> workflowDescriptors = readAllDescriptors("ai.labs.workflow", false);
            for (DocumentDescriptor workflowDescriptor : workflowDescriptors) {
                try {
                    var resourceId = RestUtilities.extractResourceId(workflowDescriptor.getResource());
                    if (resourceId == null || resourceId.getId() == null)
                        continue;

                    WorkflowConfiguration workflowConfig = workflowStore.read(resourceId.getId(), resourceId.getVersion());
                    collectExtensionUris(workflowConfig, referencedResources);
                } catch (IResourceStore.ResourceNotFoundException e) {
                    // Workflow descriptor exists but resource doesn't — genuinely
                    // unreferenced, so this does not make the scan incomplete.
                } catch (Exception e) {
                    log.warnf("Error reading workflow %s: %s", workflowDescriptor.getResource(), e.getMessage());
                    failureReason = "could not read workflow " + workflowDescriptor.getResource() + ": " + e.getMessage();
                }
            }

        } catch (Exception e) {
            log.errorf("Error building referenced URIs set: %s", e.getMessage());
            failureReason = "could not enumerate Agent/workflow descriptors: " + e.getMessage();
        }

        return new ReferenceScan(referencedResources, failureReason == null, failureReason);
    }

    /**
     * Extract all extension resource URIs from a workflow configuration. Follows
     * the same traversal as RestWorkflowStore.deleteWorkflowCascade().
     */
    private void collectExtensionUris(WorkflowConfiguration workflowConfig, Set<String> referencedResources) {
        for (WorkflowStep ext : workflowConfig.getWorkflowSteps()) {
            // Main extension resource URI (config.uri)
            Map<String, Object> config = ext.getConfig();
            if (config != null) {
                Object uriObj = config.get("uri");
                if (uriObj != null && !isNullOrEmpty(uriObj.toString())) {
                    addReference(referencedResources, uriObj);
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
                                    addReference(referencedResources, dictUri);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Records a workflow-step reference, keyed by identity. A value that is not a
     * parsable URI is still recorded verbatim: it protects nothing that way, but
     * dropping it silently would be worse than an entry that simply never matches.
     */
    private static void addReference(Set<String> referencedResources, Object uriObj) {
        String raw = uriObj.toString();
        try {
            referencedResources.add(resourceKey(URI.create(raw)));
        } catch (IllegalArgumentException e) {
            // Letting this out would cost the scan every OTHER reference this workflow
            // holds — the per-workflow catch upstream discards the whole traversal —
            // and that is exactly what promotes live resources to "orphan".
            log.warnf("Workflow references a malformed resource uri '%s'; recording it verbatim: %s", raw, e.getMessage());
            referencedResources.add(raw);
        }
    }

    /**
     * Read all descriptors for a given type, paging through all results.
     *
     * <p>
     * {@code index} is a PAGE index, not a row offset —
     * {@link ai.labs.eddi.datastore.DescriptorStore#readDescriptors} computes
     * {@code skip = index * limit}. Advancing it by {@code batch.size()} asked for
     * page 200 (skip = 40 000) on the second iteration, which always came back
     * empty, so every type was silently truncated at {@value #BATCH_SIZE} rows.
     * That truncation also hit {@link #scanReferencedUris()}, where a missing
     * reference makes a live resource look unreferenced.
     * </p>
     */
    private List<DocumentDescriptor> readAllDescriptors(String type, boolean includeDeleted)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        List<DocumentDescriptor> all = new ArrayList<>();
        int pageIndex = 0;
        List<DocumentDescriptor> batch;

        do {
            batch = documentDescriptorStore.readDescriptors(type, "", pageIndex, BATCH_SIZE, includeDeleted);
            all.addAll(batch);
            pageIndex++;
        } while (batch.size() == BATCH_SIZE && pageIndex < MAX_PAGES);

        if (batch.size() == BATCH_SIZE) {
            log.warnf("Descriptor scan for type %s hit the %d-page ceiling (%d rows); results are incomplete", type, MAX_PAGES,
                    all.size());
            throw new IResourceStore.ResourceStoreException(
                    "Descriptor scan for type " + type + " exceeded " + (MAX_PAGES * BATCH_SIZE) + " rows");
        }

        return all;
    }
}
