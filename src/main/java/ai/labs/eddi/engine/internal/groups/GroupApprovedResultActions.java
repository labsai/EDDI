/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionRecord;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.groups.validation.ApprovedResultActionValidation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import ai.labs.eddi.utils.LogSanitizer;
import ai.labs.eddi.utils.RestUtilities;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Approved-result actions ({@code phase.onApproved}): once a person approves a
 * task of an approval-gated EXECUTE phase, one configured call is made with the
 * approved — possibly reviewer-edited — result, exactly as approved, with no
 * member turn in between.
 * <p>
 * <b>Two steps, two writes.</b> {@link #recordApprovals} runs inside the
 * approval: it adds a {@code PENDING} record per approved task to the
 * discussion's task list, and the approval's own compare-and-set (state
 * <em>and</em> pauseId) persists it. A retried or concurrent approval of the
 * same pause loses that CAS, so an approval yields at most one record. A task
 * gets a new record only when a person approves it again after a retry or
 * rework reset and its previous action ended without an accepted publish
 * (FAILED, SKIPPED, NOT_SENT); a SUCCEEDED or OUTCOME_UNKNOWN action is never
 * sent again. {@link #runOwed} runs on the resumed leg, before the phase loop
 * re-enters the EXECUTE phase (so a dependent never starts before the action of
 * what it depends on has settled): each owed record is claimed as
 * {@code RUNNING} and that claim is persisted <em>before</em> the request is
 * sent. A record found {@code RUNNING} belongs to a run that died mid-call; it
 * becomes {@code OUTCOME_UNKNOWN} and is never sent again. The guarantee is
 * therefore <b>at most once</b> — exactly once whenever the run survives, and
 * for a target that deduplicates on {@code {action.idempotencyKey}},
 * effectively once even when it does not.
 * <p>
 * <b>Data, not template.</b> The approved result reaches the call only as the
 * template variable {@code {task.result}}; the call's path, headers and body
 * are the author's templates, rendered by the same executor and restricted
 * engine agents use. A result that contains {@code {...}} is substituted as
 * text, never evaluated, and a {@code ${vault:...}} written into the result is
 * refused by the executor's reference guard.
 * <p>
 * <b>Who it runs as.</b> A group is not an agent and the approver is not the
 * caller of a background leg: the call carries only what its author wrote —
 * {@code ${vault:...}} secrets granted to all agents, re-checked here before
 * every send — and {@code ${connection:...}}/{@code ${caller:...}} are refused
 * at save time. The approver is recorded on the action and in its audit entry.
 * <p>
 * <b>Cost.</b> An HTTP call has no LLM cost; tool pricing, tool rate limits and
 * the group cost ceiling do not apply. The number of calls is bounded by the
 * number of approved tasks — one each.
 */
@ApplicationScoped
public class GroupApprovedResultActions {

    private static final Logger LOGGER = Logger.getLogger(GroupApprovedResultActions.class);

    /** The audit ledger task id of an approved-result action entry. */
    public static final String AUDIT_TASK_ID = "group.approved_action";
    public static final String AUDIT_TASK_TYPE = "group";
    static final String METRIC = "eddi_group_approved_action_total";
    static final int SUMMARY_MAX_CHARS = 300;
    static final int URL_MAX_CHARS = 2000;

    private final IApiCallsStore apiCallsStore;
    private final IApiCallExecutor apiCallExecutor;
    private final ApprovedResultActionValidation validation;
    private final AuditLedgerService auditLedgerService;
    private final MeterRegistry meterRegistry;
    /**
     * The standing team's workspace — where a cadence discussion's backlog task
     * identity lives. {@code null} (tests): every key falls back to the
     * per-discussion one.
     */
    private final IGroupWorkspaceStore workspaceStore;

    public GroupApprovedResultActions(IApiCallsStore apiCallsStore, IApiCallExecutor apiCallExecutor,
            ApprovedResultActionValidation validation, AuditLedgerService auditLedgerService, MeterRegistry meterRegistry) {
        this(apiCallsStore, apiCallExecutor, validation, auditLedgerService, meterRegistry, null);
    }

    @Inject
    public GroupApprovedResultActions(IApiCallsStore apiCallsStore, IApiCallExecutor apiCallExecutor,
            ApprovedResultActionValidation validation, AuditLedgerService auditLedgerService, MeterRegistry meterRegistry,
            IGroupWorkspaceStore workspaceStore) {
        this.apiCallsStore = apiCallsStore;
        this.apiCallExecutor = apiCallExecutor;
        this.validation = validation;
        this.auditLedgerService = auditLedgerService;
        this.meterRegistry = meterRegistry;
        this.workspaceStore = workspaceStore;
    }

    /**
     * Records the action each task approved in this decision owes. Called by the
     * approval before its compare-and-set, which persists the records with the
     * approval or not at all.
     *
     * @param phase
     *            the paused EXECUTE phase; nothing is recorded when it has no
     *            {@code onApproved}
     * @param candidateTaskIds
     *            the tasks that were awaiting approval when the decision arrived;
     *            the ones now COMPLETED are the approved ones
     * @param approvedBy
     *            who decided — a {@code system:} principal (a timeout's
     *            AUTO_APPROVE) is recorded, and the action is then skipped
     * @return how many records were added (a new attempt that replaces an earlier
     *         FAILED, SKIPPED or NOT_SENT one counts)
     */
    public int recordApprovals(GroupConversation gc, DiscussionPhase phase, int phaseIndex, Collection<String> candidateTaskIds,
                               String approvedBy) {
        SharedTaskList taskList = gc.getTaskList();
        if (taskList == null || phase == null || phase.onApproved() == null || phase.onApproved().action() == null
                || candidateTaskIds == null) {
            return 0;
        }
        ApprovedAction action = phase.onApproved().action();
        String target = action.uri() + "#" + action.call();
        Instant now = Instant.now();
        int added = 0;
        for (String taskId : candidateTaskIds) {
            TaskItem task = taskList.findById(taskId);
            if (task == null || task.status() != TaskStatus.COMPLETED) {
                continue; // rejected, reset for retry, or still waiting: owes nothing
            }
            boolean edited = taskList.getReviewerEdits().containsKey(taskId);
            ApprovedActionRecord previous = taskList.getApprovedActions().get(taskId);
            var record = new ApprovedActionRecord(ApprovedActionStatus.PENDING, phase.name(), phaseIndex, target,
                    phase.onApproved().onFailure(), approvedBy != null ? approvedBy : "unknown", edited,
                    idempotencyKeyFor(gc, task), now, null, null, null, null, null);
            if (taskList.recordApprovedAction(taskId, record)) {
                added++;
                if (previous != null) {
                    LOGGER.infof("Task %s in group conversation %s was approved again after its action ended %s — a new attempt is owed",
                            LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(gc.getId()), previous.status());
                }
            } else if (previous != null) {
                // SUCCEEDED is never sent twice; OUTCOME_UNKNOWN is never resent (the
                // target may have acted). The kept record says what happened.
                LOGGER.infof("Task %s in group conversation %s was approved again, but its action already ended %s — not sent again",
                        LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(gc.getId()), previous.status());
            }
        }
        return added;
    }

    /**
     * Whether an approval was made by a person rather than by the system (a
     * timeout's AUTO_APPROVE decides as {@code system:<reason>}).
     * <p>
     * Merge note: the sibling PR #1374 (feat/cadence-approved-counts-as-done)
     * introduces {@code TaskApproval.byPerson()} for the same decision. Whichever
     * of the two merges second must replace this helper with that one, so that
     * "approved by a person" has a single definition.
     */
    private static boolean approvedByPerson(String approvedBy) {
        return approvedBy == null || !approvedBy.startsWith("system:");
    }

    /**
     * Settles every action the discussion owes: sends each PENDING one (claim
     * persisted first), turns an orphaned RUNNING one into OUTCOME_UNKNOWN, and
     * skips system-approved ones. Never throws; each outcome is persisted, audited
     * and counted. Runs on the resumed leg while the discussion is IN_PROGRESS.
     *
     * @param cancelled
     *            whether the discussion was cancelled meanwhile — owed actions are
     *            then skipped, not sent
     */
    public void runOwed(GroupConversation gc, IGroupConversationStore store, BooleanSupplier cancelled) {
        SharedTaskList taskList = gc.getTaskList();
        if (taskList == null || taskList.getApprovedActions().isEmpty()) {
            return;
        }
        for (var entry : new ArrayList<>(taskList.getApprovedActions().entrySet())) {
            String taskId = entry.getKey();
            ApprovedActionRecord record = entry.getValue();
            try {
                if (record.status() == ApprovedActionStatus.RUNNING) {
                    settle(gc, store, taskId, record.finished(ApprovedActionStatus.OUTCOME_UNKNOWN, Instant.now(), null, null,
                            "The run that sent this call stopped before recording its outcome — check the target; it is not sent again"),
                            0L);
                } else if (record.status() == ApprovedActionStatus.PENDING) {
                    if (!approvedByPerson(record.approvedBy())) {
                        settle(gc, store, taskId, record.finished(ApprovedActionStatus.SKIPPED, Instant.now(), null, null,
                                "Approved by " + record.approvedBy() + ", not by a person — an approved-result action only runs on a human approval"),
                                0L);
                    } else if (cancelled != null && cancelled.getAsBoolean()) {
                        settle(gc, store, taskId, record.finished(ApprovedActionStatus.SKIPPED, Instant.now(), null, null,
                                "The discussion was cancelled before the action ran"), 0L);
                    } else if (!send(gc, store, taskId, record)) {
                        // The claim could not be persisted, so nothing was sent. Do not leave
                        // the record PENDING: the discussion may finish without another leg,
                        // which would drop the action silently. Settle it visibly instead —
                        // SKIPPED when the discussion was cancelled — seen by this pod or stored
                        // by another one, which fails the claim's state check without touching
                        // this pod's token — otherwise FAILED.
                        boolean wasCancelled = (cancelled != null && cancelled.getAsBoolean()) || cancelledInStore(gc, store);
                        settle(gc, store, taskId, record.finished(
                                wasCancelled ? ApprovedActionStatus.SKIPPED : ApprovedActionStatus.FAILED, Instant.now(), null, null,
                                wasCancelled
                                        ? "The discussion was cancelled before the action ran"
                                        : "Not sent: the claim could not be written (the discussion changed state or the store was "
                                                + "unavailable) — post it by hand, or send the task back (retry or rework) and "
                                                + "approve it again: that approval sends it"),
                                0L);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.errorf("Approved-result action of task %s in group conversation %s could not be settled: %s",
                        LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(e.getMessage()));
            }
        }
    }

    /**
     * Claims, sends and settles one action.
     *
     * @return false when the claim could not be persisted (nothing was sent)
     */
    private boolean send(GroupConversation gc, IGroupConversationStore store, String taskId, ApprovedActionRecord pending) {
        SharedTaskList taskList = gc.getTaskList();
        TaskItem task = taskList.findById(taskId);
        if (task == null) {
            settle(gc, store, taskId, pending.finished(ApprovedActionStatus.FAILED, Instant.now(), null, null, "The task no longer exists"), 0L);
            return true;
        }
        ApprovedActionRecord claimed = pending.running(Instant.now());
        taskList.updateApprovedAction(taskId, claimed);
        if (!persist(gc, store)) {
            // Not sent, so still owed: a later leg of this discussion sends it.
            taskList.updateApprovedAction(taskId, pending);
            LOGGER.warnf("Approved-result action of task %s in group conversation %s not sent: its claim could not be persisted",
                    LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(gc.getId()));
            return false;
        }
        long start = System.nanoTime();
        ApprovedActionRecord outcome = execute(gc, task, claimed);
        settle(gc, store, taskId, outcome, (System.nanoTime() - start) / 1_000_000L);
        return true;
    }

    /** Makes the call; never throws. */
    ApprovedActionRecord execute(GroupConversation gc, TaskItem task, ApprovedActionRecord claimed) {
        try {
            ApprovedAction action = actionOf(claimed.target());
            List<String> problems = validation.referenceProblems("onApproved.action", action);
            if (!problems.isEmpty()) {
                return claimed.finished(ApprovedActionStatus.FAILED, Instant.now(), null, null, cap("Refused: " + problems.getFirst()));
            }
            IResourceStore.IResourceId id = RestUtilities.extractResourceId(URI.create(action.uri().trim()));
            ApiCallsConfiguration httpCalls = apiCallsStore.read(id.getId(), id.getVersion());
            ApiCall call = ApprovedResultActionValidation.findCall(httpCalls, action.call());
            // A throwaway memory: the executor records the request and response in a
            // conversation step, and a group has no conversation of its own to hold them.
            var memory = new ConversationMemory(gc.getId(), gc.getGroupId(), 0, gc.getUserId());
            Map<String, Object> result = apiCallExecutor.execute(call, memory, templateData(gc, task, claimed), httpCalls.getTargetServerUrl());
            Integer httpCode = result.get("httpCode") instanceof Number n ? n.intValue() : null;
            if (httpCode != null && httpCode >= 200 && httpCode < 300) {
                return claimed.finished(ApprovedActionStatus.SUCCEEDED, Instant.now(), httpCode, responseUrl(result.get("body")), "HTTP " + httpCode);
            }
            Object body = result.get("body");
            String detail = body != null ? SecretRedactionFilter.redact(String.valueOf(body)) : "no response body";
            return claimed.finished(ApprovedActionStatus.FAILED, Instant.now(), httpCode, null,
                    cap((httpCode != null ? "HTTP " + httpCode : "No HTTP status") + ": " + detail));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return claimed.finished(ApprovedActionStatus.FAILED, Instant.now(), null, null, cap(SecretRedactionFilter.redact(message)));
        }
    }

    /**
     * The template data the call renders with: the approved task, the group and the
     * action's idempotency key — values, never template source.
     */
    static Map<String, Object> templateData(GroupConversation gc, TaskItem task, ApprovedActionRecord record) {
        Map<String, Object> taskData = new LinkedHashMap<>();
        taskData.put("id", task.id());
        taskData.put("subject", task.subject());
        taskData.put("description", task.description());
        taskData.put("result", task.result());
        taskData.put("approvedBy", record.approvedBy());
        taskData.put("edited", record.edited());
        Map<String, Object> groupData = new LinkedHashMap<>();
        groupData.put("id", gc.getGroupId());
        groupData.put("conversationId", gc.getId());
        Map<String, Object> actionData = new LinkedHashMap<>();
        actionData.put("idempotencyKey", record.idempotencyKey());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", taskData);
        data.put("group", groupData);
        data.put("action", actionData);
        return data;
    }

    /** Records an outcome, applies {@code onFailure}, persists, audits, counts. */
    private void settle(GroupConversation gc, IGroupConversationStore store, String taskId, ApprovedActionRecord outcome, long durationMs) {
        SharedTaskList taskList = gc.getTaskList();
        taskList.updateApprovedAction(taskId, outcome);
        boolean failed = outcome.status() == ApprovedActionStatus.FAILED || outcome.status() == ApprovedActionStatus.OUTCOME_UNKNOWN;
        if (failed && outcome.onFailure() != ApprovedActionFailurePolicy.KEEP_COMPLETED) {
            TaskItem task = taskList.findById(taskId);
            if (task != null && task.status() == TaskStatus.COMPLETED) {
                taskList.failTask(taskId, "Approved-result action " + outcome.status() + ": " + outcome.summary());
            }
        }
        if (!persist(gc, store)) {
            LOGGER.warnf("Outcome %s of the approved-result action of task %s in group conversation %s could not be persisted now; "
                    + "the discussion's next write carries it", outcome.status(), LogSanitizer.sanitize(taskId), LogSanitizer.sanitize(gc.getId()));
        }
        LOGGER.infof("Approved-result action of task %s in group conversation %s: %s", LogSanitizer.sanitize(taskId),
                LogSanitizer.sanitize(gc.getId()), outcome.status());
        meterRegistry.counter(METRIC, "outcome", outcome.status().name().toLowerCase(Locale.ROOT)).increment();
        audit(gc, taskId, outcome, durationMs);
    }

    /**
     * Whether the stored discussion is CANCELLED — a cancel written by another pod
     * that this pod's cancellation token has not seen. False when it cannot be
     * read.
     */
    private static boolean cancelledInStore(GroupConversation gc, IGroupConversationStore store) {
        try {
            GroupConversation stored = store.read(gc.getId());
            return stored != null && stored.getState() == GroupConversationState.CANCELLED;
        } catch (IResourceStore.ResourceNotFoundException | IResourceStore.ResourceStoreException | RuntimeException e) {
            LOGGER.debugf("Could not re-read group conversation %s after a failed claim: %s", LogSanitizer.sanitize(gc.getId()),
                    e.getMessage());
            return false;
        }
    }

    private static boolean persist(GroupConversation gc, IGroupConversationStore store) {
        try {
            store.updateIfState(gc, GroupConversationState.IN_PROGRESS);
            return true;
        } catch (IResourceStore.ResourceModifiedException | IResourceStore.ResourceStoreException
                | IGroupConversationStore.GroupConversationGoneException e) {
            LOGGER.debugf("Approved-result action write for %s failed: %s", LogSanitizer.sanitize(gc.getId()), e.getMessage());
            return false;
        }
    }

    private void audit(GroupConversation gc, String taskId, ApprovedActionRecord outcome, long durationMs) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            TaskItem task = gc.getTaskList().findById(taskId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groupTaskId", taskId);
            out.put("phase", outcome.phaseName());
            out.put("target", outcome.target());
            out.put("approvedBy", outcome.approvedBy());
            out.put("edited", outcome.edited());
            out.put("runAs", "group:" + gc.getGroupId());
            out.put("status", outcome.status().name());
            out.put("idempotencyKey", outcome.idempotencyKey());
            if (task != null && task.result() != null) {
                // what was sent, by digest — the text itself stays on the task
                out.put("resultSha256", sha256(task.result()));
                out.put("resultChars", task.result().codePointCount(0, task.result().length()));
            }
            if (outcome.httpCode() != null) {
                out.put("httpCode", outcome.httpCode());
            }
            if (outcome.responseUrl() != null) {
                out.put("responseUrl", outcome.responseUrl());
            }
            if (outcome.summary() != null) {
                out.put("summary", outcome.summary());
            }
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), gc.getId(), gc.getGroupId(), null, gc.getUserId(), null,
                    -1, AUDIT_TASK_ID, AUDIT_TASK_TYPE, -1, durationMs, Map.of(), out, null, null, List.of(), 0.0, Instant.now(), null,
                    null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to submit the approved-result action audit entry for group conversation %s: %s",
                    LogSanitizer.sanitize(gc.getId()), LogSanitizer.sanitize(e.getMessage()));
        }
    }

    /** The action a record's {@code target} ({@code <uri>#<call>}) names. */
    static ApprovedAction actionOf(String target) {
        int hash = target != null ? target.lastIndexOf('#') : -1;
        if (hash <= 0) {
            return new ApprovedAction(ApprovedActionType.HTTPCALL, target, null);
        }
        return new ApprovedAction(ApprovedActionType.HTTPCALL, target.substring(0, hash), target.substring(hash + 1));
    }

    /**
     * {@code html_url}, else {@code url}, of a JSON object response — the link to
     * what was published (GitHub answers a comment with {@code html_url}).
     */
    static String responseUrl(Object body) {
        if (!(body instanceof Map<?, ?> map)) {
            return null;
        }
        for (String key : new String[]{"html_url", "url"}) {
            if (map.get(key) instanceof String url && (url.startsWith("https://") || url.startsWith("http://"))) {
                return url.length() > URL_MAX_CHARS ? null : url;
            }
        }
        return null;
    }

    /**
     * The idempotency key of a task's action. A cadence discussion's task that was
     * pulled from the standing team's backlog is keyed by the backlog task — stable
     * across every discussion that works it, so a task that returns to the backlog
     * (its publish FAILED or OUTCOME_UNKNOWN) and is approved again in a later
     * discussion offers the target the same key. Every other task is keyed by
     * discussion and task id. The backlog task is found the way cadence writeback
     * matches it: among the backlog ids the workspace's running discussion (this
     * one) pulled, by subject. A workspace that cannot be read falls back to the
     * per-discussion key.
     */
    String idempotencyKeyFor(GroupConversation gc, TaskItem task) {
        String backlogTaskId = backlogTaskId(gc, task);
        return backlogTaskId != null ? backlogIdempotencyKey(gc.getGroupId(), backlogTaskId) : idempotencyKey(gc.getId(), task.id());
    }

    private String backlogTaskId(GroupConversation gc, TaskItem task) {
        if (workspaceStore == null || gc.getGroupId() == null || gc.getId() == null || task.subject() == null) {
            return null;
        }
        try {
            GroupWorkspace workspace = workspaceStore.find(gc.getGroupId());
            if (workspace == null || !gc.getId().equals(workspace.getRunningDiscussionId())) {
                return null;
            }
            for (String pulledId : workspace.getPulledTaskIds()) {
                TaskItem backlogTask = workspace.getBacklog().findById(pulledId);
                if (backlogTask != null && task.subject().equals(backlogTask.subject())) {
                    return backlogTask.id();
                }
            }
            return null;
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            LOGGER.debugf("Could not read the workspace of group %s for an idempotency key: %s", LogSanitizer.sanitize(gc.getGroupId()),
                    e.getMessage());
            return null;
        }
    }

    /**
     * Stable per discussion and task, so a retry of the same publish can be
     * deduplicated.
     */
    static String idempotencyKey(String conversationId, String taskId) {
        return sha256(conversationId + "/" + taskId).substring(0, 32);
    }

    /**
     * Stable per standing team and backlog task, across every discussion that works
     * the task.
     */
    static String backlogIdempotencyKey(String groupId, String backlogTaskId) {
        return sha256("backlog/" + groupId + "/" + backlogTaskId).substring(0, 32);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String cap(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > SUMMARY_MAX_CHARS ? text.substring(0, SUMMARY_MAX_CHARS) + "…" : text;
    }
}
