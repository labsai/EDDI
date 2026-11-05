/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

import ai.labs.eddi.engine.alerts.model.DeliveryTarget.DeliveryType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A webhook URL is a secret: how alert delivery targets are masked on read,
 * labelled in the inbox and identified across saves.
 */
class DeliveryTargetTest {

    private static final String SECRET_URL = "https://Hooks.Example.com:8443/services/T000/B000/XXXXSECRET?token=abc";

    @Test
    void aLiteralWebhookUrlIsMaskedToItsOrigin() {
        var masked = DeliveryTarget.webhook(SECRET_URL, "${vault:hook}").withTargetId("t1").masked();
        assertEquals("https://hooks.example.com:8443" + DeliveryTarget.MASK_SUFFIX, masked.url());
        assertEquals(Boolean.TRUE, masked.urlConfigured());
        assertEquals("t1", masked.targetId(), "the id survives masking");
        assertTrue(DeliveryTarget.isMaskedUrl(masked.url()));
        assertFalse(masked.url().contains("SECRET"));
    }

    @Test
    void aUrlWithoutSchemeOrHostIsMaskedSafely() {
        assertEquals("https://?" + DeliveryTarget.MASK_SUFFIX, DeliveryTarget.webhook("relative/path", null).masked().url());
        assertEquals("https://?" + DeliveryTarget.MASK_SUFFIX, DeliveryTarget.webhook("https://bad host/x", null).masked().url());
        assertEquals("http://example.com" + DeliveryTarget.MASK_SUFFIX, DeliveryTarget.webhook(" HTTP://Example.com/x ", null).masked().url());
    }

    @Test
    void aVaultReferenceIsNotASecretAndIsShownAsItIs() {
        var target = DeliveryTarget.webhook(" ${vault:alerts-hook} ", null);
        assertTrue(target.urlIsVaultReference());
        var masked = target.masked();
        assertEquals(" ${vault:alerts-hook} ", masked.url());
        assertEquals(Boolean.TRUE, masked.urlConfigured());
        assertEquals("Webhook ${vault:alerts-hook}", target.label());
        assertFalse(DeliveryTarget.webhook("${vault:unclosed", null).urlIsVaultReference());
    }

    @Test
    void slackTargetsAndEmptyWebhooksAreUnchangedByMasking() {
        var slack = DeliveryTarget.slack("C0123").masked();
        assertEquals("C0123", slack.channelId());
        assertNull(slack.urlConfigured());
        assertEquals("Slack C0123", DeliveryTarget.slack("C0123").label());

        assertNull(DeliveryTarget.webhook(null, null).masked().url());
        assertEquals(" ", DeliveryTarget.webhook(" ", null).masked().url());
        assertNull(DeliveryTarget.webhook(" ", null).masked().urlConfigured());
        assertFalse(DeliveryTarget.webhook(null, null).urlIsVaultReference());
    }

    @Test
    void theInboxLabelShowsTheHostOnly() {
        assertEquals("Webhook hooks.example.com", DeliveryTarget.webhook(SECRET_URL, null).label());
        assertEquals("Webhook ?", DeliveryTarget.webhook("relative", null).label());
        assertEquals("Webhook ?", DeliveryTarget.webhook("https://bad host/", null).label());
    }

    @Test
    void hostOfReadsMaskedAndLiteralUrls() {
        assertEquals("hooks.example.com", DeliveryTarget.hostOf(SECRET_URL));
        assertEquals("hooks.example.com", DeliveryTarget.hostOf("https://hooks.example.com" + DeliveryTarget.MASK_SUFFIX));
        assertNull(DeliveryTarget.hostOf("relative"));
        assertNull(DeliveryTarget.hostOf("https://bad host/"));
        assertNull(DeliveryTarget.hostOf(null));
        assertFalse(DeliveryTarget.isMaskedUrl(null));
        assertFalse(DeliveryTarget.isMaskedUrl(SECRET_URL));
    }

    @Test
    void theKeyIsStableAndNeverTheUrlItself() {
        var a = DeliveryTarget.webhook(SECRET_URL, null);
        assertEquals(a.key(), DeliveryTarget.webhook(SECRET_URL, "other-secret").key(), "the secret does not change the identity");
        assertNotEquals(a.key(), DeliveryTarget.webhook(SECRET_URL + "x", null).key());
        assertEquals(16, a.key().length());
        assertEquals("t" + a.key(), a.derivedTargetId());
        assertEquals(DeliveryTarget.slack("C1").key(), DeliveryTarget.slack("C1").withUrl("ignored").key(), "a Slack key is its channel");
    }

    @Test
    void storageDropsTheReadOnlyFlag() {
        var read = new DeliveryTarget(DeliveryType.WEBHOOK, null, "https://x.example/***", null, true, "t9");
        var stored = read.forStorage();
        assertNull(stored.urlConfigured());
        assertEquals("t9", stored.targetId());
        assertEquals("https://new.example/x", read.withUrl("https://new.example/x").url());
        assertEquals("t9", read.withUrl("https://new.example/x").targetId());
        assertNull(read.withTargetId(null).targetId());
    }
}
