/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.secrets.model.SecretReference;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Naming and lifecycle of the vault entries that {@code scope: "secret"}
 * properties are auto-vaulted into.
 * <p>
 * <b>Why the slot name carries more than the agent.</b> It used to be
 * {@code <agentId>.<propertyName>} — one slot per agent, shared by every user
 * and every conversation of that agent. The last writer won: after Bob entered
 * his API key, Alice's conversation still held
 * {@code ${vault:<agentId>.apiKey}}, which now resolved to Bob's key and went
 * out in Alice's requests. A slot is now
 * {@code <agentId>.u<userHash>.<nonce>.<propertyName>}:
 * <ul>
 * <li>the nonce makes every write its own slot, so no two conversations — of
 * different users or of the same user — can ever resolve each other's
 * value;</li>
 * <li>the user hash (a truncated SHA-256 of the user id, so the id itself never
 * appears in the vault) lets an erasure find every slot a user owns.</li>
 * </ul>
 * The name stays within the vault's {@code [a-zA-Z0-9._-]{1,128}} key-name
 * rule, so the slots remain manageable through the secrets REST API.
 * <p>
 * Because a write no longer overwrites a shared slot, slots must be deleted
 * explicitly: when the property is overwritten, when its conversation is
 * deleted, and on GDPR erasure. Every delete here only ever touches slots in
 * the current format that belong to the given user — a legacy shared
 * {@code <agentId>.<name>} slot may still be referenced by other users'
 * conversations and is left alone.
 */
@ApplicationScoped
public class AutoVaultedSecrets {

    private static final Logger LOGGER = Logger.getLogger(AutoVaultedSecrets.class);

    /** Hex characters of the SHA-256 user hash kept in a slot name. */
    static final int USER_HASH_LENGTH = 16;
    /** Hex characters of the per-write nonce. */
    static final int NONCE_LENGTH = 12;

    private static final Pattern CURRENT_FORMAT = Pattern
            .compile("^[^.]+\\.u[0-9a-f]{" + USER_HASH_LENGTH + "}\\.[0-9a-f]{" + NONCE_LENGTH + "}\\..+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ISecretProvider secretProvider;

    @Inject
    public AutoVaultedSecrets(ISecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    /**
     * A fresh slot name for one auto-vaulted write.
     *
     * @param userId
     *            the conversation's user; {@code null} is hashed as the empty
     *            string
     */
    public static String newSlotName(String agentId, String userId, String propertyName) {
        byte[] nonce = new byte[NONCE_LENGTH / 2];
        RANDOM.nextBytes(nonce);
        return agentId + "." + userSegment(userId) + "." + HexFormat.of().formatHex(nonce) + "." + propertyName;
    }

    /** The {@code u<hash>} segment identifying a user's slots. */
    public static String userSegment(String userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((userId == null ? "" : userId).getBytes(StandardCharsets.UTF_8));
            return "u" + HexFormat.of().formatHex(digest).substring(0, USER_HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Whether a slot name is in the current per-write format. */
    static boolean isCurrentFormat(String keyName) {
        return keyName != null && CURRENT_FORMAT.matcher(keyName).matches();
    }

    /**
     * Whether {@code keyName} is exactly a slot {@link #newSlotName} could have
     * produced for this agent, user and property — whatever its nonce. The
     * credential-reference guard uses it to accept a conversation's own
     * auto-vaulted reference and nothing else.
     */
    public static boolean isSlotFor(String keyName, String agentId, String userId, String propertyName) {
        if (keyName == null || agentId == null || propertyName == null) {
            return false;
        }
        String prefix = agentId + "." + userSegment(userId) + ".";
        String suffix = "." + propertyName;
        if (!keyName.startsWith(prefix) || !keyName.endsWith(suffix) || keyName.length() != prefix.length() + NONCE_LENGTH + suffix.length()) {
            return false;
        }
        String nonce = keyName.substring(prefix.length(), prefix.length() + NONCE_LENGTH);
        return nonce.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    /** Whether a slot name is a current-format slot owned by the given user. */
    public static boolean belongsToUser(String keyName, String userId) {
        return isCurrentFormat(keyName) && keyName.contains("." + userSegment(userId) + ".");
    }

    /**
     * Deletes the slot an auto-vaulted property value points to, when it is a
     * current-format slot owned by {@code userId}. Best effort: a failure is
     * logged, never thrown — the caller is always in the middle of something more
     * important (a turn, a deletion).
     *
     * @return {@code true} when a slot was deleted
     */
    public boolean deleteReferencedSlot(String referenceValue, String userId) {
        if (referenceValue == null || !SecretReference.isVaultReference(referenceValue)) {
            return false;
        }
        SecretReference reference;
        try {
            reference = SecretReference.parse(referenceValue);
        } catch (RuntimeException e) {
            return false;
        }
        if (!belongsToUser(reference.keyName(), userId)) {
            return false;
        }
        try {
            secretProvider.delete(reference);
            return true;
        } catch (ISecretProvider.SecretNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.warnf("[VAULT] Could not delete auto-vaulted slot '%s': %s", LogSanitizer.sanitize(reference.keyName()), e.getMessage());
            return false;
        }
    }

    /**
     * Deletes every auto-vaulted slot referenced by a conversation's properties.
     *
     * @return the number of slots deleted
     */
    public int deleteForConversation(Map<String, Property> conversationProperties, String userId) {
        if (conversationProperties == null || conversationProperties.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (Property property : conversationProperties.values()) {
            if (property != null && Boolean.TRUE.equals(property.getAutoVaulted()) && deleteReferencedSlot(property.getValueString(), userId)) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Deletes every current-format slot owned by {@code userId} in the given
     * tenants plus the default tenant. Used by GDPR erasure, which must also reach
     * slots whose conversation is already gone.
     *
     * @return the number of slots deleted
     * @throws ISecretProvider.SecretProviderException
     *             when a tenant cannot be listed — the caller reports the erasure
     *             step as failed rather than claiming it complete
     */
    public int deleteForUser(String userId, Collection<String> tenants) throws ISecretProvider.SecretProviderException {
        if (!secretProvider.isAvailable()) {
            return 0;
        }
        Set<String> allTenants = new LinkedHashSet<>();
        allTenants.add(SecretReference.DEFAULT_TENANT);
        if (tenants != null) {
            tenants.stream().filter(t -> t != null && !t.isBlank()).forEach(allTenants::add);
        }
        int deleted = 0;
        for (String tenant : allTenants) {
            for (var metadata : secretProvider.listKeys(tenant)) {
                if (!belongsToUser(metadata.keyName(), userId)) {
                    continue;
                }
                try {
                    secretProvider.delete(new SecretReference(tenant, metadata.keyName()));
                    deleted++;
                } catch (ISecretProvider.SecretNotFoundException e) {
                    // Deleted concurrently — the outcome we wanted.
                }
            }
        }
        return deleted;
    }

    /** The tenant an auto-vaulted property value lives in, or {@code null}. */
    public static String tenantOf(Property property) {
        if (property == null || !Boolean.TRUE.equals(property.getAutoVaulted()) || !SecretReference.isVaultReference(property.getValueString())) {
            return null;
        }
        try {
            return SecretReference.parse(property.getValueString()).tenantId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
