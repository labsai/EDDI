/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one read-redaction every configuration store applies before it hands a
 * document to a caller who is not an {@code eddi-admin}.
 * <p>
 * <h3>Why it exists</h3> Editors author configurations, but the credentials in
 * them are the administrator's: {@code /secretstore} is admin-only and the
 * Manager never shows a vault value. A store that returns a plaintext literal
 * verbatim undoes that — an editor who could not read the vault reads the key
 * straight out of an LLM, MCP, channel, RAG or httpcalls document instead. The
 * LLM store was the first to redact (L12 of the 2026-10-15 review); the
 * 2026-10-17 review (M1) found the same literal readable in every other kind of
 * document, and in the LLM fields that fix did not reach. One shared walk
 * replaces the per-store patches, so a new credential field in any of them is
 * covered by the same rules rather than by whoever remembers to add it.
 * <p>
 * <h3>What is redacted</h3>
 * <ul>
 * <li>a string under a <b>credential-named field</b> ({@code apiKey},
 * {@code botToken}, {@code signingSecret}, {@code password},
 * {@code clientSecret}, {@code connectionString}, … — see
 * {@link #isCredentialName}) is replaced whole by {@link #REDACTED};</li>
 * <li>inside a <b>header map</b> (a field whose name ends in {@code headers}),
 * a header named like a credential ({@code Authorization}, {@code X-Api-Key},
 * {@code Ocp-Apim-Subscription-Key}, {@code Cookie}) is replaced whole; any
 * other header value is shape-scanned;</li>
 * <li>inside a <b>query-parameter map</b>, a credential-named parameter is
 * replaced whole and any other is shape-scanned;</li>
 * <li>a <b>URL</b> (anything carrying {@code ://}, and every field called
 * {@code path}, {@code url} or {@code uri}) has its userinfo password and its
 * credential-named query values replaced, and the rest shape-scanned;</li>
 * <li>a field called <b>{@code body}</b> is shape-scanned by
 * {@link SecretRedactionFilter}, the same scan approval cards and logs
 * use.</li>
 * </ul>
 * In-string replacements use {@link #IN_STRING_REDACTED} so the surrounding URL
 * or body stays readable and a write that echoes it is still refused.
 * <p>
 * <h3>What is never redacted</h3> A reference — {@code ${vault:…}},
 * {@code ${vars:…}}, {@code ${connection:…}}, {@code ${caller:…}} — and a
 * template ({@code {properties.apiKey}}) are pointers, not secrets; an editor
 * needs to see which key a config uses. A value made only of references plus an
 * authorization-scheme word ({@code "Bearer ${vault:k}"}) passes too. Whatever
 * is left after the references are removed is judged on its own, so
 * {@code "sk-live-… ${vault:k}"} is still a literal.
 * <p>
 * <h3>Round trip</h3> A redacted document that an editor saves back carries the
 * marker in place of the literal, which then fails at the provider rather than
 * silently keeping the old value. The store does not merge the stored literal
 * back on write on purpose: the editor could move the marker next to a host of
 * their choosing and have the server fill in the secret for them — the H1
 * exfiltration with extra steps.
 * <p>
 * Stateless and static, like {@link UriRedactor}; the stored object is never
 * touched, because a store may hand out a cached instance.
 */
public final class ConfigSecretRedactor {

    /**
     * What a non-administrator reads in place of a whole plaintext credential. It
     * is not a key, so a document echoed back with it fails at the provider rather
     * than quietly keeping the literal.
     */
    public static final String REDACTED = "<redacted: a plaintext credential - an eddi-admin must move it into the vault and reference it as ${vault:...}>";

    /**
     * The marker written inside a URL, header or body value. Deliberately not
     * {@link SecretRedactionFilter#REDACTED}: that one appears in logs and approval
     * cards, and could legitimately be quoted in a prompt or output template. This
     * one shares {@link #MARKER_PREFIX} with {@link #REDACTED}, so a write that
     * carries either can be recognised and refused.
     */
    public static final String IN_STRING_REDACTED = "<redacted: credential>";

    /**
     * What both markers begin with. A document being written that contains it
     * anywhere was read through a redacted view and must not be stored — see
     * {@link #markerPaths}.
     */
    public static final String MARKER_PREFIX = "<redacted: ";

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * A reference ({@code ${vault:…}}, {@code ${vars.x}}, …) or a Qute template
     * ({@code {properties.key}}). The template arm requires a letter or underscore
     * after the brace, so {@code hunter{2}} is not mistaken for one. Possessive
     * classes bounded by a literal brace: linear on any input.
     */
    private static final Pattern REFERENCE_OR_TEMPLATE = Pattern.compile("\\$\\{[^{}]*+}|\\{[A-Za-z_][^{}]*+}");

    /** Splits a reference remainder into words for the scaffolding test. */
    private static final Pattern NON_WORD = Pattern.compile("[^A-Za-z0-9-]++");

    /**
     * Words that may sit beside a reference in a credential field: {@code "Bearer
     * ${vault:k}"}, {@code "Basic ${vault:u}:${vault:p}"}.
     */
    private static final Set<String> SCHEME_WORDS = Set.of("bearer", "basic", "digest", "token", "bot", "apikey", "api-key", "key", "ssws",
            "oauth", "negotiate", "hmac");

    /** Whole (normalized) names that hold a credential wherever they appear. */
    private static final Set<String> CREDENTIAL_NAMES = Set.of("apikey", "apikeys", "token", "secret", "secrets", "password", "passwd", "pwd",
            "passphrase", "credential", "credentials", "authorization", "connectionstring", "nonazureapikey", "secretaccesskey", "privatekey",
            "clientsecret", "signingsecret", "privatekeypem");

    /**
     * Name endings that mark a credential. {@code token} is matched SINGULAR only:
     * {@code maxTokens}, {@code thinkingBudgetTokens} and every other count of
     * tokens ends in {@code tokens}, while {@code botToken}, {@code accessToken}
     * and {@code authToken} end in {@code token}.
     */
    private static final List<String> CREDENTIAL_SUFFIXES = List.of("apikey", "token", "secret", "password", "passwd", "passphrase",
            "privatekey", "secretkey", "credential", "credentials");

    /** A first word that turns a credential noun into a quantity of them. */
    private static final Set<String> QUANTITY_WORDS = Set.of("max", "min", "num", "number", "total", "budget", "count");

    /** Query-parameter names that carry a credential beyond the header rule. */
    private static final Set<String> CREDENTIAL_QUERY_NAMES = Set.of("key", "sig", "signature", "subscriptionkey", "accesskey",
            "appkey", "licensekey", "clientkey", "signingkey");

    /** Field names whose string value is a URL or a path with a query. */
    private static final Set<String> URL_FIELD_NAMES = Set.of("path", "url", "uri");

    /**
     * {@code scheme://} near the start of a value. The scheme class admits
     * {@code :} ({@code jdbc:postgresql://}), so it cannot be possessive; it is
     * bounded instead, which caps the backtracking at forty steps.
     */
    private static final Pattern HAS_SCHEME = Pattern.compile("^\\s*+[A-Za-z][A-Za-z0-9+.:-]{0,40}://");

    /**
     * Sentinels that stand in for a reference while a value is scanned, so a scan
     * built for resolved requests (which redacts a {@code ${vault:…}}) never eats
     * the pointer. Private-use code points, so they collide with nothing a config
     * holds.
     */
    private static final char SENTINEL_OPEN = '';
    private static final char SENTINEL_CLOSE = '';
    private static final Pattern SENTINEL = Pattern.compile(SENTINEL_OPEN + "([0-9]{1,6}+)" + SENTINEL_CLOSE);

    private ConfigSecretRedactor() {
    }

    /**
     * A copy of {@code config} with every plaintext credential redacted, or
     * {@code config} itself when there is none.
     *
     * @param skippedPaths
     *            container paths, with {@code []} for any array index (for example
     *            {@code httpCalls[].parameters}), whose content is not
     *            configuration at all — the tool-parameter descriptions of an
     *            httpcall, keyed by names such as {@code token} — and is left alone
     */
    public static <T> T redact(T config, Class<T> type, Set<String> skippedPaths) {
        if (config == null) {
            return null;
        }
        JsonNode tree = MAPPER.valueToTree(config);
        List<String> found = new ArrayList<>();
        walk(tree, "", "", null, skippedPaths == null ? Set.of() : skippedPaths, found, true);
        if (found.isEmpty()) {
            return config;
        }
        try {
            return MAPPER.treeToValue(tree, type);
        } catch (JsonProcessingException e) {
            // A document that serialized a moment ago must deserialize; if it does
            // not, failing the read is the safe answer — never the unredacted copy.
            throw new IllegalStateException("Could not rebuild the redacted " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    /** {@link #redact(Object, Class, Set)} with nothing skipped. */
    public static <T> T redact(T config, Class<T> type) {
        return redact(config, type, Set.of());
    }

    /**
     * {@link #redact(Object, Class, Set)} for a document held as a JSON string —
     * the content of an upgrade or sync preview. Returns {@code json} itself when
     * nothing in it is a credential, and keeps its indentation otherwise. Text that
     * is not a JSON document is shape-scanned by {@link SecretRedactionFilter}
     * instead; it is never returned as it came.
     */
    public static String redactJson(String json, Set<String> skippedPaths) {
        if (json == null || json.isBlank()) {
            return json;
        }
        JsonNode tree;
        try {
            tree = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            return SecretRedactionFilter.redact(json);
        }
        List<String> found = new ArrayList<>();
        walk(tree, "", "", null, skippedPaths == null ? Set.of() : skippedPaths, found, true);
        if (found.isEmpty()) {
            return json;
        }
        try {
            return json.indexOf('\n') >= 0
                    ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tree)
                    : MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            return tree.toString();
        }
    }

    /**
     * Where {@code config} holds a plaintext credential, as paths such as
     * {@code tasks[0].parameters.apiKey} — for a write-time warning. Never the
     * value.
     */
    public static List<String> plaintextSecretPaths(Object config, Set<String> skippedPaths) {
        List<String> found = new ArrayList<>();
        if (config == null) {
            return found;
        }
        walk(MAPPER.valueToTree(config), "", "", null, skippedPaths == null ? Set.of() : skippedPaths, found, false);
        return found;
    }

    private static void walk(JsonNode node, String path, String pattern, String containerName, Set<String> skipped, List<String> found,
                             boolean rewrite) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                String name = field.getKey();
                String childPath = path.isEmpty() ? name : path + "." + name;
                String childPattern = pattern.isEmpty() ? name : pattern + "." + name;
                if (skipped.contains(childPattern)) {
                    continue;
                }
                JsonNode value = field.getValue();
                if (value.isTextual()) {
                    String replacement = judge(name, containerName, value.asText());
                    if (replacement != null) {
                        found.add(childPath);
                        if (rewrite) {
                            field.setValue(new TextNode(replacement));
                        }
                    }
                } else {
                    walk(value, childPath, childPattern, name, skipped, found, rewrite);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode element = array.get(i);
                String childPath = path + "[" + i + "]";
                if (element.isTextual()) {
                    // An element has no name of its own; it is judged by the array's —
                    // "apiKeys": ["sk-…"] is a list of credentials.
                    String replacement = judge(containerName, null, element.asText());
                    if (replacement != null) {
                        found.add(childPath);
                        if (rewrite) {
                            array.set(i, new TextNode(replacement));
                        }
                    }
                } else {
                    walk(element, childPath, pattern + "[]", containerName, skipped, found, rewrite);
                }
            }
        }
    }

    /**
     * What one string becomes, or {@code null} to leave it as it is.
     *
     * @param name
     *            the field (or map key) the value sits under
     * @param containerName
     *            the name of the enclosing object, which says whether this is a
     *            header or a query-parameter map
     */
    static String judge(String name, String containerName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String container = containerName == null ? "" : normalize(containerName);
        if (container.endsWith("headers")) {
            if (isCredentialHeaderName(name)) {
                return isLiteral(value) ? REDACTED : null;
            }
            return changedOrNull(value, scanText(value));
        }
        if (container.endsWith("queryparams")) {
            if (isCredentialQueryName(name)) {
                return isLiteral(value) ? REDACTED : null;
            }
            return changedOrNull(value, scanText(value));
        }
        if (isCredentialName(name)) {
            return isLiteral(value) ? REDACTED : null;
        }
        String normalizedName = name == null ? "" : normalize(name);
        if (HAS_SCHEME.matcher(value).find() || URL_FIELD_NAMES.contains(normalizedName) || normalizedName.endsWith("url")) {
            return changedOrNull(value, redactUrl(value));
        }
        if ("body".equals(normalizedName)) {
            return changedOrNull(value, scanText(value));
        }
        return null;
    }

    private static String changedOrNull(String original, String redacted) {
        return redacted.equals(original) ? null : redacted;
    }

    /**
     * Whether a value in a credential field is a literal rather than references,
     * templates and scheme words.
     */
    static boolean isLiteral(String value) {
        String remainder = REFERENCE_OR_TEMPLATE.matcher(value).replaceAll(" ");
        for (String word : NON_WORD.split(remainder)) {
            if (!word.isEmpty() && !SCHEME_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a field or map key names a credential. Separators and case are
     * ignored, a plural {@code tokens} is a count rather than a credential, and a
     * name whose first word is a quantity ({@code maxToken}) is a number.
     */
    static boolean isCredentialName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = normalize(name);
        if (CREDENTIAL_NAMES.contains(normalized)) {
            return true;
        }
        for (String suffix : CREDENTIAL_SUFFIXES) {
            if (normalized.endsWith(suffix) && !startsWithQuantity(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a header's name says it carries a credential: the shared header rule,
     * plus any name ending in {@code key} ({@code X-Api-Key},
     * {@code Ocp-Apim-Subscription-Key}, Azure OpenAI's {@code api-key}) and
     * {@code Cookie}.
     */
    static boolean isCredentialHeaderName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = normalize(name);
        return UriRedactor.isSensitiveHeaderName(name) || normalized.endsWith("key") || "cookie".equals(normalized);
    }

    /** Whether a query parameter's name says it carries a credential. */
    static boolean isCredentialQueryName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = normalize(name);
        return UriRedactor.isSensitiveHeaderName(name) || CREDENTIAL_QUERY_NAMES.contains(normalized) || normalized.endsWith("apikey");
    }

    private static boolean startsWithQuantity(String name) {
        List<String> words = UriRedactor.splitWords(name);
        return !words.isEmpty() && QUANTITY_WORDS.contains(words.get(0));
    }

    private static String normalize(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /**
     * {@link SecretRedactionFilter#redact} over free text, with every reference and
     * template held aside so the scan cannot take a pointer for a value.
     */
    static String scanText(String value) {
        Masked masked = mask(value);
        return masked.unmask(filter(masked.text()));
    }

    /**
     * A URL or a path with a query: the userinfo password and every
     * credential-named query value are replaced, and the rest is shape-scanned.
     * References and templates are kept wherever they sit.
     */
    static String redactUrl(String value) {
        Masked masked = mask(value);
        String text = masked.text();
        String fragment = "";
        int hash = text.indexOf('#');
        if (hash >= 0) {
            fragment = filter(text.substring(hash));
            text = text.substring(0, hash);
        }
        String query = null;
        int question = text.indexOf('?');
        if (question >= 0) {
            query = text.substring(question + 1);
            text = text.substring(0, question);
        }
        StringBuilder out = new StringBuilder(value.length());
        out.append(redactBeforeQuery(text));
        if (query != null) {
            out.append('?');
            String[] pairs = query.split("&", -1);
            for (int i = 0; i < pairs.length; i++) {
                if (i > 0) {
                    out.append('&');
                }
                out.append(redactQueryPair(pairs[i]));
            }
        }
        out.append(fragment);
        return masked.unmask(out.toString());
    }

    private static String redactBeforeQuery(String text) {
        int scheme = text.indexOf("://");
        if (scheme < 0) {
            return filter(text);
        }
        int authorityStart = scheme + 3;
        int pathStart = text.indexOf('/', authorityStart);
        String authority = pathStart < 0 ? text.substring(authorityStart) : text.substring(authorityStart, pathStart);
        String path = pathStart < 0 ? "" : text.substring(pathStart);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            int colon = userInfo.indexOf(':');
            if (colon >= 0) {
                String password = userInfo.substring(colon + 1);
                // In user:password@host the second half is a credential by definition
                // (RFC 3986 §3.2.1); only a pure reference survives.
                if (!password.isEmpty() && !isOnlySentinels(password)) {
                    userInfo = userInfo.substring(0, colon + 1) + IN_STRING_REDACTED;
                }
            }
            authority = userInfo + authority.substring(at);
        }
        return text.substring(0, authorityStart) + authority + filter(path);
    }

    private static String redactQueryPair(String pair) {
        int eq = pair.indexOf('=');
        if (eq < 0) {
            return filter(pair);
        }
        String name = pair.substring(0, eq);
        String rawValue = pair.substring(eq + 1);
        if (rawValue.isEmpty() || isOnlySentinels(rawValue)) {
            return pair;
        }
        if (isCredentialQueryName(name)) {
            return name + "=" + IN_STRING_REDACTED;
        }
        String decoded = rawValue;
        try {
            decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            // Scan the raw form rather than skip the check.
        }
        return SecretRedactionFilter.redact(decoded).equals(decoded) ? pair : name + "=" + IN_STRING_REDACTED;
    }

    /**
     * {@link SecretRedactionFilter#redact}, with whatever it replaced restated in
     * {@link #IN_STRING_REDACTED}.
     */
    private static String filter(String text) {
        String redacted = SecretRedactionFilter.redact(text);
        return redacted.equals(text) ? text : redacted.replace(SecretRedactionFilter.REDACTED, IN_STRING_REDACTED);
    }

    /**
     * Every place in {@code document} where a string value carries
     * {@link #MARKER_PREFIX}, as paths such as {@code tasks[0].parameters.apiKey}.
     * Empty for a document that never went through a redacted read. Unlike the
     * redaction itself this skips nothing: a marker is corruption wherever it sits.
     */
    public static List<String> markerPaths(Object document) {
        List<String> found = new ArrayList<>();
        if (document != null) {
            collectMarkers(MAPPER.valueToTree(document), "", found);
        }
        return found;
    }

    private static void collectMarkers(JsonNode node, String path, List<String> found) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            if (node.asText().contains(MARKER_PREFIX)) {
                found.add(path.isEmpty() ? "$" : path);
            }
        } else if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                collectMarkers(field.getValue(), path.isEmpty() ? field.getKey() : path + "." + field.getKey(), found);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                collectMarkers(array.get(i), path + "[" + i + "]", found);
            }
        }
    }

    private static boolean isOnlySentinels(String text) {
        return SENTINEL.matcher(text).replaceAll("").isBlank();
    }

    private static Masked mask(String value) {
        if (value.indexOf(SENTINEL_OPEN) >= 0 || value.indexOf(SENTINEL_CLOSE) >= 0) {
            // Cannot happen in a real config; if it does, scan unmasked rather than
            // let a planted sentinel smuggle text past the scan.
            return new Masked(value, List.of());
        }
        List<String> held = new ArrayList<>();
        Matcher matcher = REFERENCE_OR_TEMPLATE.matcher(value);
        StringBuilder out = new StringBuilder(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(SENTINEL_OPEN + Integer.toString(held.size()) + SENTINEL_CLOSE));
            held.add(matcher.group());
        }
        matcher.appendTail(out);
        return new Masked(out.toString(), held);
    }

    private record Masked(String text, List<String> held) {
        String unmask(String redacted) {
            if (held.isEmpty()) {
                return redacted;
            }
            Matcher matcher = SENTINEL.matcher(redacted);
            StringBuilder out = new StringBuilder(redacted.length());
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                matcher.appendReplacement(out, Matcher.quoteReplacement(index < held.size() ? held.get(index) : ""));
            }
            matcher.appendTail(out);
            return out.toString();
        }
    }
}
