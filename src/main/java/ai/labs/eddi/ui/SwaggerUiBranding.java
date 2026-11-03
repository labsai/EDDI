/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandValidator;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the Swagger UI page and its stylesheet for a brand. Pure functions
 * over the files Quarkus generated at build time; {@link BrandedSwaggerUiRoute}
 * serves the result.
 * <p>
 * Quarkus bakes the page title, the logo's alt text and the footer
 * ({@code quarkus.swagger-ui.title} / {@code footer}) into {@code index.html}
 * at build time, and takes the logo and stylesheet from
 * {@code META-INF/branding/}. None of that can follow a brand set at runtime,
 * so the branded page is derived from the generated one on the way out.
 */
final class SwaggerUiBranding {

    private static final Pattern TITLE = Pattern.compile("<title>[^<]*</title>");
    private static final Pattern FOOTER = Pattern.compile("<footer id=\"footer\">[^<]*</footer>");
    /** The topbar logo, written by the page's own script as a JS string. */
    private static final Pattern LOGO_IMG = Pattern.compile("<img src='logo\\.png'[^>]*>");
    private static final Pattern ASSET_HREF = Pattern.compile("href=\"(favicon\\.ico|style\\.css)\"");

    /** The amber accents in {@code META-INF/branding/style.css}. */
    private static final Pattern AMBER = Pattern
            .compile("#f59e0b|#d97706|#b45309|#fbbf24|rgba\\(\\s*(?:245,\\s*158,\\s*11|217,\\s*119,\\s*6)\\s*,\\s*([0-9.]+)\\s*\\)",
                    Pattern.CASE_INSENSITIVE);
    /** A declaration that paints an accent fill behind text. */
    private static final Pattern ACCENT_FILL = Pattern.compile("background(?:-color)?:\\s*(?:#f59e0b|#d97706|#b45309)",
            Pattern.CASE_INSENSITIVE);
    /**
     * The text colour declaration of a rule — not border-color or background-color.
     */
    private static final Pattern COMMENT = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern TEXT_COLOR = Pattern.compile("(?<![-\\w])color:\\s*#[0-9a-fA-F]{3,6}");

    private SwaggerUiBranding() {
    }

    /**
     * The page for {@code brand}: its title, the logo (or the name as text when the
     * brand has no logo of its own, never EDDI's), no LABS.AI footer, and asset
     * URLs that carry the revision so a browser does not keep the old favicon or
     * stylesheet.
     *
     * @param ownLogo
     *            whether the brand has a logo of its own
     */
    static String page(String generated, ResolvedBrand brand, boolean ownLogo) {
        String name = escape(brand.productName() + " API");
        String revision = Long.toString(brand.revision());
        String page = TITLE.matcher(generated).replaceFirst(Matcher.quoteReplacement("<title>" + name + "</title>"));
        page = FOOTER.matcher(page).replaceFirst(Matcher.quoteReplacement("<footer id=\"footer\"></footer>"));
        String logo = ownLogo
                ? "<img src='logo.png?r=" + revision + "' alt='" + name + "' height='44' align='middle'>"
                : "<span class='eddi-brand-name'>" + name + "</span>";
        page = LOGO_IMG.matcher(page).replaceFirst(Matcher.quoteReplacement(logo));
        return ASSET_HREF.matcher(page).replaceAll("href=\"$1?r=" + revision + "\"");
    }

    /**
     * The stylesheet with the amber accents replaced by the brand's primary — the
     * light one in light rules, the dark one in {@code html.dark-mode} rules — and
     * the label on an accent fill by the matching foreground. The PUT method's
     * colour stays amber: next to GET's blue and POST's green it is a category, not
     * an accent. Without a primary colour the file is returned unchanged.
     */
    static String stylesheet(String generated, BrandColors colors) {
        if (colors == null || !safe(colors.primary())) {
            return generated;
        }
        String light = colors.primary();
        String dark = safe(colors.darkPrimary()) ? colors.darkPrimary() : light;
        String lightOn = safe(colors.primaryForeground()) ? colors.primaryForeground() : null;
        String darkOn = safe(colors.darkPrimaryForeground()) ? colors.darkPrimaryForeground() : lightOn;

        StringBuilder out = new StringBuilder(generated.length() + 1024);
        int from = 0;
        while (from < generated.length()) {
            int open = generated.indexOf('{', from);
            int close = open < 0 ? -1 : generated.indexOf('}', open);
            if (open < 0 || close < 0) {
                out.append(generated, from, generated.length());
                break;
            }
            String selector = generated.substring(from, open);
            String body = generated.substring(open, close + 1);
            out.append(selector).append(rebrandRule(selector, body, light, dark, lightOn, darkOn));
            from = close + 1;
        }
        return out.toString();
    }

    private static String rebrandRule(String selectorText, String body, String light, String dark, String lightOn, String darkOn) {
        // Comments before a rule are part of its "selector" text here, and one of
        // them mentions .dark-mode above the light rules.
        String selector = COMMENT.matcher(selectorText).replaceAll("");
        if (selector.contains("opblock-put")) {
            return body;
        }
        boolean darkRule = selector.contains("dark-mode");
        String accent = darkRule ? dark : light;
        String on = darkRule ? darkOn : lightOn;
        String result = body;
        if (on != null && ACCENT_FILL.matcher(body).find()) {
            result = TEXT_COLOR.matcher(result).replaceAll(Matcher.quoteReplacement("color: " + on));
        }
        Matcher amber = AMBER.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (amber.find()) {
            amber.appendReplacement(sb, Matcher.quoteReplacement(replacement(amber, accent, darkRule)));
        }
        amber.appendTail(sb);
        return sb.toString();
    }

    private static String replacement(Matcher amber, String accent, boolean darkRule) {
        if (amber.group(1) != null) {
            double alpha = Double.parseDouble(amber.group(1));
            String percent = String.format(Locale.ROOT, "%.0f%%", Math.clamp(alpha, 0.0, 1.0) * 100);
            return "color-mix(in srgb, " + accent + " " + percent + ", transparent)";
        }
        return switch (amber.group().toLowerCase(Locale.ROOT)) {
            // The light theme's accent and its stronger, hover tone.
            case "#d97706" -> darkRule ? "color-mix(in oklch, " + accent + " 85%, black)" : accent;
            case "#b45309" -> "color-mix(in oklch, " + accent + " 80%, black)";
            // The dark theme's link tone, a step lighter than the accent.
            case "#fbbf24" -> "color-mix(in oklch, " + accent + " 75%, white)";
            default -> accent;
        };
    }

    /** Only a validated colour is ever written into the stylesheet. */
    private static boolean safe(String color) {
        return color != null && BrandValidator.isSafeColor(color);
    }

    /**
     * HTML text and attribute escaping, plus the backslash for the JS string the
     * logo sits in.
     */
    static String escape(String value) {
        return BrandedShellRenderer.escapeHtml(value).replace("\\", "&#92;");
    }
}
