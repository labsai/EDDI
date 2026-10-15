/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one mapping between a runtime value and the typed slot of a
 * {@link Property} that holds it — in both directions.
 * <p>
 * Every writer of a property used to carry its own {@code instanceof} ladder,
 * and they disagreed: the HTTP-call/MCP/LLM {@code postResponse} path stored
 * {@code ""} for any number, boolean, object or list it read, the property
 * setter silently dropped a {@code Double} or a {@code Long} (the types Jackson
 * gives a JSON decimal or a large whole number), and a {@code longTerm}
 * {@code Double} reloaded from the user-memory store came back as a string.
 * They all go through here now.
 */
public final class PropertyValues {

    private PropertyValues() {
    }

    /**
     * Builds a property holding {@code value} in the slot matching its type.
     * <p>
     * Numbers keep their precision: {@code Integer}, {@code Short} and {@code Byte}
     * go to {@code valueInt}, {@code Long} to {@code valueInt} when it fits and
     * {@code valueLong} otherwise, {@code Float} to {@code valueFloat},
     * {@code Double} and {@code BigDecimal} to {@code valueDouble}, a
     * {@code BigInteger} to the narrowest of the two integral slots that holds it.
     * Maps and lists are copied, so the property never aliases the response
     * document it was read from.
     *
     * @return the property, or {@code null} when {@code value} is {@code null} or
     *         of a type no property slot can hold (a {@code BigInteger} beyond the
     *         {@code long} range, or any non-JSON type) — the caller decides how to
     *         report that
     */
    public static Property toProperty(String name, Object value, Property.Scope scope) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return new Property(name, s, scope);
        }
        if (value instanceof Boolean b) {
            return new Property(name, b, scope);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
            return new Property(name, copy, scope);
        }
        if (value instanceof List<?> list) {
            return new Property(name, new ArrayList<Object>(list), scope);
        }
        if (value instanceof Integer i) {
            return new Property(name, i, scope);
        }
        if (value instanceof Short || value instanceof Byte) {
            return new Property(name, Integer.valueOf(((Number) value).intValue()), scope);
        }
        if (value instanceof Long l) {
            return fitsInt(l) ? new Property(name, Integer.valueOf(l.intValue()), scope) : new Property(name, l, scope);
        }
        if (value instanceof BigInteger big) {
            if (big.bitLength() < 32) {
                return new Property(name, Integer.valueOf(big.intValue()), scope);
            }
            return big.bitLength() < 64 ? new Property(name, Long.valueOf(big.longValue()), scope) : null;
        }
        if (value instanceof Float f) {
            return new Property(name, f, scope);
        }
        if (value instanceof Double d) {
            return new Property(name, d, scope);
        }
        if (value instanceof BigDecimal decimal) {
            return new Property(name, Double.valueOf(decimal.doubleValue()), scope);
        }
        return null;
    }

    /**
     * The single value a property carries, or {@code null} when it carries none.
     * The order is the historical precedence of the readers this replaces.
     */
    public static Object valueOf(Property property) {
        if (property == null) {
            return null;
        }
        if (property.getValueString() != null) {
            return property.getValueString();
        }
        if (property.getValueObject() != null) {
            return property.getValueObject();
        }
        if (property.getValueList() != null) {
            return property.getValueList();
        }
        if (property.getValueInt() != null) {
            return property.getValueInt();
        }
        if (property.getValueLong() != null) {
            return property.getValueLong();
        }
        if (property.getValueFloat() != null) {
            return property.getValueFloat();
        }
        if (property.getValueDouble() != null) {
            return property.getValueDouble();
        }
        return property.getValueBoolean();
    }

    /**
     * An independent copy of {@code property}: the same value, scope, visibility
     * and auto-vault marker, with the map or list copied one level deep.
     */
    public static Property copyOf(Property property) {
        var copy = new Property(property.getName(), property.getValueString(),
                property.getValueObject() != null ? new LinkedHashMap<>(property.getValueObject()) : null,
                property.getValueList() != null ? new ArrayList<>(property.getValueList()) : null, property.getValueInt(),
                property.getValueFloat(), property.getValueBoolean(), property.getScope(), property.getVisibility());
        copy.setValueLong(property.getValueLong());
        copy.setValueDouble(property.getValueDouble());
        copy.setAutoVaulted(property.getAutoVaulted());
        return copy;
    }

    /** Whether the property sets any value slot other than {@code valueString}. */
    public static boolean hasTypedValue(Property property) {
        return property.getValueObject() != null || property.getValueList() != null || property.getValueInt() != null
                || property.getValueLong() != null || property.getValueFloat() != null || property.getValueDouble() != null
                || property.getValueBoolean() != null;
    }

    private static boolean fitsInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }
}
