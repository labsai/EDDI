/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.patch;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A patch is applied to the stored model as the REST mapper serialises it and
 * the result is parsed back into the model. Anything that does not survive that
 * round trip — a field the mapper cannot see, a getter that substitutes a
 * default, a null that turns into a value — would be silently changed in the
 * NEW VERSION by an edit that never mentioned it.
 * <p>
 * So for each patchable store: build a document with <b>every field set</b>
 * (reflectively, including nested objects, lists, maps, a vault reference and a
 * {@code toolApprovals}), apply a patch that changes nothing, and require the
 * result to equal the input field by field — compared through the fields
 * themselves, not through the mapper, so a field the mapper drops is a
 * difference rather than invisible on both sides.
 */
@DisplayName("ConfigJsonPatcher round trip — a no-op patch changes nothing")
class ConfigPatchRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ConfigJsonPatcher patcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));

    @Test
    @DisplayName("llm")
    void llm() throws Exception {
        assertSurvives(LlmConfiguration.class);
    }

    @Test
    @DisplayName("apicalls")
    void apicalls() throws Exception {
        assertSurvives(ApiCallsConfiguration.class);
    }

    @Test
    @DisplayName("mcpcalls")
    void mcpcalls() throws Exception {
        assertSurvives(McpCallsConfiguration.class);
    }

    private <T> void assertSurvives(Class<T> type) throws Exception {
        T original = type.cast(populate(type, type, "doc", 0));
        JsonNode tree = mapper.valueToTree(original);
        // The fixture must really be full: every nested level, a reference, and for the
        // LLM document the approval-gate override.
        assertTrue(tree.toString().contains("${vault:"), "fixture carries a credential reference");
        assertTrue(tree.toString().length() > 400, "fixture is populated: " + tree);
        if (type == LlmConfiguration.class) {
            assertTrue(tree.toString().contains("toolApprovals"), "fixture carries toolApprovals");
            assertTrue(tree.toString().contains("knowledgeBases"), "fixture carries nested objects");
        }
        String firstKey = tree.fieldNames().next();
        // A test operation: it changes nothing, so the result is the round trip alone.
        String patch = mapper.writeValueAsString(List.of(Map.of("op", "test", "path", "/" + firstKey, "value", tree.get(firstKey))));

        T result = patcher.apply(original, patch, type);

        List<String> differences = new ArrayList<>();
        compare(type.getSimpleName(), original, result, differences);
        assertTrue(differences.isEmpty(), "fields that did not survive the round trip:\n  " + String.join("\n  ", differences));
    }

    // ---- populate every field

    private static Object populate(Class<?> type, Type generic, String name, int depth) throws Exception {
        if (type == String.class) {
            return name.toLowerCase().contains("key") ? "${vault:key-" + name + "}" : "s-" + name;
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == Integer.class || type == int.class) {
            return 7;
        }
        if (type == Long.class || type == long.class) {
            return 9L;
        }
        if (type == Double.class || type == double.class) {
            return 1.5d;
        }
        if (type == Float.class || type == float.class) {
            return 2.5f;
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants[constants.length - 1];
        }
        if (type == Object.class) {
            return "s-" + name;
        }
        if (Collection.class.isAssignableFrom(type)) {
            Type element = ((ParameterizedType) generic).getActualTypeArguments()[0];
            var list = new ArrayList<Object>();
            list.add(populate(raw(element), element, name + "Item", depth + 1));
            return Set.class.isAssignableFrom(type) ? new LinkedHashSet<>(list) : list;
        }
        if (Map.class.isAssignableFrom(type)) {
            Type[] args = ((ParameterizedType) generic).getActualTypeArguments();
            var map = new LinkedHashMap<Object, Object>();
            map.put("entry", populate(raw(args[1]), args[1], "entry", depth + 1));
            if (raw(args[1]) == String.class) {
                map.put("apiKey", "${vault:anthropic-api-key}");
            }
            return map;
        }
        if (depth > 6) {
            return null;
        }
        return populateObject(type, name, depth);
    }

    private static Object populateObject(Class<?> type, String name, int depth) throws Exception {
        if (type.isRecord()) {
            var components = type.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = populate(components[i].getType(), components[i].getGenericType(), components[i].getName(), depth + 1);
            }
            return type.getDeclaredConstructor(types).newInstance(values);
        }
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        for (Field field : allFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            field.set(instance, populate(field.getType(), field.getGenericType(), field.getName(), depth + 1));
        }
        return instance;
    }

    private static List<Field> allFields(Class<?> type) {
        var fields = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(List.of(c.getDeclaredFields()));
        }
        return fields;
    }

    private static Class<?> raw(Type type) {
        return type instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) type;
    }

    // ---- compare through the fields

    private static void compare(String path, Object a, Object b, List<String> differences) {
        if (a == null || b == null) {
            if (a != b) {
                differences.add(path + ": " + a + " -> " + b);
            }
            return;
        }
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            if (ca.size() != cb.size()) {
                differences.add(path + ": size " + ca.size() + " -> " + cb.size());
                return;
            }
            var ia = ca.iterator();
            var ib = cb.iterator();
            for (int i = 0; ia.hasNext(); i++) {
                compare(path + "[" + i + "]", ia.next(), ib.next(), differences);
            }
            return;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (!ma.keySet().equals(mb.keySet())) {
                differences.add(path + ": keys " + ma.keySet() + " -> " + mb.keySet());
                return;
            }
            ma.forEach((k, v) -> compare(path + "." + k, v, mb.get(k), differences));
            return;
        }
        Class<?> type = a.getClass();
        if (type.isEnum() || type.getName().startsWith("java.")) {
            if (!Objects.equals(a, b)) {
                differences.add(path + ": " + a + " -> " + b);
            }
            return;
        }
        if (type != b.getClass()) {
            differences.add(path + ": type " + type + " -> " + b.getClass());
            return;
        }
        try {
            if (type.isRecord()) {
                for (var component : type.getRecordComponents()) {
                    var accessor = component.getAccessor();
                    compare(path + "." + component.getName(), accessor.invoke(a), accessor.invoke(b), differences);
                }
                return;
            }
            for (Field field : allFields(type)) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                compare(path + "." + field.getName(), field.get(a), field.get(b), differences);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the harness itself sees a difference: a field the mapper cannot round-trip is reported")
    void harnessDetectsADrop() {
        var a = new Holder();
        a.visible = "x";
        a.hidden = "y";
        var b = new Holder();
        b.visible = "x";
        var differences = new ArrayList<String>();
        compare("holder", a, b, differences);
        assertEquals(List.of("holder.hidden: y -> null"), differences);
    }

    @SuppressWarnings("unused")
    private static class Holder {
        String visible;
        String hidden;
    }
}
