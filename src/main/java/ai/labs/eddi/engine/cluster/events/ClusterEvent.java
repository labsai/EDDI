/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.events;

import java.util.Map;

/**
 * The envelope of a cluster event: {@code {v, id, type, originNode, originBoot,
 * ts, traceparent, payload}}.
 * <p>
 * Every event is an instruction to <em>evict</em> or <em>reconcile</em> — never
 * to apply a change carried in the payload — so handling one twice, late, or
 * out of order is harmless, and a node that missed events recovers with a full
 * local flush.
 */
public record ClusterEvent(int v, String id, String type, String originNode, String originBoot, long ts, String traceparent,
        Map<String, Object> payload) {

    public static final int VERSION = 1;

    /** Secret rotated: {@code {tenantId, keyName}} or {@code {all: true}}. */
    public static final String SECRET_CHANGED = "secret.changed";
    /** Global variables changed: {@code {}}. */
    public static final String GLOBALVARS_CHANGED = "globalvars.changed";
    /** Deployment record changed: {@code {env, agentId, version, status}}. */
    public static final String DEPLOYMENT_CHANGED = "deployment.changed";
    /** A versioned configuration was deleted: {@code {type, id, version}}. */
    public static final String CONFIG_DELETED = "config.deleted";
    /** Agent trigger changed: {@code {intent}}. */
    public static final String TRIGGER_CHANGED = "trigger.changed";
    /** User-conversation mapping changed: {@code {intent, userIdHash}}. */
    public static final String USERCONVERSATION_CHANGED = "userconversation.changed";
    /** Conversation state changed: {@code {conversationId}}. */
    public static final String CONVERSATION_STATE = "conversation.state";
    /** GDPR processing restriction changed: {@code {userIdHash}}. */
    public static final String GDPR_RESTRICTION = "gdpr.restriction";
    /** A user was erased: {@code {userIdHash}}. */
    public static final String GDPR_USER_ERASED = "gdpr.user-erased";
    /** A connection changed: {@code {tenantId, name}}. */
    public static final String CONNECTION_CHANGED = "connection.changed";
    /** Flush every invalidatable cache: {@code {}}. */
    public static final String RESYNC_ALL = "cache.resync-all";

    public Object get(String key) {
        return payload == null ? null : payload.get(key);
    }

    public String getString(String key) {
        Object value = get(key);
        return value == null ? null : value.toString();
    }
}
