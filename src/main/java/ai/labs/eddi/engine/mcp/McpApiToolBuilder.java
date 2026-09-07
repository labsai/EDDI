/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
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

    /**
     * The header {@code apiAuth} is sent in when the caller does not name one.
     */
    static final String DEFAULT_AUTH_HEADER = "Authorization";

    /**
     * RFC 9110 {@code field-name}: {@code 1*tchar}. Bounded, so a hostile value
     * cannot be used to smuggle a second header via CR/LF or to blow up the
     * request.
     */
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]{1,64}");

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
        return parseAndBuild(openApiSpec, endpointFilter, apiBaseUrl, apiAuth, null);
    }

    /**
     * Parse an OpenAPI spec and build grouped ApiCallsConfigurations, sending
     * {@code apiAuth} in a caller-named header.
     * <p>
     * The credential itself is not this class's problem — a
     * {@code ${connection:name}} reference already covers static keys, Basic and
     * OAuth, for a service account or per end user (see
     * {@code docs/connections.md}). What was missing is the header NAME. A
     * connection owns its header, and {@code ApiCallExecutor} refuses a generated
     * call whose header disagrees with the connection's, so a connection declaring
     * {@code x-api-key} could not be wired through this builder at all: every call
     * it generated named the header {@value #DEFAULT_AUTH_HEADER}, and the mismatch
     * failed the request rather than the setup. The spec could not close the gap
     * either, since header parameters are skipped (see {@link #buildApiCall}).
     *
     * @param openApiSpec
     *            OpenAPI spec as JSON/YAML string or a URL
     * @param endpointFilter
     *            comma-separated filter (e.g. "GET /users,POST /orders"), null for
     *            all
     * @param apiBaseUrl
     *            override the spec's server URL, null to use spec's servers[0]
     * @param apiAuth
     *            credential header value or vault ref, null for none
     * @param apiAuthHeader
     *            header {@code apiAuth} is sent in; null or blank means
     *            {@value #DEFAULT_AUTH_HEADER}
     * @return build result with grouped configs and API summary
     * @throws IllegalArgumentException
     *             if the spec cannot be parsed, or the header name is not a valid
     *             HTTP field-name
     */
    public static ApiBuildResult parseAndBuild(String openApiSpec, String endpointFilter, String apiBaseUrl, String apiAuth,
                                               String apiAuthHeader) {
        String authHeader = resolveAuthHeader(apiAuthHeader);
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

                    ApiCall httpCall = buildApiCall(method, path, operation, apiAuth, authHeader, openAPI);
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
     * <p>
     * <b>Security:</b> when the input is a location (not inline content), it is
     * required to be an {@code http}/{@code https} URL via
     * {@link UrlValidationUtils#isValidHttpUrl(String)} before being fetched. This
     * prevents the underlying swagger-parser {@code readLocation} from reading
     * local files (e.g. {@code file:///etc/passwd}) or using other non-http schemes
     * (classpath:, jar:, ftp:). Private/internal hosts are intentionally still
     * permitted so internal OpenAPI specs remain discoverable (the calling REST/MCP
     * surface is {@code eddi-admin}/{@code eddi-editor} gated). Inline JSON/YAML
     * content is parsed directly without any network access.
     */
    public static OpenAPI parseSpec(String specInput) {
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(true);

        SwaggerParseResult result;
        if (looksLikeInlineSpec(specInput)) {
            // Inline JSON or YAML content — no network/file access.
            result = new OpenAPIV3Parser().readContents(specInput, null, parseOptions);
        } else {
            // Remote location. Enforce an http(s) scheme so the parser's fetcher
            // cannot read local files (file://), classpath/jar resources, or use
            // other non-http schemes. Internal/private hosts stay allowed.
            String location = specInput.trim();
            if (!UrlValidationUtils.isValidHttpUrl(location)) {
                throw new IllegalArgumentException("OpenAPI spec location must be an http or https URL");
            }
            result = new OpenAPIV3Parser().readLocation(location, null, parseOptions);
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
     * Heuristic: does the input look like an inline OpenAPI document (JSON/YAML
     * content) rather than a remote location? A JSON object, an OpenAPI/Swagger
     * marker, or any multi-line content is inline. A single-token string such as
     * {@code https://host/openapi.json} is treated as a remote location and
     * validated as a URL before fetching.
     */
    static boolean looksLikeInlineSpec(String specInput) {
        String trimmed = specInput.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("openapi") || trimmed.startsWith("swagger") || trimmed.contains("\n");
    }

    /**
     * Build a single ApiCall from an OpenAPI operation.
     */
    private static ApiCall buildApiCall(String method, String path, Operation operation, String apiAuth, String authHeader, OpenAPI openAPI) {
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
        // Response headers, but only where the operation plausibly answers IN one.
        //
        // Without any, a whole class of REST API is unusable as a generated tool:
        // the "create" convention is 201 with an EMPTY body and the new resource's
        // id only in Location. EDDI's own POST /agents/{agentId}/start is exactly
        // that — Response.created(conversationUri).build() — so a model that
        // started a conversation received {"httpCode": 201} and had no way to learn
        // the id every following call needs. ApiCallExecutor populates the
        // "headers" key only when this name is set, and it is null by default,
        // which is why nothing generated here has ever seen a response header.
        //
        // Granting it to EVERY operation was the first version of this and it is
        // not worth the exposure. Response headers reach the tool result, the LLM
        // context and conversation memory (persisted), and nothing on that path
        // redacts them — RequestRedactor is request-only by construction and
        // SecretRedactionFilter runs on the display copy. Set-Cookie is the case
        // that matters: HttpClientModule builds a cookie-aware, application-scoped
        // WebClientSession, so that value is a live session credential EDDI is
        // actively replaying, and copying it into prompt-injectable context is
        // exactly what HttpOnly exists to prevent. A plain GET that answers with a
        // body has nothing to gain from it, so it does not get it.
        if (returnsDataInHeaders(operation)) {
            httpCall.setResponseHeaderObjectName(name + "_responseHeaders");
        }

        // Build request
        var request = new Request();
        request.setMethod(method.toLowerCase());

        // Convert path params to Qute templates: /{petId} → /{petId}
        String convertedPath = convertPathParams(path);
        request.setPath(convertedPath);

        // Headers (auth if provided)
        var headers = new LinkedHashMap<String, String>();
        if (apiAuth != null && !apiAuth.isBlank()) {
            headers.put(authHeader, apiAuth);
        }
        request.setHeaders(headers);

        // Parameters (path + query)
        var paramDescriptions = new LinkedHashMap<String, String>();
        var queryParams = new LinkedHashMap<String, String>();

        if (operation.getParameters() != null) {
            for (Parameter param : operation.getParameters()) {
                String paramName = param.getName();
                String paramDesc = param.getDescription() != null ? param.getDescription() : paramName;
                // The description is the model's ONLY view of the value space —
                // the generated tool schema types every parameter as a plain
                // string, and every one is REQUIRED. Without the allowed values
                // and the default spelled out, the model guesses: observed with
                // `environment`, where a guessed value silently falls back to
                // production on the lenient server-side enum parse — a
                // test-drive that quietly exercises the wrong deployment.
                paramDesc = appendSchemaHints(paramDesc, param.getSchema());

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
                // One level of $ref resolution, HERE and deliberately not resolveFully():
                // the parser leaves component references unresolved, so a body
                // declared as $ref: InputData reached describeBodySchema as a
                // nameless shell and the parameter description degraded to "a
                // single JSON object" with ZERO field names. The model then
                // guesses keys — observed with the say tool, where a guessed
                // {"message": ...} bound to InputData's DEFAULTS, returned 200,
                // and the approved test message was silently never delivered.
                // Nested property refs stay unresolved: the top-level field
                // names and requiredness are what the model needs to write a
                // correct body.
                var body = buildBodyTemplate(resolveComponentRef(jsonMedia.getSchema(), openAPI));
                // The body template's variables must be declared as tool parameters,
                // or the model has no documented way to fill them: the tool schema is
                // built from getParameters() alone (AgentOrchestrator), and with
                // strict-rendering off an undeclared variable renders as empty. The
                // request would go out structurally valid and semantically empty.
                //
                // A path or query parameter of the same name wins — those are
                // structural — so the body variable is RENAMED rather than dropped.
                // Dropping it is the empty-body bug all over again, for a spec that
                // happens to name a parameter "requestBody".
                String bodyTemplate = body.template();
                for (var variable : body.variables().entrySet()) {
                    String variableName = variable.getKey();
                    if (paramDescriptions.containsKey(variableName)) {
                        String renamed = variableName;
                        while (paramDescriptions.containsKey(renamed) || body.variables().containsKey(renamed)) {
                            renamed = renamed + "Body";
                        }
                        bodyTemplate = bodyTemplate.replace("{" + variableName + "}", "{" + renamed + "}");
                        paramDescriptions.put(renamed, variable.getValue());
                    } else {
                        paramDescriptions.put(variableName, variable.getValue());
                    }
                }
                request.setBody(bodyTemplate);
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
     * Whether this operation's useful answer can be in a response HEADER rather
     * than the body — the only case where binding them is worth the exposure
     * described at the call site.
     * <p>
     * Two shapes qualify, and both are the same underlying convention:
     * <ul>
     * <li>a declared {@code 201}/{@code 202} or any {@code 3xx} — "created" and
     * "redirected" both answer with a {@code Location} and routinely no body;</li>
     * <li>a {@code 2xx} that declares NO content — a {@code 204}, or a spec that
     * documents success with nothing in it. There is no body to read, so a header
     * is the only place an answer could be.</li>
     * </ul>
     * An operation whose success response declares content is answering in the body
     * and gets nothing.
     * <p>
     * A spec that documents no responses at all gets nothing either. That is the
     * deliberately safe reading of missing information: the cost is a capability an
     * undocumented endpoint silently lacks, against copying every future
     * {@code Set-Cookie} of an unknown API into conversation memory. EDDI's own
     * spec documents {@code 201} on {@code /agents/{agentId}/start}, which is the
     * case this exists for.
     */
    static boolean returnsDataInHeaders(Operation operation) {
        var responses = operation.getResponses();
        if (responses == null || responses.isEmpty()) {
            return false;
        }
        for (var entry : responses.entrySet()) {
            String status = entry.getKey();
            if (status == null) {
                continue;
            }
            if ("201".equals(status) || "202".equals(status) || status.startsWith("3")) {
                return true;
            }
            // A success that declares no content cannot be answering in the body.
            // "default" is deliberately not treated as a success here — it covers
            // errors just as often, and guessing wrong grants headers to every
            // operation that documents one.
            if (status.startsWith("2")) {
                var content = entry.getValue() == null ? null : entry.getValue().getContent();
                if (content == null || content.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
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
    /**
     * A request-body template together with the tool parameters the model must
     * supply to fill it.
     * <p>
     * The two travel together on purpose. A template variable that is not also
     * declared as a parameter is invisible to the model, and renders empty rather
     * than failing, so the call succeeds and the body is wrong.
     *
     * @param template
     *            the Qute body template
     * @param variables
     *            variable name to description, for {@code ApiCall.parameters}
     */
    private record BodyTemplate(String template, Map<String, String> variables) {
    }

    /** Name of the whole-body variable used when the schema has no properties. */
    static final String WHOLE_BODY_VARIABLE = "requestBody";

    /**
     * Resolves a top-level {@code $ref: #/components/schemas/X} to its component
     * schema, one level deep. Anything else — no ref, unknown name, no components —
     * returns the input unchanged.
     */
    private static Schema<?> resolveComponentRef(Schema<?> schema, OpenAPI openAPI) {
        if (schema == null || schema.get$ref() == null || openAPI == null
                || openAPI.getComponents() == null || openAPI.getComponents().getSchemas() == null) {
            return schema;
        }
        String ref = schema.get$ref();
        // Schemas namespace ONLY: a malformed ref into another components
        // namespace (requestBodies, parameters) must degrade to the safe
        // nameless form rather than resolving a same-named SCHEMA and
        // describing the wrong type's fields.
        String prefix = "#/components/schemas/";
        if (!ref.startsWith(prefix)) {
            return schema;
        }
        Schema<?> resolved = openAPI.getComponents().getSchemas().get(ref.substring(prefix.length()));
        return resolved != null ? resolved : schema;
    }

    private static BodyTemplate buildBodyTemplate(Schema<?> schema) {
        if (schema == null) {
            // A declared body with no schema still needs a variable, or the model has
            // no way to fill it and every write goes out empty — the exact failure the
            // whole-body form exists to prevent.
            return new BodyTemplate("{" + WHOLE_BODY_VARIABLE + "}",
                    Map.of(WHOLE_BODY_VARIABLE, "The complete JSON request body. The spec declares no schema for it."));
        }
        // One variable carrying the whole body, always — never a per-property
        // template. Decomposing looks more helpful and is worse in three ways:
        //
        // 1. Every variable becomes a REQUIRED tool parameter (AgentOrchestrator
        // builds the schema from ApiCall.parameters and marks all of them
        // required, and a Map<String,String> has nowhere to record optionality),
        // so a PATCH of one field forces the model to restate every other —
        // turning a partial update into a full overwrite.
        // 2. Values are substituted into the JSON unescaped: the templating engine
        // runs in TEXT mode and escapes nothing, so a model-supplied value
        // containing a quote can break the body or add fields the schema never
        // declared. With the whole body in one variable there is no substitution
        // boundary to cross.
        // 3. It is what the approver sees. The HITL card shows tool arguments, so
        // "the arguments are the request" only holds if the model wrote the body
        // itself.
        //
        // The shape the model would have inferred from a decomposed template is
        // preserved in the parameter description instead.
        return new BodyTemplate("{" + WHOLE_BODY_VARIABLE + "}", Map.of(WHOLE_BODY_VARIABLE, describeBodySchema(schema)));
    }

    /**
     * A one-line description of the body shape, for the tool parameter.
     * <p>
     * This is the model's only clue about what to write, so it names the properties
     * and marks which are required. Types are included because the model must
     * produce real JSON — an integer field unquoted, a string field quoted.
     */
    /**
     * Appends the schema's allowed values and default to a parameter description,
     * when it declares them. See the call site for why this is the model's only
     * channel for either.
     */
    private static String appendSchemaHints(String description, Schema<?> schema) {
        if (schema == null) {
            return description;
        }
        var sb = new StringBuilder(description);
        List<?> allowed = schema.getEnum();
        if (allowed != null && !allowed.isEmpty()) {
            sb.append(" Allowed values: ");
            sb.append(String.join(", ", allowed.stream().map(String::valueOf).toList()));
            sb.append(".");
        }
        Object defaultValue = schema.getDefault();
        if (defaultValue != null && !String.valueOf(defaultValue).isBlank()) {
            sb.append(" Default: ").append(defaultValue).append(".");
        }
        return sb.toString();
    }

    private static String describeBodySchema(Schema<?> schema) {
        // Name the container the schema actually declares. Saying "a single JSON
        // object" for a top-level array makes the model wrap the payload in braces,
        // and the request is malformed in a way the API reports and the config does
        // not explain.
        var description = new StringBuilder("The complete JSON request body, as ").append(switch (schema.getType() == null ? "" : schema.getType()) {
            case "array" -> "a JSON array.";
            case "string" -> "a JSON string.";
            case "integer", "number" -> "a JSON number.";
            case "boolean" -> "a JSON boolean.";
            default -> "a single JSON object.";
        });

        @SuppressWarnings("rawtypes")
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return description.toString();
        }

        List<String> required = schema.getRequired() != null ? schema.getRequired() : List.of();
        var parts = new ArrayList<String>();
        for (var entry : properties.entrySet()) {
            var propSchema = entry.getValue();
            var part = new StringBuilder(entry.getKey());
            if (propSchema.getType() != null) {
                part.append(" (").append(propSchema.getType());
                if (required.contains(entry.getKey())) {
                    part.append(", required");
                }
                part.append(")");
            } else if (required.contains(entry.getKey())) {
                part.append(" (required)");
            }
            if (propSchema.getDescription() != null && !propSchema.getDescription().isBlank()) {
                part.append(": ").append(propSchema.getDescription());
            }
            parts.add(part.toString());
        }
        return description.append(" Properties — ").append(String.join("; ", parts)).append(".").toString();
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
     * Resolve the header {@code apiAuth} is sent in, defaulting to
     * {@value #DEFAULT_AUTH_HEADER}.
     * <p>
     * Validated rather than sanitised: a header name reaches the wire verbatim, so
     * a value carrying CR/LF could append a second header to every generated call,
     * and silently dropping the offending characters would send the credential
     * under a name the API does not read — authenticating against nothing, which
     * looks like a permissions problem rather than a config error. Rejecting says
     * so at setup time.
     */
    static String resolveAuthHeader(String apiAuthHeader) {
        String trimmed = apiAuthHeader == null ? null : apiAuthHeader.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            return DEFAULT_AUTH_HEADER;
        }
        if (!HEADER_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "apiAuthHeader is not a valid HTTP header name: it must be 1-64 characters from the RFC 9110 token set (letters, digits and !#$%&'*+-.^_`|~)");
        }
        return trimmed;
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
