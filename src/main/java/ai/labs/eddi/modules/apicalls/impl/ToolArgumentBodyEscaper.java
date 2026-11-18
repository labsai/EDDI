/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.Metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a model-supplied tool argument from changing the structure of a JSON
 * request body.
 * <p>
 * A request body is a Qute template and the values it substitutes are written
 * as they are. On the tool path the values are chosen by a model (and so, by
 * whoever can talk to it): the argument {@code x","role":"admin} turned
 * {@code {"name":"{name}"}} into a body with a second field, sent with the
 * agent's credentials. This class closes that for <em>tool arguments</em> —
 * author-controlled data ({@code {properties.x}}, {@code {context.y}}, API
 * responses) is not touched.
 *
 * <h2>The guarantee, and how it is reached</h2> While the body of a tool call
 * is rendered with escaping on, two mechanisms work together:
 * <ol>
 * <li><b>The data is escaped, not the references.</b> The body is rendered
 * against a copy of the template data in which every model argument holds an
 * escaped twin of itself: every string, every map key and the text of every map
 * or list is JSON-string-escaped. So whichever way the template reads the value
 * — {@code {name}}, {@code {data:name}}, {@code {#let v=name}}, {@code {#each
 * tags}{it}}, {@code {#for t in tags}}, {@code {#with meta}}, a nested
 * property, a {@code forEach} item, the {@code toString()} of a map — inside a
 * JSON string literal it can only be the content of that literal.</li>
 * <li><b>A bare position receives only what this class serialised.</b> A plain
 * {@code {arg}} outside a string is rewritten to the argument's canonical JSON
 * (its validated JSON value on a typed call). Anything else outside a string
 * that can be derived from an argument — {@code {id ?: 0}}, an alias, a section
 * — is not trusted: the body is rendered a second time against <em>inert</em>
 * stand-ins (same shapes, same lengths, every string that could form JSON
 * structure replaced by characters that cannot) and the two results must parse
 * as JSON of the same structure: same node kinds, same keys, same array
 * lengths, the same author-written leaves; only argument-derived leaves may
 * differ. That is the backstop. It refuses, with a value-free message, any body
 * in which an argument escaped its slot, however the template got it
 * there.</li>
 * </ol>
 * The cost per guarded call is one extra render of the body and two JSON parses
 * plus a tree walk — paid only when the body is JSON, escaping is on and the
 * template mentions an argument at all; every other render (the rule path, a
 * body that does not mention an argument, a non-JSON body, an opted-out call)
 * is the configured template rendered against the raw data, byte for byte as
 * before.
 * <p>
 * {@code {json:...}} expressions read the raw argument (they are the author's
 * own escaping); a whole-body {@code {requestBody}} is the model's own document
 * and left to the provider's body guard.
 */
public final class ToolArgumentBodyEscaper {

    /** Prefix of the data entries holding an argument as canonical JSON. */
    static final String VALUE_PREFIX = "eddiJsonVal_";
    /** Prefix of the data entries holding an argument raw, for {@code json:}. */
    static final String RAW_PREFIX = "eddiJsonRaw_";

    /** Counter of tool calls refused because of their arguments. */
    static final String REFUSED_METRIC = "eddi.httpcalls.tool.argument.refused";

    /**
     * What an inert stand-in string is made of: private-use characters, which are
     * plain content inside a JSON string and invalid JSON anywhere else.
     */
    private static final char INERT = '';
    private static final char INERT_LAST = '';
    /** The inert render's stand-in for a canonical bare slot: any value. */
    private static final String WILDCARD = "";
    private static final String WILDCARD_JSON = "\"" + WILDCARD + "\"";

    private static final Pattern WHOLE_BODY_TEMPLATE = Pattern.compile("^\\{([A-Za-z_][A-Za-z0-9_]*)}$");
    private static final Pattern NAMESPACE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):");
    /**
     * A {@code ${ns:...}} reference (vault, vars, caller, connection, context) is
     * resolved after rendering, so at check time it is still literal text; it is
     * replaced by a neutral token in both renders before they are parsed.
     */
    private static final Pattern CONFIG_REFERENCE = Pattern.compile("\\$\\{[A-Za-z][A-Za-z0-9_-]*:[^{}\\s\"\\\\]*}");
    private static final Set<String> BLOCK_LABELS = Set.of("else", "is", "case");

    /** Parses the rendered body for the structural check: strict, lossless. */
    private static final ObjectMapper CHECK_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    /** Writes canonical JSON: decimals in plain notation, digits unchanged. */
    private static final ObjectMapper PLAIN_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private ToolArgumentBodyEscaper() {
    }

    /**
     * A model argument cannot be placed in the request; the message is safe to give
     * the model (it names the argument, the header or the expectation, never the
     * value).
     */
    public static final class ArgumentRefusedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ArgumentRefusedException(String message) {
            super(message);
        }
    }

    /** Renders a template against data — {@code PrePostUtils#templateValues}. */
    @FunctionalInterface
    public interface Renderer {
        String render(String template, Map<String, Object> data) throws ITemplatingEngine.TemplateEngineException;
    }

    /**
     * Whether a call's content type means the body is a JSON document: the media
     * type alone, parameters stripped, {@code application/json}, {@code text/json}
     * or any {@code +json} suffix.
     */
    public static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mediaType.equals("application/json") || mediaType.equals("text/json") || mediaType.endsWith("+json");
    }

    /**
     * The guard for one tool invocation of {@code call}, or {@code null} when there
     * is nothing to guard: escaping opted out, no request, no model argument, or
     * neither a JSON body that mentions an argument nor a templated header.
     *
     * @param argumentNames
     *            the template-data keys that came from the model
     * @param typedValues
     *            each typed argument as the schema check validated it (a typed
     *            call), empty for an untyped call — the source of a bare argument's
     *            canonical JSON
     */
    public static Guard guard(ApiCall call, Set<String> argumentNames, Map<String, JsonNode> typedValues) {
        Request request = call.getRequest();
        if (request == null || !call.escapesToolArguments() || argumentNames.isEmpty()) {
            return null;
        }
        String body = request.getBody();
        boolean bodyGuarded = body != null && !body.isEmpty() && isJsonContentType(request.getContentType())
                && !WHOLE_BODY_TEMPLATE.matcher(body.trim()).matches();
        boolean headersTemplated = request.getHeaders() != null
                && request.getHeaders().values().stream().anyMatch(value -> value != null && value.indexOf('{') >= 0);
        if (!bodyGuarded && !headersTemplated) {
            return null;
        }
        return new Guard(bodyGuarded ? body : null, Set.copyOf(argumentNames), Map.copyOf(typedValues));
    }

    /**
     * The guard of one tool call: renders its body safely and judges its rendered
     * headers. Immutable and shared by the gate-time resolution and the execution
     * of the same call.
     */
    public static final class Guard {
        private final String body;
        private final Set<String> names;
        private final Map<String, JsonNode> typed;

        private Guard(String body, Set<String> names, Map<String, JsonNode> typed) {
            this.body = body;
            this.names = names;
            this.typed = typed;
        }

        /** The model arguments this guard protects. */
        public Set<String> argumentNames() {
            return names;
        }

        /**
         * Whether the body is rendered through {@link #renderBody}: a JSON body that is
         * not a single whole-body variable. Otherwise only the headers are judged.
         */
        public boolean guardsBody() {
            return body != null;
        }

        /**
         * The guard for one element of a {@code forEach} or batch over
         * {@code pathToTargetArray}: when the path starts at a model argument, the
         * element bound as {@code iterationName} is model text too.
         */
        public Guard forIteration(String pathToTargetArray, String iterationName) {
            if (pathToTargetArray == null || iterationName == null || iterationName.isBlank()) {
                return this;
            }
            String path = pathToTargetArray.trim();
            int end = 0;
            while (end < path.length() && isIdentifierPart(path.charAt(end))) {
                end++;
            }
            if (!names.contains(path.substring(0, end))) {
                return this;
            }
            Set<String> withItem = new LinkedHashSet<>(names);
            withItem.add(iterationName.trim());
            return new Guard(body, withItem, typed);
        }

        /**
         * Refuses up front what is certain to be refused: a bare {@code {arg}} whose
         * value cannot be written as JSON. Run where the template data is prepared, so
         * a refusal reaches the model (and the approval gate) before anything is
         * resolved or mocked.
         */
        public void validate(Map<String, Object> data) {
            if (body == null) {
                return;
            }
            for (String name : analyse(body, names).bare()) {
                bareJson(name, data.get(name), typed.get(name));
            }
        }

        /**
         * The body to send.
         *
         * @throws ArgumentRefusedException
         *             a bare argument that is not valid JSON, or a body whose structure
         *             an argument changed (or that is not JSON once the arguments are
         *             in)
         */
        public String renderBody(Map<String, Object> data, Renderer renderer) throws ITemplatingEngine.TemplateEngineException {
            if (body == null) {
                return null;
            }
            Analysis analysis = analyse(body, names);
            if (!analysis.mentions()) {
                return renderer.render(body, data);
            }
            Map<String, Object> escaped = new LinkedHashMap<>(data);
            Map<String, Object> inert = new LinkedHashMap<>(data);
            Inert stand = new Inert();
            for (String name : names) {
                Object value = data.get(name);
                escaped.put(name, escapeLeaves(value));
                inert.put(name, stand.of(value, true));
            }
            for (String name : analysis.raw()) {
                Object value = data.get(name);
                escaped.put(RAW_PREFIX + name, value);
                inert.put(RAW_PREFIX + name, stand.of(value, false));
            }
            for (String name : analysis.bare()) {
                escaped.put(VALUE_PREFIX + name, bareJson(name, data.get(name), typed.get(name)));
                inert.put(VALUE_PREFIX + name, WILDCARD_JSON);
            }
            String sent = renderer.render(analysis.rewritten(), escaped);
            if (sent == null || sent.isBlank()) {
                return sent;
            }
            JsonNode actual = parseOrNull(sent);
            if (actual == null) {
                throw refused("not_json", "The request was NOT sent: with the arguments substituted, the request body is not valid "
                        + "JSON. Send plain values; if they are plain already, the tool's body template needs fixing.");
            }
            String benign;
            try {
                benign = renderer.render(analysis.rewritten(), inert);
            } catch (ITemplatingEngine.TemplateEngineException | RuntimeException e) {
                benign = null;
            }
            JsonNode expected = benign == null ? null : parseOrNull(benign);
            if (expected == null || !sameStructure(expected, actual)) {
                throw refused("structure", "The request was NOT sent: an argument value would change the structure of the JSON "
                        + "request body. A value used outside a JSON string may only be a plain number, boolean or single word; "
                        + "send simpler values.");
            }
            return sent;
        }

        /**
         * A rendered header value with a control character (CR, LF, NUL, ...) would
         * split or corrupt the header block. Judged on the rendered value, so no alias
         * or namespace can carry one past it.
         */
        public void checkHeader(String headerName, String renderedValue) {
            if (renderedValue != null && containsControlCharacter(renderedValue)) {
                throw refused("header", "The request was NOT sent: a value substituted into the request header '" + headerName
                        + "' contains a control character (such as a line break), which a header cannot carry. Send the value "
                        + "without control characters.");
            }
        }
    }

    private static ArgumentRefusedException refused(String reason, String message) {
        Metrics.globalRegistry.counter(REFUSED_METRIC, "reason", reason).increment();
        return new ArgumentRefusedException(message);
    }

    /**
     * What a deployment should know about {@code body} as a tool body: every
     * construct that reads a model argument outside a JSON string in a form other
     * than a plain {@code {arg}} — those are checked per call and refused when the
     * value is more than one JSON token. Names constructs, never values; empty when
     * there is nothing to say.
     */
    public static List<String> deployTimeFindings(String body, Set<String> argumentNames) {
        if (body == null || body.isEmpty() || argumentNames.isEmpty() || WHOLE_BODY_TEMPLATE.matcher(body.trim()).matches()) {
            return List.of();
        }
        return analyse(body, argumentNames).findings();
    }

    /**
     * Whether a {@code {...}} expression reads a tool argument at its root —
     * {@code {name}}, {@code {name.first}}, {@code {data:name}} — and so is escaped
     * on the tool path.
     */
    public static boolean isArgumentExpression(String expression, Set<String> argumentNames) {
        String inner = expression.startsWith("{") && expression.endsWith("}") ? expression.substring(1, expression.length() - 1) : expression;
        if (inner.startsWith("data:")) {
            inner = inner.substring("data:".length());
        }
        return argumentNames.contains(leadingIdentifier(inner));
    }

    /**
     * Canonical JSON for a non-textual tool argument: decimals in plain notation
     * with their digits unchanged ({@code 0.0000001}, not {@code 1E-7}).
     */
    public static String plainJson(JsonNode node) {
        try {
            return PLAIN_JSON.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // ------------------------------------------------------------- analysis

    /**
     * The body template rewritten for the escaped render, and what it reads.
     *
     * @param rewritten
     *            plain bare {@code {arg}} pointed at {@code eddiJsonVal_arg},
     *            arguments inside {@code json:} pointed at {@code eddiJsonRaw_arg}
     * @param bare
     *            arguments read as a plain bare {@code {arg}}
     * @param raw
     *            arguments read inside a {@code json:} expression
     * @param mentions
     *            whether any tag of the template mentions an argument at all
     * @param findings
     *            constructs worth a deploy-time warning
     */
    record Analysis(String rewritten, Set<String> bare, Set<String> raw, boolean mentions, List<String> findings) {
    }

    private static final class Section {
        final String tag;
        final Set<String> aliases;
        final boolean shadowsAll;
        final boolean readsArgument;
        boolean reported;

        Section(String tag, Set<String> aliases, boolean shadowsAll, boolean readsArgument) {
            this.tag = tag;
            this.aliases = aliases;
            this.shadowsAll = shadowsAll;
            this.readsArgument = readsArgument;
        }
    }

    /**
     * One linear pass over the template that tracks JSON string state (unescaped
     * quotes), Qute comments ({@code {! !}}), unparsed blocks ({@code {| |}}), the
     * Qute brace escape ({@code \{}) and the aliases that sections declare. {@code
     * mentions} errs on the side of yes: it only decides whether the guarded render
     * runs, and running it where it was not needed costs a render, while skipping
     * it where it was needed would send raw values.
     */
    static Analysis analyse(String body, Set<String> names) {
        StringBuilder out = new StringBuilder(body.length() + 32);
        Set<String> bare = new LinkedHashSet<>();
        Set<String> raw = new LinkedHashSet<>();
        Set<String> findings = new LinkedHashSet<>();
        Deque<Section> sections = new ArrayDeque<>();
        boolean mentions = false;
        boolean inString = false;
        int n = body.length();
        int i = 0;
        while (i < n) {
            char c = body.charAt(i);
            if (c == '\\') {
                // Inside a JSON string: a JSON escape, two characters. Outside: only the
                // Qute brace escape matters, and it makes the brace literal text.
                int span = i + 1 < n && (inString || body.charAt(i + 1) == '{') ? 2 : 1;
                out.append(body, i, i + span);
                i += span;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                i++;
                continue;
            }
            if (c != '{' || i + 1 >= n) {
                out.append(c);
                i++;
                continue;
            }
            char next = body.charAt(i + 1);
            if (next == '!') {
                int close = body.indexOf("!}", i + 2);
                int stop = close < 0 ? n : close + 2;
                // Over-approximated: a name in a comment still counts as a mention, so
                // a misread of Qute's syntax can only make the guard run, never skip it.
                mentions |= mentionsAny(body.substring(i + 2, close < 0 ? n : close), names, sections);
                out.append(body, i, stop);
                i = stop;
                continue;
            }
            if (next == '|') {
                int close = body.indexOf("|}", i + 2);
                int contentEnd = close < 0 ? n : close;
                mentions |= mentionsAny(body.substring(i + 2, contentEnd), names, sections);
                // Literal output: its quotes are JSON quotes, its braces are not tags.
                for (int k = i + 2; k < contentEnd; k++) {
                    char u = body.charAt(k);
                    if (inString && u == '\\') {
                        k++;
                    } else if (u == '"') {
                        inString = !inString;
                    }
                }
                int stop = close < 0 ? n : close + 2;
                out.append(body, i, stop);
                i = stop;
                continue;
            }
            int end = body.indexOf('}', i + 1);
            if (end < 0) {
                out.append(body, i, n);
                break;
            }
            String tag = body.substring(i + 1, end);
            if (next == '#') {
                String content = tag.substring(1).trim();
                String sectionName = leadingIdentifier(content);
                String params = content.substring(sectionName.length()).trim();
                boolean readsArgument = mentionsAny(readPart(sectionName, params), names, sections);
                mentions |= readsArgument;
                boolean selfClosing = params.endsWith("/");
                if (!BLOCK_LABELS.contains(sectionName) && !selfClosing) {
                    sections.push(section("{" + tag + "}", sectionName, params, readsArgument));
                }
                out.append(body, i, end + 1);
                i = end + 1;
                continue;
            }
            if (next == '/') {
                if (!sections.isEmpty()) {
                    sections.pop();
                }
                out.append(body, i, end + 1);
                i = end + 1;
                continue;
            }
            if (!isIdentifierStart(next)) {
                out.append(c);
                i++;
                continue;
            }
            out.append('{').append(expression(tag, inString, names, sections, bare, raw, findings)).append('}');
            mentions |= mentionsAny(tag, names, sections) || expressionRootIsArgument(tag, names, sections);
            i = end + 1;
        }
        return new Analysis(out.toString(), bare, raw, mentions, List.copyOf(findings));
    }

    /**
     * The part of a section's parameters that reads data: what follows {@code in}
     * of a {@code for}, the right-hand sides of a {@code let}/{@code set} — the
     * names an alias declares are not reads.
     */
    private static String readPart(String sectionName, String params) {
        if ("for".equals(sectionName)) {
            int in = params.indexOf(" in ");
            return in < 0 ? params : params.substring(in + 4);
        }
        if ("let".equals(sectionName) || "set".equals(sectionName)) {
            StringBuilder read = new StringBuilder();
            for (String assignment : params.split("\\s+")) {
                int eq = assignment.indexOf('=');
                read.append(eq < 0 ? assignment : assignment.substring(eq + 1)).append(' ');
            }
            return read.toString();
        }
        return params;
    }

    private static Section section(String tag, String sectionName, String params, boolean readsArgument) {
        Set<String> aliases = new HashSet<>();
        boolean shadowsAll = false;
        switch (sectionName) {
            case "for" -> {
                String alias = leadingIdentifier(params);
                if (!alias.isEmpty()) {
                    aliases.add(alias);
                }
            }
            case "each" -> aliases.add("it");
            case "let", "set" -> {
                for (String assignment : params.split("\\s+")) {
                    int eq = assignment.indexOf('=');
                    if (eq > 0) {
                        aliases.add(assignment.substring(0, eq).trim());
                    }
                }
            }
            case "with" -> shadowsAll = true;
            default -> {
                // if, when, switch and anything else declare no alias.
            }
        }
        return new Section(tag, aliases, shadowsAll, readsArgument);
    }

    /** Rewrites one {@code {...}} expression and records what it reads. */
    private static String expression(String expression, boolean inString, Set<String> names, Deque<Section> sections, Set<String> bare,
                                     Set<String> raw, Set<String> findings) {
        Matcher namespace = NAMESPACE.matcher(expression);
        String target = expression;
        String prefix = "";
        if (namespace.find()) {
            String ns = namespace.group(1);
            if ("json".equals(ns)) {
                return rawReferences(expression, names, sections, raw);
            }
            if (!"data".equals(ns)) {
                if (!inString && mentionsAny(expression, names, sections)) {
                    findings.add("{" + expression + "} reads a tool argument outside a JSON string");
                }
                return expression;
            }
            prefix = "data:";
            target = expression.substring(namespace.end());
        }
        String root = leadingIdentifier(target);
        String rest = target.substring(root.length());
        boolean argumentRoot = names.contains(root) && !shadowed(root, sections);
        if (!inString) {
            if (argumentRoot && rest.isBlank() && !insideWith(sections)) {
                bare.add(root);
                return VALUE_PREFIX + root;
            }
            if (argumentRoot || mentionsAny(expression, names, sections)) {
                findings.add("{" + expression + "} reads a tool argument outside a JSON string in a compound expression");
            } else {
                for (Section section : sections) {
                    if (section.readsArgument && !section.reported && (section.shadowsAll || section.aliases.contains(root))) {
                        section.reported = true;
                        findings.add(section.tag + " reads a tool argument, and {" + expression + "} inside it uses the result "
                                + "outside a JSON string");
                    }
                }
            }
        }
        return prefix + target;
    }

    /** Inside {@code json:}: argument identifiers read the raw value. */
    private static String rawReferences(String expression, Set<String> names, Deque<Section> sections, Set<String> raw) {
        StringBuilder out = new StringBuilder(expression.length() + 16);
        int i = 0;
        char quote = 0;
        while (i < expression.length()) {
            char c = expression.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                out.append(c);
                i++;
                continue;
            }
            if (isIdentifierStart(c) && (i == 0 || !isIdentifierPart(expression.charAt(i - 1)) && expression.charAt(i - 1) != '.')) {
                int end = i;
                while (end < expression.length() && isIdentifierPart(expression.charAt(end))) {
                    end++;
                }
                String word = expression.substring(i, end);
                boolean namespaceName = end < expression.length() && expression.charAt(end) == ':';
                if (!namespaceName && names.contains(word) && !shadowed(word, sections) && !insideWith(sections)) {
                    raw.add(word);
                    out.append(RAW_PREFIX).append(word);
                } else {
                    out.append(word);
                }
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean expressionRootIsArgument(String expression, Set<String> names, Deque<Section> sections) {
        String target = expression.startsWith("data:") ? expression.substring(5) : expression;
        String root = leadingIdentifier(target);
        return names.contains(root) && !shadowed(root, sections);
    }

    /**
     * Whether {@code text} names an argument as an identifier of its own (not as a
     * property after a dot, not as a namespace).
     */
    private static boolean mentionsAny(String text, Set<String> names, Deque<Section> sections) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (isIdentifierStart(c) && (i == 0 || !isIdentifierPart(text.charAt(i - 1)) && text.charAt(i - 1) != '.')) {
                int end = i;
                while (end < text.length() && isIdentifierPart(text.charAt(end))) {
                    end++;
                }
                String word = text.substring(i, end);
                boolean namespaceName = end < text.length() && text.charAt(end) == ':' && !"data".equals(word);
                if (!namespaceName && names.contains(word) && !shadowed(word, sections)) {
                    return true;
                }
                i = end;
                continue;
            }
            i++;
        }
        return false;
    }

    /** Whether an enclosing section declares {@code name} as its own alias. */
    private static boolean shadowed(String name, Deque<Section> sections) {
        for (Section section : sections) {
            if (section.aliases.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inside {@code {#with x}} a bare name may resolve to a property of {@code x}
     * as well as to the argument, so nothing there is rewritten — it reads the
     * escaped data and the structural check judges it.
     */
    private static boolean insideWith(Deque<Section> sections) {
        for (Section section : sections) {
            if (section.shadowsAll) {
                return true;
            }
        }
        return false;
    }

    private static String leadingIdentifier(String text) {
        int end = 0;
        while (end < text.length() && isIdentifierPart(text.charAt(end))) {
            end++;
        }
        return end > 0 && isIdentifierStart(text.charAt(0)) ? text.substring(0, end) : "";
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 && c != '\t' || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------- escaped values

    /**
     * The argument with every string, every map key and the text of every map or
     * list JSON-string-escaped: whatever part of it a template prints, no quote,
     * backslash or control character survives unescaped.
     */
    static Object escapeLeaves(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String s) {
            return escapeContent(s);
        }
        if (value instanceof Map<?, ?> map) {
            TextMap copy = new TextMap(() -> escapeContent(canonicalText(map)));
            map.forEach((k, v) -> copy.put(escapeContent(String.valueOf(k)), escapeLeaves(v)));
            return copy;
        }
        if (value instanceof Iterable<?> items) {
            TextList copy = new TextList(() -> escapeContent(canonicalText(items)));
            items.forEach(item -> copy.add(escapeLeaves(item)));
            return copy;
        }
        return escapeContent(String.valueOf(value));
    }

    static String escapeContent(String s) {
        return new String(JsonStringEncoder.getInstance().quoteAsString(s));
    }

    private static String canonicalText(Object value) {
        try {
            return PLAIN_JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * A map whose own text — what a template prints for {@code {meta}} — is given,
     * computed only when printed (a nested argument would otherwise cost its whole
     * text at every level).
     */
    static final class TextMap extends LinkedHashMap<Object, Object> {
        private static final long serialVersionUID = 1L;
        private final transient Supplier<String> text;

        TextMap(Supplier<String> text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text.get();
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    /** A list whose own text is given, computed only when printed. */
    static final class TextList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        private final transient Supplier<String> text;

        TextList(Supplier<String> text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text.get();
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o);
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }
    }

    // -------------------------------------------------------- inert values

    /**
     * Builds the stand-ins of the second render: the same shapes and lengths as the
     * escaped values, with every string that could form JSON structure replaced by
     * private-use characters. A string made only of letters, digits and
     * {@code . _ + -} is kept as it is: outside a string it is at most one JSON
     * token, so it cannot change the structure, and keeping it keeps conditions
     * such as {@code {#if mode == 'asc'}} taking the same branch.
     */
    private static final class Inert {
        private int keys;

        Object of(Object value, boolean escapedLengths) {
            if (value == null || value instanceof Number || value instanceof Boolean) {
                return value;
            }
            if (value instanceof String s) {
                return string(s, escapedLengths);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new TextMap(() -> inert(textLength(map, escapedLengths), 0));
                map.forEach((k, v) -> copy.put(key(String.valueOf(k)), of(v, escapedLengths)));
                return copy;
            }
            if (value instanceof Iterable<?> items) {
                List<Object> copy = new TextList(() -> inert(textLength(items, escapedLengths), 0));
                items.forEach(item -> copy.add(of(item, escapedLengths)));
                return copy;
            }
            return string(String.valueOf(value), escapedLengths);
        }

        private static int textLength(Object container, boolean escapedLengths) {
            String text = canonicalText(container);
            return escapedLengths ? escapeContent(text).length() : text.length();
        }

        private static String string(String s, boolean escapedLengths) {
            if (isSingleToken(s)) {
                return s;
            }
            return inert(escapedLengths ? escapeContent(s).length() : s.length(), 0);
        }

        private String key(String key) {
            if (isSingleToken(key) && !key.isEmpty()) {
                return key;
            }
            return inert(Math.max(1, escapeContent(key).length()), keys++);
        }

        private static String inert(int length, int seed) {
            if (length <= 0) {
                return "";
            }
            StringBuilder s = new StringBuilder(length);
            s.append((char) (INERT + seed % (INERT_LAST - INERT + 1)));
            for (int i = 1; i < length; i++) {
                s.append(INERT);
            }
            return s.toString();
        }
    }

    static boolean isSingleToken(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '+'
                    || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------- structural check

    private static JsonNode parseOrNull(String text) {
        try {
            JsonNode node = CHECK_JSON.readTree(CONFIG_REFERENCE.matcher(text).replaceAll("0"));
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether {@code actual} has the structure of {@code expected}, the inert
     * render: the same kind of node everywhere; objects with the same keys in the
     * same order (a key may differ only where the inert key is argument-derived);
     * arrays of the same length; author-written leaves equal; and at an
     * argument-derived string any string. A wildcard leaf is a canonical bare slot:
     * one value this class serialised itself.
     */
    static boolean sameStructure(JsonNode expected, JsonNode actual) {
        if (expected.isTextual() && WILDCARD.equals(expected.textValue())) {
            return true;
        }
        if (expected.getNodeType() != actual.getNodeType()) {
            return false;
        }
        if (expected.isTextual()) {
            return isArgumentDerived(expected.textValue()) || expected.textValue().equals(actual.textValue());
        }
        if (expected.isObject()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> e = expected.fields();
            Iterator<Map.Entry<String, JsonNode>> a = actual.fields();
            while (e.hasNext()) {
                var expectedField = e.next();
                var actualField = a.next();
                if (!isArgumentDerived(expectedField.getKey()) && !expectedField.getKey().equals(actualField.getKey())) {
                    return false;
                }
                if (!sameStructure(expectedField.getValue(), actualField.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) {
                return false;
            }
            for (int i = 0; i < expected.size(); i++) {
                if (!sameStructure(expected.get(i), actual.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return expected.equals(actual);
    }

    private static boolean isArgumentDerived(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= INERT && c <= INERT_LAST) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------- bare values

    /**
     * The argument as the canonical JSON text of a bare value position.
     * <ul>
     * <li>a typed call: the value as the schema check validated it, serialised
     * (strings quoted, numbers with their digits);</li>
     * <li>an untyped call: a non-string as its JSON; a string that is valid JSON as
     * that JSON (a number keeps its text exactly); any other string quoted.</li>
     * </ul>
     * A non-finite number (beyond the range of a double) is refused.
     */
    static String bareJson(String name, Object value, JsonNode typedValue) {
        JsonNode node;
        if (typedValue != null) {
            node = typedValue;
        } else if (value == null) {
            return "null";
        } else if (value instanceof String text) {
            JsonNode parsed = parseValueOrNull(text);
            if (parsed == null) {
                return "\"" + escapeContent(text) + "\"";
            }
            requireFinite(name, parsed);
            return parsed.isNumber() ? text.trim() : plainJson(parsed);
        } else {
            try {
                node = PLAIN_JSON.valueToTree(value);
            } catch (IllegalArgumentException e) {
                throw refused("bare_value", "Argument '" + name + "' cannot be written as a JSON value. The request was NOT sent.");
            }
        }
        requireFinite(name, node);
        return plainJson(node);
    }

    private static JsonNode parseValueOrNull(String text) {
        try {
            JsonNode node = CHECK_JSON.readTree(text);
            return node == null || node.isMissingNode() ? null : node;
        } catch (Exception e) {
            return null;
        }
    }

    private static void requireFinite(String name, JsonNode node) {
        if (node.isNumber()) {
            double d = node.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw refused("bare_value", "Argument '" + name + "' is a number too large to send. The request was NOT sent.");
            }
            return;
        }
        if (node.isContainerNode()) {
            Collection<JsonNode> children = new ArrayList<>();
            node.elements().forEachRemaining(children::add);
            for (JsonNode child : children) {
                requireFinite(name, child);
            }
        }
    }
}
