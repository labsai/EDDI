/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static ai.labs.eddi.utils.RuntimeUtilities.getResourceAsStream;

/**
 * The "Download login theme" ZIP (white-label plan R7): the Keycloak login
 * theme EDDI ships ({@code keycloak/themes/eddi}), renamed {@code brand} and
 * carrying the current brand.
 * <p>
 * Keycloak cannot read EDDI's database, so the brand reaches the login page as
 * a theme the operator installs. The ZIP holds:
 * <ul>
 * <li>every file of the shipped theme, unchanged, under {@code brand/};</li>
 * <li>{@code theme.properties} with {@code css/brand-overrides.css} appended to
 * {@code styles}, so it loads after {@code eddi-login.css} and wins;</li>
 * <li>{@code brand-overrides.css}, generated from the validated colours: the
 * theme's {@code --eddi-*} palette and, explicitly, both PatternFly 5 token
 * tiers the login components read (the {@code --100} tier in the light theme,
 * the {@code --300}/{@code --400} tier under {@code .pf-v5-theme-dark} —
 * {@code planning/keycloak-eddi-theme.md} F18), plus the logo;</li>
 * <li>the brand's logo, dark logo and favicon, where it has its own;</li>
 * <li>{@code README.md} with the realm {@code displayName} /
 * {@code displayNameHtml} to set and the install steps.</li>
 * </ul>
 * <b>Nothing from a request reaches the file system or the archive's paths</b>:
 * the file list is fixed here, the image slots are an enum, every colour is
 * re-checked with {@link BrandValidator#isSafeColor} before it is written, and
 * the product name appears only in the README, escaped for JSON and HTML. An
 * unbranded deployment gets the shipped theme with an overrides file that
 * declares nothing.
 */
@ApplicationScoped
public class KeycloakThemeExport {

    /** The theme's name inside the ZIP, and the realm's {@code loginTheme}. */
    public static final String THEME = "brand";

    /** Where the Maven build puts {@code keycloak/themes/eddi} on the classpath. */
    static final String SOURCE = "keycloak-theme/eddi/";

    /**
     * Every file of the shipped theme, relative to it. Fixed rather than listed
     * from the classpath (which a jar cannot do reliably); a test keeps it equal to
     * the directory.
     */
    static final List<String> THEME_FILES = List.of(
            "login/theme.properties",
            "login/resources/css/eddi-login.css",
            "login/resources/fonts/noto-sans-cyrillic-variable.woff2",
            "login/resources/fonts/noto-sans-greek-variable.woff2",
            "login/resources/fonts/noto-sans-latin-ext-variable.woff2",
            "login/resources/fonts/noto-sans-latin-variable.woff2",
            "login/resources/img/favicon.ico",
            "login/resources/img/logo_eddi.png",
            "login/resources/js/eddi-a11y.js",
            "login/resources/js/eddi-theme.js");

    static final String THEME_PROPERTIES = "login/theme.properties";
    static final String OVERRIDES = "login/resources/css/brand-overrides.css";
    static final String FAVICON = "login/resources/img/favicon.ico";
    static final String README = "README.md";

    /** A fixed timestamp, so the same brand always yields the same archive. */
    private static final long ENTRY_TIME = 315_532_800_000L; // 1980-01-01T00:00:00Z

    private static final Pattern STYLES = Pattern.compile("(?m)^styles=(.*?)\\s*$");

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final BrandingService branding;

    @Inject
    public KeycloakThemeExport(BrandingService branding) {
        this.branding = branding;
    }

    /** The ZIP for the brand in effect. */
    public byte[] export(BrandRequest request) {
        ResolvedBrand brand = branding.current(request);
        return build(brand, source -> ownAsset(request, brand, source), KeycloakThemeExport::readSource);
    }

    private Optional<BrandAsset> ownAsset(BrandRequest request, ResolvedBrand brand, BrandAssetSlot slot) {
        return brand.assets().get(slot) == null ? Optional.empty() : branding.asset(request, slot);
    }

    /**
     * Build the archive.
     *
     * @param assets
     *            the brand's OWN image for a slot, never a fallback or the bundled
     *            one
     * @param source
     *            the shipped theme's file at a path from {@link #THEME_FILES}
     */
    static byte[] build(ResolvedBrand brand, Function<BrandAssetSlot, Optional<BrandAsset>> assets,
                        Function<String, byte[]> source) {
        Optional<BrandAsset> logo = assets.apply(BrandAssetSlot.LOGO).filter(KeycloakThemeExport::isImage);
        Optional<BrandAsset> logoDark = assets.apply(BrandAssetSlot.LOGO_DARK).filter(KeycloakThemeExport::isImage);
        Optional<BrandAsset> favicon = assets.apply(BrandAssetSlot.FAVICON)
                .or(() -> assets.apply(BrandAssetSlot.ICON))
                .filter(KeycloakThemeExport::isFaviconCompatible);

        Map<String, byte[]> files = new LinkedHashMap<>();
        for (String file : THEME_FILES) {
            byte[] bytes = source.apply(file);
            if (THEME_PROPERTIES.equals(file)) {
                bytes = themeProperties(new String(bytes, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            } else if (FAVICON.equals(file) && favicon.isPresent()) {
                bytes = favicon.get().data();
            }
            files.put(file, bytes);
        }
        String logoFile = logo.map(a -> "brand-logo." + extension(a)).orElse(null);
        String logoDarkFile = logoDark.map(a -> "brand-logo-dark." + extension(a)).orElse(null);
        logo.ifPresent(a -> files.put("login/resources/img/" + logoFile, a.data()));
        logoDark.ifPresent(a -> files.put("login/resources/img/" + logoDarkFile, a.data()));
        files.put(OVERRIDES, overrides(brand, logoFile, logoDarkFile).getBytes(StandardCharsets.UTF_8));
        files.put(README, readme(brand, favicon.isEmpty() && hasOwnSvgIcon(brand, assets)).getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : files.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(THEME + "/" + entry.getKey());
                zipEntry.setTime(ENTRY_TIME);
                zip.putNextEntry(zipEntry);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The shipped {@code theme.properties}, loading the overrides last. */
    static String themeProperties(String shipped) {
        Matcher styles = STYLES.matcher(shipped);
        if (!styles.find()) {
            throw new IllegalStateException("The shipped theme.properties has no styles= line");
        }
        String header = "# Generated by the Branding page: the EDDI login theme with this deployment's brand.\n"
                + "# brand-overrides.css is listed last so it wins at equal specificity.\n";
        return header + shipped.substring(0, styles.start()) + "styles=" + styles.group(1) + " css/brand-overrides.css"
                + shipped.substring(styles.end());
    }

    /**
     * The brand's stylesheet. Dark is the theme's default state, so {@code :root}
     * carries the dark colours, and the theme's two light-mode selectors carry the
     * light ones, at the specificity the theme itself uses.
     */
    static String overrides(ResolvedBrand brand, String logoFile, String logoDarkFile) {
        StringBuilder css = new StringBuilder(
                "/* Generated by the Branding page from the brand's validated colours and images.\n"
                        + " * Empty when nothing is branded. Loaded after eddi-login.css (theme.properties). */\n");
        if (!brand.branded()) {
            return css.toString();
        }
        BrandColors colors = brand.colors() == null ? BrandColors.EMPTY : brand.colors();
        String light = safe(colors.primary());
        String lightOn = safe(colors.primaryForeground());
        String dark = Optional.ofNullable(safe(colors.darkPrimary())).orElse(light);
        String darkOn = Optional.ofNullable(safe(colors.darkPrimaryForeground())).orElse(lightOn);

        String darkLogo = logoDarkFile != null ? logoDarkFile : logoFile;
        boolean textLogo = logoFile == null;

        StringBuilder root = new StringBuilder();
        palette(root, dark, darkOn);
        if (dark != null) {
            // Both PatternFly tiers, explicitly (keycloak-eddi-theme.md F18): the
            // light theme reads --100/--200, .pf-v5-theme-dark reads --300 for the
            // button and --400 for its label.
            root.append("    --pf-v5-global--primary-color--100: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--primary-color--200: var(--eddi-primary-hover);\n")
                    .append("    --pf-v5-global--primary-color--300: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--primary-color--light-100: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--primary-color--dark-100: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--active-color--100: var(--eddi-primary);\n")
                    // keycloak.v2's 4px rule above the card, PatternFly blue by default.
                    .append("    --keycloak-card-top-color: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--link--Color: var(--eddi-link);\n")
                    .append("    --pf-v5-global--link--Color--hover: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--link--Color--light: var(--eddi-link);\n")
                    .append("    --pf-v5-global--link--Color--light--hover: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--link--Color--dark: var(--eddi-link);\n")
                    .append("    --pf-v5-global--link--Color--dark--hover: var(--eddi-primary);\n")
                    .append("    --pf-v5-global--link--Color--visited: var(--eddi-link);\n")
                    // eddi-login.css points warnings at the accent; a warning stays amber.
                    .append("    --pf-v5-global--warning-color--100: #f59e0b;\n");
        }
        if (darkOn != null) {
            root.append("    --pf-v5-global--primary-color--400: var(--eddi-on-primary);\n");
        }
        if (!textLogo) {
            root.append("    --eddi-logo-filter: none;\n");
        }
        block(css, ":root", root);
        if (!textLogo) {
            css.append("#kc-header-wrapper {\n    background-image: url(../img/").append(darkLogo).append(");\n}\n");
        } else {
            // No logo of the brand's own: show the realm's display name as text
            // rather than EDDI's wordmark.
            css.append("#kc-header-wrapper {\n    background-image: none;\n    text-indent: 0;\n    height: auto;\n"
                    + "    overflow: visible;\n    color: var(--eddi-fg) !important;\n}\n");
        }

        StringBuilder lightVars = new StringBuilder();
        palette(lightVars, light, lightOn);
        if (!textLogo) {
            lightVars.append("    --eddi-logo-filter: none;\n");
        }
        String lightLogo = textLogo ? "" : "    background-image: url(../img/" + logoFile + ");\n";
        if (!lightVars.isEmpty() || !lightLogo.isEmpty()) {
            css.append("@media (prefers-color-scheme: light) {\n");
            block(css, ":root:not([data-eddi-theme])", lightVars);
            if (!lightLogo.isEmpty()) {
                block(css, ":root:not([data-eddi-theme]) #kc-header-wrapper", new StringBuilder(lightLogo));
            }
            css.append("}\n");
            block(css, ":root[data-eddi-theme=\"light\"]", lightVars);
            if (!lightLogo.isEmpty()) {
                block(css, ":root[data-eddi-theme=\"light\"] #kc-header-wrapper", new StringBuilder(lightLogo));
            }
        }
        return css.toString();
    }

    /**
     * The theme's own palette variables for one mode; nothing for an unset colour.
     */
    private static void palette(StringBuilder out, String accent, String on) {
        if (accent != null) {
            out.append("    --eddi-primary: ").append(accent).append(";\n")
                    .append("    --eddi-primary-hover: color-mix(in oklch, ").append(accent).append(" 85%, black);\n")
                    .append("    --eddi-link: ").append(accent).append(";\n")
                    .append("    --eddi-focus: ").append(accent).append(";\n")
                    .append("    --eddi-bloom-near: color-mix(in srgb, ").append(accent).append(" 14%, transparent);\n")
                    .append("    --eddi-bloom-mid: color-mix(in srgb, ").append(accent).append(" 7%, transparent);\n")
                    .append("    --eddi-bloom-far: color-mix(in srgb, ").append(accent).append(" 2.5%, transparent);\n");
        }
        if (on != null) {
            out.append("    --eddi-on-primary: ").append(on).append(";\n");
        }
    }

    private static void block(StringBuilder css, String selector, StringBuilder declarations) {
        if (!declarations.isEmpty()) {
            css.append(selector).append(" {\n").append(declarations).append("}\n");
        }
    }

    /**
     * HTML text escaping for {@code displayNameHtml}, which Keycloak renders as
     * markup.
     */
    static String escapeHtml(String value) {
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

    /** A colour as it may be written into the stylesheet, or null. */
    private static String safe(String color) {
        return color != null && BrandValidator.isSafeColor(color) ? color : null;
    }

    private static boolean isImage(BrandAsset asset) {
        return switch (asset.contentType()) {
            case BrandAssetValidator.PNG, BrandAssetValidator.WEBP, BrandAssetValidator.SVG -> true;
            default -> false;
        };
    }

    /**
     * Keycloak's template links {@code img/favicon.ico} by that name, and serves it
     * as {@code image/x-icon}. Browsers read an ICO or a PNG from it; an SVG there
     * would not render, so an SVG icon leaves the shipped favicon in place.
     */
    private static boolean isFaviconCompatible(BrandAsset asset) {
        return BrandAssetValidator.ICO.equals(asset.contentType()) || BrandAssetValidator.PNG.equals(asset.contentType());
    }

    private static boolean hasOwnSvgIcon(ResolvedBrand brand, Function<BrandAssetSlot, Optional<BrandAsset>> assets) {
        return brand.branded() && assets.apply(BrandAssetSlot.FAVICON).or(() -> assets.apply(BrandAssetSlot.ICON))
                .map(a -> BrandAssetValidator.SVG.equals(a.contentType())).orElse(false);
    }

    private static String extension(BrandAsset asset) {
        return switch (asset.contentType()) {
            case BrandAssetValidator.SVG -> "svg";
            case BrandAssetValidator.WEBP -> "webp";
            default -> "png";
        };
    }

    /** The install notes, with the realm values to set. */
    static String readme(ResolvedBrand brand, boolean svgIconKept) {
        Map<String, String> realm = new LinkedHashMap<>();
        realm.put("loginTheme", THEME);
        realm.put("displayName", brand.productName());
        realm.put("displayNameHtml", escapeHtml(brand.productName()));
        String realmJson;
        try {
            realmJson = JSON.writeValueAsString(realm);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder readme = new StringBuilder();
        readme.append("# Login theme for this deployment\n\n")
                .append("This is the Keycloak login theme EDDI ships, renamed `").append(THEME)
                .append("` and carrying the brand set on the Manager's Admin → Branding page when it was downloaded. ")
                .append("Download it again after a rebrand: Keycloak cannot read the brand by itself.\n\n")
                .append("Built for Keycloak 26 (PatternFly 5, the `keycloak.v2` login theme).\n\n")
                .append("## Install\n\n")
                .append("1. Unzip it so that the theme sits at `/opt/keycloak/themes/").append(THEME).append("/login/theme.properties` ")
                .append("in the Keycloak container, for example as a read-only volume:\n\n")
                .append("   ```yaml\n   volumes:\n     - ./").append(THEME).append(":/opt/keycloak/themes/").append(THEME).append(":ro\n   ```\n\n")
                .append("   With the Helm chart, put the files in a ConfigMap or a volume mounted at that path in the Keycloak pod.\n\n")
                .append("2. Restart Keycloak, or start it with `--spi-theme-cache-themes=false` while trying the theme out.\n\n")
                .append("3. Set these on the realm the deployment signs in with (Admin Console → Realm settings → General and → Themes, ")
                .append("or the realm JSON you import):\n\n")
                .append("   ```json\n").append(realmJson.indent(3)).append("   ```\n\n")
                .append("   `displayName` is the browser tab title (\"Sign in to …\"). `displayNameHtml` is the heading the logo ")
                .append("replaces, kept as plain, escaped text so the logo still has an accessible name.\n\n")
                .append("## What it changes\n\n")
                .append("Only `resources/css/brand-overrides.css` (generated), `theme.properties` (which loads it last) and the ")
                .append("images in `resources/img/`. Everything else is the shipped theme, unchanged.\n");
        if (svgIconKept) {
            readme.append("\nThe brand's icon is an SVG. Keycloak links the favicon as `favicon.ico`, which an SVG cannot replace, ")
                    .append("so the shipped favicon is kept. Upload an ICO or PNG favicon on the Branding page to change it.\n");
        }
        return readme.toString();
    }

    private static byte[] readSource(String file) {
        try (InputStream in = getResourceAsStream(SOURCE + file)) {
            if (in == null) {
                throw new IllegalStateException("The Keycloak theme file " + file + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
