/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandDeliveryTest {

    private static ResolvedBrand brand(String productName, BrandColors colors) {
        return new ResolvedBrand(3, BrandSource.DATABASE, false, true, productName, productName, null, colors, true, false,
                BrandAssets.EMPTY);
    }

    @Test
    void unbrandedScriptIsWhatTheDevServerServes() throws IOException {
        String script = BrandDelivery.script(ResolvedBrand.defaults(false));
        assertEquals("window.__EDDI_BRAND__={branded:false,revision:0,productName:\"EDDI\",shortName:\"EDDI\",tagline:null,"
                + "poweredBy:true,showUpdateCheck:false,chat:{accent:null}};", script);
        // Both UIs' dev-server plugin must answer exactly this, or `npm run dev`
        // drifts from what an unbranded EDDI serves.
        for (String ui : new String[]{"manager", "chat"}) {
            Path plugin = Path.of("ui", ui, "scripts", "vite-plugin-brand.mjs");
            String source = Files.readString(plugin, StandardCharsets.UTF_8);
            assertTrue(source.contains("'" + script + "'"), plugin + " must carry the unbranded script verbatim");
        }
    }

    /** The §5 hostile inputs for the JS-string context. */
    @ParameterizedTest
    @ValueSource(strings = {"</script><script>alert(1)</script>", "\";alert(1)//", "'+alert(1)+'", "\\\";alert(1)//", "a b"})
    void scriptStringsCannotBreakOut(String productName) {
        String script = BrandDelivery.script(brand(productName, BrandColors.EMPTY));
        String literal = script.substring(script.indexOf("productName:") + "productName:".length(), script.indexOf(",shortName:"));
        assertTrue(literal.startsWith("\"") && literal.endsWith("\""), literal);
        String inner = literal.substring(1, literal.length() - 1);
        for (int i = 0; i < inner.length(); i++) {
            if (inner.charAt(i) == '"') {
                assertEquals('\\', inner.charAt(i - 1), "unescaped quote in " + literal);
                // an even run of backslashes before the quote would leave it unescaped
                int run = 0;
                for (int j = i - 1; j >= 0 && inner.charAt(j) == '\\'; j--) {
                    run++;
                }
                assertEquals(1, run % 2, "quote escaped by an even backslash run in " + literal);
            }
        }
        assertFalse(script.contains("</script"), script);
        assertFalse(script.contains(" "), script);
    }

    @Test
    void unbrandedStylesheetIsEmpty() {
        assertEquals("", BrandDelivery.stylesheet(ResolvedBrand.defaults(false)));
    }

    @Test
    void stylesheetSetsTheTokensOfBothUis() {
        BrandColors colors = BrandingService.resolveColors(new BrandColors("#0f766e", "#ffffff", "#2dd4bf", "#042f2e", null));
        String css = BrandDelivery.stylesheet(brand("Acme", colors));
        assertEquals("/* brand revision 3 */\n"
                + ":root{--color-primary:#0f766e;--color-ring:#0f766e;--color-sidebar-accent:#0f766e;"
                + "--color-primary-foreground:#ffffff;--color-sidebar-accent-foreground:#ffffff;--chat-accent:#0f766e;}\n"
                + ".dark{--color-primary:#2dd4bf;--color-ring:#2dd4bf;--color-sidebar-accent:#2dd4bf;"
                + "--color-primary-foreground:#042f2e;--color-sidebar-accent-foreground:#042f2e;}\n", css);
    }

    @Test
    void chatAccentAloneTouchesOnlyTheChat() {
        String css = BrandDelivery.stylesheet(brand("Acme", BrandingService.resolveColors(new BrandColors(null, null, null, null, "#123"))));
        assertEquals("/* brand revision 3 */\n:root{--chat-accent:#123;}\n", css);
    }

    @Test
    void darkColoursAloneStillReachTheDarkTheme() {
        String css = BrandDelivery.stylesheet(brand("Acme", BrandingService.resolveColors(new BrandColors(null, null, "#2dd4bf", "#042f2e", null))));
        assertEquals("/* brand revision 3 */\n:root{}\n"
                + ".dark{--color-primary:#2dd4bf;--color-ring:#2dd4bf;--color-sidebar-accent:#2dd4bf;"
                + "--color-primary-foreground:#042f2e;--color-sidebar-accent-foreground:#042f2e;}\n", css);
    }

    /**
     * Defence in depth: a value that somehow bypassed validation (a hand-edited
     * database document) is never emitted, and with nothing safe left the sheet is
     * empty.
     */
    @ParameterizedTest
    @ValueSource(strings = {"red;}body{display:none", "url(javascript:alert(1))", "expression(alert(1))", "#fff}</style>"})
    void unsafeStoredColoursAreNeverEmitted(String hostile) {
        BrandColors colors = new BrandColors(hostile, hostile, hostile, hostile, hostile);
        assertEquals("", BrandDelivery.stylesheet(brand("Acme", colors)));
        assertTrue(BrandDelivery.script(brand("Acme", colors)).contains("chat:{accent:null}"));
    }
}
