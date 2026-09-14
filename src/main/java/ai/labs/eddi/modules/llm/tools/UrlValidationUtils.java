/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URL validation utilities to prevent SSRF (Server-Side Request Forgery)
 * attacks. Validates that URLs use allowed schemes and do not target
 * private/internal networks.
 * <p>
 * Covers: RFC 1918 (IPv4 private), RFC 4193 (IPv6 ULA), RFC 6598 (CGNAT),
 * IPv4-mapped IPv6, link-local, loopback, multicast, unspecified, and cloud
 * metadata endpoints.
 */
public final class UrlValidationUtils {

    private UrlValidationUtils() {
        // Utility class
    }

    /**
     * Functional interface for hostname resolution. Allows mocking in tests to
     * simulate DNS rebinding scenarios.
     */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }

    /** Default resolver delegating to the JDK. */
    private static final HostResolver DEFAULT_RESOLVER = InetAddress::getAllByName;

    /**
     * Validates that the given URL is safe for server-side fetching. Checks: 1. URL
     * is syntactically valid 2. Scheme is http or https only 3. Hostname does not
     * resolve to a private/loopback/link-local address
     *
     * @param url
     *            the URL to validate
     * @throws IllegalArgumentException
     *             if the URL is invalid or targets a private address
     */
    public static void validateUrl(String url) {
        validateUrl(url, DEFAULT_RESOLVER);
    }

    /**
     * Validates URL safety using a custom host resolver. Returns the resolved
     * addresses for callers that wish to implement socket-level IP pinning.
     * <p>
     * <b>Note:</b> The default {@link SafeHttpClient} code path does NOT pin the
     * resolved addresses — the JDK {@code HttpClient} re-resolves DNS
     * independently. This means a short-TTL DNS rebinding attack is theoretically
     * possible between validation and connect. The risk is accepted because
     * exploitation requires a cooperating DNS server, a sub-second race window, AND
     * bypassing per-hop redirect validation. See {@code docs/architecture.md} "DNS
     * Rebinding" for details.
     *
     * @param url
     *            the URL to validate
     * @param resolver
     *            the host resolver to use (injectable for testing)
     * @return the resolved InetAddresses (for optional pinning by callers)
     * @throws IllegalArgumentException
     *             if the URL is invalid or targets a private address
     */
    public static InetAddress[] validateUrl(String url, HostResolver resolver) {
        URI uri = validateUrlSyntax(url);
        String host = uri.getHost();

        // 3. Block known internal hostnames
        String lowerHost = host.toLowerCase();
        if (isBlockedHostname(lowerHost)) {
            throw new IllegalArgumentException("Access to internal/local addresses is not allowed: " + host);
        }

        // 4. Resolve hostname and check if it's a private IP
        try {
            InetAddress[] addresses = resolver.resolveAll(host);
            for (InetAddress addr : addresses) {
                if (isPrivateAddress(addr)) {
                    throw new IllegalArgumentException("URL resolves to a private/internal address which is not allowed: " + host);
                }
            }
            return addresses;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve hostname: " + host);
        }
    }

    /**
     * The syntactic half of {@link #validateUrl(String)} on its own: non-blank,
     * parseable, an {@code http} or {@code https} scheme, and a host — and no
     * address check whatsoever.
     * <p>
     * For a target that a rule <em>stricter</em> than the SSRF check has already
     * approved: an operator-maintained allowlist of exact origins may legitimately
     * name a host on a private network (an on-premises identity provider), which
     * the address check would refuse. Nothing user- or config-controlled may use
     * this without such a rule in front of it.
     *
     * @return the parsed URI
     * @throws IllegalArgumentException
     *             if the URL is blank, malformed, not http(s), or has no host
     */
    public static URI validateUrlSyntax(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL must not be null or empty");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL syntax: " + e.getMessage());
        }

        // 1. Scheme check
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only http and https URLs are allowed. Got: " + (scheme != null ? scheme : "<no scheme>"));
        }

        // 2. Host check
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must have a valid hostname");
        }
        return uri;
    }

    /**
     * Checks whether the hostname is a known internal/local hostname that should be
     * blocked.
     */
    static boolean isBlockedHostname(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1") || host.endsWith(".local")
                || host.endsWith(".internal") || host.equals("metadata.google.internal") || host.equals("169.254.169.254"); // Cloud metadata endpoint
    }

    /**
     * Hostnames and literal addresses of cloud instance-metadata services: AWS,
     * Azure, GCP and OpenStack share 169.254.169.254; AWS also serves IPv6
     * fd00:ec2::254; Alibaba uses 100.100.100.200; GCP names its own.
     */
    private static final Set<String> METADATA_HOSTS = Set.of("169.254.169.254", "fd00:ec2::254", "100.100.100.200",
            "metadata.google.internal", "metadata.goog");

    /** fd00:ec2::254 — the AWS Nitro IPv6 metadata endpoint. */
    private static final byte[] AWS_IPV6_METADATA = {(byte) 0xfd, 0x00, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54};

    /**
     * Refuse a URL that targets a cloud instance-metadata service — always,
     * independent of {@code eddi.security.ssrf-protection.enabled}.
     * <p>
     * That setting is opt-in because configured httpcalls, MCP servers and A2A
     * peers legitimately reach private and loopback hosts. The metadata service is
     * not one of those: it hands out the instance's cloud credentials, nobody
     * configures it as an API, and one templated URL on a cloud VM is enough to
     * reach it. So it is blocked on every outbound path, protection on or off. The
     * whole link-local range (169.254.0.0/16, fe80::/10) goes with it: nothing
     * addressed there is a real API, and it is where the metadata service lives.
     * <p>
     * Deliberately lenient about everything else — an unparseable URL or an
     * unresolvable host is left for the caller's own checks and the request itself
     * to reject. This checks one URL; with protection off redirects are still
     * followed, so the httpcalls client applies it to every redirect hop as well
     * ({@code HttpClientModule.refusingMetadataHops}).
     *
     * @throws IllegalArgumentException
     *             when the host is, or resolves to, a metadata/link-local address
     */
    public static void rejectCloudMetadataTarget(String url) {
        rejectCloudMetadataTarget(url, DEFAULT_RESOLVER);
    }

    /**
     * {@link #rejectCloudMetadataTarget(String)} with an injectable resolver.
     */
    public static void rejectCloudMetadataTarget(String url, HostResolver resolver) {
        if (url == null || url.isBlank()) {
            return;
        }
        String host;
        try {
            host = new URI(url.trim()).getHost();
        } catch (URISyntaxException e) {
            return;
        }
        if (host == null || host.isBlank()) {
            return;
        }
        String bareHost = host.toLowerCase(Locale.ROOT);
        if (bareHost.startsWith("[") && bareHost.endsWith("]")) {
            bareHost = bareHost.substring(1, bareHost.length() - 1);
        }
        if (METADATA_HOSTS.contains(bareHost)) {
            throw metadataRefusal(host);
        }
        try {
            // An IP literal is checked as written, without the resolver: parsing a
            // literal needs no DNS, and a resolver that cannot answer must not let
            // 169.254.170.2 (the ECS credentials endpoint) through.
            InetAddress[] addresses = isIpLiteral(bareHost) ? new InetAddress[]{InetAddress.getByName(bareHost)} : resolver.resolveAll(bareHost);
            for (InetAddress address : addresses) {
                if (isMetadataAddress(address)) {
                    throw metadataRefusal(host);
                }
            }
        } catch (UnknownHostException e) {
            // Unresolvable: the request itself fails, nothing to protect against here.
        }
    }

    /**
     * Link-local (which contains 169.254.169.254), AWS's IPv6 metadata address, or
     * Alibaba's 100.100.100.200 — including their IPv4-mapped IPv6 forms.
     */
    static boolean isMetadataAddress(InetAddress address) {
        if (address.isLinkLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isIPv4Mapped(bytes)) {
            bytes = Arrays.copyOfRange(bytes, 12, 16);
        }
        if (bytes.length == 4) {
            int b0 = bytes[0] & 0xFF;
            int b1 = bytes[1] & 0xFF;
            boolean linkLocal = b0 == 169 && b1 == 254;
            boolean alibaba = b0 == 100 && b1 == 100 && (bytes[2] & 0xFF) == 100 && (bytes[3] & 0xFF) == 200;
            return linkLocal || alibaba;
        }
        return Arrays.equals(bytes, AWS_IPV6_METADATA);
    }

    /** A dotted-quad IPv4 or (colon-bearing) IPv6 literal — never a hostname. */
    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || IPV4_LITERAL.matcher(host).matches();
    }

    private static final Pattern IPV4_LITERAL = Pattern.compile("\\d{1,3}(?:\\.\\d{1,3}){3}");

    private static IllegalArgumentException metadataRefusal(String host) {
        return new IllegalArgumentException("Access to the cloud instance-metadata service is never allowed, whatever "
                + "eddi.security.ssrf-protection.enabled says: " + host);
    }

    /**
     * Checks whether an InetAddress is a private, loopback, link-local, multicast,
     * unspecified, or otherwise unsafe address for outbound requests.
     * <p>
     * Covers:
     * <ul>
     * <li>Loopback (127.0.0.0/8, ::1)</li>
     * <li>RFC 1918 private (10/8, 172.16/12, 192.168/16)</li>
     * <li>RFC 4193 IPv6 ULA (fc00::/7)</li>
     * <li>RFC 6598 CGNAT (100.64.0.0/10)</li>
     * <li>Link-local (169.254/16, fe80::/10)</li>
     * <li>IPv4-mapped IPv6 (::ffff:x.x.x.x) — extracts and re-checks IPv4</li>
     * <li>IPv4 multicast (224.0.0.0/4)</li>
     * <li>Unspecified (0.0.0.0/8)</li>
     * <li>Cloud metadata (169.254.169.254)</li>
     * </ul>
     * <p>
     * Public because it is the single definition of "unsafe outbound address" for
     * the whole codebase: {@code SourceUrlValidator}, which guards the remote agent
     * sync endpoints, delegates here rather than keeping the second, weaker copy it
     * used to have (that one missed RFC 4193 ULA and RFC 6598 CGNAT, because the
     * JDK predicates alone do not cover them).
     */
    public static boolean isPrivateAddress(InetAddress address) {
        // JDK covers: loopback, site-local (RFC 1918 for IPv4, fec0::/10 for IPv6),
        // link-local, any-local (0.0.0.0, ::)
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {
            return isPrivateIPv4(bytes);
        }

        if (bytes.length == 16) {
            return isPrivateIPv6(bytes);
        }

        // Unknown address length — block by default for safety
        return true;
    }

    /**
     * Additional IPv4 checks beyond what JDK covers.
     */
    private static boolean isPrivateIPv4(byte[] bytes) {
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;

        // CGNAT (100.64.0.0/10) — RFC 6598
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return true;
        }

        // Multicast (224.0.0.0/4)
        if ((b0 & 0xF0) == 224) {
            return true;
        }

        // Unspecified / "this network" (0.0.0.0/8)
        if (b0 == 0) {
            return true;
        }

        // Cloud metadata (169.254.169.254)
        if (isCloudMetadataAddress(bytes)) {
            return true;
        }

        return false;
    }

    /**
     * IPv6 private address checks covering ULA and IPv4-mapped addresses.
     * <p>
     * Teredo (2001::/32) and 6to4 (2002::/16) tunneling prefixes are NOT blocked —
     * these are largely deprecated and the embedded IPv4 addresses would be caught
     * by {@link #isPrivateIPv4(byte[])} if they were private.
     */
    private static boolean isPrivateIPv6(byte[] bytes) {
        // IPv6 ULA (fc00::/7) — RFC 4193
        if ((bytes[0] & 0xFE) == 0xFC) {
            return true;
        }

        // IPv4-mapped IPv6 (::ffff:x.x.x.x)
        // Bytes 0-9 are zero, bytes 10-11 are 0xFF
        if (isIPv4Mapped(bytes)) {
            byte[] ipv4 = new byte[4];
            System.arraycopy(bytes, 12, ipv4, 0, 4);

            // Re-check the embedded IPv4 address against all IPv4 rules
            try {
                InetAddress embedded = InetAddress.getByAddress(ipv4);
                if (embedded.isLoopbackAddress() || embedded.isSiteLocalAddress() || embedded.isLinkLocalAddress() || embedded.isAnyLocalAddress()
                        || embedded.isMulticastAddress() || isPrivateIPv4(ipv4)) {
                    return true;
                }
            } catch (Exception e) {
                // Should never happen with 4-byte array, but block if it does
                return true;
            }
        }

        return false;
    }

    /**
     * Detects IPv4-mapped IPv6 addresses (::ffff:x.x.x.x). Format: 80 bits of zero,
     * 16 bits of 0xFFFF, 32 bits of IPv4.
     */
    private static boolean isIPv4Mapped(byte[] bytes) {
        if (bytes.length != 16)
            return false;
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0)
                return false;
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    /**
     * Checks for cloud metadata service addresses (169.254.169.254).
     */
    private static boolean isCloudMetadataAddress(byte[] bytes) {
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254 && (bytes[2] & 0xFF) == 169 && (bytes[3] & 0xFF) == 254;
        }
        return false;
    }

    /**
     * Checks if the URL is a valid http(s) URL without performing DNS resolution.
     * Use this for quick validation when DNS resolution is not desired.
     *
     * @param url
     *            the URL to check
     * @return true if the URL has a valid http/https scheme and hostname
     */
    public static boolean isValidHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) && host != null && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
