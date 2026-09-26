/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.SecretValueScrubber;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.AutoVaultReference;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static ai.labs.eddi.configs.properties.model.Property.Scope.conversation;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * Implements {@code scope: "secret"} for every path that writes a conversation
 * property: the property setter ({@code PropertySetterTask}) and the
 * pre-request / post-response property instructions of httpcalls, MCP calls and
 * LLM tasks ({@code PrePostUtils}).
 * <p>
 * The plaintext is stored in the vault and the property receives a vault
 * reference instead, marked {@link Property#getAutoVaulted() autoVaulted} so
 * the apicall reference guard can tell it apart from the same string arriving
 * through conversation data. Every copy of the plaintext is then scrubbed from
 * the current conversation step, so the document persisted for the turn holds
 * none.
 * <p>
 * <b>One vault entry per conversation.</b> The key is
 * {@code <agentId>.<conversationId>.<property>} under the default tenant (see
 * {@link AutoVaultReference}). It used to be {@code <agentId>.<property>},
 * shared by every conversation of the agent: the vault write is an upsert, so
 * the second user to enter a key replaced the first user's, and the first
 * user's calls went out with the second user's credential. The tenant used to
 * come from a {@code tenantId} conversation property, which a client can set
 * through a context expression; it is no longer consulted.
 * <p>
 * The resolver cache is invalidated after every write: re-entering a secret in
 * the same conversation overwrites the same entry, and the cached previous
 * plaintext would otherwise keep being sent for the cache TTL.
 * <p>
 * Fails closed throughout: a disabled vault, a value that is not a string, a
 * property name that cannot be embedded in a reference, or a conversation
 * without an id all abort the turn with a {@link LifecycleException} after the
 * plaintext has been scrubbed. The value is never stored as a plaintext
 * property.
 */
@ApplicationScoped
public class SecretPropertyVault {

    private static final Logger LOGGER = Logger.getLogger(SecretPropertyVault.class);

    private static final String EXPRESSIONS_PARSED_IDENTIFIER = "expressions:parsed";
    private static final String EXPRESSIONS_MATCHES_IDENTIFIER = MemoryKeys.EXPRESSIONS_MATCHES.key();
    private static final String INTENTS_IDENTIFIER = MemoryKeys.INTENTS.key();
    private static final String PROPERTIES_EXTRACTED_IDENTIFIER = "properties:extracted";
    /**
     * The conversation-output key InputParserTask echoes the parsed expressions
     * under.
     */
    private static final String EXPRESSIONS_OUTPUT_KEY = "expressions";
    private static final String INPUT_INITIAL_IDENTIFIER = "input:initial";
    /**
     * Written by {@code InputParserTask} — which is always the FIRST workflow step
     * — into the SAME conversation step, so a scrub that only rewrites
     * {@code input:initial} leaves a verbatim copy of the plaintext behind.
     */
    private static final String INPUT_NORMALIZED_IDENTIFIER = "input:normalized";
    /** Conversation-output key holding the echoed user input. */
    private static final String INPUT_OUTPUT_KEY = "input";
    /**
     * Minimum length of the punctuation/whitespace-stripped form below which a
     * containment match is too loose to act on. Guards against scrubbing a whole
     * turn because a two-character input happens to appear inside the secret.
     */
    private static final int MIN_NORMALIZED_MATCH_LENGTH = 4;
    private static final String SECRET_INPUT_PLACEHOLDER = MemoryKeys.SECRET_INPUT_PLACEHOLDER;
    /**
     * Written into the description of every entry this class stores, followed by
     * the conversation id. Together with the key shape it is what
     * {@link #deleteConversationSecrets} matches on, so a secret an operator
     * created under a similar-looking name is never removed.
     */
    static final String AUTO_VAULT_DESCRIPTION_PREFIX = "Auto-vaulted from conversation ";

    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;
    private final IDataFactory dataFactory;

    @Inject
    public SecretPropertyVault(ISecretProvider secretProvider, SecretResolver secretResolver, IDataFactory dataFactory) {
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
        this.dataFactory = dataFactory;
    }

    /**
     * An instruction that sets a {@code scope: "secret"} property could not be
     * vaulted. Unchecked, so the pre-request / post-response instruction loop of
     * {@code PrePostUtils} — which logs and skips any other failing instruction —
     * lets it fail the turn instead of silently storing nothing.
     */
    public static class SecretPropertyException extends IllegalStateException {
        public SecretPropertyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Vault {@code value} for property {@code name} of this conversation.
     *
     * @param value
     *            the resolved value the instruction produced; anything but a
     *            {@link String} is refused
     * @return the conversation-scoped, autoVaulted property holding the vault
     *         reference, ready to be put into the conversation properties
     * @throws LifecycleException
     *             when the value cannot be vaulted — the plaintext has been
     *             scrubbed from the step by then
     */
    public Property vault(IConversationMemory memory, String name, Object value) throws LifecycleException {
        if (!(value instanceof String plaintext)) {
            throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': only string values can be vaulted, "
                    + "but the instruction produced " + (value == null ? "null" : "a " + value.getClass().getSimpleName())
                    + ". Refusing to persist it in plaintext.");
        }
        SecretReference ref = AutoVaultReference.of(memory.getAgentId(), memory.getConversationId(), name);
        if (ref == null) {
            scrubSecretInput(memory, name, plaintext);
            throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': the property name must not contain "
                    + "'/', '{', '}' or '$', and the conversation must have an agent and conversation id. Refusing to persist "
                    + "the value in plaintext.");
        }

        try {
            secretProvider.store(ref, plaintext, AUTO_VAULT_DESCRIPTION_PREFIX + memory.getConversationId(), List.of(memory.getAgentId()));
        } catch (ISecretProvider.SecretProviderException e) {
            // Fail CLOSED. The historical behaviour returned the plaintext, which was then
            // persisted TWICE — as a conversation property and (because the scrub below
            // was skipped) as the raw input:initial data. A disabled vault is the default
            // (eddi.vault.master-key ships empty), so that was the common path.
            // Scrub the raw input BEFORE aborting so the plaintext cannot survive in the
            // conversation document that is persisted for the failed turn either.
            scrubSecretInput(memory, name, plaintext);
            LOGGER.errorf("Failed to store secret in vault for property '%s': %s", name, e.getMessage());
            throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': the secrets vault is unavailable or "
                    + "disabled (set EDDI_VAULT_MASTER_KEY). Refusing to persist the value in plaintext.", e);
        }
        // The entry is per conversation, so re-entering the secret overwrites it: the
        // previous plaintext must not keep resolving from the cache.
        secretResolver.invalidateCache(ref);

        scrubSecretInput(memory, name, plaintext);

        var vaulted = new Property(name, ref.toReferenceString(), conversation);
        // The ONLY place this marker is ever set. It is what lets ConfigReferenceGuard
        // tell this reference apart from the identical string arriving through
        // conversation data — the scope it is stored under is conversation either way,
        // so nothing else can.
        vaulted.setAutoVaulted(Boolean.TRUE);
        return vaulted;
    }

    /**
     * Delete the vault entries the secret properties of these conversations were
     * stored under — called when conversations are permanently deleted (explicit
     * delete, the ended-conversation retention sweep).
     * <p>
     * One entry per conversation means one entry more for every conversation that
     * entered a secret; without this they would outlive their conversations
     * forever. An entry qualifies only when its key carries the conversation id as
     * {@code <agentId>.<conversationId>.<property>} AND its description is the one
     * {@link #vault} writes, so nothing an operator created is touched. Entries are
     * found with one listing for the whole batch. Best effort: a failure is logged
     * and never stops the conversation delete.
     *
     * @return the number of entries deleted
     */
    public int deleteConversationSecrets(Collection<String> conversationIds) {
        if (conversationIds == null || conversationIds.isEmpty() || !secretProvider.isAvailable()) {
            return 0;
        }
        Set<String> ids = new HashSet<>(conversationIds);
        return deleteMatching(entry -> ids.contains(conversationIdOf(entry)),
                "the secrets of " + conversationIds.size() + " deleted conversation(s)");
    }

    /**
     * Delete the auto-vaulted entries whose conversation no longer exists — the
     * reconciliation behind {@link #deleteConversationSecrets}, run by the
     * retention sweep.
     * <p>
     * That call is best effort and runs after the conversation is gone, so a vault
     * failure at that moment used to leave the entry behind for good. Two more
     * paths never call it at all: a start turn that vaults a secret and then fails
     * before the conversation is first stored, and an erasure that removes
     * conversations in bulk. This sweep finds all three by what they have in common
     * — an entry {@link #vault} wrote whose conversation is not in the store — so a
     * missed delete is retried on the next run instead of being lost.
     * <p>
     * Only entries last written before {@code writtenBefore} qualify: a
     * conversation's first turn writes its entry before the conversation is stored,
     * and must not lose it to a sweep that runs in between. An entry without a
     * timestamp, or whose conversation cannot be looked up, is kept.
     *
     * @param conversationExists
     *            whether the conversation with this id is still stored
     * @return the number of entries deleted
     */
    public int deleteOrphanedConversationSecrets(Predicate<String> conversationExists, Instant writtenBefore) {
        if (conversationExists == null || writtenBefore == null || !secretProvider.isAvailable()) {
            return 0;
        }
        return deleteMatching(entry -> {
            String conversationId = conversationIdOf(entry);
            Instant lastWritten = entry.lastRotatedAt() != null ? entry.lastRotatedAt() : entry.createdAt();
            if (conversationId == null || lastWritten == null || !lastWritten.isBefore(writtenBefore)) {
                return false;
            }
            try {
                return !conversationExists.test(conversationId);
            } catch (RuntimeException e) {
                LOGGER.debugf("Kept vault entry of conversation '%s': its conversation could not be looked up (%s)", sanitize(conversationId),
                        e.getMessage());
                return false;
            }
        }, "orphaned conversation secrets");
    }

    /**
     * Deletes every default-tenant entry {@code selected} accepts, found with one
     * listing. Best effort: a failure is logged and never thrown.
     */
    private int deleteMatching(Predicate<SecretMetadata> selected, String what) {
        List<SecretMetadata> entries;
        try {
            entries = secretProvider.listKeys(SecretReference.DEFAULT_TENANT);
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.warnf("Could not list vault entries to remove %s: %s", what, e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (SecretMetadata entry : entries) {
            if (!selected.test(entry)) {
                continue;
            }
            var ref = new SecretReference(SecretReference.DEFAULT_TENANT, entry.keyName());
            try {
                secretProvider.delete(ref);
                secretResolver.invalidateCache(ref);
                deleted++;
            } catch (ISecretProvider.SecretNotFoundException e) {
                // already gone — nothing to do
            } catch (ISecretProvider.SecretProviderException e) {
                LOGGER.warnf("Could not delete vault entry '%s' of conversation '%s': %s", sanitize(entry.keyName()),
                        sanitize(conversationIdOf(entry)), e.getMessage());
            }
        }
        return deleted;
    }

    /**
     * The conversation an auto-vaulted entry belongs to, or {@code null} when the
     * entry is not one {@link #vault} wrote.
     */
    private static String conversationIdOf(SecretMetadata entry) {
        String description = entry.description();
        if (description == null || !description.startsWith(AUTO_VAULT_DESCRIPTION_PREFIX) || entry.keyName() == null) {
            return null;
        }
        String conversationId = description.substring(AUTO_VAULT_DESCRIPTION_PREFIX.length());
        return entry.keyName().contains("." + conversationId + ".") ? conversationId : null;
    }

    /**
     * Removes the plaintext of a {@code scope: "secret"} property from EVERY part
     * of the current conversation step, so it cannot survive in the conversation
     * document that gets persisted for this turn (successful or aborted).
     * <p>
     * Two earlier defects this closes:
     * <ol>
     * <li><strong>Only {@code input:initial} was rewritten.</strong>
     * {@code InputParserTask} is always the first workflow step and has already
     * copied the same text into {@code input:normalized} of the same step, and
     * {@code ConversationMemoryUtilities} serializes every datum of a step
     * (committed or not) into the stored document. Both keys — plus any other
     * datum, context value or conversation-output entry that happens to carry the
     * text — are rewritten now.</li>
     * <li><strong>The scrub was gated on byte equality with
     * {@code input:initial}.</strong> The canonical {@code valueString:
     * "{memory.current.input}"} resolves to the NORMALIZED input, so as soon as any
     * parser normalizer is configured the resolved secret is not byte-identical to
     * the raw input and the scrub silently did nothing — leaking exactly what the
     * fail-closed path claims to prevent. Matching is containment-based and
     * additionally normalization-insensitive (punctuation/whitespace
     * stripped).</li>
     * </ol>
     * A scrub that finds nothing is logged at WARN (never silently ignored): the
     * value may legitimately come from a static config literal or data that was
     * never in the step, but if it came from the user it means the raw input is
     * still in the document.
     *
     * @param keyName
     *            property name, for the diagnostic only — never the value
     */
    void scrubSecretInput(IConversationMemory memory, String keyName, String plaintext) {
        if (isNullOrEmpty(plaintext)) {
            return;
        }
        var currentStep = memory.getCurrentStep();
        boolean inputScrubbed = false;
        boolean anythingScrubbed = false;

        // (1) The known input-carrying keys of this step.
        for (String inputKey : List.of(INPUT_INITIAL_IDENTIFIER, INPUT_NORMALIZED_IDENTIFIER)) {
            IData<String> inputData = currentStep.getLatestData(inputKey);
            if (inputData != null && carriesSecret(inputData.getResult(), plaintext)) {
                storeScrubbed(currentStep, inputKey, SECRET_INPUT_PLACEHOLDER);
                inputScrubbed = true;
                anythingScrubbed = true;
            }
        }

        // (2) Every other datum of the step that carries the plaintext verbatim —
        // including a `context:<key>` value, which is how a client-supplied secret
        // reaches a `{context.x}` property instruction, and a saved httpcall response,
        // which is where a token fetched by a post-response instruction came from.
        for (IData<?> data : currentStep.getAllElements()) {
            String key = data.getKey();
            if (INPUT_INITIAL_IDENTIFIER.equals(key) || INPUT_NORMALIZED_IDENTIFIER.equals(key)) {
                continue;
            }
            Object cleaned = SecretValueScrubber.scrubValue(data.getResult(), plaintext, SECRET_INPUT_PLACEHOLDER);
            if (cleaned != null) {
                storeScrubbed(currentStep, key, cleaned);
                anythingScrubbed = true;
            }
        }

        // (3) The conversation output of the step — the projection returned to the
        // client and stored alongside the step data.
        var conversationOutput = currentStep.getConversationOutput();
        if (conversationOutput != null) {
            for (var outputEntry : conversationOutput.entrySet()) {
                Object cleaned = SecretValueScrubber.scrubValue(outputEntry.getValue(), plaintext, SECRET_INPUT_PLACEHOLDER);
                if (cleaned != null) {
                    outputEntry.setValue(cleaned);
                    anythingScrubbed = true;
                }
            }
        }

        if (inputScrubbed) {
            // The echoed input is replaced wholesale rather than patched: after a
            // normalizer the echoed form need not contain the resolved secret verbatim.
            currentStep.resetConversationOutput(INPUT_OUTPUT_KEY);
            currentStep.addConversationOutputString(INPUT_OUTPUT_KEY, SECRET_INPUT_PLACEHOLDER);
            dropParsedForms(currentStep);
        }

        if (!anythingScrubbed) {
            LOGGER.warnf("Could not locate the plaintext of scope='secret' property '%s' anywhere in the current "
                    + "conversation step — nothing was scrubbed. If the value came from user input, the raw input may "
                    + "still be persisted in the conversation document.", keyName);
        }
    }

    /**
     * Replace everything the parser DERIVED from a scrubbed input — the parsed
     * expressions, the per-token match details, the intents, and any properties
     * extracted from them — and drop their echoes from the conversation output.
     * <p>
     * Patching these by containment does not work: the parser tokenizes the input
     * and wraps the pieces ({@code unknown(sk-live_abc)}, {@code "sk-live_abc" →
     * unknown(...)}), so the resolved secret is never a substring of them and the
     * verbatim scrub left the key in the stored step. The behavior rules of this
     * workflow that consume them have already run by the time a property setter
     * executes. A LATER workflow of a multi-workflow agent sees them empty for this
     * turn — deliberate: its input matchers would otherwise be matching against a
     * vaulted secret.
     */
    private void dropParsedForms(IWritableConversationStep currentStep) {
        if (currentStep.getLatestData(EXPRESSIONS_PARSED_IDENTIFIER) != null) {
            storeScrubbed(currentStep, EXPRESSIONS_PARSED_IDENTIFIER, "");
        }
        for (String derivedListKey : List.of(EXPRESSIONS_MATCHES_IDENTIFIER, INTENTS_IDENTIFIER, PROPERTIES_EXTRACTED_IDENTIFIER)) {
            if (currentStep.getLatestData(derivedListKey) != null) {
                storeScrubbed(currentStep, derivedListKey, List.of());
            }
        }
        currentStep.removeConversationOutput(EXPRESSIONS_OUTPUT_KEY);
        currentStep.removeConversationOutput(INTENTS_IDENTIFIER);
    }

    /**
     * Whether {@code value} carries {@code plaintext}: verbatim, or equal/contained
     * once punctuation and whitespace are stripped from both. The second form is
     * what a configured parser normalizer produces — the resolved secret is the
     * NORMALIZED input, never byte-identical to the raw one.
     */
    private static boolean carriesSecret(String value, String plaintext) {
        if (isNullOrEmpty(value)) {
            return false;
        }
        if (value.contains(plaintext)) {
            return true;
        }
        String normalizedValue = alphanumericOnly(value);
        String normalizedSecret = alphanumericOnly(plaintext);
        if (normalizedValue.length() >= MIN_NORMALIZED_MATCH_LENGTH && normalizedSecret.contains(normalizedValue)) {
            return true;
        }
        return normalizedSecret.length() >= MIN_NORMALIZED_MATCH_LENGTH && normalizedValue.contains(normalizedSecret);
    }

    /** The letters and digits of {@code value}, in order. */
    private static String alphanumericOnly(String value) {
        var builder = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    /**
     * Replaces the datum stored under {@code key} with the scrubbed value.
     * Tolerates a null from the data factory so a partially stubbed step in a unit
     * test cannot turn a security scrub into an NPE.
     */
    private void storeScrubbed(IWritableConversationStep currentStep, String key, Object value) {
        IData<Object> replacement = dataFactory.createData(key, value);
        if (replacement != null) {
            currentStep.storeData(replacement);
        }
    }
}
