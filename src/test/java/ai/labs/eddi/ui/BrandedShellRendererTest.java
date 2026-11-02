/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import ai.labs.eddi.configs.branding.BrandingService;
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
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Head substitution. The real shells are read from the source tree, because the
 * unit-test classpath carries stand-ins (the UI is not built there).
 */
class BrandedShellRendererTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("%BRAND_[A-Z_]+%");

    /** The product name, not an identifier like {@code __EDDI_AUTH__}. */
    private static final Pattern EDDI_WORD = Pattern.compile("(?<![A-Za-z_])EDDI(?![A-Za-z_])");

    private static final Map<BrandedShell, Path> SOURCES = Map.of(
            BrandedShell.MANAGE, Path.of("ui/manager/manage.html"),
            BrandedShell.WELCOME, Path.of("ui/manager/welcome.html"),
            BrandedShell.WORKFORCE, Path.of("ui/manager/workforce.html"),
            BrandedShell.CHAT, Path.of("ui/chat/chat.html"),
            BrandedShell.ROOT, Path.of("src/main/resources/META-INF/resources/index.html"));

    private static String unbranded(BrandedShell shell) throws IOException {
        String template = Files.readString(SOURCES.get(shell), StandardCharsets.UTF_8);
        return BrandedShellRenderer.substitute(template, shell, ResolvedBrand.defaults(false));
    }

    private static ResolvedBrand branded(String productName, String tagline, String primary) {
        return new ResolvedBrand(5, BrandSource.DATABASE, false, true, productName, productName, tagline,
                new BrandColors(primary, null, primary, null, primary), true, false, BrandAssets.EMPTY);
    }

    /**
     * Unbranded, every piece of text a person or a link preview sees is what the
     * shell showed before branding existed.
     */
    @Test
    void unbrandedTextIsTheTextOfBefore() throws IOException {
        String manage = unbranded(BrandedShell.MANAGE);
        assertTrue(manage.contains("<title>EDDI Manager</title>"));
        assertTrue(manage.contains("content=\"EDDI Manager — Admin dashboard for the EDDI conversational AI platform\""));
        assertTrue(manage.contains("alt=\"EDDI\""));
        assertTrue(manage.contains("Loading EDDI Manager…"));

        String welcome = unbranded(BrandedShell.WELCOME);
        assertTrue(welcome.contains("<title>EDDI</title>"));
        assertTrue(welcome.contains("content=\"EDDI — Open Source Conversational AI Platform\""));
        assertTrue(welcome.contains("Loading EDDI…"));

        String workforce = unbranded(BrandedShell.WORKFORCE);
        assertTrue(workforce.contains("<title>EDDI Workforce</title>"));
        assertTrue(workforce.contains("content=\"EDDI Workforce — AI teams that solve complex challenges together\""));
        assertTrue(workforce.contains("Loading EDDI Workforce…"));

        String chat = unbranded(BrandedShell.CHAT);
        assertTrue(chat.contains("<meta name=\"theme-color\" content=\"#1a1a1a\" />"));
        assertTrue(chat.contains("<title>EDDI Chat</title>"));
        assertTrue(chat.contains("<meta name=\"description\" content=\"EDDI — Open Source Conversational AI\" />"));
        assertTrue(chat.contains("<meta property=\"og:title\" content=\"EDDI Chat\" />"));
        assertTrue(chat.contains("<meta property=\"og:description\" content=\"Open Source Conversational AI\" />"));

        String root = unbranded(BrandedShell.ROOT);
        assertTrue(root.contains("<title>EDDI — Redirecting</title>"));
        assertTrue(root.contains("Redirecting to <a href=\"/welcome\">EDDI</a>…"));
    }

    @Test
    void everyShellIsFullySubstitutedAndLoadsTheBrand() throws IOException {
        for (BrandedShell shell : BrandedShell.values()) {
            String template = Files.readString(SOURCES.get(shell), StandardCharsets.UTF_8);
            assertTrue(template.contains("%BRAND_PRODUCT_NAME%"), shell + " has no placeholder");
            assertTrue(template.contains("/branding/__brand__.js"), shell + " does not load the brand script");
            assertTrue(template.contains("/branding/brand.css"), shell + " does not load the brand stylesheet");
            assertTrue(template.contains("<link rel=\"icon\" href=\"/branding/"), shell + " has a fixed favicon");
            String page = BrandedShellRenderer.substitute(template, shell, branded("Acme", "Teams", "#0f766e"));
            assertFalse(PLACEHOLDER.matcher(page).find(), shell + " left a placeholder");
            String visible = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(page).replaceAll("");
            assertFalse(EDDI_WORD.matcher(visible).find(), shell + " still names EDDI when branded:\n" + visible);
        }
    }

    @Test
    void brandedValuesAreSubstituted() throws IOException {
        String template = Files.readString(SOURCES.get(BrandedShell.CHAT), StandardCharsets.UTF_8);
        String page = BrandedShellRenderer.substitute(template, BrandedShell.CHAT, branded("Acme Assist", "AI teams for Acme", "#0f766e"));
        assertTrue(page.contains("<title>Acme Assist Chat</title>"));
        assertTrue(page.contains("<meta name=\"theme-color\" content=\"#0f766e\" />"));
        assertTrue(page.contains("<meta property=\"og:description\" content=\"AI teams for Acme\" />"));
    }

    /** The §5 hostile inputs for the HTML context — defence behind validation. */
    @ParameterizedTest
    @ValueSource(strings = {"</title><script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", "' onmouseover='x"})
    void valuesAreHtmlEscaped(String hostile) {
        String template = "<title>%BRAND_PRODUCT_NAME%</title><meta content=\"%BRAND_TAGLINE%\"><meta content='%BRAND_PRODUCT_NAME%'>";
        String page = BrandedShellRenderer.substitute(template, BrandedShell.WELCOME, branded(hostile, hostile, null));
        assertFalse(page.contains("<script"), page);
        assertFalse(page.contains("<img"), page);
        assertFalse(page.contains("' on"), page);
    }

    @Test
    void escapeHtmlCoversTextAndBothQuotes() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;x", BrandedShellRenderer.escapeHtml("&<>\"'x"));
    }

    @Test
    void renderIsCachedPerRevision() {
        BrandingService branding = mock(BrandingService.class);
        ResolvedBrand first = branded("One", null, null);
        when(branding.current(BrandRequest.NONE)).thenReturn(first);
        BrandedShellRenderer renderer = new BrandedShellRenderer(branding);
        String a = renderer.render(BrandedShell.MANAGE, BrandRequest.NONE).orElseThrow();
        assertSame(a, renderer.render(BrandedShell.MANAGE, BrandRequest.NONE).orElseThrow(), "same revision, same page");

        ResolvedBrand next = new ResolvedBrand(6, BrandSource.DATABASE, false, true, "Two", "Two", null, BrandColors.EMPTY, true, false,
                BrandAssets.EMPTY);
        when(branding.current(BrandRequest.NONE)).thenReturn(next);
        String b = renderer.render(BrandedShell.MANAGE, BrandRequest.NONE).orElseThrow();
        assertTrue(b.contains("Two"), b);
        assertFalse(b.contains("One"), b);
    }

    @Test
    void missingShellIsEmpty() {
        BrandingService branding = mock(BrandingService.class);
        when(branding.current(BrandRequest.NONE)).thenReturn(ResolvedBrand.defaults(false));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new ClassLoader(null) {
        });
        try {
            assertTrue(new BrandedShellRenderer(branding).render(BrandedShell.CHAT, BrandRequest.NONE).isEmpty());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
