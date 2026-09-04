/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * Fields whose value is a schema-fixed identifier — a discriminator, a name, or
     * a memory path — and therefore never a credential. Exempt from the entropy
     * heuristic; see {@link #isStructuralFieldName(String)} for why.
     */
    private static final Set<String> STRUCTURAL_FIELD_NAMES = Set.of("type", "subtype", "name", "action", "actions", "expressions",
            "fromobjectpath", "toobjectpath", "behaviorrulename", "scope", "uri", "occurrence");

    /** Known secret field names (case-insensitive matching) */
    private static final Set<String> SECRET_FIELD_NAMES = Set.of("apikey", "api_key", "apitoken", "api_token", "password", "passwd", "secret",
            "secretkey", "secret_key", "token", "accesstoken", "access_token", "authorization", "auth", "credential", "credentials", "privatekey",
            "private_key", "clientsecret", "client_secret");

    /**
     * Name suffixes that mark a credential wherever the field appears — unless the
     * name in front of them measures a quantity, see {@link #QUANTITY_QUALIFIERS}.
     */
    private static final Set<String> SECRET_FIELD_NAME_SUFFIXES = Set.of("token", "secret", "password", "passwd", "credential", "credentials",
            "authorization");

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

    private void scrubNode(JsonNode node, String parentFieldName) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String fieldName = field.getKey();
                JsonNode fieldValue = field.getValue();

                if (fieldValue.isTextual()) {
                    String replacement = scrubTextValue(fieldName, parentFieldName, fieldValue.asText());
                    if (replacement != null) {
                        objectNode.set(fieldName, new TextNode(replacement));
                    }
                } else {
                    scrubNode(fieldValue, fieldName);
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
                    String replacement = scrubTextValue(parentFieldName, null, element.asText());
                    if (replacement != null) {
                        arrayNode.set(i, new TextNode(replacement));
                    }
                } else {
                    scrubNode(element, parentFieldName);
                }
            }
        }
    }

    /**
     * Decides what one string value becomes, or {@code null} to leave it alone.
     *
     * @param fieldName
     *            the name this value sits under
     * @param parentFieldName
     *            the name of the enclosing object, used to recognise a header map
     * @param textValue
     *            the value as stored
     */
    private String scrubTextValue(String fieldName, String parentFieldName, String textValue) {
        // A vault reference is a pointer to a secret, not a secret, and is left
        // legible so an operator can still see WHICH key the config used.
        //
        // The exemption speaks for one value, and a URL is not one value: it is a
        // host, a path and a set of independent query parameters. Read over a whole
        // URL, "carries a reference somewhere" exempted the LIVE credential sitting
        // in the next parameter —
        // `?api_key=${vault:k}&access_token=<plaintext>` was exported intact. A URL
        // is therefore always handed to the part-by-part pass below, which judges
        // each parameter on its own.
        if (!looksLikeUrl(textValue) && (textValue.contains("${vault:") || textValue.contains("${eddivault:"))) {
            return null;
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
        // an approval card cannot disagree about what is a credential.
        return name.endsWith("key") || UriRedactor.isSensitiveHeaderName(fieldName);
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
