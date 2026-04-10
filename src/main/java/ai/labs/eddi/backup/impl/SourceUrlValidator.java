package ai.labs.eddi.backup.impl;

import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates remote sync source URLs to prevent Server-Side Request Forgery
 * (SSRF) attacks. Rejects private IP ranges, loopback addresses, and non-HTTPS
 * URLs in production.
 *
 * @since 6.0.0
 */
public final class SourceUrlValidator {

    private static final Logger log = Logger.getLogger(SourceUrlValidator.class);

    private SourceUrlValidator() {
    }

    /**
     * Validates a source URL for remote sync operations.
     *
     * @param sourceUrl
     *            the URL to validate
     * @param allowHttp
     *            if true, allows HTTP (for dev/test). In production, set to false
     *            to require HTTPS.
     * @throws IllegalArgumentException
     *             if the URL is invalid or points to a private/internal address
     */
    public static void validate(String sourceUrl, boolean allowHttp) {
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
        if (!allowHttp && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Source URL must use HTTPS in production: " + sourceUrl);
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Source URL must use HTTP or HTTPS: " + sourceUrl);
        }

        // Validate host
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Source URL must have a valid host: " + sourceUrl);
        }

        // Block loopback
        if (isLoopback(host)) {
            throw new IllegalArgumentException("Source URL must not point to localhost/loopback: " + sourceUrl);
        }

        // Block private IP ranges
        if (isPrivateIp(host)) {
            throw new IllegalArgumentException("Source URL must not point to a private IP address: " + sourceUrl);
        }

        log.debugf("Source URL validated: %s", sourceUrl);
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host))
            return true;
        if (host.startsWith("127."))
            return true;
        if ("::1".equals(host) || "[::1]".equals(host))
            return true;
        return false;
    }

    private static boolean isPrivateIp(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            return addr.isSiteLocalAddress() || addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            // DNS resolution failed — could be a non-existent host.
            // We let the actual HTTP request fail with a clear connection error.
            log.debugf("Could not resolve host %s — skipping IP validation", host);
            return false;
        }
    }
}
