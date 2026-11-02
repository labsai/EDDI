/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.ICO;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeycloakThemeExportTest {

    /**
     * The theme as it is checked in — what install.sh and docker-compose.auth.yml
     * mount.
     */
    private static final Path THEME_DIR = Path.of("keycloak", "themes", "eddi");

    private static final BrandColors TEAL = new BrandColors("#0f766e", "#ffffff", "#2dd4bf", "#042f2e", null);

    private static byte[] source(String file) {
        try {
            return Files.readAllBytes(THEME_DIR.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResolvedBrand brand(String name, BrandColors colors, BrandAssets assets) {
        return new ResolvedBrand(4, BrandSource.DATABASE, false, true, name, name, null, colors, true, false, assets);
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> zip, String file) {
        return new String(zip.get("brand/" + file), StandardCharsets.UTF_8);
    }

    private static Function<BrandAssetSlot, Optional<BrandAsset>> images(Map<BrandAssetSlot, BrandAsset> own) {
        return slot -> Optional.ofNullable(own.get(slot));
    }

    private static BrandAsset asset(BrandAssetSlot slot, String type, byte[] data) {
        return new BrandAsset(slot, BrandAssetValidator.hash(data), type, data);
    }

    @Test
    void theFileListIsExactlyTheCheckedInTheme() throws IOException {
        // The list is fixed in code because a jar cannot be listed reliably. A file
        // added to the theme must be added there, or the download would drop it.
        List<String> onDisk;
        try (Stream<Path> files = Files.walk(THEME_DIR)) {
            onDisk = files.filter(Files::isRegularFile).map(p -> THEME_DIR.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
        assertEquals(onDisk, KeycloakThemeExport.THEME_FILES.stream().sorted().toList());
    }

    @Test
    void theThemeIsOnTheClasspath() {
        // pom.xml copies keycloak/themes/eddi to keycloak-theme/eddi in the jar.
        for (String file : KeycloakThemeExport.THEME_FILES) {
            assertTrue(getClass().getClassLoader().getResource(KeycloakThemeExport.SOURCE + file) != null, file);
        }
    }

    @Test
    void unbrandedItIsTheShippedThemeWithAnOverridesFileThatDeclaresNothing() throws IOException {
        var zip = unzip(KeycloakThemeExport.build(ResolvedBrand.defaults(false), images(Map.of()), KeycloakThemeExportTest::source));

        for (String file : KeycloakThemeExport.THEME_FILES) {
            if (!file.equals(KeycloakThemeExport.THEME_PROPERTIES)) {
                assertArrayEquals(source(file), zip.get("brand/" + file), file);
            }
        }
        String overrides = text(zip, KeycloakThemeExport.OVERRIDES);
        assertFalse(overrides.contains("{"), "no rule at all: " + overrides);
        // Only the shipped files, the overrides and the README.
        assertEquals(KeycloakThemeExport.THEME_FILES.size() + 2, zip.size());
        assertTrue(text(zip, KeycloakThemeExport.THEME_PROPERTIES).contains("css/eddi-login.css css/brand-overrides.css"));
    }

    @Test
    void theOverridesLoadLastAndEverythingElseInThePropertiesIsKept() {
        String shipped = new String(source(KeycloakThemeExport.THEME_PROPERTIES), StandardCharsets.UTF_8);
        String generated = KeycloakThemeExport.themeProperties(shipped);

        Matcher styles = Pattern.compile("(?m)^styles=(.*)$").matcher(generated);
        assertTrue(styles.find());
        assertEquals("css/styles.css css/eddi-login.css css/brand-overrides.css", styles.group(1).strip());
        for (String line : shipped.split("\\R")) {
            if (!line.startsWith("styles=")) {
                assertTrue(generated.contains(line), "kept: " + line);
            }
        }
    }

    @Test
    void aBrandMapsItsColoursOntoBothPatternFlyTiersPerMode() throws IOException {
        var zip = unzip(KeycloakThemeExport.build(brand("Acme Assist", TEAL, BrandAssets.EMPTY), images(Map.of()),
                KeycloakThemeExportTest::source));
        String css = text(zip, KeycloakThemeExport.OVERRIDES);

        // Dark is the theme's default state: :root carries the dark pair...
        String root = css.substring(css.indexOf(":root {"), css.indexOf("}", css.indexOf(":root {")));
        assertTrue(root.contains("--eddi-primary: #2dd4bf;"));
        assertTrue(root.contains("--eddi-on-primary: #042f2e;"));
        // ...and both PF5 tiers (keycloak-eddi-theme.md F18): --100 for the light
        // theme, --300 for the button and --400 for its label under the dark one.
        for (String tier : List.of("--pf-v5-global--primary-color--100: var(--eddi-primary);",
                "--pf-v5-global--primary-color--300: var(--eddi-primary);",
                "--pf-v5-global--primary-color--400: var(--eddi-on-primary);",
                "--pf-v5-global--primary-color--light-100: var(--eddi-primary);",
                "--pf-v5-global--active-color--100: var(--eddi-primary);",
                // keycloak.v2 26.x: the 4px rule above the card, PatternFly blue otherwise.
                "--keycloak-card-top-color: var(--eddi-primary);")) {
            assertTrue(root.contains(tier), tier);
        }
        // A warning stays amber.
        assertTrue(root.contains("--pf-v5-global--warning-color--100: #f59e0b;"));
        // The light pair, under both of the theme's light-mode selectors.
        assertTrue(css.contains("@media (prefers-color-scheme: light) {\n:root:not([data-eddi-theme]) {\n    --eddi-primary: #0f766e;"));
        assertTrue(css.contains(":root[data-eddi-theme=\"light\"] {\n    --eddi-primary: #0f766e;"));
        // No logo of its own: the realm's display name is shown, never EDDI's wordmark.
        assertTrue(css.contains("#kc-header-wrapper {\n    background-image: none;\n    text-indent: 0;"));
    }

    @Test
    void theShippedThemeColoursKeycloaksCardTopRuleWithItsAccent() {
        // keycloak.v2 (26.x) declares --keycloak-card-top-color on :root as PatternFly
        // blue-400 and draws a 4px border-top on the card with it. eddi-login.css loads
        // after it, so its :root declaration wins and the rule takes the EDDI accent.
        String css = new String(source("login/resources/css/eddi-login.css"), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("--keycloak-card-top-color:\\s*var\\(--eddi-primary\\);").matcher(css).find());
    }

    @Test
    void theBrandsImagesReplaceTheLogoAndFavicon() throws IOException {
        byte[] logo = svg("<rect width='10' height='10'/>");
        Map<BrandAssetSlot, BrandAsset> own = new EnumMap<>(BrandAssetSlot.class);
        own.put(BrandAssetSlot.LOGO, asset(BrandAssetSlot.LOGO, BrandAssetValidator.SVG, logo));
        own.put(BrandAssetSlot.LOGO_DARK, asset(BrandAssetSlot.LOGO_DARK, BrandAssetValidator.PNG, PNG));
        own.put(BrandAssetSlot.FAVICON, asset(BrandAssetSlot.FAVICON, BrandAssetValidator.ICO, ICO));

        var zip = unzip(KeycloakThemeExport.build(brand("Acme", TEAL, BrandAssets.EMPTY), images(own), KeycloakThemeExportTest::source));

        assertArrayEquals(logo, zip.get("brand/login/resources/img/brand-logo.svg"));
        assertArrayEquals(PNG, zip.get("brand/login/resources/img/brand-logo-dark.png"));
        assertArrayEquals(ICO, zip.get("brand/" + KeycloakThemeExport.FAVICON));
        String css = text(zip, KeycloakThemeExport.OVERRIDES);
        assertTrue(css.contains("#kc-header-wrapper {\n    background-image: url(../img/brand-logo-dark.png);\n}"));
        assertTrue(css.contains(":root[data-eddi-theme=\"light\"] #kc-header-wrapper {\n    background-image: url(../img/brand-logo.svg);\n}"));
        assertTrue(css.contains("--eddi-logo-filter: none;"));
    }

    @Test
    void anSvgIconKeepsTheShippedFaviconAndTheReadmeSaysWhy() throws IOException {
        Map<BrandAssetSlot, BrandAsset> own = Map.of(BrandAssetSlot.ICON,
                asset(BrandAssetSlot.ICON, BrandAssetValidator.SVG, svg("<circle r='4'/>")));

        var zip = unzip(KeycloakThemeExport.build(brand("Acme", BrandColors.EMPTY, BrandAssets.EMPTY), images(own),
                KeycloakThemeExportTest::source));

        assertArrayEquals(source(KeycloakThemeExport.FAVICON), zip.get("brand/" + KeycloakThemeExport.FAVICON));
        assertTrue(text(zip, KeycloakThemeExport.README).contains("Upload an ICO or PNG favicon"));
    }

    @Test
    void theReadmeGivesTheRealmValuesEscaped() throws IOException {
        String hostile = "Acme \"Q&A\" 'x' \\ {y}";
        String readme = KeycloakThemeExport.readme(brand(hostile, BrandColors.EMPTY, BrandAssets.EMPTY), false);

        String json = readme.substring(readme.indexOf("```json") + 7, readme.indexOf("```", readme.indexOf("```json") + 7));
        JsonNode realm = new ObjectMapper().readTree(json);
        assertEquals("brand", realm.get("loginTheme").asText());
        assertEquals(hostile, realm.get("displayName").asText());
        assertEquals("Acme &quot;Q&amp;A&quot; &#39;x&#39; \\ {y}", realm.get("displayNameHtml").asText());
    }

    @Test
    void noHostileColourOrNameReachesTheStylesheet() throws IOException {
        // Values the validator refuses, as a corrupted store or a future bug could
        // hand them over: each is checked again on the way out and dropped.
        var hostile = new BrandColors("red;}body{display:none", "url(javascript:alert(1))", "expression(alert(1))",
                "#fff;}*{color:red", "var(--x)");
        var zip = unzip(KeycloakThemeExport.build(brand("Evil */ } body { display:none", hostile, BrandAssets.EMPTY), images(Map.of()),
                KeycloakThemeExportTest::source));
        String css = text(zip, KeycloakThemeExport.OVERRIDES);

        for (String fragment : List.of("display:none", "javascript", "expression", "*{color", "var(--x)", "Evil")) {
            assertFalse(css.contains(fragment), fragment);
        }
        assertFalse(css.contains("--eddi-primary:"), "no colour survived, so none is declared");
    }

    @Test
    void theSameBrandAlwaysYieldsTheSameArchive() {
        var brand = brand("Acme", TEAL, BrandAssets.EMPTY);
        assertArrayEquals(KeycloakThemeExport.build(brand, images(Map.of()), KeycloakThemeExportTest::source),
                KeycloakThemeExport.build(brand, images(Map.of()), KeycloakThemeExportTest::source));
    }
}
