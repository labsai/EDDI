/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerUiBrandingTest {

    /**
     * The parts of the page Quarkus generates that the rewrite touches, as 3.40
     * writes them.
     */
    static final String GENERATED_PAGE = """
            <!DOCTYPE html>
            <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>EDDI API</title>
                    <link rel="stylesheet" type="text/css" href="swagger-ui.css" >
                    <link rel="shortcut icon" href="favicon.ico" type="image/x-icon">
                    <link rel="icon" href="favicon.ico" type="image/x-icon">
                    <link rel="stylesheet" type="text/css" href="style.css" >
                </head>
                <body>
                    <div id="swagger-ui"></div>
                    <footer id="footer">&#169; LABS.AI 2026</footer>
                    <script defer>
                        linkElement.innerHTML = "<a id='swaggerUiLogoLink' href='/q/swagger-ui'><img src='logo.png' alt='EDDI API' height='44' align='middle'></a><a id='swaggerUiTitleLink' href='/q/swagger-ui'></a>";
                    </script>
                </body>
            </html>
            """;

    private static final BrandColors TEAL = new BrandColors("#0f766e", "#ffffff", "#2dd4bf", "#042f2e", "#0f766e");

    private static ResolvedBrand brand(String name, BrandColors colors) {
        return new ResolvedBrand(7, BrandSource.DATABASE, false, true, name, name, null, colors, true, false, BrandAssets.EMPTY);
    }

    private static String styleSource() throws IOException {
        try (InputStream in = SwaggerUiBrandingTest.class.getClassLoader().getResourceAsStream(BrandedSwaggerUiRoute.STYLE_SOURCE)) {
            assertNotNull(in, "META-INF/branding/style.css is on the classpath");
            // Checked out with CRLF on Windows; the rewrite does not care, the assertions
            // do.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @Test
    void thePageTakesTheProductNameAndDropsTheLabsFooter() {
        String page = SwaggerUiBranding.page(GENERATED_PAGE, brand("Acme Assist", TEAL), true);

        assertTrue(page.contains("<title>Acme Assist API</title>"));
        assertTrue(page.contains("<footer id=\"footer\"></footer>"));
        assertTrue(page.contains("<img src='logo.png?r=7' alt='Acme Assist API' height='44' align='middle'>"));
        assertTrue(page.contains("href=\"favicon.ico?r=7\""));
        assertTrue(page.contains("href=\"style.css?r=7\""));
        assertFalse(page.contains("EDDI"));
        assertFalse(page.contains("LABS.AI"));
    }

    @Test
    void withoutALogoOfItsOwnThePageShowsTheNameNotEddisLogo() {
        String page = SwaggerUiBranding.page(GENERATED_PAGE, brand("Acme Assist", TEAL), false);

        assertFalse(page.contains("logo.png"));
        assertTrue(page.contains("<span class='eddi-brand-name'>Acme Assist API</span>"));
    }

    @Test
    void aHostileNameCannotLeaveTheTextOrTheScriptString() {
        // Names cannot contain < or > (BrandValidator); quotes, & and \ are allowed.
        String page = SwaggerUiBranding.page(GENERATED_PAGE, brand("A\"';x=1//\\&", TEAL), true);

        assertTrue(page.contains("<title>A&quot;&#39;;x=1//&#92;&amp; API</title>"));
        assertTrue(page.contains("alt='A&quot;&#39;;x=1//&#92;&amp; API'"));
        assertFalse(page.contains("A\"'"));
    }

    @Test
    void theStylesheetTakesTheBrandColoursPerModeAndKeepsPutAmber() throws IOException {
        String css = SwaggerUiBranding.stylesheet(styleSource(), TEAL);

        // Light rules take the light primary, and the label on a fill its foreground.
        assertTrue(css.contains(".swagger-ui .btn.execute {\n    background-color: #0f766e !important;\n    color: #ffffff !important;"),
                "light execute button");
        // Dark rules take the dark pair.
        assertTrue(
                css.contains("html.dark-mode .swagger-ui .btn.execute {\n    background-color: #2dd4bf !important;\n    color: #042f2e !important;"),
                "dark execute button");
        assertTrue(css.contains("color-mix(in srgb, #0f766e 20%, transparent)"), "translucent accents keep their alpha");

        // The only amber left is the PUT method's, which is a category colour.
        var amber = Pattern.compile("#f59e0b|#d97706|#b45309|#fbbf24|rgba\\(245, 158, 11|rgba\\(217, 119, 6").matcher(css);
        while (amber.find()) {
            int lineStart = css.lastIndexOf('\n', amber.start()) + 1;
            int lineEnd = css.indexOf('\n', amber.start());
            String line = css.substring(lineStart, lineEnd < 0 ? css.length() : lineEnd);
            assertTrue(line.contains("opblock-put") || line.trim().startsWith("*"), "unexpected amber: " + line);
        }
    }

    @Test
    void aCommentAboveARuleDoesNotDecideItsMode() throws IOException {
        // style.css opens its light section with a comment that mentions ".dark-mode";
        // only the selector itself may say which mode a rule is for.
        String css = SwaggerUiBranding.stylesheet("/* LIGHT MODE (when .dark-mode is NOT set) */\n.a { color: #f59e0b; }\n"
                + "/* not opblock-put */\nhtml.dark-mode .b { color: #f59e0b; }\n", TEAL);
        assertTrue(css.contains(".a { color: #0f766e; }"), css);
        assertTrue(css.contains("html.dark-mode .b { color: #2dd4bf; }"), css);

        String real = SwaggerUiBranding.stylesheet(styleSource(), TEAL);
        assertTrue(
                real.contains(".swagger-ui .info {\n    background-color: #fafaf9;\n    padding: 36px 48px;\n    border-bottom: 3px solid #0f766e;"));
    }

    @Test
    void withoutAPrimaryOrWithAnUnsafeOneTheStylesheetIsUnchanged() throws IOException {
        String source = styleSource();
        assertEquals(source, SwaggerUiBranding.stylesheet(source, BrandColors.EMPTY));
        assertEquals(source, SwaggerUiBranding.stylesheet(source, new BrandColors("red;}body{display:none", null, null, null, null)));
        // An unsafe dark colour falls back to the light one rather than being written.
        String css = SwaggerUiBranding.stylesheet(source, new BrandColors("#0f766e", null, "url(javascript:x)", null, null));
        assertFalse(css.contains("javascript"));
        assertTrue(css.contains("html.dark-mode .swagger-ui .btn.execute {\n    background-color: #0f766e !important;"));
    }

    @Test
    void swaggerRootFollowsTheQuarkusPathRules() {
        assertEquals("/q/swagger-ui", BrandedSwaggerUiRoute.swaggerRoot("swagger-ui", "q"));
        assertEquals("/q/swagger-ui", BrandedSwaggerUiRoute.swaggerRoot("swagger-ui", "/q/"));
        assertEquals("/docs", BrandedSwaggerUiRoute.swaggerRoot("/docs/", "q"));
    }
}
