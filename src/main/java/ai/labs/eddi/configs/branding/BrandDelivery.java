/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.ui.JsGlobalScript;

/**
 * Renders the two runtime files every shell loads: {@code __brand__.js} and
 * {@code brand.css}.
 * <p>
 * The script goes through the shared JavaScript string escaper. The stylesheet
 * has no escaper on purpose: it only ever emits values that passed
 * {@link BrandValidator#isSafeColor}, and re-checks them here, so nothing
 * free-form reaches CSS.
 */
public final class BrandDelivery {

    /**
     * The Manager's {@code brand-50…950} scale, derived in the browser from
     * {@code --color-primary}: 500 is the primary itself, lighter steps mix it with
     * white and darker ones with black, in OKLCH so the hue holds. The percentages
     * put each step at about the lightness of the Tailwind amber step it replaces
     * (amber 500 sits at L≈77%). The server only ever writes this fixed text; it
     * never computes a shade. {@code --color-warn-*} (warnings, fixed amber) and
     * {@code --color-ochre-*} (a fixed categorical amber hue) are deliberately
     * never touched.
     */
    static final String BRAND_SCALE = "--color-brand-50:color-mix(in oklch,var(--color-primary) 6%,white);"
            + "--color-brand-100:color-mix(in oklch,var(--color-primary) 16%,white);"
            + "--color-brand-200:color-mix(in oklch,var(--color-primary) 33%,white);"
            + "--color-brand-300:color-mix(in oklch,var(--color-primary) 52%,white);"
            + "--color-brand-400:color-mix(in oklch,var(--color-primary) 75%,white);"
            + "--color-brand-500:var(--color-primary);"
            + "--color-brand-600:color-mix(in oklch,var(--color-primary) 85%,black);"
            + "--color-brand-700:color-mix(in oklch,var(--color-primary) 70%,black);"
            + "--color-brand-800:color-mix(in oklch,var(--color-primary) 60%,black);"
            + "--color-brand-900:color-mix(in oklch,var(--color-primary) 54%,black);"
            + "--color-brand-950:color-mix(in oklch,var(--color-primary) 36%,black);";

    private BrandDelivery() {
    }

    /**
     * {@code window.__EDDI_BRAND__}: what the UIs need before they render. Read by
     * each UI's {@code brand.ts}, which holds the same EDDI defaults for the dev
     * server and mock mode.
     */
    public static String script(ResolvedBrand brand) {
        return JsGlobalScript.assign("__EDDI_BRAND__")
                .put("branded", brand.branded())
                .put("revision", brand.revision())
                .put("productName", brand.productName())
                .put("shortName", brand.shortName())
                .put("tagline", brand.tagline())
                .put("poweredBy", brand.poweredBy())
                .put("showUpdateCheck", brand.showUpdateCheck())
                .putObject("chat", chat -> chat.put("accent", safe(brand.colors().chatAccent())))
                .toJs();
    }

    /**
     * The colour tokens of both UIs, or an empty stylesheet when no colour is set.
     * <p>
     * The Manager reads {@code --color-primary},
     * {@code --color-primary-foreground}, {@code --color-ring} and
     * {@code --color-sidebar-accent} (with its foreground, so the pair keeps the
     * brand's contrast) in light ({@code :root}) and dark ({@code .dark}); the Chat
     * reads {@code --chat-accent}. The block for {@code .dark} is always written
     * when a colour is set, because it comes after {@code :root} with the same
     * specificity as the bundle's own {@code .dark} block and would otherwise lose
     * to it.
     * <p>
     * With a primary colour, each block also carries {@link #BRAND_SCALE}, so the
     * scale is recomputed against that mode's primary wherever the {@code dark}
     * class sits, and {@code :root} sets {@code --brand-loader-accent} for the HTML
     * shells' loading spinner. The spinner always sits on a dark background, so it
     * takes the dark primary.
     */
    public static String stylesheet(ResolvedBrand brand) {
        BrandColors colors = brand.colors();
        String primary = safe(colors.primary());
        String foreground = safe(colors.primaryForeground());
        String darkPrimary = safe(colors.darkPrimary());
        String darkForeground = safe(colors.darkPrimaryForeground());
        String accent = safe(colors.chatAccent());
        if (primary == null && foreground == null && darkPrimary == null && darkForeground == null && accent == null) {
            return "";
        }
        StringBuilder css = new StringBuilder("/* brand revision ").append(brand.revision()).append(" */\n");
        css.append(":root{");
        managerTokens(css, primary, foreground);
        if (primary != null || darkPrimary != null) {
            css.append("--brand-loader-accent:").append(darkPrimary != null ? darkPrimary : primary).append(';');
        }
        if (accent != null) {
            css.append("--chat-accent:").append(accent).append(';');
        }
        css.append("}\n");
        if (darkPrimary != null || darkForeground != null) {
            css.append(".dark{");
            managerTokens(css, darkPrimary, darkForeground);
            css.append("}\n");
        }
        return css.toString();
    }

    private static void managerTokens(StringBuilder css, String primary, String foreground) {
        if (primary != null) {
            css.append("--color-primary:").append(primary).append(';');
            css.append("--color-ring:").append(primary).append(';');
            css.append("--color-sidebar-accent:").append(primary).append(';');
        }
        if (foreground != null) {
            css.append("--color-primary-foreground:").append(foreground).append(';');
            css.append("--color-sidebar-accent-foreground:").append(foreground).append(';');
        }
        if (primary != null) {
            css.append(BRAND_SCALE);
        }
    }

    /** Defence in depth: a value that is not a safe colour is never emitted. */
    private static String safe(String color) {
        return color != null && BrandValidator.isSafeColor(color) ? color : null;
    }
}
