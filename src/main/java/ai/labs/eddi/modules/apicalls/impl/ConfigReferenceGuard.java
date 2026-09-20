/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.properties.model.Property;
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
 * property the template names that {@code PropertySetterTask.autoVaultSecret}
 * itself wrote, which {@link Property#getAutoVaulted()} records. Anything else
 * came from data, and the call is refused rather than resolved.
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
final class ConfigReferenceGuard {

    /** The references resolved after templating that release a credential. */
    static final Pattern CREDENTIAL_REFERENCE = Pattern.compile("\\$\\{(?:vault|eddivault|connection|caller):[^}]*\\}");

    /**
     * {@code {properties.name}} and {@code properties.name} inside a template
     * expression.
     */
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
    static void requireConfiguredReferences(String template, String rendered, String location, Map<String, Object> templateData,
                                            Map<String, Property> conversationProperties) {
        if (rendered == null || !rendered.contains("${")) {
            return;
        }
        Set<String> allowed = references(template);
        allowed.addAll(autoVaultReferences(template, templateData, conversationProperties));
        for (String reference : references(rendered)) {
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
     * A property qualifies only when {@code PropertySetterTask.autoVaultSecret}
     * wrote its value — the {@link Property#getAutoVaulted()} marker, which that
     * method is the only writer of. This is a provenance test, not a shape test:
     * the value a {@code scope: "secret"} instruction stores is a plain
     * conversation-scoped string, character-for-character reproducible by anyone
     * who can write a property, so no amount of inspecting the value can establish
     * where it came from. Only a marker set at the moment of vaulting can.
     * <p>
     * <b>The marker is necessary, not sufficient.</b> The value must still be this
     * conversation's own auto-vault reference for the property the template names:
     * key {@code <agentId>.<name>} for this conversation's agent, under this
     * conversation's tenant. The marker already implies all three, because
     * {@code autoVaultSecret} derives them itself — so the comparison is redundant
     * by construction and deliberately kept anyway, as the bound that still holds
     * if a marked {@code Property} ever reaches memory from somewhere other than
     * that method (a restored document, a future writer). A marked property whose
     * tenant has since been rewritten under it fails this comparison and the call
     * is refused, which is the safe direction of that corner.
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
        String agentId = agentId(templateData);
        if (agentId == null) {
            return found;
        }
        String tenantId = tenantId(conversationProperties);
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
            if (value == null) {
                continue;
            }
            String key = SecretReference.DEFAULT_TENANT.equals(tenantId) ? agentId + "." + name : tenantId + "/" + agentId + "." + name;
            // The legacy prefix too: a conversation property stored before the
            // ${eddivault:…} → ${vault:…} rename still holds the old spelling, and the
            // vault still resolves it.
            if (value.equals("${vault:" + key + "}") || value.equals("${eddivault:" + key + "}")) {
                found.add(value);
            }
        }
        return found;
    }

    private static String agentId(Map<String, Object> templateData) {
        if (templateData.get("conversationInfo") instanceof Map<?, ?> info && info.get("agentId") != null) {
            return String.valueOf(info.get("agentId"));
        }
        return null;
    }

    /**
     * The conversation's tenant, read the same way
     * {@code PropertySetterTask.autoVaultSecret} reads it: the {@code valueString}
     * of the {@code tenantId} property, defaulting to {@code default}.
     */
    private static String tenantId(Map<String, Property> conversationProperties) {
        Property tenant = conversationProperties.get("tenantId");
        String tenantId = tenant != null ? tenant.getValueString() : null;
        return tenantId != null && !tenantId.isBlank() ? tenantId : SecretReference.DEFAULT_TENANT;
    }
}
