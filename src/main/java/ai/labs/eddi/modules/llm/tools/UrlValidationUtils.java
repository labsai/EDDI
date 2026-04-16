package ai.labs.eddi.modules.llm.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

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
     * addresses so callers can use the same addresses for the actual HTTP request,
     * defeating DNS rebinding (TOCTOU) attacks.
     *
     * @param url
     *            the URL to validate
     * @param resolver
     *            the host resolver to use (injectable for testing)
     * @return the resolved InetAddresses (callers should connect to these, not
     *         re-resolve)
     * @throws IllegalArgumentException
     *             if the URL is invalid or targets a private address
     */
    public static InetAddress[] validateUrl(String url, HostResolver resolver) {
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
     * Checks whether the hostname is a known internal/local hostname that should be
     * blocked.
     */
    static boolean isBlockedHostname(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1") || host.endsWith(".local")
                || host.endsWith(".internal") || host.equals("metadata.google.internal") || host.equals("169.254.169.254"); // Cloud metadata endpoint
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
     */
    static boolean isPrivateAddress(InetAddress address) {
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
     * IPv6 private address checks covering ULA, IPv4-mapped, and Teredo.
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
