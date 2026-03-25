package ai.labs.eddi.modules.llm.tools;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/**
 * URL validation utilities to prevent SSRF (Server-Side Request Forgery)
 * attacks.
 * Validates that URLs use allowed schemes and do not target private/internal
 * networks.
 */
public final class UrlValidationUtils {

    private UrlValidationUtils() {
        // Utility class
    }

    /**
     * Validates that the given URL is safe for server-side fetching.
     * Checks:
     * 1. URL is syntactically valid
     * 2. Scheme is http or https only
     * 3. Hostname does not resolve to a private/loopback/link-local address
     *
     * @param url the URL to validate
     * @throws IllegalArgumentException if the URL is invalid or targets a
     *                                  production address
     */
    public static void validateUrl(String url) {
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
            throw new IllegalArgumentException(
                    "Only http and https URLs are allowed. Got: " + (scheme != null ? scheme : "<no scheme>"));
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
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (isPrivateAddress(addr)) {
                    throw new IllegalArgumentException(
                            "URL resolves to a private/internal address which is not allowed: " + host);
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve hostname: " + host);
        }
    }

    /**
     * Checks whether the hostname is a known internal/local hostname that should be
     * blocked.
     */
    static boolean isBlockedHostname(String host) {
        return host.equals("localhost") ||
                host.equals("127.0.0.1") ||
                host.equals("[::1]") ||
                host.equals("::1") ||
                host.endsWith(".local") ||
                host.endsWith(".internal") ||
                host.equals("metadata.google.internal") ||
                host.equals("169.254.169.254"); // Cloud metadata endpoint
    }

    /**
     * Checks whether an InetAddress is a private, loopback, or link-local address.
     */
    static boolean isPrivateAddress(InetAddress address) {
        return address.isLoopbackAddress() ||
                address.isSiteLocalAddress() ||
                address.isLinkLocalAddress() ||
                address.isAnyLocalAddress() ||
                isCloudMetadataAddress(address);
    }

    /**
     * Checks for cloud metadata service addresses (169.254.169.254).
     */
    private static boolean isCloudMetadataAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // 169.254.169.254 - AWS/GCP/Azure metadata endpoint
            return (bytes[0] & 0xFF) == 169 && (bytes[1] & 0xFF) == 254 &&
                    (bytes[2] & 0xFF) == 169 && (bytes[3] & 0xFF) == 254;
        }
        return false;
    }

    /**
     * Checks if the URL is a valid http(s) URL without performing DNS resolution.
     * Use this for quick validation when DNS resolution is not desired.
     *
     * @param url the URL to check
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
            return scheme != null &&
                    (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) &&
                    host != null && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
