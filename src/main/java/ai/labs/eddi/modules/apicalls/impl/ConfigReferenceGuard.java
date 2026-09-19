/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

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
 * property the template names, where that value is exactly this agent's own
 * auto-vault reference for that property ({@code ${vault:<agentId>.<name>}},
 * what a {@code scope: "secret"} property instruction stores). Anything else
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
     * @throws IllegalArgumentException
     *             if {@code rendered} holds a credential reference the
     *             configuration did not write
     */
    static void requireConfiguredReferences(String template, String rendered, String location, Map<String, Object> templateData) {
        if (rendered == null || !rendered.contains("${")) {
            return;
        }
        Set<String> allowed = references(template);
        allowed.addAll(autoVaultReferences(template, templateData));
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
     * A property qualifies only when its value is character-for-character one of
     * the references {@code PropertySetterTask.autoVaultSecret} could have written
     * for <em>that</em> property: the key is {@code <agentId>.<name>} for this
     * conversation's agent and the name the template reads, and the tenant is this
     * conversation's own. Built by string comparison rather than a pattern compiled
     * per call, so there is no dynamic regex to reason about.
     * <p>
     * <b>What this is and is not.</b> It is a shape test, not a provenance test:
     * {@code Property} carries no marker saying "auto-vaulted" (a {@code secret}
     * instruction stores the reference with {@code scope: conversation}, exactly
     * like any other), so a property populated from data with that exact string is
     * accepted too. The bound on that is what makes it acceptable: the reference is
     * derived from this agent and this property name, so data cannot choose WHICH
     * secret is read, and the endpoint it would be sent to is the one the
     * configuration names. Pinning the tenant is what closes the part that did
     * matter — the earlier pattern accepted any {@code <tenant>/} prefix, so a
     * user-supplied value could read another tenant's secret of the same key name.
     * The residual path is a configuration that writes the {@code tenantId}
     * property from conversation data, which redirects legitimate auto-vaulting the
     * same way. A real provenance check needs a marker on {@code Property} and is
     * tracked separately.
     */
    private static Set<String> autoVaultReferences(String template, Map<String, Object> templateData) {
        Set<String> found = new LinkedHashSet<>();
        if (template == null || templateData == null) {
            return found;
        }
        String agentId = agentId(templateData);
        if (agentId == null || !(templateData.get("properties") instanceof Map<?, ?> properties)) {
            return found;
        }
        String tenantId = tenantId(properties);
        Matcher access = PROPERTY_ACCESS.matcher(template);
        while (access.find()) {
            String name = access.group(1);
            if (!(properties.get(name) instanceof String value)) {
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
     * {@code PropertySetterTask.autoVaultSecret} reads it: the {@code tenantId}
     * property, defaulting to {@code default}.
     */
    private static String tenantId(Map<?, ?> properties) {
        return properties.get("tenantId") instanceof String tenantId && !tenantId.isBlank() ? tenantId : SecretReference.DEFAULT_TENANT;
    }
}
