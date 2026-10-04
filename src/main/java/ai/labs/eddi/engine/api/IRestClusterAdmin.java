/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

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
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.NoCache;

import java.util.List;

/**
 * The cluster console: health, nodes, NATS/JetStream, leases, dead letters, the
 * activity timeline, a stuck-conversation diagnosis and the recovery actions.
 * <p>
 * <b>Roles.</b> Operational metadata — ids, counts, states, timings — is open
 * to {@code eddi-admin} and the read-only {@code eddi-viewer} (EDDI has no role
 * hierarchy, so both are listed). Everything that carries conversation content
 * (the dead-letter listing with its captured input) and every action is
 * {@code eddi-admin} only.
 *
 * @since 6.6.0
 */
@Path("/administration/cluster")
@Tag(name = "Operations / Cluster", description = "Cluster health, leases, dead letters, activity and recovery actions")
@RolesAllowed("eddi-admin")
public interface IRestClusterAdmin {

    @GET
    @Path("/overview")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "Cluster health overview", description = "Health verdict (HEALTHY, DEGRADED, PARTITIONED, SINGLE_NODE) with "
            + "its reason codes, one card per node (live, stale, lost or left), NATS and JetStream as this node sees them "
            + "(streams, KV buckets, replicas, consumer lag, the leases bucket generation) and the dead-letter counts.")
    @APIResponse(responseCode = "200", description = "Overview.")
    ClusterOverview getOverview();

    @GET
    @Path("/leases")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "List leases", description = "Every lease in the LEASES bucket with holder, age, revision (fencing token), "
            + "last renewal and suspicious flags (HOLDER_GONE, HOLDER_RESTARTED, NOT_RENEWED, LONG_RUNNING, CONTENDED); suspicious first. "
            + "Empty on a single node.")
    @APIResponse(responseCode = "200", description = "A page of leases.")
    LeasePage getLeases(@QueryParam("q") String query, @QueryParam("flagged")
    @DefaultValue("false") boolean flaggedOnly,
                        @QueryParam("limit")
                        @DefaultValue("100") int limit);

    @POST
    @Path("/leases/{conversationId}/release")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Force-release a conversation lease", description = "Deletes the lease by compare-and-set, refused with "
            + "RENEWED when expectedRevision is given and the holder renewed since. Safe because of the fence: a still-alive former "
            + "holder's late write is refused and dead-lettered. Audited.")
    @APIResponse(responseCode = "200", description = "RELEASED, ALREADY_RELEASED or RENEWED.")
    @APIResponse(responseCode = "409", description = "Not in cluster mode.")
    @APIResponse(responseCode = "503", description = "NATS unreachable.")
    ActionResult releaseLease(@PathParam("conversationId") String conversationId, ReleaseRequest request);

    @GET
    @Path("/activity")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "Recent cluster activity", description = "The latest entries of the cluster-wide activity timeline (at most "
            + "500), oldest first; type filters by prefix, e.g. node. or admin.")
    @APIResponse(responseCode = "200", description = "Activity entries.")
    List<ActivityEvent> getActivity(@QueryParam("limit")
    @DefaultValue("200") int limit, @QueryParam("type") List<String> types);

    @GET
    @Path("/activity/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "Stream cluster activity (SSE)", description = "Emits an 'activity' event per new timeline entry and a "
            + "'ping' every 15 seconds. At most 16 subscribers per node; beyond that the stream answers one 'busy' event and closes.")
    void streamActivity(@Context SseEventSink eventSink, @Context Sse sse);

    @GET
    @Path("/diagnose/{conversationId}")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "Why is this conversation stuck?", description = "Its lease and holder, the turns queued for it on each node, "
            + "its state, its dead letters (without content) and the findings with a suggested action.")
    @APIResponse(responseCode = "200", description = "Diagnosis.")
    Diagnosis diagnose(@PathParam("conversationId") String conversationId);

    @GET
    @Path("/dead-letters")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Query dead letters", description = "A filtered page, oldest first, including the captured input. Filters: "
            + "reason (fenced, timeout, failed), nodeId, agentId, conversationId (substring), from/to (epoch millis). Reads at most "
            + "2000 entries per call; continue from nextCursor.")
    @APIResponse(responseCode = "200", description = "A page of dead letters.")
    DeadLetterPage getDeadLetters(@QueryParam("limit")
    @DefaultValue("50") int limit, @QueryParam("after") String after, @QueryParam("reason") String reason,
                                  @QueryParam("nodeId") String nodeId, @QueryParam("agentId") String agentId,
                                  @QueryParam("conversationId") String conversationId, @QueryParam("from") Long from,
                                  @QueryParam("to") Long to);

    @GET
    @Path("/dead-letters/summary")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"eddi-admin", "eddi-viewer"})
    @Operation(summary = "Dead-letter counts", description = "Counts by reason, node and agent, the oldest and newest timestamps and "
            + "the five newest entries — without any captured input.")
    @APIResponse(responseCode = "200", description = "Summary.")
    DeadLetterSummary getDeadLetterSummary();

    @POST
    @Path("/dead-letters/replay")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Replay dead letters", description = "Replays up to 100 entries, each as a NEW turn of its conversation, and "
            + "reports every id's outcome (REPLAYED, NOT_FOUND, NOT_REPLAYABLE with the reason, REJECTED, UNAVAILABLE). Audited.")
    @APIResponse(responseCode = "200", description = "Per-item outcomes.")
    @APIResponse(responseCode = "400", description = "No ids, or more than 100.")
    BulkResult replayDeadLetters(BulkRequest request);

    @POST
    @Path("/dead-letters/discard")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Discard dead letters", description = "Permanently removes up to 100 entries and reports every id's outcome "
            + "(DISCARDED, NOT_FOUND, UNAVAILABLE). Audited.")
    @APIResponse(responseCode = "200", description = "Per-item outcomes.")
    @APIResponse(responseCode = "400", description = "No ids, or more than 100.")
    BulkResult discardDeadLetters(BulkRequest request);

    @POST
    @Path("/dead-letters/forward-local")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Forward node-local dead letters", description = "Asks every node to move the dead letters it kept while NATS "
            + "was unreachable into the shared stream now. Audited.")
    @APIResponse(responseCode = "200", description = "Forwarded counts per node.")
    ActionResult forwardLocalDeadLetters();

    @POST
    @Path("/caches/resync")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Resync caches cluster-wide", description = "Every node flushes its invalidatable caches and reloads from the "
            + "database. Audited.")
    @APIResponse(responseCode = "200", description = "Done.")
    ActionResult resyncCaches();

    @POST
    @Path("/deployments/reconcile")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Reconcile deployments now", description = "Runs the deployment sweep on every node at once instead of at its "
            + "next 10 s tick. Audited.")
    @APIResponse(responseCode = "200", description = "The nodes that ran it.")
    ActionResult reconcileDeployments();

    @POST
    @Path("/nodes/{nodeId}/drain")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Drain a node", description = "The node takes no new turns (409 + Retry-After) and reports itself not ready; "
            + "running turns finish. Refused for the last node that still takes turns. Lasts until undrain or restart. Audited.")
    @APIResponse(responseCode = "200", description = "Drained.")
    @APIResponse(responseCode = "404", description = "No such live node.")
    @APIResponse(responseCode = "409", description = "The last undrained node, or not in cluster mode.")
    ActionResult drainNode(@PathParam("nodeId") String nodeId);

    @POST
    @Path("/nodes/{nodeId}/undrain")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Undrain a node", description = "The node takes turns again. Audited.")
    @APIResponse(responseCode = "200", description = "Undrained.")
    ActionResult undrainNode(@PathParam("nodeId") String nodeId);
}
