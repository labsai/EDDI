/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.updatecheck;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semver-ish version comparison for the update check — the Java twin of
 * {@code compareVersions} in the Manager's {@code updates.ts}, so the banner
 * and the notification can never disagree about whether a release is newer.
 */
final class ReleaseVersions {

    private static final Pattern VERSION = Pattern.compile("^(\\d+(?:\\.\\d+)*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private ReleaseVersions() {
    }

    /** Strips whitespace and a leading {@code v}. */
    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().replaceFirst("^[vV]", "");
    }

    /** Whether {@code raw} is a version this class can compare. */
    static boolean isVersion(String raw) {
        return VERSION.matcher(normalize(raw)).matches();
    }

    /**
     * Whether {@code latest} is strictly newer than {@code installed}. False
     * whenever either side is not a version — an unparseable installed version (the
     * {@code unknown} fallback) must never raise a notice.
     */
    static boolean isNewer(String installed, String latest) {
        Matcher left = VERSION.matcher(normalize(installed));
        Matcher right = VERSION.matcher(normalize(latest));
        if (!left.matches() || !right.matches()) {
            return false;
        }
        return compare(left, right) < 0;
    }

    private static int compare(Matcher a, Matcher b) {
        String[] coreA = a.group(1).split("\\.");
        String[] coreB = b.group(1).split("\\.");
        for (int i = 0; i < Math.max(coreA.length, coreB.length); i++) {
            int diff = part(coreA, i).compareTo(part(coreB, i));
            if (diff != 0) {
                return diff;
            }
        }
        String preA = a.group(2);
        String preB = b.group(2);
        if (preA == null && preB == null) {
            return 0;
        }
        // A release outranks any prerelease of the same core version.
        if (preA == null) {
            return 1;
        }
        if (preB == null) {
            return -1;
        }
        return comparePrerelease(preA.split("\\."), preB.split("\\."));
    }

    private static BigInteger part(String[] core, int i) {
        return i >= core.length ? BigInteger.ZERO : new BigInteger(core[i]);
    }

    private static int comparePrerelease(String[] a, String[] b) {
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            // Fewer identifiers wins when everything before matched.
            if (i >= a.length) {
                return -1;
            }
            if (i >= b.length) {
                return 1;
            }
            if (a[i].equals(b[i])) {
                continue;
            }
            boolean numA = a[i].matches("\\d+");
            boolean numB = b[i].matches("\\d+");
            if (numA && numB) {
                return new BigInteger(a[i]).compareTo(new BigInteger(b[i]));
            }
            if (numA) {
                return -1;
            }
            if (numB) {
                return 1;
            }
            return a[i].compareTo(b[i]) < 0 ? -1 : 1;
        }
        return 0;
    }
}
