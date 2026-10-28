/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import java.util.Optional;

/**
 * The HTML pages EDDI serves with the brand substituted into their
 * {@code <head>}: the three Manager shells, the Chat shell and the root
 * redirect.
 * <p>
 * Each carries the EDDI text it showed before branding existed as its defaults,
 * so an unbranded deployment serves exactly the page it always did.
 */
public enum BrandedShell {
    // @formatter:off
    MANAGE("manage.html", null, "#111113"),
    WELCOME("welcome.html", "Open Source Conversational AI Platform", "#111113"),
    WORKFORCE("workforce.html", null, "#111113"),
    CHAT("chat.html", "Open Source Conversational AI", "#1a1a1a"),
    ROOT("index.html", null, "#111113");
    // @formatter:on

    static final String RESOURCE_BASE = "META-INF/resources/";

    private final String fileName;
    private final String defaultTagline;
    private final String defaultThemeColor;

    BrandedShell(String fileName, String defaultTagline, String defaultThemeColor) {
        this.fileName = fileName;
        this.defaultTagline = defaultTagline;
        this.defaultThemeColor = defaultThemeColor;
    }

    public String fileName() {
        return fileName;
    }

    public String resource() {
        return RESOURCE_BASE + fileName;
    }

    /** What {@code %BRAND_TAGLINE%} becomes when the brand sets no tagline. */
    public String defaultTagline() {
        return defaultTagline == null ? "" : defaultTagline;
    }

    /** What {@code %BRAND_THEME_COLOR%} becomes when the brand sets no colour. */
    public String defaultThemeColor() {
        return defaultThemeColor;
    }

    public static Optional<BrandedShell> fromFileName(String fileName) {
        for (BrandedShell shell : values()) {
            if (shell.fileName.equals(fileName)) {
                return Optional.of(shell);
            }
        }
        return Optional.empty();
    }
}
