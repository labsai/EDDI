/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Validates remote sync source URLs.
 * <p>
 * The default policy is the strict one — HTTPS only, no loopback, no private
 * address — because a sync source URL is caller-supplied and carries a bearer
 * token. But it is a <em>policy</em>, not a law: plenty of real deployments run
 * staging and production as two services on one private network and promote
 * agents between them over plain HTTP, and those deployments could not use this
 * feature at all while the rules were compiled in. See {@link SyncSourcePolicy}
 * for the three settings that relax it, and {@code docs/agent-sync-guide.md}
 * for when each is appropriate.
 *
 * @since 6.0.0
 */
public final class SourceUrlValidator {

    private static final Logger LOGGER = Logger.getLogger(SourceUrlValidator.class);

    /** Names the setting an operator flips, so the refusal is actionable. */
    static final String REQUIRE_HTTPS_PROPERTY = "eddi.backup.sync.require-https";
    static final String ALLOW_PRIVATE_PROPERTY = "eddi.backup.sync.allow-private-targets";
    static final String ALLOWED_SOURCES_PROPERTY = "eddi.backup.sync.allowed-sources";

    private SourceUrlValidator() {
    }

    /**
     * What a deployment allows a sync source to be.
     *
     * @param requireHttps
     *            refuse a plain {@code http://} source. On by default: the
     *            {@code X-Source-Authorization} bearer token travels to this host,
     *            and on an untrusted network HTTP hands it to anyone on the path.
     *            Turned off for a trusted private network, where TLS may be
     *            terminated elsewhere or not used at all between services.
     * @param allowPrivateTargets
     *            permit loopback, RFC 1918, ULA, CGNAT and link-local hosts. Off by
     *            default, because with it on a caller who can reach the sync
     *            endpoint can use this deployment to probe hosts behind it. On for
     *            a single-tenant deployment whose other instances are internal —
     *            the common self-hosted case.
     * @param allowedSources
     *            exact origins ({@code scheme://host[:port]}) that are permitted
     *            whatever the other two say. This is the narrow way to reach one
     *            internal staging instance without opening the endpoint to every
     *            internal address, and it is the setting to prefer.
     */
    public record SyncSourcePolicy(boolean requireHttps, boolean allowPrivateTargets, Set<String> allowedSources) {

        /** The shipped default: HTTPS only, public addresses only, no exceptions. */
        public static final SyncSourcePolicy STRICT = new SyncSourcePolicy(true, false, Set.of());

        public SyncSourcePolicy {
            allowedSources = normalizeOrigins(allowedSources);
        }

        /**
         * Whether this exact origin was named by the operator.
         * <p>
         * Compared as scheme + host + port and nothing else: a path, a query or a
         * userinfo component in the request URL must not be able to make an origin
         * match that the operator did not name.
         */
        boolean permits(URI uri) {
            return allowedSources.contains(originOf(uri));
        }
    }

    /**
     * Validates a source URL for remote sync operations.
     *
     * @param sourceUrl
     *            the URL to validate
     * @param policy
     *            what this deployment allows
     * @throws IllegalArgumentException
     *             if the URL is malformed or the policy refuses it. The message
     *             names the setting that would allow it, because "must not point to
     *             a private IP address" with no further hint is where every
     *             internal-network deployment got stuck.
     */
    public static void validate(String sourceUrl, SyncSourcePolicy policy) {
        SyncSourcePolicy effective = policy == null ? SyncSourcePolicy.STRICT : policy;

        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new IllegalArgumentException("Source URL must not be empty");
        }

        URI uri;
        try {
            uri = URI.create(sourceUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid source URL: " + sourceUrl, e);
        }

        // Validate scheme
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("Source URL must specify a scheme (http or https): " + sourceUrl);
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Source URL must use HTTP or HTTPS: " + sourceUrl);
        }

        // Validate host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Source URL must have a valid host: " + sourceUrl);
        }

        // An origin the operator named passes whatever the other two rules say —
        // that is the entire point of naming it.
        if (effective.permits(uri)) {
            LOGGER.debugf("Source URL %s is an allow-listed origin", sanitize(sourceUrl));
            return;
        }

        if (effective.requireHttps() && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Source URL must use HTTPS: " + sourceUrl
                    + ". Set " + REQUIRE_HTTPS_PROPERTY + "=false to allow plain HTTP between trusted instances,"
                    + " or name this origin in " + ALLOWED_SOURCES_PROPERTY + ".");
        }

        if (!effective.allowPrivateTargets()) {
            if (isLoopback(host)) {
                throw new IllegalArgumentException("Source URL must not point to localhost/loopback: " + sourceUrl
                        + ". Set " + ALLOW_PRIVATE_PROPERTY + "=true, or name this origin in "
                        + ALLOWED_SOURCES_PROPERTY + ".");
            }
            if (isPrivateIp(host)) {
                throw new IllegalArgumentException("Source URL must not point to a private IP address: " + sourceUrl
                        + ". Set " + ALLOW_PRIVATE_PROPERTY + "=true to sync between instances on an internal"
                        + " network, or name this origin in " + ALLOWED_SOURCES_PROPERTY + ".");
            }
        } else {
            // Still has to resolve. A host that does not is a mistake either way, and
            // failing here beats failing later with a bare connect error.
            requireResolvable(host);
        }

        LOGGER.debugf("Source URL validated: %s", sanitize(sourceUrl));
    }

    /**
     * The strict policy, with {@code http://} optionally permitted — the shape the
     * launch-mode decision has, and the one most tests want.
     *
     * @param allowHttp
     *            whether a plain {@code http://} source is accepted. Private and
     *            loopback targets stay refused either way; that decision needs
     *            {@link #validate(String, SyncSourcePolicy)}.
     */
    public static void validate(String sourceUrl, boolean allowHttp) {
        validate(sourceUrl, new SyncSourcePolicy(!allowHttp, false, Set.of()));
    }

    /**
     * {@code scheme://host[:port]}, lower-cased, with nothing else — the form
     * {@link SyncSourcePolicy#allowedSources} is compared in.
     */
    static String originOf(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "";
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        // A trailing dot names the same host to DNS and a different string to an
        // exact comparison, which would refuse an origin the operator did name.
        if (host.length() > 1 && host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        String origin = scheme + "://" + host;
        // https://x and https://x:443 are the same origin. Comparing them as
        // different strings fails closed, but an operator who writes one and is
        // refused for the other has no way to tell why.
        int port = uri.getPort();
        if (port == -1 || port == defaultPortOf(scheme)) {
            return origin;
        }
        return origin + ":" + port;
    }

    private static int defaultPortOf(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    /**
     * Parses the configured allow-list.
     * <p>
     * Each entry is reduced to its origin, so an operator who pastes a full URL
     * with a path still gets the origin they meant rather than an entry that can
     * never match. An unparseable entry is dropped with a warning rather than
     * failing startup: one typo must not take the whole deployment down, and a
     * dropped entry fails closed (the URL is simply refused).
     */
    public static Set<String> parseAllowedSources(String configured) {
        if (configured == null || configured.isBlank()) {
            return Set.of();
        }
        Set<String> origins = new LinkedHashSet<>();
        for (String entry : configured.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                String origin = originOf(URI.create(trimmed));
                if (origin.isEmpty()) {
                    LOGGER.warnf("Ignoring %s entry '%s': it names no scheme and host", ALLOWED_SOURCES_PROPERTY,
                            sanitize(trimmed));
                    continue;
                }
                origins.add(origin);
            } catch (IllegalArgumentException e) {
                LOGGER.warnf("Ignoring unparseable %s entry '%s'", ALLOWED_SOURCES_PROPERTY, sanitize(trimmed));
            }
        }
        return Set.copyOf(origins);
    }

    /**
     * Reduces every configured entry with {@link #originOf}, the same function the
     * request URL goes through.
     * <p>
     * Both sides have to be normalised by the same rules or the comparison decides
     * on spelling: an operator who writes {@code http://host:80} and a caller who
     * writes {@code http://host} name one origin, and lower-casing alone made them
     * two. It also means a policy constructed directly — by a test, or by future
     * code that does not go through {@link #parseAllowedSources} — behaves
     * identically to one read from configuration.
     */
    private static Set<String> normalizeOrigins(Set<String> origins) {
        if (origins == null || origins.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            if (origin == null || origin.isBlank()) {
                continue;
            }
            try {
                String reduced = originOf(URI.create(origin.trim()));
                if (!reduced.isEmpty()) {
                    normalized.add(reduced);
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warnf("Ignoring unparseable allowed sync source '%s'", sanitize(origin));
            }
        }
        return Set.copyOf(normalized);
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host))
            return true;
        if (host.startsWith("127."))
            return true;
        return "::1".equals(host) || "[::1]".equals(host);
    }

    /**
     * Delegates the address decision to
     * {@link UrlValidationUtils#isPrivateAddress}, the codebase's single definition
     * of an unsafe outbound address.
     * <p>
     * This used to be a second, local copy built from the four JDK predicates
     * ({@code isSiteLocalAddress}, {@code isLoopbackAddress},
     * {@code isLinkLocalAddress}, {@code isAnyLocalAddress}). Those do not cover
     * RFC 4193 IPv6 ULA ({@code fc00::/7} - {@code isSiteLocalAddress} only matches
     * the deprecated {@code fec0::/10}) or RFC 6598 CGNAT ({@code 100.64.0.0/10},
     * used by Tailscale and some k8s pod CIDRs). The sync endpoints are open to
     * {@code eddi-editor}, so that gap let a lower-privileged role reach internal
     * hosts the rest of the codebase already refused.
     * <p>
     * The messages here are deliberately kept rather than delegating to
     * {@code validateUrl} wholesale: this validator's HTTPS rule has no equivalent
     * there, and its wording is what the sync UI shows the operator.
     */
    private static boolean isPrivateIp(String host) {
        for (InetAddress addr : resolve(host)) {
            if (UrlValidationUtils.isPrivateAddress(addr)) {
                return true;
            }
        }
        return false;
    }

    private static void requireResolvable(String host) {
        resolve(host);
    }

    private static InetAddress[] resolve(String host) {
        try {
            return InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            // DNS resolution failed — fail closed to prevent DNS rebinding attacks
            LOGGER.debugf("Could not resolve host %s — rejecting source URL", sanitize(host));
            throw new IllegalArgumentException("Source URL host could not be resolved: " + host, e);
        }
    }
}
