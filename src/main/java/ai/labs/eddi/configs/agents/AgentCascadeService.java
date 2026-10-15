/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents;

import ai.labs.eddi.configs.agents.model.AgentCascadeRequest.ResourceMapping;
import ai.labs.eddi.configs.agents.model.AgentCascadeResult;
import ai.labs.eddi.configs.agents.model.AgentCascadeResult.WorkflowChange;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory.RestInterfaceFactoryException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cascades resource URI changes through an agent's workflows and into the agent
 * itself in ONE pass, saving every document once and optionally redeploying.
 * <p>
 * The single implementation behind the MCP {@code apply_agent_changes} tool and
 * {@code POST /agentstore/agents/{id}/cascade}. Nothing in EDDI mutates in
 * place — every write creates the next version — so landing an edit takes three
 * pointer changes (the workflow step at the new config version, the agent at
 * the new workflow version, then a deploy). Done one call at a time that writes
 * an intermediate version per hop; here each workflow and the agent are written
 * exactly once.
 *
 * <h3>Why this cannot change anything but references</h3>
 * <ul>
 * <li>The request carries only {@link ResourceMapping}s — pairs of strings.
 * There is no field through which a document, a setting or a gate can
 * arrive.</li>
 * <li>The only mutations are {@code step.config["uri"]} on a workflow step and
 * {@code AgentConfiguration.setWorkflows(...)}. The agent is the document read
 * from the store, written back with its workflow list replaced; every other
 * field — {@code hitlConfig} above all — is carried forward untouched. The
 * agent store refuses an update whose base version is not the current one, so
 * "the document read" is also "the document stored".</li>
 * <li>Each pair is validated before anything is read or written: both URIs
 * carry a version and name the <em>same kind</em> of resource (same
 * {@code eddi://} authority and store path), and the old one must be a
 * reference the agent's workflows really hold — otherwise nothing is written. A
 * pair cannot retarget a workflow step at a different type of document, nor
 * name something the agent does not already reference.</li>
 * </ul>
 * Every store write goes through the same REST facades a person's edit does
 * (loopback, carrying the caller's token), so access checks, validation and
 * version bookkeeping are the ones the normal endpoints apply; a redeploy goes
 * through {@link IRestAgentAdministration#deployAgent}, the normal deploy
 * endpoint, so quota and the vault-grant check apply identically.
 */
@ApplicationScoped
public class AgentCascadeService {

    /**
     * {@code eddi://<authority>/<store path>/<id>?version=<n>} and nothing else —
     * no further query, no fragment. Group 1 is the kind of resource (authority and
     * store path, without the id), 2 the id, 3 the version.
     */
    private static final Pattern VERSIONED_RESOURCE_URI = Pattern
            .compile("^eddi://([A-Za-z0-9._-]+(?:/[A-Za-z0-9._~-]+)*)/([A-Za-z0-9._~-]+)\\?version=([1-9][0-9]{0,8})$");

    private final IRestInterfaceFactory restInterfaceFactory;
    private final IRestAgentAdministration agentAdmin;

    @Inject
    public AgentCascadeService(IRestInterfaceFactory restInterfaceFactory, IRestAgentAdministration agentAdmin) {
        this.restInterfaceFactory = restInterfaceFactory;
        this.agentAdmin = agentAdmin;
    }

    /** Why a cascade was refused before it wrote anything. */
    public static final class CascadeException extends RuntimeException {
        /** The kind of refusal, which transports map to their own status. */
        public enum Kind {
            BAD_REQUEST, NOT_FOUND, CONFLICT,
            /**
             * Something failed after the apply phase began; the message says what was and
             * was not written.
             */
            FAILED
        }

        private final Kind kind;

        public CascadeException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    /**
     * Applies the mappings to the agent and returns what changed.
     *
     * @param agentId
     *            the agent
     * @param agentVersion
     *            the agent version to start from; must be the current one
     * @param mappings
     *            the URI changes; at least one
     * @param redeploy
     *            deploy the new agent version afterwards
     * @param environment
     *            where to deploy; only used when {@code redeploy} is set
     * @param compatible
     *            whether the new agent version is compatible with the previous one
     * @throws CascadeException
     *             for a request that is refused before anything is written
     */
    public AgentCascadeResult cascade(String agentId, int agentVersion, List<ResourceMapping> mappings, boolean redeploy,
                                      Deployment.Environment environment, boolean compatible) {
        Map<String, String> newByOld = validate(mappings);

        IRestAgentStore agentStore = rest(IRestAgentStore.class);
        IRestWorkflowStore workflowStore = rest(IRestWorkflowStore.class);

        // Before reading anything the writes depend on: a stale base version would
        // otherwise only be noticed by the agent update at the very end, AFTER every
        // workflow had been written — new workflow versions nothing references. 0/null
        // means the store could not say; the update then still enforces it.
        Integer current = agentStore.getCurrentVersion(agentId);
        if (current != null && current > 0 && current != agentVersion) {
            throw new CascadeException(CascadeException.Kind.CONFLICT,
                    "Agent " + agentId + " is at version " + current + ", not " + agentVersion + ". Read the current version and retry.");
        }

        AgentConfiguration agentConfig = agentStore.readAgent(agentId, agentVersion);
        if (agentConfig == null) {
            throw new CascadeException(CascadeException.Kind.NOT_FOUND, "Agent not found: " + agentId + " version " + agentVersion);
        }

        // ── Plan: read and rewrite in memory, write nothing. ────────────────────────
        List<URI> originalWorkflowUris = new ArrayList<>(agentConfig.getWorkflows());
        // Keyed by the workflow URI the agent will use BEFORE step rewrites. The same
        // workflow can be referenced twice (or two references can be mapped to the same
        // target); it is read, rewritten and written ONCE and the result reused for
        // every
        // position, since a second write of one workflow would 409 halfway through.
        Map<String, PlannedWorkflow> plan = new LinkedHashMap<>();
        List<String> positions = new ArrayList<>();
        Set<String> matched = new LinkedHashSet<>();

        for (URI referenced : originalWorkflowUris) {
            // A mapping can name the workflow itself: the agent is repointed at the new
            // workflow version, and step mappings then apply to THAT version's steps.
            String repointTo = newByOld.get(referenced.toString());
            boolean repointed = repointTo != null;
            if (repointed) {
                // The agent's own workflow list may only move to another VERSION of the
                // same workflow — exactly what PUT .../updateResourceUri allows (it matches
                // on the path without the version). Re-pointing the agent at a DIFFERENT
                // workflow id is not something the granted endpoints can do, so it is not
                // something the cascade adds. Step references are different: PUT on the
                // workflow can already set any step to any resource, so they stay free.
                if (!Objects.equals(idOf(referenced.toString()), idOf(repointTo))) {
                    throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "An agent's workflow reference can only move to another version"
                            + " of the same workflow, not to a different workflow: " + referenced + " -> " + repointTo + ". Nothing was changed.");
                }
                matched.add(referenced.toString());
            }
            URI effective = repointed ? URI.create(repointTo) : referenced;
            positions.add(effective.toString());

            PlannedWorkflow existing = plan.get(effective.toString());
            if (existing != null) {
                plan.put(effective.toString(), existing.withRepointed(existing.repointed() || repointed));
                continue;
            }

            String workflowId = idOf(effective.toString());
            int workflowVersion = versionOf(effective.toString());
            WorkflowConfiguration workflow = workflowId == null ? null : workflowStore.readWorkflow(workflowId, workflowVersion);

            boolean stepsUpdated = false;
            if (workflow != null) {
                for (var step : workflow.getWorkflowSteps()) {
                    Map<String, Object> config = step.getConfig();
                    Object uriObj = config == null ? null : config.get("uri");
                    if (uriObj == null) {
                        continue;
                    }
                    String replacement = newByOld.get(uriObj.toString());
                    if (replacement != null) {
                        matched.add(uriObj.toString());
                        config.put("uri", replacement);
                        stepsUpdated = true;
                    }
                }
            }
            plan.put(effective.toString(),
                    new PlannedWorkflow(referenced, effective, workflowId, workflowVersion, workflow, repointed, stepsUpdated));
        }

        // Two VERSIONS of one workflow in the same agent, either needing a rewrite:
        // only
        // the latest version of a document can be updated, so the second write cannot
        // succeed. Refused whole, like every other plan-time problem.
        Map<String, String> versionById = new HashMap<>();
        for (PlannedWorkflow planned : plan.values()) {
            if (planned.workflowId() == null) {
                continue;
            }
            String other = versionById.put(planned.workflowId(), planned.effective().toString());
            if (other != null && (planned.stepsUpdated() || plan.get(other).stepsUpdated())) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "Agent " + agentId + " references two versions of workflow "
                        + planned.workflowId() + " (" + other + " and " + planned.effective()
                        + ") and a mapping would rewrite one of them. Nothing was changed. Point the agent at one version first.");
            }
        }

        List<String> unmatched = new ArrayList<>(newByOld.keySet());
        unmatched.removeAll(matched);
        if (!unmatched.isEmpty()) {
            // Refused whole, before any write: a pair that matches nothing means the
            // caller is working from a stale picture of the agent (an older version, the
            // wrong agent), and applying the pairs that did match would be a half-change
            // nobody asked for.
            throw new CascadeException(CascadeException.Kind.BAD_REQUEST,
                    "Not referenced by agent " + agentId + " version " + agentVersion + " or its workflows: " + String.join(", ", unmatched)
                            + ". Nothing was changed. Read the agent and its workflows and use the exact URIs they reference.");
        }

        // ── Apply: each changed workflow once, then the agent once. ─────────────────
        // Workflows are written first, so a failure from here on can leave NEW workflow
        // versions that nothing references. `written` records them so the failure can
        // say so.
        List<String> written = new ArrayList<>();
        Map<String, URI> resultingByEffective = new HashMap<>();
        List<WorkflowChange> changes = new ArrayList<>();
        for (PlannedWorkflow planned : plan.values()) {
            URI resulting = planned.effective();
            int newVersion = planned.version();
            if (planned.stepsUpdated()) {
                String location;
                try {
                    Response response = workflowStore.updateWorkflow(planned.workflowId(), planned.version(), planned.workflow());
                    location = response.getHeaderString("Location");
                } catch (RuntimeException failure) {
                    throw partialFailure(agentId, agentVersion, written, "writing workflow " + planned.workflowId(), failure);
                }
                if (location == null || location.isBlank() || !location.contains("version=")) {
                    // A store update that does not say where it wrote is a broken contract;
                    // guessing "version + 1" would point the agent at a version that may not exist.
                    throw partialFailure(agentId, agentVersion, written,
                            "workflow " + planned.workflowId() + " was updated but the store did not report its new version", null);
                }
                resulting = URI.create(location);
                newVersion = versionOf(location);
                written.add(location);
            }
            resultingByEffective.put(planned.effective().toString(), resulting);
            if (planned.repointed() || planned.stepsUpdated()) {
                changes.add(new WorkflowChange(planned.workflowId(), versionOf(planned.referenced().toString()), newVersion, planned.repointed(),
                        planned.stepsUpdated()));
            }
        }
        List<URI> updatedWorkflowUris = new ArrayList<>();
        for (String position : positions) {
            updatedWorkflowUris.add(resultingByEffective.get(position));
        }

        int newAgentVersion = agentVersion;
        if (!changes.isEmpty()) {
            // The ONLY agent field touched. Everything else — hitlConfig included — is
            // the stored document written back unchanged.
            agentConfig.setWorkflows(updatedWorkflowUris);
            String agentLocation;
            try {
                Response agentResponse = agentStore.updateAgent(agentId, agentVersion, agentConfig, compatible);
                agentLocation = agentResponse.getHeaderString("Location");
            } catch (RuntimeException failure) {
                throw partialFailure(agentId, agentVersion, written, "saving the agent", failure);
            }
            if (agentLocation == null || agentLocation.isBlank() || !agentLocation.contains("version=")) {
                throw new CascadeException(CascadeException.Kind.FAILED, "The agent update was accepted but the store did not report the new version"
                        + " of agent " + agentId + "; read its current version before doing anything else." + writtenNote(written));
            }
            newAgentVersion = versionOf(agentLocation);
        }

        AgentCascadeResult.Deployment deployment = null;
        if (redeploy && !changes.isEmpty()) {
            deployment = redeploy(environment, agentId, newAgentVersion);
        }

        return new AgentCascadeResult(agentId, agentVersion, newAgentVersion, List.copyOf(changes), originalWorkflowUris.size(), matched.size(),
                compatible, deployment);
    }

    /**
     * What a failure after some workflow versions were written must tell the
     * caller.
     */
    private static String writtenNote(List<String> written) {
        if (written.isEmpty()) {
            return " No workflow version had been written.";
        }
        return " Workflow versions already written (nothing references them yet): " + String.join(", ", written) + ".";
    }

    /**
     * A failure in the apply phase. The agent was NOT changed, so the caller can
     * retry from the same agent version; any workflow versions written first are
     * harmless orphans. A 409 from a store (a concurrent edit that slipped in after
     * the version pre-check) stays a conflict.
     */
    private static CascadeException partialFailure(String agentId, int agentVersion, List<String> written, String doing, Throwable cause) {
        boolean conflict = cause instanceof WebApplicationException web && web.getResponse() != null
                && web.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode();
        String why = cause == null ? "" : ": " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
        String message = "Cascade stopped while " + doing + why + ". Agent " + agentId + " was NOT changed (still at version " + agentVersion
                + ")." + writtenNote(written) + " Retry the cascade from the agent's current version; unreferenced workflow versions are harmless.";
        CascadeException result = new CascadeException(conflict ? CascadeException.Kind.CONFLICT : CascadeException.Kind.FAILED, message);
        if (cause != null) {
            result.initCause(cause);
        }
        return result;
    }

    /**
     * Redeploys through the normal deploy endpoint. A waited deploy answers 200
     * whether or not it worked — a timeout or a failed build is reported in the
     * body — so the body, not the HTTP status, says whether it is live. A refusal
     * (quota, a vault key the agent was not granted) is reported rather than
     * thrown: the documents are already written and the caller needs to know
     * exactly that.
     */
    private AgentCascadeResult.Deployment redeploy(Deployment.Environment environment, String agentId, int version) {
        try {
            Response deployResponse = agentAdmin.deployAgent(environment, agentId, version, true, true);
            Object status = null;
            Object error = null;
            DeploymentFailure failure = null;
            if (deployResponse.getEntity() instanceof Map<?, ?> body) {
                status = body.get("status");
                error = body.get("error");
                // The waited deploy now says WHY (a missing vault grant names the secrets
                // and the fix); carry it, or the caller only sees "Deployment failed".
                if (body.get("failure") instanceof DeploymentFailure reported) {
                    failure = reported;
                }
            }
            boolean redeployed = deployResponse.getStatus() == 200 && error == null && Deployment.Status.READY.name().equals(String.valueOf(status));
            // The previous version stays deployed: deploy never retires a version, and
            // undeploying would also disable the agent's schedules.
            return new AgentCascadeResult.Deployment(environment.name(), redeployed, status == null ? null : String.valueOf(status),
                    error == null ? null : String.valueOf(error), true, failure);
        } catch (Exception deployErr) {
            return new AgentCascadeResult.Deployment(null, false, null, "Redeployment failed: " + deployErr.getMessage(), true, null);
        }
    }

    /**
     * Checks every pair and returns them as {@code oldUri -> newUri}, in order.
     */
    static Map<String, String> validate(List<ResourceMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "resourceMappings must contain at least one {oldUri, newUri} pair");
        }
        Map<String, String> newByOld = new LinkedHashMap<>();
        for (ResourceMapping mapping : mappings) {
            if (mapping == null || isBlank(mapping.oldUri()) || isBlank(mapping.newUri())) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "Every mapping needs both oldUri and newUri");
            }
            Matcher oldMatch = VERSIONED_RESOURCE_URI.matcher(mapping.oldUri());
            Matcher newMatch = VERSIONED_RESOURCE_URI.matcher(mapping.newUri());
            if (!oldMatch.matches()) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST,
                        "oldUri must look like eddi://<type>/<store>/<id>?version=<n>, got: " + mapping.oldUri());
            }
            if (!newMatch.matches()) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST,
                        "newUri must look like eddi://<type>/<store>/<id>?version=<n>, got: " + mapping.newUri());
            }
            if (!oldMatch.group(1).equals(newMatch.group(1))) {
                // A step's reference can only move to another document of the SAME kind.
                // Without this a mapping could re-aim an llm step at a rule set, or an
                // agent's workflow reference at some other store's document.
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST,
                        "oldUri and newUri must name the same kind of resource (" + oldMatch.group(1) + " vs " + newMatch.group(1) + ")");
            }
            if (mapping.oldUri().equals(mapping.newUri())) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "newUri is the same as oldUri: " + mapping.oldUri());
            }
            String previous = newByOld.put(mapping.oldUri(), mapping.newUri());
            if (previous != null && !previous.equals(mapping.newUri())) {
                throw new CascadeException(CascadeException.Kind.BAD_REQUEST, "oldUri is mapped twice with different targets: " + mapping.oldUri());
            }
        }
        return newByOld;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private <T> T rest(Class<T> type) {
        try {
            return restInterfaceFactory.get(type);
        } catch (RestInterfaceFactoryException e) {
            throw new IllegalStateException("Failed to get REST proxy for " + type.getSimpleName(), e);
        }
    }

    /**
     * The id: last path segment before any query, or {@code null} if there is none.
     */
    private static String idOf(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : null;
    }

    /** The {@code version=} query value, or 1 when absent or unreadable. */
    private static int versionOf(String uri) {
        if (uri == null || !uri.contains("version=")) {
            return 1;
        }
        try {
            int start = uri.indexOf("version=") + "version=".length();
            int end = uri.indexOf('&', start);
            return Integer.parseInt((end > 0 ? uri.substring(start, end) : uri.substring(start)).trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * One workflow the agent references, as it will be after the cascade.
     *
     * @param referenced
     *            what the agent referenced before
     * @param effective
     *            what it references before step rewrites — {@code referenced}, or
     *            the mapping's target when the workflow itself was repointed
     */
    private record PlannedWorkflow(URI referenced, URI effective, String workflowId, int version, WorkflowConfiguration workflow, boolean repointed,
            boolean stepsUpdated) {
        PlannedWorkflow withRepointed(boolean value) {
            return new PlannedWorkflow(referenced, effective, workflowId, version, workflow, value, stepsUpdated);
        }
    }
}
