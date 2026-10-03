/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.a2a.A2AInFlightLimiter.Permit;
import ai.labs.eddi.engine.a2a.A2AModels.A2ABusyException;
import ai.labs.eddi.engine.a2a.A2AModels.A2AMessage;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATask;
import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.a2a.A2AModels.Artifact;
import ai.labs.eddi.engine.a2a.A2AModels.Dialect;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.a2a.A2AModels.Part;
import ai.labs.eddi.engine.a2a.A2AModels.TaskState;
import ai.labs.eddi.engine.a2a.A2AModels.TaskStatus;
import ai.labs.eddi.engine.a2a.A2AWireFormat.SendRequest;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.CancelOutcome;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.StreamingResponseHandler;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_TASK_NOT_FOUND;
import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_UNSUPPORTED_OPERATION;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Runs A2A tasks as EDDI conversation turns.
 * <p>
 * <strong>Tasks and conversations.</strong> A context is one conversation; a
 * task is one turn in it. A task a peer opens without naming a context gets a
 * fresh conversation, and that conversation's id is the context id it is handed
 * back. Server-issued task ids are {@code <conversationId>_<random>}, and every
 * turn carries its task id in the input context ({@value #CONTEXT_TASK_ID}), so
 * a task can be found again in the conversation store when the
 * {@link IA2ATaskStore} has forgotten it — after a restart, or on another node.
 * <p>
 * <strong>States.</strong> The answer to a task is decided by how its own turn
 * ended, never by what the conversation last said: an ERROR turn is
 * {@code failed}, a HITL pause is {@code input-required}, a turn dropped
 * because the conversation was paused or busy is {@code input-required} or
 * {@code rejected}, and a cancelled one is {@code canceled}. A turn's output is
 * returned only when it carries this task's id, so a skipped turn can no longer
 * hand back the previous answer.
 * <p>
 * <strong>Peer scoping.</strong> {@code taskId} and {@code contextId} are
 * payload, never proof of ownership. The store is keyed on the authenticated
 * peer plus the id, every conversation this handler creates is owned by that
 * peer, and a task or context re-derived from the conversation store resolves
 * only when the conversation's owner is the caller. A peer can only ever reach
 * tasks and contexts it created itself.
 *
 * @author ginccc
 */
@ApplicationScoped
public class A2ATaskHandler {

    private static final Logger LOGGER = Logger.getLogger(A2ATaskHandler.class);

    /** Last-resort turn budget when neither configured value is positive. */
    static final int DEFAULT_TASK_TIMEOUT_SECONDS = 60;

    /**
     * How long past the task timeout an in-flight slot is held at most — room for
     * the turn's own watchdog to settle it first.
     */
    static final int SLOT_LEASE_GRACE_SECONDS = 30;

    /**
     * Owner recorded for peers that arrive without an authenticated identity — the
     * case when {@code authorization.enabled=false}, where the JSON-RPC endpoint's
     * role gate is a no-op and there is a single trust domain anyway. Deliberately
     * a value no OIDC principal can ever equal: switching authorization on later
     * must not hand these conversations to a real user.
     */
    static final String ANONYMOUS_PEER = "a2a:anonymous";

    /** Input-context key naming the task a turn belongs to. */
    static final String CONTEXT_TASK_ID = "a2aTaskId";

    /** Input-context key naming the context a turn belongs to. */
    static final String CONTEXT_CONTEXT_ID = "a2aContextId";

    static final String RESPONSE_ARTIFACT_ID = "response";

    /**
     * Snapshots are read detailed: the default projection keeps only input, actions
     * and output keys, and drops the {@code context} map a turn's task id is read
     * from. The detailed projection is still redacted (no audit, trace or error
     * keys), and nothing but the extracted text leaves this class.
     */
    private static final boolean DETAILED = true;

    private static final char TASK_ID_SEPARATOR = '_';

    static final String STATUS_AWAITING_APPROVAL = "The conversation is waiting for a human to approve the agent's next step. "
            + "Poll the task until the approval is resolved.";
    static final String STATUS_SKIPPED_AWAITING_APPROVAL = "The conversation is waiting for a human approval, so this message was "
            + "not processed. Send it again once the approval is resolved.";
    static final String STATUS_BUSY = "The agent was busy with another turn of this conversation, so this message was not "
            + "processed. Send it again.";
    static final String STATUS_INACTIVE = "The conversation is no longer active, so this message was not processed.";
    static final String STATUS_FAILED = "The agent failed while processing the message.";
    static final String STATUS_CANCELED = "The task was canceled before it finished.";

    private final IConversationService conversationService;
    private final IA2ATaskStore taskStore;
    private final AgentCardService agentCardService;
    private final A2AInFlightLimiter limiter;
    private final MeterRegistry meterRegistry;

    /**
     * The unsettled tasks of each conversation, oldest first, as submitted from
     * this node. A conversation runs one turn at a time, so the head is the turn
     * that is running (or next to run) and the rest are queued behind it.
     * {@code cancelConversation} stops whatever turn the conversation is running,
     * so a cancel of a queued task would stop its sibling's turn instead; this is
     * how {@link #cancel} tells the two apart. Transport bookkeeping, not
     * conversation state: entries live only until their turn settles.
     */
    private final Map<String, Deque<String>> unsettledByConversation = new ConcurrentHashMap<>();

    /**
     * The calling peer's identity. For a remote agent this is the principal of the
     * Bearer token it presented — the only caller-independent identity available on
     * this surface.
     */
    private final SecurityIdentity identity;

    /**
     * How long a blocking send waits for its turn before answering with the task
     * still {@code working}.
     * <p>
     * Defaults to {@code systemRuntime.agentTimeoutInSeconds}, the same budget the
     * REST surface gives a turn; {@code eddi.a2a.task-timeout-seconds} overrides it
     * for a deployment whose peers cannot wait that long.
     */
    private final int taskTimeoutSeconds;

    @Inject
    public A2ATaskHandler(IConversationService conversationService, IA2ATaskStore taskStore, SecurityIdentity identity,
            AgentCardService agentCardService, A2AInFlightLimiter limiter, MeterRegistry meterRegistry,
            @ConfigProperty(name = "systemRuntime.agentTimeoutInSeconds", defaultValue = "60") int agentTimeoutSeconds,
            @ConfigProperty(name = "eddi.a2a.task-timeout-seconds") Optional<Integer> a2aTaskTimeoutSeconds) {
        this.conversationService = conversationService;
        this.taskStore = taskStore;
        this.identity = identity;
        this.agentCardService = agentCardService;
        this.limiter = limiter;
        this.meterRegistry = meterRegistry;
        // A non-positive budget makes Future.get return immediately, so neither
        // source may supply one. systemRuntime.agentTimeoutInSeconds carries no
        // positive-value validation of its own, so falling back to it is not enough.
        this.taskTimeoutSeconds = a2aTaskTimeoutSeconds.filter(seconds -> seconds > 0)
                .orElseGet(() -> agentTimeoutSeconds > 0 ? agentTimeoutSeconds : DEFAULT_TASK_TIMEOUT_SECONDS);
    }

    int taskTimeoutSeconds() {
        return taskTimeoutSeconds;
    }

    // ==================== send ====================

    /**
     * Sends a message — {@code SendMessage}, {@code message/send} or the legacy
     * {@code tasks/send}.
     * <p>
     * Waits for the turn unless the peer asked to return immediately. A turn that
     * outlives the wait is answered as {@code working}; the peer polls
     * {@code tasks/get} for the outcome.
     *
     * @throws InvalidA2ARequestException
     *             for a request the peer has to fix
     * @throws A2ABusyException
     *             when every in-flight slot is taken
     */
    public A2ATask send(String agentId, SendRequest request) throws Exception {
        Permit permit = acquireSlot();
        Prepared prepared;
        try {
            prepared = prepare(agentId, request);
        } catch (Exception | Error e) {
            permit.release();
            throw e;
        }

        CompletableFuture<A2ATaskRecord> settled = new CompletableFuture<>();
        submit(prepared, permit, settled::complete, null);

        A2ATaskRecord result;
        if (request.returnImmediately()) {
            result = settled.getNow(currentRecord(prepared));
        } else {
            try {
                result = settled.get(taskTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                LOGGER.infof("A2A task %s is still running after %ds — answering 'working'", sanitize(prepared.record().taskId()),
                        taskTimeoutSeconds);
                result = currentRecord(prepared);
            }
        }
        return toTask(result, request.historyLength());
    }

    // ==================== stream ====================

    /** An event of a streamed task, in the order a peer receives them. */
    public sealed interface StreamEvent permits TaskEvent, ChunkEvent, FinalEvent {
    }

    /** The task as accepted — the first event of every stream. */
    public record TaskEvent(A2ATask task) implements StreamEvent {
    }

    /** A streamed piece of the answer, appended to the response artifact. */
    public record ChunkEvent(A2ATask task, String text, boolean append) implements StreamEvent {
    }

    /** The settled task — the last event of every stream. */
    public record FinalEvent(A2ATask task) implements StreamEvent {
    }

    /**
     * Sends a message and reports the turn as a stream of events —
     * {@code SendStreamingMessage} / {@code message/stream}.
     * <p>
     * Everything that can refuse the request happens before this returns, so a
     * refusal is still an ordinary JSON-RPC error. The events are delivered to
     * {@code sink} from the turn's own thread; the first is a {@link TaskEvent}
     * delivered before this method returns, the last is always a
     * {@link FinalEvent}.
     */
    public void stream(String agentId, SendRequest request, Consumer<StreamEvent> sink) throws Exception {
        Permit permit = acquireSlot();
        Prepared prepared;
        try {
            prepared = prepare(agentId, request);
        } catch (Exception | Error e) {
            permit.release();
            throw e;
        }
        sink.accept(new TaskEvent(toTask(prepared.record(), 0)));
        submit(prepared, permit, record -> sink.accept(new FinalEvent(toTask(record, request.historyLength()))), sink);
    }

    // ==================== get / cancel ====================

    /**
     * {@code GetTask} / {@code tasks/get}.
     * <p>
     * Only tasks created by the <em>calling</em> peer resolve: a taskId belonging
     * to another peer is indistinguishable from an unknown one.
     *
     * @return the task, or null when the caller has no such task
     */
    public A2ATask get(String taskId, Integer historyLength) {
        return resolve(callerPrincipal(), taskId).map(record -> toTask(record, historyLength)).orElse(null);
    }

    /** What {@link #cancel} did. */
    public enum CancelResult {
        CANCELED, NOT_FOUND, NOT_CANCELABLE
    }

    /** A cancel's result and, when it found the task, the task as it now stands. */
    public record CancelOutcomeAndTask(CancelResult result, A2ATask task) {
    }

    /**
     * {@code CancelTask} / {@code tasks/cancel}.
     * <p>
     * Stops the task's turn — a running one at its next task boundary, a HITL pause
     * by cancelling the pending approval — without ending the conversation, so the
     * context stays usable for the next task. A task that already reached a
     * terminal state is not cancelable.
     */
    public CancelOutcomeAndTask cancel(String taskId) {
        String principal = callerPrincipal();
        Optional<A2ATaskRecord> found = resolve(principal, taskId);
        if (found.isEmpty()) {
            return new CancelOutcomeAndTask(CancelResult.NOT_FOUND, null);
        }
        A2ATaskRecord record = found.get();
        if (record.state().isTerminal()) {
            return new CancelOutcomeAndTask(CancelResult.NOT_CANCELABLE, toTask(record, null));
        }
        if (queuedBehindAnotherTask(record.conversationId(), taskId)) {
            // Cancelling the conversation would stop the sibling task's running turn,
            // not this one. Refused rather than mis-aimed; the peer can retry once the
            // turn ahead of it has settled.
            LOGGER.debugf("A2A task %s is queued behind another turn of its conversation — not cancelable now", sanitize(taskId));
            return new CancelOutcomeAndTask(CancelResult.NOT_CANCELABLE, toTask(record, null));
        }
        try {
            CancelOutcome outcome = conversationService.cancelConversation(record.conversationId(), ControlSignal.CANCEL_GRACEFUL,
                    "a2a:" + principal);
            if (outcome == CancelOutcome.CANCELLED) {
                A2ATaskRecord canceled = record.withOutcome(TaskState.canceled, STATUS_CANCELED, null);
                taskStore.saveTask(principal, canceled);
                countTask(TaskState.canceled);
                return new CancelOutcomeAndTask(CancelResult.CANCELED, toTask(canceled, null));
            }
            if (outcome == CancelOutcome.NOT_FOUND) {
                return new CancelOutcomeAndTask(CancelResult.NOT_FOUND, null);
            }
            // Nothing was running: the turn settled between the read and the cancel.
            A2ATaskRecord current = resolve(principal, taskId).orElse(record);
            return new CancelOutcomeAndTask(CancelResult.NOT_CANCELABLE, toTask(current, null));
        } catch (Exception e) {
            LOGGER.warnf("Failed to cancel A2A task %s: %s", sanitize(taskId), e.getMessage());
            return new CancelOutcomeAndTask(CancelResult.NOT_CANCELABLE, toTask(record, null));
        }
    }

    // ==================== preparing a task ====================

    /**
     * A task about to run: its record, the peer, and how its conversation was
     * found.
     */
    record Prepared(String principal, String agentId, A2ATaskRecord record, String messageText, boolean conversationFromContext) {

        Prepared withConversation(String conversationId) {
            A2ATaskRecord moved = new A2ATaskRecord(record.taskId(), record.contextId(), conversationId, record.agentId(), record.state(),
                    record.statusText(), record.responseText(), record.userText(), record.updatedAt());
            return new Prepared(principal, agentId, moved, messageText, false);
        }
    }

    private Permit acquireSlot() {
        Permit permit = limiter.tryAcquire((long) taskTimeoutSeconds + SLOT_LEASE_GRACE_SECONDS);
        if (permit == null) {
            throw new A2ABusyException("Too many concurrent A2A requests (limit " + limiter.capacity() + "). Retry shortly.");
        }
        return permit;
    }

    private Prepared prepare(String agentId, SendRequest request) throws Exception {
        String text = request.text();
        if (text == null || text.isBlank()) {
            throw new InvalidA2ARequestException("No text content found in message parts");
        }

        // A2A sits outside the workspace model on purpose — a peer is a remote system,
        // not an EDDI user. The gate this surface claims is isA2aEnabled() on the
        // target; getAgentCard returns null for "no such agent" and "not A2A-enabled"
        // alike, the same refusal a peer gets from discovery.
        if (agentCardService.getAgentCard(agentId) == null) {
            throw new InvalidA2ARequestException("Agent is not available over A2A: " + agentId);
        }

        // The input cap the conversationId entry points enforce, applied before a
        // conversation is resolved so a refused message leaves none behind.
        InputData probe = new InputData();
        probe.setInput(text);
        try {
            conversationService.requireInputWithinLimit(probe);
        } catch (IConversationService.InputTooLargeException e) {
            throw new InvalidA2ARequestException(e.getMessage());
        }

        String principal = callerPrincipal();

        // A message naming a task continues it — in its conversation, under its id.
        if (request.taskId() != null) {
            A2ATaskRecord existing = resolve(principal, request.taskId())
                    .filter(found -> agentId.equals(found.agentId()))
                    .orElseThrow(() -> new InvalidA2ARequestException(ERROR_TASK_NOT_FOUND, "Task not found"));
            if (existing.state().isTerminal()) {
                throw new InvalidA2ARequestException(ERROR_UNSUPPORTED_OPERATION,
                        "The task is " + existing.state().wireName(Dialect.V0_3)
                                + " and cannot take further messages; send a new message in the same context instead");
            }
            A2ATaskRecord continued = new A2ATaskRecord(existing.taskId(), existing.contextId(), existing.conversationId(), agentId,
                    TaskState.working, null, null, text, Instant.now());
            taskStore.saveTask(principal, continued);
            return new Prepared(principal, agentId, continued, text, false);
        }

        final String requestedContextId = request.contextId();
        String contextId = requestedContextId;
        String conversationId = null;
        boolean fromContext = false;
        if (requestedContextId != null) {
            conversationId = taskStore.findContextConversation(principal, requestedContextId)
                    .or(() -> ownConversation(principal, agentId, requestedContextId) ? Optional.of(requestedContextId) : Optional.empty())
                    .orElse(null);
            fromContext = conversationId != null;
        }
        if (conversationId == null) {
            conversationId = startConversation(agentId, principal);
        }
        boolean legacy = request.dialect() == Dialect.LEGACY;
        if (contextId == null && !legacy) {
            // A context the server opens is the conversation: it then resolves after a
            // restart without anything having been remembered.
            contextId = conversationId;
        }
        if (contextId != null) {
            taskStore.bindContext(principal, contextId, conversationId);
        }

        String taskId = request.legacyTaskId() != null
                ? request.legacyTaskId()
                : legacy
                        ? UUID.randomUUID().toString()
                        : conversationId + TASK_ID_SEPARATOR + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        A2ATaskRecord record = new A2ATaskRecord(taskId, contextId, conversationId, agentId, TaskState.working, null, null, text,
                Instant.now());
        taskStore.saveTask(principal, record);
        return new Prepared(principal, agentId, record, text, fromContext);
    }

    private String startConversation(String agentId, String principal) throws Exception {
        // Owned by the calling peer. Passing null would let ConversationService
        // substitute a random anonymous-* id, leaving the conversation owned by a
        // principal that can never authenticate.
        return conversationService.startConversation(Environment.production, agentId, principal, Map.of()).conversationId();
    }

    /**
     * Whether {@code conversationId} is a conversation of {@code agentId} owned by
     * the peer — how a context EDDI issued is recognised once the store has
     * forgotten it.
     */
    private boolean ownConversation(String principal, String agentId, String conversationId) {
        try {
            SimpleConversationMemorySnapshot snapshot = conversationService.readConversation(conversationId, false, true, null);
            return snapshot != null && principal.equals(snapshot.getUserId()) && agentId.equals(snapshot.getAgentId());
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== running a task ====================

    /**
     * Runs the task's turn. {@code onSettled} receives the final record exactly
     * once, on whichever thread settles it; the permit is released at the same
     * moment.
     */
    private void submit(Prepared prepared, Permit permit, Consumer<A2ATaskRecord> onSettled, Consumer<StreamEvent> sink) {
        Settler settler = new Settler(prepared, permit, onSettled);
        try {
            runTurn(prepared, settler, sink);
        } catch (IConversationService.ConversationEndedException | IConversationService.AgentMismatchException e) {
            if (!prepared.conversationFromContext()) {
                LOGGER.warnf("A2A task %s could not run: %s", sanitize(prepared.record().taskId()), e.getMessage());
                settler.settle(TaskState.rejected, STATUS_INACTIVE, null);
                return;
            }
            // The context's conversation has ended (or belongs to another agent): the
            // context carries on in a fresh one rather than failing every later task.
            try {
                String fresh = startConversation(prepared.agentId(), prepared.principal());
                Prepared moved = prepared.withConversation(fresh);
                taskStore.bindContext(moved.principal(), moved.record().contextId(), fresh);
                taskStore.saveTask(moved.principal(), moved.record());
                settler.retarget(moved);
                runTurn(moved, settler, sink);
            } catch (Exception retryFailure) {
                failTurn(settler, retryFailure);
            }
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            settler.settle(TaskState.input_required, STATUS_SKIPPED_AWAITING_APPROVAL, null);
        } catch (Exception e) {
            failTurn(settler, e);
        }
    }

    private void failTurn(Settler settler, Exception e) {
        // The peer is a remote party: the detail stays in the log, the task says only
        // that it failed.
        LOGGER.errorf(e, "A2A task %s failed to start", sanitize(settler.prepared.record().taskId()));
        settler.settle(TaskState.failed, STATUS_FAILED, null);
    }

    private void runTurn(Prepared prepared, Settler settler, Consumer<StreamEvent> sink) throws Exception {
        InputData inputData = new InputData();
        inputData.setInput(prepared.messageText());
        Map<String, Context> context = new HashMap<>();
        context.put(CONTEXT_TASK_ID, new Context(Context.ContextType.string, prepared.record().taskId()));
        if (prepared.record().contextId() != null) {
            context.put(CONTEXT_CONTEXT_ID, new Context(Context.ContextType.string, prepared.record().contextId()));
        }
        inputData.setContext(context);

        String conversationId = prepared.record().conversationId();
        String taskId = prepared.record().taskId();
        settler.enqueue(conversationId);
        if (sink == null) {
            conversationService.say(Environment.production, prepared.agentId(), conversationId, DETAILED, true, null, inputData, false,
                    new ConversationResponseHandler() {
                        @Override
                        public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                            settler.settleFrom(snapshot, taskId);
                        }

                        @Override
                        public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                            settler.settleSkipped(snapshot);
                        }
                    });
            return;
        }

        conversationService.sayStreaming(Environment.production, prepared.agentId(), conversationId, DETAILED, true, null, inputData,
                new StreamingResponseHandler() {
                    private boolean firstChunk = true;

                    @Override
                    public void onTaskStart(TaskId lifecycleTaskId, String taskType, int index) {
                    }

                    @Override
                    public void onTaskComplete(TaskId lifecycleTaskId, String taskType, long durationMs, Map<String, Object> summary) {
                    }

                    @Override
                    public void onToken(String token) {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        sink.accept(new ChunkEvent(toTask(settler.prepared.record(), 0), token, !firstChunk));
                        firstChunk = false;
                    }

                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        settler.settleFrom(snapshot, taskId);
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        settler.settleSkipped(snapshot);
                    }

                    @Override
                    public void onError(Throwable error) {
                        LOGGER.warnf("A2A streamed task %s failed: %s", sanitize(taskId), error == null ? "unknown" : error.getMessage());
                        settler.settle(TaskState.failed, STATUS_FAILED, null);
                    }
                });
    }

    /** Settles one task exactly once and releases its in-flight slot. */
    private final class Settler {

        private volatile Prepared prepared;
        private final Permit permit;
        private final Consumer<A2ATaskRecord> onSettled;
        private final AtomicBoolean done = new AtomicBoolean();
        private final List<String> queuedOn = new CopyOnWriteArrayList<>();

        Settler(Prepared prepared, Permit permit, Consumer<A2ATaskRecord> onSettled) {
            this.prepared = prepared;
            this.permit = permit;
            this.onSettled = onSettled;
        }

        void retarget(Prepared moved) {
            this.prepared = moved;
        }

        void enqueue(String conversationId) {
            String taskId = prepared.record().taskId();
            unsettledByConversation.compute(conversationId, (id, tasks) -> {
                Deque<String> queue = tasks == null ? new ArrayDeque<>() : tasks;
                queue.addLast(taskId);
                return queue;
            });
            queuedOn.add(conversationId);
        }

        private void dequeue() {
            String taskId = prepared.record().taskId();
            for (String conversationId : queuedOn) {
                unsettledByConversation.computeIfPresent(conversationId, (id, tasks) -> {
                    tasks.remove(taskId);
                    return tasks.isEmpty() ? null : tasks;
                });
            }
        }

        void settleFrom(SimpleConversationMemorySnapshot snapshot, String taskId) {
            Outcome outcome = outcomeOf(snapshot, taskId);
            settle(outcome.state(), outcome.statusText(), outcome.responseText());
        }

        void settleSkipped(SimpleConversationMemorySnapshot snapshot) {
            ConversationState state = snapshot == null ? null : snapshot.getConversationState();
            if (state == ConversationState.AWAITING_HUMAN) {
                settle(TaskState.input_required, STATUS_SKIPPED_AWAITING_APPROVAL, null);
            } else if (state == ConversationState.ENDED || state == ConversationState.EXECUTION_INTERRUPTED) {
                settle(TaskState.rejected, STATUS_INACTIVE, null);
            } else {
                settle(TaskState.rejected, STATUS_BUSY, null);
            }
        }

        void settle(TaskState state, String statusText, String responseText) {
            if (!done.compareAndSet(false, true)) {
                return;
            }
            try {
                String principal = prepared.principal();
                A2ATaskRecord base = prepared.record();
                // A cancel that won the race stands: the turn's late outcome does not
                // un-cancel the task.
                A2ATaskRecord stored = taskStore.findTask(principal, base.taskId()).orElse(base);
                A2ATaskRecord settledRecord;
                if (stored.state() == TaskState.canceled) {
                    settledRecord = stored;
                } else {
                    settledRecord = base.withOutcome(state, statusText, responseText);
                    taskStore.saveTask(principal, settledRecord);
                    countTask(state);
                }
                onSettled.accept(settledRecord);
            } catch (RuntimeException e) {
                LOGGER.warnf("Failed to record the outcome of A2A task %s: %s", sanitize(prepared.record().taskId()), e.getMessage());
                // The waiting peer (a blocking send, a stream) must still be answered, not
                // left to run out its timeout on a store failure.
                try {
                    onSettled.accept(prepared.record().withOutcome(TaskState.failed, STATUS_FAILED, null));
                } catch (RuntimeException ignored) {
                    // nothing more can be done for this task
                }
            } finally {
                dequeue();
                permit.release();
            }
        }
    }

    /**
     * Whether another task's turn is ahead of {@code taskId} in its conversation,
     * as far as this node knows. Read under the map's lock for the key, so it sees
     * a consistent queue.
     */
    boolean queuedBehindAnotherTask(String conversationId, String taskId) {
        boolean[] behind = {false};
        unsettledByConversation.computeIfPresent(conversationId, (id, tasks) -> {
            behind[0] = tasks.contains(taskId) && !taskId.equals(tasks.peekFirst());
            return tasks;
        });
        return behind[0];
    }

    /** How a task's turn ended, as A2A sees it. */
    record Outcome(TaskState state, String statusText, String responseText) {
    }

    /**
     * Maps the conversation's state after the turn to the task's. The answer is
     * this turn's output, or none: an output that does not carry this task's id is
     * another turn's, and handing it back is exactly the stale answer this guards
     * against.
     */
    static Outcome outcomeOf(SimpleConversationMemorySnapshot snapshot, String taskId) {
        ConversationState state = snapshot == null ? null : snapshot.getConversationState();
        String text = null;
        if (snapshot != null && snapshot.getConversationOutputs() != null && !snapshot.getConversationOutputs().isEmpty()) {
            ConversationOutput last = snapshot.getConversationOutputs().getLast();
            if (taskId.equals(taskIdOf(last))) {
                text = ConversationOutputExtractor.extractText(last);
            } else {
                LOGGER.warnf("A2A task %s: the turn's snapshot carries no output of this task — returning none", sanitize(taskId));
            }
        }
        if (state == null) {
            return new Outcome(TaskState.completed, null, text == null ? "" : text);
        }
        return switch (state) {
            case AWAITING_HUMAN -> new Outcome(TaskState.input_required, text != null ? text : STATUS_AWAITING_APPROVAL, null);
            case ERROR -> new Outcome(TaskState.failed, STATUS_FAILED, null);
            case EXECUTION_INTERRUPTED -> new Outcome(TaskState.canceled, STATUS_CANCELED, null);
            case IN_PROGRESS -> new Outcome(TaskState.working, null, null);
            default -> new Outcome(TaskState.completed, null, text == null ? "" : text);
        };
    }

    /** The task id a conversation output was produced for, or null. */
    static String taskIdOf(ConversationOutput output) {
        return contextValue(output, CONTEXT_TASK_ID);
    }

    private static String contextValue(ConversationOutput output, String key) {
        if (output != null && output.get("context") instanceof Map<?, ?> context && context.get(key) instanceof String value) {
            return value;
        }
        return null;
    }

    // ==================== resolving a task ====================

    /**
     * The caller's task: the store's record while it is terminal, otherwise what
     * the conversation store says about it now. A task this server issued is found
     * in the conversation store even when the task store has forgotten it.
     */
    Optional<A2ATaskRecord> resolve(String principal, String taskId) {
        Optional<A2ATaskRecord> stored = taskStore.findTask(principal, taskId);
        if (stored.isPresent() && stored.get().state().isTerminal()) {
            return stored;
        }
        Optional<A2ATaskRecord> derived = derive(principal, taskId, stored.orElse(null));
        if (derived.isPresent() && (stored.isEmpty() || !derived.get().equals(stored.get()))) {
            taskStore.saveTask(principal, derived.get());
        }
        return derived.or(() -> stored);
    }

    private Optional<A2ATaskRecord> derive(String principal, String taskId, A2ATaskRecord stored) {
        String conversationId = stored != null ? stored.conversationId() : conversationIdOf(taskId);
        if (conversationId == null) {
            return Optional.empty();
        }
        SimpleConversationMemorySnapshot snapshot;
        try {
            snapshot = conversationService.readConversation(conversationId, DETAILED, false, null);
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warnf("Could not read the conversation of A2A task %s: %s", sanitize(taskId), e.getMessage());
            return Optional.empty();
        }
        if (snapshot == null || !principal.equals(snapshot.getUserId())) {
            return Optional.empty();
        }
        List<ConversationOutput> outputs = snapshot.getConversationOutputs();
        int index = -1;
        for (int i = outputs == null ? -1 : outputs.size() - 1; i >= 0; i--) {
            if (taskId.equals(taskIdOf(outputs.get(i)))) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            // The turn has not been persisted yet (or never ran): nothing to add.
            return Optional.ofNullable(stored);
        }
        ConversationOutput output = outputs.get(index);
        boolean latestTurn = index == outputs.size() - 1;
        String text = ConversationOutputExtractor.extractText(output);

        TaskState state;
        String statusText = null;
        String responseText = null;
        if (!latestTurn) {
            // A later turn ran, so this one finished.
            state = TaskState.completed;
            responseText = text == null ? "" : text;
        } else {
            ConversationState conversationState = snapshot.getConversationState();
            Outcome outcome = conversationState == null
                    ? new Outcome(TaskState.completed, null, text == null ? "" : text)
                    : switch (conversationState) {
                        case AWAITING_HUMAN -> new Outcome(TaskState.input_required, text != null ? text : STATUS_AWAITING_APPROVAL, null);
                        case ERROR -> new Outcome(TaskState.failed, STATUS_FAILED, null);
                        case EXECUTION_INTERRUPTED -> new Outcome(TaskState.canceled, STATUS_CANCELED, null);
                        case IN_PROGRESS -> new Outcome(TaskState.working, null, null);
                        default -> new Outcome(TaskState.completed, null, text == null ? "" : text);
                    };
            state = outcome.state();
            statusText = outcome.statusText();
            responseText = outcome.responseText();
        }

        if (stored != null) {
            if (stored.state() == state && Objects.equals(stored.responseText(), responseText)
                    && Objects.equals(stored.statusText(), statusText)) {
                return Optional.of(stored);
            }
            return Optional.of(stored.withOutcome(state, statusText, responseText));
        }
        String contextId = contextValue(output, CONTEXT_CONTEXT_ID);
        String userText = output.get("input") instanceof String input ? input : null;
        return Optional.of(new A2ATaskRecord(taskId, contextId, conversationId, snapshot.getAgentId(), state, statusText, responseText,
                userText, Instant.now()));
    }

    /** The conversation a server-issued task id names, or null for any other id. */
    static String conversationIdOf(String taskId) {
        int separator = taskId == null ? -1 : taskId.lastIndexOf(TASK_ID_SEPARATOR);
        return separator > 0 ? taskId.substring(0, separator) : null;
    }

    // ==================== rendering ====================

    private A2ATaskRecord currentRecord(Prepared prepared) {
        return taskStore.findTask(prepared.principal(), prepared.record().taskId()).orElse(prepared.record());
    }

    /**
     * The task a peer sees. The answer is an artifact, an explanation of any other
     * state is the status message, and the history is the exchange itself.
     *
     * @param historyLength
     *            how many history messages to include; null for all
     */
    static A2ATask toTask(A2ATaskRecord record, Integer historyLength) {
        String taskId = record.taskId();
        String contextId = record.contextId();
        A2AMessage statusMessage = record.statusText() == null
                ? null
                : new A2AMessage(taskId + "-status", A2AMessage.ROLE_AGENT, List.of(Part.textPart(record.statusText())), taskId, contextId);
        TaskStatus status = new TaskStatus(record.state(), statusMessage, record.updatedAt());

        List<Artifact> artifacts = null;
        if (record.state() == TaskState.completed && record.responseText() != null) {
            artifacts = List.of(new Artifact(RESPONSE_ARTIFACT_ID, RESPONSE_ARTIFACT_ID, List.of(Part.textPart(record.responseText()))));
        }

        List<A2AMessage> history = new ArrayList<>();
        if (record.userText() != null) {
            history.add(new A2AMessage(taskId + "-user", A2AMessage.ROLE_USER, List.of(Part.textPart(record.userText())), taskId, contextId));
        }
        if (record.responseText() != null && record.state() == TaskState.completed) {
            history.add(new A2AMessage(taskId + "-agent", A2AMessage.ROLE_AGENT, List.of(Part.textPart(record.responseText())), taskId,
                    contextId));
        }
        if (historyLength != null) {
            int keep = Math.max(0, Math.min(historyLength, history.size()));
            history = new ArrayList<>(history.subList(history.size() - keep, history.size()));
        }
        return new A2ATask(taskId, contextId, status, history.isEmpty() ? null : history, artifacts);
    }

    // ==================== helpers ====================

    private void countTask(TaskState state) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.a2a.tasks", "state", state.name()).increment();
        }
    }

    /**
     * The identity a task/context is filed under. The JSON-RPC surface is
     * authenticated, so a remote peer always has a principal; the anonymous
     * fallback only applies with authorization disabled.
     */
    String callerPrincipal() {
        if (identity != null && !identity.isAnonymous() && identity.getPrincipal() != null) {
            String name = identity.getPrincipal().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return ANONYMOUS_PEER;
    }
}
