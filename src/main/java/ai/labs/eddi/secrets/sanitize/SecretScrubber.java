/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import ai.labs.eddi.connections.model.ConnectionReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-based JSON scrubber that replaces secret values with vault-redacted
 * placeholders. Used during Agent export to prevent plaintext secrets from
 * leaking into ZIP archives.
 * <p>
 * Detection strategy (defense-in-depth):
 * <ol>
 * <li><b>Field name heuristics</b>: known secret field names (apiKey, password,
 * token, etc.), plus suffix and header-map rules for the unconventional
 * spellings — see {@link #isSecretFieldName(String, String)}</li>
 * <li><b>URL credentials</b>: {@code https://user:pass@host} and
 * {@code ?api_key=…} are redacted in place by {@link UriRedactor}, which no
 * whole-value check can catch</li>
 * <li><b>Shannon entropy</b>: high-entropy strings (&gt;3.5 bits/char) that
 * look like API keys</li>
 * <li><b>Vault references</b>: existing ${vault:...} or ${eddivault:...}
 * references are left untouched</li>
 * </ol>
 * Every rule applies to strings inside arrays as well as to object fields.
 */
@ApplicationScoped
public class SecretScrubber {

    private static final Logger LOGGER = Logger.getLogger(SecretScrubber.class);

    /**
     * The marker written in place of a secret value.
     * <p>
     * Public because readers of scrubbed content have to recognise it: the upgrade
     * path puts the target's own value back wherever the source carries this
     * placeholder, and it used to compare against its own copy of the literal — so
     * changing the marker here would have silently stopped that matching and
     * overwritten a production agent's live credentials with placeholders, with no
     * compile error and no failing test.
     */
    public static final String REDACTED = "${vault:REDACTED}";

    /**
     * Minimum length for entropy analysis (short strings are less likely to be
     * secrets)
     */
    private static final int MIN_ENTROPY_LENGTH = 14;

    /**
     * Shannon entropy threshold — API keys typically have entropy > 3.5 bits/char
     */
    private static final double ENTROPY_THRESHOLD = 3.5;

    /** Pattern matching strings that look like API keys / tokens */
    private static final Pattern KEY_LIKE_PATTERN = Pattern.compile("[a-zA-Z0-9_.+/~$\\-]{14,1022}");

    /**
     * A value that is exactly one {@code ${connection:…}} reference — the only
     * shape of connection reference any outbound path accepts, and therefore the
     * only one worth keeping legible. See {@link #scrubTextValue}.
     */
    private static final Pattern SOLE_CONNECTION_REFERENCE = Pattern.compile("\\s*" + ConnectionReference.CONNECTION_PATTERN + "\\s*");

    /**
     * A single {@code ${vault:…}} or {@code ${eddivault:…}} reference. Used to
     * strip the pointers out of a value so what remains can be judged on its own —
     * see {@link #scrubTextValue}.
     */
    private static final Pattern VAULT_REFERENCE = Pattern.compile("\\$\\{(?:vault|eddivault):[^}]*\\}");

    /**
     * Splits a vault-reference remainder into candidate credential segments: any
     * run of characters {@link #KEY_LIKE_PATTERN} does not admit ends a segment.
     * Splitting on whitespace alone left punctuation glued to a credential —
     * {@code "sk-live-…,"} — and the whole-token key pattern then rejected it.
     */
    private static final Pattern NON_KEY_CHARACTERS = Pattern.compile("[^a-zA-Z0-9_.+/~$\\-]+");

    /**
     * Words that may legitimately sit beside a vault reference in a
     * credential-named field: HTTP authorization schemes and their common vendor
     * variants, as in {@code "Authorization": "Bearer ${vault:k}"}. Compared
     * case-insensitively. Anything else next to a reference in such a field is
     * treated as a possible plaintext credential — see
     * {@link #isReferenceScaffolding(String)}.
     */
    private static final Set<String> AUTH_SCHEME_WORDS = Set.of("bearer", "basic", "digest", "token", "bot", "apikey", "api-key", "key",
            "ssws", "oauth", "negotiate", "hmac");

    /**
     * Fields whose value is a schema-fixed identifier — a discriminator, a name, or
     * a memory path — and therefore never a credential. Exempt from the entropy
     * heuristic; see {@link #isStructuralFieldName(String)} for why.
     */
    private static final Set<String> STRUCTURAL_FIELD_NAMES = Set.of("type", "subtype", "name", "action", "actions", "expressions",
            "fromobjectpath", "toobjectpath", "behaviorrulename", "scope", "uri", "occurrence",
            // Model identifiers. "claude-sonnet-5" scores over the entropy threshold, so an
            // export rewrote modelName to ${vault:REDACTED} and the imported agent failed
            // every turn with "model not found". Credential-shaped names (apiKey, token,
            // ...) are still caught by the name checks, which run before this exemption.
            "modelname", "model", "modelid", "deploymentname", "embeddingmodel", "embeddingmodelname");

    /** Known secret field names (case-insensitive matching) */
    private static final Set<String> SECRET_FIELD_NAMES = Set.of("apikey", "api_key", "apitoken", "api_token", "password", "passwd", "secret",
            "secretkey", "secret_key", "token", "accesstoken", "access_token", "authorization", "auth", "credential", "credentials", "privatekey",
            "private_key", "clientsecret", "client_secret",
            // The abbreviations and the key-material names a config author reaches for
            // when the field is not literally called "password": a short pwd/pass/pin/otp
            // value scores under the entropy threshold, so the name is the only thing that
            // catches it.
            "pwd", "pass", "passphrase", "pin", "otp", "private_key_pem", "privatekeypem", "pem");

    /**
     * Name suffixes that mark a credential wherever the field appears — unless the
     * name in front of them measures a quantity, see {@link #QUANTITY_QUALIFIERS}.
     * <p>
     * {@code pin}, {@code otp} and {@code pem} are deliberately NOT suffixes: as
     * suffixes they would match {@code spin}, {@code item} and
     * {@code totalPostsSystem}-style names, and a scrubber that over-matches breaks
     * the export → import round trip. They are whole-name matches above.
     */
    private static final Set<String> SECRET_FIELD_NAME_SUFFIXES = Set.of("token", "secret", "password", "passwd", "credential", "credentials",
            "authorization", "pwd", "passphrase", "privatekey", "private_key");

    /**
     * Qualifiers that turn a credential noun into a COUNT of them.
     * <p>
     * Without this the suffix rule above is a round-trip bug on the most common LLM
     * parameter there is: {@code maxTokens} singularises to {@code maxtoken}, which
     * ends in {@code token}, so every agent export replaced the model's output
     * limit with a vault placeholder — and the export is stored as
     * {@code Map<String, String>}, so the value IS a JSON string and the rule
     * really fires. {@code maxTokens} is read by eight of the model builders,
     * {@code maxOutputTokens} by Gemini and {@code maxNewTokens} by HuggingFace —
     * every token-shaped parameter key a builder reads is one of those three, and
     * {@code budgetTokens} and {@code maxCompletionTokens} are the same field again
     * wherever a config carries them.
     * <p>
     * Matched as a PREFIX of the whole name and only in front of a credential
     * suffix, so no genuine credential is exempted: {@code apiToken},
     * {@code accessToken}, {@code refreshToken} and {@code authToken} do not begin
     * with a quantity.
     */
    /**
     * {@code scheme://} at the start of a value. Bounded, so the scan stays linear.
     */
    private static final Pattern SCHEME_PREFIX = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]{0,31}://");

    private static final Set<String> QUANTITY_QUALIFIERS = Set.of("max", "min", "num", "number", "total", "budget");

    /**
     * A value that is, in full, an unresolved template expression or a vault
     * reference — a pointer, never the secret itself. Inside an embedded document
     * or a query string it is left legible, as a vault reference is everywhere.
     */
    private static final Pattern WHOLE_POINTER = Pattern.compile("\\s*+(?:\\$?\\{[^{}]*+\\})\\s*+");

    /**
     * A Qute expression inside free text — {@code {properties.apiKey}}, {@code {#if
     * x}}. Masked before the free-text pass so the redaction rules judge the
     * literal text around it, never the expression (a templated {@code "token":
     * "{properties.t}"} is a pointer, not a credential). A JSON object
     * ({@code {"…}) does not match: no double quote may appear inside.
     */
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{[a-zA-Z_#/!][^{}\"]*+\\}");

    /** One {@code name=value} pair of a form-encoded body or query string. */
    private static final Pattern FORM_PAIR = Pattern.compile("[A-Za-z0-9_.\\-\\[\\]%]++=[^&\\s]*+");

    /**
     * Field names whose value is request material — what an outbound call sends (a
     * path, a body, a URL or endpoint). Only these, values inside a header or
     * query-parameter map, and documents embedded in a string get the full
     * {@link SecretRedactionFilter} pass with its name-bound rules
     * ({@code password: ...}, {@code token=...}); any other text — a prompt, a
     * description, an output — only loses tokens that are credentials by their
     * shape, so export never rewrites prose.
     */
    private static final Set<String> REQUEST_MATERIAL_FIELD_NAMES = Set.of("path", "body", "requestbody", "url", "baseurl", "endpoint",
            "targetserverurl", "serverurl", "apiurl", "query", "querystring");

    /** Containers whose string values are request material. */
    private static final Set<String> REQUEST_MATERIAL_CONTAINERS = Set.of("queryparams", "queryparameters", "params", "cookies");

    /**
     * Credential shapes the export pass recognises in prose on top of
     * {@link SecretRedactionFilter#redactCredentialShapes}: provider keys and
     * tokens that are secrets by their form alone. Each is anchored so it does not
     * start inside a word, and possessive so the scan stays linear.
     */
    private static final List<Pattern> EXPORT_CREDENTIAL_SHAPES = List.of(
            Pattern.compile("(?<![A-Za-z0-9_])AIza[0-9A-Za-z_\\-]{35}+"),
            Pattern.compile("(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{36,}+"),
            Pattern.compile("(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9_]{22,}+"),
            Pattern.compile("(?<![A-Za-z0-9_])xox[abprs]-[A-Za-z0-9\\-]{10,}+"),
            Pattern.compile("(?<![A-Za-z0-9_])(?:AKIA|ASIA)[0-9A-Z]{16}+(?![0-9A-Z])"),
            Pattern.compile("(?<![A-Za-z0-9_])gsk_[A-Za-z0-9]{20,}+"),
            Pattern.compile("(?<![A-Za-z0-9_])xai-[A-Za-z0-9]{20,}+"),
            Pattern.compile("(?<![A-Za-z0-9_])eyJ[A-Za-z0-9_\\-]{8,}+\\.eyJ[A-Za-z0-9_\\-]{8,}+\\.[A-Za-z0-9_\\-]{8,}+"));

    /** The password of a {@code mongodb(+srv)://user:password@} string in prose. */
    private static final Pattern MONGODB_USERINFO = Pattern.compile("(mongodb(?:\\+srv)?+://[^\\s:/@]++):[^\\s/@]++@");

    /** Brackets a masked template expression in {@link #redactFreeText}. */
    private static final char MASK_OPEN = (char) 0xE000;
    private static final char MASK_CLOSE = (char) 0xE001;

    private final ObjectMapper objectMapper;

    @Inject
    public SecretScrubber(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Scrub potential plaintext secrets from a JSON string. Returns the sanitized
     * JSON, or the original string if parsing fails.
     *
     * @param json
     *            the JSON string to scrub
     * @return sanitized JSON with secrets replaced by ${vault:REDACTED}
     */
    public String scrubJson(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            scrubNode(root, null);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            LOGGER.warnv("Failed to parse JSON for scrubbing, returning original: {0}", e.getMessage());
            return json;
        }
    }

    /**
     * Whether scrubbing {@code json} would redact anything — that is, whether it
     * holds a value this class treats as a credential, judged by exactly the rules
     * {@link #scrubJson(String)} applies. Nothing is changed.
     *
     * <p>
     * For callers that must keep a credential out of somewhere rather than mask it
     * in place, such as the migration that copies legacy properties into long-term
     * memory. JSON that cannot be parsed answers {@code true}: a value that could
     * not be inspected has not been shown to be safe.
     * </p>
     */
    public boolean containsCredential(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode original = objectMapper.readTree(json);
            JsonNode scrubbed = original.deepCopy();
            scrubNode(scrubbed, null);
            return !scrubbed.equals(original);
        } catch (Exception e) {
            LOGGER.warnv("Could not parse JSON to check it for credentials, treating it as holding one: {0}", e.getMessage());
            return true;
        }
    }

    /**
     * Whether a field of this name holds a credential, whatever its value — the
     * name rules of {@link #scrubJson(String)} on their own. A caller that walks a
     * structure itself needs them for a credential-named field whose value is an
     * object: the scrubber judges the strings inside by their own names.
     */
    public static boolean isCredentialFieldName(String fieldName) {
        return isSecretFieldName(fieldName, null);
    }

    private void scrubNode(JsonNode node, String parentFieldName) {
        scrubNode(node, parentFieldName, false);
    }

    /**
     * @param embedded
     *            whether {@code node} is a document that sat inside a string value
     *            (an httpcall {@code request.body}, a service-account JSON), where
     *            a whole template expression is a pointer and stays legible
     */
    private void scrubNode(JsonNode node, String parentFieldName, boolean embedded) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();

                if (fieldValue.isTextual()) {
                    String replacement = scrubTextValue(fieldName, parentFieldName, fieldValue.asText(), embedded);
                    if (replacement != null) {
                        objectNode.set(fieldName, new TextNode(replacement));
                    }
                } else {
                    scrubNode(fieldValue, fieldName, embedded);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode element = arrayNode.get(i);
                if (element.isTextual()) {
                    // The hole this closes: the previous version recursed into an
                    // array and handed each element to scrubNode with the PARENT's
                    // field name — and scrubNode does nothing at all with a node that
                    // is neither object nor array. So every string inside every array
                    // was exported verbatim, including one under a field named
                    // "apiKeys". An element is judged by the array's own field name,
                    // which is the only name it has.
                    String replacement = scrubTextValue(parentFieldName, null, element.asText(), embedded);
                    if (replacement != null) {
                        arrayNode.set(i, new TextNode(replacement));
                    }
                } else {
                    scrubNode(element, parentFieldName, embedded);
                }
            }
        }
    }

    /**
     * Decides what one string value becomes, or {@code null} to leave it alone.
     * <p>
     * Three passes the export path lacked, which the runtime redaction had
     * (security review 2026-10-17, M8): a value that is itself structured — a
     * relative {@code request.path} with a query string, a JSON document or a
     * form-encoded body inside a string — is judged part by part, so
     * {@code ?api_key=…}, {@code client_secret=…} and an {@code apiKey} inside a
     * JSON-in-a-string body no longer leave in clear; and every other value finally
     * gets {@link SecretRedactionFilter}'s shape rules, so a provider key pasted
     * into a {@code description} or {@code systemPrompt} is caught by the same
     * patterns the logs use (and any added there later).
     *
     * @param embedded
     *            whether the value sits in a document that was itself a string
     */
    private String scrubTextValue(String fieldName, String parentFieldName, String textValue, boolean embedded) {
        if (embedded && WHOLE_POINTER.matcher(textValue).matches()) {
            return null;
        }
        boolean requestMaterial = embedded || isRequestMaterial(fieldName, parentFieldName);
        // A credential-named field is redacted whole below, whatever its shape. Only
        // request material, and a field that names itself a JSON document
        // (serviceAccountJson), is taken apart: a prompt that happens to look like
        // a query string or a JSON example stays prose.
        if (!isSecretFieldName(fieldName, parentFieldName) && (requestMaterial || isEmbeddedDocumentName(fieldName))) {
            String structured = scrubStructuredText(textValue);
            if (structured != null && !structured.equals(textValue)) {
                return structured;
            }
        }
        String decided = scrubSingleValue(fieldName, parentFieldName, textValue);
        if (decided != null || isStructuralFieldName(fieldName) || looksLikeUrl(textValue)) {
            return decided;
        }
        return requestMaterial ? redactFreeText(textValue) : redactCredentialShapes(textValue);
    }

    /**
     * Whether the value is something an outbound call sends — see
     * {@link #REQUEST_MATERIAL_FIELD_NAMES}.
     */
    private static boolean isRequestMaterial(String fieldName, String parentFieldName) {
        if (fieldName != null && REQUEST_MATERIAL_FIELD_NAMES.contains(normalizeFieldName(fieldName))) {
            return true;
        }
        return isHeaderContainer(parentFieldName)
                || (parentFieldName != null && REQUEST_MATERIAL_CONTAINERS.contains(normalizeFieldName(parentFieldName)));
    }

    /**
     * A field that says it holds a JSON document, e.g. {@code serviceAccountJson}.
     */
    private static boolean isEmbeddedDocumentName(String fieldName) {
        return fieldName != null && normalizeFieldName(fieldName).endsWith("json");
    }

    /**
     * Only the tokens that are credentials by their shape (see
     * {@link #EXPORT_CREDENTIAL_SHAPES}); {@code null} when nothing matched.
     */
    static String redactCredentialShapes(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String redacted = SecretRedactionFilter.redactCredentialShapes(text);
        for (Pattern shape : EXPORT_CREDENTIAL_SHAPES) {
            redacted = shape.matcher(redacted).replaceAll(Matcher.quoteReplacement(SecretRedactionFilter.REDACTED));
        }
        redacted = MONGODB_USERINFO.matcher(redacted).replaceAll("$1:" + Matcher.quoteReplacement(SecretRedactionFilter.REDACTED) + "@");
        return redacted.equals(text) ? null : redacted;
    }

    /**
     * The value scrubbed part by part when it is structured — a relative URI with a
     * query string, an embedded JSON document, a form-encoded body — or
     * {@code null} when it is none of those (the caller then judges it whole). An
     * unchanged structured value comes back unchanged.
     */
    private String scrubStructuredText(String textValue) {
        String trimmed = textValue.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            String document = scrubEmbeddedJson(trimmed);
            if (document != null) {
                // an unchanged document keeps its surrounding whitespace as well
                return document.equals(trimmed) ? textValue : document;
            }
        }
        if (textValue.startsWith("/") && textValue.indexOf('?') > 0) {
            int queryStart = textValue.indexOf('?');
            int fragmentStart = textValue.indexOf('#', queryStart);
            String query = fragmentStart < 0 ? textValue.substring(queryStart + 1) : textValue.substring(queryStart + 1, fragmentStart);
            String path = textValue.substring(0, queryStart);
            String fragment = fragmentStart < 0 ? "" : textValue.substring(fragmentStart);
            String redactedPath = redactFreeText(path);
            return (redactedPath != null ? redactedPath : path) + "?" + scrubPairs(query) + fragment;
        }
        if (isFormEncoded(textValue)) {
            return scrubPairs(textValue);
        }
        return null;
    }

    /**
     * An embedded JSON document scrubbed with the same rules, re-serialised only
     * when something was redacted — so an unchanged body template keeps its
     * formatting. {@code null} when the text is not JSON (a body template with an
     * unquoted placeholder usually is not; it gets the free-text pass instead).
     */
    private String scrubEmbeddedJson(String text) {
        try {
            JsonNode original = objectMapper.readTree(text);
            if (original == null || !(original.isObject() || original.isArray())) {
                return null;
            }
            JsonNode scrubbed = original.deepCopy();
            scrubNode(scrubbed, null, true);
            return scrubbed.equals(original) ? text : objectMapper.writeValueAsString(scrubbed);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the whole value is {@code name=value} pairs joined by {@code &}. */
    private static boolean isFormEncoded(String value) {
        if (value.isEmpty() || value.indexOf('=') < 0) {
            return false;
        }
        for (String pair : value.split("&", -1)) {
            if (!FORM_PAIR.matcher(pair).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Each {@code name=value} pair judged on its own: a credential-named key, or a
     * value {@link SecretRedactionFilter} recognises, loses its value; a value that
     * is a vault reference or a template expression is a pointer and stays.
     */
    private static String scrubPairs(String pairs) {
        var out = new StringBuilder(pairs.length());
        for (String pair : pairs.split("&", -1)) {
            if (!out.isEmpty()) {
                out.append('&');
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                String redacted = redactFreeText(pair);
                out.append(redacted != null ? REDACTED : pair);
                continue;
            }
            String name = pair.substring(0, eq);
            String rawValue = pair.substring(eq + 1);
            String decoded;
            try {
                decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformedEscape) {
                decoded = rawValue;
            }
            boolean pointer = WHOLE_POINTER.matcher(decoded).matches();
            boolean credential = !decoded.isEmpty() && !pointer
                    && (isSecretFieldName(name, null) || UriRedactor.isSensitiveHeaderName(name) || redactFreeText(decoded) != null);
            out.append(name).append('=').append(credential ? REDACTED : rawValue);
        }
        return out.toString();
    }

    /**
     * {@link SecretRedactionFilter#redact} over free text, with template
     * expressions masked so they are never mistaken for the value of a credential
     * key; {@code null} when nothing was redacted.
     */
    static String redactFreeText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        List<String> masked = new ArrayList<>();
        Matcher expressions = TEMPLATE_EXPRESSION.matcher(text);
        var maskedText = new StringBuilder(text.length());
        while (expressions.find()) {
            expressions.appendReplacement(maskedText, Matcher.quoteReplacement(MASK_OPEN + Integer.toString(masked.size()) + MASK_CLOSE));
            masked.add(expressions.group());
        }
        expressions.appendTail(maskedText);
        String input = maskedText.toString();
        String redacted = SecretRedactionFilter.redact(input);
        if (redacted.equals(input)) {
            return null;
        }
        for (int i = 0; i < masked.size(); i++) {
            redacted = redacted.replace(MASK_OPEN + Integer.toString(i) + MASK_CLOSE, masked.get(i));
        }
        return redacted;
    }

    /**
     * The original whole-value decision: references, credential names, URLs and the
     * entropy heuristic.
     */
    private String scrubSingleValue(String fieldName, String parentFieldName, String textValue) {
        // A vault reference is a pointer to a secret, not a secret, and is left
        // legible so an operator can still see WHICH key the config used. A
        // connection reference is the same kind of pointer one level up — the
        // connection document holds the vault references — and it sits in exactly
        // the fields this scrubber otherwise redacts (an Authorization header, an
        // apiKey), so without its own exemption every exported httpcall header
        // reading ${connection:jira} came back as ${vault:REDACTED} and the
        // reference was lost before the archive was even written.
        //
        // The two exemptions differ in width, on purpose. "Bearer ${vault:k}" is a
        // legitimate shape, so a vault reference exempts the value it sits in. A
        // connection reference is legitimate ONLY on its own — every outbound path
        // refuses "Bearer sk-… ${connection:jira}" through
        // ConnectionReference.requireSole — so exempting a value that merely contains
        // one would keep a literal credential legible for the sake of a config that
        // cannot run. A sole reference passes; a mixed value is judged like any other.
        //
        // The exemption speaks for one value, and a URL is not one value: it is a
        // host, a path and a set of independent query parameters. Read over a whole
        // URL, "carries a reference somewhere" exempted the LIVE credential sitting
        // in the next parameter —
        // `?api_key=${vault:k}&access_token=<plaintext>` was exported intact. A URL
        // is therefore always handed to the part-by-part pass below, which judges
        // each parameter on its own.
        if (!looksLikeUrl(textValue)) {
            if (SOLE_CONNECTION_REFERENCE.matcher(textValue).matches()) {
                return null;
            }
            if (textValue.contains("${vault:") || textValue.contains("${eddivault:")) {
                // "Contains a reference" is not "is a reference". A value built from
                // references, optionally with fixed scaffolding like "Bearer ", is a
                // legitimate shape and is left legible so an operator can still read
                // WHICH key the config used. But a value that pairs a reference with a
                // real credential — "Bearer sk-live-… ${vault:x}" — must NOT ride the
                // exemption: the plaintext half would then be exported verbatim. Strip
                // the references and judge only what is left.
                String remainder = VAULT_REFERENCE.matcher(textValue).replaceAll("");
                // The non-reference remainder itself looks like a secret, so redact the
                // whole value rather than leak the plaintext half. Losing the pointer
                // costs nothing — this value could never be a valid single credential.
                if (containsSecretMaterial(remainder)) {
                    return REDACTED;
                }
                // In a credential-named field the entropy test is not enough: a short or
                // low-entropy password ("hunter2 ${vault:k}") passes it and would be
                // exported verbatim. There, only a scheme word and separators may sit
                // beside the reference; anything else redacts the value.
                if (isSecretFieldName(fieldName, parentFieldName) && !isReferenceScaffolding(remainder)) {
                    return REDACTED;
                }
                return null;
            }
        }

        // Check 1: Known secret field names
        if (isSecretFieldName(fieldName, parentFieldName)) {
            return REDACTED;
        }

        // Check 2: a credential embedded in a URL. Neither of the other checks can
        // see one — the field is called "targetServerUrl", not "apiKey", and
        // KEY_LIKE_PATTERN requires a WHOLE-string match, which the ':', '/', '?'
        // and '=' of a URL all defeat. So https://user:pass@host and ?api_key=…
        // survived export untouched. Only the credential segment is replaced, not
        // the whole value: an exported config whose target host has become a
        // placeholder is neither reviewable nor importable.
        if (looksLikeUrl(textValue)) {
            // The scrubber's own marker, not the approval card's <REDACTED>: this
            // value goes into an exported config, where the angle brackets are not
            // URI characters and a vault placeholder is both legible and what the
            // importer expects an operator to replace.
            String redactedUrl = UriRedactor.redactUri(textValue, REDACTED);
            return redactedUrl.equals(textValue) ? null : redactedUrl;
        }

        // Check 3: Shannon entropy on key-like strings — but never on a
        // field whose meaning is fixed by the configuration schema.
        if (!isStructuralFieldName(fieldName) && textValue.length() >= MIN_ENTROPY_LENGTH && KEY_LIKE_PATTERN.matcher(textValue).matches()
                && shannonEntropy(textValue) > ENTROPY_THRESHOLD) {
            return REDACTED;
        }

        return null;
    }

    /** An http(s) URL — the only shape {@link UriRedactor} is meant to be given. */
    /**
     * Any {@code scheme://authority} value, not just the two web schemes.
     * <p>
     * The per-component pass is what pulls a password out of a URI's userinfo, and
     * restricting the gate to http and https meant a connection string never
     * reached it: {@code mongodb://user:pw@host/db} is exactly the shape EDDI's own
     * configuration uses, and its password is not caught by the whole-value checks
     * either — the {@code :}, {@code /}, {@code ?} and {@code =} of a URI defeat
     * the key-like pattern the entropy check requires. So it was exported verbatim.
     * {@code wss://}, {@code redis://}, {@code amqp://} and {@code postgresql://}
     * carry credentials the same way.
     * <p>
     * Matching the RFC 3986 scheme grammar rather than listing schemes: the next
     * one nobody thought of is the one that leaks. {@link UriRedactor#redactUri} is
     * scheme-agnostic, and returns the value unchanged when nothing needed
     * redacting, so widening the gate cannot over-redact a value that is not a URI.
     */
    private static boolean looksLikeUrl(String value) {
        return SCHEME_PREFIX.matcher(value.trim()).find();
    }

    /**
     * Whether a field name marks its value as a credential.
     * <p>
     * The exact-name set alone missed every unconventional spelling.
     * {@code X-Api-Token} normalizes to {@code xapitoken}, which is in no set, so
     * it fell through to the entropy heuristic — and that requires a whole-string
     * match, so {@code Bearer abc…} with its space never matched either. The result
     * was an Authorization-equivalent header exported in full.
     * <p>
     * Two additions, deliberately asymmetric:
     * <ul>
     * <li>a name ENDING in token/secret/password/credential(s)/authorization is a
     * credential wherever it appears, unless a quantity qualifier in front of the
     * suffix makes it a count — see {@link #QUANTITY_QUALIFIERS};</li>
     * <li>a name ending in {@code key}, and the shared header rule
     * {@link UriRedactor#isSensitiveHeaderName(String)}, count only INSIDE a header
     * map. Applied globally, {@code endsWith("key")} would redact
     * {@code publicKey}, {@code groupKey} and every other structural identifier,
     * and export → import is the one round trip that must stay lossless.</li>
     * </ul>
     */
    private static boolean isSecretFieldName(String fieldName, String parentFieldName) {
        if (fieldName == null) {
            return false;
        }
        String name = normalizeFieldName(fieldName);
        // A plural is the same field. "apiKeys" normalizes to apikeys, which is in
        // no set and matches no suffix, so a LIST of credentials — the one shape
        // the array fix above exists to reach — was still exported verbatim.
        String singular = singularize(name);
        if (SECRET_FIELD_NAMES.contains(name) || SECRET_FIELD_NAMES.contains(singular)) {
            return true;
        }
        for (String suffix : SECRET_FIELD_NAME_SUFFIXES) {
            if (isCredentialSuffix(name, suffix, fieldName) || isCredentialSuffix(singular, suffix, fieldName)) {
                return true;
            }
        }
        if (!isHeaderContainer(parentFieldName)) {
            return false;
        }
        // endsWith("key") stays header-local and stays broader than the shared
        // rule: a vendor names its credential header Ocp-Apim-Subscription-Key,
        // which no curated word list predicts. The rest of the header decision is
        // the shared one, so a header this scrubber exports and the same header on
        // an approval card cannot disagree about what is a credential. A Cookie
        // header is a session credential by definition (security review M8).
        return name.endsWith("key") || name.equals("cookie") || name.equals("setcookie") || UriRedactor.isSensitiveHeaderName(fieldName);
    }

    /**
     * Whether {@code name} ends in a credential suffix and is not a count of them.
     */
    private static boolean isCredentialSuffix(String name, String suffix, String originalFieldName) {
        return name.endsWith(suffix) && !startsWithQuantityWord(originalFieldName);
    }

    /**
     * Whether the name's FIRST WORD measures a quantity.
     * <p>
     * A raw prefix test is not enough, and the difference leaks credentials.
     * Normalizing strips the separators that say where the first word ends, so
     * {@code minioSecret} becomes {@code miniosecret} — which begins with
     * {@code min}, took the exemption, and left a real credential in the export in
     * plaintext. {@code numericToken} went the same way. Splitting the ORIGINAL
     * name keeps the camel-case and separator boundaries, so {@code maxTokens}
     * stays exempt for the reason it is meant to be and {@code minioSecret} does
     * not.
     */
    private static boolean startsWithQuantityWord(String fieldName) {
        List<String> words = UriRedactor.splitWords(fieldName);
        return !words.isEmpty() && QUANTITY_QUALIFIERS.contains(words.get(0));
    }

    /**
     * Drops one trailing {@code s}. Crude on purpose: it exists to make
     * {@code apiKeys} match {@code apikey}, and a scrubber that over-matches a
     * plural costs an export a redacted field, while one that under-matches costs a
     * credential.
     */
    private static String singularize(String name) {
        return name.length() > 1 && name.endsWith("s") ? name.substring(0, name.length() - 1) : name;
    }

    /** Whether an enclosing field name denotes a map of HTTP headers. */
    private static boolean isHeaderContainer(String parentFieldName) {
        return parentFieldName != null && normalizeFieldName(parentFieldName).endsWith("headers");
    }

    /**
     * Case-folds a field name and drops the separators that distinguish
     * {@code X-Api-Token} from {@code x_api_token} from {@code xApiToken}.
     * Underscore is included — without it {@code api_token} and {@code apiToken}
     * were two different names to every set in this class.
     */
    private static String normalizeFieldName(String fieldName) {
        return fieldName.toLowerCase(Locale.ROOT).replaceAll("[\\-._]", "");
    }

    /**
     * Whether this field's meaning is fixed by the configuration schema, so its
     * value cannot be a credential no matter how random it looks.
     * <p>
     * The entropy heuristic cannot tell a long identifier from a long key —
     * {@code dynamicvaluematcher} scores 3.68 bits/char and
     * {@code currentWeatherDescription} 3.57, both over the 3.5 threshold. Because
     * this scrubber runs on the export path, that silently rewrote structural
     * values to {@code ${vault:REDACTED}} and produced ZIPs EDDI could not import:
     * a behaviour condition whose {@code type} no longer resolves to any condition
     * class, property names and {@code fromObjectPath} expressions replaced by a
     * vault reference. Export → import is the one round trip that must be lossless.
     * <p>
     * These names are discriminators and identifiers the engine resolves — a
     * condition {@code type} names a class, {@code fromObjectPath} names a memory
     * path — so exempting them costs no secret coverage. Field-name detection
     * (check 1) still runs first, so a field actually called {@code token} or
     * {@code apiKey} is redacted regardless of what this returns.
     */
    private static boolean isStructuralFieldName(String fieldName) {
        return fieldName != null && STRUCTURAL_FIELD_NAMES.contains(normalizeFieldName(fieldName));
    }

    /**
     * Whether any segment of {@code remainder} looks like a secret — the same
     * length/key-shape/entropy heuristic {@code scrubTextValue} check 3 applies to
     * a whole value, run per segment here because the remainder of a
     * vault-reference value ("Bearer sk-live-…") is not a single token. Segments
     * are split on every character the key pattern does not admit (see
     * {@link #NON_KEY_CHARACTERS}), not just whitespace, so a credential followed
     * by a comma or wrapped in quotes is still judged on its own.
     * <p>
     * Field names are judged separately, by the caller — see
     * {@link #isReferenceScaffolding(String)}.
     */
    private static boolean containsSecretMaterial(String remainder) {
        if (remainder == null || remainder.isBlank()) {
            return false;
        }
        for (String token : NON_KEY_CHARACTERS.split(remainder.trim())) {
            if (token.length() >= MIN_ENTROPY_LENGTH && KEY_LIKE_PATTERN.matcher(token).matches() && shannonEntropy(token) > ENTROPY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the non-reference remainder of a value is only scaffolding: nothing,
     * separators, or an authorization-scheme word ({@link #AUTH_SCHEME_WORDS}).
     * {@code "Bearer "} and the {@code ":"} of {@code "${vault:u}:${vault:p}"} are;
     * {@code "hunter2 "} is not. An allow-list, because in a credential-named field
     * a word that is not a known scheme cannot be told apart from a short password.
     */
    private static boolean isReferenceScaffolding(String remainder) {
        for (String word : remainder.split("[^a-zA-Z0-9\\-]+")) {
            if (!word.isEmpty() && !AUTH_SCHEME_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Calculate Shannon entropy of a string (bits per character). Higher entropy =
     * more randomness = more likely to be an API key / secret.
     */
    static double shannonEntropy(String s) {
        if (s == null || s.isEmpty())
            return 0.0;

        int[] freq = new int[256];
        for (int i = 0; i < s.length(); i++) {
            freq[s.charAt(i) & 0xFF]++;
        }

        double entropy = 0.0;
        double len = s.length();
        for (int f : freq) {
            if (f > 0) {
                double p = f / len;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }
}
