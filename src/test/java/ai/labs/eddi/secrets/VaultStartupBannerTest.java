/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vault-disabled banner said audit entries "will NOT have HMAC integrity
 * signatures". {@code eddi.audit.hmac-key} signs the ledger without a master
 * key, so on a deployment that sets it the banner contradicted the ledger.
 */
@DisplayName("VaultStartupBanner")
class VaultStartupBannerTest {

    @Test
    @DisplayName("the disabled banner does not claim the audit ledger is unsigned")
    void disabledBannerNamesTheAuditKey() {
        assertFalse(VaultStartupBanner.DISABLED_BANNER.contains("will NOT have HMAC"), VaultStartupBanner.DISABLED_BANNER);
        assertTrue(VaultStartupBanner.DISABLED_BANNER.contains("EDDI_AUDIT_HMAC_KEY"), VaultStartupBanner.DISABLED_BANNER);
    }
}
