/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.BrandingTestSupport.Config;
import ai.labs.eddi.configs.branding.BrandingTestSupport.MemoryStore;
import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import ai.labs.eddi.configs.branding.model.BrandAssets;
import ai.labs.eddi.configs.branding.model.BrandColors;
import ai.labs.eddi.configs.branding.model.BrandDocument;
import ai.labs.eddi.configs.branding.model.BrandSource;
import ai.labs.eddi.configs.branding.model.BrandUpdate;
import ai.labs.eddi.configs.branding.model.BrandingView;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private static final BrandRequest REQ = BrandRequest.NONE;

    private MemoryStore store;
    private Config config;

    @BeforeEach
    void setUp() {
        store = new MemoryStore();
        config = new Config();
    }

    private BrandingService service() {
        return new BrandingService(store, config, CLOCK, Duration.ofHours(1));
    }

    private static BrandUpdate update(long revision, String productName) {
        return new BrandUpdate(revision, productName, null, null, null, null, null);
    }

    @Nested
    @DisplayName("resolution order")
    class Resolution {

        @Test
        void nothingSetIsTheEddiBrand() {
            ResolvedBrand brand = service().current(REQ);
            assertEquals(ResolvedBrand.defaults(false), brand);
            assertFalse(brand.branded());
            assertEquals(BrandSource.DEFAULT, brand.source());
            assertEquals("EDDI", brand.productName());
            assertTrue(brand.poweredBy());
            assertFalse(brand.showUpdateCheck());
        }

        @Test
        void seedAppliesWhenNoDocumentExists() {
            config.productName = "Acme Assist";
            config.primary = "#0f766e";
            ResolvedBrand brand = service().current(REQ);
            assertEquals(BrandSource.PROPERTIES, brand.source());
            assertTrue(brand.branded());
            assertEquals("Acme Assist", brand.productName());
            assertEquals("Acme Assist", brand.shortName(), "an unset short name follows the product name");
            assertEquals(new BrandColors("#0f766e", null, "#0f766e", null, "#0f766e"), brand.colors(),
                    "dark follows light, the chat accent follows primary");
        }

        @Test
        void documentWinsFieldByFieldOverTheSeed() {
            config.productName = "Seed Name";
            config.tagline = "Seed tagline";
            config.primary = "#111111";
            store.document = new BrandDocument(4, "Stored Name", null, null, new BrandColors(null, "#ffffff", null, null, null), false,
                    true, null, null, null);
            ResolvedBrand brand = service().current(REQ);
            assertEquals(BrandSource.DATABASE, brand.source());
            assertEquals(4, brand.revision());
            assertEquals("Stored Name", brand.productName());
            assertEquals("Seed tagline", brand.tagline());
            assertEquals("#111111", brand.colors().primary());
            assertEquals("#ffffff", brand.colors().primaryForeground());
            assertFalse(brand.poweredBy());
            assertTrue(brand.showUpdateCheck());
        }

        @Test
        void lockedIgnoresTheDocument() {
            config.locked = true;
            config.productName = "Locked Name";
            store.document = new BrandDocument(9, "Stored Name", null, null, null, null, null, null, null, null);
            BrandingService service = service();
            ResolvedBrand brand = service.current(REQ);
            assertEquals("Locked Name", brand.productName());
            assertEquals(BrandSource.PROPERTIES, brand.source());
            assertTrue(brand.locked());
            assertEquals(0, brand.revision());
            assertEquals(0, store.reads.get(), "a locked brand never reads the database");
            BrandingView view = service.view(REQ);
            assertTrue(view.locked());
            assertEquals("Locked Name", view.document().productName(), "the page shows what is in effect");
        }

        @Test
        void lockedWithNothingSetIsTheEddiBrand() {
            config.locked = true;
            ResolvedBrand brand = service().current(REQ);
            assertEquals(BrandSource.DEFAULT, brand.source());
            assertTrue(brand.locked());
        }

        @Test
        void emptyDocumentAfterResetFallsBackToTheSeedButKeepsItsRevision() {
            config.productName = "Seed Name";
            store.document = BrandDocument.EMPTY.stamped(6, null, null);
            ResolvedBrand brand = service().current(REQ);
            assertEquals("Seed Name", brand.productName());
            assertEquals(BrandSource.PROPERTIES, brand.source());
            assertEquals(6, brand.revision());
        }

        @Test
        void databaseFailureServesTheSeed() {
            config.productName = "Seed Name";
            IBrandingStore failing = new MemoryStore() {
                @Override
                public synchronized Optional<BrandDocument> read() {
                    throw new IllegalStateException("database down");
                }
            };
            ResolvedBrand brand = new BrandingService(failing, config, CLOCK, Duration.ofHours(1)).current(REQ);
            assertEquals("Seed Name", brand.productName());
        }
    }

    @Nested
    @DisplayName("the seed")
    class Seed {

        @Test
        void invalidSeedFailsStartupNamingTheProperty() {
            config.primary = "red;}body{display:none";
            IllegalStateException e = assertThrows(IllegalStateException.class, BrandingServiceTest.this::service);
            assertTrue(e.getMessage().contains("eddi.branding.color.primary"), e.getMessage());

            config.primary = null;
            config.shortName = "Much Too Long A Short Name";
            e = assertThrows(IllegalStateException.class, BrandingServiceTest.this::service);
            assertTrue(e.getMessage().contains("eddi.branding.short-name"), e.getMessage());

            config.shortName = null;
            config.productName = "<img onerror=x>";
            e = assertThrows(IllegalStateException.class, BrandingServiceTest.this::service);
            assertTrue(e.getMessage().contains("eddi.branding.product-name"), e.getMessage());
        }

        @Test
        void emptySeedValuesAreUnset() {
            config.productName = "   ";
            config.primary = "";
            assertEquals(BrandSource.DEFAULT, service().current(REQ).source());
        }

        @Test
        void propertyNamesAreKebabCase() {
            assertEquals("eddi.branding.product-name", BrandingService.propertyName("productName"));
            assertEquals("eddi.branding.color.dark-primary-foreground", BrandingService.propertyName("colors.darkPrimaryForeground"));
            assertEquals("eddi.branding.tagline", BrandingService.propertyName("tagline"));
        }

        @Test
        void assetsDirIsReadAndValidated(@TempDir Path dir) throws IOException {
            Files.write(dir.resolve("logo.png"), PNG);
            Files.write(dir.resolve("icon.svg"), svg("<rect/>"));
            config.assetsDir = dir.toString();
            BrandingService service = service();
            ResolvedBrand brand = service.current(REQ);
            assertEquals(BrandSource.PROPERTIES, brand.source());
            assertEquals(BrandAssetValidator.hash(PNG), brand.assets().logo());
            assertArrayEquals(PNG, service.asset(REQ, BrandAssetSlot.LOGO).orElseThrow().data());
            assertArrayEquals(PNG, service.asset(REQ, BrandAssetSlot.OG_IMAGE).orElseThrow().data(), "og-image falls back to the logo");
            assertEquals(BrandAssetValidator.SVG, service.asset(REQ, BrandAssetSlot.FAVICON).orElseThrow().contentType(),
                    "favicon falls back to the icon");
            assertEquals(0, store.assetReads.get(), "seed images never touch the database");
        }

        @Test
        void hostileSeedImageFailsStartup(@TempDir Path dir) throws IOException {
            Files.write(dir.resolve("logo.svg"), svg("<script>alert(1)</script>"));
            config.assetsDir = dir.toString();
            IllegalStateException e = assertThrows(IllegalStateException.class, BrandingServiceTest.this::service);
            assertTrue(e.getMessage().contains("eddi.branding.assets-dir"), e.getMessage());
            assertTrue(e.getMessage().contains("logo.svg"), e.getMessage());
        }

        @Test
        void missingAssetsDirFailsStartup(@TempDir Path dir) {
            config.assetsDir = dir.resolve("nope").toString();
            assertThrows(IllegalStateException.class, BrandingServiceTest.this::service);
        }
    }

    @Nested
    @DisplayName("writes")
    class Writes {

        @Test
        void updateAdvancesTheRevisionAndStampsTheUser() {
            BrandingService service = service();
            BrandingView view = service.update(REQ, update(0, "  Acme  "), "alice");
            assertEquals(1, view.effective().revision());
            assertEquals("Acme", view.effective().productName(), "values are stripped");
            assertEquals("alice", store.document.updatedBy());
            assertEquals("2026-10-24T10:00:00Z", store.document.updatedAt());

            view = service.update(REQ, update(1, "Acme 2"), "bob");
            assertEquals(2, view.effective().revision());
            assertEquals("Acme 2", service.current(REQ).productName());
        }

        @Test
        void staleRevisionIsAConflict() {
            BrandingService service = service();
            service.update(REQ, update(0, "First"), "alice");
            BrandingException e = assertThrows(BrandingException.class, () -> service.update(REQ, update(0, "Second"), "bob"));
            assertEquals(409, e.status());
            assertEquals(1L, e.currentRevision());
            assertEquals("First", store.document.productName(), "the lost update did not land");
        }

        @Test
        void losingTheCompareAndSetIsAConflict() {
            BrandingService service = service();
            store.failNextWrites = 1;
            assertEquals(409, assertThrows(BrandingException.class, () -> service.update(REQ, update(0, "X"), "a")).status());
        }

        @Test
        void missingRevisionIsInvalid() {
            BrandingException e = assertThrows(BrandingException.class,
                    () -> service().update(REQ, new BrandUpdate(null, "X", null, null, null, null, null), "a"));
            assertEquals(400, e.status());
            assertEquals("revision", e.violations().getFirst().field());
        }

        @Test
        void invalidValuesAreRefusedWithEveryViolation() {
            BrandUpdate bad = new BrandUpdate(0L, "</script>", null, "ok", new BrandColors("url(javascript:alert(1))", "#fff", null, null,
                    "expression(alert(1))"), null, null);
            BrandingException e = assertThrows(BrandingException.class, () -> service().update(REQ, bad, "a"));
            assertEquals(400, e.status());
            assertEquals(List.of("productName", "colors.primary", "colors.chatAccent"),
                    e.violations().stream().map(v -> v.field()).toList());
            assertNull(store.document);
        }

        @Test
        void lowContrastIsAWarningNotARefusal() {
            BrandUpdate grey = new BrandUpdate(0L, null, null, null, new BrandColors("#777777", "#888888", null, null, null), null, null);
            BrandingView view = service().update(REQ, grey, "a");
            assertEquals(1, view.warnings().size());
            assertEquals("#777777", view.effective().colors().primary());
        }

        @Test
        void everyWriteIsRefusedWhenLocked() {
            config.locked = true;
            BrandingService service = service();
            assertEquals(409, assertThrows(BrandingException.class, () -> service.update(REQ, update(0, "X"), "a")).status());
            assertEquals(409, assertThrows(BrandingException.class, () -> service.putAsset(REQ, BrandAssetSlot.LOGO, PNG, "a")).status());
            assertEquals(409, assertThrows(BrandingException.class, () -> service.deleteAsset(REQ, BrandAssetSlot.LOGO, "a")).status());
            assertEquals(409, assertThrows(BrandingException.class, () -> service.reset(REQ, "a")).status());
            assertTrue(assertThrows(BrandingException.class, () -> service.reset(REQ, "a")).getMessage().contains("eddi.branding.locked"));
            assertNull(store.document);
        }

        @Test
        void assetUploadKeepsTheTextAndAssetDeleteRevertsToTheDefault() {
            BrandingService service = service();
            service.update(REQ, update(0, "Acme"), "a");
            BrandingView view = service.putAsset(REQ, BrandAssetSlot.LOGO, PNG, "a");
            assertEquals(2, view.effective().revision());
            assertEquals("Acme", view.effective().productName());
            assertEquals(BrandAssetValidator.hash(PNG), view.document().assets().logo());
            assertArrayEquals(PNG, service.asset(REQ, BrandAssetSlot.LOGO_DARK).orElseThrow().data(), "logo-dark follows the logo");

            view = service.deleteAsset(REQ, BrandAssetSlot.LOGO, "a");
            assertEquals(3, view.effective().revision());
            assertNull(view.document().assets().logo());
            assertTrue(store.assets.isEmpty());
        }

        @Test
        void assetWriteRetriesALostCompareAndSet() {
            BrandingService service = service();
            store.failNextWrites = BrandingService.ASSET_WRITE_ATTEMPTS - 1;
            assertEquals(1, service.putAsset(REQ, BrandAssetSlot.ICON, PNG, "a").effective().revision());
            store.failNextWrites = BrandingService.ASSET_WRITE_ATTEMPTS;
            assertEquals(409, assertThrows(BrandingException.class, () -> service.putAsset(REQ, BrandAssetSlot.ICON, PNG, "a")).status());
        }

        @Test
        void rejectedUploadStoresNothing() {
            BrandingService service = service();
            assertEquals(400, assertThrows(BrandingException.class,
                    () -> service.putAsset(REQ, BrandAssetSlot.LOGO, svg("<script/>"), "a")).status());
            assertTrue(store.assets.isEmpty());
            assertNull(store.document);
        }

        @Test
        void resetClearsEverythingAndTheRevisionStillGrows() {
            config.productName = "Seed";
            BrandingService service = service();
            service.update(REQ, update(0, "Stored"), "a");
            service.putAsset(REQ, BrandAssetSlot.LOGO, PNG, "a");
            BrandingView view = service.reset(REQ, "a");
            assertEquals(3, view.effective().revision(), "revision is monotonic across a reset");
            assertEquals("Seed", view.effective().productName());
            assertEquals(BrandSource.PROPERTIES, view.source());
            assertTrue(store.assets.isEmpty());
            assertFalse(store.document.hasValues());
        }

        @Test
        void changedFieldsNameWhatDiffers() {
            BrandDocument before = new BrandDocument(1, "A", null, null, null, null, null, null, null, null);
            BrandDocument after = new BrandDocument(2, "B", null, null, new BrandColors("#fff", null, null, null, null), null, null,
                    BrandAssets.EMPTY.with(BrandAssetSlot.ICON, "sha256:x"), "t", "u");
            assertEquals(List.of("productName", "colors.primary", "assets.icon"), BrandingService.changedFields(before, after));
        }
    }

    @Nested
    @DisplayName("cache")
    class CacheBehaviour {

        @Test
        void readsAreCachedAndALocalWriteInvalidatesAtOnce() {
            BrandingService service = service();
            service.current(REQ);
            service.current(REQ);
            service.view(REQ);
            assertEquals(1, store.reads.get(), "the brand is read once per TTL");

            service.update(REQ, update(0, "Acme"), "a");
            assertEquals("Acme", service.current(REQ).productName(), "the writing node sees its write immediately");
        }

        @Test
        void anotherNodesWriteShowsAfterTheTtl() {
            BrandingService service = new BrandingService(store, config, CLOCK, Duration.ZERO);
            assertEquals("EDDI", service.current(REQ).productName());
            store.document = new BrandDocument(1, "Elsewhere", null, null, null, null, null, null, null, null);
            assertEquals("Elsewhere", service.current(REQ).productName());
        }

        @Test
        void storedImageBytesAreCachedByHash() {
            BrandingService service = service();
            service.putAsset(REQ, BrandAssetSlot.LOGO, PNG, "a");
            store.assetReads.set(0);
            service.asset(REQ, BrandAssetSlot.LOGO);
            service.asset(REQ, BrandAssetSlot.LOGO);
            assertEquals(0, store.assetReads.get());
        }

        @Test
        void anotherNodesImageIsReadFromTheStore() {
            BrandAsset elsewhere = BrandAssetValidator.validate(BrandAssetSlot.ICON, PNG);
            store.assets.put(BrandAssetSlot.ICON, elsewhere);
            store.document = BrandDocument.EMPTY.withAssets(BrandAssets.EMPTY.with(BrandAssetSlot.ICON, elsewhere.hash())).stamped(1, null,
                    null);
            assertArrayEquals(PNG, service().asset(REQ, BrandAssetSlot.ICON).orElseThrow().data());
            assertEquals(1, store.assetReads.get());
        }
    }

    @Test
    void unbrandedAssetsAreTheBundledEddiImagesWhenOnTheClasspath() {
        // The unit-test classpath has no UI build, so the bundled images may be
        // missing; either way the answer is never a stored or seeded image.
        BrandingService service = service();
        for (BrandAssetSlot slot : BrandAssetSlot.values()) {
            service.asset(REQ, slot).ifPresent(asset -> assertTrue(asset.data().length > 0));
        }
        assertEquals(0, store.assetReads.get());
    }
}
