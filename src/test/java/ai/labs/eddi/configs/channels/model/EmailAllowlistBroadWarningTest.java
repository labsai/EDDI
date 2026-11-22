/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code email.allowlist-broad} save-time warning. */
class EmailAllowlistBroadWarningTest {

    private static boolean warns(String allowlist) {
        var settings = EmailChannelSettings.from(Map.of("mode", "trigger", "address", "invoices@mail.contoso.com", "senderAllowlist", allowlist));
        return EmailChannelSettings.warnings(settings).contains(EmailChannelSettings.WARNING_ALLOWLIST_BROAD);
    }

    @ParameterizedTest(name = "{0} is broad")
    @ValueSource(strings = {"*@*.co.uk", "*@github.io", "*@*.github.io", "bob@partner.com,*@mail.contoso.com", "*@contoso.com",
            "*@*.contoso.com"})
    void broad(String allowlist) {
        assertTrue(warns(allowlist));
    }

    @ParameterizedTest(name = "{0} is not broad")
    @ValueSource(strings = {"bob@partner.com", "*@partner.com", "*@*.partner.co.uk", "alice@contoso.com"})
    void narrow(String allowlist) {
        assertFalse(warns(allowlist));
    }
}
