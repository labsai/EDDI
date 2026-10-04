/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestClusterAdmin;
import ai.labs.eddi.engine.cluster.admin.ClusterActivityLog;
import ai.labs.eddi.engine.cluster.admin.ClusterAdminService;
import ai.labs.eddi.engine.cluster.admin.ClusterAdminService.ActionRefusedException;
import ai.labs.eddi.engine.cluster.admin.ClusterAdminService.DeadLetterFilter;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActionResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActivityEvent;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkRequest;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.ClusterOverview;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterPage;
import ai.labs.eddi.engine.model.ClusterAdminModels.DeadLetterSummary;
import ai.labs.eddi.engine.model.ClusterAdminModels.Diagnosis;
import ai.labs.eddi.engine.model.ClusterAdminModels.LeasePage;
import ai.labs.eddi.engine.model.ClusterAdminModels.ReleaseRequest;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * REST adapter of the cluster console. The logic is in
 * {@link ClusterAdminService}; this class adds the caller's identity (for the
 * audit entry of every action), maps refusals to their status codes and runs
 * the activity SSE stream.
 */
@ApplicationScoped
public class RestClusterAdmin implements IRestClusterAdmin {

    private static final Logger LOGGER = Logger.getLogger(RestClusterAdmin.class);
    private static final long PING_SECONDS = 15;
    private static final String ADMIN_ROLE = "eddi-admin";

    private final ClusterAdminService service;
    private final ClusterActivityLog activity;
    private final SecurityIdentity identity;
    private final Map<SseEventSink, Consumer<ActivityEvent>> streams = new ConcurrentHashMap<>();
    private final Map<SseEventSink, Long> expiries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService pinger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cluster-activity-sse-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile Sse pingSse;

    @Inject
    public RestClusterAdmin(ClusterAdminService service, ClusterActivityLog activity, SecurityIdentity identity) {
        this.service = service;
        this.activity = activity;
        this.identity = identity;
    }

    @Override
    public ClusterOverview getOverview() {
        return service.overview();
    }

    @Override
    public LeasePage getLeases(String query, boolean flaggedOnly, int limit) {
        return guarded(() -> service.leases(query, flaggedOnly, limit));
    }

    @Override
    public ActionResult releaseLease(String conversationId, ReleaseRequest request) {
        requireId(conversationId);
        return guarded(() -> service.forceRelease(conversationId, request == null ? null : request.expectedRevision(), actor()));
    }

    @Override
    public List<ActivityEvent> getActivity(int limit, List<String> types) {
        boolean admin = isAdmin();
        return activity.recent(limit, types).stream().map(e -> forCaller(e, admin)).toList();
    }

    private boolean isAdmin() {
        return identity != null && identity.hasRole(ADMIN_ROLE);
    }

    /**
     * An administrator's name is a person's identifier (often an e-mail address):
     * the read-only viewer sees that an administrator acted, not who.
     */
    static ActivityEvent forCaller(ActivityEvent event, boolean admin) {
        if (admin || event.payload() == null || !event.payload().containsKey("actor")) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        payload.put("actor", "an administrator");
        return new ActivityEvent(event.id(), event.type(), event.severity(), event.node(), event.ts(), payload);
    }

    @Override
    public void streamActivity(SseEventSink sink, Sse sse) {
        // Read here: the identity is bound to the request, not to the event threads
        // below.
        boolean admin = isAdmin();
        Consumer<ActivityEvent> listener = event -> send(sink, sse.newEventBuilder().name("activity").mediaType(MediaType.APPLICATION_JSON_TYPE)
                .data(ActivityEvent.class, forCaller(event, admin)).build());
        if (!activity.addListener(listener, admin)) {
            sink.send(sse.newEventBuilder().name("busy").data("too many subscribers on this node").build())
                    .whenComplete((v, t) -> sink.close());
            return;
        }
        streams.put(sink, listener);
        long expiresAt = tokenExpiry();
        if (expiresAt > 0) {
            expiries.put(sink, expiresAt);
        }
        startPinger(sse);
        send(sink, sse.newEventBuilder().name("ping").data(String.valueOf(System.currentTimeMillis())).build());
    }

    private void send(SseEventSink sink, OutboundSseEvent event) {
        if (sink.isClosed()) {
            drop(sink);
            return;
        }
        sink.send(event).exceptionally(t -> {
            drop(sink);
            return null;
        });
    }

    private void drop(SseEventSink sink) {
        expiries.remove(sink);
        Consumer<ActivityEvent> listener = streams.remove(sink);
        if (listener != null) {
            activity.removeListener(listener);
        }
    }

    private synchronized void startPinger(Sse sse) {
        if (pingSse != null) {
            return;
        }
        pingSse = sse;
        pinger.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (SseEventSink sink : Set.copyOf(streams.keySet())) {
                try {
                    Long expiresAt = expiries.get(sink);
                    if (expiresAt != null && now >= expiresAt) {
                        // The stream must not outlive the access token it was opened with: a
                        // revoked or downgraded user keeps nothing. The client reconnects
                        // with its refreshed token, which is checked again.
                        expire(sink);
                        continue;
                    }
                    send(sink, pingSse.newEventBuilder().name("ping").data(String.valueOf(now)).build());
                } catch (RuntimeException e) {
                    drop(sink);
                }
            }
        }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
    }

    private void expire(SseEventSink sink) {
        drop(sink);
        if (!sink.isClosed()) {
            sink.send(pingSse.newEventBuilder().name("expired").data("token expired").build()).whenComplete((v, t) -> sink.close());
        }
    }

    /** When the caller's access token expires (epoch millis), or 0 without one. */
    long tokenExpiry() {
        if (identity != null && identity.getPrincipal() instanceof JsonWebToken jwt && jwt.getExpirationTime() > 0) {
            return jwt.getExpirationTime() * 1000L;
        }
        return 0;
    }

    /** For tests: closes every stream whose token has expired by {@code now}. */
    int expireStreams(long now) {
        int closed = 0;
        for (Map.Entry<SseEventSink, Long> e : Map.copyOf(expiries).entrySet()) {
            if (now >= e.getValue()) {
                drop(e.getKey());
                e.getKey().close();
                closed++;
            }
        }
        return closed;
    }

    @PreDestroy
    void close() {
        pinger.shutdownNow();
        for (SseEventSink sink : Set.copyOf(streams.keySet())) {
            drop(sink);
            try {
                sink.close();
            } catch (RuntimeException ignored) {
                // shutting down
            }
        }
    }

    @Override
    public Diagnosis diagnose(String conversationId) {
        requireId(conversationId);
        return guarded(() -> service.diagnose(conversationId));
    }

    @Override
    public DeadLetterPage getDeadLetters(int limit, String after, String reason, String nodeId, String agentId, String conversationId, Long from,
                                         Long to) {
        return guarded(() -> service.deadLetters(limit, after, new DeadLetterFilter(reason, nodeId, agentId, conversationId, from, to), true));
    }

    @Override
    public DeadLetterSummary getDeadLetterSummary() {
        return guarded(service::deadLetterSummary);
    }

    @Override
    public BulkResult replayDeadLetters(BulkRequest request) {
        return guarded(() -> service.replay(request == null ? null : request.ids(), actor()));
    }

    @Override
    public BulkResult discardDeadLetters(BulkRequest request) {
        return guarded(() -> service.discard(request == null ? null : request.ids(), actor()));
    }

    @Override
    public ActionResult forwardLocalDeadLetters() {
        return guarded(() -> service.forwardLocalDeadLetters(actor()));
    }

    @Override
    public ActionResult resyncCaches() {
        return guarded(() -> service.resyncCaches(actor()));
    }

    @Override
    public ActionResult reconcileDeployments() {
        return guarded(() -> service.reconcileDeployments(actor()));
    }

    @Override
    public ActionResult drainNode(String nodeId) {
        requireId(nodeId);
        return guarded(() -> service.drain(nodeId, true, actor()));
    }

    @Override
    public ActionResult undrainNode(String nodeId) {
        requireId(nodeId);
        return guarded(() -> service.drain(nodeId, false, actor()));
    }

    private String actor() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return "anonymous";
        }
        return identity.getPrincipal().getName();
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 200) {
            throw new IllegalArgumentException("a non-blank id of at most 200 characters is required");
        }
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T guarded(Call<T> call) {
        try {
            return call.run();
        } catch (ActionRefusedException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", e.code());
            body.put("message", e.getMessage());
            LOGGER.infof("Cluster action refused (%s): %s", e.code(), sanitize(e.getMessage()));
            throw new WebApplicationException(Response.status(e.status()).type(MediaType.APPLICATION_JSON).entity(body).build());
        } catch (ClusterUnavailableException e) {
            throw new WebApplicationException(Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
                    .entity(Map.of("code", "NATS_UNREACHABLE", "message", "NATS is unreachable from this node")).build());
        }
    }
}
