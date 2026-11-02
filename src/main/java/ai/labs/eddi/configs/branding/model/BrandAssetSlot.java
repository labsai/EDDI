/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding.model;

import java.util.Optional;

/**
 * The five image slots a brand can fill. A closed set: the slot is the only
 * part of an asset request that reaches storage, so no path from a URL ever
 * reaches the database or the filesystem.
 * <p>
 * Each slot has three names: {@link #id()} is the JSON field and the admin REST
 * path segment, {@link #publicPath()} the file name under {@code /branding/},
 * and {@link #seedFileBase()} the file name (without extension) read from
 * {@code eddi.branding.assets-dir}.
 */
public enum BrandAssetSlot {
    // @formatter:off
    LOGO("logo", "logo", "logo", "META-INF/resources/img/logo_eddi.png"),
    LOGO_DARK("logoDark", "logo-dark", "logo-dark", null),
    ICON("icon", "icon", "icon", "META-INF/resources/eddi-icon.svg"),
    FAVICON("favicon", "favicon.ico", "favicon", "META-INF/resources/img/favicon.ico"),
    OG_IMAGE("ogImage", "og-image", "og-image", null);
    // @formatter:on

    private final String id;
    private final String publicPath;
    private final String seedFileBase;
    private final String bundledResource;

    BrandAssetSlot(String id, String publicPath, String seedFileBase, String bundledResource) {
        this.id = id;
        this.publicPath = publicPath;
        this.seedFileBase = seedFileBase;
        this.bundledResource = bundledResource;
    }

    public String id() {
        return id;
    }

    public String publicPath() {
        return publicPath;
    }

    public String seedFileBase() {
        return seedFileBase;
    }

    /**
     * The classpath resource of the EDDI image this slot shows when nothing is
     * uploaded, or {@code null} when the slot borrows another slot's image
     * ({@link #fallback()}). The files are the ones the UI builds copy into
     * {@code META-INF/resources} (ui/manager/public, ui/chat/public/img).
     */
    public String bundledResource() {
        return bundledResource;
    }

    /**
     * The slot whose image this one shows when it has none of its own: the dark
     * logo and the link-preview image use the logo, the favicon uses the icon. So a
     * brand that uploads only a logo and an icon shows them everywhere, rather than
     * leaving the EDDI favicon or preview behind.
     */
    public Optional<BrandAssetSlot> fallback() {
        return switch (this) {
            case LOGO_DARK, OG_IMAGE -> Optional.of(LOGO);
            case FAVICON -> Optional.of(ICON);
            default -> Optional.empty();
        };
    }

    /** Whether an ICO file is accepted. Only the favicon slot takes one. */
    public boolean acceptsIco() {
        return this == FAVICON;
    }

    public static Optional<BrandAssetSlot> fromId(String id) {
        for (BrandAssetSlot slot : values()) {
            if (slot.id.equals(id)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    public static Optional<BrandAssetSlot> fromPublicPath(String path) {
        for (BrandAssetSlot slot : values()) {
            if (slot.publicPath.equals(path)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }
}
