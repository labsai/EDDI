/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.quarkus.smallrye.openapi.OpenApiFilter;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.PathItem;
import org.eclipse.microprofile.openapi.models.media.MediaType;
import org.eclipse.microprofile.openapi.models.media.Schema;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Publishes the strict configuration boundary in the OpenAPI document: every
 * schema the server parses with {@code FAIL_ON_UNKNOWN_PROPERTIES} gets
 * {@code additionalProperties: false}.
 *
 * <p>
 * {@link StrictConfigurationBodyInterceptor} answers 400 to an undeclared key
 * in any configuration body, nested objects included. The generated schemas
 * said nothing about that, and JSON Schema's default is "extra keys allowed",
 * so a client validating against the published contract could build a payload
 * the server refuses.
 * </p>
 *
 * <h2>What gets closed</h2>
 *
 * <p>
 * The roots are the request-body types the interceptor actually applies to:
 * every JAX-RS entity parameter whose class passes
 * {@link StrictConfigurationBodyInterceptor#isConfigurationModel}, found in the
 * Jandex index SmallRye hands this filter. From each root the Java type and its
 * schema are walked <em>together</em>, following the {@code $ref} each property
 * really emits. Nested schemas are never looked up by simple name: SmallRye
 * resolves collisions by suffixing ({@code HitlConfig}, {@code HitlConfig1}),
 * so a name says nothing reliable about which class it describes.
 * </p>
 *
 * <p>
 * A schema is closed when Jackson rejects an unknown key there and the schema
 * lists every key the server <em>writes</em> — the component is the response
 * schema too, so a closed schema missing an emitted key would reject EDDI's own
 * output. Keys that are only ever read are deliberately not required: legacy
 * {@code @JsonAlias} names (such as {@code retryHttpCallInstruction}) and keys
 * ignored by name are still accepted by the server, but the published schema
 * describes the canonical shape, so a validating client is held to the current
 * names. That makes the contract narrower than the server, never wider, and
 * only a wider contract can produce the 400 this exists to prevent.
 * </p>
 *
 * <p>
 * It is left open where the server accepts more than the schema can say:
 * </p>
 * <ul>
 * <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} or a
 * {@code @JsonAnySetter} — the server accepts anything there. Set on a property
 * instead of a class, the walk does not descend into that property;</li>
 * <li>a polymorphic type ({@code @JsonTypeInfo}) — the schema lists only the
 * base properties, so closing it would reject every subtype's fields;</li>
 * <li>{@code @JsonDeserialize} on the class — a custom deserializer, builder or
 * target type decides what it accepts, not the schema;</li>
 * <li>a {@code @JsonAnyGetter} or {@code @JsonUnwrapped} property — the server
 * writes keys the schema does not list;</li>
 * <li>maps ({@code Map<String, ?>}), {@code Object} and {@code JsonNode} —
 * genuinely open. A map's <em>value</em> type is still walked, because a
 * {@code Map<String, StrictType>} value is parsed strictly.</li>
 * </ul>
 *
 * <p>
 * A schema shared with a lenient endpoint is closed too: there is one schema
 * per class, and the strict reading is the one that can turn a valid-looking
 * payload into a 400.
 * </p>
 *
 * <p>
 * Array bodies ({@code Foo[]}) are strict at the boundary but are not walked:
 * none exists, and their schema is inline rather than a named component.
 * </p>
 */
@OpenApiFilter(stages = OpenApiFilter.RunStage.BUILD)
public class StrictConfigurationSchemaFilter implements OASFilter {

    private static final Logger LOGGER = Logger.getLogger(StrictConfigurationSchemaFilter.class);

    private static final String SCHEMA_REF_PREFIX = "#/components/schemas/";
    private static final List<DotName> HTTP_METHODS = List.of(
            DotName.createSimple("jakarta.ws.rs.POST"),
            DotName.createSimple("jakarta.ws.rs.PUT"),
            DotName.createSimple("jakarta.ws.rs.PATCH"),
            DotName.createSimple("jakarta.ws.rs.DELETE"));
    private static final DotName OPENAPI_SCHEMA = DotName.createSimple("org.eclipse.microprofile.openapi.annotations.media.Schema");

    /**
     * Introspection only. The strict parser's mapper is a copy of the CDI mapper;
     * the modules registered here ({@code Jdk8Module},
     * {@code ParameterNamesModule}, {@code JavaTimeModule}) are the ones that
     * change how a class's properties are found — {@code Optional} as a reference
     * type, creator parameters by name. The rest of that customisation (dates,
     * indentation, lenient unknown-property handling) does not.
     */
    private final DeserializationConfig jackson;
    private final SerializationConfig serialization;
    private final IndexView index;

    /** Called by SmallRye, which passes the application index. */
    public StrictConfigurationSchemaFilter(IndexView index) {
        this.index = index;
        JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
        this.jackson = mapper.getDeserializationConfig();
        this.serialization = mapper.getSerializationConfig();
    }

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return;
        }
        var walk = new Walk(openAPI.getComponents().getSchemas());
        Set<String> bodySchemas = requestBodySchemaNames(openAPI);

        for (Class<?> root : strictRoots()) {
            if (jackson.constructType(root).isContainerType()) {
                // A configuration that is itself a map (Properties) is open by definition.
                continue;
            }
            String name = resolveRootSchema(root, bodySchemas, walk.components);
            if (name == null) {
                LOGGER.warnf("No unambiguous request-body schema for strict configuration type %s; its schema is left open",
                        root.getName());
                continue;
            }
            walk.ref(jackson.constructType(root), name);
        }

        walk.closedComponents.keySet().removeAll(walk.conflicts);
        walk.closedComponents.values().forEach(schema -> schema.setAdditionalPropertiesBoolean(Boolean.FALSE));
        walk.closedInline.forEach(schema -> schema.setAdditionalPropertiesBoolean(Boolean.FALSE));
        LOGGER.debugf("Closed %d component and %d inline OpenAPI schemas to match the strict configuration boundary",
                walk.closedComponents.size(), walk.closedInline.size());
    }

    /**
     * The classes the interceptor treats as configuration bodies: entity parameters
     * of resource methods, in configuration-model packages.
     */
    Set<Class<?>> strictRoots() {
        Set<Class<?>> roots = new LinkedHashSet<>();
        if (index == null) {
            return roots;
        }
        for (DotName method : HTTP_METHODS) {
            for (AnnotationInstance annotation : index.getAnnotations(method)) {
                if (annotation.target().kind() != AnnotationTarget.Kind.METHOD) {
                    continue;
                }
                MethodInfo resourceMethod = annotation.target().asMethod();
                for (Type parameter : resourceMethod.parameterTypes()) {
                    if (parameter.kind() != Type.Kind.CLASS) {
                        continue;
                    }
                    Class<?> type = load(parameter.name().toString());
                    if (type != null && StrictConfigurationBodyInterceptor.isConfigurationModel(type)) {
                        roots.add(type);
                    }
                }
            }
        }
        return roots;
    }

    private static Set<String> requestBodySchemaNames(OpenAPI openAPI) {
        Set<String> names = new HashSet<>();
        if (openAPI.getPaths() == null || openAPI.getPaths().getPathItems() == null) {
            return names;
        }
        for (PathItem pathItem : openAPI.getPaths().getPathItems().values()) {
            for (Operation operation : pathItem.getOperations().values()) {
                if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null
                        || operation.getRequestBody().getContent().getMediaTypes() == null) {
                    continue;
                }
                for (MediaType mediaType : operation.getRequestBody().getContent().getMediaTypes().values()) {
                    String ref = mediaType.getSchema() == null ? null : mediaType.getSchema().getRef();
                    if (ref != null && ref.startsWith(SCHEMA_REF_PREFIX)) {
                        names.add(ref.substring(SCHEMA_REF_PREFIX.length()));
                    }
                }
            }
        }
        return names;
    }

    /**
     * The request-body schema generated for a root class: a body schema named after
     * the class, or that name with SmallRye's collision suffix, whose properties
     * are all ones the class declares. The property check applies even to a single
     * candidate — the class's own body may be hidden from the document while
     * another class of the same name is a body. Anything but exactly one match
     * stays open.
     */
    private String resolveRootSchema(Class<?> root, Set<String> bodySchemas, Map<String, Schema> components) {
        JavaType type = jackson.constructType(root);
        Set<String> declared = new HashSet<>();
        describe(type).findProperties().forEach(property -> declared.add(property.getName()));
        describeWrite(type).findProperties().forEach(property -> declared.add(property.getName()));

        Pattern candidate = Pattern.compile(Pattern.quote(schemaName(root)) + "\\d*");
        List<String> matches = new ArrayList<>();
        for (String name : bodySchemas) {
            Schema schema = components.get(name);
            if (candidate.matcher(name).matches() && schema != null && schema.getProperties() != null
                    && declared.containsAll(schema.getProperties().keySet())) {
                matches.add(name);
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String schemaName(Class<?> type) {
        var classInfo = index.getClassByName(type.getName());
        AnnotationInstance schema = classInfo == null ? null : classInfo.declaredAnnotation(OPENAPI_SCHEMA);
        if (schema != null && schema.value("name") != null && !schema.value("name").asString().isBlank()) {
            return schema.value("name").asString();
        }
        return type.getSimpleName();
    }

    private BeanDescription describe(JavaType type) {
        return jackson.introspect(type);
    }

    private BeanDescription describeWrite(JavaType type) {
        return serialization.introspect(type);
    }

    private static Class<?> load(String className) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            return Class.forName(className, false, loader != null ? loader : StrictConfigurationSchemaFilter.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** One pass over the schemas reachable from the strict roots. */
    private final class Walk {
        final Map<String, Schema> components;
        /** Component name → the class it was reached as. */
        final Map<String, Class<?>> visited = new HashMap<>();
        /** Component schemas to close, by name. */
        final Map<String, Schema> closedComponents = new HashMap<>();
        /** Inline object schemas to close. */
        final Set<Schema> closedInline = identitySet();
        /** Component names reached as two different classes — never closed. */
        final Set<String> conflicts = new HashSet<>();
        final Set<Schema> visitedInline = identitySet();

        Walk(Map<String, Schema> components) {
            this.components = components;
        }

        void ref(JavaType type, String name) {
            Schema schema = components.get(name);
            if (schema == null) {
                return;
            }
            if (!isBean(type)) {
                // An Object- or Map-typed property rendered as a $ref (say through
                // @Schema(implementation = ...)): the Java side says nothing about the
                // component, so it neither claims nor conflicts with it.
                return;
            }
            Class<?> raw = type.getRawClass();
            Class<?> previous = visited.putIfAbsent(name, raw);
            if (previous != null) {
                if (previous != raw) {
                    conflicts.add(name);
                }
                return;
            }
            walk(type, schema, name);
        }

        void walk(JavaType type, Schema schema, String componentName) {
            if (type == null || schema == null) {
                return;
            }
            String ref = schema.getRef();
            if (ref != null) {
                if (ref.startsWith(SCHEMA_REF_PREFIX)) {
                    ref(type, ref.substring(SCHEMA_REF_PREFIX.length()));
                }
                return;
            }
            Schema wrapped = singleAllOfRef(schema);
            if (wrapped != null) {
                // {allOf: [{$ref}], description: ...}: how a $ref carries siblings.
                walk(type, wrapped, null);
                return;
            }
            if (type.isReferenceType()) {
                walk(type.getContentType(), schema, null);
            } else if (type.isArrayType() || type.isCollectionLikeType()) {
                walk(type.getContentType(), schema.getItems(), null);
            } else if (type.isMapLikeType()) {
                // The map itself is open by definition; its values are not.
                walk(type.getContentType(), schema.getAdditionalPropertiesSchema(), null);
            } else if (isBean(type)) {
                if (componentName == null && !visitedInline.add(schema)) {
                    return;
                }
                bean(type, schema, componentName);
            }
        }

        private void bean(JavaType type, Schema schema, String componentName) {
            BeanDescription description = describe(type);
            AnnotationIntrospector introspector = jackson.getAnnotationIntrospector();
            var annotatedClass = description.getClassInfo();

            if (annotatedClass.hasAnnotation(JsonTypeInfo.class) || annotatedClass.hasAnnotation(JsonDeserialize.class)
                    || introspector.findDeserializer(annotatedClass) != null) {
                // Polymorphic or custom-deserialised: the schema cannot express what is
                // accepted, and the subtype schemas are not part of the document.
                return;
            }

            List<BeanPropertyDefinition> properties = description.findProperties();
            if (closable(type, description, schema)) {
                if (componentName != null) {
                    closedComponents.put(componentName, schema);
                } else {
                    closedInline.add(schema);
                }
            }

            for (BeanPropertyDefinition property : properties) {
                if (schema.getProperties() == null) {
                    break;
                }
                AnnotatedMember mutator = property.getMutator() != null ? property.getMutator() : property.getPrimaryMember();
                if (mutator != null && (mutator.hasAnnotation(JsonTypeInfo.class) || mutator.hasAnnotation(JsonDeserialize.class)
                        || mutator.hasAnnotation(JsonIgnoreProperties.class) || mutator.hasAnnotation(JsonUnwrapped.class)
                        || introspector.findDeserializer(mutator) != null)) {
                    // The property's own annotation changes what is accepted below it.
                    continue;
                }
                walk(property.getPrimaryType(), schema.getProperties().get(property.getName()), null);
            }
        }

        private boolean closable(JavaType type, BeanDescription description, Schema schema) {
            if (schema.getProperties() == null || schema.getProperties().isEmpty() || schema.getAdditionalPropertiesSchema() != null
                    || schema.getAdditionalPropertiesBoolean() != null || schema.getAllOf() != null || schema.getAnyOf() != null
                    || schema.getOneOf() != null) {
                return false;
            }

            var ignorals = jackson.getDefaultPropertyIgnorals(type.getRawClass(), description.getClassInfo());
            if (ignorals != null && ignorals.getIgnoreUnknown()) {
                return false;
            }
            if (description.findAnySetterAccessor() != null) {
                return false;
            }

            // The component is also the response schema. Every key the server writes
            // must be listed, or a closed schema would reject EDDI's own output. Keys
            // that are only ever read (legacy aliases, names ignored on input) are
            // deliberately not required: the schema describes the canonical shape.
            BeanDescription write = describeWrite(type);
            if (write.findAnyGetter() != null) {
                return false;
            }
            Set<String> written = new HashSet<>();
            for (BeanPropertyDefinition property : write.findProperties()) {
                if (property.getPrimaryMember() != null && property.getPrimaryMember().hasAnnotation(JsonUnwrapped.class)) {
                    return false;
                }
                if (property.couldSerialize()) {
                    written.add(property.getName());
                }
            }
            return schema.getProperties().keySet().containsAll(written);
        }

        private static Schema singleAllOfRef(Schema schema) {
            List<Schema> allOf = schema.getAllOf();
            if (allOf == null || allOf.size() != 1 || allOf.getFirst().getRef() == null) {
                return null;
            }
            return allOf.getFirst();
        }

        private static Set<Schema> identitySet() {
            return Collections.newSetFromMap(new IdentityHashMap<>());
        }

        private static boolean isBean(JavaType type) {
            Class<?> raw = type.getRawClass();
            return !raw.isPrimitive() && !raw.isEnum() && raw != Object.class && !JsonNode.class.isAssignableFrom(raw)
                    && !raw.getName().startsWith("java.") && !raw.getName().startsWith("javax.");
        }
    }
}
