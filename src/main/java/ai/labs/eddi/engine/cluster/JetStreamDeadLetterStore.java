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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    /** JetStream "no message found". */
    private static final int NO_MESSAGE = 10037;

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
                .replicas(config.natsReplicas()).build();
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

    @Override
    public String append(String conversationId, String error, long timestamp, Map<String, Object> turn) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("error", error);
        body.put("timestamp", timestamp);
        body.put("failedOn", connections.node().nodeId());
        if (turn != null) {
            body.put("turn", turn);
        }
        try {
            PublishAck ack = connections.jetStream().publish(subjects.deadLetterTurn(KvKeys.safe(conversationId)), JSON.writeValueAsBytes(body));
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
                    if (e.getApiErrorCode() == NO_MESSAGE) {
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
            if (info == null || !info.isMessage() || !info.getSubject().startsWith(subjects.deadLetterTurn(""))) {
                return Optional.empty();
            }
            return Optional.of(toEntry(info));
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == NO_MESSAGE) {
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
            return connections.jetStreamManagement().deleteMessage(stream, seq);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == NO_MESSAGE) {
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
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int purgeConversation(String conversationId) {
        try {
            return (int) connections.jetStreamManagement()
                    .purgeStream(stream, PurgeOptions.subject(subjects.deadLetterTurn(KvKeys.safe(conversationId)))).getPurged();
        } catch (IOException | JetStreamApiException e) {
            throw new ClusterUnavailableException("dead-letter purge failed: " + e.getMessage(), e);
        }
    }

    @Override
    public long count() {
        try {
            return connections.jetStreamManagement().getStreamInfo(stream).getStreamState().getMsgCount();
        } catch (IOException | JetStreamApiException e) {
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
            return new DeadLetterEntry(String.valueOf(info.getSeq()), String.valueOf(body.get("conversationId")),
                    String.valueOf(body.get("error")), ts instanceof Number n ? n.longValue() : 0L, payload, turn);
        } catch (IOException e) {
            return new DeadLetterEntry(String.valueOf(info.getSeq()), null, "unreadable entry", 0L, payload, null);
        }
    }

    private static long parseSeq(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
