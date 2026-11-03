/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlConfigValidation;
import ai.labs.eddi.configs.hitl.model.AutonomyPolicyConfig;
import ai.labs.eddi.configs.hitl.model.OversightConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.OversightLedger;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Recommendation;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState.Suspension;
import ai.labs.eddi.engine.hitl.oversight.samples.AuditSample;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Earned autonomy: evaluates gated categories into recommendations, applies or
 * dismisses them on a human's request, suspends a category when a reviewer
 * flags what it did, and lifts a suspension on a human's request.
 * <p>
 * The asymmetry is deliberate. Nothing ungates without a human: a promotion is
 * a recommendation, and applying it writes a new agent version through the
 * audited agent store (it does not deploy it). Re-gating is automatic, a
 * runtime overlay ({@link AutonomyOverlayService}) that never rewrites
 * configuration; only a human lifts it, and a suspension never expires.
 * <p>
 * The public methods are what the REST resources (and the follow-up MCP read
 * tools) call. Callers check access; this class checks state.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AutonomyService {

    private static final Logger LOGGER = Logger.getLogger(AutonomyService.class);

    /** Upper bound of the decisions read for one evaluation. */
    static final int MAX_EVIDENCE_ROWS = 20_000;
    /** Compare-and-set retries before a write gives up. */
    static final int CAS_ATTEMPTS = 5;
    /** Upper bound of a dismissal or lift note. */
    public static final int MAX_NOTE_LENGTH = 2000;

    private final IAutonomyStateStore stateStore;
    private final IApprovalDecisionStore decisionStore;
    private final IAuditSampleStore sampleStore;
    private final OversightConfigLookup configLookup;
    private final IAgentStore agentStore;
    private final IRestAgentStore restAgentStore;
    private final AutonomyOverlayService overlay;
    private final OversightLedger ledger;
    private final OversightMetrics metrics;
    private final Clock clock;

    @Inject
    public AutonomyService(IAutonomyStateStore stateStore, IApprovalDecisionStore decisionStore, IAuditSampleStore sampleStore,
            OversightConfigLookup configLookup, IAgentStore agentStore, IRestAgentStore restAgentStore, AutonomyOverlayService overlay,
            OversightLedger ledger, OversightMetrics metrics) {
        this(stateStore, decisionStore, sampleStore, configLookup, agentStore, restAgentStore, overlay, ledger, metrics, Clock.systemUTC());
    }

    /** Test seam: a fixed clock. */
    public AutonomyService(IAutonomyStateStore stateStore, IApprovalDecisionStore decisionStore, IAuditSampleStore sampleStore,
            OversightConfigLookup configLookup, IAgentStore agentStore, IRestAgentStore restAgentStore, AutonomyOverlayService overlay,
            OversightLedger ledger, OversightMetrics metrics, Clock clock) {
        this.stateStore = stateStore;
        this.decisionStore = decisionStore;
        this.sampleStore = sampleStore;
        this.configLookup = configLookup;
        this.agentStore = agentStore;
        this.restAgentStore = restAgentStore;
        this.overlay = overlay;
        this.ledger = ledger;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What the API shows of one category. */
    public record CategoryView(String agentId, String category, String tier, String pattern, AutonomyState state,
            AutonomyState.Evidence evidence, String evidenceStatus, AutonomyPolicyConfig.Resolved policy) {
    }

    // === Reads ===

    /** The stored states of an agent (null = all agents), with their live tier. */
    public List<CategoryView> list(String agentId, int limit) {
        List<CategoryView> views = new ArrayList<>();
        for (AutonomyState state : stateStore.list(agentId, limit)) {
            if (state.systemRow()) {
                continue;
            }
            views.add(new CategoryView(state.agentId(), state.category(), tierOf(state), patternOf(state), state, null, null, null));
        }
        return views;
    }

    /**
     * One category: its state, and its evidence evaluated now against the newest
     * agent version's policy.
     */
    public CategoryView get(String agentId, String category) {
        AutonomyState state = stateStore.read(agentId, category).orElse(AutonomyState.initial(agentId, category));
        AgentConfiguration.HitlConfig hitl = configLookup.hitlConfig(agentId, null);
        AutonomyPolicyConfig.Resolved policy = policyOf(hitl, category);
        String pattern = state.suspended() ? state.suspension().pattern() : PromotionPatchBuilder.patternOf(hitl, category);
        if (policy == null) {
            return new CategoryView(agentId, category, tierOf(state), pattern, state, null, "the agent's autonomyPolicy does not name it",
                    null);
        }
        AutonomyEvaluator.Result result = evaluate(agentId, category, policy, state);
        return new CategoryView(agentId, category, tierOf(state), pattern, state, result.evidence(), result.reason(), policy);
    }

    // === Evaluation (maintenance) ===

    /**
     * Evaluates every category the agent's policy names: opens a recommendation
     * where the evidence holds, marks one STALE where it no longer does or the
     * agent changed.
     *
     * @return recommendations opened
     */
    public int evaluateAgent(String agentId, int agentVersion, AgentConfiguration.HitlConfig hitl) {
        OversightConfig oversight = hitl != null ? hitl.getOversight() : null;
        AutonomyPolicyConfig policyConfig = oversight != null ? oversight.getAutonomyPolicy() : null;
        if (policyConfig == null || policyConfig.getCategories() == null) {
            return 0;
        }
        int opened = 0;
        for (String category : policyConfig.getCategories().keySet()) {
            try {
                if (evaluateCategory(agentId, agentVersion, hitl, category)) {
                    opened++;
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("Could not evaluate earned autonomy of agent %s category %s: %s", sanitize(agentId), sanitize(category),
                        e.getMessage());
            }
        }
        return opened;
    }

    boolean evaluateCategory(String agentId, int agentVersion, AgentConfiguration.HitlConfig hitl, String category) {
        AutonomyPolicyConfig.Resolved policy = policyOf(hitl, category);
        if (policy == null) {
            return false;
        }
        AutonomyState state = stateStore.read(agentId, category).orElse(AutonomyState.initial(agentId, category));
        if (state.suspended()) {
            return false;
        }
        AutonomyEvaluator.Result result = evaluate(agentId, category, policy, state);
        Recommendation current = state.recommendation();
        boolean open = state.hasOpenRecommendation() && current.kind() == Recommendation.Kind.PROMOTE;
        if (open && (!result.eligible() || current.basedOnAgentVersion() == null || current.basedOnAgentVersion() != agentVersion)) {
            update(agentId, category, s -> s.recommendation() != null && s.recommendation().id().equals(current.id())
                    ? s.withRecommendation(s.recommendation().withStatus(Recommendation.Status.STALE))
                    : s);
            open = false;
        }
        if (!result.eligible() || open) {
            return false;
        }
        String pattern = PromotionPatchBuilder.patternOf(hitl, category);
        Recommendation recommendation;
        if (pattern == null) {
            recommendation = new Recommendation(UUID.randomUUID().toString(), Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN,
                    clock.instant(), agentVersion, result.evidence(), null, false, null,
                    List.of(advisoryText(category)), false, null, null, null, null, null, null);
        } else {
            PromotionPatchBuilder.Patch patch = PromotionPatchBuilder.promote(hitl, category, pattern, policy.promotedSampleRate());
            recommendation = new Recommendation(UUID.randomUUID().toString(), Recommendation.Kind.PROMOTE, Recommendation.Status.OPEN,
                    clock.instant(), agentVersion, result.evidence(), pattern, true, patch.preview(), patch.changes(), patch.leavesNoGate(),
                    null, null, null, null, null, null);
        }
        Optional<AutonomyState> saved = update(agentId, category, s -> s.suspended() || s.hasOpenRecommendation()
                ? s
                : s.withRecommendation(recommendation));
        boolean openedNow = saved.isPresent() && saved.get().recommendation() != null
                && recommendation.id().equals(saved.get().recommendation().id());
        if (openedNow) {
            metrics.recommendation(Recommendation.Kind.PROMOTE.name());
            LOGGER.infof("Earned autonomy: agent %s category %s is ready to promote (%d decisions, %.2f unedited)", sanitize(agentId),
                    sanitize(category), result.evidence().n(), result.evidence().uneditedRate());
        }
        return openedNow;
    }

    private AutonomyEvaluator.Result evaluate(String agentId, String category, AutonomyPolicyConfig.Resolved policy, AutonomyState state) {
        Instant now = clock.instant();
        Instant from = AutonomyEvaluator.windowStart(policy, state, now);
        List<ApprovalDecision> decisions = decisionStore.query(DecisionFilter.ofCategory(agentId, category, from, null), 0,
                MAX_EVIDENCE_ROWS);
        return AutonomyEvaluator.evaluate(policy, decisions, state, now);
    }

    // === Decision and sample triggers ===

    /**
     * A rejection carrying one of the category's demote reason codes resets its
     * evidence and makes an open promotion stale. Called by the projector for the
     * rows it inserted.
     */
    public void onDecisions(List<ApprovalDecision> rows) {
        for (ApprovalDecision row : rows) {
            if (!"REJECTED".equals(row.outcome()) || row.reasonCode() == null || row.agentId() == null || row.legacy()) {
                continue;
            }
            AutonomyPolicyConfig.Resolved policy = policyOf(configLookup.hitlConfig(row.agentId(), row.agentVersion()), row.category());
            if (policy == null || !policy.demoteOnReasonCodes().contains(row.reasonCode())) {
                continue;
            }
            Instant at = row.decidedAt() != null ? row.decidedAt() : clock.instant();
            update(row.agentId(), row.category(), s -> {
                AutonomyState next = s.evidenceResetAt() == null || s.evidenceResetAt().isBefore(at) ? s.withEvidenceResetAt(at) : s;
                if (next.hasOpenRecommendation() && next.recommendation().kind() == Recommendation.Kind.PROMOTE) {
                    next = next.withRecommendation(next.recommendation().withStatus(Recommendation.Status.STALE));
                }
                return next;
            });
            LOGGER.infof("Earned autonomy: rejection with %s reset the evidence of agent %s category %s", sanitize(row.reasonCode()),
                    sanitize(row.agentId()), sanitize(row.category()));
        }
    }

    /**
     * A reviewer flagged a sample: suspends its category at once when the reason
     * code is a demote code, or once enough samples were flagged within the window.
     * A category the policy does not name is never suspended automatically.
     *
     * @return the suspension, when one was set
     */
    public Optional<AutonomyState> onSampleFlagged(AuditSample sample) {
        if (sample.status() != AuditSample.Status.FLAGGED || sample.agentId() == null) {
            return Optional.empty();
        }
        AgentConfiguration.HitlConfig hitl = configLookup.hitlConfig(sample.agentId(), sample.agentVersion());
        AutonomyPolicyConfig.Resolved policy = policyOf(hitl, sample.category());
        if (policy == null) {
            return Optional.empty();
        }
        if (sample.reasonCode() != null && policy.demoteOnReasonCodes().contains(sample.reasonCode())) {
            return suspend(sample.agentId(), sample.category(), sample.rulePattern(), Suspension.Trigger.REASON_CODE, sample.id(),
                    sample.reasonCode(), "system:audit-sample", null);
        }
        if (policy.demoteOnFlaggedSamples() <= 0) {
            return Optional.empty();
        }
        AutonomyState state = stateStore.read(sample.agentId(), sample.category()).orElse(null);
        Instant from = AutonomyEvaluator.windowStart(policy, state, clock.instant());
        long flagged = sampleStore.count(new SampleFilter(sample.agentId(), sample.category(), AuditSample.Status.FLAGGED, from, null));
        if (flagged >= policy.demoteOnFlaggedSamples()) {
            return suspend(sample.agentId(), sample.category(), sample.rulePattern(), Suspension.Trigger.SAMPLE_FLAGGED, sample.id(),
                    sample.reasonCode(), "system:audit-sample", null);
        }
        return Optional.empty();
    }

    // === Human actions ===

    /** A human suspends a tool category by hand. */
    public AutonomyState suspendManually(String agentId, String category, String note, String actor) {
        String text = requireNote(note);
        String pattern = PromotionPatchBuilder.patternOf(configLookup.hitlConfig(agentId, null), category);
        if (pattern == null) {
            throw OversightErrors.badRequest(OversightErrors.NOT_A_TOOL_CATEGORY,
                    "Only a tool category can be suspended: '" + category + "' names no tool pattern of this agent");
        }
        return suspend(agentId, category, pattern, Suspension.Trigger.MANUAL, null, null, actor, text)
                .orElseThrow(() -> OversightErrors.conflict(OversightErrors.CONCURRENT_CHANGE, "The category changed meanwhile; try again"));
    }

    /** A human lifts a suspension; the category is ungated again. */
    public AutonomyState lift(String agentId, String category, String note, String actor) {
        String text = requireNote(note);
        AutonomyState state = stateStore.read(agentId, category)
                .orElseThrow(() -> OversightErrors.notFound(OversightErrors.STATE_NOT_FOUND, "No autonomy state for this category"));
        if (!state.suspended()) {
            throw OversightErrors.conflict(OversightErrors.NOT_SUSPENDED, "The category is not suspended");
        }
        Instant now = clock.instant();
        AutonomyState lifted = update(agentId, category, s -> {
            if (!s.suspended()) {
                return s;
            }
            AutonomyState next = s.withSuspension(s.suspension().lifted(actor, now, text));
            if (next.hasOpenRecommendation() && next.recommendation().kind() == Recommendation.Kind.REVERT) {
                next = next.withRecommendation(next.recommendation().withStatus(Recommendation.Status.STALE));
            }
            return next;
        }).orElseThrow(() -> OversightErrors.conflict(OversightErrors.CONCURRENT_CHANGE, "The category changed meanwhile; try again"));
        overlay.invalidate(agentId);
        ledger.write(OversightLedger.LIFTED, null, agentId, null, actor,
                details("category", category, "pattern", state.suspension().pattern(), "note", text));
        return lifted;
    }

    /** A human dismisses an open recommendation. */
    public AutonomyState dismiss(String agentId, String category, String recommendationId, String note, String actor) {
        String text = requireNote(note);
        AutonomyState state = requireOpen(agentId, category, recommendationId);
        AutonomyPolicyConfig.Resolved policy = policyOf(configLookup.hitlConfig(agentId, null), category);
        int promoteAfter = policy != null ? policy.promoteAfter() : AutonomyPolicyConfig.DEFAULT_PROMOTE_AFTER;
        int seen = state.recommendation().evidence() != null ? state.recommendation().evidence().n() : 0;
        Instant now = clock.instant();
        AutonomyState dismissed = update(agentId, category, s -> s.hasOpenRecommendation() && s.recommendation().id().equals(recommendationId)
                ? s.withRecommendation(s.recommendation().dismissed(actor, now, text)).withSuppressUntilCount(seen + promoteAfter)
                : s).orElseThrow(() -> OversightErrors.conflict(OversightErrors.CONCURRENT_CHANGE, "The category changed meanwhile"));
        ledger.write(OversightLedger.DISMISSAL, null, agentId, null, actor,
                details("category", category, "recommendationId", recommendationId, "kind", state.recommendation().kind().name(), "note",
                        text));
        return dismissed;
    }

    /**
     * A human applies an open recommendation: re-checks the evidence (a promotion),
     * builds the change on the agent version the human saw, validates it and writes
     * a new version through the agent store. It does not deploy it.
     *
     * @return the state after applying, carrying the resulting version
     */
    public AutonomyState apply(String agentId, String category, String recommendationId, Integer expectedAgentVersion, String actor) {
        AutonomyState state = requireOpen(agentId, category, recommendationId);
        Recommendation recommendation = state.recommendation();
        if (!recommendation.applicable() || recommendation.pattern() == null) {
            throw OversightErrors.badRequest(OversightErrors.NOT_APPLICABLE,
                    "This recommendation is advisory: change the configuration it names by hand");
        }
        int current;
        AgentConfiguration agent;
        try {
            current = configLookup.currentVersion(agentId);
            agent = agentStore.read(agentId, current);
        } catch (Exception e) {
            throw OversightErrors.notFound(OversightErrors.STATE_NOT_FOUND, "The agent could not be read");
        }
        if (expectedAgentVersion == null || current != expectedAgentVersion
                || (recommendation.kind() == Recommendation.Kind.PROMOTE && !Integer.valueOf(current).equals(recommendation.basedOnAgentVersion()))) {
            throw OversightErrors.conflict(OversightErrors.AGENT_CHANGED,
                    "The agent is at version " + current + " now; reload the recommendation and review it again");
        }
        AgentConfiguration.HitlConfig hitl = agent.getHitlConfig();
        PromotionPatchBuilder.Patch patch;
        AutonomyState.Evidence evidence = recommendation.evidence();
        if (recommendation.kind() == Recommendation.Kind.PROMOTE) {
            AutonomyPolicyConfig.Resolved policy = policyOf(hitl, category);
            if (policy == null) {
                throw OversightErrors.conflict(OversightErrors.EVIDENCE_CHANGED, "The agent's autonomyPolicy no longer names this category");
            }
            AutonomyEvaluator.Result now = evaluate(agentId, category, policy, state);
            if (!now.eligible()) {
                throw OversightErrors.conflict(OversightErrors.EVIDENCE_CHANGED,
                        "The evidence no longer supports this recommendation (" + now.reason() + ")");
            }
            evidence = now.evidence();
            patch = PromotionPatchBuilder.promote(hitl, category, recommendation.pattern(), policy.promotedSampleRate());
        } else {
            List<String> covering = PromotionPatchBuilder.coveringExemptions(hitl, recommendation.pattern());
            if (!covering.isEmpty()) {
                // The suspension stays: lifting it would leave the pattern ungated.
                throw OversightErrors.badRequest(OversightErrors.NOT_APPLICABLE, "The exempt entry " + covering
                        + " still covers '" + recommendation.pattern() + "' and outranks requireApproval: narrow or remove it by hand,"
                        + " then apply again. The suspension stays in force until then");
            }
            patch = PromotionPatchBuilder.revert(hitl, recommendation.pattern());
        }
        try {
            HitlConfigValidation.validate(patch.after());
        } catch (IllegalArgumentException invalid) {
            throw OversightErrors.badRequest(OversightErrors.INVALID_RESULT,
                    "The change would not be a valid configuration: " + invalid.getMessage());
        }
        agent.setHitlConfig(patch.after());
        int resulting;
        try {
            restAgentStore.updateAgent(agentId, current, agent, false);
            resulting = configLookup.currentVersion(agentId);
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof IResourceStore.ResourceModifiedException) {
                throw OversightErrors.conflict(OversightErrors.AGENT_CHANGED, "The agent was changed meanwhile; reload and review again");
            }
            throw new IllegalStateException("Could not write the new agent version: " + e.getMessage(), e);
        }
        Instant at = clock.instant();
        boolean revert = recommendation.kind() == Recommendation.Kind.REVERT;
        AutonomyState applied = update(agentId, category, s -> {
            AutonomyState next = s.recommendation() != null && s.recommendation().id().equals(recommendationId)
                    ? s.withRecommendation(s.recommendation().applied(actor, at, resulting))
                    : s;
            if (revert && next.suspended()) {
                next = next.withSuspension(next.suspension().lifted(actor, at, "made permanent in agent version " + resulting));
            }
            if (!revert) {
                // Promotion evidence starts over: the next question is whether to keep it.
                next = next.withEvidenceResetAt(at).withSuppressUntilCount(null);
            }
            return next;
        }).orElseThrow(() -> OversightErrors.conflict(OversightErrors.CONCURRENT_CHANGE, "The category changed meanwhile"));
        overlay.invalidate(agentId);
        metrics.promotionApplied();
        ledger.write(revert ? OversightLedger.REVERT : OversightLedger.PROMOTION, null, agentId, resulting, actor,
                details("category", category, "pattern", recommendation.pattern(), "recommendationId", recommendationId, "fromVersion",
                        current, "toVersion", resulting, "changes", patch.changes(), "evidence",
                        evidence != null ? OversightJson.copy(evidence, Map.class) : null, "leavesNoGate",
                        patch.leavesNoGate()));
        LOGGER.infof("Earned autonomy: %s applied %s for agent %s category %s (v%d -> v%d)", sanitize(actor),
                recommendation.kind(), sanitize(agentId), sanitize(category), current, resulting);
        return applied;
    }

    // === Internals ===

    /**
     * Suspends a category (idempotent: an active suspension is returned as is).
     * Resets its evidence, opens a REVERT ("make permanent") recommendation, and
     * writes {@code autonomy.demotion}.
     */
    Optional<AutonomyState> suspend(String agentId, String category, String pattern, Suspension.Trigger trigger, String triggerRef,
                                    String reasonCode, String actor, String note) {
        if (pattern == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        AgentConfiguration agent = configLookup.agent(agentId, null);
        Integer version = null;
        try {
            version = configLookup.currentVersion(agentId);
        } catch (Exception e) {
            LOGGER.debugf("No current version of %s: %s", sanitize(agentId), e.getMessage());
        }
        Recommendation revert = null;
        if (agent != null) {
            PromotionPatchBuilder.Patch patch = PromotionPatchBuilder.revert(agent.getHitlConfig(), pattern);
            revert = new Recommendation(UUID.randomUUID().toString(), Recommendation.Kind.REVERT, Recommendation.Status.OPEN, now, version,
                    null, pattern, true, patch.preview(), patch.changes(), false, null, null, null, null, null, null);
        }
        Recommendation revertRecommendation = revert;
        boolean[] newlySuspended = {false};
        Optional<AutonomyState> saved = update(agentId, category, s -> {
            if (s.suspended()) {
                return s;
            }
            newlySuspended[0] = true;
            AutonomyState next = s.withSuspension(new Suspension(true, pattern, now, trigger, triggerRef, reasonCode, actor, null, null, note))
                    .withEvidenceResetAt(now).withLastDemotionAt(now);
            return revertRecommendation != null ? next.withRecommendation(revertRecommendation) : next;
        });
        if (saved.isPresent() && newlySuspended[0]) {
            overlay.invalidate(agentId);
            metrics.demotion(trigger.name());
            if (revertRecommendation != null) {
                metrics.recommendation(Recommendation.Kind.REVERT.name());
            }
            ledger.write(OversightLedger.DEMOTION, null, agentId, version, actor, details("category", category, "pattern", pattern,
                    "trigger", trigger.name(), "triggerRef", triggerRef, "reasonCode", reasonCode, "note", note));
            LOGGER.warnf("Earned autonomy: agent %s category %s suspended (%s) — '%s' calls need approval again", sanitize(agentId),
                    sanitize(category), trigger, sanitize(pattern));
        }
        return saved;
    }

    private AutonomyState requireOpen(String agentId, String category, String recommendationId) {
        AutonomyState state = stateStore.read(agentId, category)
                .orElseThrow(() -> OversightErrors.notFound(OversightErrors.STATE_NOT_FOUND, "No autonomy state for this category"));
        if (!state.hasOpenRecommendation() || recommendationId == null || !recommendationId.equals(state.recommendation().id())) {
            throw OversightErrors.conflict(OversightErrors.RECOMMENDATION_CHANGED,
                    "This recommendation is no longer open; reload the category");
        }
        return state;
    }

    /** Read-modify-write with compare-and-set, retried on a concurrent change. */
    Optional<AutonomyState> update(String agentId, String category, UnaryOperator<AutonomyState> change) {
        for (int attempt = 0; attempt < CAS_ATTEMPTS; attempt++) {
            AutonomyState current = stateStore.read(agentId, category).orElse(AutonomyState.initial(agentId, category));
            AutonomyState next = change.apply(current);
            if (next == current) {
                return Optional.of(current);
            }
            Optional<AutonomyState> saved = stateStore.save(next, current.version());
            if (saved.isPresent()) {
                return saved;
            }
        }
        LOGGER.warnf("Gave up writing the autonomy state of agent %s category %s after %d concurrent changes", sanitize(agentId),
                sanitize(category), CAS_ATTEMPTS);
        return Optional.empty();
    }

    static AutonomyPolicyConfig.Resolved policyOf(AgentConfiguration.HitlConfig hitl, String category) {
        if (hitl == null || hitl.getOversight() == null || hitl.getOversight().getAutonomyPolicy() == null) {
            return null;
        }
        return hitl.getOversight().getAutonomyPolicy().resolve(category);
    }

    static String tierOf(AutonomyState state) {
        if (state.suspended()) {
            return "SUSPENDED";
        }
        Recommendation r = state.recommendation();
        if (r != null && r.kind() == Recommendation.Kind.PROMOTE) {
            if (r.status() == Recommendation.Status.APPLIED) {
                return "PROMOTED";
            }
            if (r.status() == Recommendation.Status.OPEN) {
                return "READY";
            }
        }
        return "GATED";
    }

    private static String patternOf(AutonomyState state) {
        if (state.suspension() != null) {
            return state.suspension().pattern();
        }
        return state.recommendation() != null ? state.recommendation().pattern() : null;
    }

    private static String advisoryText(String category) {
        if (!category.startsWith("group:")) {
            return "This category is a behavior-rule pause, not a tool call: review the behavior rule that emits PAUSE_CONVERSATION "
                    + "for '" + category + "' — earned autonomy cannot change it automatically";
        }
        return "This category is a group phase or task pause: review the group's hitlConfig for '" + category
                + "' — earned autonomy cannot change it automatically";
    }

    private static String requireNote(String note) {
        String text = note != null ? note.strip() : "";
        if (text.isEmpty()) {
            throw OversightErrors.badRequest(OversightErrors.NOTE_REQUIRED, "A note is required");
        }
        if (text.codePointCount(0, text.length()) > MAX_NOTE_LENGTH) {
            throw OversightErrors.badRequest(OversightErrors.NOTE_TOO_LONG, "The note may be at most " + MAX_NOTE_LENGTH + " characters");
        }
        return text;
    }

    private static Map<String, Object> details(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
            }
        }
        return map;
    }
}
