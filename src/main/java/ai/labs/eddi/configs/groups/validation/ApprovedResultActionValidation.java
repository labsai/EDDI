/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.hitl.HitlGranularity;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.apicalls.impl.SecretContextReferences;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Save-time (and run-time) rules for a phase's {@code onApproved} action.
 * <p>
 * Two halves. {@link #structuralProblems} needs nothing but the configuration
 * and always runs: the action belongs on an {@code EXECUTE} phase with
 * {@code requiresApproval} in a group gated at {@code TASK} granularity — on
 * any other phase no task is ever approved, so the action could never run and
 * saving it would only hide the mistake. {@link #referenceProblems} reads the
 * referenced {@code httpcalls} configuration: it must exist at the pinned
 * version and name the call, the call must return an outcome (no
 * fire-and-forget, batch or forEach), and its credentials must suit a caller
 * that is a group, not an agent.
 * <p>
 * <b>Credentials.</b> A group is not an agent, so the deploy-time vault grant
 * check has no identity to test. The rule is the SERVICE judge's
 * ({@link ServiceJudgeVaultGrants}): every {@code ${vault:...}} reference in
 * the call must name a secret granted to all agents. {@code ${connection:...}},
 * {@code ${caller:...}} and {@code ${context:...}} are refused outright: the
 * action runs after the approval, in the background, with no request whose
 * caller or context they could mean.
 */
@ApplicationScoped
public class ApprovedResultActionValidation {

    private final IApiCallsStore apiCallsStore;
    private final ServiceJudgeVaultGrants vaultGrants;
    private final Instance<ResourceAccessGuard> accessGuard;

    @Inject
    public ApprovedResultActionValidation(IApiCallsStore apiCallsStore, ServiceJudgeVaultGrants vaultGrants,
            Instance<ResourceAccessGuard> accessGuard) {
        this.apiCallsStore = apiCallsStore;
        this.vaultGrants = vaultGrants;
        this.accessGuard = accessGuard;
    }

    /**
     * The configuration-only problems of every {@code onApproved} in
     * {@code config.phases}. Empty = valid.
     */
    public static List<String> structuralProblems(AgentGroupConfiguration config) {
        List<String> problems = new ArrayList<>();
        List<DiscussionPhase> phases = config.getPhases() != null ? config.getPhases() : List.of();
        boolean taskGranularity = config.getHitlConfig() != null && config.getHitlConfig().getGranularity() == HitlGranularity.TASK;
        for (int i = 0; i < phases.size(); i++) {
            DiscussionPhase phase = phases.get(i);
            if (phase == null || phase.onApproved() == null) {
                continue;
            }
            String path = "phases[" + i + "].onApproved";
            if (phase.type() != PhaseType.EXECUTE) {
                problems.add(path + " is only valid on an EXECUTE phase (this one is " + phase.type()
                        + ") — move it to the EXECUTE phase whose task results it should act on");
            }
            if (!phase.requiresApproval()) {
                problems.add(path + " needs requiresApproval: true on the same phase — it runs when a human approves a task, "
                        + "and without the gate no task is ever approved");
            }
            if (!taskGranularity) {
                problems.add(path + " needs hitlConfig.granularity: TASK — it acts on each approved task, and at PHASE "
                        + "granularity tasks are not approved one by one");
            }
            ApprovedAction action = phase.onApproved().action();
            if (action == null) {
                problems.add(path + ".action is required: { \"type\": \"HTTPCALL\", \"uri\": \"eddi://ai.labs.apicalls/apicallstore/"
                        + "apicalls/<id>?version=<n>\", \"call\": \"<call name>\" }");
                continue;
            }
            if (action.call() == null || action.call().isBlank()) {
                problems.add(path + ".action.call is required — the name of the call inside the httpcalls configuration");
            }
            String uriProblem = uriProblem(action.uri());
            if (uriProblem != null) {
                problems.add(path + ".action.uri " + uriProblem);
            }
        }
        return problems;
    }

    /**
     * The structural problems plus those of each referenced {@code httpcalls} call.
     * Empty = valid.
     */
    public List<String> problems(AgentGroupConfiguration config) {
        List<String> problems = structuralProblems(config);
        if (!problems.isEmpty()) {
            return problems;
        }
        List<DiscussionPhase> phases = config.getPhases() != null ? config.getPhases() : List.of();
        for (int i = 0; i < phases.size(); i++) {
            DiscussionPhase phase = phases.get(i);
            if (phase != null && phase.onApproved() != null) {
                requireUseAccess(phase.onApproved().action().uri());
                problems.addAll(referenceProblems("phases[" + i + "].onApproved.action", phase.onApproved().action()));
            }
        }
        return problems;
    }

    /**
     * The problems of one action's reference, also re-checked just before the
     * action runs — a grant narrowed or a call removed after the save is caught.
     */
    public List<String> referenceProblems(String path, ApprovedAction action) {
        List<String> problems = new ArrayList<>();
        String uriProblem = action != null ? uriProblem(action.uri()) : "is required";
        if (uriProblem != null) {
            problems.add(path + ".uri " + uriProblem);
            return problems;
        }
        IResourceStore.IResourceId id = RestUtilities.extractResourceId(URI.create(action.uri().trim()));
        ApiCallsConfiguration httpCalls;
        try {
            httpCalls = apiCallsStore.read(id.getId(), id.getVersion());
        } catch (IResourceStore.ResourceNotFoundException e) {
            problems.add(path + ".uri names httpcalls " + LogSanitizer.sanitize(id.getId()) + " version " + id.getVersion()
                    + ", which does not exist — create it first or fix the id/version");
            return problems;
        } catch (IResourceStore.ResourceStoreException e) {
            problems.add(path + ".uri could not be read (" + LogSanitizer.sanitize(e.getMessage()) + ") — retry when the store is available");
            return problems;
        }
        if (httpCalls == null) {
            problems.add(path + ".uri names an httpcalls configuration that does not exist");
            return problems;
        }
        ApiCall call = findCall(httpCalls, action.call());
        if (call == null) {
            problems.add(path + ".call '" + LogSanitizer.sanitize(action.call()) + "' is not a call of that httpcalls configuration"
                    + " — use one of " + callNames(httpCalls));
            return problems;
        }
        if (Boolean.TRUE.equals(call.getFireAndForget()) || Boolean.TRUE.equals(call.getIsBatchCalls()) || call.getForEach() != null) {
            problems.add(path + ".call '" + LogSanitizer.sanitize(action.call()) + "' is fire-and-forget, batched or forEach — an "
                    + "approved-result action must make exactly one request and see its outcome; use a plain call");
        }
        if (call.getPreRequest() != null && call.getPreRequest().getPropertyInstructions() != null) {
            // The executor rebuilds the template data from conversation memory after
            // pre-request instructions, which would drop task.*, group.* and action.*.
            problems.add(path + ".call '" + LogSanitizer.sanitize(action.call()) + "' has preRequest.propertyInstructions — an "
                    + "approved-result action renders with the approved task's data, which those instructions would replace; "
                    + "remove them or use another call");
        }
        if (call.getRequest() == null) {
            problems.add(path + ".call '" + LogSanitizer.sanitize(action.call()) + "' has no request");
            return problems;
        }
        Map<String, String> fields = requestFields(httpCalls, call);
        fields.forEach((field, value) -> {
            if (ConnectionResolver.containsReference(value) || CallerIdentityResolver.containsReference(value)
                    || SecretContextReferences.containsReference(value)) {
                problems.add(path + " call field " + field + " uses ${connection:...}, ${caller:...} or ${context:...} — an "
                        + "approved-result action runs for the group after the approval, with no caller or request context; "
                        + "use a ${vault:...} secret granted to all agents instead");
            }
        });
        problems.addAll(vaultGrants.problems(path + " call", fields));
        return problems;
    }

    /** The call of {@code httpCalls} named {@code name}, or null. */
    public static ApiCall findCall(ApiCallsConfiguration httpCalls, String name) {
        if (httpCalls == null || httpCalls.getHttpCalls() == null || name == null) {
            return null;
        }
        return httpCalls.getHttpCalls().stream().filter(c -> c != null && name.equals(c.getName())).findFirst().orElse(null);
    }

    private static List<String> callNames(ApiCallsConfiguration httpCalls) {
        return httpCalls.getHttpCalls() == null
                ? List.of()
                : httpCalls.getHttpCalls().stream().filter(c -> c != null && c.getName() != null).map(ApiCall::getName).toList();
    }

    /** Every author-written string of the request that may carry a reference. */
    private static Map<String, String> requestFields(ApiCallsConfiguration httpCalls, ApiCall call) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("targetServerUrl", httpCalls.getTargetServerUrl());
        fields.put("request.path", call.getRequest().getPath());
        // The URL as ApiCallExecutor joins it, so a reference split across the two
        // fields is checked as the one string the executor resolves.
        fields.put("request URL", effectiveUrl(httpCalls.getTargetServerUrl(), call.getRequest().getPath()));
        fields.put("request.body", call.getRequest().getBody());
        if (call.getRequest().getHeaders() != null) {
            call.getRequest().getHeaders().forEach((k, v) -> fields.put("request.headers." + k, v));
        }
        if (call.getRequest().getQueryParams() != null) {
            call.getRequest().getQueryParams().forEach((k, v) -> fields.put("request.queryParams." + k, v));
        }
        return fields;
    }

    /** Mirrors the path join in {@code ApiCallExecutor}'s request building. */
    static String effectiveUrl(String targetServerUrl, String rawPath) {
        String path = rawPath == null ? "" : rawPath.trim();
        if (!path.startsWith("/") && !path.isEmpty() && !path.startsWith("http")) {
            path = "/" + path;
        }
        return path.startsWith("http") ? path : (targetServerUrl == null ? "" : targetServerUrl) + path;
    }

    private static String uriProblem(String uri) {
        if (uri == null || uri.isBlank()) {
            return "is required — eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=<n>";
        }
        try {
            URI parsed = URI.create(uri.trim());
            // the v6 host, and the pre-v6 one stored configurations may still carry
            if (!"eddi".equals(parsed.getScheme())
                    || !("ai.labs.apicalls".equals(parsed.getHost()) || "ai.labs.httpcalls".equals(parsed.getHost()))) {
                return "must be an httpcalls URI (eddi://ai.labs.apicalls/apicallstore/apicalls/<id>?version=<n>)";
            }
            IResourceStore.IResourceId id = RestUtilities.extractResourceId(parsed);
            if (id == null || id.getId() == null) {
                return "carries no httpcalls id";
            }
            if (id.getVersion() == null || id.getVersion() < 1) {
                return "needs an explicit ?version=<n> — the call an approval triggers must not change under it";
            }
            return null;
        } catch (IllegalArgumentException e) {
            return "is not a valid URI";
        }
    }

    private void requireUseAccess(String uri) {
        if (accessGuard == null || !accessGuard.isResolvable()) {
            return;
        }
        IResourceStore.IResourceId id = RestUtilities.extractResourceId(URI.create(uri.trim()));
        try {
            // A group editor must not wire up somebody else's httpcalls configuration —
            // the call carries its author's target and credentials.
            accessGuard.get().requireUseAccess(id.getId(), "httpcalls configuration");
        } catch (ContextNotActiveException e) {
            // No request (startup, import replay): nobody to check — as for every other
            // store-level save made outside a request.
        }
    }
}
