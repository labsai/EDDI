/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.autonomy;

import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.engine.hitl.oversight.OversightConfigLookup;
import ai.labs.eddi.engine.hitl.oversight.OversightMetrics;
import ai.labs.eddi.engine.hitl.oversight.OversightSettings;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalBackfillService;
import ai.labs.eddi.engine.hitl.oversight.analytics.IApprovalDecisionStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * One run of the {@value HitlAutonomySchedules#SCHEDULE_NAME} system schedule:
 * <ol>
 * <li>expires PENDING samples past their review date;</li>
 * <li>evaluates earned autonomy for deployed agents, round-robin, at most
 * {@code eddi.hitl.autonomy.max-agents-per-run} per run;</li>
 * <li>deletes decision facts and samples past retention;</li>
 * <li>once per deployment, rebuilds the decision facts of the last
 * {@code eddi.hitl.analytics.initial-backfill} from the ledger.</li>
 * </ol>
 * Every step is idempotent and compare-and-set, so a duplicate schedule or a
 * retried run is harmless.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class HitlAutonomyMaintenanceService {

    private static final Logger LOGGER = Logger.getLogger(HitlAutonomyMaintenanceService.class);

    /** Marker row: the first-start rebuild has run. */
    static final String BACKFILL_MARKER = "initial-backfill";

    private final IAuditSampleStore sampleStore;
    private final IApprovalDecisionStore decisionStore;
    private final IAutonomyStateStore stateStore;
    private final IDeploymentStore deploymentStore;
    private final OversightConfigLookup configLookup;
    private final AutonomyService autonomy;
    private final ApprovalBackfillService backfill;
    private final OversightSettings settings;
    private final OversightMetrics metrics;
    private final Clock clock;
    /** Where the last run stopped (per node; only fairness depends on it). */
    private volatile String cursor;

    @Inject
    public HitlAutonomyMaintenanceService(IAuditSampleStore sampleStore, IApprovalDecisionStore decisionStore,
            IAutonomyStateStore stateStore, IDeploymentStore deploymentStore, OversightConfigLookup configLookup, AutonomyService autonomy,
            ApprovalBackfillService backfill, OversightSettings settings, OversightMetrics metrics) {
        this(sampleStore, decisionStore, stateStore, deploymentStore, configLookup, autonomy, backfill, settings, metrics,
                Clock.systemUTC());
    }

    /** Test seam: a fixed clock. */
    public HitlAutonomyMaintenanceService(IAuditSampleStore sampleStore, IApprovalDecisionStore decisionStore,
            IAutonomyStateStore stateStore, IDeploymentStore deploymentStore, OversightConfigLookup configLookup, AutonomyService autonomy,
            ApprovalBackfillService backfill, OversightSettings settings, OversightMetrics metrics, Clock clock) {
        this.sampleStore = sampleStore;
        this.decisionStore = decisionStore;
        this.stateStore = stateStore;
        this.deploymentStore = deploymentStore;
        this.configLookup = configLookup;
        this.autonomy = autonomy;
        this.backfill = backfill;
        this.settings = settings;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** What one run did. */
    public record Result(long expired, int agentsEvaluated, int recommendationsOpened, long decisionsDeleted, long samplesDeleted,
            boolean backfilled) {

        public boolean isEmpty() {
            return expired == 0 && recommendationsOpened == 0 && decisionsDeleted == 0 && samplesDeleted == 0 && !backfilled;
        }
    }

    public Result run() {
        Instant now = clock.instant();
        long expired = sampleStore.expireOverdue(now);
        metrics.samplesExpired(expired);

        int evaluated = 0;
        int opened = 0;
        for (Map.Entry<String, Integer> agent : nextAgents()) {
            try {
                // Evaluate the newest STORED version, the one apply() builds on: a deployed
                // version older than the stored one would open recommendations that every
                // apply attempt rejects as AGENT_CHANGED and that maintenance never closes.
                int version = latestVersionOrDeployed(agent.getKey(), agent.getValue());
                opened += autonomy.evaluateAgent(agent.getKey(), version, configLookup.hitlConfig(agent.getKey(), version));
                evaluated++;
            } catch (RuntimeException e) {
                LOGGER.warnf("Earned-autonomy evaluation of agent %s failed: %s", sanitize(agent.getKey()), e.getMessage());
            }
        }

        long decisionsDeleted = decisionStore.deleteDecidedBefore(now.minus(settings.analyticsRetention()));
        long samplesDeleted = sampleStore.deleteSampledBefore(now.minus(settings.samplesRetention()));
        boolean backfilled = initialBackfill(now);
        return new Result(expired, evaluated, opened, decisionsDeleted, samplesDeleted, backfilled);
    }

    private int latestVersionOrDeployed(String agentId, int deployedVersion) {
        try {
            return Math.max(deployedVersion, configLookup.currentVersion(agentId));
        } catch (Exception e) {
            return deployedVersion;
        }
    }

    /**
     * The next slice of deployed agents (newest deployed version each), in agent-id
     * order after the cursor, wrapping around.
     */
    List<Map.Entry<String, Integer>> nextAgents() {
        TreeMap<String, Integer> deployed = new TreeMap<>();
        try {
            for (DeploymentInfo info : deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)) {
                if (info.getAgentId() != null && info.getAgentVersion() != null) {
                    deployed.merge(info.getAgentId(), info.getAgentVersion(), Math::max);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("Could not read deployments for earned-autonomy evaluation: %s", e.getMessage());
            return List.of();
        }
        int max = Math.max(1, settings.maxAgentsPerRun());
        List<Map.Entry<String, Integer>> slice = new ArrayList<>();
        String start = cursor;
        var tail = start == null ? deployed : deployed.tailMap(start, false);
        for (var entry : tail.entrySet()) {
            if (slice.size() >= max) {
                break;
            }
            slice.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        if (slice.size() < max && start != null) {
            for (var entry : deployed.headMap(start, true).entrySet()) {
                if (slice.size() >= max) {
                    break;
                }
                slice.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        cursor = slice.isEmpty() ? null : slice.getLast().getKey();
        return slice;
    }

    private boolean initialBackfill(Instant now) {
        if (settings.initialBackfill() == null || settings.initialBackfill().isZero() || settings.initialBackfill().isNegative()) {
            return false;
        }
        if (stateStore.read(AutonomyState.SYSTEM_AGENT_ID, BACKFILL_MARKER).isPresent()) {
            return false;
        }
        // The marker is written only after a COMPLETE rebuild: a truncated one (page
        // limit,
        // unsupported store), a failed one and a skipped one leave it absent, so the
        // next run
        // retries. Two nodes may rebuild the same window at once; that is harmless,
        // because
        // the projection inserts only rows that are not there yet.
        ApprovalBackfillService.Result result = backfill.rebuild(now.minus(settings.initialBackfill()), now);
        if (result == null) {
            return false;
        }
        if (result.truncated()) {
            LOGGER.warnf("Initial approval analytics backfill stopped before the start of its window (read %d, stored %d); "
                    + "it will be retried on the next run", result.entriesRead(), result.unitsStored());
        } else {
            stateStore.save(AutonomyState.initial(AutonomyState.SYSTEM_AGENT_ID, BACKFILL_MARKER), 0L);
        }
        return true;
    }
}
