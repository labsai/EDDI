/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build guard (plan §6.3, §14.2): work handed to another thread does not carry
 * the org binding unless it is wrapped with {@link OrgPropagation}.
 * <p>
 * Two chokepoints carry it for most work:
 * <ul>
 * <li>{@code BaseRuntime.submitCallable}, which captures at submission;</li>
 * <li>the conversation coordinators, which capture at enqueue.</li>
 * </ul>
 * Every other raw async call is a hand-off the binding does not survive.
 * <p>
 * This guard is a ratchet. The {@link #NOT_YET_PROPAGATING} classes used raw
 * async APIs before org propagation existed. The later packages work through
 * them, and the guard enforces three rules:
 * <ul>
 * <li>a new class that uses one must propagate;</li>
 * <li>a class that starts propagating must leave the list;</li>
 * <li>a class that stops using raw async APIs must leave the list.</li>
 * </ul>
 * It sees only that a class mentions {@code OrgPropagation}, not that every
 * hand-off in it is wrapped; review covers that.
 */
class OrgHandOffGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /**
     * Thread hand-offs: executors, async futures, Mutiny threading, raw threads.
     */
    private static final Pattern RAW_ASYNC = Pattern.compile("Executors\\.new|newVirtualThread|ManagedExecutor|supplyAsync\\(|runAsync\\("
            + "|then(Apply|Accept|Compose|Run|Combine)Async|(whenComplete|handle|exceptionally)Async|emitOn\\(|runSubscriptionOn\\("
            + "|Thread\\.ofVirtual|new Thread\\(|ForkJoinPool|parallelStream\\(|executeBlocking\\("
            + "|(?i:executor|scheduler|pool)\\w*(\\(\\))?\\.(execute|submit|schedule|scheduleAtFixedRate|scheduleWithFixedDelay)\\("
            + "|\\.schedule(AtFixedRate|WithFixedDelay)\\(|\\.execute\\(\\(\\) ->|\\.submit\\(\\(\\) ->");
    private static final Pattern PROPAGATES = Pattern.compile("\\bOrgPropagation\\b|\\bOrgCapturedTask\\b");

    /**
     * Raw async users that do not propagate yet. Shrink it; never grow it without
     * deciding the hand-off needs no org (and then say so in the class instead).
     */
    private static final Set<String> NOT_YET_PROPAGATING = Set.of(
            "A2AInFlightLimiter", "AgentDeploymentManagement", "AgentTestRunner", "AgentTestService", "AlertDispatcher",
            "ApiCallsTask", "AuditKeyring", "AuditLedgerService", "BoundedBodyReader", "BoundedFanOut", "BoundedHostResolver",
            "BoundedLogStore", "CascadingModelExecutor", "ClusterActivityLog", "ClusterAdminService", "ClusterHitlRecovery",
            "ClusterPresence", "ClusterWatcher", "ConversationTitleService", "EmailReplyComposer", "EmailReplySender",
            "GroupApprovedResultActions", "GroupConversationService", "GroupHitlCoordinator", "GroupTieBreakPauses",
            "GuardrailModelClient", "HitlCrashRecoveryObserver", "HttpCallToolsProvider", "HttpClientModule", "JetStreamEventBus",
            "KvLeaseManager", "LlmConfiguration", "LlmTask", "McpCallsTask", "MetricsScrapeRoute", "NatsClusterRpc",
            "NatsConnectionManager", "NatsLeaseManager", "OAuthTokenService", "ObservableChatModel", "OffloadingScheduler",
            "OperatorRevisionCheck", "OutboundHttp", "OutputMatchHook", "PhaseExecutionEngine", "PostgresAuditStore",
            "PostgresFieldIndexSwaps", "PostgresSubstringSearchIndexes", "ProgressLogger", "RagSourceIngestionService",
            "RestAgentEngineStreaming", "RestAlertEvents", "RestApprovalAnalytics", "RestAttachmentUpload", "RestClusterAdmin",
            "RestCoordinatorAdmin", "RestGroupConversation", "RestLogAdmin", "RestRagIngestion", "RetryConfiguration",
            "SampledAuditRecorder", "SchedulePollerService", "ServiceConvergenceJudge", "SlackEventHandler",
            "SlackInteractivityHandler", "TaskForceEngine", "ToolExecutionService", "ToolLoopRunner", "UnrestrictedSecretsCheck",
            "UserEventHub", "WebScraperTool", "WorkspaceNotifications");

    private record Source(String name, String text) {
    }

    private static List<Source> sources() throws IOException {
        List<Source> all = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(f -> f.toString().endsWith(".java")).filter(f -> !f.toString().replace('\\', '/').contains("/engine/org/"))
                    .forEach(f -> {
                        try {
                            all.add(new Source(f.getFileName().toString().replace(".java", ""), Files.readString(f)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        return all;
    }

    @Test
    void everyHandOffPropagatesTheOrgOrIsAKnownLeftover() throws IOException {
        Set<String> unpropagated = new TreeSet<>();
        Set<String> propagating = new TreeSet<>();
        for (Source s : sources()) {
            if (!RAW_ASYNC.matcher(s.text()).find()) {
                continue;
            }
            if (PROPAGATES.matcher(s.text()).find()) {
                propagating.add(s.name());
            } else {
                unpropagated.add(s.name());
            }
        }
        assertTrue(propagating.containsAll(Set.of("BaseRuntime", "ClusterConversationCoordinator", "UserDirectory")),
                "the chokepoints no longer propagate, or the pattern is broken: " + propagating);

        Set<String> added = new TreeSet<>(unpropagated);
        added.removeAll(NOT_YET_PROPAGATING);
        assertTrue(added.isEmpty(), "These classes hand work to another thread without carrying the org binding. Wrap the work "
                + "with OrgPropagation (plan §6.3), or submit it through IRuntime.submitCallable: " + added);

        Set<String> done = new TreeSet<>(NOT_YET_PROPAGATING);
        done.removeAll(unpropagated);
        assertTrue(done.isEmpty(), "These classes now propagate the org or no longer hand work off; remove them from "
                + "NOT_YET_PROPAGATING: " + done);
    }
}
