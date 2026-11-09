/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.HandoffPolicies;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.IntakeMode;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.HandoffOrigin;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.InboxItem;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Intake;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffAdmission.Decision;
import ai.labs.eddi.engine.runtime.internal.handoff.HandoffResult.Outcome;
import ai.labs.eddi.modules.llm.tools.quota.IToolQuotaStore;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaKey;
import ai.labs.eddi.modules.llm.tools.quota.ToolQuotaRules;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The one place that writes into ANOTHER team's workspace.
 * <p>
 * Two entry points end here: the {@code handoff_to_team} tool and the
 * {@code handoffConfig.onDecision} rules. Neither may write to a foreign
 * workspace any other way, so consent, loop prevention, caps, admission and the
 * audit trail are enforced in exactly one pipeline:
 * <ol>
 * <li><b>Consent.</b> The target's current config must list this source in
 * {@code accept.from}. A store failure fails closed.</li>
 * <li><b>Lineage.</b> Self-handoff, a cycle and the hop limit are refused.</li>
 * <li><b>Text.</b> Control and format characters stripped, lengths capped.</li>
 * <li><b>Per-run cap</b> of the source, reserved atomically.</li>
 * <li><b>Day caps</b>, atomic across pods through {@link IToolQuotaStore}.</li>
 * <li><b>Admission</b> SPI ({@link HandoffAdmission}).</li>
 * <li><b>Compare-and-set loop</b> on the target workspace: decline tombstones,
 * duplicates and capacity are re-validated on every attempt, then AUTO files to
 * the backlog and REVIEW to the intake inbox.</li>
 * <li><b>Best-effort side effects</b>: audit entry, metrics.</li>
 * </ol>
 * No identity crosses the boundary: the source can only propose <em>data</em>.
 * The receiving team's cadence runs under its own creator.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamHandoffService {

    private static final Logger LOGGER = Logger.getLogger(TeamHandoffService.class);

    static final int MAX_CAS_ATTEMPTS = 3;
    static final String QUOTA_AGENT = "team-handoff";
    static final String QUOTA_INTAKE_COUNTER = "intake";
    static final String QUOTA_EDGE_TAG = "edge";
    static final Duration DAY = Duration.ofDays(1);
    static final String AUDIT_TASK_ID = "team.handoff";

    private final IGroupWorkspaceStore workspaceStore;
    private final IAgentGroupStore groupStore;
    private final IToolQuotaStore quotaStore;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;
    private final Instance<HandoffAdmission> admissions;
    private final int maxHops;
    private final Duration declineMemory;
    private final Clock clock;
    /**
     * Whether the receiving team's records take an agent's text as that agent's
     * (see {@link #reviewedWhenUnverifiable}).
     */
    private final BiPredicate<AgentGroupConfiguration, String> recordsTrustAgentText;

    @Inject
    public TeamHandoffService(IGroupWorkspaceStore workspaceStore, IAgentGroupStore groupStore, IToolQuotaStore quotaStore,
            AuditLedgerService auditLedgerService, MeterRegistry meterRegistry, Instance<HandoffAdmission> admissions,
            GroupConversationService groupConversationService,
            @ConfigProperty(name = "eddi.groups.handoff.max-hops", defaultValue = "3") int maxHops,
            @ConfigProperty(name = "eddi.groups.handoff.decline-memory", defaultValue = "P30D") Duration declineMemory) {
        this(workspaceStore, groupStore, quotaStore, auditLedgerService, meterRegistry, admissions, maxHops, declineMemory, Clock.systemUTC(),
                groupConversationService::recordsTrustAgentText);
    }

    TeamHandoffService(IGroupWorkspaceStore workspaceStore, IAgentGroupStore groupStore, IToolQuotaStore quotaStore,
            AuditLedgerService auditLedgerService, MeterRegistry meterRegistry, Instance<HandoffAdmission> admissions, int maxHops,
            Duration declineMemory, Clock clock) {
        this(workspaceStore, groupStore, quotaStore, auditLedgerService, meterRegistry, admissions, maxHops, declineMemory, clock,
                (config, agentId) -> true);
    }

    TeamHandoffService(IGroupWorkspaceStore workspaceStore, IAgentGroupStore groupStore, IToolQuotaStore quotaStore,
            AuditLedgerService auditLedgerService, MeterRegistry meterRegistry, Instance<HandoffAdmission> admissions, int maxHops,
            Duration declineMemory, Clock clock, BiPredicate<AgentGroupConfiguration, String> recordsTrustAgentText) {
        this.workspaceStore = workspaceStore;
        this.groupStore = groupStore;
        this.quotaStore = quotaStore;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
        this.admissions = admissions;
        this.maxHops = Math.max(1, maxHops);
        this.declineMemory = declineMemory != null ? declineMemory : Duration.ofDays(30);
        this.clock = clock;
        this.recordsTrustAgentText = recordsTrustAgentText;
    }

    /** Pipeline entry — see the class Javadoc. Never throws. */
    public HandoffResult file(HandoffRequest request) {
        IntakeMode[] modeHolder = new IntakeMode[1];
        HandoffResult result;
        try {
            result = pipeline(request, modeHolder);
        } catch (RuntimeException e) {
            // A bug or an unforeseen store failure must read as a refusal, never as a
            // thrown exception into a tool loop or a completion path.
            LOGGER.errorf(e, "Handoff to group '%s' failed unexpectedly", LogSanitizer.sanitize(request.targetGroupId()));
            result = HandoffResult.of(Outcome.UNAVAILABLE, "The handoff could not be filed right now. Do not retry in this turn.");
        }
        count(request, modeHolder[0], result.outcome());
        return result;
    }

    /**
     * A read-only summary of what filing to {@code targetGroupId} as
     * {@code sourceKey} would do right now, for approval cards. Never writes.
     */
    public TargetDescription describeTarget(String targetGroupId, String sourceKey) {
        try {
            AgentGroupConfiguration config = readGroup(targetGroupId);
            AcceptPolicy accept = acceptPolicy(config);
            AcceptRule rule = accept != null ? accept.ruleFor(sourceKey) : null;
            GroupWorkspace workspace = workspaceStore.find(targetGroupId);
            int inbox = workspace != null ? workspace.getHandoffs().inboxSize() : 0;
            int backlog = workspace != null ? workspace.getBacklog().size() : 0;
            return new TargetDescription(targetGroupId, rule != null, rule != null ? rule.mode() : null, inbox,
                    accept != null ? accept.maxInbox() : 0, backlog);
        } catch (Exception e) {
            LOGGER.debugf("describeTarget(%s) failed: %s", LogSanitizer.sanitize(targetGroupId), e.getMessage());
            return new TargetDescription(targetGroupId, false, null, 0, 0, 0);
        }
    }

    /**
     * What {@link #describeTarget} reports.
     *
     * @param accepting
     *            whether the source is currently accepted
     * @param mode
     *            how a handoff would land, {@code null} when not accepted
     */
    public record TargetDescription(String targetGroupId, boolean accepting, IntakeMode mode, int inboxSize, int inboxCapacity, int backlogSize) {
    }

    // ------------------------------------------------------------------ pipeline

    private HandoffResult pipeline(HandoffRequest request, IntakeMode[] modeHolder) {
        // 1. Consent: the target's CURRENT config must list this source.
        AgentGroupConfiguration targetConfig;
        try {
            targetConfig = readGroup(request.targetGroupId());
        } catch (IResourceStore.ResourceNotFoundException e) {
            return HandoffResult.of(Outcome.NOT_ACCEPTED, "That team does not exist (any more), so nothing was handed off.");
        } catch (Exception e) {
            storeFailure("read_target");
            LOGGER.warnf("Could not read handoff target '%s': %s", LogSanitizer.sanitize(request.targetGroupId()), e.getMessage());
            return HandoffResult.of(Outcome.UNAVAILABLE, "The receiving team could not be reached right now. Do not retry in this turn.");
        }
        AcceptPolicy accept = acceptPolicy(targetConfig);
        AcceptRule rule = accept != null ? accept.ruleFor(request.sourceKey()) : null;
        if (rule == null) {
            return HandoffResult.of(Outcome.NOT_ACCEPTED,
                    "That team does not accept handoffs from you. Tell the user; do not try another route around it.");
        }
        IntakeMode mode = reviewedWhenUnverifiable(request, targetConfig, rule.mode());
        modeHolder[0] = mode;

        // 2. Lineage.
        String loopMessage = loopRefusal(request);
        if (loopMessage != null) {
            audit(request, null, mode, Outcome.LOOP, null, null, null);
            return HandoffResult.of(Outcome.LOOP, loopMessage);
        }

        // 3. Text.
        String subject = cleanSubject(request.subject());
        String description = cleanDescription(request.description());
        int descriptionCap = request.maxDescriptionChars() > 0
                ? Math.min(request.maxDescriptionChars(), HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS)
                : HandoffPolicies.HARD_MAX_DESCRIPTION_CHARS;
        String invalid = textRefusal(subject, description, descriptionCap);
        if (invalid != null) {
            audit(request, null, mode, Outcome.INVALID, null, subject, description);
            return HandoffResult.of(Outcome.INVALID, invalid);
        }
        String dedupeKey = request.dedupeKey() != null ? request.dedupeKey() : defaultDedupeKey(request, subject);

        // 4-5. Reserve capacity; every exit below that does not file gives it back.
        Reservation reservation = new Reservation(request);
        HandoffResult refused = reserve(request, rule, accept, reservation);
        if (refused != null) {
            audit(request, null, mode, refused.outcome(), dedupeKey, subject, description);
            return refused;
        }
        HandoffResult result = null;
        try {
            // 6. Admission SPI.
            Decision decision = admit(request, subject, description, mode);
            if (!decision.admitted()) {
                String reason = decision.reason() != null && !decision.reason().isBlank()
                        ? decision.reason()
                        : "It was refused by a screening policy.";
                result = HandoffResult.of(Outcome.REFUSED_ADMISSION, "The handoff was not filed: " + reason);
            } else {
                if (decision.subject() != null) {
                    subject = cleanSubject(decision.subject());
                }
                if (decision.description() != null) {
                    description = cleanDescription(decision.description());
                }
                invalid = textRefusal(subject, description, descriptionCap);
                if (invalid != null) {
                    result = HandoffResult.of(Outcome.INVALID, invalid);
                }
            }
            if (result == null) {
                // 7. Compare-and-set into the target workspace.
                result = fileIntoWorkspace(request, accept, mode, subject, description, dedupeKey);
            }
        } finally {
            if (result == null || !isConsumed(result.outcome())) {
                reservation.releaseAll();
            }
        }
        // 8. Side effects, best effort.
        audit(request, result.handoffId(), mode, result.outcome(), dedupeKey, subject, description);
        return result;
    }

    /** A DUPLICATE consumed nothing: the first handoff already took its slots. */
    private static boolean isConsumed(Outcome outcome) {
        return outcome == Outcome.FILED || outcome == Outcome.QUEUED_FOR_REVIEW;
    }

    private String loopRefusal(HandoffRequest request) {
        if (request.sourceKind() == WorkspaceHandoffs.SourceKind.GROUP && request.targetGroupId().equals(request.sourceId())) {
            return "A team cannot hand work off to itself.";
        }
        String targetKey = WorkspaceHandoffs.SourceKind.GROUP.key(request.targetGroupId());
        if (request.lineage().contains(targetKey)) {
            return "This work already passed through that team, so handing it back would loop. Report the result to the user instead.";
        }
        if (request.hop() + 1 > maxHops) {
            return "This work has already been handed across " + request.hop() + " teams, which is the limit. Report it to the user instead.";
        }
        return null;
    }

    private static String textRefusal(String subject, String description, int descriptionCap) {
        if (subject.isBlank()) {
            return "A handoff needs a subject.";
        }
        if (subject.length() > SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH) {
            return "The subject is longer than " + SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH + " characters; shorten it.";
        }
        if (description.length() > descriptionCap) {
            return "The description is longer than " + descriptionCap + " characters; summarize it.";
        }
        return null;
    }

    // ---------------------------------------------------------- reservations

    /**
     * What the pipeline has taken so far, so that a refusal can give it all back.
     */
    private final class Reservation {
        private final HandoffRequest request;
        private boolean run;
        private ToolQuotaKey edge;
        private ToolQuotaKey intake;

        Reservation(HandoffRequest request) {
            this.request = request;
        }

        void releaseAll() {
            if (run && request.runQuota() != null) {
                request.runQuota().release();
                run = false;
            }
            releaseDay(edge);
            releaseDay(intake);
            edge = null;
            intake = null;
        }

        private void releaseDay(ToolQuotaKey key) {
            if (key == null) {
                return;
            }
            try {
                quotaStore.release(key);
            } catch (RuntimeException e) {
                // The counter errs on the side of too few handoffs until the window ends.
                LOGGER.warnf("Could not give back a handoff day-cap slot: %s", e.getMessage());
            }
        }
    }

    private HandoffResult reserve(HandoffRequest request, AcceptRule rule, AcceptPolicy accept, Reservation reservation) {
        if (request.runQuota() != null) {
            if (!request.runQuota().tryReserve()) {
                return HandoffResult.of(Outcome.RUN_CAPPED,
                        "You have reached the handoff limit for this conversation. Do not file more; tell the user what is left.");
            }
            reservation.run = true;
        }
        Instant now = clock.instant();
        long windowStart = ToolQuotaRules.windowStart(now, DAY);
        Instant resetAt = ToolQuotaRules.windowEnd(windowStart, DAY);
        Instant expiresAt = resetAt.plus(ToolQuotaRules.RETENTION_GRACE);
        try {
            if (rule.maxPerDay() != null) {
                ToolQuotaKey edge = new ToolQuotaKey(QUOTA_AGENT, QUOTA_EDGE_TAG + ":" + request.sourceKey(), request.targetGroupId(), windowStart);
                if (!quotaStore.tryAcquire(edge, rule.maxPerDay(), expiresAt).granted()) {
                    reservation.releaseAll();
                    return HandoffResult.of(Outcome.DAY_CAPPED,
                            "That team takes at most " + rule.maxPerDay() + " handoffs a day from you and the limit is used up until " + resetAt
                                    + ". Do not retry before then.");
                }
                reservation.edge = edge;
            }
            ToolQuotaKey intake = new ToolQuotaKey(QUOTA_AGENT, QUOTA_INTAKE_COUNTER, request.targetGroupId(), windowStart);
            if (!quotaStore.tryAcquire(intake, accept.maxPerDay(), expiresAt).granted()) {
                reservation.releaseAll();
                return HandoffResult.of(Outcome.DAY_CAPPED,
                        "That team's daily intake limit is used up until " + resetAt + ". Do not retry before then.");
            }
            reservation.intake = intake;
        } catch (RuntimeException e) {
            // A day-cap store failure refuses, like toolQuotas: fail closed.
            reservation.releaseAll();
            storeFailure("day_cap");
            LOGGER.warnf("Handoff day-cap store failed: %s", e.getMessage());
            return HandoffResult.of(Outcome.UNAVAILABLE, "The handoff limits could not be checked right now. Do not retry in this turn.");
        }
        return null;
    }

    // -------------------------------------------------------------- admission

    private Decision admit(HandoffRequest request, String subject, String description, IntakeMode mode) {
        if (admissions == null || admissions.isUnsatisfied()) {
            return Decision.admit();
        }
        String replacementSubject = null;
        String replacementDescription = null;
        for (HandoffAdmission admission : admissions) {
            try {
                Decision decision = admission.check(request, replacementSubject != null ? replacementSubject : subject,
                        replacementDescription != null ? replacementDescription : description, mode);
                if (decision == null || !decision.admitted()) {
                    return decision != null ? decision : Decision.refuse("a screening policy did not answer");
                }
                if (decision.subject() != null) {
                    replacementSubject = decision.subject();
                }
                if (decision.description() != null) {
                    replacementDescription = decision.description();
                }
            } catch (RuntimeException e) {
                LOGGER.warnf("Handoff admission %s failed — refusing: %s", admission.getClass().getSimpleName(), e.getMessage());
                return Decision.refuse("a screening policy could not run");
            }
        }
        return new Decision(true, null, replacementSubject, replacementDescription);
    }

    // ------------------------------------------------------- workspace write

    private HandoffResult fileIntoWorkspace(HandoffRequest request, AcceptPolicy accept, IntakeMode mode, String subject, String description,
                                            String dedupeKey) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            GroupWorkspace workspace;
            try {
                workspace = workspaceStore.readOrCreate(request.targetGroupId());
            } catch (IResourceStore.ResourceStoreException e) {
                storeFailure("read_workspace");
                LOGGER.warnf("Could not read workspace of '%s': %s", LogSanitizer.sanitize(request.targetGroupId()), e.getMessage());
                return HandoffResult.of(Outcome.UNAVAILABLE,
                        "The receiving team's workspace could not be read right now. Do not retry in this turn.");
            }
            // Re-validated on EVERY attempt: the world may have changed under a lost CAS.
            WorkspaceHandoffs handoffs = workspace.getHandoffs();
            Instant now = clock.instant();
            if (handoffs.isDeclinedSince(dedupeKey, now.minus(declineMemory))) {
                return HandoffResult.of(Outcome.DECLINED_RECENTLY,
                        "A person on that team declined this exact handoff recently. Do not file it again; tell the user instead.");
            }
            SharedTaskList backlog = workspace.getBacklog();
            String existing = handoffs.existingStatus(dedupeKey, taskId -> {
                TaskItem task = backlog.findById(taskId);
                return task != null ? task.status().name() : null;
            });
            if (existing != null) {
                return new HandoffResult(Outcome.DUPLICATE, null, existing,
                        "Already handed off earlier (status " + existing + ") — not filed again.");
            }
            for (TaskItem task : backlog.getTasks()) {
                if (subject.equalsIgnoreCase(task.subject())) {
                    return new HandoffResult(Outcome.DUPLICATE, null, task.status().name(),
                            "A task with that subject already exists on that team's backlog (status " + task.status().name()
                                    + ") — not filed again. Use a more specific subject if this is different work.");
                }
            }
            if (handoffs.inboxHasSubject(subject)) {
                return new HandoffResult(Outcome.DUPLICATE, null, "PENDING_REVIEW",
                        "A handoff with that subject is already waiting for that team's review — not filed again.");
            }

            String handoffId = UUID.randomUUID().toString();
            int priority = request.priority() != null
                    ? Math.max(0, Math.min(HandoffPolicies.MAX_PRIORITY, request.priority()))
                    : HandoffPolicies.DEFAULT_PRIORITY;
            HandoffOrigin origin = originFor(request, handoffId, dedupeKey, mode == IntakeMode.AUTO ? Intake.AUTO : Intake.PENDING, now);
            String landed;
            if (mode == IntakeMode.AUTO) {
                if (backlog.size() >= GroupWorkspace.MAX_BACKLOG_SIZE) {
                    return HandoffResult.of(Outcome.BACKLOG_FULL,
                            "That team's backlog is full. Do not retry; tell the user the team needs to clear its backlog.");
                }
                TaskItem task = backlog.addTask(new TaskItem(subject, description, priority, request.bet()).filedBy(writingAgentOf(request)));
                handoffs.putOrigin(task.id(), origin);
                landed = "backlog";
            } else {
                if (handoffs.inboxSize() >= accept.maxInbox()) {
                    return HandoffResult.of(Outcome.INBOX_FULL,
                            "That team's intake inbox is full. Do not retry; tell the user the team needs to review its pending handoffs.");
                }
                handoffs.addInbox(new InboxItem(handoffId, subject, description, priority, origin, request.bet()));
                landed = "inbox";
            }
            try {
                if (workspaceStore.casRevision(workspace)) {
                    return mode == IntakeMode.AUTO
                            ? new HandoffResult(Outcome.FILED, handoffId, null, "Handed off as \"" + subject + "\"; it is on that team's backlog.")
                            : new HandoffResult(Outcome.QUEUED_FOR_REVIEW, handoffId, null,
                                    "Handed off as \"" + subject + "\"; it is waiting for that team's review.");
                }
                LOGGER.debugf("Handoff CAS lost for '%s' (%s), attempt %d", LogSanitizer.sanitize(request.targetGroupId()), landed, attempt + 1);
            } catch (IResourceStore.ResourceStoreException e) {
                storeFailure("cas");
                LOGGER.warnf("Handoff write to '%s' failed: %s", LogSanitizer.sanitize(request.targetGroupId()), e.getMessage());
                return HandoffResult.of(Outcome.UNAVAILABLE, "The handoff could not be saved right now. Do not retry in this turn.");
            }
        }
        return HandoffResult.of(Outcome.CONFLICT, "That team's workspace is being changed concurrently. Do not retry in this turn.");
    }

    /**
     * The agent whose text a handoff files, recorded as the backlog task's creator:
     * the member that called the tool, or the standalone agent. A member of the
     * receiving team that requires peer verification is then shown the task only
     * while that agent signs verifiably (see {@code TaskRecordView}). A
     * {@code DECISION} handoff renders the source group's decision record and names
     * no agent; an inbox item a person accepts is not filed here.
     */
    private static String writingAgentOf(HandoffRequest request) {
        if (request.trigger() != WorkspaceHandoffs.Trigger.TOOL) {
            return null;
        }
        if (request.sourceMemberAgentId() != null && !request.sourceMemberAgentId().isBlank()) {
            return request.sourceMemberAgentId();
        }
        return request.sourceKind() == WorkspaceHandoffs.SourceKind.AGENT ? request.sourceId() : null;
    }

    /**
     * {@code mode}, or {@link IntakeMode#REVIEW} for an {@code AUTO} handoff whose
     * text the receiving team's records would not take as its writing agent's: a
     * member that requires peer verification, while that agent does not sign
     * verifiably. Filed to the backlog, such a task is one its cadence never pulls,
     * since a verifying member would fail it unasked on every run; in the intake
     * inbox a person reads it and vouches for it by accepting it.
     */
    private IntakeMode reviewedWhenUnverifiable(HandoffRequest request, AgentGroupConfiguration targetConfig, IntakeMode mode) {
        String writer = writingAgentOf(request);
        if (mode != IntakeMode.AUTO || writer == null || recordsTrustAgentText.test(targetConfig, writer)) {
            return mode;
        }
        LOGGER.infof("Handoff from agent '%s' to group '%s' goes to the intake inbox: a member there requires peer verification "
                + "and the agent does not sign verifiably", LogSanitizer.sanitize(writer), LogSanitizer.sanitize(request.targetGroupId()));
        return IntakeMode.REVIEW;
    }

    private HandoffOrigin originFor(HandoffRequest request, String handoffId, String dedupeKey, Intake intake, Instant now) {
        List<String> lineage = new ArrayList<>(request.lineage());
        lineage.add(request.sourceKey());
        // Keep the NEWEST links: the chain a cycle check must still recognize.
        if (lineage.size() > WorkspaceHandoffs.MAX_LINEAGE) {
            lineage = new ArrayList<>(lineage.subList(lineage.size() - WorkspaceHandoffs.MAX_LINEAGE, lineage.size()));
        }
        return new HandoffOrigin(handoffId, request.sourceKind(), request.sourceId(), request.sourceVersion(), request.sourceConversationId(),
                request.sourceMemberAgentId(), request.trigger(), request.decisionRef(), request.artifactId(), request.reference(),
                request.hop() + 1, lineage, dedupeKey, now, intake, null);
    }

    // ----------------------------------------------------------------- helpers

    private AgentGroupConfiguration readGroup(String groupId) throws IResourceStore.ResourceNotFoundException {
        var resourceId = groupStore.getCurrentResourceId(groupId);
        if (resourceId == null) {
            throw new IResourceStore.ResourceNotFoundException("group " + groupId);
        }
        try {
            return groupStore.read(groupId, resourceId.getVersion());
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private static AcceptPolicy acceptPolicy(AgentGroupConfiguration config) {
        return config != null && config.getHandoffConfig() != null ? config.getHandoffConfig().accept() : null;
    }

    /** One line, no control or format characters. */
    static String cleanSubject(String raw) {
        if (raw == null) {
            return "";
        }
        return stripControl(raw, false).replaceAll("\\s+", " ").strip();
    }

    /**
     * Multi-line allowed; control and format characters (bidi, zero-width)
     * stripped.
     */
    static String cleanDescription(String raw) {
        return raw == null ? "" : stripControl(raw, true).strip();
    }

    private static String stripControl(String raw, boolean keepNewlines) {
        StringBuilder out = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            boolean control = type == Character.CONTROL || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR;
            if (cp == '\n' || cp == '\t') {
                out.append(keepNewlines ? (char) cp : ' ');
            } else if (!control) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString();
    }

    static String defaultDedupeKey(HandoffRequest request, String subject) {
        return sha256(request.sourceKey() + "|" + request.targetGroupId() + "|" + subject.toLowerCase(Locale.ROOT));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private void count(HandoffRequest request, IntakeMode mode, Outcome outcome) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry
                .counter("eddi_team_handoff_total", "trigger", request.trigger() != null ? request.trigger().name().toLowerCase(Locale.ROOT) : "tool",
                        "mode", mode != null ? mode.name().toLowerCase(Locale.ROOT) : "none", "outcome", outcome.metricTag())
                .increment();
    }

    private void storeFailure(String operation) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi_team_handoff_store_failures_total", "operation", operation).increment();
        }
    }

    private void audit(HandoffRequest request, String handoffId, IntakeMode mode, Outcome outcome, String dedupeKey, String subject,
                       String description) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("handoffId", handoffId);
            detail.put("targetGroupId", request.targetGroupId());
            detail.put("mode", mode != null ? mode.name() : null);
            detail.put("outcome", outcome.name());
            detail.put("dedupeKey", dedupeKey);
            detail.put("hop", request.hop() + 1);
            detail.put("subject", subject);
            // The full description lives in the workspace; the ledger keeps a fingerprint.
            detail.put("descriptionSha256", description != null ? sha256(description) : null);
            detail.put("trigger", request.trigger() != null ? request.trigger().name() : null);
            detail.put("decisionRef", request.decisionRef());
            detail.put("sourceKey", request.sourceKey());
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), request.sourceConversationId(), request.sourceId(), null,
                    request.userId(), null, -1, AUDIT_TASK_ID, "group", -1, 0L, Map.of(), detail, null, null, List.of(), 0.0, clock.instant(), null,
                    null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit handoff audit entry: %s", e.getMessage());
        }
    }
}
