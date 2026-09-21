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
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
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
    private final IDeploymentStore deploymentStore;

    @Inject
    public RestOrphanAdmin(IAgentStore agentStore, IWorkflowStore workflowStore, IDocumentDescriptorStore documentDescriptorStore,
            IResourceClientLibrary resourceClientLibrary, IRestWorkflowStore restWorkflowStore, IDeploymentStore deploymentStore) {
        this.agentStore = agentStore;
        this.workflowStore = workflowStore;
        this.documentDescriptorStore = documentDescriptorStore;
        this.resourceClientLibrary = resourceClientLibrary;
        this.restWorkflowStore = restWorkflowStore;
        this.deploymentStore = deploymentStore;
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
                // Re-checked immediately before the irreversible delete, not only in
                // the scan above. The scan is a mark phase over the whole deployment
                // and takes as long as it takes; a workflow created (or re-pointed) in
                // that window makes a resource live again, and this loop would then
                // erase every version of something in use. A targeted reverse lookup
                // costs one query per candidate and closes all but the last instants
                // of that window — narrowing it, not eliminating it: there is no
                // deployment-wide write lock here, and taking one would serialise
                // configuration editing against an administrative sweep.
                //
                // Resolved once and used for BOTH the re-check and the delete: asking
                // the two questions at different versions is how a guard that passes
                // can still be followed by a delete of something in use.
                Integer version = resolveVersion(orphan);
                if (isReferencedNow(orphan, version) || isReferencedByADeployedVersionNow(orphan)) {
                    log.warnf("Skipping orphan %s — it became referenced after the scan and before the purge", orphan.getResourceUri());
                    continue;
                }
                deleteOrphan(orphan, version);
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
     * Fresh reverse lookup for one purge candidate, run between the scan and the
     * delete.
     *
     * <p>
     * Asked at {@code version} — the version {@link #deleteOrphan} is about to
     * address, i.e. the LIVE one. Both reverse lookups take
     * {@code includePreviousVersions=true}, which walks from the version given DOWN
     * to 1, so a referrer pinning a version ABOVE the one asked about is invisible.
     * Asked at the descriptor's version, a stale descriptor (v1) would hide an
     * agent that started referencing the resource at v2 in the mark/sweep window,
     * and the purge would then erase a resource in use. Starting from the current
     * version means there is no newer version above it for the walk to miss — the
     * same argument {@code RestWorkflowStore.collectCascadeCandidate} makes.
     * </p>
     *
     * <p>
     * Fails CLOSED: a lookup that cannot answer reports "referenced", because the
     * alternative is a permanent delete decided on a question nobody answered.
     * </p>
     */
    private boolean isReferencedNow(OrphanInfo orphan, Integer version) {
        var resourceId = RestUtilities.extractResourceId(orphan.getResourceUri());
        if (resourceId == null || version == null || version < 1) {
            // Nothing to re-query with. Do not upgrade the scan's classification to a
            // permanent delete on a reference we cannot ask about.
            log.warnf("Cannot re-check orphan %s (no usable version) — NOT purging it", orphan.getResourceUri());
            return true;
        }

        try {
            if (WORKFLOW_TYPE.equals(orphan.getType())) {
                if (isNullOrEmpty(resourceId.getId())) {
                    log.warnf("Cannot re-check orphan workflow %s (no usable id) — NOT purging it", orphan.getResourceUri());
                    return true;
                }
                return !agentStore.getAgentDescriptorsContainingWorkflow(resourceId.getId(), version, true).isEmpty();
            }
            // Extensions are looked up by URI, so this branch needs no resource id.
            return !workflowStore.getWorkflowDescriptorsContainingResource(atVersion(orphan.getResourceUri(), version).toString(), true)
                    .isEmpty();
        } catch (Exception e) {
            log.warnf("Re-check of orphan %s failed — NOT purging it: %s", orphan.getResourceUri(), e.getMessage());
            return true;
        }
    }

    /**
     * The second half of the fresh re-check: the references held by DEPLOYED Agent
     * versions, which {@link #isReferencedNow} cannot see.
     *
     * <p>
     * Both reverse lookups {@link #isReferencedNow} uses skip a referrer that is
     * not a resource's current version
     * ({@code AbstractResourceStore.isStaleReference}), so they answer only for
     * current Agents and current workflows. The mark scan deliberately counts more
     * than that — {@link #scanDeployedAgents} folds in every version a deployment
     * record pins, because {@code checkDeployments} redeploys exactly those on
     * every startup and every 10-second sweep. Re-checking with the narrower
     * question would therefore have DISCARDED a referrer the scan itself treats as
     * live: a deployment record that starts pinning an older Agent version in the
     * mark/sweep window, whose workflow (or extension) this loop would then erase
     * with every version and every history row.
     * </p>
     *
     * <p>
     * Costs one deployment enumeration per candidate — deliberately not hoisted out
     * of the loop, since a set computed once before the loop would be exactly the
     * stale answer this re-check exists to replace. Fails CLOSED: an incomplete
     * deployment scan reports "referenced".
     * </p>
     */
    private boolean isReferencedByADeployedVersionNow(OrphanInfo orphan) {
        String key = resourceKey(orphan.getResourceUri());
        if (key == null) {
            log.warnf("Cannot re-check orphan %s against deployed versions (no usable key) — NOT purging it", orphan.getResourceUri());
            return true;
        }
        Set<String> deployedReferences = new HashSet<>();
        String failureReason;
        try {
            failureReason = scanDeployedAgents(deployedReferences, null);
        } catch (Exception e) {
            log.warnf("Deployed-version re-check of orphan %s failed — NOT purging it: %s", orphan.getResourceUri(), e.getMessage());
            return true;
        }
        if (failureReason != null) {
            log.warnf("Deployed-version re-check of orphan %s was incomplete (%s) — NOT purging it", orphan.getResourceUri(), failureReason);
            return true;
        }
        if (deployedReferences.contains(key)) {
            log.warnf("Skipping orphan %s — a deployed Agent version started referencing it after the scan", orphan.getResourceUri());
            return true;
        }
        return false;
    }

    /**
     * The version this orphan's re-check and delete must both use: the resource's
     * LIVE version, falling back to the descriptor's when nothing is live.
     *
     * <p>
     * Resolved ONCE per candidate and threaded through both steps, so the question
     * "is anyone still using this?" and the answer "then erase it" cannot be asked
     * about different versions. See {@link #liveVersion} for why the descriptor's
     * own version cannot be trusted.
     * </p>
     */
    private Integer resolveVersion(OrphanInfo orphan) {
        var resourceId = RestUtilities.extractResourceId(orphan.getResourceUri());
        if (WORKFLOW_TYPE.equals(orphan.getType())) {
            String id = resourceId != null ? resourceId.getId() : null;
            return liveVersion(isNullOrEmpty(id) ? null : currentWorkflowId(id), resourceId);
        }
        return liveVersion(resourceClientLibrary.getCurrentResourceId(orphan.getResourceUri()), resourceId);
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
     *
     * <p>
     * {@code version} is the resource's LIVE version, resolved by
     * {@link #resolveVersion} and already used by {@link #isReferencedNow} — not
     * the one the descriptor carries. See {@link #liveVersion}.
     * </p>
     */
    private void deleteOrphan(OrphanInfo orphan, Integer version) throws Exception {
        var resourceId = RestUtilities.extractResourceId(orphan.getResourceUri());

        if (!WORKFLOW_TYPE.equals(orphan.getType())) {
            resourceClientLibrary.deleteResource(atVersion(orphan.getResourceUri(), version), true);
            removeDescriptor(resourceId);
            return;
        }

        // deletedCount is the operator's only feedback for an irreversible operation,
        // so each way this can fail to delete anything has to raise rather than fall
        // through to the caller's deletedCount++.
        if (resourceId == null || isNullOrEmpty(resourceId.getId())) {
            throw new IllegalStateException("Orphan workflow URI carries no resource id: " + orphan.getResourceUri());
        }

        Response response = restWorkflowStore.deleteWorkflow(resourceId.getId(), version, true, false);
        if (response == null) {
            throw new IllegalStateException("Workflow delete answered -1 for " + orphan.getResourceUri());
        }
        if (response.getStatus() < 200 || response.getStatus() >= 300) {
            throw new IllegalStateException("Workflow delete answered " + response.getStatus() + " for " + orphan.getResourceUri());
        }
        removeDescriptor(resourceId);
    }

    /**
     * The version a permanent delete must be addressed at: the resource's LIVE
     * version, falling back to the descriptor's when there is no live version left.
     *
     * <p>
     * A permanent delete is ID-scoped — {@code deleteAllPermanently} drops every
     * version and every history row — but {@code RestVersionInfo.delete} now
     * refuses one addressed at anything but the current version, so a stale tab
     * cannot erase a resource that has since moved on. Descriptor versions go stale
     * routinely: {@code DocumentDescriptorFilter} advances them only on an HTTP
     * PUT/PATCH, so every in-process update path (MCP's injected facades, ZIP
     * import, the upgrade executor) leaves the descriptor saying {@code ?version=1}
     * while the resource is at v2 — the skew {@link #resourceKey(URI)} documents as
     * normal. Addressing the purge's delete at the descriptor's version therefore
     * made every such orphan fail with a caught 409, on every run, for ever:
     * exactly the non-convergence this endpoint exists to remove.
     * </p>
     *
     * <p>
     * A null {@code current} means no live version — an already soft-deleted
     * resource whose history is being purged, which {@code requireCurrentVersion}
     * deliberately admits — so the descriptor's version is the right thing to
     * address it at.
     * </p>
     */
    private static Integer liveVersion(IResourceStore.IResourceId current, IResourceStore.IResourceId fromDescriptor) {
        if (current != null && current.getVersion() != null) {
            return current.getVersion();
        }
        return fromDescriptor != null ? fromDescriptor.getVersion() : null;
    }

    /**
     * The workflow's live id/version, or null when it has none left.
     * {@code IWorkflowStore} reports "gone" by exception where
     * {@code ResourceClientLibrary} reports it by null; {@link #liveVersion} takes
     * the null form.
     */
    private IResourceStore.IResourceId currentWorkflowId(String id) {
        try {
            return workflowStore.getCurrentResourceId(id);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The same resource, addressed at {@code version}.
     *
     * <p>
     * {@code pathWithoutVersionQuery} answers null for BOTH "no query at all" and
     * "a query that carries no usable version", and returning the URI unchanged in
     * those cases was not a no-op: it handed
     * {@code getWorkflowDescriptorsContainingResource} exactly the shape
     * {@code WorkflowStore} refuses ("Reverse lookup requires a versioned resource
     * URI"), so {@link #isReferencedNow} caught, logged "NOT purging it" and
     * answered true — a descriptor whose URI carries no {@code ?version=} was
     * re-listed by every scan and purged by none, the non-convergence this endpoint
     * exists to remove. {@code deleteOrphan} would have fared no better:
     * {@code ResourceClientLibrary.deleteResource} reads the version straight out
     * of the URI.
     * </p>
     *
     * <p>
     * Everything before the query is therefore re-addressed at {@code version},
     * exactly as {@code RestWorkflowStore.withVersion} does for the cascade's
     * references — the two walk the same stored URIs and must not disagree about
     * what "at this version" means.
     * </p>
     *
     * <p>
     * No null guard, deliberately: both call sites are downstream of
     * {@link #isReferencedNow}'s {@code resourceId == null || version == null ||
     * version < 1} check, so a null URI or a null version cannot reach here — and a
     * defensive branch nothing can execute is a line no test can pin.
     * </p>
     */
    private static URI atVersion(URI resourceUri, Integer version) {
        String withoutVersion = RestUtilities.pathWithoutVersionQuery(resourceUri);
        if (withoutVersion == null) {
            String raw = resourceUri.toString();
            int queryStart = raw.indexOf('?');
            withoutVersion = queryStart >= 0 ? raw.substring(0, queryStart) : raw;
        }
        return URI.create(withoutVersion + "?version=" + version);
    }

    /**
     * Removes the descriptor of a resource this endpoint has just purged
     * permanently — the one place where erasing it is both safe and necessary.
     *
     * <p>
     * {@code RestVersionInfo.markDescriptorDeleted} only FLAGS descriptors, and has
     * to: on the HTTP path {@code DocumentDescriptorFilter} reads the descriptor
     * back after the delete and a missing row there answers 404 to a delete that in
     * fact succeeded. That filter does not run for {@code /administration/orphans}
     * — {@code extractResourceId} yields no id for this request URI, so it skips —
     * which is what makes erasing the row safe here.
     * </p>
     *
     * <p>
     * And necessary, because a flagged descriptor whose resource is gone is exactly
     * what {@code readDescriptors(includeDeleted=true)} selects. Left behind, every
     * purged resource came back as an orphan on the next
     * {@code ?includeDeleted=true} sweep, {@code deleteAllPermanently} on a
     * non-existent id answered silently, {@code deletedCount} counted it again —
     * and the tombstone set grew with every run. The purge could never converge,
     * which is the "reports success while doing nothing" it exists to remove.
     * </p>
     *
     * <p>
     * Best-effort: the resource IS gone by now, so a descriptor that cannot be
     * removed must not turn a completed purge into a failure.
     * </p>
     */
    private void removeDescriptor(IResourceStore.IResourceId resourceId) {
        if (resourceId == null || isNullOrEmpty(resourceId.getId())) {
            return;
        }
        try {
            documentDescriptorStore.deleteAllDescriptor(resourceId.getId());
        } catch (Exception e) {
            log.warnf("Purged resource %s but could not remove its descriptor; it will be re-reported as an orphan: %s", resourceId.getId(),
                    e.getMessage());
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
     * The host is canonicalised too. {@code ResourceClientLibrary.init()} registers
     * three stores under two authorities each ({@code behavior}/{@code rules},
     * {@code httpcalls}/{@code apicalls},
     * {@code regulardictionary}/{@code dictionary}), so a workflow step written
     * through REST or MCP with the legacy authority resolves perfectly at runtime
     * while the descriptor always carries the canonical one that
     * {@code RestVersionInfo.create} writes. Only ZIP import normalises; a literal
     * host compare therefore reported a rule set that a live workflow still
     * references as unreferenced, and the purge erased it.
     * </p>
     *
     * <p>
     * Note the remaining, deliberate gap: only the CURRENT version of each agent
     * and workflow contributes references (plus every version named by a deployment
     * record — see {@link #scanReferencedUris()}), so a resource referenced solely
     * by a superseded, undeployed agent version is still reported. That is the
     * documented meaning of "orphan" here — history is not a reference — but it
     * does mean rolling an agent back to an older version after a purge can leave
     * it unresolvable.
     * </p>
     */
    private static String resourceKey(URI uri) {
        if (uri == null) {
            return null;
        }
        String withoutVersion = RestUtilities.pathWithoutVersionQuery(uri);
        String path = withoutVersion != null ? withoutVersion : uri.toString();
        return canonicalHost(uri.getHost(), path);
    }

    /**
     * The legacy authority a store also answers to, mapped to the canonical one
     * descriptors are written with. Mirrors the aliases
     * {@code ResourceClientLibrary.init()} registers — the runtime resolves both,
     * so the orphan scan has to compare both as one.
     */
    private static final Map<String, String> CANONICAL_HOSTS = Map.of("ai.labs.behavior", "ai.labs.rules", "ai.labs.httpcalls",
            "ai.labs.apicalls", "ai.labs.regulardictionary", "ai.labs.dictionary");

    /**
     * A reference keyed by canonical type and resource id, dropping the store path.
     * The two authorities of an aliased store carry different paths as well
     * ({@code behaviorstore/behaviorsets} vs {@code rulestore/rulesets}), so
     * rewriting the host alone would not make them compare equal.
     */
    private static String canonicalHost(String host, String path) {
        if (host == null) {
            return path;
        }
        var resourceId = RestUtilities.extractResourceId(URI.create(path));
        if (resourceId == null || resourceId.getId() == null) {
            return path;
        }
        return CANONICAL_HOSTS.getOrDefault(host, host) + "/" + resourceId.getId();
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

            // Step 1c: every DEPLOYED Agent version, not just the current one.
            //
            // Deployments are version-pinned: AgentDeploymentManagement.checkDeployments
            // reads (agentId, agentVersion) straight out of the deployment store and
            // redeploys exactly that version on every startup and every 10-second sweep.
            // Steps 1a/1b only see each Agent's CURRENT version, so an author editing a
            // deployed Agent to point at a new workflow made the old one look
            // unreferenced — and purging it left the still-deployed version unable to
            // resolve its own workflow, with the history rows gone and no recovery path.
            // Superseded-and-undeployed versions remain out of scope by design (see
            // resourceKey); "deployed" is not history.
            failureReason = scanDeployedAgents(referencedResources, failureReason);

        } catch (Exception e) {
            log.errorf("Error building referenced URIs set: %s", e.getMessage());
            failureReason = "could not enumerate Agent/workflow descriptors: " + e.getMessage();
        }

        return new ReferenceScan(referencedResources, failureReason == null, failureReason);
    }

    /**
     * Folds the workflows (and their extensions) of every deployed Agent version
     * into the referenced set.
     *
     * @param currentFailure
     *            the failure reason accumulated so far
     * @return {@code currentFailure}, or a new reason when this step could not
     *         complete — a deployment store that cannot be read leaves the scan
     *         incomplete, because everything a deployed version protects would
     *         otherwise be reported as an orphan
     */
    private String scanDeployedAgents(Set<String> referencedResources, String currentFailure) {
        String failureReason = currentFailure;
        List<DeploymentInfo> deployments;
        try {
            deployments = deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed);
        } catch (Exception e) {
            log.errorf("Could not read the deployment records: %s", e.getMessage());
            return "could not read the deployment records: " + e.getMessage();
        }

        for (DeploymentInfo deployment : deployments) {
            if (deployment == null || isNullOrEmpty(deployment.getAgentId()) || deployment.getAgentVersion() == null) {
                continue;
            }
            try {
                AgentConfiguration deployedAgent = agentStore.read(deployment.getAgentId(), deployment.getAgentVersion());
                if (deployedAgent.getWorkflows() == null) {
                    continue;
                }
                for (URI workflowUri : deployedAgent.getWorkflows()) {
                    referencedResources.add(resourceKey(workflowUri));
                    failureReason = collectExtensionUrisOfPinnedWorkflow(workflowUri, referencedResources, failureReason);
                }
            } catch (IResourceStore.ResourceNotFoundException e) {
                // The deployed version is gone from the store already; it protects
                // nothing, and that is not an incomplete scan.
            } catch (Exception e) {
                log.warnf("Error reading deployed Agent %s (v%d): %s", deployment.getAgentId(), deployment.getAgentVersion(), e.getMessage());
                failureReason = "could not read deployed Agent " + deployment.getAgentId() + " (v" + deployment.getAgentVersion() + "): "
                        + e.getMessage();
            }
        }
        return failureReason;
    }

    /**
     * Reads the exact workflow version a deployed Agent pins — which the
     * descriptor-driven walk in {@link #scanReferencedUris()} does not see, because
     * that only visits current versions — and records its extension references.
     */
    private String collectExtensionUrisOfPinnedWorkflow(URI workflowUri, Set<String> referencedResources, String currentFailure) {
        var resourceId = RestUtilities.extractResourceId(workflowUri);
        if (resourceId == null || isNullOrEmpty(resourceId.getId()) || resourceId.getVersion() == null || resourceId.getVersion() < 1) {
            return currentFailure;
        }
        try {
            collectExtensionUris(workflowStore.read(resourceId.getId(), resourceId.getVersion()), referencedResources);
            return currentFailure;
        } catch (IResourceStore.ResourceNotFoundException e) {
            return currentFailure;
        } catch (Exception e) {
            log.warnf("Error reading deployed workflow %s: %s", workflowUri, e.getMessage());
            return "could not read deployed workflow " + workflowUri + ": " + e.getMessage();
        }
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
