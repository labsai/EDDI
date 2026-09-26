/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.secrets.model.AutoVaultReference;
import ai.labs.eddi.secrets.model.SecretReference;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes sure an apicall resolves only the credential references its
 * configuration wrote.
 * <p>
 * {@code ApiCallExecutor} renders a template first and resolves
 * {@code ${vault:...}}, {@code ${eddivault:...}}, {@code ${connection:...}} and
 * {@code ${caller:...}} in the rendered string afterwards. Conversation data is
 * substituted into that string verbatim — the user's message, a model reply, an
 * earlier API response, client context. So a body of {@code {"q":
 * "{memory.current.input}"}} and the input {@code ${vault:another-agents-key}}
 * rendered to a string holding that reference, and the resolver then put the
 * plaintext secret into the outgoing request. Grants did not stop it: they are
 * checked when an agent is deployed, not when a secret is read.
 * <p>
 * The rule: every credential reference in the rendered value must also appear
 * in the configuration template of that same field — or be the value of a
 * property the template names that {@code SecretPropertyVault} itself wrote,
 * which {@link Property#getAutoVaulted()} records. Anything else came from
 * data, and the call is refused rather than resolved.
 * <p>
 * {@code ${vars:...}} is not itself a credential reference: global variables
 * hold configuration such as model names and base URLs, and resolving one a
 * user typed reveals nothing an agent configuration could not already show. A
 * variable MAY however hold a credential reference, which makes a data-supplied
 * {@code ${vars:...}} an indirection to one — so
 * {@code ApiCallExecutor#resolveGuardedVariables} calls this again after
 * variable expansion, with the configured template expanded the same way. This
 * class is unchanged by that: it only ever compares the references of a
 * configured value against the references of a rendered one.
 */
public final class ConfigReferenceGuard {

    /** The references resolved after templating that release a credential. */
    static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("\\$\\{(?:vault|eddivault|connection|caller):[^}]*\\}");

    /**
     * {@code {properties.name}} and {@code properties.name} inside a template
     * expression.
     */
    /**
     * A global variable reference: not a credential, but a variable may hold one.
     */
    private static final Pattern VARS_REFERENCE = Pattern.compile("\\$\\{vars:[^}]*\\}");

    private static final Pattern PROPERTY_ACCESS = Pattern.compile("properties\\.([A-Za-z0-9_\\-]+)");

    private ConfigReferenceGuard() {
    }

    /**
     * @param template
     *            the configured value of the field, before templating
     * @param rendered
     *            the same value after templating, before any reference is resolved
     * @param location
     *            human-readable field name for the error, e.g. "a request body"
     * @param templateData
     *            the data the template was rendered with
     * @param conversationProperties
     *            the live properties of the conversation, carrying the provenance
     *            marker that {@code templateData} has already flattened away
     * @throws IllegalArgumentException
     *             if {@code rendered} holds a credential reference the
     *             configuration did not write
     */
    public static void requireConfiguredReferences(String template, String rendered, String location, Map<String, Object> templateData,
                                                   Map<String, Property> conversationProperties) {
        if (rendered == null || !rendered.contains("${")) {
            return;
        }
        Set<String> allowed = references(template);
        allowed.addAll(autoVaultReferences(template, templateData, conversationProperties));
        for (String reference : references(rendered)) {
            if (!allowed.contains(reference) && legacyAutoVaultReferences(template, templateData, conversationProperties).contains(reference)) {
                // Not an injection: the property really was vaulted by this process, under
                // the shared per-agent key an earlier release used. That entry holds
                // whichever user entered a value last, so it is not resolved — but the
                // operator must not be sent chasing a phantom attack.
                throw new IllegalArgumentException(location + " uses " + reference
                        + ", a secret this conversation stored under the shared per-agent vault key of an earlier release. That entry "
                        + "may hold another user's value, so it is no longer resolved: ask the user to enter the secret again (or start "
                        + "a new conversation).");
            }
            if (!allowed.contains(reference)) {
                throw new IllegalArgumentException(location + " contains the reference " + reference
                        + ", which the agent configuration does not write there: it came from conversation data (user input, a model reply,"
                        + " an API response or client context). References are only resolved where the configuration wrote them,"
                        + " so the call is refused.");
            }
        }
    }

    private static Set<String> references(String value) {
        Set<String> found = new LinkedHashSet<>();
        if (value != null) {
            Matcher matcher = CREDENTIAL_REFERENCE.matcher(value);
            while (matcher.find()) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    /**
     * The auto-vault references of the properties {@code template} names.
     * <p>
     * A property qualifies only when {@code SecretPropertyVault} wrote its value —
     * the {@link Property#getAutoVaulted()} marker, which that class is the only
     * writer of. This is a provenance test, not a shape test: the value a
     * {@code scope: "secret"} instruction stores is a plain conversation-scoped
     * string, character-for-character reproducible by anyone who can write a
     * property, so no amount of inspecting the value can establish where it came
     * from. Only a marker set at the moment of vaulting can.
     * <p>
     * <b>The marker is necessary, not sufficient.</b> The value must still be this
     * conversation's own auto-vault reference for the property the template names:
     * {@link AutoVaultReference#of} of this conversation's agent, this conversation
     * and that property. The marker already implies all three, so the comparison is
     * redundant by construction and deliberately kept anyway, as the bound that
     * still holds if a marked {@code Property} ever reaches memory from somewhere
     * other than that class (a restored document, a future writer).
     * <p>
     * A reference written under the old, per-agent key
     * ({@code <agentId>.<property>}, shared by every conversation of the agent,
     * optionally under a client-settable tenant) no longer qualifies: that entry
     * held whichever user's value was written last, which is the defect the
     * per-conversation key removes. Such a conversation has to have the secret
     * entered again.
     * <p>
     * Built by string comparison rather than a pattern compiled per call, so there
     * is no dynamic regex to reason about.
     */
    private static Set<String> autoVaultReferences(String template, Map<String, Object> templateData,
                                                   Map<String, Property> conversationProperties) {
        Set<String> found = new LinkedHashSet<>();
        if (template == null || templateData == null || conversationProperties == null || conversationProperties.isEmpty()) {
            return found;
        }
        String agentId = conversationInfo(templateData, "agentId");
        String conversationId = conversationInfo(templateData, "conversationId");
        if (agentId == null || conversationId == null) {
            return found;
        }
        Matcher access = PROPERTY_ACCESS.matcher(template);
        while (access.find()) {
            String name = access.group(1);
            Property property = conversationProperties.get(name);
            // Unmarked is refused. A property written from conversation data and one
            // written before the marker existed are the same null here, and one of the
            // two is the attack — see Property#getAutoVaulted for why the pair is failed
            // closed rather than grandfathered.
            if (property == null || !Boolean.TRUE.equals(property.getAutoVaulted())) {
                continue;
            }
            String value = property.getValueString();
            SecretReference expected = AutoVaultReference.of(agentId, conversationId, name);
            if (value != null && expected != null && value.equals(expected.toReferenceString())) {
                found.add(value);
            }
        }
        return found;
    }

    /**
     * The marked properties {@code template} names whose value is the auto-vault
     * reference of an earlier release: {@code <agentId>.<property>}, optionally
     * under a tenant or with the legacy {@code eddivault} prefix. Only used to word
     * the refusal — these are never allowed.
     */
    private static Set<String> legacyAutoVaultReferences(String template, Map<String, Object> templateData,
                                                         Map<String, Property> conversationProperties) {
        Set<String> found = new LinkedHashSet<>();
        if (template == null || templateData == null || conversationProperties == null) {
            return found;
        }
        String agentId = conversationInfo(templateData, "agentId");
        if (agentId == null) {
            return found;
        }
        Matcher access = PROPERTY_ACCESS.matcher(template);
        while (access.find()) {
            String name = access.group(1);
            Property property = conversationProperties.get(name);
            if (property == null || !Boolean.TRUE.equals(property.getAutoVaulted()) || property.getValueString() == null) {
                continue;
            }
            String value = property.getValueString();
            Matcher reference = SecretReference.compiledPattern().matcher(value);
            if (reference.matches() && (agentId + "." + name).equals(reference.group(2))) {
                found.add(value);
            }
        }
        return found;
    }

    /**
     * {@link #requireConfiguredReferences} over every rendered parameter of a model
     * client, plus the {@code ${vars:…}} indirection.
     * <p>
     * The builder parameters of an LLM task, a cascade step and a cascade judge are
     * all resolved by {@code ChatModelRegistry} after templating — global variables
     * first, then {@code ${vault:…}} — so a reference conversation data put into
     * one used to be resolved with no grant check and handed to the provider
     * client. The httpcall path guards {@code ${vars:…}} by guarding again after
     * expansion; here expansion happens later, in the registry, so a data-supplied
     * variable reference is refused outright.
     *
     * @param configured
     *            the parameters as configured, before templating
     * @param rendered
     *            the same parameters after templating
     * @param exempt
     *            keys that are never resolved (the prompts) and so may carry
     *            reference-shaped text from the conversation
     * @param what
     *            the owner of the parameters, for the message (e.g. {@code "LLM"})
     * @throws IllegalArgumentException
     *             naming the first parameter that carries a reference its template
     *             did not write
     */
    public static void requireConfiguredParameters(Map<String, String> configured, Map<String, String> rendered, Set<String> exempt,
                                                   String what, Map<String, Object> templateData, Map<String, Property> conversationProperties) {
        if (rendered == null) {
            return;
        }
        for (var entry : rendered.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ((exempt != null && exempt.contains(key)) || value == null || !value.contains("${")) {
                continue;
            }
            String template = configured != null ? configured.get(key) : null;
            String location = what + " parameter '" + key + "'";
            requireConfiguredReferences(template, value, location, templateData, conversationProperties);
            Matcher variables = VARS_REFERENCE.matcher(value);
            while (variables.find()) {
                String reference = variables.group();
                if (template == null || !template.contains(reference)) {
                    throw new IllegalArgumentException(location + " contains the reference " + reference
                            + ", which the agent configuration does not write there: it came from conversation data (user input, a model "
                            + "reply, an API response or client context). References are only resolved where the configuration wrote them.");
                }
            }
        }
    }

    private static String conversationInfo(Map<String, Object> templateData, String key) {
        if (templateData.get("conversationInfo") instanceof Map<?, ?> info && info.get(key) != null) {
            return String.valueOf(info.get(key));
        }
        return null;
    }
}
