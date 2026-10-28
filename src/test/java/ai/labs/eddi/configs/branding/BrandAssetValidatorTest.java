/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.branding;

import ai.labs.eddi.configs.branding.model.BrandAsset;
import ai.labs.eddi.configs.branding.model.BrandAssetSlot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static ai.labs.eddi.configs.branding.BrandingTestSupport.ICO;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.PNG;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.WEBP;
import static ai.labs.eddi.configs.branding.BrandingTestSupport.svg;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrandAssetValidatorTest {

    private static int refusal(BrandAssetSlot slot, byte[] data) {
        return assertThrows(BrandingException.class, () -> BrandAssetValidator.validate(slot, data)).status();
    }

    @Nested
    @DisplayName("type by magic bytes")
    class MagicBytes {

        @Test
        void pngWebpAndSvgAreDetected() {
            assertEquals(BrandAssetValidator.PNG, BrandAssetValidator.validate(BrandAssetSlot.LOGO, PNG).contentType());
            assertEquals(BrandAssetValidator.WEBP, BrandAssetValidator.validate(BrandAssetSlot.LOGO, WEBP).contentType());
            assertEquals(BrandAssetValidator.SVG, BrandAssetValidator.validate(BrandAssetSlot.LOGO, svg("<rect/>")).contentType());
        }

        @Test
        void svgWithDeclarationCommentDoctypeAndBomIsDetected() {
            String doc = "﻿  <?xml version=\"1.0\"?>\n<!-- made with a tool -->\n"
                    + "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n"
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>";
            assertEquals(BrandAssetValidator.SVG, BrandAssetValidator.detectType(doc.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void icoOnlyForTheFavicon() {
            assertEquals(BrandAssetValidator.ICO, BrandAssetValidator.validate(BrandAssetSlot.FAVICON, ICO).contentType());
            for (BrandAssetSlot slot : BrandAssetSlot.values()) {
                if (slot != BrandAssetSlot.FAVICON) {
                    assertEquals(BrandingException.UNSUPPORTED_TYPE, refusal(slot, ICO), slot.name());
                }
            }
        }

        /**
         * The name and Content-Type never decide: content that is not an image is
         * refused.
         */
        @ParameterizedTest
        @ValueSource(strings = {"<html><script>alert(1)</script></html>", "GIF89a....", "ÿØÿà jpeg", "plain text",
                "<?xml version=\"1.0\"?><html/>"})
        void otherContentIsUnsupported(String content) {
            assertEquals(BrandingException.UNSUPPORTED_TYPE, refusal(BrandAssetSlot.LOGO, content.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void invalidUtf8IsNotSvg() {
            byte[] data = {'<', 's', 'v', 'g', (byte) 0xC3, (byte) 0x28, '>'};
            assertNull(BrandAssetValidator.detectType(data));
        }

        @Test
        void icoNeedsAnImageCount() {
            assertNull(BrandAssetValidator.detectType(new byte[]{0, 0, 1, 0, 0, 0}));
        }
    }

    @Nested
    @DisplayName("size")
    class Size {

        @Test
        void emptyIsInvalid() {
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, new byte[0]));
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, null));
        }

        @Test
        void capIs512Kb() {
            byte[] atCap = Arrays.copyOf(PNG, BrandAssetValidator.MAX_BYTES);
            assertEquals(BrandAssetValidator.PNG, BrandAssetValidator.validate(BrandAssetSlot.LOGO, atCap).contentType());
            byte[] over = Arrays.copyOf(PNG, BrandAssetValidator.MAX_BYTES + 1);
            assertEquals(BrandingException.TOO_LARGE, refusal(BrandAssetSlot.LOGO, over));
        }
    }

    @Nested
    @DisplayName("SVG filter")
    class SvgFilter {

        @ParameterizedTest
        @ValueSource(strings = {"<script>alert(1)</script>", "<SCRIPT>alert(1)</SCRIPT>", "<svg:script>alert(1)</svg:script>",
                "<script\n>alert(1)</script>", "<foreignObject><div/></foreignObject>", "<x:foreignobject/>", "<iframe src=x/>",
                "<embed src=x/>", "<object data=x/>", "<handler/>", "<listener/>",
                "<a><animate attributeName=\"href\" values=\"x\"/></a>", "<set attributeName=\"href\" to=\"x\"/>"})
        void scriptAndEmbeddingElementsAreRefused(String body) {
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, svg(body)), body);
        }

        @ParameterizedTest
        @ValueSource(strings = {"<rect onload=\"alert(1)\"/>", "<rect ONCLICK='x'/>", "<rect\nonmouseover = \"x\"/>",
                "<g/onload=alert(1)/>"})
        void eventHandlersAreRefused(String body) {
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, svg(body)), body);
        }

        @ParameterizedTest
        @ValueSource(strings = {"<a href=\"javascript:alert(1)\"><rect/></a>", "<a href=\"java\tscript:alert(1)\"><rect/></a>",
                "<a href=\"JaVaScRiPt:alert(1)\"/>", "<a xlink:href=\"java\nscript:x\"/>", "<a href=\"&#106;avascript:x\"/>",
                "<text>&#60;script&#62;</text>", "<a href=\"vbscript:x\"/>"})
        void scriptUrlsAndCharacterReferencesAreRefused(String body) {
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, svg(body)), body);
        }

        @ParameterizedTest
        @ValueSource(strings = {"<image href=\"https://evil.example/t.png\"/>", "<image xlink:href=\"//evil.example/t.png\"/>",
                "<use x:href=\"https://evil.example/s.svg#a\"/>", "<image href=evil.png/>", "<image href=\"/relative.png\"/>",
                "<rect style=\"fill:url(https://evil.example/x)\"/>", "<style>@import 'https://evil.example/x.css';</style>",
                "<style>rect{background:url(\"//evil.example/x\")}</style>"})
        void externalReferencesAreRefused(String body) {
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, svg(body)), body);
        }

        @Test
        void entityDeclarationsAreRefused() {
            String doc = "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x \"y\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>";
            assertEquals(BrandingException.INVALID, refusal(BrandAssetSlot.LOGO, doc.getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void localReferencesAndOrdinaryArtworkPass() {
            String body = "<defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#0f766e\"/></linearGradient></defs>"
                    + "<rect fill=\"url(#g)\" style=\"fill: url( '#g' )\"/><use href=\"#g\"/><use xlink:href='#g'/>"
                    + "<image href=\"data:image/png;base64,iVBORw0KGgo=\"/><settings-like-name/><setting/>"
                    + "<animateTransform attributeName=\"transform\" type=\"rotate\"/><text>Bacon, onion</text>";
            BrandAsset asset = BrandAssetValidator.validate(BrandAssetSlot.ICON, svg(body));
            assertEquals(BrandAssetValidator.SVG, asset.contentType());
        }

        @Test
        void refusalNamesTheReason() {
            BrandingException e = assertThrows(BrandingException.class,
                    () -> BrandAssetValidator.validate(BrandAssetSlot.LOGO, svg("<script/>")));
            assertTrue(e.getMessage().contains("<script>"), e.getMessage());
        }
    }

    @Test
    void hashIsSha256AndTheBytesAreCopied() {
        byte[] data = PNG.clone();
        BrandAsset asset = BrandAssetValidator.validate(BrandAssetSlot.LOGO, data);
        assertTrue(asset.hash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(BrandAssetValidator.hash(PNG), asset.hash());
        data[0] = 0;
        assertArrayEquals(PNG, asset.data());
    }
}
