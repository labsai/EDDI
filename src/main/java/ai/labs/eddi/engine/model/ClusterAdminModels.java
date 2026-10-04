/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * The shapes of the cluster console API ({@code /administration/cluster}).
 * <p>
 * Every view here carries ids, counts, states and timestamps only. The one
 * exception is {@link DeadLetterView#input()}, the captured input of a failed
 * turn, which is filled in only for {@code eddi-admin} callers of the
 * dead-letter listing and stays {@code null} everywhere a read-only role can
 * look.
 */
public final class ClusterAdminModels {

    // Fencing tokens and lease revisions go out as JSON strings: they are ~1.8e15
    // today, and a browser's number loses integers above 2^53, so a token copied
    // from
    // the console (or sent back as an expected revision) must not be rounded.

    private ClusterAdminModels() {
    }

    /**
     * The answer to "is my cluster healthy?".
     *
     * @param mode
     *            {@code single-node} or {@code cluster}
     * @param verdict
     *            {@code HEALTHY}, {@code DEGRADED}, {@code PARTITIONED} or
     *            {@code SINGLE_NODE}
     * @param reasons
     *            machine-readable codes behind the verdict (see
     *            {@code docs/coordinator-admin.md})
     * @param answeredBy
     *            the node that built this view
     * @param degradedTurnsPolicy
     *            {@code local} or {@code reject} — what turns do while NATS is
     *            unreachable
     * @param degradedSince
     *            when the answering node lost NATS, or {@code null}
     * @param hitlLeader
     *            the node leading the HITL recovery sweep, or {@code null}
     * @param settings
     *            the timing settings the verdict is judged against
     */
    public record ClusterOverview(String mode, String verdict, List<String> reasons, String answeredBy, long generatedAt,
            String degradedTurnsPolicy, Long degradedSince, List<ClusterNode> nodes, NatsView nats, DeadLetterCounts deadLetters,
            String hitlLeader, Map<String, Object> settings) {
    }

    /**
     * One EDDI replica.
     *
     * @param state
     *            {@code LIVE}, {@code STALE} (still listed, heartbeat late),
     *            {@code UNKNOWN} (the answering node cannot reach NATS, so it
     *            cannot tell), {@code LOST} (stopped heartbeating without leaving:
     *            killed, crashed or partitioned from NATS) or {@code LEFT} (shut
     *            down cleanly)
     * @param goneSince
     *            for {@code LOST}/{@code LEFT}: when the answering node noticed
     */
    public record ClusterNode(String nodeId, String boot, String host, String version, long startedAt, long lastHeartbeat, long heartbeatAgeMs,
            String state, boolean self, boolean degraded, boolean draining, long natsRtt, int activeConversations, int leasesHeld,
            int queueDepthTotal, int localDeadLetters, boolean hitlLeader, Long goneSince) {
    }

    /** NATS and JetStream as the answering node sees them. */
    public record NatsView(String status, String connectedUrl, String serverName, String serverVersion, String clusterName,
            List<String> knownServers, long reconnects, long rttMillis, List<StreamView> streams, List<BucketView> buckets,
            LeaseEpoch leaseEpoch, AccountView account, String error) {
    }

    /**
     * A JetStream stream of this deployment.
     *
     * @param role
     *            {@code events}, {@code dead-letters}, {@code activity},
     *            {@code archives} or {@code other}
     */
    public record StreamView(String name, String role, int replicas, long messages, long bytes, long firstSequence, long lastSequence,
            long consumerCount, String leader, List<PeerView> peers, List<ConsumerView> consumers) {
    }

    /** A KV bucket of this deployment. */
    public record BucketView(String bucket, String name, int replicas, long values, long bytes, Long ttlMillis, String leader,
            List<PeerView> peers) {
    }

    /** A stream replica on a NATS server. */
    public record PeerView(String name, boolean current, boolean offline, long lag, Long activeMillis) {
    }

    /** A consumer and how far behind it is. */
    public record ConsumerView(String name, long pending, long ackPending, long redelivered) {
    }

    /**
     * The generation of the leases bucket. Its first revision is the bucket's
     * creation time in microseconds, so every fencing token handed out by this
     * bucket is above every token of an earlier one.
     */
    public record LeaseEpoch(@JsonSerialize(using = ToStringSerializer.class) long firstRevision, Long createdAt,
            @JsonSerialize(using = ToStringSerializer.class) long lastRevision) {
    }

    /** JetStream usage of the NATS account. */
    public record AccountView(long memoryBytes, long storageBytes, long streams, long consumers) {
    }

    /**
     * Waiting dead letters: in the shared stream, and kept on nodes that could not
     * reach NATS.
     */
    public record DeadLetterCounts(long shared, int local) {
    }

    /**
     * A lease in the {@code LEASES} bucket.
     *
     * @param kind
     *            {@code conversation}, {@code group} or {@code leader}
     * @param holderStatus
     *            {@code LIVE}, {@code RESTARTED} (the holder's node is back with a
     *            new boot), {@code GONE} (no presence) or {@code UNKNOWN}
     * @param flags
     *            what makes it suspicious: {@code HOLDER_GONE},
     *            {@code HOLDER_RESTARTED}, {@code NOT_RENEWED},
     *            {@code LONG_RUNNING}, {@code CONTENDED}
     */
    public record LeaseView(String key, String kind, String conversationId, String agentId, String conversationState, String holderNode,
            String holderBoot, @JsonSerialize(using = ToStringSerializer.class) long revision, long since, long ageMs, long renewedAt,
            long sinceRenewalMs, String holderStatus,
            List<String> flags, String waitingNode) {
    }

    /** One page of leases. */
    public record LeasePage(List<LeaseView> leases, int total, int suspicious, boolean truncated) {
    }

    /**
     * An entry of the activity timeline.
     *
     * @param severity
     *            {@code info}, {@code warning} or {@code error}
     * @param node
     *            the node that observed it
     */
    public record ActivityEvent(String id, String type, String severity, String node, long ts, Map<String, Object> payload) {
    }

    /**
     * A dead letter as the console shows it.
     *
     * @param input
     *            the captured input — only for {@code eddi-admin} listings, else
     *            {@code null}
     * @param secretInput
     *            the client flagged the turn secret: its input was never stored
     * @param local
     *            kept on a node that could not reach NATS (id {@code local-…})
     */
    public record DeadLetterView(String id, String conversationId, String agentId, Integer agentVersion, String environment, String error,
            long timestamp, String reason, String nodeId, @JsonSerialize(contentUsing = ToStringSerializer.class) Map<String, Object> fence,
            String input, boolean secretInput, boolean replayable,
            String notReplayableReason, boolean local) {
    }

    /**
     * A filtered page of dead letters.
     *
     * @param nextCursor
     *            pass as {@code after} for the next page; {@code null} at the end
     * @param scanned
     *            how many entries were read to fill the page (bounded per request)
     */
    public record DeadLetterPage(List<DeadLetterView> entries, String nextCursor, int scanned) {
    }

    /** Counts of the waiting dead letters, without their content. */
    public record DeadLetterSummary(long total, int local, Map<String, Long> byReason, Map<String, Long> byNode, Map<String, Long> byAgent,
            Long oldest, Long newest, int scanned, boolean truncated, List<DeadLetterView> recent) {
    }

    /** Ids for a bulk replay or discard. */
    public record BulkRequest(List<String> ids) {
    }

    /**
     * What happened to one id of a bulk operation.
     *
     * @param outcome
     *            {@code REPLAYED}, {@code DISCARDED}, {@code NOT_FOUND},
     *            {@code NOT_REPLAYABLE}, {@code REJECTED} or {@code UNAVAILABLE}
     */
    public record ItemOutcome(String id, String outcome, String message) {
    }

    /** The per-item outcomes of a bulk operation. */
    public record BulkResult(List<ItemOutcome> results, int succeeded, int failed) {
    }

    /**
     * A force-release request.
     *
     * @param expectedRevision
     *            the revision the admin saw; the release is refused if the holder
     *            renewed since. {@code null} releases whatever revision is there.
     */
    public record ReleaseRequest(Long expectedRevision) {
    }

    /**
     * The result of a recovery action.
     *
     * @param outcome
     *            action-specific, e.g. {@code RELEASED}, {@code ALREADY_RELEASED},
     *            {@code RENEWED}, {@code DONE}, {@code DRAINED}
     */
    public record ActionResult(String action, String outcome, String message, Map<String, Object> details) {
    }

    /**
     * The answer to "why is conversation X stuck?".
     *
     * @param verdict
     *            {@code OK}, {@code BUSY}, {@code STUCK}, {@code NEEDS_ATTENTION}
     *            or {@code NOT_FOUND}
     * @param queuedOn
     *            node → turns of this conversation queued or running there
     */
    public record Diagnosis(String conversationId, String verdict, boolean exists, String agentId, Integer agentVersion, String state,
            int steps, LeaseView lease, Map<String, Integer> queuedOn, List<DeadLetterView> deadLetters, List<Finding> findings) {
    }

    /**
     * One observation of a diagnosis.
     *
     * @param action
     *            the suggested next step: {@code WAIT}, {@code FORCE_RELEASE},
     *            {@code REPLAY}, {@code OPEN_APPROVALS}, {@code CANCEL},
     *            {@code NONE}
     */
    public record Finding(String code, String severity, String action, Map<String, Object> details) {
    }
}
