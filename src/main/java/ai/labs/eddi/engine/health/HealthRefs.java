/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.IConversationMemory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The business references of Agent Health (plan §5, H5): how a reference value
 * is read from a turn, and the one stored form every row, event and match uses.
 * <p>
 * A reference is stored as {@code name=value} (plain, the default) or
 * {@code name#<hex>} (with {@code healthConfig.hashRefs}: the salted
 * HMAC-SHA256 of {@link HealthRefHasher}). The separators cannot occur in a
 * reference name ({@link HealthConfigValidator#REF_NAME}), so the name is
 * always recoverable and a plain and a hashed form never collide. Matching
 * compares stored forms for equality — nothing else is ever done with a value.
 */
public final class HealthRefs {

    /** Longer values are not recorded (and not matched): they are not ids. */
    public static final int MAX_VALUE_LENGTH = 128;
    /** At most this many references are kept on one turn row. */
    public static final int MAX_REFS_PER_TURN = HealthConfigValidator.MAX_EXTERNAL_REFS;
    /** At most this many references are kept on one session row. */
    public static final int MAX_SESSION_REFS = 50;

    static final char PLAIN = '=';
    static final char HASHED = '#';

    private HealthRefs() {
    }

    /** The stored form of a plain reference. */
    public static String plain(String name, String value) {
        return name + PLAIN + value;
    }

    /** The stored form of a hashed reference. */
    public static String hashed(String name, String hex) {
        return name + HASHED + hex;
    }

    /** Whether the stored form is a hash. */
    public static boolean isHashed(String stored) {
        int plain = stored == null ? -1 : stored.indexOf(PLAIN);
        int hashed = stored == null ? -1 : stored.indexOf(HASHED);
        return hashed >= 0 && (plain < 0 || hashed < plain);
    }

    /** The reference name of a stored form, or {@code null}. */
    public static String nameOf(String stored) {
        if (stored == null) {
            return null;
        }
        for (int i = 0; i < stored.length(); i++) {
            char c = stored.charAt(i);
            if (c == PLAIN || c == HASHED) {
                return stored.substring(0, i);
            }
        }
        return null;
    }

    /** The value (or hash) part of a stored form, or {@code null}. */
    public static String valueOf(String stored) {
        String name = nameOf(stored);
        return name == null ? null : stored.substring(name.length() + 1);
    }

    /**
     * Whether a value may be recorded as a reference: not blank, at most
     * {@link #MAX_VALUE_LENGTH} characters, no control characters.
     */
    public static boolean recordable(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The raw values of the named references a turn ended with — read on the turn's
     * thread from the conversation properties. A property that is a secret
     * ({@code scope: secret}, or vaulted), not a scalar, or not {@link #recordable}
     * is left out. At most {@link #MAX_REFS_PER_TURN}.
     */
    public static Map<String, String> rawValues(IConversationMemory memory, Collection<String> names) {
        if (memory == null || names == null || names.isEmpty()) {
            return Map.of();
        }
        IConversationMemory.IConversationProperties properties = memory.getConversationProperties();
        if (properties == null || properties.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : names) {
            if (values.size() >= MAX_REFS_PER_TURN) {
                break;
            }
            Property property = name == null ? null : properties.get(name);
            String value = scalar(property);
            if (value != null && recordable(value)) {
                values.put(name, value);
            }
        }
        return values;
    }

    /** A property's scalar value as text, or {@code null} (and never a secret). */
    static String scalar(Property property) {
        if (property == null || property.getScope() == Property.Scope.secret || Boolean.TRUE.equals(property.getAutoVaulted())) {
            return null;
        }
        if (property.getValueString() != null) {
            return property.getValueString();
        }
        if (property.getValueInt() != null) {
            return String.valueOf(property.getValueInt());
        }
        if (property.getValueLong() != null) {
            return String.valueOf(property.getValueLong());
        }
        if (property.getValueBoolean() != null) {
            return null;
        }
        if (property.getValueDouble() != null) {
            double d = property.getValueDouble();
            return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (property.getValueFloat() != null) {
            float f = property.getValueFloat();
            return f == Math.rint(f) && !Float.isInfinite(f) ? String.valueOf((long) f) : String.valueOf(f);
        }
        return null;
    }

    /**
     * Raw values in their stored form: plain, or hashed when {@code hash} — and
     * then left out entirely when no hash can be made (no salt): a reference meant
     * to be hashed is never stored in plain.
     */
    public static List<String> stored(Map<String, String> raw, boolean hash, HealthRefHasher hasher) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            if (hash) {
                String hex = hasher == null ? null : hasher.hash(entry.getKey(), entry.getValue());
                if (hex != null) {
                    out.add(hashed(entry.getKey(), hex));
                }
            } else {
                out.add(plain(entry.getKey(), entry.getValue()));
            }
        }
        return out;
    }

    /**
     * Every stored form a raw reference can have been recorded in — plain, and
     * hashed when a hash can be made — so an event matches turns recorded before
     * and after the agent switched {@code hashRefs} on.
     */
    public static Set<String> matchKeys(String name, String value, HealthRefHasher hasher) {
        Set<String> keys = new LinkedHashSet<>();
        if (name == null || value == null) {
            return keys;
        }
        keys.add(plain(name, value));
        String hex = hasher == null ? null : hasher.hash(name, value);
        if (hex != null) {
            keys.add(hashed(name, hex));
        }
        return keys;
    }

    /**
     * The union of the references of several rows, in first-seen order, at most
     * {@link #MAX_SESSION_REFS}.
     */
    public static List<String> union(Iterable<List<String>> refs) {
        Set<String> all = new LinkedHashSet<>();
        for (List<String> list : refs) {
            if (list == null) {
                continue;
            }
            for (String ref : list) {
                if (all.size() >= MAX_SESSION_REFS) {
                    return List.copyOf(all);
                }
                if (ref != null) {
                    all.add(ref);
                }
            }
        }
        return List.copyOf(all);
    }
}
