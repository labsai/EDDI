/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.model.DeadLetterEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.PublishOptions;
import io.nats.client.PurgeOptions;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.PublishAck;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.nats.client.api.StreamInfo;
import io.nats.client.api.StreamInfoOptions;

/**
 * Dead letters on the JetStream stream
 * {@code eddi.nats.dead-letter-stream-name} ({@code EDDI_DEAD_LETTERS}),
 * subjects {@code eddi.<prefix>.dlq.turn.<conv>} and
 * {@code eddi.<prefix>.dlq.audit}, kept for
 * {@code eddi.coordinator.dead-letter.max-age} (7 d).
 * <p>
 * Entries are read by sequence with {@code getNextMessage}, which skips deleted
 * sequences; the id of an entry is its stream sequence. This replaces the old
 * listing, which opened a push subscription per request, waited 500 ms and
 * never unsubscribed — and whose discard and replay did nothing.
 */
@ApplicationScoped
@Typed(JetStreamDeadLetterStore.class)
public class JetStreamDeadLetterStore implements IDeadLetterStore {

    private static final Logger LOGGER = Logger.getLogger(JetStreamDeadLetterStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** JetStream "no message found" (message get). */
    private static final int NO_MESSAGE = 10037;
    /** JetStream "no message found" (message delete). */
    private static final int NO_MESSAGE_TO_DELETE = 10057;
    /**
     * JetStream "sequence not found" — what a replicated (R3) stream answers when
     * the entry was already deleted, e.g. discarded through another node.
     */
    private static final int SEQUENCE_NOT_FOUND = 10043;

    /**
     * JetStream "stream not found": the stream is provisioned asynchronously once
     * the connection is up, so the first use can come before it exists — then there
     * is simply nothing in it yet.
     */
    private static final int STREAM_NOT_FOUND = 10059;

    static boolean notFound(JetStreamApiException e) {
        int code = e.getApiErrorCode();
        return code == NO_MESSAGE || code == NO_MESSAGE_TO_DELETE || code == SEQUENCE_NOT_FOUND || code == STREAM_NOT_FOUND;
    }

    static boolean streamMissing(JetStreamApiException e) {
        return e.getApiErrorCode() == STREAM_NOT_FOUND;
    }

    private final NatsConnectionManager connections;
    private final ClusterSubjects subjects;
    private final String stream;

    @Inject
    public JetStreamDeadLetterStore(NatsConnectionManager connections) {
        this.connections = connections;
        this.subjects = new ClusterSubjects(connections.config().natsPrefix());
        this.stream = connections.config().deadLetterStreamName();
        connections.onConnected(this::provision);
    }

    void provision() {
        ClusterConfig config = connections.config();
        StreamConfiguration desired = StreamConfiguration.builder().name(stream).subjects(subjects.deadLetterWildcard())
                .retentionPolicy(RetentionPolicy.Limits).maxAge(config.deadLetterMaxAge()).storageType(StorageType.File)
                .duplicateWindow(DUPLICATE_WINDOW).replicas(config.natsReplicas()).build();
        JetStreamManagement jsm = connections.jetStreamManagement();
        try {
            try {
                jsm.getStreamInfo(stream);
            } catch (JetStreamApiException notFound) {
                jsm.addStream(desired);
                LOGGER.infof("Created dead-letter stream %s", stream);
                return;
            }
            jsm.updateStream(desired);
        } catch (IOException | JetStreamApiException e) {
            LOGGER.warnf("Could not provision dead-letter stream %s: %s", stream, e.getMessage());
        }
    }

    /**
     * How long a dead letter waits for a stream it has just created to elect its
     * leader.
     */
    static final int READY_ATTEMPTS = 10;
    static final long READY_BACKOFF_MILLIS = 200;

    /**
     * Publishes to a stream that was created a moment ago. A replicated stream
     * answers only once its leader is elected: until then a publish fails with "no
     * responders". Without the wait, the first dead letter of a new cluster failed
     * over to the node-local ring (seen live).
     */
    private PublishAck publishOnceReady(String subject, byte[] payload, PublishOptions options) throws IOException, JetStreamApiException {
        IOException last = null;
        for (int attempt = 0; attempt < READY_ATTEMPTS; attempt++) {
            try {
                return connections.jetStream().publish(subject, payload, options);
            } catch (IOException notReadyYet) {
                last = notReadyYet;
                try {
                    Thread.sleep(READY_BACKOFF_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted while the dead-letter stream elected its leader", e);
                }
            }
        }
        throw last;
    }

    /**
     * How long the stream remembers a message id. A retried publish, or a forward
     * of a locally kept dead letter, within it is stored once.
     */
    static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(2);

    /**
     * The id JetStream de-duplicates one dead letter by ({@code Nats-Msg-Id}). The
     * same for every attempt to store this dead letter — the retries after a stream
     * creation, and a later forward from the node-local ring — because a publish
     * the server stored but whose acknowledgement was lost would otherwise be
     * stored twice. Built from what identifies the dead letter: the node, the
     * conversation, the time and the error.
     */
    static String messageId(String node, String conversationId, long timestamp, String error) {
        return KvKeys.sha256(node + "|" + conversationId + "|" + timestamp + "|" + error);
    }

    @Override
    public String append(String conversationId, String error, long timestamp, Map<String, Object> turn, String reason,
                         Map<String, Object> fence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("error", error);
        body.put("timestamp", timestamp);
        body.put("failedOn", connections.node().nodeId());
        if (reason != null) {
            body.put("reason", reason);
        }
        if (fence != null) {
            body.put("fence", fence);
        }
        if (turn != null) {
            body.put("turn", turn);
        }
        try {
            byte[] payload = JSON.writeValueAsBytes(body);
            String subject = subjects.deadLetterTurn(KvKeys.safe(conversationId));
            PublishOptions options = PublishOptions.builder()
                    .messageId(messageId(connections.node().nodeId(), conversationId, timestamp, error)).build();
            PublishAck ack;
            try {
                ack = connections.jetStream().publish(subject, payload, options);
            } catch (IOException | JetStreamApiException noStreamYet) {
                // The stream is provisioned asynchronously after connecting; a dead letter
                // that arrives first creates it here rather than being lost.
                provision();
                ack = publishOnceReady(subject, payload, options);
            }
            return String.valueOf(ack.getSeqno());
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("dead-letter publish failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<DeadLetterEntry> list(int limit, String after) {
        long seq = after == null ? 1 : parseSeq(after) + 1;
        List<DeadLetterEntry> entries = new ArrayList<>();
        JetStreamManagement jsm = connections.jetStreamManagement();
        try {
            while (entries.size() < limit) {
                MessageInfo info;
                try {
                    info = jsm.getNextMessage(stream, seq, subjects.deadLetterTurnWildcard());
                } catch (JetStreamApiException e) {
                    if (notFound(e)) {
                        break;
                    }
                    throw e;
                }
                if (info == null || !info.isMessage()) {
                    break;
                }
                entries.add(toEntry(info));
                seq = info.getSeq() + 1;
            }
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("dead-letter list failed: " + e.getMessage(), e);
        }
        return entries;
    }

    @Override
    public List<DeadLetterEntry> listConversation(String conversationId, int limit) {
        String subject = subjects.deadLetterTurn(KvKeys.safe(conversationId));
        List<DeadLetterEntry> entries = new ArrayList<>();
        JetStreamManagement jsm = connections.jetStreamManagement();
        long seq = 1;
        try {
            while (entries.size() < limit) {
                MessageInfo info;
                try {
                    info = jsm.getNextMessage(stream, seq, subject);
                } catch (JetStreamApiException e) {
                    if (notFound(e)) {
                        break;
                    }
                    throw e;
                }
                if (info == null || !info.isMessage()) {
                    break;
                }
                entries.add(toEntry(info));
                seq = info.getSeq() + 1;
            }
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("dead-letter list failed: " + e.getMessage(), e);
        }
        return entries;
    }

    @Override
    public Optional<DeadLetterEntry> get(String id) {
        long seq = parseSeq(id);
        if (seq <= 0) {
            return Optional.empty();
        }
        try {
            MessageInfo info = connections.jetStreamManagement().getMessage(stream, seq);
            if (info == null || !info.isMessage() || !isTurnSubject(info.getSubject())) {
                return Optional.empty();
            }
            return Optional.of(toEntry(info));
        } catch (JetStreamApiException e) {
            if (notFound(e)) {
                return Optional.empty();
            }
            throw new ClusterUnavailableException("dead-letter read failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter read failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean delete(String id) {
        long seq = parseSeq(id);
        if (seq <= 0) {
            return false;
        }
        try {
            JetStreamManagement jsm = connections.jetStreamManagement();
            // The stream also holds audit-ledger entries that could not be stored: an
            // operator discarding a turn's dead letter by sequence must not be able to
            // delete one of those. get() already hides them; delete() has to as well.
            MessageInfo info = jsm.getMessage(stream, seq);
            if (info == null || !info.isMessage() || !isTurnSubject(info.getSubject())) {
                return false;
            }
            return jsm.deleteMessage(stream, seq);
        } catch (JetStreamApiException e) {
            if (notFound(e)) {
                return false;
            }
            throw new ClusterUnavailableException("dead-letter delete failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int purge() {
        try {
            return (int) connections.jetStreamManagement()
                    .purgeStream(stream, PurgeOptions.subject(subjects.deadLetterTurnWildcard())).getPurged();
        } catch (JetStreamApiException e) {
            if (streamMissing(e)) {
                return 0;
            }
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int purgeConversation(String conversationId) {
        try {
            return (int) connections.jetStreamManagement()
                    .purgeStream(stream, PurgeOptions.subject(subjects.deadLetterTurn(KvKeys.safe(conversationId)))).getPurged();
        } catch (JetStreamApiException e) {
            if (streamMissing(e)) {
                // No dead letter was ever written — nothing to erase.
                return 0;
            }
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long count() {
        try {
            return connections.jetStreamManagement().getStreamInfo(stream).getStreamState().getMsgCount();
        } catch (JetStreamApiException e) {
            if (streamMissing(e)) {
                return 0;
            }
            throw new ClusterUnavailableException("dead-letter count failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter count failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long countTurns() {
        try {
            StreamInfo info = connections.jetStreamManagement().getStreamInfo(stream,
                    StreamInfoOptions.filterSubjects(subjects.deadLetterTurnWildcard()));
            Map<String, Long> bySubject = info.getStreamState().getSubjectMap();
            return bySubject == null ? 0 : bySubject.values().stream().mapToLong(Long::longValue).sum();
        } catch (JetStreamApiException e) {
            if (streamMissing(e)) {
                return 0;
            }
            throw new ClusterUnavailableException("dead-letter count failed: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new ClusterUnavailableException("dead-letter count failed: " + e.getMessage(), e);
        }
    }

    /**
     * Publishes an audit entry that could not be stored (see AuditLedgerService).
     */
    public void appendAudit(byte[] json) {
        try {
            connections.jetStream().publish(subjects.deadLetterAudit(), json);
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("audit dead-letter publish failed: " + e.getMessage(), e);
        }
    }

    private DeadLetterEntry toEntry(MessageInfo info) {
        String payload = new String(info.getData(), StandardCharsets.UTF_8);
        try {
            Map<String, Object> body = JSON.readValue(info.getData(), new TypeReference<Map<String, Object>>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> turn = body.get("turn") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            Object ts = body.get("timestamp");
            @SuppressWarnings("unchecked")
            Map<String, Object> fence = body.get("fence") instanceof Map<?, ?> f ? (Map<String, Object>) f : null;
            Object reason = body.get("reason");
            if (reason == null && turn != null) {
                // written by a node that records why only in the turn descriptor
                reason = turn.get("reason");
                if (fence == null && turn.get("fence") != null) {
                    fence = new LinkedHashMap<>();
                    fence.put("token", turn.get("fence"));
                    if (turn.get("storedFence") != null) {
                        fence.put("storedFence", turn.get("storedFence"));
                    }
                }
            }
            Object failedOn = body.get("failedOn");
            return new DeadLetterEntry(String.valueOf(info.getSeq()), String.valueOf(body.get("conversationId")),
                    String.valueOf(body.get("error")), ts instanceof Number n ? n.longValue() : 0L, payload, turn,
                    reason == null ? null : reason.toString(), failedOn == null ? null : failedOn.toString(), fence);
        } catch (IOException e) {
            return new DeadLetterEntry(String.valueOf(info.getSeq()), null, "unreadable entry", 0L, payload, null);
        }
    }

    /** Whether a stream subject is a conversation turn's dead letter. */
    boolean isTurnSubject(String subject) {
        return subject != null && subject.startsWith(subjects.deadLetterTurn(""));
    }

    private static long parseSeq(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
