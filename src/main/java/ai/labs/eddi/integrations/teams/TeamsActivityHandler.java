/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.integrations.channels.ChannelConnectionCredentials;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ChatThreadConversations;
import ai.labs.eddi.integrations.channels.ChatThreadConversations.ThreadConversation;
import ai.labs.eddi.integrations.channels.InboundOrigin;
import ai.labs.eddi.integrations.slack.SlackHitlSupport;
import ai.labs.eddi.integrations.teams.refs.ITeamsConversationReferenceStore;
import ai.labs.eddi.integrations.teams.refs.ITeamsConversationReferenceStore.TeamsConversationReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Handles one verified Bot Connector activity: finds the integration that owns
 * it, checks the tenant, routes it to a target and answers in the same
 * conversation.
 * <p>
 * Everything here runs after {@link TeamsTokenVerifier} established the app id
 * the activity was sent to ({@code aud}) and that the {@code serviceUrl} is the
 * Bot Connector's own. Nothing in the body is trusted to name an integration:
 * <ol>
 * <li>The integration is the one bound to the conversation's channel
 * ({@code conversationId} without its {@code ;messageid=} suffix) <em>and</em>
 * declaring the verified app id, or else the app's channel-less integration.
 * </li>
 * <li>The activity's tenant must be the integration's {@code tenantId} or one
 * of its {@code allowedTenantIds}. Single-tenant bots receive no tenant
 * isolation from the platform, so this check is the boundary.</li>
 * <li>The integration's connection must still be the app's own (its client id
 * and pinned token endpoint), because an import or a direct store write skips
 * save validation.</li>
 * <li>The route is resolved from that integration only; a locked thread target
 * is used only if that integration still serves it.</li>
 * </ol>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsActivityHandler {

    private static final Logger LOGGER = Logger.getLogger(TeamsActivityHandler.class);

    static final String HELP_TEXT = "Hi! Send me a message and I'll pass it to the agent. In a channel, @mention me.";
    static final String WELCOME_TEXT = "Hi! I'm connected. Send me a message to get started.";
    static final String GROUP_NOT_SUPPORTED = "Group discussions are not available in Teams yet.";
    static final String DELIVERY_FAILED_TEXT = "⚠️ I have an answer but could not post it here. Please try again, or ask for a shorter reply.";
    static final String ERROR_TEXT = "⚠️ Sorry, I encountered an error processing your message. Please try again.";
    static final String AWAITING_NOTICE = "⏸️ This conversation is waiting for a reviewer's approval before it can continue.";

    private static final SimpleConversationMemorySnapshot SKIPPED_STILL_AWAITING = new SimpleConversationMemorySnapshot();
    private static final SimpleConversationMemorySnapshot SKIPPED_NOT_ACTIVE = new SimpleConversationMemorySnapshot();
    private static final SimpleConversationMemorySnapshot SKIPPED_ENDED = new SimpleConversationMemorySnapshot();

    private final ChannelTargetRouter router;
    private final TeamsChannelType channelType;
    private final ChannelConnectionCredentials credentials;
    private final ChatThreadConversations threads;
    private final IConversationService conversationService;
    private final TeamsConnectorClient connector;
    private final ITeamsConversationReferenceStore references;
    private final int requestTimeoutSeconds;
    private final TeamsMetrics metrics;
    private final ExecutorService executor;

    @Inject
    public TeamsActivityHandler(ChannelTargetRouter router, TeamsChannelType channelType, ChannelConnectionCredentials credentials,
            ChatThreadConversations threads, IConversationService conversationService, TeamsConnectorClient connector,
            ITeamsConversationReferenceStore references, TeamsMetrics metrics,
            @ConfigProperty(name = "eddi.teams.request-timeout-seconds", defaultValue = "60") int requestTimeoutSeconds) {
        this.metrics = metrics != null ? metrics : TeamsMetrics.none();
        this.router = router;
        this.channelType = channelType;
        this.credentials = credentials;
        this.threads = threads;
        this.conversationService = conversationService;
        this.connector = connector;
        this.references = references;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Whether some Teams integration declares {@code appId}. */
    public boolean isKnownApp(String appId) {
        return router.declaresApplication(TeamsChannelType.ID, appId);
    }

    /** Processes a verified activity in the background. */
    public void handleAsync(String verifiedAppId, JsonNode activity) {
        executor.submit(() -> {
            try {
                handle(verifiedAppId, activity);
            } catch (Exception e) {
                LOGGER.errorf(e, "Error handling Teams activity for app %s", sanitize(verifiedAppId));
            }
        });
    }

    /** The parsed fields of an activity this handler uses. */
    record Activity(String type, String id, String serviceUrl, String conversationId, String conversationType, String tenantId,
            String fromId, String fromAadObjectId, String recipientId, boolean targeted, String text, JsonNode raw) {

        /** The conversation without a channel thread's {@code ;messageid=} part. */
        String baseConversationId() {
            int semicolon = conversationId.indexOf(';');
            return semicolon < 0 ? conversationId : conversationId.substring(0, semicolon);
        }
    }

    /**
     * Reads the fields this handler uses. The tenant may appear in
     * {@code channelData.tenant.id} and in {@code conversation.tenantId}; when both
     * are present and disagree, the activity has no tenant (and is refused), rather
     * than one of the two being picked.
     */
    static Activity parse(JsonNode node) {
        JsonNode conversation = node.path("conversation");
        String channelDataTenant = text(node.path("channelData").path("tenant"), "id");
        String conversationTenant = text(conversation, "tenantId");
        String tenantId;
        if (channelDataTenant != null && conversationTenant != null) {
            tenantId = channelDataTenant.equalsIgnoreCase(conversationTenant) ? channelDataTenant : null;
        } else {
            tenantId = channelDataTenant != null ? channelDataTenant : conversationTenant;
        }
        JsonNode recipient = node.path("recipient");
        return new Activity(text(node, "type"), text(node, "id"), text(node, "serviceUrl"), text(conversation, "id"),
                text(conversation, "conversationType"), tenantId, text(node.path("from"), "id"), text(node.path("from"), "aadObjectId"),
                text(recipient, "id"), recipient.path("isTargeted").asBoolean(false), text(node, "text"), node);
    }

    void handle(String verifiedAppId, JsonNode node) throws Exception {
        Activity activity = parse(node);
        if (activity.conversationId() == null || activity.serviceUrl() == null) {
            LOGGER.debug("[TEAMS] Activity without a conversation or serviceUrl — ignoring");
            return;
        }
        ChannelIntegrationConfiguration integration = owningIntegration(verifiedAppId, activity.baseConversationId()).orElse(null);
        if (integration == null) {
            LOGGER.warnf("[TEAMS] No integration serves app %s in conversation %s — ignoring", sanitize(verifiedAppId),
                    sanitize(activity.baseConversationId()));
            metrics.activity(TeamsMetrics.NO_INTEGRATION);
            return;
        }
        String tenantId = activity.tenantId() == null ? null : activity.tenantId().toLowerCase(Locale.ROOT);
        if (!TeamsChannelType.isGuid(tenantId) || !TeamsChannelType.acceptedTenants(integration.getPlatformConfig()).contains(tenantId)) {
            LOGGER.warnf("[TEAMS] Activity for integration '%s' from tenant %s, which it does not accept — ignoring",
                    sanitize(integration.getName()), sanitize(String.valueOf(activity.tenantId())));
            metrics.activity(TeamsMetrics.TENANT_REFUSED);
            return;
        }
        if (!credentials.holdsRequiredIdentity(channelType, integration)) {
            LOGGER.warnf("[TEAMS] Integration '%s' names a connection that is not app %s's own (oauth.clientId, tokenUrl and scope "
                    + "must match) — ignoring", sanitize(integration.getName()), sanitize(verifiedAppId));
            metrics.activity(TeamsMetrics.IDENTITY_MISMATCH);
            return;
        }
        if (activity.fromId() != null && activity.fromId().equals(activity.recipientId())) {
            return;
        }
        if (activity.targeted()) {
            // A private message in a channel or group chat. Answering it the normal way
            // would post the reply for everyone; private replies are not built yet.
            LOGGER.debugf("[TEAMS] Ignoring a targeted (private) activity in conversation %s", sanitize(activity.baseConversationId()));
            metrics.activity(TeamsMetrics.TARGETED_IGNORED);
            return;
        }
        if ("conversationUpdate".equals(activity.type()) && botIn(activity, "membersRemoved")) {
            // The bot is gone from this conversation: nothing can be sent there again.
            forgetConversation(verifiedAppId, activity);
            metrics.activity(TeamsMetrics.BOT_REMOVED);
            return;
        }
        rememberConversation(verifiedAppId, tenantId, activity);
        metrics.activity(TeamsMetrics.HANDLED);

        switch (activity.type() == null ? "" : activity.type()) {
            case "message" -> handleMessage(verifiedAppId, tenantId, integration, activity);
            case "conversationUpdate" -> handleConversationUpdate(integration, activity);
            default -> LOGGER.debugf("[TEAMS] Ignoring activity type %s", sanitize(activity.type()));
        }
    }

    /** Whether the bot itself is among the members listed under {@code field}. */
    private static boolean botIn(Activity activity, String field) {
        if (activity.recipientId() == null) {
            return false;
        }
        for (JsonNode member : activity.raw().path(field)) {
            if (activity.recipientId().equals(text(member, "id"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The integration bound to the conversation's channel when it declares the
     * verified app, otherwise the app's channel-less integration.
     */
    Optional<ChannelIntegrationConfiguration> owningIntegration(String appId, String baseConversationId) {
        Optional<ChannelIntegrationConfiguration> bound = router.getIntegration(TeamsChannelType.ID, baseConversationId)
                .filter(cfg -> declaresApp(cfg, appId));
        if (bound.isPresent()) {
            return bound;
        }
        return router.getIntegrationForApplication(TeamsChannelType.ID, appId);
    }

    private boolean declaresApp(ChannelIntegrationConfiguration integration, String appId) {
        return integration != null && channelType.applicationId(integration.getPlatformConfig()).filter(appId::equals).isPresent();
    }

    private void handleConversationUpdate(ChannelIntegrationConfiguration integration, Activity activity) {
        if (botIn(activity, "membersAdded")) {
            reply(integration, activity, WELCOME_TEXT);
        }
    }

    private void handleMessage(String appId, String tenantId, ChannelIntegrationConfiguration integration, Activity activity)
            throws Exception {
        if (!TeamsChannelType.isGuid(activity.fromAadObjectId())) {
            LOGGER.debugf("[TEAMS] Message without an Entra object id — ignoring");
            return;
        }
        String userId = TeamsUserIdentity.eddiUserId(tenantId, activity.fromAadObjectId());
        String text = stripBotMentions(activity.text(), activity.raw(), activity.recipientId());
        if (text.isBlank()) {
            reply(integration, activity, HELP_TEXT);
            return;
        }
        InboundOrigin origin = InboundOrigin.application(TeamsChannelType.ID, appId);
        boolean channelBound = activity.baseConversationId().equals(integration.getPlatformConfig().get(TeamsChannelType.KEY_CONVERSATION_ID));

        ResolvedTarget resolved = null;
        ResolvedTarget locked = router.resolveThreadTarget(TeamsChannelType.ID, activity.baseConversationId(), threadKey(appId, activity));
        if (locked != null) {
            resolved = channelBound ? locked : router.threadCredentialsForDm(locked.target(), origin);
            if (resolved == null || !sameIntegration(resolved.integration(), integration)) {
                LOGGER.warnf("[TEAMS] Conversation %s is locked to a target integration '%s' no longer serves — resolving afresh",
                        sanitize(activity.baseConversationId()), sanitize(integration.getName()));
                resolved = null;
            }
        }
        if (resolved == null) {
            resolved = channelBound
                    ? router.resolveTarget(TeamsChannelType.ID, activity.baseConversationId(), text)
                    : router.resolveDefaultForOrigin(origin, text);
        }
        if (resolved == null) {
            reply(integration, activity, HELP_TEXT);
            return;
        }
        if (!sameIntegration(resolved.integration(), integration)) {
            LOGGER.warnf("[TEAMS] Route for conversation %s belongs to another integration — ignoring",
                    sanitize(activity.baseConversationId()));
            return;
        }
        router.lockThreadTarget(TeamsChannelType.ID, activity.baseConversationId(), threadKey(appId, activity), resolved.target());

        ChannelTarget target = resolved.target();
        switch (target.getType()) {
            case AGENT -> converse(integration, activity, appId, userId, target.getTargetId(),
                    resolved.strippedMessage() != null ? resolved.strippedMessage() : text);
            case GROUP -> reply(integration, activity, GROUP_NOT_SUPPORTED);
            default -> LOGGER.warnf("[TEAMS] Unsupported target type %s", target.getType());
        }
    }

    /**
     * The thread-lock key: the app id with the full conversation id (a channel
     * thread's included). Two bots in one conversation therefore keep separate
     * locks instead of overwriting each other's.
     */
    static String threadKey(String appId, Activity activity) {
        return appId + "|" + activity.conversationId();
    }

    private static boolean sameIntegration(ChannelIntegrationConfiguration a, ChannelIntegrationConfiguration b) {
        return a != null && b != null && a.getName() != null && a.getName().equals(b.getName());
    }

    private void converse(ChannelIntegrationConfiguration integration, Activity activity, String appId, String userId, String agentId,
                          String message)
            throws Exception {
        String intent = "channel:teams:" + appId + ":" + activity.conversationId() + ":" + agentId;
        try {
            ThreadConversation thread = threads.open(agentId, userId, intent);
            try {
                deliver(integration, activity, sendAndWait(thread.conversationId(), message));
            } catch (IConversationService.ConversationEndedException e) {
                ThreadConversation fresh = threads.replaceEnded(agentId, userId, intent, thread.conversationId(),
                        threads.endReasonOf(thread.conversationId()));
                deliver(integration, activity, sendAndWait(fresh.conversationId(), message));
            }
        } catch (IConversationService.ConversationAwaitingApprovalException e) {
            reply(integration, activity, AWAITING_NOTICE);
        } catch (TimeoutException e) {
            reply(integration, activity, "⏳ That took longer than " + requestTimeoutSeconds
                    + " seconds, so I stopped waiting. The agent may still be working — ask again in a moment.");
        } catch (TeamsDeliveryException e) {
            // The answer could not be posted (too large, refused, rate-limited). Try a
            // short notice so the user is not left waiting in silence; it may fail too.
            LOGGER.warnf("[TEAMS] Could not deliver the answer for integration '%s': %s", sanitize(integration.getName()),
                    e.getMessage());
            try {
                reply(integration, activity, DELIVERY_FAILED_TEXT);
            } catch (TeamsDeliveryException again) {
                LOGGER.debugf("[TEAMS] The delivery-failure notice could not be posted either: %s", again.getMessage());
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "[TEAMS] Turn failed for integration '%s'", sanitize(integration.getName()));
            reply(integration, activity, ERROR_TEXT);
        }
    }

    private void deliver(ChannelIntegrationConfiguration integration, Activity activity, SimpleConversationMemorySnapshot snapshot)
            throws IConversationService.ConversationEndedException {
        if (snapshot == SKIPPED_ENDED) {
            throw new IConversationService.ConversationEndedException("Conversation has ended!");
        }
        if (snapshot == SKIPPED_STILL_AWAITING) {
            reply(integration, activity, AWAITING_NOTICE);
            return;
        }
        if (snapshot == SKIPPED_NOT_ACTIVE) {
            reply(integration, activity, SlackHitlSupport.CONVERSATION_NOT_ACTIVE_NOTICE);
            return;
        }
        String response = SlackHitlSupport.extractSlackResponseText(snapshot);
        boolean paused = snapshot != null && snapshot.getConversationState() == ConversationState.AWAITING_HUMAN;
        if (!paused || (response != null && !response.startsWith("_"))) {
            reply(integration, activity, response);
        }
        if (paused) {
            reply(integration, activity, AWAITING_NOTICE);
        }
    }

    private SimpleConversationMemorySnapshot sendAndWait(String conversationId, String message) throws Exception {
        var inputData = new InputData();
        inputData.setInput(message);
        inputData.setContext(Map.of("teams", new Context(Context.ContextType.string, "true")));
        var future = new CompletableFuture<SimpleConversationMemorySnapshot>();
        conversationService.say(conversationId, false, true, Collections.emptyList(), inputData, false,
                new IConversationService.ConversationResponseHandler() {
                    @Override
                    public void onComplete(SimpleConversationMemorySnapshot snapshot) {
                        if (snapshot != null) {
                            future.complete(snapshot);
                        } else {
                            future.completeExceptionally(new IllegalStateException("Agent returned null response"));
                        }
                    }

                    @Override
                    public void onSkipped(SimpleConversationMemorySnapshot snapshot) {
                        ConversationState state = snapshot != null ? snapshot.getConversationState() : null;
                        future.complete(state == ConversationState.AWAITING_HUMAN
                                ? SKIPPED_STILL_AWAITING
                                : state == ConversationState.ENDED ? SKIPPED_ENDED : SKIPPED_NOT_ACTIVE);
                    }
                });
        return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
    }

    private void reply(ChannelIntegrationConfiguration integration, Activity activity, String text) {
        connector.sendText(integration, activity.serviceUrl(), activity.conversationId(), activity.id(), text);
    }

    private void forgetConversation(String appId, Activity activity) {
        if (references == null) {
            return;
        }
        try {
            references.delete(appId, activity.conversationId());
        } catch (RuntimeException e) {
            LOGGER.warnf("[TEAMS] Could not delete the conversation reference: %s", e.getMessage());
        }
    }

    private void rememberConversation(String appId, String tenantId, Activity activity) {
        if (references == null) {
            return;
        }
        boolean personal = "personal".equals(activity.conversationType());
        String userId = personal && TeamsChannelType.isGuid(activity.fromAadObjectId())
                ? TeamsUserIdentity.eddiUserId(tenantId, activity.fromAadObjectId())
                : null;
        try {
            references.upsert(new TeamsConversationReference(appId, activity.conversationId(), activity.conversationType(),
                    activity.serviceUrl(), tenantId, activity.recipientId(), userId, Instant.now()));
        } catch (RuntimeException e) {
            LOGGER.warnf("[TEAMS] Could not store the conversation reference: %s", e.getMessage());
        }
    }

    /**
     * Removes the {@code <at>…</at>} mentions of the bot itself (the entities of
     * type {@code mention} whose {@code mentioned.id} is the recipient), then
     * trims. Mentions of other people stay in the text.
     */
    static String stripBotMentions(String text, JsonNode activity, String recipientId) {
        if (text == null) {
            return "";
        }
        String result = text;
        for (JsonNode entity : activity.path("entities")) {
            if (!"mention".equals(text(entity, "type")) || recipientId == null) {
                continue;
            }
            if (!recipientId.equals(text(entity.path("mentioned"), "id"))) {
                continue;
            }
            String mentionText = text(entity, "text");
            if (mentionText != null && !mentionText.isEmpty()) {
                result = result.replace(mentionText, "");
            }
        }
        return result.replace("&nbsp;", " ").trim();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
