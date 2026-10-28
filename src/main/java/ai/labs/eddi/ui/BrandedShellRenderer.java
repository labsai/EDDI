/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * Serves the HTML shells with the brand substituted into the few values a
 * browser or a link-preview crawler reads before any script runs.
 * <p>
 * The shells carry {@code %BRAND_PRODUCT_NAME%}, {@code %BRAND_TAGLINE%} and
 * {@code %BRAND_THEME_COLOR%}. Vite only rewrites {@code %VITE_*%}-style names,
 * so these pass through the UI build untouched (a test in each UI pins that)
 * and are replaced here, HTML-escaped, for text and attribute positions alike.
 * The result is cached per (shell, brand revision), so a rebrand is served on
 * the next request after this node's brand cache turns over.
 */
@ApplicationScoped
public class BrandedShellRenderer {

    private static final Logger LOGGER = Logger.getLogger(BrandedShellRenderer.class);

    static final String PRODUCT_NAME = "%BRAND_PRODUCT_NAME%";
    static final String TAGLINE = "%BRAND_TAGLINE%";
    static final String THEME_COLOR = "%BRAND_THEME_COLOR%";

    record Key(BrandedShell shell, long revision) {
    }

    private final BrandingService branding;
    private final Cache<Key, String> rendered = Caffeine.newBuilder().maximumSize(32).build();

    @Inject
    public BrandedShellRenderer(BrandingService branding) {
        this.branding = branding;
    }

    /**
     * The branded page, or empty when the shell is not on the classpath (a build
     * without the UIs).
     */
    public Optional<String> render(BrandedShell shell, BrandRequest request) {
        ResolvedBrand brand = branding.current(request);
        Key key = new Key(shell, brand.revision());
        String cached = rendered.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<String> template = readTemplate(shell);
        if (template.isEmpty()) {
            LOGGER.errorf("%s not found on the classpath", shell.resource());
            return Optional.empty();
        }
        String page = substitute(template.get(), shell, brand);
        rendered.put(key, page);
        return Optional.of(page);
    }

    private static Optional<String> readTemplate(BrandedShell shell) {
        try (InputStream in = getResourceAsStream(shell.resource())) {
            return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + shell.resource(), e);
        }
    }

    static String substitute(String template, BrandedShell shell, ResolvedBrand brand) {
        String tagline = brand.tagline() != null ? brand.tagline() : shell.defaultTagline();
        String themeColor = brand.colors().primary() != null ? brand.colors().primary() : shell.defaultThemeColor();
        return template
                .replace(PRODUCT_NAME, escapeHtml(brand.productName()))
                .replace(TAGLINE, escapeHtml(tagline))
                .replace(THEME_COLOR, escapeHtml(themeColor));
    }

    /**
     * Escapes for HTML text and for quoted attribute values (either quote).
     * Validation already refuses {@code <} and {@code >} in names; this is the
     * output-side half, so the shell stays safe whatever reaches it.
     */
    public static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
