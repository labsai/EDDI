/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The trust boundary for conversation context supplied by an external client.
 * <p>
 * A handful of context keys are not ordinary template variables: EDDI itself
 * writes them when it drives a conversation on its own behalf (a group member
 * turn, a delegated sub-agent conversation), and the engine then reads them to
 * decide what a turn may do — which group's shared memories are visible, which
 * dynamic-agent policy governs the turn, which agents count as created by it,
 * how deep a delegation chain already is. A value for one of those keys that
 * arrives from a client would be read with the same authority, so it is removed
 * at every entry point where context crosses from a client into the engine.
 * Internal callers (the group orchestrator, the delegation tool, schedules)
 * reach {@code IConversationService} directly and are unaffected.
 * <p>
 * An operator with a legitimate reason to let clients set one of these keys can
 * list it in {@code eddi.conversation.client-context.permitted-reserved-keys};
 * the default permits none. See {@code docs/passing-context-information.md}.
 */
@Singleton
public class ClientContextGuard {

    private static final Logger LOGGER = Logger.getLogger(ClientContextGuard.class);

    /** Written by the group orchestrator; selects group-visible user memories. */
    public static final String KEY_GROUP_ID = "groupId";
    /** Written by the group orchestrator; identifies the live discussion. */
    public static final String KEY_GROUP_CONVERSATION_ID = "groupConversationId";
    /** Written by the group orchestrator; nesting depth of the discussion. */
    public static final String KEY_GROUP_DEPTH = "groupDepth";
    /** Written by the group orchestrator; the discussion transcript. */
    public static final String KEY_GROUP_TRANSCRIPT = "groupTranscript";
    /** Written by the group orchestrator; the group's dynamic-agent policy. */
    public static final String KEY_DYNAMIC_AGENT_CONFIG = "dynamicAgentConfig";
    /** Written by the group orchestrator; agents the discussion has created. */
    public static final String KEY_DYNAMIC_CREATED_AGENT_IDS = "dynamicCreatedAgentIds";
    /** Written by the delegation tool; hop count of the delegation chain. */
    public static final String KEY_DELEGATION_DEPTH = "delegationDepth";

    /**
     * Every context key only EDDI itself may set. Exact, case-sensitive names — the
     * same way every consumer looks them up.
     */
    public static final Set<String> RESERVED_KEYS = Set.of(KEY_GROUP_ID, KEY_GROUP_CONVERSATION_ID, KEY_GROUP_DEPTH,
            KEY_GROUP_TRANSCRIPT, KEY_DYNAMIC_AGENT_CONFIG, KEY_DYNAMIC_CREATED_AGENT_IDS, KEY_DELEGATION_DEPTH);

    private final Set<String> permittedReservedKeys;

    @Inject
    public ClientContextGuard(
            @ConfigProperty(name = "eddi.conversation.client-context.permitted-reserved-keys") Optional<List<String>> permitted) {
        this.permittedReservedKeys = permitted.orElse(List.of()).stream()
                .map(String::trim)
                .filter(RESERVED_KEYS::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (!permittedReservedKeys.isEmpty()) {
            LOGGER.warnf("Clients may set the engine-reserved context key(s) %s "
                    + "(eddi.conversation.client-context.permitted-reserved-keys). Values for these keys are trusted by "
                    + "the engine — only permit them when every caller is trusted.", permittedReservedKeys);
        }
    }

    private static final ClientContextGuard STRICT = new ClientContextGuard(Optional.empty());

    /**
     * The shipped default: no reserved key may come from a client. Used where the
     * bean is not injected (directly constructed classes in unit tests).
     */
    public static ClientContextGuard strict() {
        return STRICT;
    }

    /**
     * The client's context without the reserved keys. Returns the argument itself
     * when there is nothing to remove (including {@code null}), otherwise a new
     * mutable map in the original order.
     */
    public Map<String, Context> strip(Map<String, Context> context) {
        if (context == null || context.isEmpty()) {
            return context;
        }
        boolean anyReserved = false;
        for (String key : context.keySet()) {
            if (isRejected(key)) {
                anyReserved = true;
                break;
            }
        }
        if (!anyReserved) {
            return context;
        }
        Map<String, Context> filtered = new LinkedHashMap<>();
        for (var entry : context.entrySet()) {
            if (isRejected(entry.getKey())) {
                LOGGER.debugf("Dropped client-supplied context key '%s': it is reserved for values EDDI sets itself",
                        sanitize(entry.getKey()));
                continue;
            }
            filtered.put(entry.getKey(), entry.getValue());
        }
        return filtered;
    }

    /**
     * Strips the reserved keys from {@code inputData}'s context in place and
     * returns the same instance, for call-site chaining.
     */
    public InputData strip(InputData inputData) {
        if (inputData != null) {
            var context = inputData.getContext();
            var stripped = strip(context);
            if (stripped != context) {
                inputData.setContext(stripped);
            }
        }
        return inputData;
    }

    private boolean isRejected(String key) {
        return key != null && RESERVED_KEYS.contains(key) && !permittedReservedKeys.contains(key);
    }
}
