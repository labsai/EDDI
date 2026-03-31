package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parses an OpenAPI spec and generates EDDI-compatible ApiCallsConfiguration
 * resources, grouped by OpenAPI tag. Each tag becomes a separate
 * ApiCallsConfiguration with proper naming (e.g. "MyAPI - Users", "MyAPI -
 * Orders").
 *
 * <p>
 * This is a stateless utility used by {@link McpSetupTools#createApIAgent}.
 * </p>
 *
 * @author ginccc
 */
public final class McpApiToolBuilder {

    private static final Logger LOGGER = Logger.getLogger(McpApiToolBuilder.class);
    private static final String DEFAULT_GROUP = "General";
    private static final int MAX_SUMMARY_ENDPOINTS = 30;
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");

    private McpApiToolBuilder() {
        // utility class
    }

    /**
     * Result of building configs from an OpenAPI spec.
     *
     * @param title
     *            API title from the spec's info section
     * @param configsByGroup
     *            map of group/tag name → ApiCallsConfiguration
     * @param apiSummary
     *            human-readable summary of available endpoints for LLM context
     * @param endpointCount
     *            total number of endpoints processed
     */
    public record ApiBuildResult(String title, Map<String, ApiCallsConfiguration> configsByGroup, String apiSummary, int endpointCount) {
    }

    /**
     * Parse an OpenAPI spec and build grouped ApiCallsConfigurations.
     *
     * @param openApiSpec
     *            OpenAPI spec as JSON/YAML string or a URL
     * @param endpointFilter
     *            comma-separated filter (e.g. "GET /users,POST /orders"), null for
     *            all
     * @param apiBaseUrl
     *            override the spec's server URL, null to use spec's servers[0]
     * @param apiAuth
     *            authorization header value or vault ref, null for none
     * @return build result with grouped configs and API summary
     * @throws IllegalArgumentException
     *             if the spec cannot be parsed
     */
    public static ApiBuildResult parseAndBuild(String openApiSpec, String endpointFilter, String apiBaseUrl, String apiAuth) {
        OpenAPI openAPI = parseSpec(openApiSpec);
        String baseUrl = resolveBaseUrl(openAPI, apiBaseUrl);
        Set<String> allowedEndpoints = parseEndpointFilter(endpointFilter);

        // Collect all operations grouped by tag
        Map<String, List<ApiCall>> callsByGroup = new LinkedHashMap<>();
        int endpointCount = 0;
        var summaryLines = new ArrayList<String>();

        if (openAPI.getPaths() != null) {
            for (var pathEntry : openAPI.getPaths().entrySet()) {
                String path = pathEntry.getKey();
                PathItem pathItem = pathEntry.getValue();

                for (var methodEntry : getOperations(pathItem).entrySet()) {
                    String method = methodEntry.getKey();
                    Operation operation = methodEntry.getValue();

                    // Skip deprecated operations
                    if (Boolean.TRUE.equals(operation.getDeprecated())) {
                        LOGGER.debugf("Skipping deprecated operation: %s %s", method, path);
                        continue;
                    }

                    // Apply endpoint filter
                    if (!allowedEndpoints.isEmpty()) {
                        String filterKey = method.toUpperCase() + " " + path;
                        if (!allowedEndpoints.contains(filterKey)) {
                            continue;
                        }
                    }

                    // Determine group (first tag, or "General")
                    String group = (operation.getTags() != null && !operation.getTags().isEmpty()) ? operation.getTags().get(0) : DEFAULT_GROUP;

                    ApiCall httpCall = buildApiCall(method, path, operation, apiAuth);
                    callsByGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(httpCall);
                    endpointCount++;

                    String desc = operation.getSummary() != null ? operation.getSummary() : httpCall.getName();
                    summaryLines.add("- " + method.toUpperCase() + " " + path + ": " + desc);
                }
            }
        }

        if (endpointCount == 0) {
            throw new IllegalArgumentException(
                    "No valid endpoints found in the OpenAPI spec" + (allowedEndpoints.isEmpty() ? "" : " matching the filter"));
        }

        // Build ApiCallsConfiguration per group
        Map<String, ApiCallsConfiguration> configsByGroup = new LinkedHashMap<>();
        for (var entry : callsByGroup.entrySet()) {
            var config = new ApiCallsConfiguration();
            config.setTargetServerUrl(baseUrl);
            config.setHttpCalls(entry.getValue());
            configsByGroup.put(entry.getKey(), config);
        }

        var displayLines = summaryLines.size() <= MAX_SUMMARY_ENDPOINTS ? summaryLines : summaryLines.subList(0, MAX_SUMMARY_ENDPOINTS);
        String apiSummary = "Available API endpoints (" + endpointCount + " total, " + configsByGroup.size() + " groups):\n"
                + String.join("\n", displayLines);
        if (summaryLines.size() > MAX_SUMMARY_ENDPOINTS) {
            apiSummary += "\n... and " + (summaryLines.size() - MAX_SUMMARY_ENDPOINTS) + " more";
        }

        String title = (openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null) ? openAPI.getInfo().getTitle() : "API";

        return new ApiBuildResult(title, configsByGroup, apiSummary, endpointCount);
    }

    /**
     * Parse an OpenAPI spec from a JSON/YAML string or URL.
     */
    public static OpenAPI parseSpec(String specInput) {
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(true);

        SwaggerParseResult result;
        if (specInput.trim().startsWith("{") || specInput.trim().startsWith("openapi")) {
            // Inline JSON or YAML content
            result = new OpenAPIV3Parser().readContents(specInput, null, parseOptions);
        } else {
            // URL or file path
            result = new OpenAPIV3Parser().readLocation(specInput, null, parseOptions);
        }

        if (result == null || result.getOpenAPI() == null) {
            String errors = result != null && result.getMessages() != null ? String.join("; ", result.getMessages()) : "unknown error";
            throw new IllegalArgumentException("Failed to parse OpenAPI spec: " + errors);
        }

        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            LOGGER.warnf("OpenAPI parse warnings: %s", result.getMessages());
        }

        return result.getOpenAPI();
    }

    /**
     * Build a single ApiCall from an OpenAPI operation.
     */
    private static ApiCall buildApiCall(String method, String path, Operation operation, String apiAuth) {
        var httpCall = new ApiCall();

        // Name: operationId or generated slug
        String name = operation.getOperationId();
        if (name == null || name.isBlank()) {
            name = generateSlug(method, path);
        }
        httpCall.setName(name);

        // Description for LLM agents
        String description = operation.getSummary();
        if (description == null || description.isBlank()) {
            description = operation.getDescription();
        }
        if (description != null && !description.isBlank()) {
            httpCall.setDescription(description);
        }

        // Action name — used by behavior rules to trigger this call
        String actionName = "api_" + generateSlug(method, path);
        httpCall.setActions(List.of(actionName));

        // Save response for post-processing
        httpCall.setSaveResponse(true);
        httpCall.setResponseObjectName(name + "_response");

        // Build request
        var request = new Request();
        request.setMethod(method.toLowerCase());

        // Convert path params to Qute templates: /{petId} → /{petId}
        String convertedPath = convertPathParams(path);
        request.setPath(convertedPath);

        // Headers (auth if provided)
        var headers = new LinkedHashMap<String, String>();
        if (apiAuth != null && !apiAuth.isBlank()) {
            headers.put("Authorization", apiAuth);
        }
        request.setHeaders(headers);

        // Parameters (path + query)
        var paramDescriptions = new LinkedHashMap<String, String>();
        var queryParams = new LinkedHashMap<String, String>();

        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                String paramName = param.getName();
                String paramDesc = param.getDescription() != null ? param.getDescription() : paramName;

                if ("query".equals(param.getIn())) {
                    // Query params use Qute template for LLM-provided values
                    queryParams.put(paramName, "{" + paramName + "}");
                    paramDescriptions.put(paramName, paramDesc);
                } else if ("path".equals(param.getIn())) {
                    paramDescriptions.put(paramName, paramDesc);
                }
                // header/cookie params are skipped for now
            }
        }
        request.setQueryParams(queryParams);

        // Request body for POST/PUT/PATCH
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            var content = operation.getRequestBody().getContent();
            MediaType jsonMedia = content.get("application/json");
            if (jsonMedia != null) {
                request.setContentType("application/json");
                request.setBody(buildBodyTemplate(jsonMedia.getSchema()));
            }
        }

        httpCall.setRequest(request);

        // Set parameter descriptions for LLM tool use
        if (!paramDescriptions.isEmpty()) {
            httpCall.setParameters(paramDescriptions);
        }

        return httpCall;
    }

    /**
     * Convert OpenAPI path params to Qute templates. E.g. /pets/{petId}/toys →
     * stays as /pets/{petId}/toys (already Qute-compatible)
     */
    static String convertPathParams(String path) {
        var matcher = PATH_PARAM_PATTERN.matcher(path);
        var sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement("{" + paramName + "}"));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Generate a slug from method + path: GET /users/{id} → get_users_id
     */
    static String generateSlug(String method, String path) {
        String cleaned = path.replaceAll("[{}]", "").replaceAll("[^a-zA-Z0-9/]", "").replaceAll("/+", "_").replaceAll("^_|_$", "");
        return method.toLowerCase() + "_" + cleaned.toLowerCase();
    }

    /**
     * Build a JSON body template from a schema. Produces a Qute-templated JSON body
     * where each property is a template variable.
     * <p>
     * Note: Only handles flat schemas (direct properties). Nested objects and
     * arrays fall back to a single {@code {requestBody}} template variable.
     */
    private static String buildBodyTemplate(Schema<?> schema) {
        if (schema == null) {
            return "{}";
        }

        @SuppressWarnings("rawtypes")
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            // No properties — use the raw template variable based on schema
            return "{requestBody}";
        }

        var sb = new StringBuilder("{\n");
        var entries = new ArrayList<>(properties.entrySet());
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            String propName = entry.getKey();
            Schema<?> propSchema = entry.getValue();

            sb.append("  \"").append(propName).append("\": ");

            String type = propSchema.getType();
            if ("string".equals(type)) {
                sb.append("\"{").append(propName).append("}\"");
            } else {
                // number, integer, boolean, object, array — unquoted template
                sb.append("{").append(propName).append("}");
            }

            if (i < entries.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Resolve the API base URL from the OpenAPI spec or override.
     */
    private static String resolveBaseUrl(OpenAPI openAPI, String apiBaseUrl) {
        if (apiBaseUrl != null && !apiBaseUrl.isBlank()) {
            return apiBaseUrl.trim();
        }
        if (openAPI.getServers() != null && !openAPI.getServers().isEmpty()) {
            Server server = openAPI.getServers().get(0);
            return server.getUrl();
        }
        return "https://api.example.com"; // fallback
    }

    /**
     * Parse the endpoint filter string into a set of "METHOD /path" entries.
     */
    private static Set<String> parseEndpointFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(filter.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(s -> {
            // Uppercase the method but keep the path as-is
            int space = s.indexOf(' ');
            if (space > 0) {
                return s.substring(0, space).toUpperCase() + s.substring(space);
            }
            return s.toUpperCase();
        }).collect(Collectors.toSet());
    }

    /**
     * Extract all operations from a PathItem as method → operation map.
     */
    private static Map<String, Operation> getOperations(PathItem pathItem) {
        var ops = new LinkedHashMap<String, Operation>();
        if (pathItem.getGet() != null)
            ops.put("GET", pathItem.getGet());
        if (pathItem.getPost() != null)
            ops.put("POST", pathItem.getPost());
        if (pathItem.getPut() != null)
            ops.put("PUT", pathItem.getPut());
        if (pathItem.getDelete() != null)
            ops.put("DELETE", pathItem.getDelete());
        if (pathItem.getPatch() != null)
            ops.put("PATCH", pathItem.getPatch());
        return ops;
    }
}
