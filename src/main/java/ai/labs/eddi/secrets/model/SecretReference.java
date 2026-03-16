package ai.labs.eddi.secrets.model;

/**
 * Immutable reference to a secret stored in the vault.
 * Parsed from the {@code ${eddivault:tenantId/botId/keyName}} syntax.
 *
 * @param tenantId the tenant namespace (default: "default" for single-tenant)
 * @param botId    the bot identifier
 * @param keyName  the secret key name
 */
public record SecretReference(String tenantId, String botId, String keyName) {

    /**
     * Regex pattern for vault references: ${eddivault:tenantId/botId/keyName}
     */
    public static final String VAULT_PATTERN = "\\$\\{eddivault:([^/]+)/([^/]+)/([^}]+)\\}";

    /**
     * Parse a vault reference string into a SecretReference.
     *
     * @param reference the full reference string, e.g. "${eddivault:default/myBot/openaiKey}"
     * @return the parsed SecretReference
     * @throws IllegalArgumentException if the string doesn't match the expected format
     */
    public static SecretReference parse(String reference) {
        var matcher = java.util.regex.Pattern.compile(VAULT_PATTERN).matcher(reference);
        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Invalid vault reference: " + reference +
                            ". Expected format: ${eddivault:tenantId/botId/keyName}");
        }
        return new SecretReference(matcher.group(1), matcher.group(2), matcher.group(3));
    }

    /**
     * Construct the vault reference string from this reference.
     */
    public String toReferenceString() {
        return "${eddivault:" + tenantId + "/" + botId + "/" + keyName + "}";
    }

    /**
     * Check if a string contains a vault reference.
     */
    public static boolean isVaultReference(String value) {
        return value != null && value.contains("${eddivault:");
    }
}
