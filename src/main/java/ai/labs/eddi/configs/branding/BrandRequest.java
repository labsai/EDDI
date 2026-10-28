/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

/**
 * Which request a brand is resolved for.
 * <p>
 * There is one brand per deployment today and {@link BrandingService} ignores
 * this. It is the seam for per-tenant brands (plan §4.6): every caller already
 * passes the request it serves, so a host-based resolver can be added inside
 * the service without touching them.
 *
 * @param host
 *            the request's {@code Host}, or {@code null} outside a request
 */
public record BrandRequest(String host) {

    /** Outside any HTTP request. */
    public static final BrandRequest NONE = new BrandRequest(null);

    public static BrandRequest ofHost(String host) {
        return host == null ? NONE : new BrandRequest(host);
    }
}
