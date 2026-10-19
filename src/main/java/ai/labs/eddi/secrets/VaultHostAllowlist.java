/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces a secret's {@code allowedHosts} where a {@code ${vault:…}} reference
 * is resolved for an outbound destination.
 * <p>
 * The agent grant ({@code allowedAgents}) says <em>which agent</em> may use a
 * key and is checked when the agent is deployed. It says nothing about
 * <em>where</em> the key goes: an editor who may change an agent's LLM
 * configuration can point {@code baseUrl} at a host of their own and keep
 * {@code apiKey: ${vault:openai-key}}, and the deploy-time check passes because
 * the agent is granted the key. The destination is only known at the moment the
 * reference is resolved — the API call's target URL, the model's base URL — so
 * that is where this check runs, by the two callers that resolve for an
 * outbound request: {@code ApiCallExecutor} and {@code ChatModelRegistry}.
 * <p>
 * <b>Matching.</b> An entry is an exact {@code host} or {@code host:port},
 * compared case-insensitively. {@code host} alone matches that host on any
 * port; {@code host:port} matches only that port, where the destination's port
 * is the one in its URL or, absent that, the scheme default (443 for https, 80
 * for http). No wildcards, no suffix matching: {@code example.com} does not
 * admit {@code api.example.com}, and nothing admits
 * {@code example.com.attacker.net}. A secret without a list may go anywhere,
 * which is what every secret stored before the list existed does.
 * <p>
 * <b>Failure is closed.</b> A reference whose metadata cannot be read is
 * refused: a key that cannot be checked is not a key that may go anywhere. A
 * reference that does not exist is left to the resolver, which already refuses
 * to send the unresolved reference. The refusal message names the reference,
 * the destination and the list — none of which is a secret — so the operator
 * can fix the list or the configuration without reading a log.
 */
@ApplicationScoped
public class VaultHostAllowlist {

    private static final Logger LOGGER = Logger.getLogger(VaultHostAllowlist.class);

    /**
     * What an allow-list entry may look like: a host name or IP literal, optionally
     * with a port. Bracketed IPv6 is accepted in the same shape a URL writes it.
     */
    private static final Pattern ENTRY = Pattern.compile("(?:[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?(?:\\.[a-z0-9]([a-z0-9-]{0,62}[a-z0-9])?)*\\.?"
            + "|\\[[0-9a-f:.]+])(?::[0-9]{1,5})?");

    /**
     * Upper bound on a list — a guard against a pasted file, not a security
     * control.
     */
    public static final int MAX_ALLOWED_HOSTS = 100;

    private final ISecretProvider secretProvider;

    @Inject
    public VaultHostAllowlist(ISecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    /**
     * Validates and normalises a list as it arrives in a request body.
     *
     * @return the entries lower-cased, trimmed and de-duplicated, in the operator's
     *         order; an empty list for an empty input
     * @throws IllegalArgumentException
     *             with an operator-readable message naming the offending entry
     */
    public static List<String> validatedHosts(List<String> allowedHosts) {
        if (allowedHosts == null) {
            return null;
        }
        if (allowedHosts.size() > MAX_ALLOWED_HOSTS) {
            throw new IllegalArgumentException("allowedHosts must not contain more than " + MAX_ALLOWED_HOSTS + " entries.");
        }
        var distinct = new LinkedHashSet<String>();
        for (String entry : allowedHosts) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("allowedHosts must not contain blank entries.");
            }
            String normalised = entry.trim().toLowerCase(Locale.ROOT);
            if (normalised.contains("://") || normalised.contains("/") || normalised.contains("*")) {
                throw new IllegalArgumentException("allowedHosts entries are exact host or host:port values — no scheme, no path, no "
                        + "wildcard. Got: " + entry.trim());
            }
            if (!ENTRY.matcher(normalised).matches() || !validPort(normalised)) {
                throw new IllegalArgumentException("allowedHosts entry is not a host or host:port: " + entry.trim());
            }
            distinct.add(normalised);
        }
        return new ArrayList<>(distinct);
    }

    /**
     * The one entry that says "no destination restriction" out loud. It is accepted
     * only alone, as {@code ["*"]}, and stored as the empty list every other layer
     * already reads as unrestricted; it is never a pattern.
     */
    public static final String ANY_HOST = "*";

    /** Whether a request list is exactly {@code ["*"]}. */
    public static boolean isExplicitlyUnrestricted(List<String> allowedHosts) {
        return allowedHosts != null && allowedHosts.size() == 1 && allowedHosts.getFirst() != null
                && ANY_HOST.equals(allowedHosts.getFirst().trim());
    }

    /**
     * {@link #validatedHosts} for a REST body that may also say {@code ["*"]}: that
     * comes back as the empty list, which is what an unrestricted secret stores.
     * {@code null} stays {@code null} ("not supplied").
     */
    public static List<String> requestedHosts(List<String> allowedHosts) {
        return isExplicitlyUnrestricted(allowedHosts) ? List.of() : validatedHosts(allowedHosts);
    }

    private static boolean validPort(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon < 0 || entry.endsWith("]")) {
            return true;
        }
        try {
            int port = Integer.parseInt(entry.substring(colon + 1));
            return port >= 1 && port <= 65535;
        } catch (NumberFormatException e) {
            // ENTRY admits only digits here, but an overlong run overflows parseInt;
            // either way the entry is not a port.
            return false;
        }
    }

    /**
     * Refuses {@code destination} for every vault reference in {@code references}
     * whose secret restricts its hosts to a list the destination is not on.
     *
     * @param references
     *            the reference strings ({@code ${vault:…}}) that were, or are about
     *            to be, resolved into the request
     * @param destination
     *            the request's target: a URL, or a bare {@code host[:port]}
     * @param usedFor
     *            what is being sent, for the message (e.g. {@code "API call
     *            'weather'"})
     * @throws HostNotAllowedException
     *             when a secret may not be sent there, or its list could not be
     *             read
     */
    public void requireAllowed(Iterable<String> references, String destination, String usedFor) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            SecretReference ref;
            try {
                ref = SecretReference.parse(reference);
            } catch (IllegalArgumentException e) {
                continue; // Not a vault reference: nothing to check.
            }
            if (SecretReference.isReservedKeyName(ref.keyName())) {
                continue; // The resolver refuses these outright.
            }
            List<String> allowedHosts;
            try {
                allowedHosts = secretProvider.getMetadata(ref).allowedHosts();
            } catch (ISecretProvider.SecretNotFoundException e) {
                continue; // The resolver fails closed on a missing secret.
            } catch (ISecretProvider.SecretProviderException e) {
                LOGGER.errorf("Could not read the allowed hosts of %s for %s: %s", sanitize(reference), sanitize(usedFor), e.getMessage());
                throw new HostNotAllowedException("The allowed hosts of " + reference + " could not be read, so " + usedFor
                        + " is refused rather than sent with a secret whose destination list is unknown.");
            }
            if (!SecretMetadata.restrictsHosts(allowedHosts)) {
                continue;
            }
            HostPort target = HostPort.of(destination);
            if (target == null || !isAllowed(target, allowedHosts)) {
                throw new HostNotAllowedException(usedFor + " sends " + reference + " to " + (target == null ? "an unparseable destination" : target)
                        + ", which is not on that secret's allowedHosts " + allowedHosts
                        + ". Add the host with PUT /secretstore/secrets/" + ref.tenantId() + "/" + ref.keyName()
                        + "/grant {\"allowedHosts\": [...]} (an eddi-admin call that needs no secret value), or point the configuration at an "
                        + "allowed host.");
            }
        }
    }

    /**
     * The map form of {@link #requireAllowed}: every {@code ${vault:…}} reference
     * found in any value of {@code params} is checked against {@code destination}.
     */
    public void requireAllowed(Map<String, String> params, String destination, String usedFor) {
        if (params == null || params.isEmpty()) {
            return;
        }
        requireAllowed(referencesIn(params.values()), destination, usedFor);
    }

    /** Every vault reference string occurring in {@code values}, de-duplicated. */
    public static Set<String> referencesIn(Iterable<String> values) {
        var references = new LinkedHashSet<String>();
        Pattern pattern = SecretReference.compiledPattern();
        for (String value : values) {
            if (value == null || !SecretReference.isVaultReference(value)) {
                continue;
            }
            Matcher matcher = pattern.matcher(value);
            while (matcher.find()) {
                references.add(matcher.group());
            }
        }
        return references;
    }

    static boolean isAllowed(HostPort target, List<String> allowedHosts) {
        for (String entry : allowedHosts) {
            if (entry == null) {
                continue;
            }
            String allowed = entry.trim().toLowerCase(Locale.ROOT);
            if (allowed.equals(target.host()) || allowed.equals(target.host() + ":" + target.port())) {
                return true;
            }
        }
        return false;
    }

    /**
     * A destination reduced to what the list is compared against. {@code port} is
     * the explicit or scheme-default port, or -1 when neither is known.
     */
    record HostPort(String host, int port) {

        static HostPort of(String destination) {
            if (destination == null || destination.isBlank()) {
                return null;
            }
            String value = destination.trim();
            try {
                URI uri = value.contains("://") ? new URI(value) : new URI("placeholder://" + value);
                if (uri.getHost() == null) {
                    return null;
                }
                int port = uri.getPort();
                if (port < 0 && uri.getScheme() != null) {
                    port = switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                        case "https", "wss" -> 443;
                        case "http", "ws" -> 80;
                        default -> -1;
                    };
                }
                return new HostPort(uri.getHost().toLowerCase(Locale.ROOT), port);
            } catch (URISyntaxException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return port < 0 ? host : host + ":" + port;
        }
    }

    /**
     * A secret was about to be sent somewhere its {@code allowedHosts} does not
     * name.
     */
    public static class HostNotAllowedException extends IllegalStateException {
        public HostNotAllowedException(String message) {
            super(message);
        }
    }
}
