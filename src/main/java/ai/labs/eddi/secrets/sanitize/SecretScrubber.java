/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Iterator;
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
 * token, etc.)</li>
 * <li><b>Shannon entropy</b>: high-entropy strings (>3.5 bits/char) that look
 * like API keys</li>
 * <li><b>Vault references</b>: existing ${eddivault:...} references are left
 * untouched</li>
 * </ol>
 */
@ApplicationScoped
public class SecretScrubber {

    private static final Logger LOGGER = Logger.getLogger(SecretScrubber.class);
    private static final String REDACTED = "${eddivault:REDACTED}";

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

    /** Known secret field names (case-insensitive matching) */
    private static final Set<String> SECRET_FIELD_NAMES = Set.of("apikey", "api_key", "apitoken", "api_token", "password", "passwd", "secret",
            "secretkey", "secret_key", "token", "accesstoken", "access_token", "authorization", "auth", "credential", "credentials", "privatekey",
            "private_key", "clientsecret", "client_secret");

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
     * @return sanitized JSON with secrets replaced by ${eddivault:REDACTED}
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
                    String textValue = fieldValue.asText();

                    // Skip existing vault references
                    if (textValue.contains("${eddivault:")) {
                        continue;
                    }

                    // Check 1: Known secret field names
                    if (isSecretFieldName(fieldName)) {
                        objectNode.set(fieldName, new TextNode(REDACTED));
                        continue;
                    }

                    // Check 2: Shannon entropy on key-like strings
                    if (textValue.length() >= MIN_ENTROPY_LENGTH && KEY_LIKE_PATTERN.matcher(textValue).matches()
                            && shannonEntropy(textValue) > ENTROPY_THRESHOLD) {
                        objectNode.set(fieldName, new TextNode(REDACTED));
                        continue;
                    }
                } else {
                    scrubNode(fieldValue, fieldName);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                scrubNode(element, parentFieldName);
            }
        }
    }

    private static boolean isSecretFieldName(String fieldName) {
        return SECRET_FIELD_NAMES.contains(fieldName.toLowerCase().replaceAll("[\\-.]", ""));
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
