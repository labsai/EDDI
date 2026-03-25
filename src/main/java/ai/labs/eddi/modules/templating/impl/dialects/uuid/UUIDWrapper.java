package ai.labs.eddi.modules.templating.impl.dialects.uuid;

public class UUIDWrapper {
    public String generateUUID() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Extracts the resource ID from an EDDI location URI. Works with both MongoDB
     * ObjectIds (24 hex chars) and PostgreSQL UUIDs (36 chars with dashes).
     *
     * @param locationUri
     *            e.g.
     *            "http://localhost:7070/behaviorstore/behaviorsets/6740832a2b0f614abcaee7ab?version=1"
     *            or
     *            "http://localhost:7070/behaviorstore/behaviorsets/f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81?version=1"
     * @return the resource ID, e.g. "6740832a2b0f614abcaee7ab" or
     *         "f3be2bcd-aff3-41f0-9a1a-cf4eb513dd81"
     */
    public String extractId(String locationUri) {
        if (locationUri == null || locationUri.isEmpty()) {
            return "";
        }

        // Remove query string (everything from '?' onwards)
        String path = locationUri.contains("?") ? locationUri.substring(0, locationUri.indexOf('?')) : locationUri;

        // Return the last path segment
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 && lastSlash < path.length() - 1 ? path.substring(lastSlash + 1) : "";
    }

    /**
     * Extracts the version number from an EDDI location URI.
     *
     * @param locationUri
     *            e.g.
     *            "http://localhost:7070/behaviorstore/behaviorsets/abc123?version=1"
     * @return the version string, e.g. "1"
     */
    public String extractVersion(String locationUri) {
        if (locationUri == null || locationUri.isEmpty()) {
            return "";
        }

        String versionParam = "version=";
        int versionIdx = locationUri.indexOf(versionParam);
        if (versionIdx < 0) {
            return "";
        }

        String afterVersion = locationUri.substring(versionIdx + versionParam.length());
        // Stop at '&' if there are more query params
        int ampIdx = afterVersion.indexOf('&');
        return ampIdx >= 0 ? afterVersion.substring(0, ampIdx) : afterVersion;
    }
}
