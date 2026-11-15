/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.engine.mcp.McpApiToolBuilder.ApiBuildResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Platform Operator's tools are generated from EDDI's own OpenAPI spec, and
 * every tool source registers its tools BY NAME: when two operations of the
 * same spec produce the same name, assembly drops the second
 * ({@code Tool name collision: http tool 'list' clashes with the
 * already-registered http tool of the same name — the http tool is DROPPED})
 * and the model never sees an endpoint the admin was told it had.
 * <p>
 * That is exactly what 6.7.0 builds did. {@code GET
 * .../groups/{groupId}/lessons} and {@code GET .../groups/{groupId}/outcomes}
 * were both Java methods called {@code list}, and the spec's operationId is the
 * method name ({@code quarkus.smallrye-openapi.operation-id-strategy=METHOD}).
 * Both sit under the tag "Agents / Groups", so setup-api put them in ONE
 * apicalls config, and the operator lost the outcomes tool on every deployment
 * that granted it.
 * <p>
 * This test rebuilds the spec from the JAX-RS interfaces (path, verb, tag and
 * method name, which is all tool naming reads) and holds two lines:
 * <ol>
 * <li><b>The generator</b> hands out a distinct name to every operation of the
 * whole spec, across every group of the one build. This is the backstop, and it
 * is what a deployment with an operationId EDDI has not fixed yet depends on.
 * <li><b>The source</b>: an operationId EDDI repeats is a defect in the spec,
 * not something to lean on the backstop for, because the backstop renames the
 * later operation to a method-and-path slug, which is not what a reader of the
 * allow-list, an approval pattern or the prompt expects it to be called. The
 * repeats that exist today are listed below; a new one fails, and an operation
 * the Manager's allow-list grants may not be among them.
 * </ol>
 * It reconstructs the spec rather than fetching {@code /openapi} so it runs in
 * the plain unit suite, with no Docker and no running server.
 */
class OperatorToolNamesTest {

    // java.io.File rather than java.nio.file.Path: the JAX-RS @Path annotation is
    // used here by its simple name.
    private static final File JAVA_SOURCES = new File("src/main/java");
    private static final File TOOL_SCOPES = new File("ui/manager/src/lib/operator/tool-scopes.ts");
    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)}");
    private static final Pattern PATH_PARAM_REGEX = Pattern.compile("\\{([^}:]+):[^}]*}");
    private static final Pattern GRANTED_LITERAL = Pattern.compile("(?m)^\\s*\"((?:GET|POST|PUT|PATCH|DELETE) /[^\"]*)\",?\\s*(?://.*)?$");
    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    /**
     * operationIds the spec repeats today, none of them reachable from the
     * operator's allow-list. Shrink this list, never grow it: give the new
     * operation its own {@code @Operation(operationId = "...")}.
     */
    private static final Set<String> KNOWN_REPEATED_OPERATION_IDS = Set.of("deleteSecret", "fetchManagerResources", "getDeadLetters", "listSecrets",
            "storeSecret", "viewDefault", "viewHtml");

    /** One operation as the spec lists it. */
    private record Op(String verb, String path, String tag, String operationId) {
        String key() {
            return verb + " " + path;
        }
    }

    private static List<Op> operations;
    private static String spec;

    @BeforeAll
    static void reconstructTheSpec() throws Exception {
        operations = new ArrayList<>();
        Set<String> classNames = new TreeSet<>();
        try (var files = Files.walk(JAVA_SOURCES.toPath())) {
            files.filter(file -> file.getFileName().toString().matches("IRest.*\\.java"))
                    .forEach(file -> classNames.add(JAVA_SOURCES.toPath().relativize(file).toString().replace('\\', '.').replace('/', '.')
                            .replaceAll("\\.java$", "")));
        }
        for (String className : classNames) {
            Class<?> type = Class.forName(className);
            var classPath = type.getAnnotation(Path.class);
            Tag tag = type.getAnnotation(Tag.class);
            for (Method method : type.getDeclaredMethods()) {
                String verb = verbOf(method);
                if (verb == null) {
                    continue;
                }
                var methodPath = method.getAnnotation(Path.class);
                String path = ("/" + (classPath == null ? "" : classPath.value()) + "/" + (methodPath == null ? "" : methodPath.value()))
                        .replaceAll("/+", "/");
                if (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                path = PATH_PARAM_REGEX.matcher(path).replaceAll("{$1}");
                Operation operation = method.getAnnotation(Operation.class);
                String operationId = operation != null && !operation.operationId().isBlank() ? operation.operationId() : method.getName();
                operations.add(new Op(verb, path, tag == null ? "General" : tag.name(), operationId));
            }
        }
        assertTrue(operations.size() > 300, "expected EDDI's REST surface, reconstructed only " + operations.size() + " operations");
        spec = toOpenApi(operations);
    }

    private static String verbOf(Method method) {
        for (var annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getSimpleName();
            if (VERBS.contains(name)) {
                return name;
            }
        }
        return null;
    }

    private static String toOpenApi(List<Op> operations) throws IOException {
        var mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("openapi", "3.0.0");
        root.putObject("info").put("title", "EDDI").put("version", "1");
        ObjectNode paths = root.putObject("paths");
        for (Op op : operations) {
            ObjectNode item = paths.has(op.path()) ? (ObjectNode) paths.get(op.path()) : paths.putObject(op.path());
            ObjectNode node = item.putObject(op.verb().toLowerCase());
            node.put("operationId", op.operationId());
            node.putArray("tags").add(op.tag());
            ArrayNode parameters = node.putArray("parameters");
            Matcher params = PATH_PARAM.matcher(op.path());
            while (params.find()) {
                ObjectNode parameter = parameters.addObject();
                parameter.put("name", params.group(1)).put("in", "path").put("required", true);
                parameter.putObject("schema").put("type", "string");
            }
            node.putObject("responses").putObject("200").put("description", "ok");
        }
        return mapper.writeValueAsString(root);
    }

    @Test
    void theGeneratorGivesEveryOperationOfTheWholeSpecItsOwnToolName() {
        ApiBuildResult result = McpApiToolBuilder.parseAndBuild(spec, null, "http://localhost:7070", null);

        List<String> names = new ArrayList<>();
        result.configsByGroup().values().forEach(config -> config.getHttpCalls().forEach(call -> names.add(call.getName())));

        assertEquals(result.endpointCount(), names.size());
        Set<String> seen = new HashSet<>();
        List<String> repeated = names.stream().filter(name -> !seen.add(name)).distinct().sorted().toList();
        assertTrue(repeated.isEmpty(),
                "tool names must be unique across every group of one build, or assembly drops the later tool: " + repeated);
        // Distinct response objects too: two calls storing under one name overwrite
        // each other.
        Set<String> responseObjects = new HashSet<>();
        for (var config : result.configsByGroup().values()) {
            for (ApiCall call : config.getHttpCalls()) {
                assertTrue(responseObjects.add(call.getResponseObjectName()), "two calls share a response object: " + call.getResponseObjectName());
            }
        }
    }

    @Test
    void anOperationIdIsNeverRepeatedInEddisOwnSpecBeyondTheKnownCases() {
        Map<String, List<String>> byId = new TreeMap<>();
        for (Op op : operations) {
            byId.computeIfAbsent(op.operationId(), id -> new ArrayList<>()).add(op.key() + " [" + op.tag() + "]");
        }
        Map<String, List<String>> repeated = new LinkedHashMap<>();
        byId.forEach((id, users) -> {
            if (users.size() > 1 && !KNOWN_REPEATED_OPERATION_IDS.contains(id)) {
                repeated.put(id, users);
            }
        });
        assertTrue(repeated.isEmpty(),
                "these operationIds are used by more than one operation. Give each its own @Operation(operationId = \"...\"): "
                        + "the generator would rename the later tool to a method-and-path slug (and before it did, assembly dropped it): "
                        + repeated);
        for (String known : KNOWN_REPEATED_OPERATION_IDS) {
            assertTrue(byId.getOrDefault(known, List.of()).size() > 1,
                    "'" + known + "' is no longer repeated; remove it from KNOWN_REPEATED_OPERATION_IDS");
        }
    }

    @Test
    void noEndpointTheOperatorIsGrantedSharesItsOperationIdWithAnother() throws IOException {
        Set<String> granted = grantedEndpoints();
        assertTrue(granted.size() > 60, "expected the allow-list literals of tool-scopes.ts, found " + granted.size());

        Map<String, Op> byKey = new LinkedHashMap<>();
        Map<String, Integer> idCounts = new TreeMap<>();
        for (Op op : operations) {
            byKey.put(op.key(), op);
            idCounts.merge(op.operationId(), 1, Integer::sum);
        }
        Set<String> unknown = new TreeSet<>();
        Set<String> shared = new TreeSet<>();
        for (String endpoint : granted) {
            Op op = byKey.get(endpoint);
            if (op == null) {
                unknown.add(endpoint);
            } else if (idCounts.get(op.operationId()) > 1) {
                shared.add(endpoint + " -> " + op.operationId());
            }
        }
        assertTrue(unknown.isEmpty(), "granted endpoints that no JAX-RS interface declares (this reconstruction is out of date): " + unknown);
        assertTrue(shared.isEmpty(), "the operator is granted endpoints whose tool name another operation also claims: " + shared);
    }

    @Test
    void grantedEndpointsKeepTheirOperationIdAsTheirToolName() throws IOException {
        Set<String> granted = grantedEndpoints();
        ApiBuildResult result = McpApiToolBuilder.parseAndBuild(spec, String.join(",", granted), "http://localhost:7070", null);

        Map<String, String> idByEndpoint = new LinkedHashMap<>();
        operations.forEach(op -> idByEndpoint.put(op.key(), op.operationId()));
        Set<String> expected = new TreeSet<>();
        granted.stream().map(idByEndpoint::get).filter(Objects::nonNull).forEach(expected::add);

        Set<String> actual = new TreeSet<>();
        result.configsByGroup().values().forEach(config -> config.getHttpCalls().forEach(call -> actual.add(call.getName())));
        assertEquals(expected, actual, "a granted endpoint was renamed or dropped by the generator");
    }

    /**
     * Every {@code "METHOD /path"} array entry in the Manager's allow-list source
     * (one per line, so a path quoted in a doc comment is not an entry).
     */
    private static Set<String> grantedEndpoints() throws IOException {
        assertTrue(TOOL_SCOPES.isFile(), "missing " + TOOL_SCOPES.getAbsolutePath());
        Matcher matcher = GRANTED_LITERAL.matcher(Files.readString(TOOL_SCOPES.toPath(), StandardCharsets.UTF_8));
        Set<String> granted = new TreeSet<>();
        while (matcher.find()) {
            String entry = matcher.group(1);
            if (!entry.contains("${")) {
                granted.add(entry);
            }
        }
        return granted;
    }
}
