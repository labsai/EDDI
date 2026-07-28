/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.*;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.InputData;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Handles incoming A2A JSON-RPC requests by bridging them to EDDI's
 * {@link IConversationService}. Each A2A task maps to a conversation.
 *
 * <p>
 * <strong>Peer scoping.</strong> {@code taskId} and {@code contextId} are
 * chosen by the calling peer, so they are request payload — never proof of
 * ownership. Both caches are therefore keyed on the authenticated peer
 * <em>plus</em> the supplied id, and every conversation this handler creates is
 * stamped with that same principal as its owner. A peer can only ever resolve
 * tasks and contexts it created itself, and the resulting conversation is
 * reachable through the regular ownership model
 * ({@link ai.labs.eddi.engine.security.OwnershipValidator}) rather than being
 * owned by nobody.
 * </p>
 *
 * @author ginccc
 */
@ApplicationScoped
public class A2ATaskHandler {

    private static final Logger LOGGER = Logger.getLogger(A2ATaskHandler.class);
    private static final String CACHE_NAME = "a2aTaskMapping";
    private static final int TASK_TIMEOUT_SECONDS = 60;

    /**
     * Owner recorded for peers that arrive without an authenticated identity — the
     * case when {@code authorization.enabled=false}, where the JSON-RPC endpoint's
     * {@code @Authenticated} gate is a no-op and there is a single trust domain
     * anyway. Deliberately a value no OIDC principal can ever equal: switching
     * authorization on later must not hand these conversations to a real user.
     */
    static final String ANONYMOUS_PEER = "a2a:anonymous";

    private final IConversationService conversationService;

    /**
     * The calling peer's identity. The JSON-RPC endpoint is {@code @Authenticated},
     * so for a remote agent this is the principal of the Bearer token it presented
     * (typically the OIDC subject / client id of the peer agent) — the only
     * caller-independent identity available on this surface.
     */
    private final SecurityIdentity identity;

    /**
     * Maps (peer, A2A taskId) → conversationId for multi-turn conversations. A task
     * that uses the same contextId should reuse the same conversation.
     */
    private final ICache<String, String> taskConversationCache;

    /**
     * Maps (peer, A2A contextId) → conversationId so multi-turn requests on the
     * same context reuse the same conversation.
     */
    private final ICache<String, String> contextConversationCache;

    @Inject
    public A2ATaskHandler(IConversationService conversationService, ICacheFactory cacheFactory, SecurityIdentity identity) {
        this.conversationService = conversationService;
        this.taskConversationCache = cacheFactory.getCache(CACHE_NAME);
        this.contextConversationCache = cacheFactory.getCache(CACHE_NAME + ":context");
        this.identity = identity;
    }

    /**
     * Handle a {@code tasks/send} request — the core A2A operation.
     */
    public A2ATask handleTaskSend(String agentId, Map<String, Object> params) throws Exception {
        // Extract message from params
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) params.get("message");
        if (message == null) {
            throw new InvalidA2ARequestException("Missing 'message' in params");
        }

        String taskId = params.containsKey("id") ? params.get("id").toString() : UUID.randomUUID().toString();
        String contextId = params.containsKey("contextId") ? params.get("contextId").toString() : null;

        // Extract text from message parts
        String userInput = extractTextFromMessage(message);
        if (userInput == null || userInput.isBlank()) {
            throw new InvalidA2ARequestException("No text content found in message parts");
        }

        // Resolve or create conversation — scoped to the calling peer
        String conversationId = resolveConversation(agentId, taskId, contextId, callerPrincipal());

        // Build InputData
        InputData inputData = new InputData();
        inputData.setInput(userInput);

        // Execute synchronously via ConversationService
        CompletableFuture<String> responseFuture = new CompletableFuture<>();

        conversationService.say(Environment.production, agentId, conversationId, false, true, null, inputData, false, snapshot -> {
            String response = "";
            if (snapshot != null && snapshot.getConversationOutputs() != null && !snapshot.getConversationOutputs().isEmpty()) {
                var outputs = snapshot.getConversationOutputs();
                var lastOutput = outputs.get(outputs.size() - 1);
                response = lastOutput != null ? lastOutput.toString() : "";
            }
            responseFuture.complete(response);
        });

        String response = responseFuture.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // Build A2A response
        List<Part> responseParts = List.of(Part.textPart(response));
        A2AMessage responseMessage = new A2AMessage("agent", responseParts, null);
        Artifact artifact = new Artifact("response", null, responseParts, 0, null);

        return new A2ATask(taskId, contextId, TaskState.completed,
                List.of(new A2AMessage("user", List.of(Part.textPart(userInput)), null), responseMessage), List.of(artifact), null);
    }

    /**
     * Handle a {@code tasks/get} request — retrieve task status.
     * <p>
     * Only tasks created by the <em>calling</em> peer resolve: a taskId belonging
     * to another peer is indistinguishable from an unknown one.
     */
    public A2ATask handleTaskGet(String taskId) {
        String conversationId = taskConversationCache.get(scopedKey(callerPrincipal(), taskId));
        if (conversationId == null) {
            return null; // Task not found (or not this peer's task)
        }

        try {
            var state = conversationService.getConversationState(conversationId);
            TaskState taskState = switch (state) {
                case READY -> TaskState.submitted;
                case IN_PROGRESS -> TaskState.working;
                case ENDED -> TaskState.completed;
                case ERROR -> TaskState.failed;
                default -> TaskState.unknown;
            };

            return new A2ATask(taskId, null, taskState, null, null, null);
        } catch (Exception e) {
            LOGGER.warnf("Failed to get task state for taskId=%s: %s", sanitize(taskId), e.getMessage());
            return new A2ATask(taskId, null, TaskState.unknown, null, null, null);
        }
    }

    /**
     * Handle a {@code tasks/cancel} request — end the conversation.
     * <p>
     * Cancelling is scoped the same way {@link #handleTaskGet} is: a peer can only
     * cancel a task it created itself.
     */
    public boolean handleTaskCancel(String taskId) {
        String conversationId = taskConversationCache.get(scopedKey(callerPrincipal(), taskId));
        if (conversationId == null) {
            return false;
        }

        try {
            conversationService.endConversation(conversationId);
            return true;
        } catch (Exception e) {
            LOGGER.warnf("Failed to cancel task %s: %s", sanitize(taskId), e.getMessage());
            return false;
        }
    }

    // === Internal helpers ===

    /**
     * The identity a task/context is filed under. The JSON-RPC surface is
     * authenticated, so a remote peer always has a principal; the anonymous
     * fallback only applies with authorization disabled.
     */
    private String callerPrincipal() {
        if (identity != null && !identity.isAnonymous() && identity.getPrincipal() != null) {
            String name = identity.getPrincipal().getName();
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return ANONYMOUS_PEER;
    }

    /**
     * Compound cache key binding a caller-supplied id to the peer that supplied it.
     * The principal is length-prefixed so the encoding stays injective even if a
     * principal or an id contains the separator — without that, a peer could craft
     * an id that collides with another peer's key.
     */
    static String scopedKey(String principal, String id) {
        return principal.length() + ":" + principal + "|" + id;
    }

    private String resolveConversation(String agentId, String taskId, String contextId, String principal) throws Exception {
        // If contextId is provided, try to reuse an existing conversation — but only
        // one this same peer opened. Another peer's contextId simply does not resolve.
        if (contextId != null) {
            String existingConvId = contextConversationCache.get(scopedKey(principal, contextId));
            if (existingConvId != null) {
                taskConversationCache.put(scopedKey(principal, taskId), existingConvId);
                return existingConvId;
            }
        }

        // Start a new conversation owned by the calling peer. Passing null here would
        // let ConversationService substitute a random anonymous-* id, leaving the
        // conversation owned by a principal that can never authenticate.
        var result = conversationService.startConversation(Environment.production, agentId, principal, Map.of());
        String conversationId = result.conversationId();

        // Cache the mapping
        taskConversationCache.put(scopedKey(principal, taskId), conversationId);
        if (contextId != null) {
            contextConversationCache.put(scopedKey(principal, contextId), conversationId);
        }

        return conversationId;
    }

    private String extractTextFromMessage(Map<String, Object> message) {
        Object partsObj = message.get("parts");
        if (partsObj instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    String type = (String) partMap.get("type");
                    if ("text".equals(type) || type == null) {
                        Object text = partMap.get("text");
                        if (text != null) {
                            return text.toString();
                        }
                    }
                }
            }
        }
        return null;
    }
}
