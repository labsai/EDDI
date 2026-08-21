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
    private static final String REDACTED = "${vault:REDACTED}";

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
     * Name suffixes that mark a credential wherever the field appears. No benign
     * configuration field ends in any of these.
     */
    private static final Set<String> SECRET_FIELD_NAME_SUFFIXES = Set.of("token", "secret", "password", "passwd", "credential", "credentials",
            "authorization");

    /** Credential words looked for inside an {@code x-}-prefixed header name. */
    private static final Set<String> CREDENTIAL_WORDS = Set.of("key", "token", "secret", "auth", "credential", "password");

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
        // Skip existing vault references (both new and legacy prefix)
        if (textValue.contains("${vault:") || textValue.contains("${eddivault:")) {
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
            String redactedUrl = UriRedactor.redactUri(textValue);
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
    private static boolean looksLikeUrl(String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
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
     * credential wherever it appears — there is no benign field with those
     * suffixes;</li>
     * <li>a name ending in {@code key}, or an {@code x-}-prefixed name containing a
     * credential word, counts only INSIDE a header map. Applied globally,
     * {@code endsWith("key")} would redact {@code publicKey}, {@code groupKey} and
     * every other structural identifier, and export → import is the one round trip
     * that must stay lossless.</li>
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
            if (name.endsWith(suffix) || singular.endsWith(suffix)) {
                return true;
            }
        }
        if (!isHeaderContainer(parentFieldName)) {
            return false;
        }
        if (name.endsWith("key")) {
            return true;
        }
        return name.startsWith("x") && CREDENTIAL_WORDS.stream().anyMatch(name::contains);
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
