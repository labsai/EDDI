/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.dictionary.model.DictionaryConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.SerializationCustomizer;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What EDDI serializes, EDDI must be able to read back.
 * <p>
 * {@link StrictConfigurationBodyInterceptor} rejects unknown keys on inbound
 * configuration bodies. That promotes serialize/deserialize asymmetry — a
 * derived getter with no matching field or setter, which Jackson happily writes
 * and strictly refuses to read — from a silent no-op into a hard 400. Three
 * paths serialize a configuration model and hand it straight back to EDDI's own
 * REST boundary:
 * <ul>
 * <li>{@code AgentSetupService} builds each config as an object and posts it
 * through the internal typed REST clients, so the setup wizard 400s on its own
 * output;</li>
 * <li>EDDI-Manager does GET → edit → PUT;</li>
 * <li>export → ZIP import replays what export wrote.</li>
 * </ul>
 * {@code LlmConfiguration.Task.isAgentMode()} was exactly this: a pure function
 * of {@code tools} / {@code enableBuiltInTools} / {@code a2aAgents}, written
 * out as {@code agentMode}, unreadable on the way back in. It reached CI as
 * {@code Expected status code <201> but was <400>} with no field named
 * anywhere.
 * <p>
 * Jackson introspection rather than instance round-tripping, deliberately. An
 * instance built from {@code {}} has empty collections, so nested models are
 * never reached — an earlier version of this test passed on
 * {@code LlmConfiguration} while the bug sat in its nested {@code Task}.
 * Introspection compares the serializable and deserializable property sets per
 * class and walks into property types, so nested models are covered whether or
 * not a default instance would reach them.
 */
class StrictBoundaryRoundTripTest {

    /**
     * Top-level configuration models behind the REST resources the interceptor
     * guards.
     */
    private static final List<Class<?>> ROOTS = List.of(
            AgentConfiguration.class,
            WorkflowConfiguration.class,
            RuleSetConfiguration.class,
            ApiCallsConfiguration.class,
            LlmConfiguration.class,
            OutputConfigurationSet.class,
            PropertySetterConfiguration.class,
            DictionaryConfiguration.class,
            ParserConfiguration.class,
            McpCallsConfiguration.class,
            RagConfiguration.class,
            AgentGroupConfiguration.class);

    @Test
    @DisplayName("no config model serializes a property it cannot deserialize")
    void everySerializedPropertyIsReadableBack() {
        var mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        var offenders = new LinkedHashMap<String, Set<String>>();
        var visited = new LinkedHashSet<Class<?>>();
        Deque<JavaType> queue = new ArrayDeque<>();
        ROOTS.forEach(c -> queue.add(mapper.constructType(c)));

        while (!queue.isEmpty()) {
            JavaType type = queue.poll();
            Class<?> raw = type.getRawClass();
            if (!isEddiModel(raw) || !visited.add(raw)) {
                continue;
            }

            var serNames = propertyNames(mapper, type, true);
            var deserNames = propertyNames(mapper, type, false);
            var writeOnly = new TreeSet<>(serNames);
            writeOnly.removeAll(deserNames);
            if (!writeOnly.isEmpty()) {
                offenders.put(raw.getName(), writeOnly);
            }

            // Walk into property types so nested models are covered even when a
            // default instance would leave their collections empty.
            for (var prop : mapper.getSerializationConfig().introspect(type).findProperties()) {
                collectCandidateTypes(prop.getPrimaryType(), queue);
            }
        }

        assertTrue(offenders.isEmpty(), () -> "config models that serialize properties they cannot read back — "
                + "each is a 400 at the REST boundary for the setup wizard, EDDI-Manager PUTs and ZIP import. "
                + "Add a field/setter, or annotate the getter @JsonIgnore if it is derived state:\n"
                + offenders.entrySet().stream()
                        .map(e -> "  " + e.getKey() + " → " + e.getValue())
                        .collect(Collectors.joining("\n")));
    }

    /**
     * Serializable or deserializable property names for a type, as Jackson resolves
     * them. {@code couldSerialize} / {@code couldDeserialize} are what actually
     * decide whether a key is written and whether it can be read, which is
     * precisely the asymmetry under test — {@code @JsonIgnore}d members drop out of
     * both sides.
     */
    private static Set<String> propertyNames(ObjectMapper mapper, JavaType type, boolean serialize) {
        var description = serialize
                ? mapper.getSerializationConfig().introspect(type)
                : mapper.getDeserializationConfig().introspect(type);
        return description.findProperties().stream()
                .filter(p -> serialize ? p.couldSerialize() : p.couldDeserialize())
                .map(BeanPropertyDefinition::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Enqueues a property type and its generic parameters (list element, map value,
     * …).
     */
    private static void collectCandidateTypes(JavaType type, Deque<JavaType> queue) {
        if (type == null) {
            return;
        }
        queue.add(type);
        for (int i = 0; i < type.containedTypeCount(); i++) {
            collectCandidateTypes(type.containedType(i), queue);
        }
        if (type.getContentType() != null) {
            collectCandidateTypes(type.getContentType(), queue);
        }
    }

    /**
     * Only EDDI's own model classes are in scope. Enums and records with no bean
     * properties fall out naturally — they simply report no asymmetry.
     */
    private static boolean isEddiModel(Class<?> raw) {
        return raw.getName().startsWith("ai.labs.eddi.") && !raw.isEnum() && !raw.isInterface();
    }

    /** Kept so a failure can show what the sweep actually covered. */
    @Test
    @DisplayName("the sweep reaches nested models, not just the top-level roots")
    void sweepReachesNestedModels() {
        var mapper = SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
        var reached = new ArrayList<String>();
        var visited = new LinkedHashSet<Class<?>>();
        Deque<JavaType> queue = new ArrayDeque<>();
        ROOTS.forEach(c -> queue.add(mapper.constructType(c)));

        while (!queue.isEmpty()) {
            JavaType type = queue.poll();
            if (!isEddiModel(type.getRawClass()) || !visited.add(type.getRawClass())) {
                continue;
            }
            reached.add(type.getRawClass().getSimpleName());
            for (var prop : mapper.getSerializationConfig().introspect(type).findProperties()) {
                collectCandidateTypes(prop.getPrimaryType(), queue);
            }
        }

        // The guard is only meaningful if traversal genuinely descends.
        // LlmConfiguration.Task
        // is the case that matters: the agentMode bug lived there, invisible to any
        // test that
        // only looked at the top-level roots.
        assertTrue(reached.contains("Task"),
                () -> "traversal never reached LlmConfiguration.Task, so the asymmetry sweep would "
                        + "have missed the agentMode bug. Reached: " + reached);
        assertTrue(reached.size() > ROOTS.size(),
                () -> "traversal reached only " + reached.size() + " classes for " + ROOTS.size()
                        + " roots — it is not descending into nested models: " + reached);
    }
}
