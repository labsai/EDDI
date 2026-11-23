/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.integrations.email.EmailTargetRouting.RouteDecision;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which other agents an email turn may consult (email v2 plan §10, decision D3:
 * consult, never switch owner).
 * <p>
 * The inbound processor decides it once, before the turn, and writes the answer
 * into the turn's {@code mail} context as {@code consultRequested}; the
 * delegation tool of that turn reads it back and talks to no other agent. So
 * the model cannot widen the set — a mail that says "ask @hr" when HR is not
 * consultable gets no HR, whatever the agent then tries — and every rule lives
 * here:
 * <ul>
 * <li>the sender is authenticated;</li>
 * <li>the mail named the target explicitly — by address, plus-tag or mention,
 * never by a subject word or by default;</li>
 * <li>a target of the agent the mail runs with, which admits this sender, lists
 * it in {@code consultable};</li>
 * <li>the named target admits the sender itself ({@code allowedSenders}, v2
 * §7.6: "by any path" — a consultation is a path);</li>
 * <li>it is an addressable {@code AGENT} target of a different agent.</li>
 * </ul>
 */
public final class EmailConsults {

    /** Key of the consultations in the turn's {@code mail} context. */
    public static final String CONSULT_REQUESTED_KEY = "consultRequested";

    /** Step-data key of the {@code mail} context. */
    static final String MAIL_CONTEXT_DATA_KEY = "context:" + EmailInboundProcessor.MAIL_CONTEXT_KEY;

    private EmailConsults() {
    }

    /**
     * The targets the mail asks the agent {@code runAgentId} to consult, in the
     * order the mail named them, one per agent.
     */
    public static List<ChannelTarget> requested(ChannelIntegrationConfiguration integration, String runAgentId, RouteDecision route,
                                                String sender, boolean authenticated) {
        List<ChannelTarget> consults = new ArrayList<>();
        if (!authenticated || runAgentId == null || integration == null || integration.getTargets() == null || route == null) {
            return consults;
        }
        Set<String> consultable = consultableBy(integration, runAgentId, sender);
        if (consultable.isEmpty()) {
            return consults;
        }
        List<String> named = new ArrayList<>();
        if (route.target() != null && route.source() != null && route.source().explicit()) {
            named.add(route.target().getName());
        }
        named.addAll(route.alsoAddressed());
        Set<String> agents = new LinkedHashSet<>();
        for (String name : named) {
            ChannelTarget candidate = byName(integration, name);
            if (candidate == null || candidate.isObserveMode() || candidate.getType() != ChannelTarget.TargetType.AGENT
                    || candidate.getTargetId() == null || candidate.getTargetId().equals(runAgentId)) {
                continue;
            }
            if (!consultable.contains(candidate.getName().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!EmailInboundProcessor.mayReach(candidate, sender, true)) {
                continue;
            }
            if (agents.add(candidate.getTargetId())) {
                consults.add(candidate);
            }
        }
        return consults;
    }

    /**
     * Lower-cased names in the {@code consultable} lists of the agent's targets
     * that admit {@code sender} — the same "any of the agent's targets" reading the
     * processor applies to a continued conversation, whose starting target is not
     * recorded. The union is no wider than what the sender could reach directly:
     * each contributing target admits them, and each consulted target must admit
     * them itself as well.
     * <p>
     * {@code honorReplyTo} changes none of this: who may consult is decided by the
     * authenticated {@code From}, and the consulted agent's answer reaches the
     * {@code Reply-To} only inside the consulting agent's own reply, which
     * {@code honorReplyTo} already sends there.
     */
    static Set<String> consultableBy(ChannelIntegrationConfiguration integration, String runAgentId, String sender) {
        Set<String> names = new LinkedHashSet<>();
        for (ChannelTarget target : integration.getTargets()) {
            if (target == null || target.isObserveMode() || target.getType() != ChannelTarget.TargetType.AGENT
                    || !runAgentId.equals(target.getTargetId()) || target.getConsultable() == null) {
                continue;
            }
            if (!EmailInboundProcessor.mayReach(target, sender, true)) {
                continue;
            }
            for (String name : target.getConsultable()) {
                if (name != null) {
                    names.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }

    private static ChannelTarget byName(ChannelIntegrationConfiguration integration, String name) {
        if (name == null) {
            return null;
        }
        for (ChannelTarget target : integration.getTargets()) {
            if (target != null && name.equalsIgnoreCase(target.getName())) {
                return target;
            }
        }
        return null;
    }

    /** {@code mail.consultRequested}: {@code [{target, agentId}]}. */
    static List<Map<String, Object>> toMaps(Collection<ChannelTarget> consults) {
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ChannelTarget target : consults) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("target", target.getName());
            entry.put("agentId", target.getTargetId());
            maps.add(entry);
        }
        return maps;
    }

    /**
     * The agent ids the current step's {@code mail} context allows this turn to
     * consult. Read with an exact key from the current step only — the turn a mail
     * started, which a HITL resume continues — and empty whenever anything about it
     * is not the expected shape: no step, no mail context, a value that is neither
     * the live map nor its stored form. Never null.
     */
    public static Set<String> requestedAgentIds(IConversationMemory memory) {
        Set<String> ids = new LinkedHashSet<>();
        var step = memory == null ? null : memory.getCurrentStep();
        if (step == null) {
            return ids;
        }
        IData<Object> data = step.getData(MAIL_CONTEXT_DATA_KEY);
        Object value = data == null
                ? null
                : data.getResult() instanceof Context context
                        ? context.getValue()
                        // The stored form: a context read back from the conversation store as
                        // its JSON shape, {type, value}.
                        : data.getResult() instanceof Map<?, ?> stored ? stored.get("value") : null;
        if (!(value instanceof Map<?, ?> mail)) {
            return ids;
        }
        if (!(mail.get(CONSULT_REQUESTED_KEY) instanceof Collection<?> requested)) {
            return ids;
        }
        for (Object entry : requested) {
            if (entry instanceof Map<?, ?> map && map.get("agentId") instanceof String agentId && !agentId.isBlank()) {
                ids.add(agentId);
            }
        }
        return ids;
    }
}
