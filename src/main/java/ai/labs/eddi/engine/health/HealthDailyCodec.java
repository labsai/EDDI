/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthTurnAggregate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The counters of a daily row as a plain map — the payload both daily stores
 * keep next to the row's key columns (a MongoDB sub-document, a PostgreSQL
 * {@code JSONB}). One codec, so the two stores read and write identical fields,
 * and a counter added later is read as zero from older rows.
 */
public final class HealthDailyCodec {

    private HealthDailyCodec() {
    }

    /**
     * The counters of {@code a}; the key (agent, version, origin, day) is not
     * included.
     */
    public static Map<String, Object> counters(HealthTurnAggregate a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("turns", a.turns());
        m.put("conversations", a.conversations());
        m.put("userInputTurns", a.userInputTurns());
        m.put("failedTurns", a.failedTurns());
        m.put("errorKinds", new TreeMap<>(a.errorKinds()));
        m.put("pausedTurns", a.pausedTurns());
        m.put("fallbackTurns", a.fallbackTurns());
        m.put("failuresByClass", new TreeMap<>(a.failuresByClass()));
        m.put("recoveredFailures", a.recoveredFailures());
        m.put("costUsd", a.costUsd());
        m.put("totalTokens", a.totalTokens());
        m.put("durationMsTotal", a.durationMsTotal());
        m.put("durationCount", a.durationCount());
        m.put("toolCalls", a.toolCalls());
        m.put("toolFailures", a.toolFailures());
        m.put("toolRefusals", a.toolRefusals());
        m.put("guardrailTrips", a.guardrailTrips());
        m.put("agentFailedTurns", a.agentFailedTurns());
        m.put("platformFailedTurns", a.platformFailedTurns());
        m.put("platformAffectedTurns", a.platformAffectedTurns());
        m.put("tokenTurns", a.tokenTurns());
        m.put("durationBuckets", new ArrayList<>(a.durationBuckets()));
        m.put("apdexTMs", a.apdexTMs());
        m.put("apdexSatisfied", a.apdexSatisfied());
        m.put("apdexTolerating", a.apdexTolerating());
        return m;
    }

    /** A daily row from its key and stored counters. */
    public static HealthTurnAggregate fromCounters(String agentId, Integer agentVersion, String origin, LocalDate day, Map<?, ?> m) {
        Map<?, ?> c = m == null ? Map.of() : m;
        return new HealthTurnAggregate(agentId, agentVersion, origin, day, l(c, "turns"), l(c, "conversations"), l(c, "userInputTurns"),
                l(c, "failedTurns"), counts(c.get("errorKinds")), l(c, "pausedTurns"), l(c, "fallbackTurns"), counts(c.get("failuresByClass")),
                l(c, "recoveredFailures"), d(c, "costUsd"), l(c, "totalTokens"), l(c, "durationMsTotal"), l(c, "durationCount"), l(c, "toolCalls"),
                l(c, "toolFailures"), l(c, "toolRefusals"), l(c, "guardrailTrips"), l(c, "agentFailedTurns"), l(c, "platformFailedTurns"),
                l(c, "platformAffectedTurns"), l(c, "tokenTurns"), buckets(c.get("durationBuckets")), l(c, "apdexTMs"), l(c, "apdexSatisfied"),
                l(c, "apdexTolerating"));
    }

    private static long l(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    private static double d(Map<?, ?> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static Map<String, Long> counts(Object value) {
        Map<String, Long> out = new TreeMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v instanceof Number n) {
                    out.put(String.valueOf(k), n.longValue());
                }
            });
        }
        return out;
    }

    private static List<Long> buckets(Object value) {
        List<Long> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                out.add(entry instanceof Number n ? n.longValue() : 0L);
            }
        }
        return out;
    }
}
