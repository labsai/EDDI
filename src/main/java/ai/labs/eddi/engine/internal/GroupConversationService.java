package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Core orchestrator for multi-agent group conversations. Coordinates multiple
 * agents in structured debate rounds with moderator synthesis.
 * <p>
 * Agents participate through their normal pipelines via
 * {@link IConversationService#say}. The orchestrator constructs input messages
 * with group context and collects responses into a transcript.
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupConversationService implements IGroupConversationService {

    private static final Logger LOGGER = Logger.getLogger(GroupConversationService.class);
    private static final Environment DEFAULT_ENV = Environment.production;

    // Default input templates (Thymeleaf syntax)
    private static final String DEFAULT_ROUND1_TEMPLATE = """
            A panel of experts is discussing the following question:
            "[[${question}]]"

            As [[${displayName}]], please share your professional perspective.""";

    private static final String DEFAULT_ROUNDN_TEMPLATE = """
            The discussion continues (Round [[${round}]]).

            Previous responses:
            [# th:each="entry : ${previousResponses}"]
            — [[${entry.speaker}]] (Round [[${entry.round}]]): "[[${entry.content}]]"
            [/]

            As [[${displayName}]], please respond to the others' perspectives.""";

    private static final String DEFAULT_SYNTHESIS_TEMPLATE = """
            The panel discussed this question for [[${totalRounds}]] rounds:
            "[[${question}]]"

            Full transcript:
            [# th:each="entry : ${transcript}"]
            [Round [[${entry.round}]]] [[${entry.speaker}]]: "[[${entry.content}]]"
            [/]

            Synthesize a balanced conclusion with clear recommendation.""";

    private final IAgentGroupStore groupStore;
    private final IGroupConversationStore conversationStore;
    private final IConversationService conversationService;
    private final IAgentFactory agentFactory;
    private final ITemplatingEngine templatingEngine;
    private final int maxDepth;
    private final ExecutorService executorService;

    // Metrics
    private final Timer timerGroupDiscussion;
    private final Counter counterGroupDiscussion;
    private final Counter counterGroupFailure;

    @Inject
    public GroupConversationService(IAgentGroupStore groupStore, IGroupConversationStore conversationStore, IConversationService conversationService,
            IAgentFactory agentFactory, ITemplatingEngine templatingEngine, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.groups.max-depth", defaultValue = "3") int maxDepth) {
        this.groupStore = groupStore;
        this.conversationStore = conversationStore;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.templatingEngine = templatingEngine;
        this.maxDepth = maxDepth;
        this.executorService = Executors.newCachedThreadPool();

        this.timerGroupDiscussion = meterRegistry.timer("eddi_group_discussion_duration");
        this.counterGroupDiscussion = meterRegistry.counter("eddi_group_discussion_count");
        this.counterGroupFailure = meterRegistry.counter("eddi_group_discussion_failure_count");
    }

    @Override
    public GroupConversation discuss(String groupId, String question, String userId, int depth)
            throws GroupDiscussionException, IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {

        long startTime = System.nanoTime();
        counterGroupDiscussion.increment();

        // Depth check
        if (depth > maxDepth) {
            throw new GroupDepthExceededException("Maximum group discussion depth (%d) exceeded".formatted(maxDepth));
        }

        // Load group config
        AgentGroupConfiguration config = groupStore.read(groupId, groupStore.getCurrentResourceId(groupId).getVersion());
        if (config == null) {
            throw new IResourceStore.ResourceNotFoundException("Group configuration not found: " + groupId);
        }

        // Create group conversation
        GroupConversation gc = createGroupConversation(groupId, question, userId, depth);

        try {
            ProtocolConfig protocol = config.getProtocol() != null
                    ? config.getProtocol()
                    : new ProtocolConfig(ProtocolConfig.ProtocolType.SEQUENTIAL, 2, 60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                            ProtocolConfig.MemberUnavailablePolicy.SKIP);

            ContextConfig contextConfig = config.getContextConfig() != null
                    ? config.getContextConfig()
                    : new ContextConfig(ContextConfig.HistoryStrategy.FULL, null, false, null, null, null);

            // Order members by speakingOrder
            List<GroupMember> orderedMembers = config.getMembers().stream()
                    .sorted(Comparator.comparing(m -> m.speakingOrder() != null ? m.speakingOrder() : Integer.MAX_VALUE)).toList();

            int maxRounds = protocol.maxRounds() > 0 ? protocol.maxRounds() : 2;

            // Run debate rounds
            for (int round = 1; round <= maxRounds; round++) {
                gc.setCurrentRound(round);

                if (protocol.type() == ProtocolConfig.ProtocolType.SEQUENTIAL) {
                    executeSequentialRound(gc, config, orderedMembers, protocol, contextConfig, question, round);
                } else {
                    executeParallelRound(gc, config, orderedMembers, protocol, contextConfig, question, round);
                }

                gc.setLastModified(Instant.now());
                conversationStore.update(gc);
            }

            // Synthesis
            if (config.getModeratorAgentId() != null && !config.getModeratorAgentId().isBlank()) {
                gc.setState(GroupConversationState.SYNTHESIZING);
                conversationStore.update(gc);

                String synthesized = executeSynthesis(gc, config, contextConfig, question);
                gc.setSynthesizedAnswer(synthesized);
            }

            gc.setState(GroupConversationState.COMPLETED);
            gc.setLastModified(Instant.now());
            conversationStore.update(gc);

            return gc;

        } catch (GroupDiscussionException e) {
            gc.setState(GroupConversationState.FAILED);
            gc.setLastModified(Instant.now());
            try {
                conversationStore.update(gc);
            } catch (Exception updateErr) {
                LOGGER.warnf("Failed to update group conversation state to FAILED: %s", updateErr.getMessage());
            }
            counterGroupFailure.increment();
            throw e;
        } catch (Exception e) {
            gc.setState(GroupConversationState.FAILED);
            gc.setLastModified(Instant.now());
            try {
                conversationStore.update(gc);
            } catch (Exception updateErr) {
                LOGGER.warnf("Failed to update group conversation state to FAILED: %s", updateErr.getMessage());
            }
            counterGroupFailure.increment();
            throw new GroupDiscussionException("Group discussion failed: " + e.getMessage(), e);
        } finally {
            timerGroupDiscussion.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public GroupConversation readGroupConversation(String groupConversationId)
            throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        return conversationStore.read(groupConversationId);
    }

    @Override
    public void deleteGroupConversation(String groupConversationId) throws IResourceStore.ResourceStoreException {
        try {
            GroupConversation gc = conversationStore.read(groupConversationId);

            // Cascade-delete private conversations
            for (String privateConvId : gc.getMemberConversationIds().values()) {
                try {
                    conversationService.endConversation(privateConvId);
                } catch (Exception e) {
                    LOGGER.warnf("Failed to end private conversation %s: %s", privateConvId, e.getMessage());
                }
            }

            conversationStore.delete(groupConversationId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnf("Group conversation %s not found for deletion", groupConversationId);
        }
    }

    @Override
    public List<GroupConversation> listGroupConversations(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        return conversationStore.listByGroupId(groupId, index, limit);
    }

    // --- Private helpers ---

    private GroupConversation createGroupConversation(String groupId, String question, String userId, int depth)
            throws IResourceStore.ResourceStoreException {

        GroupConversation gc = new GroupConversation();
        gc.setGroupId(groupId);
        gc.setUserId(userId);
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion(question);
        gc.setCurrentRound(0);
        gc.setDepth(depth);
        gc.setCreated(Instant.now());
        gc.setLastModified(Instant.now());

        // Add the user question as first transcript entry
        gc.getTranscript().add(new TranscriptEntry("user", "User", question, 0, TranscriptEntryType.QUESTION, Instant.now(), null));

        String id = conversationStore.create(gc);
        gc.setId(id);
        return gc;
    }

    private void executeSequentialRound(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> members, ProtocolConfig protocol,
            ContextConfig contextConfig, String question, int round) throws GroupDiscussionException {

        for (GroupMember member : members) {
            String input = buildInput(config, contextConfig, member, question, gc.getTranscript(), round);

            TranscriptEntry entry = executeAgentTurn(member, gc, input, protocol, round);

            gc.getTranscript().add(entry);
        }
    }

    private void executeParallelRound(GroupConversation gc, AgentGroupConfiguration config, List<GroupMember> members, ProtocolConfig protocol,
            ContextConfig contextConfig, String question, int round) throws GroupDiscussionException {

        // Build inputs before parallel execution (context is same for all in parallel)
        List<TranscriptEntry> previousEntries = gc.getTranscript();

        List<CompletableFuture<TranscriptEntry>> futures = members.stream().map(member -> CompletableFuture.supplyAsync(() -> {
            try {
                String input = buildInput(config, contextConfig, member, question, previousEntries, round);
                return executeAgentTurn(member, gc, input, protocol, round);
            } catch (Exception e) {
                LOGGER.errorf("Parallel agent turn failed for %s: %s", member.agentId(), e.getMessage());
                return new TranscriptEntry(member.agentId(), member.displayName(), null, round, TranscriptEntryType.ERROR, Instant.now(),
                        e.getMessage());
            }
        }, executorService)).toList();

        int timeoutSeconds = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : 60;

        for (CompletableFuture<TranscriptEntry> future : futures) {
            try {
                TranscriptEntry entry = future.get(timeoutSeconds, TimeUnit.SECONDS);
                gc.getTranscript().add(entry);
            } catch (TimeoutException e) {
                future.cancel(true);
                gc.getTranscript().add(new TranscriptEntry("unknown", "Unknown", null, round, TranscriptEntryType.SKIPPED, Instant.now(), "Timeout"));
            } catch (Exception e) {
                gc.getTranscript()
                        .add(new TranscriptEntry("unknown", "Unknown", null, round, TranscriptEntryType.ERROR, Instant.now(), e.getMessage()));
            }
        }
    }

    private TranscriptEntry executeAgentTurn(GroupMember member, GroupConversation gc, String input, ProtocolConfig protocol, int round)
            throws GroupDiscussionException {

        // Check if agent is deployed
        try {
            var agent = agentFactory.getLatestReadyAgent(DEFAULT_ENV, member.agentId());
            if (agent == null) {
                if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                    throw new GroupDiscussionException("Agent %s is not deployed and onMemberUnavailable=FAIL".formatted(member.agentId()));
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, round, TranscriptEntryType.SKIPPED, Instant.now(),
                        "Agent not deployed");
            }
        } catch (GroupDiscussionException e) {
            throw e;
        } catch (Exception e) {
            if (protocol.onMemberUnavailable() == ProtocolConfig.MemberUnavailablePolicy.FAIL) {
                throw new GroupDiscussionException("Cannot reach agent %s: %s".formatted(member.agentId(), e.getMessage()), e);
            }
            return new TranscriptEntry(member.agentId(), member.displayName(), null, round, TranscriptEntryType.SKIPPED, Instant.now(),
                    "Agent unavailable: " + e.getMessage());
        }

        // Get or create private conversation
        String privateConvId = gc.getMemberConversationIds().get(member.agentId());
        if (privateConvId == null) {
            try {
                Map<String, Context> groupContext = new LinkedHashMap<>();
                groupContext.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
                groupContext.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));

                var result = conversationService.startConversation(DEFAULT_ENV, member.agentId(), gc.getUserId(), groupContext);
                privateConvId = result.conversationId();
                gc.getMemberConversationIds().put(member.agentId(), privateConvId);
            } catch (Exception e) {
                return handleAgentFailure(member, round, protocol, e, "Failed to start conversation");
            }
        }

        // Build InputData with group context
        InputData inputData = new InputData();
        inputData.setInput(input);
        Map<String, Context> context = new LinkedHashMap<>();
        context.put("groupTranscript", new Context(Context.ContextType.object, gc.getTranscript()));
        context.put("groupConversationId", new Context(Context.ContextType.string, gc.getId()));
        context.put("groupDepth", new Context(Context.ContextType.string, String.valueOf(gc.getDepth())));
        inputData.setContext(context);

        // Call through ConversationService
        int retries = 0;
        int maxRetries = protocol.maxRetries() > 0 ? protocol.maxRetries() : 2;
        int timeout = protocol.agentTimeoutSeconds() > 0 ? protocol.agentTimeoutSeconds() : 60;

        while (true) {
            try {
                CompletableFuture<String> responseFuture = new CompletableFuture<>();
                final String convId = privateConvId;

                conversationService.say(DEFAULT_ENV, member.agentId(), convId, false, true, null, inputData, false, snapshot -> {
                    String response = extractResponse(snapshot);
                    responseFuture.complete(response);
                });

                String response = responseFuture.get(timeout, TimeUnit.SECONDS);

                return new TranscriptEntry(member.agentId(), member.displayName(), response, round, TranscriptEntryType.OPINION, Instant.now(), null);

            } catch (TimeoutException e) {
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s timed out (attempt %d/%d), retrying...", member.agentId(), retries, maxRetries);
                    continue;
                }
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
                    throw new GroupDiscussionException("Agent %s timed out and onAgentFailure=ABORT".formatted(member.agentId()));
                }
                return new TranscriptEntry(member.agentId(), member.displayName(), null, round, TranscriptEntryType.SKIPPED, Instant.now(),
                        "Timeout after " + timeout + "s");

            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.RETRY && retries < maxRetries) {
                    retries++;
                    LOGGER.warnf("Agent %s failed (attempt %d/%d): %s", member.agentId(), retries, maxRetries, cause.getMessage());
                    continue;
                }
                return handleAgentFailure(member, round, protocol, cause, "Agent execution failed");
            }
        }
    }

    private String executeSynthesis(GroupConversation gc, AgentGroupConfiguration config, ContextConfig contextConfig, String question)
            throws GroupDiscussionException {

        String moderatorId = config.getModeratorAgentId();
        String template = contextConfig.inputTemplateSynthesis() != null ? contextConfig.inputTemplateSynthesis() : DEFAULT_SYNTHESIS_TEMPLATE;

        String input;
        try {
            Map<String, Object> templateData = buildSynthesisTemplateData(gc, question);
            input = templatingEngine.processTemplate(template, templateData, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for synthesis, using plain text: %s", e.getMessage());
            input = buildPlainTextSynthesisInput(gc, question);
        }

        // Create a fake GroupMember for the moderator
        GroupMember moderator = new GroupMember(moderatorId, "Moderator", null, false, false);

        ProtocolConfig defaultProtocol = config.getProtocol() != null
                ? config.getProtocol()
                : new ProtocolConfig(ProtocolConfig.ProtocolType.SEQUENTIAL, 2, 120, ProtocolConfig.MemberFailurePolicy.ABORT, 1,
                        ProtocolConfig.MemberUnavailablePolicy.FAIL);

        TranscriptEntry entry = executeAgentTurn(moderator, gc, input, defaultProtocol, -1);

        gc.getTranscript().add(entry);

        if (entry.type() == TranscriptEntryType.ERROR || entry.type() == TranscriptEntryType.SKIPPED) {
            LOGGER.warnf("Synthesis failed: %s", entry.errorReason());
            return null;
        }

        return entry.content();
    }

    private String buildInput(AgentGroupConfiguration config, ContextConfig contextConfig, GroupMember member, String question,
            List<TranscriptEntry> transcript, int round) {

        String template;
        Map<String, Object> templateData = new LinkedHashMap<>();
        templateData.put("question", question);
        templateData.put("displayName", member.displayName());
        templateData.put("round", round);

        if (round == 1) {
            template = contextConfig.inputTemplateRound1() != null ? contextConfig.inputTemplateRound1() : DEFAULT_ROUND1_TEMPLATE;
        } else {
            template = contextConfig.inputTemplateRoundN() != null ? contextConfig.inputTemplateRoundN() : DEFAULT_ROUNDN_TEMPLATE;

            // Build previous responses based on history strategy
            List<Map<String, Object>> previousResponses = buildContextEntries(transcript, contextConfig, round);
            templateData.put("previousResponses", previousResponses);
        }

        try {
            return templatingEngine.processTemplate(template, templateData, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed, using plain text fallback: %s", e.getMessage());
            return buildPlainTextInput(member, question, transcript, round);
        }
    }

    private List<Map<String, Object>> buildContextEntries(List<TranscriptEntry> transcript, ContextConfig contextConfig, int currentRound) {

        ContextConfig.HistoryStrategy strategy = contextConfig.historyStrategy() != null
                ? contextConfig.historyStrategy()
                : ContextConfig.HistoryStrategy.FULL;

        return transcript.stream().filter(e -> e.type() == TranscriptEntryType.OPINION || e.type() == TranscriptEntryType.QUESTION)
                .filter(e -> switch (strategy) {
                    case FULL -> true;
                    case LAST_ROUND -> e.round() >= currentRound - 1;
                    case WINDOW -> {
                        int windowSize = contextConfig.windowSize() != null ? contextConfig.windowSize() : 3;
                        yield e.round() >= currentRound - windowSize;
                    }
                }).map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("speaker", e.speakerDisplayName());
                    entry.put("content", e.content());
                    entry.put("round", e.round());
                    return entry;
                }).collect(Collectors.toList());
    }

    private Map<String, Object> buildSynthesisTemplateData(GroupConversation gc, String question) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("totalRounds", gc.getCurrentRound());
        data.put("transcript", gc.getTranscript().stream().filter(e -> e.type() == TranscriptEntryType.OPINION).map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("speaker", e.speakerDisplayName());
            entry.put("content", e.content());
            entry.put("round", e.round());
            return entry;
        }).collect(Collectors.toList()));
        return data;
    }

    private TranscriptEntry handleAgentFailure(GroupMember member, int round, ProtocolConfig protocol, Throwable cause, String prefix)
            throws GroupDiscussionException {

        if (protocol.onAgentFailure() == ProtocolConfig.MemberFailurePolicy.ABORT) {
            throw new GroupDiscussionException(
                    "%s for agent %s and onAgentFailure=ABORT: %s".formatted(prefix, member.agentId(), cause.getMessage()));
        }
        return new TranscriptEntry(member.agentId(), member.displayName(), null, round, TranscriptEntryType.SKIPPED, Instant.now(),
                prefix + ": " + cause.getMessage());
    }

    private String extractResponse(ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot snapshot) {
        if (snapshot == null || snapshot.getConversationOutputs() == null) {
            return "";
        }
        // Extract the last output from the conversation
        var outputs = snapshot.getConversationOutputs();
        if (outputs.isEmpty()) {
            return "";
        }
        var lastOutput = outputs.get(outputs.size() - 1);
        if (lastOutput == null) {
            return "";
        }
        // Try to get the output string
        return lastOutput.toString();
    }

    // --- Plain text fallbacks ---

    private String buildPlainTextInput(GroupMember member, String question, List<TranscriptEntry> transcript, int round) {

        var sb = new StringBuilder();
        if (round == 1) {
            sb.append("A panel of experts is discussing: \"").append(question).append("\"\n\n");
            sb.append("As ").append(member.displayName()).append(", please share your professional perspective.");
        } else {
            sb.append("The discussion continues (Round ").append(round).append(").\n\n");
            sb.append("Previous responses:\n");
            for (TranscriptEntry e : transcript) {
                if (e.type() == TranscriptEntryType.OPINION && e.content() != null) {
                    sb.append("— ").append(e.speakerDisplayName()).append(" (Round ").append(e.round()).append("): \"").append(e.content())
                            .append("\"\n");
                }
            }
            sb.append("\nAs ").append(member.displayName()).append(", please respond to the others' perspectives.");
        }
        return sb.toString();
    }

    private String buildPlainTextSynthesisInput(GroupConversation gc, String question) {
        var sb = new StringBuilder();
        sb.append("The panel discussed this question for ").append(gc.getCurrentRound()).append(" rounds:\n\"").append(question)
                .append("\"\n\nFull transcript:\n");
        for (TranscriptEntry e : gc.getTranscript()) {
            if (e.type() == TranscriptEntryType.OPINION && e.content() != null) {
                sb.append("[Round ").append(e.round()).append("] ").append(e.speakerDisplayName()).append(": \"").append(e.content()).append("\"\n");
            }
        }
        sb.append("\nSynthesize a balanced conclusion with clear recommendation.");
        return sb.toString();
    }
}
