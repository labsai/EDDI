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
import ai.labs.eddi.secrets.AutoVaultedSecrets;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.configs.properties.model.Property.Scope.conversation;
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
 * none. Every write goes to its own slot — see {@link AutoVaultedSecrets}.
 * <p>
 * This is the vaulting and step scrub {@code PropertySetterTask} used to do
 * itself, moved here unchanged so the other property paths share it: they used
 * to store a {@code scope: "secret"} value as a plaintext property.
 * <p>
 * Fails closed throughout: a disabled vault, a value that is not a string, or a
 * property name that cannot be embedded in a reference all abort the turn with
 * a {@link LifecycleException}. The value is never stored as a plaintext
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

    private final ISecretProvider secretProvider;
    private final IDataFactory dataFactory;

    @Inject
    public SecretPropertyVault(ISecretProvider secretProvider, IDataFactory dataFactory) {
        this.secretProvider = secretProvider;
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
        if (!AutoVaultedSecrets.isEmbeddable(name)) {
            scrubSecretInput(memory, name, plaintext);
            throw new LifecycleException("Cannot store property '" + name + "' with scope 'secret': the property name must not contain "
                    + "'/', '{', '}' or '$'. Refusing to persist the value in plaintext.");
        }
        // The ONLY place this marker is ever set. It is what lets ConfigReferenceGuard
        // tell this reference apart from the identical string arriving through
        // conversation data — the scope it is stored under is conversation either way,
        // so nothing else can.
        var vaulted = new Property(name, autoVaultSecret(memory, name, plaintext), conversation);
        vaulted.setAutoVaulted(Boolean.TRUE);
        return vaulted;
    }

    /**
     * Store a plaintext secret in the vault and return the vault reference string.
     * Also scrubs the raw user input from conversation memory to prevent leakage.
     * <p>
     * <b>Agent designers never see vault references for auto-vaulted secrets.</b>
     * They simply write {@code { "name": "userApiKey", "scope": "secret" }} in the
     * PropertySetter config. This method transparently vaults the user input and
     * stores a vault reference in conversation properties. Templates use
     * {@code {properties.userApiKey}} — the SecretResolver resolves transparently.
     * <p>
     * Every write goes to its own slot,
     * {@code <agentId>.u<userHash>.<nonce>.<keyName>} (see
     * {@link AutoVaultedSecrets}), so no other user or conversation can resolve it;
     * the slot the property pointed to before is kept for undo and deleted with the
     * conversation. Since the tenant is typically "default", the short-form syntax
     * is used.
     *
     * @param memory
     *            the conversation memory (used for agentId and input scrubbing)
     * @param keyName
     *            the property name used as the vault key
     * @param plaintext
     *            the secret value to store
     * @return the vault reference string, e.g.
     *         {@code ${vault:69c687.u1a2b3c4d5e6f7a8b.0f1e2d3c4b5a.userApiKey}}
     * @throws LifecycleException
     *             when the vault is unavailable or disabled. This method fails
     *             CLOSED: the raw input is scrubbed first and the plaintext is
     *             never stored as a conversation property, so a
     *             {@code scope: "secret"} property can never silently degrade to a
     *             plaintext secret persisted twice (property +
     *             {@code input:initial} ) in the conversation document.
     */
    private String autoVaultSecret(IConversationMemory memory, String keyName, String plaintext) throws LifecycleException {
        // Determine tenantId — use conversation property if set, else "default"
        var conversationProperties = memory.getConversationProperties();
        String tenantId = "default";
        if (conversationProperties.containsKey("tenantId")) {
            Property tenantProp = conversationProperties.get("tenantId");
            if (tenantProp.getValueString() != null) {
                tenantId = tenantProp.getValueString();
            }
        }

        String agentId = memory.getAgentId();
        // A fresh slot per write, attributable to the user (see AutoVaultedSecrets).
        // The slot used to be <agentId>.<keyName> — shared by every user and every
        // conversation of the agent, so the last writer's secret was what every
        // conversation's reference resolved to.
        String qualifiedKeyName = AutoVaultedSecrets.newSlotName(agentId, memory.getUserId(), keyName);
        var ref = new SecretReference(tenantId, qualifiedKeyName);

        // Store the plaintext in the vault (encrypted at rest)
        try {
            secretProvider.store(ref, plaintext, "Auto-vaulted from conversation", List.of(agentId));
        } catch (ISecretProvider.SecretProviderException e) {
            // Fail CLOSED. The previous behaviour returned the plaintext, which was then
            // persisted TWICE — as a conversation property and (because the scrub below
            // was skipped) as the raw input:initial data. A disabled vault is the default
            // (eddi.vault.master-key ships empty), so that was the common path.
            // Scrub the raw input BEFORE aborting so the plaintext cannot survive in the
            // conversation document that is persisted for the failed turn either.
            scrubSecretInput(memory, keyName, plaintext);
            LOGGER.errorf("Failed to store secret in vault for property '%s': %s", keyName, e.getMessage());
            throw new LifecycleException("Cannot store property '" + keyName + "' with scope 'secret': the secrets vault is unavailable or "
                    + "disabled (set EDDI_VAULT_MASTER_KEY). Refusing to persist the value in plaintext.", e);
        }

        scrubSecretInput(memory, keyName, plaintext);

        // The slot the property pointed at before is NOT deleted: this turn's property
        // delta records it, and undo restores that reference. It is deleted with the
        // conversation, which sweeps every version in the step history and redo cache.

        // Return the vault reference to be stored in properties instead of plaintext
        return ref.toReferenceString();
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
     * value may legitimately come from a static config literal or a non-string
     * context, but if it came from the user it means the raw input is still in the
     * document.
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
        // The resolved value plus every input form replaced below: when the match is
        // normalization-insensitive, a copy of the differently formatted raw input
        // elsewhere in the step does not contain the resolved value verbatim.
        List<String> needles = new ArrayList<>(List.of(plaintext));

        // (1) The known input-carrying keys of this step.
        for (String inputKey : List.of(INPUT_INITIAL_IDENTIFIER, INPUT_NORMALIZED_IDENTIFIER)) {
            IData<String> inputData = currentStep.getLatestData(inputKey);
            if (inputData != null && carriesSecret(inputData.getResult(), plaintext)) {
                needles.add(inputData.getResult());
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
            Object cleaned = SecretValueScrubber.scrubAll(data.getResult(), needles, SECRET_INPUT_PLACEHOLDER);
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
                Object cleaned = SecretValueScrubber.scrubAll(outputEntry.getValue(), needles, SECRET_INPUT_PLACEHOLDER);
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
